import test from 'node:test'
import assert from 'node:assert/strict'
import vm from 'node:vm'
import { readFile } from 'node:fs/promises'
import { ref, computed } from 'vue'
import { validateSlot, minuteValue, minuteText, serverStartMinute } from '../src/composables/bookingPolicy.js'
import { bookingRestrictionDetails } from '../src/composables/restrictionForm.js'
import { windowKey, validateWindowSlot, queryRequiredMinutes, shortfallText } from '../src/composables/availabilitySelection.js'
const policy = (server_now = '2026-11-01T08:00:00+08:00') => ({server_now,campus_today:'2026-11-01',dates:['2026-11-01','2026-11-02','2026-11-03'],campus_zone:'Asia/Shanghai',min_duration_minutes:30})
test('server second and nanosecond fractions round upward without millisecond truncation', () => {
  assert.equal(serverStartMinute(policy('2026-11-01T08:00:00.000000001+08:00')),481)
  assert.equal(serverStartMinute(policy('2026-11-01T08:00:00.000000000+08:00')),480)
  assert.equal(serverStartMinute(policy('2026-11-01T08:00:01+08:00')),481)
  assert.equal(serverStartMinute(policy('2026-11-01T08:00+08:00')),480)
})
test('initial availability uses server campus date even when today has no remaining half-hour, without auto-switching date', async () => {
  const page = await booking({getBookingPolicy:async()=>({data:policy('2026-11-01T23:59:59.999999999+08:00')})})
  await page.loadPolicy(true)
  assert.equal(page.date.value,'2026-11-01');assert.equal(page.start.value,'');assert.equal(page.end.value,'')
})

test('minute forms enforce minimum and date membership with no four/eight hour ceiling', () => {
  assert.throws(()=>validateSlot(policy(),'2026-11-02','09:00','09:29'),/至少30/)
  assert.equal(validateSlot(policy(),'2026-11-02','09:00','09:30'),30)
  assert.equal(validateSlot(policy(),'2026-11-02','09:00','13:01'),241)
  assert.equal(validateSlot(policy(),'2026-11-02','09:00','14:00'),300)
  assert.equal(validateSlot(policy(),'2026-11-03','00:00','24:00'),1440)
  assert.throws(()=>validateSlot(policy(),'2026-11-04','09:00','10:00'),/无效/)
  assert.throws(()=>validateSlot(policy(),'2026-11-02','24:00','24:00'),/开始不可24/)
  assert.throws(()=>validateSlot(policy(),'2026-11-02','22:00','02:00'),/不可跨/)
  assert.throws(()=>validateSlot(policy('2026-11-01T09:00:00.000000001+08:00'),'2026-11-01','09:00','10:00'),/早于当前/)
  assert.equal(minuteText(minuteValue('24:00',true)),'24:00')
})
async function booking(overrides = {}) {
  const calls = [], cleanup = []
  const currentRoom = {id:1,name:'测试空间',enabled:true,opening_windows:[{start:'00:00',end:'24:00'}]}
  const segment = {room_id:1,seat_id:2,seat_no:'A1',available_start:'22:00',available_end:'24:00',duration_minutes:120,wait_minutes:2280,suggested_start:'22:00',suggested_end:'22:30'}
  const seat = {id:2,seat_no:'A1',status:'available',available_windows:[segment]}
  const api = { getBookingPolicy: async()=>({data:policy()}),createReservation:async data=>{calls.push(['create',data]);return{data:{room_name:'测试空间',seat_no:'A1'}}},...overrides }
  const mocks = {
    vue:{ref,computed,watch(){},onMounted(){},onBeforeUnmount(handler){cleanup.push(handler)}},
    '../components/seat3d/seatPresentation':{seatType:()=>'',zoneName:{regular:'普通区'},getWindowSides:()=>({})},
    '../components/ui/feedback':{message:{success(){},info(){},warning(){}},confirmDialog:overrides.confirmDialog || (async()=>{})},
    '../api/room':{getRooms:async()=>({data:[currentRoom]})},
    '../api/availability':{getAvailability:overrides.getAvailability || (async(id,date,strategy='earliest',requiredMinutes=null)=>{calls.push(['map',{id,date,strategy,requiredMinutes}]);return{data:{room:{...currentRoom,version:9},date,strategy,required_minutes:requiredMinutes,server_now:policy().server_now,campus_today:policy().campus_today,computed_at:policy().server_now,campus_zone:'Asia/Shanghai',seats:[seat],results:[segment]}}})},
    '../composables/restrictionForm.js':{bookingRestrictionDetails},
    '../composables/availabilitySelection.js':{windowKey,validateWindowSlot,queryRequiredMinutes,shortfallText},
    '../api/reservation':api,
    '../composables/bookingPolicy.js':{validateSlot,minuteValue,minuteText,serverStartMinute}
  }
  const context=vm.createContext({})
  const source=(await readFile(new URL('../src/views/BookingView.vue',import.meta.url),'utf8')).split('<script setup>')[1].split('</script>')[0]
  const module=new vm.SourceTextModule(source+'\nexport {loadPolicy,refreshPolicy,loadAvailability,policy,policyError,restrictionFailure,bookingError,inputError,mapErrorText,submitting,date,start,end,roomId,room,rooms,seats,selected,selectedWindow,results,availability,chooseWindow,onSeatClick,changeActualTime,confirmBooking,strategy,requiredDraft,queryDirty,changeRequired,changeStrategy,shortfall}',{context})
  await module.link(specifier=>{
    const values=mocks[specifier]||(specifier.endsWith('.vue')?{default:{}}:null)
    assert.ok(values,specifier)
    return new vm.SyntheticModule(Object.keys(values),function(){for(const[key,value]of Object.entries(values))this.setExport(key,value)},{context})
  })
  await module.evaluate()
  const page=module.namespace
  page.policy.value=policy();page.room.value=currentRoom;page.roomId.value=1;page.seats.value=[seat];page.selected.value=seat;page.selectedWindow.value=segment;page.results.value=[segment];page.queryDirty.value=false;page.availability.value={strategy:'earliest',required_minutes:null,computed_at:policy().server_now};page.date.value='2026-11-02';page.start.value='22:00';page.end.value='24:00'
  return{...page,calls,cleanup,fixture:{currentRoom,segment,seat}}
}
test('policy failure is persistent and fail-closed without inventing fallback dates',async()=>{
  const page=await booking({getBookingPolicy:async()=>{throw new Error('政策读取失败')}})
  assert.equal(await page.loadPolicy(true),false)
  assert.equal(page.policy.value,null)
  assert.equal(page.policyError.value,'政策读取失败')
  assert.equal(page.date.value,'2026-11-02')
})
test('successful submit keeps minute and 24:00 payload and reloads authoritative seat map',async()=>{
  const page=await booking()
  await page.confirmBooking()
  assert.equal(page.calls[0][0],'create')
  assert.equal(page.calls[0][1].end_time,'24:00')
  assert.equal(page.calls[0][1].date,'2026-11-02')
  assert.equal(page.calls[1][0],'map')
  assert.equal(page.room.value.version,9)
})
test('expired policy is re-read immediately before posting and preserves attempted inputs',async()=>{
  const page=await booking({getBookingPolicy:async()=>({data:{...policy('2026-11-02T08:00:00+08:00'),campus_today:'2026-11-02',dates:['2026-11-02','2026-11-03','2026-11-04']}})})
  page.date.value='2026-11-01';page.start.value='09:00';page.end.value='10:00';page.selectedWindow.value={...page.selectedWindow.value,available_start:'09:00',available_end:'10:00',duration_minutes:60}
  await page.confirmBooking()
  assert.equal(page.calls.some(call=>call[0]==='create'),false)
  assert.match(page.bookingError.value,/已无效/)
  assert.equal(page.date.value,'2026-11-01')
  assert.equal(page.start.value,'09:00')
})
test('server conflict refreshes actual availability and exposes persistent error',async()=>{
  const page=await booking({createReservation:async()=>{throw{status:409,message:'座位已被预约'}}})
  await page.confirmBooking()
  assert.match(page.bookingError.value,/座位已被预约/)
  assert.equal(page.calls[0][0],'map')
  assert.equal(page.start.value,'22:00')
})
test('old creation APIs and UI entry points are removed while historical waitlist endpoints remain',async()=>{
  for(const name of ['reservation','waitlist']){
    const source=await readFile(new URL(`../src/api/${name}.js`,import.meta.url),'utf8')
    const context=vm.createContext({})
    const module=new vm.SourceTextModule(source,{context})
    await module.link(()=>new vm.SyntheticModule(['default'],function(){this.setExport('default',{})},{context}))
    await module.evaluate()
    assert.equal('autoAllocate'in module.namespace,false)
    assert.equal('joinWaitlist'in module.namespace,false)
    if(name==='waitlist'){assert.equal(typeof module.namespace.listMyWaitlist,'function');assert.equal(typeof module.namespace.cancelWaitlist,'function')}
  }
  const bookingSource=await readFile(new URL('../src/views/BookingView.vue',import.meta.url),'utf8')
  assert.doesNotMatch(bookingSource,/autoAllocate|joinWaitlist|runAllocate|allocVisible/)
  const historySource=await readFile(new URL('../src/views/WaitlistView.vue',import.meta.url),'utf8')
  assert.match(historySource,/不会自动递补/)
  assert.match(historySource,/row.status === 'waiting'/)
})

test('confirmation snapshot remains identical to posted date/time while submission is in flight',async()=>{
  let page, release
  page=await booking({confirmDialog:()=>new Promise(resolve=>{release=resolve})})
  const first=page.confirmBooking()
  assert.equal(page.submitting.value,true)
  page.date.value='2026-11-03';page.start.value='00:00';page.end.value='00:30'
  await page.confirmBooking()
  release()
  await first
  const creates=page.calls.filter(call=>call[0]==='create')
  assert.equal(creates.length,1)
  assert.equal(creates[0][1].date,'2026-11-02')
  assert.equal(creates[0][1].start_time,'22:00')
  assert.equal(creates[0][1].end_time,'24:00')
  assert.equal(page.submitting.value,false)
})
test('map query failure exposes actual server message and preserves requested conditions',async()=>{
  const page=await booking({getAvailability:async()=>{throw new Error('空间临时停用，详情请重试')}})
  await page.loadAvailability()
  assert.equal(page.mapErrorText.value,'空间临时停用，详情请重试')
  assert.equal(page.date.value,'2026-11-02')
  assert.equal(page.start.value,'')
  assert.equal(page.selectedWindow.value,null)
})

test('retrying policy after initial failure loads real rooms and map instead of leaving an empty screen',async()=>{
  let failed=true
  const page=await booking({getBookingPolicy:async()=>{if(failed)throw new Error('暂时失败');return{data:policy()}}})
  page.roomId.value=null;page.room.value=null;page.rooms.value=[];page.date.value='';page.start.value='';page.end.value=''
  await page.loadPolicy(true)
  failed=false
  await page.refreshPolicy()
  assert.equal(page.policyError.value,'')
  assert.equal(page.rooms.value.length,1)
  assert.ok(page.calls.some(call=>call[0]==='map'))
  assert.equal(page.date.value,'2026-11-01')
})

test('historical waitlist SFC shows historical counts and does not claim a frozen queue position',async()=>{
  const source=await readFile(new URL('../src/views/WaitlistView.vue',import.meta.url),'utf8')
  const template=source.split('<template>')[1].split('</template>')[0]
  assert.match(template,/>历史等待</)
  assert.match(template,/>历史已递补</)
  assert.doesNotMatch(template,/正在排队|成功递补|排队位次|row\.position|原第/)
  assert.match(template,/不会自动递补/)
  assert.match(template,/row.status === 'waiting'/)
})

const snapshotResponse=(page,segments=[page.fixture.segment],date=page.date.value,strategy=page.strategy.value,requiredMinutes=strategy==='duration'?queryRequiredMinutes(strategy,page.requiredDraft.value):null)=>{
  const windows=segments.map(segment=>({...segment,suggested_start:segment.available_start,suggested_end:minuteText(minuteValue(segment.available_start)+(requiredMinutes||30))}))
  return{data:{room:page.fixture.currentRoom,date,strategy,required_minutes:requiredMinutes,server_now:policy().server_now,campus_today:policy().campus_today,computed_at:policy().server_now,campus_zone:'Asia/Shanghai',seats:[{...page.fixture.seat,available_windows:windows}],results:windows}}
}
test('continuous window bounds preserve arbitrary minutes, 241 minutes and 24:00 without combining fragments',()=>{
  const window={room_id:1,seat_id:2,available_start:'18:00',available_end:'24:00'}
  assert.equal(validateWindowSlot(policy(),'2026-11-02','19:59','24:00',window),241)
  assert.throws(()=>validateWindowSlot(policy(),'2026-11-02','18:00','18:29',window),/至少30/)
  assert.throws(()=>validateWindowSlot(policy(),'2026-11-02','17:59','19:00',window),/完整位于/)
  assert.throws(()=>validateWindowSlot(policy(),'2026-11-02','18:00','24:00',{...window,available_end:'19:00'}),/完整位于/)
})
test('one seat exposes all windows without implicit selection; choosing a fragment defaults only the actual booking to 30 minutes',async()=>{
  const page=await booking()
  const second={...page.fixture.segment,available_start:'12:00',available_end:'17:00',duration_minutes:300,suggested_start:'12:00',suggested_end:'12:30'}
  page.seats.value=[{...page.fixture.seat,available_windows:[second,page.fixture.segment]}];page.selected.value=null;page.selectedWindow.value=null
  page.onSeatClick(page.seats.value[0]);assert.equal(page.selected.value.id,2);assert.equal(page.selectedWindow.value,null)
  page.chooseWindow(second);assert.equal(page.selectedWindow.value.available_end,'17:00');assert.equal(page.start.value,'12:00');assert.equal(page.end.value,'12:30')
  page.changeActualTime('end','16:01');assert.equal(page.end.value,'16:01')
})
test('refresh preserves the full same window and actual minutes but clears changed boundaries even for the same seat',async()=>{
  let response;const page=await booking({getAvailability:async()=>response})
  response=snapshotResponse(page)
  page.start.value='22:17';page.end.value='23:19';await page.loadAvailability(true)
  assert.equal(page.start.value,'22:17');assert.equal(page.end.value,'23:19')
  response=snapshotResponse(page,[{...page.fixture.segment,available_end:'23:30',duration_minutes:90}]);await page.loadAvailability(true)
  assert.equal(page.selectedWindow.value,null);assert.equal(page.selected.value,null);assert.equal(page.start.value,'')
})
test('older availability responses and a response after unmount cannot replace the current snapshot',async()=>{
  const pending=[];const page=await booking({getAvailability:()=>new Promise(resolve=>pending.push(resolve))})
  const older=page.loadAvailability(),latest=page.loadAvailability()
  pending[1](snapshotResponse(page,[{...page.fixture.segment,available_start:'23:00',duration_minutes:60}]));await latest
  pending[0](snapshotResponse(page));await older
  assert.equal(page.results.value[0].available_start,'23:00')
  const leaving=page.loadAvailability();page.cleanup[0]();pending[2](snapshotResponse(page));await leaving
  assert.equal(page.results.value.length,0)
})
test('empty availability differs from failed or inconsistent data and retains chosen date and room',async()=>{
  let response;const page=await booking({getAvailability:async()=>response})
  response=snapshotResponse(page,[]);await page.loadAvailability();assert.equal(page.results.value.length,0);assert.equal(page.mapErrorText.value,'');assert.equal(page.availability.value.date,'2026-11-02')
  response=snapshotResponse(page);response.data.results=[{...page.fixture.segment,duration_minutes:999}]
  await page.loadAvailability();assert.match(page.mapErrorText.value,/图与列表空档数据不一致/);assert.equal(page.availability.value,null)
  assert.equal(page.date.value,'2026-11-02');assert.equal(page.roomId.value,1)
})
test('confirmation binds the full segment and actual slot while a conflicting POST refreshes only the same query',async()=>{
  let confirmation='',page
  page=await booking({confirmDialog:async text=>{confirmation=text},createReservation:async()=>{throw{status:409,message:'被其他预约抢占'}}})
  await page.confirmBooking();assert.match(confirmation,/完整空档：22:00–24:00/);assert.match(confirmation,/计算于/);assert.match(confirmation,/15分钟之前/)
  assert.match(page.bookingError.value,/被其他预约抢占/);assert.equal(page.calls.at(-1)[1].date,'2026-11-02');assert.equal(page.calls.at(-1)[1].id,1)
})
test('2D and 3D share real window selection rather than passing has-window into old occupied flags',async()=>{
  const bookingSource=await readFile(new URL('../src/views/BookingView.vue',import.meta.url),'utf8')
  assert.doesNotMatch(bookingSource,/getSeatMap|bookable_for_slot|s\.occupied/)
  assert.match(bookingSource,/@select-window="chooseWindow"/);assert.match(bookingSource,/results/);assert.match(bookingSource,/首选结果/)
  const explorer=await readFile(new URL('../src/components/seat3d/SeatExplorer.vue',import.meta.url),'utf8')
  assert.match(explorer,/selected\.available_windows/);assert.match(explorer,/emit\('select-window', window\)/);assert.match(explorer,/!selectedWindow/)
  const {seatState}=await import('../src/components/seat3d/seatPresentation.js')
  assert.equal(seatState({id:1,status:'available',available_windows:[]},null),'no_window')
  assert.equal(seatState({id:1,status:'maintenance',available_windows:[]},null),'maintenance')
  assert.equal(seatState({id:1,status:'available',available_windows:[{}]},1),'selected')
})
test('availability API requests only selected room/date and the actual earliest strategy',async()=>{
  let args;const context=vm.createContext({})
  const module=new vm.SourceTextModule(await readFile(new URL('../src/api/availability.js',import.meta.url),'utf8'),{context})
  await module.link(()=>new vm.SyntheticModule(['default'],function(){this.setExport('default',{get:(...value)=>{args=value}})},{context}));await module.evaluate()
  module.namespace.getAvailability(7,'2026-11-02');assert.equal(args[0],'/availability');assert.equal(args[1].params.strategy,'earliest');assert.equal(args[1].params.room_id,7);assert.equal(args[1].params.date,'2026-11-02');assert.equal('start'in args[1].params,false)
})

test('required minutes use strict decimal integer syntax without business duration ceilings',()=>{
  for(const value of ['', '29','30.0','1e2','+120','-120',' 120','120 ','NaN','１２0','2147483648']) assert.throws(()=>queryRequiredMinutes('duration',value))
  assert.equal(queryRequiredMinutes('duration','00030'),30)
  assert.equal(queryRequiredMinutes('duration','241'),241);assert.equal(queryRequiredMinutes('duration','1500'),1500)
  assert.equal(queryRequiredMinutes('duration','2147483647'),2147483647)
  assert.equal(queryRequiredMinutes('longest','invalid retained draft'),null)
})
test('switching strategies retains date, room and required draft while clearing old selection and consuming server order',async()=>{
  const seen=[];let page
  page=await booking({getAvailability:async(id,date,strategy,required)=>{seen.push({id,date,strategy,required});return snapshotResponse(page,[{...page.fixture.segment,available_start:'18:00',available_end:'24:00',duration_minutes:360}],date,strategy,required)}})
  page.requiredDraft.value='00241';await page.changeStrategy('longest')
  assert.equal(page.selectedWindow.value,null);assert.equal(page.start.value,'');assert.equal(page.date.value,'2026-11-02');assert.equal(page.roomId.value,1);assert.equal(page.requiredDraft.value,'00241')
  assert.equal(seen[0].strategy,'longest');assert.equal(seen[0].required,null);assert.equal(page.results.value[0].available_start,'18:00')
  await page.changeStrategy('duration');assert.equal(seen[1].required,241);assert.equal(page.requiredDraft.value,'00241')
})
test('duration uses full server suggestion but permits a deliberate 30-minute booking with truthful editing and snapshot confirmation',async()=>{
  let page,confirmation='',posted
  page=await booking({getAvailability:async(id,date,strategy,required)=>snapshotResponse(page,[{...page.fixture.segment,available_start:'12:00',available_end:'17:00',duration_minutes:300}],date,strategy,required),confirmDialog:async text=>{confirmation=text},createReservation:async body=>{posted=body;return{data:{room_name:'测试空间',seat_no:'A1'}}}})
  await page.changeStrategy('duration');page.chooseWindow(page.results.value[0])
  assert.equal(page.selectedWindow.value.available_end,'17:00');assert.equal(page.start.value,'12:00');assert.equal(page.end.value,'14:00')
  assert.equal(page.shortfall.value,'');page.changeActualTime('end','12:30')
  assert.equal(page.shortfall.value,'本次30分钟，短于找座时输入的120分钟')
  await page.confirmBooking();assert.equal(posted.start_time,'12:00');assert.equal(posted.end_time,'12:30');assert.equal('required_minutes' in posted,false)
  assert.match(confirmation,/找座所需：120分钟/);assert.match(confirmation,/建议：12:00–14:00/);assert.match(confirmation,/本次30分钟，短于找座时输入的120分钟/)
})
test('241-minute suggestion and above-day empty query preserve exact duration input and chosen place',async()=>{
  let page
  page=await booking({getAvailability:async(id,date,strategy,required)=>snapshotResponse(page,required>1440?[]:[{...page.fixture.segment,available_start:'18:00',duration_minutes:360}],date,strategy,required)})
  page.requiredDraft.value='241';await page.changeStrategy('duration');page.chooseWindow(page.results.value[0]);assert.equal(page.end.value,'22:01')
  page.changeRequired('1500');assert.equal(page.results.value.length,0);assert.equal(page.availability.value,null);assert.equal(page.queryDirty.value,true)
  await page.loadAvailability();assert.equal(page.results.value.length,0);assert.equal(page.availability.value.required_minutes,1500);assert.equal(page.requiredDraft.value,'1500');assert.equal(page.date.value,'2026-11-02');assert.equal(page.roomId.value,1)
})
test('invalid duration keeps input visible and cannot call API or reuse a previous fragment',async()=>{
  const page=await booking();page.requiredDraft.value='29'
  await page.changeStrategy('duration');assert.equal(page.calls.length,0);assert.match(page.mapErrorText.value,/至少30/);assert.equal(page.selectedWindow.value,null);assert.equal(page.requiredDraft.value,'29')
})
test('rapid strategy or required-draft changes invalidate old responses before they can claim new query success',async()=>{
  const pending=[];const page=await booking({getAvailability:(id,date,strategy,required)=>new Promise(resolve=>pending.push({resolve,date,strategy,required}))})
  const old=page.loadAvailability();const latest=page.changeStrategy('longest')
  pending[1].resolve(snapshotResponse(page,[],pending[1].date,pending[1].strategy,pending[1].required));await latest
  pending[0].resolve(snapshotResponse(page,[page.fixture.segment],pending[0].date,pending[0].strategy,pending[0].required));await old
  assert.equal(page.availability.value.strategy,'longest');assert.equal(page.results.value.length,0)
  const durationRequest=page.changeStrategy('duration');page.changeRequired('241')
  pending[2].resolve(snapshotResponse(page,[{...page.fixture.segment,available_start:'18:00',duration_minutes:360}],pending[2].date,pending[2].strategy,pending[2].required));await durationRequest
  assert.equal(page.availability.value,null);assert.equal(page.results.value.length,0);assert.equal(page.queryDirty.value,true);assert.equal(page.requiredDraft.value,'241')
})
test('duration conflict refresh uses unchanged strategy and required snapshot without changing search input',async()=>{
  const queries=[];let page
  page=await booking({getAvailability:async(id,date,strategy,required)=>{queries.push({id,date,strategy,required});return snapshotResponse(page,[],date,strategy,required)},createReservation:async()=>{throw{status:409,message:'刚被抢占'}}})
  page.strategy.value='duration';page.availability.value={...page.availability.value,strategy:'duration',required_minutes:120}
  page.end.value='22:30';await page.confirmBooking()
  assert.match(page.bookingError.value,/刚被抢占/);assert.equal(queries[0].strategy,'duration');assert.equal(queries[0].required,120);assert.equal(page.requiredDraft.value,'120');assert.equal(page.date.value,'2026-11-02')
})
test('all availability strategies send only their applicable parameters; 3D uses same suggested and shortfall fields',async()=>{
  const calls=[];const context=vm.createContext({})
  const module=new vm.SourceTextModule(await readFile(new URL('../src/api/availability.js',import.meta.url),'utf8'),{context})
  await module.link(()=>new vm.SyntheticModule(['default'],function(){this.setExport('default',{get:(...value)=>calls.push(value)})},{context}));await module.evaluate()
  module.namespace.getAvailability(1,'2026-11-02','earliest','120');module.namespace.getAvailability(1,'2026-11-02','longest','120');module.namespace.getAvailability(1,'2026-11-02','duration',241)
  assert.equal('required_minutes' in calls[0][1].params,false);assert.equal('required_minutes' in calls[1][1].params,false);assert.equal(calls[2][1].params.required_minutes,241)
  const explorer=await readFile(new URL('../src/components/seat3d/SeatExplorer.vue',import.meta.url),'utf8')
  assert.match(explorer,/strategyLabel/);assert.match(explorer,/suggested_start/);assert.match(explorer,/v-if="shortfall"/);assert.match(explorer,/本次预约已主动缩短/)
})

test('confirmation keeps original strategy and required minutes together with actual slot despite later live state changes',async()=>{
  let release,posted,confirmation=''
  const page=await booking({confirmDialog:text=>{confirmation=text;return new Promise(resolve=>{release=resolve})},createReservation:async body=>{posted=body;return{data:{room_name:'测试空间',seat_no:'A1'}}}})
  page.strategy.value='duration';page.availability.value={...page.availability.value,strategy:'duration',required_minutes:120};page.end.value='22:30'
  const attempt=page.confirmBooking();assert.equal(page.submitting.value,true)
  page.changeRequired('241');assert.equal(page.requiredDraft.value,'120')
  page.strategy.value='longest';page.requiredDraft.value='241';page.start.value='23:00';page.end.value='24:00'
  release();await attempt
  assert.equal(posted.start_time,'22:00');assert.equal(posted.end_time,'22:30');assert.match(confirmation,/满足学习时长/);assert.match(confirmation,/找座所需：120分钟/);assert.match(confirmation,/本次30分钟，短于找座时输入的120分钟/)
})

test('only structured restriction 403 exposes actual active records, preserves the attempted query and never retries a POST',async()=>{
  for(const details of [{code:'booking_restricted',active_restrictions:[{id:8,source:'manual',status:'active',reason:'工程模拟限制'}],server_now:'2026-11-01T08:00+08:00',campus_zone:'Asia/Shanghai'},null]){
    let posts=0;const page=await booking({createReservation:async()=>{posts++;throw{status:403,message:details?'仍有有效限制':'账号已停用',details}}});await page.loadPolicy(true);page.roomId.value=1;page.date.value='2026-11-02';await page.loadAvailability();page.chooseWindow(page.results.value[0]);const room=page.roomId.value,date=page.date.value,start=page.start.value,end=page.end.value;await page.confirmBooking();assert.equal(posts,1);assert.equal(page.roomId.value,room);assert.equal(page.date.value,date);assert.equal(page.start.value,start);assert.equal(page.end.value,end);assert.equal(page.restrictionFailure.value?.active_restrictions?.[0]?.id,details?8:undefined);assert.match(page.bookingError.value,details?/有效限制/:/停用/)
  }
})
