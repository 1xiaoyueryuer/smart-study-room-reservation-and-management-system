<script setup>
import PageHelp from '../components/PageHelp.vue'
import SAnimatedNumber from '../components/ui/SAnimatedNumber.vue'
import {ref,computed,onMounted,nextTick,watch,onUnmounted} from 'vue'
import * as echarts from 'echarts'
import {getRooms} from '../api/room'
import {getHeatmap,getOverview} from '../api/stats'
import {statsQuery,validOverview,validHeatmap,usageHours,ratioText} from '../composables/statsForm.js'
import {overviewChartOptions,heatChartOption} from '../composables/statsCharts.js'
import {useThemeStore} from '../stores/theme'
import SSelect from '../components/ui/SSelect.vue'
import SDatePicker from '../components/ui/SDatePicker.vue'
import SBanner from '../components/ui/SBanner.vue'
import SButton from '../components/ui/SButton.vue'

const themeStore=useThemeStore()
const chartTab=ref('heat'),chartRoot=ref(null)
const chartTabs=[{key:'heat',label:'座位热力图'},{key:'trend',label:'运营趋势'},{key:'top',label:'热门座位'}]
const rooms=ref([]),roomsError=ref(''),roomId=ref(null),date=ref(''),formError=ref('')
const roomOptions=computed(()=>[{id:null,name:'全校'},...rooms.value])
const overview=ref(null),heatmap=ref(null),overviewError=ref(''),heatError=ref('')
const overviewLoading=ref(false),heatLoading=ref(false),attemptedQuery=ref(null)
const heatApplicable=computed(()=>Boolean(attemptedQuery.value?.room_id))
const heatEl=ref(null),trendEl=ref(null),peakEl=ref(null),topEl=ref(null)
let heatChart,trendChart,peakChart,topChart,resizeObserver
let disposed=false,selectionSequence=0,overviewRequest=0,heatRequest=0,roomsRequest=0
const motionMedia=window.matchMedia('(prefers-reduced-motion: reduce)')
const animateCharts=()=>!motionMedia.matches
const CHART=computed(()=>{const dark=themeStore.isDark;return{axisLine:dark?'#283547':'#d4dbe6',splitLine:dark?'#1c2635':'#eef1f6',label:dark?'#8393a7':'#75819a',title:dark?'#f1f5fa':'#46536a',tooltipBg:dark?'#151b24':'#1c2534',heatRange:dark?['#1b232f','#4f7cf6']:['#f4f6f9','#3b66da'],heatBorder:dark?'#151b24':'#ffffff'}})
const scopeText=computed(()=>overview.value ? `${overview.value.scope.room_id===null?'全校':`空间 #${overview.value.scope.room_id}`} · 所选日 ${overview.value.scope.selected_date}` : '尚未读取统计快照')
const attemptedText=computed(()=>attemptedQuery.value ? `${attemptedQuery.value.room_id?`空间 #${attemptedQuery.value.room_id}`:'全校'} · ${attemptedQuery.value.date||'服务端校园今天'}` : '')
const dayMetrics=computed(()=>overview.value ? [
 {label:'当日成功创建',value:overview.value.day.created_count},
 {label:'当日真实取消',value:overview.value.day.cancelled_count},
 {label:'当日签到人次',value:overview.value.day.checkin_count},
 {label:'当日使用小时',text:usageHours(overview.value.day.usage_seconds).toFixed(2)},
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
async function loadRooms(){
 const sequence=++roomsRequest;roomsError.value=''
 try{const response=await getRooms();if(disposed||sequence!==roomsRequest)return;rooms.value=response.data||[]}
 catch(error){if(!disposed&&sequence===roomsRequest)roomsError.value=error.message||'空间读取失败'}
}
async function loadOverview(query,selection){
 const sequence=++overviewRequest;overviewLoading.value=true;overviewError.value=''
 try{
  const response=await getOverview(query)
  if(disposed||selection!==selectionSequence||sequence!==overviewRequest)return null
  if(!validOverview(response.data,query))throw new Error('统计回执不完整或与查询不符，请重新读取')
  overview.value=response.data
  await renderOverview()
  return response.data
 }catch(error){if(!disposed&&selection===selectionSequence&&sequence===overviewRequest)overviewError.value=error.message||'统计读取失败';return null}
 finally{if(!disposed&&sequence===overviewRequest)overviewLoading.value=false}
}
async function loadHeatmap(query,selection){
 const sequence=++heatRequest;heatLoading.value=true;heatError.value=''
 try{
  const response=await getHeatmap(query.room_id,query.date)
  if(disposed||selection!==selectionSequence||sequence!==heatRequest)return
  if(!validHeatmap(response.data,query))throw new Error('热力图回执不完整或与查询不符，请重新读取')
  heatmap.value=response.data;await renderHeatmap()
 }catch(error){if(!disposed&&selection===selectionSequence&&sequence===heatRequest)heatError.value=error.message||'热力图读取失败'}
 finally{if(!disposed&&sequence===heatRequest)heatLoading.value=false}
}
async function applyQuery(query){
 const selection=++selectionSequence
 ++heatRequest;heatLoading.value=false;heatError.value=''
 attemptedQuery.value={...query}
 // The first default date comes from the server campus date, never a UTC browser date.
 const overviewPromise=loadOverview(query,selection)
 if(query.room_id&&query.date)await Promise.all([overviewPromise,loadHeatmap(query,selection)])
 else{
  const result=await overviewPromise
  if(disposed||selection!==selectionSequence)return
  if(!query.date&&result){if(!date.value)date.value=result.scope.selected_date;attemptedQuery.value={...query,date:result.scope.selected_date}}
  if(query.room_id&&result)await loadHeatmap({...query,date:result.scope.selected_date},selection)
 }
}
function applyFilters(){formError.value='';try{return applyQuery(statsQuery({room_id:roomId.value,date:date.value}))}catch(error){formError.value=error.message}}
function retryOverview(){if(attemptedQuery.value)return applyQuery({...attemptedQuery.value})}
function retryHeat(){if(attemptedQuery.value?.room_id&&attemptedQuery.value?.date)return loadHeatmap({...attemptedQuery.value},selectionSequence)}
function resizeAll(){for(const chart of [heatChart,trendChart,peakChart,topChart]){const element=chart?.getDom();if(element?.clientWidth&&element.clientHeight)chart.resize({width:element.clientWidth,height:element.clientHeight})}}
function redraw(){renderOverview();renderHeatmap()}
watch(()=>themeStore.resolvedTheme,redraw)
watch(chartTab,async()=>{await nextTick();if(disposed)return;await Promise.all([renderOverview(),renderHeatmap()]);if(!disposed)resizeAll()})
onMounted(()=>{resizeObserver=new ResizeObserver(resizeAll);resizeObserver.observe(chartRoot.value);motionMedia.addEventListener('change',redraw);window.addEventListener('resize',resizeAll);loadRooms();applyQuery({})})
onUnmounted(()=>{disposed=true;++selectionSequence;++overviewRequest;++heatRequest;++roomsRequest;resizeObserver?.disconnect();motionMedia.removeEventListener('change',redraw);window.removeEventListener('resize',resizeAll);for(const chart of [heatChart,trendChart,peakChart,topChart])chart?.dispose()})
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
   <section class="card responsive-compact"><div class="card-title-row"><h3>统计参数</h3></div>
    <div class="filter-group" role="group" aria-label="统计筛选">
     <div class="filter-row"><label>空间</label><SSelect v-model="roomId" :options="roomOptions" label-key="name" value-key="id" /></div>
     <div class="filter-row"><label>校园日期</label><SDatePicker v-model="date" /></div>
     <SButton type="button" @click="applyFilters">应用筛选</SButton>
     <SButton type="button" variant="ghost" @click="date='';applyFilters()">校园今天</SButton>
    </div>
    <SBanner v-if="formError" type="error" :title="formError" />
    <SBanner v-if="roomsError" type="error" :title="roomsError"><SButton size="sm" @click="loadRooms">重读空间</SButton></SBanner>
    <p class="muted">日期留空时由服务端选择校园今天；热力图需要具体空间。</p>
   </section>
   <section class="card responsive-compact"><div class="card-title-row"><h3>运营快照</h3></div>
    <p class="muted" role="status">{{ overviewLoading?'正在读取；已有数据保留为上次成功快照。':scopeText }}</p>
    <SBanner v-if="overviewError" type="error" :title="overviewError"><p>本次查询：{{ attemptedText }}。保留最近成功数据及其原范围。</p><SButton size="sm" @click="retryOverview">重试原查询</SButton></SBanner>
    <template v-if="overview"><p class="snapshot-scope">{{ scopeText }}<br />快照：{{ snapshotText(overview.snapshot_at) }}</p>
     <div class="kpi-grid"><div v-for="metric in dayMetrics" :key="metric.label" class="kpi"><div class="kpi-num"><span v-if="metric.text!==undefined">{{ metric.text }}</span><SAnimatedNumber v-else :value="metric.value" /></div><div class="kpi-label">{{ metric.label }}</div></div></div>
     <dl class="current-metrics"><dt>当前开放可用座位</dt><dd>{{ overview.current.open_available_seats }}</dd><dt>其中使用中</dt><dd>{{ overview.current.in_use_now }}</dd><dt>当前使用比例</dt><dd>{{ ratioText(overview.current.usage_ratio) }}</dd><dt>投诉待办</dt><dd>{{ overview.current.complaint_todo_count }}</dd><dt>投诉待确认</dt><dd>{{ overview.current.complaint_awaiting_count }}</dd></dl>
     <p class="muted">当前指标均取上方快照时刻；投诉待办为已提交及处理中，待确认单列。</p>
    </template><p v-else-if="!overviewLoading" class="muted">尚无成功统计快照。</p>
   </section>
  </div><div ref="chartRoot" class="split-right">
   <nav class="chart-tabs" aria-label="统计视图"><button v-for="tab in chartTabs" :key="tab.key" type="button" :class="{active:chartTab===tab.key}" :aria-pressed="chartTab===tab.key" @click="chartTab=tab.key">{{ tab.label }}</button></nav>
   <section v-show="chartTab==='heat'" class="card chart-panel"><div class="card-title-row"><h3>座位 × 小时 使用记录</h3></div>
    <p v-if="!heatApplicable" class="muted">请选择具体空间并应用筛选以查看热力图。</p>
    <p v-if="heatLoading" role="status" class="muted">正在读取热力图；已有数据保留为上次成功快照。</p>
    <SBanner v-if="heatError" type="error" :title="heatError"><p>本次查询：{{ attemptedText }}。</p><SButton size="sm" @click="retryHeat">重试热力原查询</SButton></SBanner>
    <div v-show="heatApplicable&&heatmap"><p v-if="heatmap" class="snapshot-scope">最近成功快照：{{ heatmap.room.name }} · {{ heatmap.date }} · {{ snapshotText(heatmap.snapshot_at) }}</p><p class="muted">包括历史停用座位；单元格仅表示真实使用记录与该小时是否相交。</p><p v-if="heatmap&&!heatmap.seats.length" class="muted">该空间没有座位记录。</p><div class="responsive-scroll" tabindex="0" aria-label="座位时段使用记录，可左右滑动"><div ref="heatEl" class="chart chart-heat chart-wide"></div></div></div>
    <p v-if="heatApplicable&&!heatmap&&!heatLoading&&!heatError" class="muted">尚无成功热力图快照。</p>
   </section>
   <section v-show="chartTab==='trend'" class="card chart-panel"><div class="card-title-row"><h3>近七日运营趋势与时段</h3></div><p v-if="overview" class="snapshot-scope">{{ overview.scope.room_id===null?'全校':`空间 #${overview.scope.room_id}` }} · {{ overview.scope.trend_from }} 至 {{ overview.scope.trend_to }} · 快照 {{ snapshotText(overview.snapshot_at) }}</p><p class="muted">趋势按各指标发生日计数；时段分布累计近七日真实使用小时与签到人次。</p><p v-if="!overview" class="muted">尚无成功统计快照。</p><div v-show="overview" class="charts-row"><div ref="trendEl" class="chart"></div><div ref="peakEl" class="chart"></div></div></section>
   <section v-show="chartTab==='top'" class="card chart-panel"><div class="card-title-row"><h3>近七日热门座位 Top 10</h3></div><p v-if="overview" class="snapshot-scope">{{ overview.scope.room_id===null?'全校':`空间 #${overview.scope.room_id}` }} · {{ overview.scope.trend_from }} 至 {{ overview.scope.trend_to }} · 快照 {{ snapshotText(overview.snapshot_at) }}</p><p class="muted">按真实使用时长排序，保留历史停用座位。</p><p v-if="overview&&!overview.top_seats.length" class="muted">该范围没有使用记录。</p><p v-if="!overview" class="muted">尚无成功统计快照。</p><div v-show="overview" class="responsive-scroll" tabindex="0" aria-label="热门座位图，可左右滑动"><div ref="topEl" class="chart chart-bar"></div></div></section>
  </div></div>
 </div>
</template>

<style scoped>
.chart{width:100%;height:360px}.chart-heat{height:380px}.chart-wide{min-width:720px}.chart-bar{min-width:620px;height:340px}
.charts-row{display:grid;grid-template-columns:minmax(0,1fr) minmax(0,1fr);gap:18px}.charts-row>.chart{min-width:0}.snapshot-scope{overflow-wrap:anywhere;color:var(--text-3);font-size:var(--fs-caption);line-height:1.65}
.current-metrics{display:grid;grid-template-columns:1fr auto;gap:8px 12px;font-size:var(--fs-caption)}.current-metrics dt{color:var(--text-3)}.current-metrics dd{margin:0;font-weight:600}
@media(max-width:720px){.chart{height:340px}.chart-heat{height:360px}.chart-bar{height:340px}}
@media(max-width:1100px){.charts-row{grid-template-columns:1fr}}
</style>
