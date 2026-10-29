import test from 'node:test'
import assert from 'node:assert/strict'
import vm from 'node:vm'
import { readFile } from 'node:fs/promises'
import { ref, computed } from 'vue'
import { initialSlot, validateSlot, minuteValue, minuteText, serverStartMinute } from '../src/composables/bookingPolicy.js'
const policy = (server_now = '2026-11-01T08:00:00+08:00') => ({server_now,campus_today:'2026-11-01',dates:['2026-11-01','2026-11-02','2026-11-03'],campus_zone:'Asia/Shanghai',min_duration_minutes:30})
test('server second and nanosecond fractions round upward without millisecond truncation', () => {
  assert.equal(serverStartMinute(policy('2026-11-01T08:00:00.000000001+08:00')),481)
  assert.equal(serverStartMinute(policy('2026-11-01T08:00:00.000000000+08:00')),480)
  assert.equal(serverStartMinute(policy('2026-11-01T08:00:01+08:00')),481)
  assert.equal(serverStartMinute(policy('2026-11-01T08:00+08:00')),480)
})
test('initial slot uses server campus day and only server-provided next date at midnight', () => {
  const slot = initialSlot(policy('2026-11-01T23:59:59.999999999+08:00'))
  assert.equal(slot.date,'2026-11-02')
  assert.equal(slot.start,'00:00')
  assert.equal(slot.end,'00:30')
  assert.equal(initialSlot(policy('2026-11-01T23:29:59+08:00')).end,'24:00')
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
  const calls = []
  const currentRoom = {id:1,name:'测试空间',enabled:true,opening_windows:[{start:'00:00',end:'24:00'}]}
  const seat = {id:2,seat_no:'A1',status:'available',bookable_for_slot:true,occupied:false}
  const api = { getBookingPolicy: async()=>({data:policy()}),createReservation:async data=>{calls.push(['create',data]);return{data:{room_name:'测试空间',seat_no:'A1'}}},...overrides }
  const mocks = {
    vue:{ref,computed,watch(){},onMounted(){}},
    '../components/seat3d/seatPresentation':{seatType:()=>'',zoneName:{regular:'普通区'},getWindowSides:()=>({})},
    '../components/ui/feedback':{message:{success(){},info(){},warning(){}},confirmDialog:overrides.confirmDialog || (async()=>{})},
    '../api/room':{getRooms:async()=>({data:[currentRoom]}),getSeatMap:overrides.getSeatMap || (async(id,date,start,end)=>{calls.push(['map',{id,date,start,end}]);return{data:{room:{...currentRoom,version:9},seats:[seat]}}})},
    '../api/reservation':api,
    '../composables/bookingPolicy.js':{initialSlot,validateSlot,minuteValue,serverStartMinute}
  }
  const context=vm.createContext({})
  const source=(await readFile(new URL('../src/views/BookingView.vue',import.meta.url),'utf8')).split('<script setup>')[1].split('</script>')[0]
  const module=new vm.SourceTextModule(source+'\nexport {loadPolicy,refreshPolicy,loadSeatMap,policy,policyError,bookingError,inputError,mapErrorText,submitting,date,start,end,roomId,room,rooms,seats,selected,confirmBooking}',{context})
  await module.link(specifier=>{
    const values=mocks[specifier]||(specifier.endsWith('.vue')?{default:{}}:null)
    assert.ok(values,specifier)
    return new vm.SyntheticModule(Object.keys(values),function(){for(const[key,value]of Object.entries(values))this.setExport(key,value)},{context})
  })
  await module.evaluate()
  const page=module.namespace
  page.policy.value=policy();page.room.value=currentRoom;page.roomId.value=1;page.seats.value=[seat];page.selected.value=seat;page.date.value='2026-11-02';page.start.value='22:00';page.end.value='24:00'
  return{...page,calls}
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
  page.date.value='2026-11-01';page.start.value='09:00';page.end.value='10:00'
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
  const page=await booking({getSeatMap:async()=>{throw new Error('空间临时停用，详情请重试')}})
  await page.loadSeatMap()
  assert.equal(page.mapErrorText.value,'空间临时停用，详情请重试')
  assert.equal(page.date.value,'2026-11-02')
  assert.equal(page.start.value,'22:00')
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
