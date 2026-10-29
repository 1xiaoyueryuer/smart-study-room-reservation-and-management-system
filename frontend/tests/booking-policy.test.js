import test from 'node:test'
import assert from 'node:assert/strict'
import vm from 'node:vm'
import { readFile } from 'node:fs/promises'
import { ref, computed } from 'vue'
import { validateSlot, minuteValue, minuteText, serverStartMinute } from '../src/composables/bookingPolicy.js'
import { windowKey, validateWindowSlot } from '../src/composables/availabilitySelection.js'
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
  const segment = {room_id:1,seat_id:2,seat_no:'A1',available_start:'22:00',available_end:'24:00',duration_minutes:120,wait_minutes:2280}
  const seat = {id:2,seat_no:'A1',status:'available',available_windows:[segment]}
  const api = { getBookingPolicy: async()=>({data:policy()}),createReservation:async data=>{calls.push(['create',data]);return{data:{room_name:'测试空间',seat_no:'A1'}}},...overrides }
  const mocks = {
    vue:{ref,computed,watch(){},onMounted(){},onBeforeUnmount(handler){cleanup.push(handler)}},
    '../components/seat3d/seatPresentation':{seatType:()=>'',zoneName:{regular:'普通区'},getWindowSides:()=>({})},
    '../components/ui/feedback':{message:{success(){},info(){},warning(){}},confirmDialog:overrides.confirmDialog || (async()=>{})},
    '../api/room':{getRooms:async()=>({data:[currentRoom]})},
    '../api/availability':{getAvailability:overrides.getAvailability || (async(id,date)=>{calls.push(['map',{id,date}]);return{data:{room:{...currentRoom,version:9},date,strategy:'earliest',server_now:policy().server_now,campus_today:policy().campus_today,computed_at:policy().server_now,campus_zone:'Asia/Shanghai',seats:[seat],results:[segment]}}})},
    '../composables/availabilitySelection.js':{windowKey,validateWindowSlot},
    '../api/reservation':api,
    '../composables/bookingPolicy.js':{validateSlot,minuteValue,minuteText,serverStartMinute}
  }
  const context=vm.createContext({})
  const source=(await readFile(new URL('../src/views/BookingView.vue',import.meta.url),'utf8')).split('<script setup>')[1].split('</script>')[0]
  const module=new vm.SourceTextModule(source+'\nexport {loadPolicy,refreshPolicy,loadAvailability,policy,policyError,bookingError,inputError,mapErrorText,submitting,date,start,end,roomId,room,rooms,seats,selected,selectedWindow,results,availability,chooseWindow,onSeatClick,changeActualTime,confirmBooking}',{context})
  await module.link(specifier=>{
    const values=mocks[specifier]||(specifier.endsWith('.vue')?{default:{}}:null)
    assert.ok(values,specifier)
    return new vm.SyntheticModule(Object.keys(values),function(){for(const[key,value]of Object.entries(values))this.setExport(key,value)},{context})
  })
  await module.evaluate()
  const page=module.namespace
  page.policy.value=policy();page.room.value=currentRoom;page.roomId.value=1;page.seats.value=[seat];page.selected.value=seat;page.selectedWindow.value=segment;page.results.value=[segment];page.availability.value={computed_at:policy().server_now};page.date.value='2026-11-02';page.start.value='22:00';page.end.value='24:00'
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

const snapshotResponse=(page,segments= [page.fixture.segment],date=page.date.value)=>({data:{room:page.fixture.currentRoom,date,strategy:'earliest',server_now:policy().server_now,campus_today:policy().campus_today,computed_at:policy().server_now,campus_zone:'Asia/Shanghai',seats:[{...page.fixture.seat,available_windows:segments}],results:segments}})
test('continuous window bounds preserve arbitrary minutes, 241 minutes and 24:00 without combining fragments',()=>{
  const window={room_id:1,seat_id:2,available_start:'18:00',available_end:'24:00'}
  assert.equal(validateWindowSlot(policy(),'2026-11-02','19:59','24:00',window),241)
  assert.throws(()=>validateWindowSlot(policy(),'2026-11-02','18:00','18:29',window),/至少30/)
  assert.throws(()=>validateWindowSlot(policy(),'2026-11-02','17:59','19:00',window),/完整位于/)
  assert.throws(()=>validateWindowSlot(policy(),'2026-11-02','18:00','24:00',{...window,available_end:'19:00'}),/完整位于/)
})
test('one seat exposes all windows without implicit selection; choosing a fragment defaults only the actual booking to 30 minutes',async()=>{
  const page=await booking()
  const second={...page.fixture.segment,available_start:'12:00',available_end:'17:00',duration_minutes:300}
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
  assert.match(bookingSource,/@select-window="chooseWindow"/);assert.match(bookingSource,/results/);assert.match(bookingSource,/最早优先/)
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
