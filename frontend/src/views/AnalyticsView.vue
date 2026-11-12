<script setup>
import PageHelp from '../components/PageHelp.vue'
import SAnimatedNumber from '../components/ui/SAnimatedNumber.vue'
import {ref,computed,onMounted,nextTick,watch,onUnmounted} from 'vue'
import * as echarts from 'echarts'
import {getRooms} from '../api/room'
import {getHeatmap,getOverview} from '../api/stats'
import {statsQuery,validOverview,validHeatmap,usageHours,ratioText} from '../composables/statsForm.js'
import {overviewChartOptions,heatChartOption,roomBarOption} from '../composables/statsCharts.js'
import {createStatsRefresh} from '../composables/statsRefresh.js'
import {useThemeStore} from '../stores/theme'
import SSelect from '../components/ui/SSelect.vue'
import SDatePicker from '../components/ui/SDatePicker.vue'
import SBanner from '../components/ui/SBanner.vue'
import SButton from '../components/ui/SButton.vue'

const themeStore=useThemeStore()
const chartTab=ref('heat'),chartRoot=ref(null)
const chartTabs=[{key:'heat',label:'座位热力图'},{key:'trend',label:'运营趋势'},{key:'rooms',label:'空间对比'},{key:'top',label:'热门座位'}]
const rooms=ref([]),roomsError=ref(''),roomId=ref(null),date=ref(''),formError=ref('')
const roomOptions=computed(()=>[{id:null,name:'全校'},...rooms.value])
const overview=ref(null),heatmap=ref(null),overviewError=ref(''),heatError=ref('')
const overviewLoading=ref(false),heatLoading=ref(false),attemptedQuery=ref(null),heatQuery=ref(null),statsVisible=ref(true)
const heatApplicable=computed(()=>Boolean(attemptedQuery.value?.room_id))
const heatEl=ref(null),trendEl=ref(null),peakEl=ref(null),topEl=ref(null),roomBarEl=ref(null)
let heatChart,trendChart,peakChart,topChart,roomBarChart,resizeObserver
const roomBars=ref([]),roomBarsLoading=ref(false),roomBarsError=ref('')
let roomBarSequence=0
let disposed=false,selectionSequence=0,roomsRequest=0,heatCompletion=Promise.resolve(null),autoDate=null
const motionMedia=window.matchMedia('(prefers-reduced-motion: reduce)')
const animateCharts=()=>!motionMedia.matches
const CHART=computed(()=>{const dark=themeStore.isDark;return{axisLine:dark?'#33453a':'#d7cab2',splitLine:dark?'#24312a':'#efe7d8',label:dark?'#8d9c90':'#7b877d',title:dark?'#f1f5ee':'#4f5f54',tooltipBg:dark?'#1a241e':'#23372d',heatRange:dark?['#202c25','#7fb391']:['#f3ecdd','#2f5d46'],heatBorder:dark?'#1a241e':'#fffdf8'}})
const scopeText=computed(()=>overview.value ? `${overview.value.scope.room_id===null?'全校':`空间 #${overview.value.scope.room_id}`} · 所选日 ${overview.value.scope.selected_date}` : '尚未读取统计快照')
const attemptedText=computed(()=>attemptedQuery.value ? `${attemptedQuery.value.room_id?`空间 #${attemptedQuery.value.room_id}`:'全校'} · ${attemptedQuery.value.date||'服务端校园今天'}` : '')
const heatAttemptedText=computed(()=>heatQuery.value?`空间 #${heatQuery.value.room_id} · ${heatQuery.value.date}`:attemptedText.value)
const dayMetrics=computed(()=>overview.value ? [
 {label:'当日成功创建',value:overview.value.day.created_count},
 {label:'当日真实取消',value:overview.value.day.cancelled_count},
 {label:'当日签到人次',value:overview.value.day.checkin_count},
 {label:'当日累计使用（人·小时）',text:usageHours(overview.value.day.usage_seconds).toFixed(2)},
 {label:'当日有效违规',value:overview.value.day.active_violation_count}
] : [])
function snapshotText(value){return value ? new Date(value).toLocaleString('zh-CN',{timeZone:'Asia/Shanghai',hour12:false})+'（校园时区）' : '尚未读取'}
function createChart(element,height=320){if(!element?.clientWidth)return null;return echarts.init(element,null,{width:element.clientWidth,height:element.clientHeight||height})}
async function renderOverview(){
 const data=overview.value
 if(!data)return
 await nextTick()
 if(disposed||data!==overview.value)return
 const options=overviewChartOptions(data,CHART.value,animateCharts())
 if(!trendChart)trendChart=createChart(trendEl.value)
 if(!peakChart)peakChart=createChart(peakEl.value)
 if(!topChart)topChart=createChart(topEl.value,340)
 trendChart?.setOption(options.trend,true);peakChart?.setOption(options.peak,true);topChart?.setOption(options.top,true)
 resizeAll()
}
async function renderHeatmap(){
 const data=heatmap.value
 if(!data)return
 await nextTick()
 if(disposed||data!==heatmap.value)return
 if(!heatChart)heatChart=createChart(heatEl.value,380)
 heatChart?.setOption(heatChartOption(data,CHART.value,animateCharts()),true)
 resizeAll()
}
async function loadRoomBars(){
 const sequence=++roomBarSequence
 const targets=(roomId.value?rooms.value.filter(room=>room.id===roomId.value):rooms.value.filter(room=>room.enabled!==false))
 roomBarsLoading.value=true;roomBarsError.value=''
 try{
  const settled=await Promise.allSettled(targets.map(async room=>{
   const query={room_id:room.id,...(date.value?{date:date.value}:{})}
   const {data}=await getOverview(query)
   if(!validOverview(data,query))throw new Error(`${room.name} 统计回执不完整`)
   return {id:room.id,name:room.name,usage_seconds:data.day.usage_seconds,checkin_count:data.day.checkin_count,created_count:data.day.created_count,snapshot_at:data.snapshot_at,selected_date:data.scope.selected_date}
  }))
  if(disposed||sequence!==roomBarSequence)return
  roomBars.value=settled.filter(result=>result.status==='fulfilled').map(result=>result.value)
  const failed=settled.filter(result=>result.status==='rejected')
  if(!roomBars.value.length)roomBarsError.value=failed[0]?.reason?.message||'空间统计读取失败'
  else if(failed.length)roomBarsError.value=`${failed.length} 个空间读取失败，下列仅展示已成功读取的空间。`
  await renderRoomBars()
 } finally { if(!disposed&&sequence===roomBarSequence)roomBarsLoading.value=false }
}
async function renderRoomBars(){
 await nextTick()
 if(disposed||!roomBarEl.value?.clientWidth)return
 if(!roomBarChart)roomBarChart=echarts.init(roomBarEl.value,null,{width:roomBarEl.value.clientWidth,height:roomBarEl.value.clientHeight||340})
 roomBarChart.setOption(roomBarOption(roomBars.value,CHART.value,animateCharts()),true)
 resizeAll()
}
async function loadRooms(){
 const sequence=++roomsRequest;roomsError.value=''
 try{const response=await getRooms();if(disposed||sequence!==roomsRequest)return;rooms.value=response.data||[]}
 catch(error){if(!disposed&&sequence===roomsRequest)roomsError.value=error.message||'空间读取失败'}
}
function selectHeat(query){heatQuery.value=query?{...query}:null;heatCompletion=statsRefresh.select('heat',query,{refresh:false});return heatCompletion}
const statsRefresh=createStatsRefresh({onVisibility:visible=>{statsVisible.value=visible},resources:{
 overview:{
  read:query=>getOverview(query),
  success:async(response,query)=>{
   if(!validOverview(response.data,query))throw new Error('统计回执不完整或与查询不符，请重新读取')
   overview.value=response.data
   // An omitted date remains server-campus-today on later refreshes and midnight rollover.
   if(!query.date){const selected=response.data.scope.selected_date;if(!date.value||date.value===autoDate)date.value=selected;autoDate=selected;if(query.room_id)selectHeat({...query,date:selected})}
   await renderOverview();return response.data
  },
  failure:error=>{overviewError.value=error.message||'统计读取失败'},
  loading:value=>{overviewLoading.value=value;if(value)overviewError.value=''}
 },
 heat:{
  read:query=>getHeatmap(query.room_id,query.date),
  success:async(response,query)=>{if(!validHeatmap(response.data,query))throw new Error('热力图回执不完整或与查询不符，请重新读取');heatmap.value=response.data;await renderHeatmap();return response.data},
  failure:error=>{heatError.value=error.message||'热力图读取失败'},
  loading:value=>{heatLoading.value=value;if(value)heatError.value=''}
 }
}})
async function applyQuery(query){
 const selection=++selectionSequence
 attemptedQuery.value={...query};overviewError.value='';heatError.value=''
 const completion=statsRefresh.select('overview',query)
 const initialHeat=selectHeat(query.room_id&&query.date?query:null)
 if(query.room_id&&query.date)await statsRefresh.refresh('heat')
 const result=await completion
 if(disposed||selection!==selectionSequence)return
 if(query.room_id&&!query.date&&result)await heatCompletion
 else await initialHeat
}
function applyFilters(){formError.value='';try{return applyQuery(statsQuery({room_id:roomId.value,date:date.value}))}catch(error){formError.value=error.message}}
function retryOverview(){return statsRefresh.refresh('overview')}
function retryHeat(){return statsRefresh.refresh('heat')}
function resizeAll(){for(const chart of [heatChart,trendChart,peakChart,topChart,roomBarChart]){const element=chart?.getDom();if(element?.clientWidth&&element.clientHeight)chart.resize({width:element.clientWidth,height:element.clientHeight})}}
function redraw(){renderOverview();renderHeatmap();if(chartTab.value==='rooms')loadRoomBars()}
watch(()=>themeStore.resolvedTheme,redraw)
watch(chartTab,async()=>{await nextTick();if(disposed)return;if(chartTab.value==='rooms')await loadRoomBars();await Promise.all([renderOverview(),renderHeatmap()]);if(!disposed)resizeAll()})
onMounted(()=>{resizeObserver=new ResizeObserver(resizeAll);resizeObserver.observe(chartRoot.value);motionMedia.addEventListener('change',redraw);window.addEventListener('resize',resizeAll);loadRooms();applyQuery({});statsRefresh.start()})
onUnmounted(()=>{disposed=true;++selectionSequence;++roomsRequest;++roomBarSequence;statsRefresh.stop();resizeObserver?.disconnect();motionMedia.removeEventListener('change',redraw);window.removeEventListener('resize',resizeAll);for(const chart of [heatChart,trendChart,peakChart,topChart,roomBarChart])chart?.dispose()})
</script>

<template>
 <div v-reveal class="page-view">
  <header class="view-heading has-page-help"><div class="heading-copy" data-page-title><h1>热力图与统计</h1><p class="heading-sub">真实使用记录、当日运营与近七日时段分布</p></div><PageHelp title="指标说明"><ul class="tips">
   <li>所选日的创建按预约创建时刻计数，取消按实际取消时刻计数；签到与使用时长取真实使用记录。</li>
   <li>使用时长按校园日期、小时切片；尚未结束的使用截至快照时刻，且不超过预约结束。</li>
   <li>当前使用比例为开放可用座位中的使用中数量 ÷ 开放可用座位数；分母为零时显示不适用。</li>
   <li>近七日以快照当天为末日，不随所选日移动。有效违规不含已撤销记录和一般提醒。</li>
   <li>热力图的 1 表示该小时与真实使用记录相交，0 表示无相交记录；不能证明人在座，也不表示预约概率。</li>
  </ul></PageHelp></header>
  <div class="split analytics-split"><div class="split-left">
   <section class="card stats-panel">
    <div class="card-title-row"><h3>统计参数</h3><span class="muted">选择范围后查看快照</span></div>
    <div class="stats-filters" role="group" aria-label="统计筛选">
     <div class="filter-row"><label>空间</label><SSelect v-model="roomId" :options="roomOptions" label-key="name" value-key="id" /></div>
     <div class="filter-row"><label>校园日期</label><SDatePicker v-model="date" /></div>
     <div class="filter-actions"><SButton type="button" @click="applyFilters">应用筛选</SButton><SButton type="button" variant="ghost" @click="date='';applyFilters()">校园今天</SButton></div>
    </div>
    <SBanner v-if="formError" type="error" :title="formError" />
    <SBanner v-if="roomsError" type="error" :title="roomsError"><SButton size="sm" @click="loadRooms">重读空间</SButton></SBanner>
    <div class="snapshot-block">
     <div class="snapshot-head"><h4>运营快照</h4><span class="muted">{{ scopeText }}<template v-if="overview"> · 快照 {{ snapshotText(overview.snapshot_at) }}</template></span></div>
     <p v-if="overviewLoading&&!overview" role="status">正在读取统计快照…</p>
     <SBanner v-if="overviewError" type="error" :title="overviewError"><p>本次查询：{{ attemptedText }}。保留最近成功数据及其原范围。</p><SButton size="sm" @click="retryOverview">重试原查询</SButton></SBanner>
     <template v-if="overview">
      <div class="kpi-grid"><div v-for="metric in dayMetrics" :key="metric.label" class="kpi"><div class="kpi-num"><span v-if="metric.text!==undefined">{{ metric.text }}</span><SAnimatedNumber v-else :value="metric.value" /></div><div class="kpi-label">{{ metric.label }}</div></div></div>
      <dl class="current-metrics"><dt>当前开放可用座位</dt><dd>{{ overview.current.open_available_seats }}</dd><dt>其中使用中</dt><dd>{{ overview.current.in_use_now }}</dd><dt>当前使用比例</dt><dd>{{ ratioText(overview.current.usage_ratio) }}</dd><dt>投诉待办</dt><dd>{{ overview.current.complaint_todo_count }}</dd><dt>投诉待确认</dt><dd>{{ overview.current.complaint_awaiting_count }}</dd></dl>
     </template>
     <p v-else-if="!overviewLoading" class="muted">尚无成功统计快照。</p>
    </div>
   </section>
  </div><div ref="chartRoot" class="split-right">
   <nav class="chart-tabs" aria-label="统计视图"><button v-for="tab in chartTabs" :key="tab.key" type="button" :class="{active:chartTab===tab.key}" :aria-pressed="chartTab===tab.key" @click="chartTab=tab.key">{{ tab.label }}</button></nav>
   <section v-show="chartTab==='heat'" class="card chart-panel"><div class="card-title-row"><h3>座位 × 小时 使用记录</h3></div>
    <p v-if="!heatApplicable" class="muted">请选择具体空间并应用筛选以查看热力图。</p>
    <p v-if="heatLoading" role="status" class="muted">正在读取热力图；已有数据保留为上次成功快照。</p>
    <SBanner v-if="heatError" type="error" :title="heatError"><p>本次查询：{{ heatAttemptedText }}。</p><SButton size="sm" @click="retryHeat">重试热力原查询</SButton></SBanner>
    <div v-show="heatApplicable&&heatmap"><p v-if="heatmap" class="snapshot-scope">最近成功快照：{{ heatmap.room.name }} · {{ heatmap.date }} · {{ snapshotText(heatmap.snapshot_at) }}</p><p class="muted">包括历史停用座位；单元格仅表示真实使用记录与该小时是否相交。</p><p v-if="heatmap&&!heatmap.seats.length" class="muted">该空间没有座位记录。</p><div class="responsive-scroll" tabindex="0" aria-label="座位时段使用记录，可左右滑动"><div ref="heatEl" class="chart chart-heat chart-wide"></div></div></div>
    <p v-if="heatApplicable&&!heatmap&&!heatLoading&&!heatError" class="muted">尚无成功热力图快照。</p>
   </section>
   <section v-show="chartTab==='trend'" class="card chart-panel"><div class="card-title-row"><h3>近七日运营趋势与时段</h3></div><p class="muted">趋势按各指标发生日计数；时段分布累计近七日真实使用小时与签到人次。</p><p v-if="!overview" class="muted">尚无成功统计快照。</p><div v-show="overview" class="charts-row"><div ref="trendEl" class="chart"></div><div ref="peakEl" class="chart"></div></div></section>
   <section v-show="chartTab==='rooms'" class="card chart-panel"><div class="card-title-row"><h3>空间使用对比</h3></div><p v-if="roomBarsLoading" role="status" class="muted">正在读取各空间统计…</p><SBanner v-if="roomBarsError" type="error" :title="roomBarsError"><SButton size="sm" @click="loadRoomBars">重试空间对比</SButton></SBanner><p v-if="roomBars.length" class="snapshot-scope">{{ roomBars[0]?.selected_date }} · {{ roomBars.length }} 个空间 · 使用小时与签到人次</p><p v-if="!roomBarsLoading&&!roomBars.length&&!roomBarsError" class="muted">暂无可比较的空间统计。</p><div v-show="roomBars.length" class="responsive-scroll" tabindex="0" aria-label="空间使用对比柱状图，可左右滑动"><div ref="roomBarEl" class="chart chart-bar"></div></div></section>
   <section v-show="chartTab==='top'" class="card chart-panel"><div class="card-title-row"><h3>近七日热门座位 Top 10</h3></div><p class="muted">按真实使用时长排序，保留历史停用座位。</p><p v-if="overview&&!overview.top_seats.length" class="muted">该范围没有使用记录。</p><p v-if="!overview" class="muted">尚无成功统计快照。</p><div v-show="overview" class="responsive-scroll" tabindex="0" aria-label="热门座位图，可左右滑动"><div ref="topEl" class="chart chart-bar"></div></div></section>
  </div></div>
 </div>
</template>

<style scoped>
.analytics-split .split-left{grid-template-columns:minmax(0,1fr)}
.stats-panel{display:grid;gap:16px;min-width:0}
.stats-filters{display:grid;grid-template-columns:minmax(0,1fr) minmax(0,1fr) auto;gap:12px;align-items:end}
.filter-actions{display:flex;gap:8px;flex-wrap:wrap;align-items:end}
.snapshot-block{border-top:1px solid var(--hairline);padding-top:16px;display:grid;gap:14px}
.snapshot-head{display:flex;align-items:baseline;justify-content:space-between;gap:12px;flex-wrap:wrap}
.snapshot-head h4{font-family:var(--font-display);font-size:20px;color:var(--text-1)}
.snapshot-head .muted{font-size:var(--fs-caption);color:var(--text-3)}
@media(max-width:820px){.stats-filters{grid-template-columns:1fr}}
.stats-panel .kpi-grid{grid-template-columns:repeat(5,minmax(0,1fr))}
@media(max-width:1000px){.stats-panel .kpi-grid{grid-template-columns:repeat(2,minmax(0,1fr))}}
.chart{width:100%;height:360px}.chart-heat{height:380px}.chart-wide{min-width:720px}.chart-bar{min-width:620px;height:340px}
.charts-row{display:grid;grid-template-columns:minmax(0,1fr) minmax(0,1fr);gap:18px}.charts-row>.chart{min-width:0}.snapshot-scope{overflow-wrap:anywhere;color:var(--text-3);font-size:var(--fs-caption);line-height:1.65}
.current-metrics{display:grid;grid-template-columns:1fr auto;gap:8px 12px;font-size:var(--fs-caption)}.current-metrics dt{color:var(--text-3)}.current-metrics dd{margin:0;font-weight:600}
@media(max-width:720px){.chart{height:340px}.chart-heat{height:360px}.chart-bar{height:340px}}
@media(max-width:1100px){.charts-row{grid-template-columns:1fr}}
</style>
