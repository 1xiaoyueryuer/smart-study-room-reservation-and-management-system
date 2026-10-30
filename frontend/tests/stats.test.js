import test,{afterEach} from 'node:test'
import assert from 'node:assert/strict'
import vm from 'node:vm'
import {readFile} from 'node:fs/promises'
import {setImmediate as nextTurn} from 'node:timers/promises'
import * as vue from 'vue'
import * as ssr from 'vue/server-renderer'
import {parse,compileTemplate} from '@vue/compiler-sfc'
import * as statsForm from '../src/composables/statsForm.js'
import * as statsCharts from '../src/composables/statsCharts.js'
import {createStatsRefresh} from '../src/composables/statsRefresh.js'
import {statsClock} from './helpers/statsClock.js'
const livePages=[]
afterEach(()=>{for(const page of livePages.splice(0)){page.cleanup.forEach(fn=>fn());assert.equal(page.clock.timers.size,0);assert.equal(page.clock.listeners.size,0)}})

const metric=()=>({created_count:0,cancelled_count:0,checkin_count:0,usage_seconds:0,active_violation_count:0})
const overviewData=(query={},extra={})=>({snapshot_at:'2026-10-30T23:45:00.123456+08:00',campus_zone:'Asia/Shanghai',scope:{room_id:query.room_id??null,selected_date:query.date||'2026-10-30',trend_from:'2026-10-24',trend_to:'2026-10-30'},day:metric(),current:{open_available_seats:0,in_use_now:0,usage_ratio:null,complaint_todo_count:0,complaint_awaiting_count:0},trend:Array.from({length:7},(_,index)=>({date:`2026-10-${24+index}`,...metric()})),peak:Array.from({length:24},(_,hour)=>({hour:String(hour).padStart(2,'0')+':00',usage_seconds:0,checkin_count:0})),top_seats:[],...extra})
const heatData=(room=1,date='2026-10-30',extra={})=>({snapshot_at:'2026-10-30T23:45:01+08:00',campus_zone:'Asia/Shanghai',date,room:{id:room,name:`历史空间${room}`},seats:[{id:room*10,seat_no:'A1',row_no:1,col_no:1}],hours:Array.from({length:24},(_,h)=>String(h).padStart(2,'0')+':00'),ends:Array.from({length:24},(_,h)=>String(h+1).padStart(2,'0')+':00'),cells:[Array.from({length:24},(_,h)=>h===0?1:0)],...extra})
const deferred=()=>{let resolve,reject;const promise=new Promise((yes,no)=>{resolve=yes;reject=no});return{promise,resolve,reject}}

async function component(kind='analytics',overrides={}){
 const calls=[],cleanup=[],mounted=[],charts=[],listeners=new Map(),clock=statsClock(),theme=vue.reactive({isDark:false,resolvedTheme:'light'}),auth=vue.reactive({isAdmin:true,isOrdinary:false,user:{id:7,username:'admin'},roleLabel:'管理员'})
 const api={getOverview:async(query={})=>{calls.push(['overview',structuredClone(query)]);return{data:overviewData(query)}},getHeatmap:async(room,date)=>{calls.push(['heat',room,date]);return{data:heatData(room,date)}},...overrides}
 const motion={matches:false,addEventListener:(key,fn)=>listeners.set('motion:'+key,fn),removeEventListener:key=>listeners.delete('motion:'+key)}
 const context=vm.createContext({console,window:{matchMedia:()=>motion,addEventListener:(key,fn)=>listeners.set(key,fn),removeEventListener:key=>listeners.delete(key)},ResizeObserver:class{observe(){}disconnect(){}},setInterval:()=>{calls.push(['unread timer']);return 1},clearInterval(){}})
 const filename=kind==='analytics'?'AnalyticsView':'HomeView',source=(await readFile(new URL(`../src/views/${filename}.vue`,import.meta.url),'utf8')).split('<script setup>')[1].split('</script>')[0]
 const exports=kind==='analytics'?'rooms,roomsError,roomOptions,roomId,date,overview,heatmap,overviewError,heatError,overviewLoading,heatLoading,attemptedQuery,attemptedText,formError,applyFilters,applyQuery,retryOverview,retryHeat,loadRooms,chartTab,chartTabs,chartRoot,heatEl,trendEl,peakEl,topEl,dayMetrics,scopeText,redraw,heatApplicable,snapshotText,ratioText,resizeAll,statsRefresh,heatQuery,statsVisible,heatAttemptedText':'statsOverview,statsError,statsLoading,loadStats,openAvailableSeats,inUseNow,currentUsageText,statsSnapshotText,statsRefresh,statsVisible'
 const module=new vm.SourceTextModule(source+'\nexport {'+exports+'}',{context})
 await module.link(specifier=>{
  const values=specifier==='vue'?{...vue,onMounted:fn=>mounted.push(fn),onUnmounted:fn=>cleanup.push(fn),watch:(...args)=>{const stop=vue.watch(...args);cleanup.push(stop);return stop}}:specifier==='echarts'?{init:(element,_theme,dimensions)=>{const chart={element,dimensions,resizes:[],options:[],disposed:false,setOption(option){this.options.push(option)},getDom:()=>element,resize(dimensions){this.resizes.push(dimensions)},dispose(){this.disposed=true}};charts.push(chart);return chart}}:specifier==='../api/stats'?api:specifier==='../api/room'?{getRooms:async()=>({data:[{id:1,name:'历史空间1',enabled:false}]}),getOpeningWindows:async()=>({data:{windows:[],closed_reasons:[]}})}:specifier.endsWith('/statsForm.js')?statsForm:specifier.endsWith('/statsCharts.js')?statsCharts:specifier==='../stores/theme'?{useThemeStore:()=>theme}:specifier==='../stores/auth'?{useAuthStore:()=>auth}:specifier==='../stores/notification'?{useNotificationStore:()=>({unread:0,refresh(){}})}:specifier==='vue-router'?{useRouter:()=>({push(){}})}:{default:{}}
  const linked=specifier.endsWith('/statsRefresh.js')?{createStatsRefresh:options=>createStatsRefresh({...options,...clock.options})}:values
  return new vm.SyntheticModule(Object.keys(linked),function(){for(const[key,value]of Object.entries(linked))this.setExport(key,value)},{context})
 });await module.evaluate()
 const result={...module.namespace,calls,charts,mounted,cleanup,listeners,clock,context,theme,auth,filename}
 if(kind==='analytics')for(const key of ['heatEl','trendEl','peakEl','topEl','chartRoot'])result[key].value={clientWidth:800,clientHeight:360}
 if(kind==='home')result.statsRefresh.select('overview',{})
 result.statsRefresh.start();livePages.push(result);return result
}
async function render(p){const source=await readFile(new URL(`../src/views/${p.filename}.vue`,import.meta.url),'utf8'),{descriptor}=parse(source),compiled=compileTemplate({source:descriptor.template.content,filename:p.filename+'.vue',id:'stats',ssr:true,ssrCssVars:[]});assert.equal(compiled.errors.length,0);const module=new vm.SourceTextModule(compiled.code,{context:p.context});await module.link(specifier=>{const values=specifier==='vue'?vue:ssr;return new vm.SyntheticModule(Object.keys(values),function(){for(const[key,value]of Object.entries(values))this.setExport(key,value)},{context:p.context})});await module.evaluate();const app=vue.createSSRApp({setup:()=>p,ssrRender:module.namespace.ssrRender});for(const name of ['PageHelp','SAnimatedNumber','SSelect','SDatePicker','SBanner','SButton','StudyTips','AppIcon'])app.component(name,{render:()=>null});app.directive('reveal',{});return ssr.renderToString(app)}

test('statistics adapters use exact GET filters and keep errors observable by the caller',async()=>{
 const calls=[],context=vm.createContext({}),module=new vm.SourceTextModule(await readFile(new URL('../src/api/stats.js',import.meta.url),'utf8'),{context});await module.link(()=>new vm.SyntheticModule(['default'],function(){this.setExport('default',{get:(...args)=>calls.push(args)})},{context}));await module.evaluate();const query={room_id:2,date:'2026-10-29'};module.namespace.getOverview(query);module.namespace.getHeatmap(2,query.date);assert.equal(calls[0][0],'/stats/overview');assert.equal(calls[0][1].params,query);assert.equal(calls[1][0],'/stats/heatmap');assert.equal(calls[1][1].params.room_id,2);assert.equal(calls[1][1].params.date,query.date)
})

test('campus date crosses UTC midnight correctly; historical ISO dates and malformed filters are distinguished',()=>{
 assert.equal(statsForm.campusDateAt('2026-10-29T16:01:00Z'),'2026-10-30');assert.equal(statsForm.validStatsDate('1582-10-10'),true);assert.equal(statsForm.validStatsDate('0001-01-01'),true);for(const date of ['0000-01-01','2026-02-29','2026-1-01'])assert.equal(statsForm.validStatsDate(date),false);assert.deepEqual(statsForm.statsQuery({room_id:null,date:''}),{});assert.throws(()=>statsForm.statsQuery({room_id:0}),/空间/);assert.throws(()=>statsForm.statsQuery({date:'2026-02-29'}),/日期/)
})

test('overview guard enforces requested scope, seven campus days, 24 hours and zero versus null',()=>{
 const data=overviewData({room_id:1,date:'1582-10-10'});assert.equal(statsForm.validOverview(data,{room_id:1,date:'1582-10-10'}),true);assert.equal(statsForm.ratioText(data.current.usage_ratio),'不适用');data.current={...data.current,open_available_seats:4,usage_ratio:0};assert.equal(statsForm.validOverview(data,{room_id:1}),true);assert.equal(statsForm.ratioText(0),'0.0%');assert.equal(statsForm.validOverview(data,{room_id:2}),false)
 for(const mutate of [d=>d.current.usage_ratio=null,d=>d.trend.pop(),d=>d.trend[1].date='2026-10-24',d=>d.peak[1].hour=1,d=>d.day.usage_seconds=-1,d=>d.scope.trend_to='2026-10-29',d=>d.snapshot_at='2026-10-30T23:45:00']){const changed=structuredClone(data);mutate(changed);assert.equal(statsForm.validOverview(changed,{room_id:1}),false)}
})

test('heatmap validates exact room/date, hourly boundaries and binary real-usage cells without filtering disabled seats',()=>{
 const data=heatData();assert.equal(statsForm.validHeatmap(data,{room_id:1,date:data.date}),true);assert.equal(statsForm.validHeatmap(data,{room_id:2,date:data.date}),false);for(const mutate of [d=>d.ends[23]='00:00',d=>d.cells[0][2]=0.5,d=>d.cells[0].pop(),d=>d.seats[0].id=null]){const changed=structuredClone(data);mutate(changed);assert.equal(statsForm.validHeatmap(changed,{room_id:1,date:data.date}),false)}
})

test('chart options use truthful seconds-to-hours and separate counts; resource labels never enter an HTML tooltip',()=>{
 const data=overviewData();data.trend[0]={...data.trend[0],created_count:9,cancelled_count:2,checkin_count:3,usage_seconds:7200.5,active_violation_count:1};data.peak[23]={hour:'23:00',usage_seconds:3600,checkin_count:4};data.top_seats=[{seat_id:1,room_id:1,room_name:'<img src=x onerror=alert(1)>',seat_no:'<b>A1</b>',usage_seconds:5400,checkin_count:2}];const options=statsCharts.overviewChartOptions(data,{label:'#000',splitLine:'#eee',tooltipBg:'#222'},false);assert.equal(options.trend.series[0].data[0],9);assert.equal(options.trend.series[4].data[0],7200.5/3600);assert.equal(options.peak.series[0].data[23],1);assert.equal(options.peak.series[1].data[23],4);assert.equal(options.top.series[0].data[0],1.5);for(const key of ['trend','peak','top'])assert.equal(options[key].tooltip.renderMode,'richText');assert.match(options.top.tooltip.formatter([{dataIndex:0}]),/签到人次：2/)
 const heat=heatData(1,'2026-10-30',{seats:[{id:1,seat_no:'<script>bad</script>',row_no:1,col_no:1}]});const option=statsCharts.heatChartOption(heat,{label:'#000',heatRange:['#fff','#00f'],heatBorder:'#fff'},false);assert.deepEqual(option.series[0].data[0],[0,0,1]);assert.equal(option.tooltip.renderMode,'richText');assert.match(option.tooltip.formatter({value:[23,0,0]}),/23:00—24:00\n使用记录相交：无/)
})

test('default statistics load waits for server campus date before exactly one heat GET',async()=>{
 const pending=deferred(),calls=[],p=await component('analytics',{getOverview:query=>{calls.push(['overview',{...query}]);return pending.promise},getHeatmap:async(room,date)=>{calls.push(['heat',room,date]);return{data:heatData(room,date)}}});const load=p.applyQuery({room_id:1});assert.equal(calls.length,1);pending.resolve({data:overviewData({room_id:1})});await load;assert.equal(calls.length,2);assert.equal(calls[1][2],'2026-10-30');assert.equal(p.date.value,'2026-10-30');assert.equal(p.heatmap.value.room.id,1);assert.equal(p.overview.value.scope.room_id,1)
})

test('latest applied room/date queues behind each real request and stale responses cannot overwrite it',async()=>{
 const pending=[],p=await component('analytics',{getOverview:query=>{const task=deferred();pending.push({kind:'overview',query,...task});return task.promise},getHeatmap:(room,date)=>{const task=deferred();pending.push({kind:'heat',query:{room_id:room,date},...task});return task.promise}});const first=p.applyQuery({room_id:1,date:'2026-10-29'}),second=p.applyQuery({room_id:2,date:'2026-10-30'});assert.equal(pending.length,2);for(const item of pending.slice(0,2))item.resolve({data:item.kind==='overview'?overviewData(item.query):heatData(item.query.room_id,item.query.date)});await p.clock.flush();assert.equal(pending.length,4);assert.equal(p.overview.value,null);assert.equal(p.heatmap.value,null);for(const item of pending.slice(2))item.resolve({data:item.kind==='overview'?overviewData(item.query):heatData(item.query.room_id,item.query.date)});await Promise.all([first,second]);assert.equal(p.overview.value.scope.room_id,2);assert.equal(p.heatmap.value.room.id,2);assert.equal(p.attemptedQuery.value.date,'2026-10-30');assert.equal(p.overviewError.value,'')
})

test('read errors preserve each original successful snapshot and retry the applied query rather than edited drafts',async()=>{
 let broken=false;const calls=[],p=await component('analytics',{getOverview:async query=>{calls.push(['overview',{...query}]);if(broken)throw new Error('503 unavailable');return{data:overviewData(query)}},getHeatmap:async(room,date)=>{calls.push(['heat',room,date]);if(broken)throw new Error('503 heat');return{data:heatData(room,date)}}});await p.applyQuery({room_id:1,date:'2026-10-29'});const previous=p.overview.value,heat=p.heatmap.value;broken=true;await p.applyQuery({room_id:2,date:'2026-10-30'});assert.equal(p.overview.value,previous);assert.equal(p.heatmap.value,heat);assert.match(p.overviewError.value,/503/);assert.match(p.heatError.value,/503/);p.roomId.value=3;p.date.value='2026-10-28';broken=false;await p.retryOverview();assert.equal(calls.at(-1)[1].room_id,2);assert.equal(calls.at(-1)[1].date,'2026-10-30');assert.equal(p.overview.value.scope.room_id,2);assert.equal(p.heatmap.value,heat);await p.retryHeat();assert.equal(p.heatmap.value.room.id,2)
})

test('all-school selection needs no heat GET; theme redraw reuses successful snapshots and unload rejects later results',async()=>{
 const p=await component();await p.applyQuery({});assert.equal(p.calls.filter(call=>call[0]==='heat').length,0);const reads=p.calls.length;p.theme.resolvedTheme='dark';p.theme.isDark=true;await vue.nextTick();await vue.nextTick();assert.equal(p.calls.length,reads);assert.equal(p.charts.length,3);const pending=deferred(),leaving=await component('analytics',{getOverview:()=>pending.promise});const request=leaving.applyQuery({});leaving.cleanup.forEach(fn=>fn());pending.resolve({data:overviewData()});await request;assert.equal(leaving.overview.value,null);assert.equal(leaving.charts.length,0)
})

test('actual template distinguishes selected date, current null ratio, seven-day scope and historical heatmap snapshot',async()=>{
 const p=await component();await p.applyQuery({room_id:1,date:'1582-10-10'});const html=await render(p);assert.match(html,/所选日 1582-10-10/);assert.match(html,/2026-10-24 至 2026-10-30/);assert.match(html,/不适用/);assert.match(html,/历史空间1/);assert.match(html,/包括历史停用座位/);assert.doesNotMatch(html,/近 14 天|今日利用率|颜色越深.*概率/)
})

test('hidden trend charts wait for real layout, then initialize and resize to each visible column without re-reading data',async()=>{
 const p=await component();for(const key of ['trendEl','peakEl','topEl'])p[key].value.clientWidth=0
 await p.applyQuery({});assert.equal(p.charts.length,0)
 const reads=p.calls.length
 for(const key of ['trendEl','peakEl'])p[key].value.clientWidth=465
 p.chartTab.value='trend';await nextTurn()
 assert.equal(p.charts.length,2);for(const chart of p.charts){assert.equal(chart.dimensions.width,465);assert.equal(chart.resizes.at(-1).width,465)}
 for(const key of ['trendEl','peakEl']){p[key].value.clientWidth=380;p[key].value.clientHeight=340}
 p.resizeAll();for(const chart of p.charts){assert.equal(chart.resizes.at(-1).width,380);assert.equal(chart.resizes.at(-1).height,340)}
 p.heatEl.value.clientWidth=720;await p.applyQuery({room_id:1,date:'2026-10-30'});assert.equal(p.charts.find(chart=>chart.element===p.heatEl.value).dimensions.width,720);assert.equal(p.calls.length,reads+2)
 p.cleanup.forEach(fn=>fn())
})

test('first controlled 503 has no fabricated zero snapshot and visible timer recovery uses real server snapshots independently',async()=>{
 let broken=true,round=0
 const p=await component('analytics',{getOverview:async query=>{if(broken)throw new Error('503 controlled');return{data:overviewData(query,{snapshot_at:`2026-10-30T23:4${round}:00+08:00`})}},getHeatmap:async(room,date)=>{if(broken)throw new Error('503 heat');return{data:heatData(room,date,{snapshot_at:`2026-10-30T23:4${round}:01+08:00`})}}})
 await p.applyQuery({room_id:1,date:'2026-10-30'});assert.equal(p.overview.value,null);assert.equal(p.heatmap.value,null);assert.match(p.overviewError.value,/503/);assert.match(p.heatError.value,/503/)
 broken=false;round=1;await p.clock.advance();assert.equal(p.overview.value.snapshot_at,'2026-10-30T23:41:00+08:00');assert.equal(p.heatmap.value.snapshot_at,'2026-10-30T23:41:01+08:00');const old=p.overview.value,heat=p.heatmap.value;broken=true;await p.clock.advance();assert.equal(p.overview.value,old);assert.equal(p.heatmap.value,heat);assert.equal(p.overview.value.scope.room_id,1);broken=false;round=3;await p.clock.advance();assert.equal(p.overview.value.snapshot_at,'2026-10-30T23:43:00+08:00');assert.equal(p.overviewError.value,'');assert.equal(p.heatError.value,'')
})

test('campus midnight updates default overview and heat dates plus untouched form, but preserves a user date draft',async()=>{
 let day=30
 const calls=[],p=await component('analytics',{getOverview:async query=>{calls.push(['overview',{...query}]);const selected=`2026-${day===32?'11':'10'}-${String(day===32?1:day).padStart(2,'0')}`,end=new Date(selected+'T00:00:00Z'),trend=Array.from({length:7},(_,index)=>{const date=new Date(end);date.setUTCDate(date.getUTCDate()-6+index);return{date:date.toISOString().slice(0,10),...metric()}});return{data:overviewData(query,{snapshot_at:selected+'T00:01:00+08:00',scope:{room_id:1,selected_date:selected,trend_from:trend[0].date,trend_to:selected},trend})}},getHeatmap:async(room,date)=>{calls.push(['heat',room,date]);return{data:heatData(room,date)}}})
 await p.applyQuery({room_id:1});assert.equal(p.date.value,'2026-10-30');assert.equal(p.heatmap.value.date,'2026-10-30')
 day=31;await p.clock.advance();assert.equal(p.overview.value.scope.selected_date,'2026-10-31');assert.equal(p.heatmap.value.date,'2026-10-31');assert.equal(p.date.value,'2026-10-31');assert.equal(calls.filter(([kind])=>kind==='overview').at(-1)[1].date,undefined)
 p.date.value='2026-09-15';day=32;await p.clock.advance();assert.equal(p.overview.value.scope.selected_date,'2026-11-01');assert.equal(p.heatmap.value.date,'2026-11-01');assert.equal(p.date.value,'2026-09-15')
})

test('visibility pause/resume and unmount stop component reads while retaining success data and current ratio semantics',async()=>{
 const p=await component();await p.applyQuery({});const snapshot=p.overview.value,reads=p.calls.length;await p.clock.visibility('hidden');assert.equal(p.statsVisible.value,false);await p.clock.advance(30000);assert.equal(p.calls.length,reads);assert.equal(p.overview.value,snapshot);await p.clock.visibility('visible');assert.equal(p.statsVisible.value,true);assert.equal(p.calls.length,reads+1);assert.equal(p.overview.value.current.usage_ratio,null);p.cleanup.forEach(fn=>fn());await p.clock.advance(30000);assert.equal(p.calls.length,reads+1)
})

test('management homepage consumes current fields, preserves snapshot on error and refuses stale identity/unmount reads',async()=>{
 let broken=false;const data=overviewData({}, {current:{open_available_seats:8,in_use_now:2,usage_ratio:.25,complaint_todo_count:3,complaint_awaiting_count:1}}),p=await component('home',{getOverview:async()=>{if(broken)throw new Error('503');return{data}}});await p.loadStats();assert.equal(p.openAvailableSeats.value,8);assert.equal(p.inUseNow.value,2);assert.equal(p.currentUsageText.value,'25.0%');const previous=p.statsOverview.value,time=p.statsSnapshotText.value;broken=true;await p.loadStats();assert.equal(p.statsOverview.value,previous);assert.equal(p.statsSnapshotText.value,time);assert.match(p.statsError.value,/503/);const pending=deferred(),leaving=await component('home',{getOverview:()=>pending.promise});const read=leaving.loadStats();leaving.auth.user.id=9;pending.resolve({data});await read;assert.equal(leaving.statsOverview.value,null);const stopped=deferred(),unmounted=await component('home',{getOverview:()=>stopped.promise});const last=unmounted.loadStats();unmounted.cleanup.forEach(fn=>fn());stopped.resolve({data});await last;assert.equal(unmounted.statsOverview.value,null)
})
