import test from 'node:test'
import assert from 'node:assert/strict'
import vm from 'node:vm'
import { readFile } from 'node:fs/promises'
import { ref, computed } from 'vue'
async function script(file, mocks, exports, extra={}) {
  const source=(await readFile(new URL(file,import.meta.url),'utf8')).split('<script setup>')[1].split('</script>')[0]
  const context=vm.createContext({defineExpose(){},defineProps:()=>({autoLoad:false}),...extra})
  const module=new vm.SourceTextModule(source+`\nexport {${exports}}`,{context})
  await module.link(specifier=>{
    const values=mocks[specifier]||(specifier.endsWith('.vue')?{default:{}}:null)
    assert.ok(values,specifier)
    return new vm.SyntheticModule(Object.keys(values),function(){for(const[key,value]of Object.entries(values))this.setExport(key,value)},{context})
  })
  await module.evaluate()
  return module.namespace
}
const waiting=(allowed_actions=['cancel'])=>({id:10,status:'pending',allowed_actions,res_date:'2026-11-01',start_time:'09:00',end_time:'10:00'})
async function mine(overrides={}) {
  const calls=[],confirmations=[]
  const api={listMyReservations:async()=>{calls.push('mine');return{data:[waiting([])]}},...Object.fromEntries(['cancel','abandon','checkin','leave','return','checkout'].map(key=>[`${key}Reservation`,async id=>{calls.push(key);return{data:{id}}}])),...overrides}
  const page=await script('../src/views/MyReservationsView.vue',{
    vue:{ref,computed,onMounted(){}},
    'vue-router':{useRouter:()=>({push:async location=>{calls.push(location)}})},
    '../composables/usePagination':{usePagination:items=>({page:ref(1),pages:computed(()=>1),pageItems:items})},
    '../components/ui/feedback':{message:{success(){}},confirmDialog:overrides.confirmDialog|| (async text=>{confirmations.push(text)})},
    '../api/reservation':api
  },'list,loading,error,actionError,busyId,violations,actionsOf,doAction,refreshAll,terminationLabels,statusBuckets')
  page.violations.value={refresh:async()=>{calls.push('violations')}}
  return{...page,calls,confirmations}
}
test('reservation buttons use only server allowed_actions, never infer cancel or abandon from pending',async()=>{
  const page=await mine()
  assert.deepEqual(Array.from(page.actionsOf(waiting(['cancel'])),item=>item.key),['cancel'])
  assert.deepEqual(Array.from(page.actionsOf(waiting(['checkin','abandon'])),item=>item.key),['checkin','abandon'])
  assert.equal(page.actionsOf({status:'pending'}).length,0)
  assert.equal(page.actionsOf(waiting(['not_implemented'])).length,0)
})
test('before-start cancellation and after-start abandonment have distinct truthful confirmations and refresh both sources',async()=>{
  const cancellation=await mine()
  await cancellation.doAction(waiting(['cancel']),'cancel')
  assert.match(cancellation.confirmations[0],/不登记新的违规/)
  assert.deepEqual(cancellation.calls,['cancel','mine','violations'])
  const abandonment=await mine()
  await abandonment.doAction(waiting(['abandon']),'abandon')
  assert.match(abandonment.confirmations[0],/登记一次未使用违约/)
  assert.deepEqual(abandonment.calls,['abandon','mine','violations'])
})
test('action in-flight guard includes confirmation dialog and prevents duplicate abandonment',async()=>{
  let release
  const page=await mine({confirmDialog:()=>new Promise(resolve=>{release=resolve})})
  const first=page.doAction(waiting(['abandon']),'abandon')
  assert.equal(page.busyId.value,10)
  await page.doAction(waiting(['abandon']),'abandon')
  release();await first
  assert.equal(page.calls.filter(key=>key==='abandon').length,1)
  assert.equal(page.busyId.value,null)
})
test('committed deadline 409 is explained and actual no_show state plus independent records are re-read',async()=>{
  const page=await mine({abandonReservation:async()=>{throw{status:409,message:'已超时',details:{reservation_id:10,status:'unused_ended',termination_reason:'no_show'}}},listMyReservations:async()=>({data:[{id:10,status:'unused_ended',termination_reason:'no_show',allowed_actions:[]}]})})
  await page.doAction(waiting(['abandon']),'abandon')
  assert.match(page.actionError.value,/已按签到期限超时结束/)
  assert.equal(page.list.value[0].status,'unused_ended')
  assert.equal(page.actionsOf(page.list.value[0]).length,0)
  assert.ok(page.calls.includes('violations'))
})
test('failed reservation refresh stays visible and still refreshes independent violation source',async()=>{
  const page=await mine({listMyReservations:async()=>{throw new Error('预约查询失败')}})
  await page.refreshAll()
  assert.equal(page.error.value,'预约查询失败')
  assert.deepEqual(page.calls,['violations'])
  await page.doAction(waiting(['cancel']),'cancel')
  assert.equal(page.calls.includes('cancel'),false)
})
test('legacy terminal state is explained without fabricating an independent violation count',async()=>{
  const page=await mine()
  page.list.value=[{id:10,status:'unused_ended',legacy_status:'violation',termination_reason:'legacy_violation',allowed_actions:[]}]
  assert.match(page.terminationLabels.legacy_violation,/未转为新独立违规/)
  assert.equal(page.statusBuckets.value.unused_ended,1)
  const source=await readFile(new URL('../src/views/MyReservationsView.vue',import.meta.url),'utf8')
  assert.match(source,/待签到预约/)
  assert.doesNotMatch(source,/今日待签到|kpi-label">违约记录/)
})
async function violations(loader) {
  return script('../src/components/ViolationsPanel.vue',{
    vue:{ref,computed,onMounted(){}},'../api/violation':{listMyViolations:loader}
  },'items,error,loading,loaded,activeCount,refresh')
}
test('independent effective count uses record status and preserves revoked records',async()=>{
  const page=await violations(async()=>({data:[{id:1,status:'active',type:'no_show'},{id:2,status:'revoked',type:'abandon'}]}))
  await page.refresh()
  assert.equal(page.items.value.length,2)
  assert.equal(page.activeCount.value,1)
})
test('violation failure is separate from empty data and can recover through actual retry',async()=>{
  let fail=true
  const page=await violations(async()=>{if(fail)throw new Error('违规读取失败');return{data:[]}})
  assert.equal(page.loaded.value,false)
  await page.refresh()
  assert.equal(page.error.value,'违规读取失败')
  assert.equal(page.loaded.value,false)
  fail=false;await page.refresh()
  assert.equal(page.error.value,'')
  assert.equal(page.items.value.length,0)
  assert.equal(page.loaded.value,true)
  const source=await readFile(new URL('../src/components/ViolationsPanel.vue',import.meta.url),'utf8')
  assert.match(source,/v-else-if="error"/)
  assert.match(source,/v-else-if="!loaded"/)
  assert.match(source,/尚未读取本人独立违规记录/)
  assert.match(source,/尚无已登记的独立违规记录/)
})
test('historical credit display follows server legacy ban flag and removes new reward or score-based eligibility promises',async()=>{
  for(const file of ['ProfileView','HomeView','LoginView','MyReservationsView']){
    const source=await readFile(new URL(`../src/views/${file}.vue`,import.meta.url),'utf8')
    assert.doesNotMatch(source,/履约加分|违约扣分|低于 60|可正常预约|按期履约 \+1|不足 30 分钟的取消将扣除/)
    if(file==='ProfileView'){
      assert.match(source,/overview\.legacy_ban_active === true/)
      assert.match(source,/历史信用资料（只读）/)
      assert.doesNotMatch(source,/new Date\(until\)|scoreColor/)
    }
  }
})

test('an older mine response cannot restore pending actions after a newer deadline refresh',async()=>{
  const pending=[]
  const page=await mine({listMyReservations:()=>new Promise(resolve=>pending.push(resolve))})
  const older=page.refreshAll(),latest=page.refreshAll()
  pending[1]({data:[{id:10,status:'unused_ended',termination_reason:'no_show',allowed_actions:[]}]})
  await latest
  pending[0]({data:[waiting(['abandon'])]})
  await older
  assert.equal(page.list.value[0].status,'unused_ended')
  assert.equal(page.actionsOf(page.list.value[0]).length,0)
})

test('deferred violation panel stays uninitialized rather than claiming zero before its first response',async()=>{
  let release
  const page=await violations(()=>new Promise(resolve=>{release=resolve}))
  assert.equal(page.loaded.value,false)
  assert.equal(page.loading.value,false)
  const request=page.refresh()
  assert.equal(page.loading.value,true)
  assert.equal(page.loaded.value,false)
  release({data:[]})
  await request
  assert.equal(page.loaded.value,true)
  assert.equal(page.loading.value,false)
  assert.equal(page.items.value.length,0)
})

test('only current checkout action is available and confirmation prevents duplicate early checkout before real refresh',async()=>{
  let release
  const page=await mine({confirmDialog:text=>{assert.match(text,/释放剩余时段/);assert.match(text,/不新增信用奖惩/);return new Promise(resolve=>{release=resolve})},listMyReservations:async()=>({data:[{id:10,status:'completed',termination_reason:'early_checkout',allowed_actions:[],usage_record:{id:1,checkin_at:'2026-10-30T08:00:00',ended_at:'2026-10-30T08:01:00.5',duration_seconds:60.5,origin:'seat_code'}}]})})
  assert.deepEqual(Array.from(page.actionsOf({allowed_actions:['leave','return','checkout']}),action=>action.key),['checkout'])
  const attempt=page.doAction({id:10,status:'checked_in',allowed_actions:['checkout']},'checkout')
  await page.doAction({id:10,status:'checked_in',allowed_actions:['checkout']},'checkout')
  release();await attempt
  assert.equal(page.calls.filter(item=>item==='checkout').length,1)
  assert.equal(page.list.value[0].usage_record.duration_seconds,60.5)
  assert.equal(page.actionsOf(page.list.value[0]).length,0)
})
test('auto-end conflict explains committed end and displays re-read usage; failed reload never claims fresh usage',async()=>{
  const page=await mine({checkoutReservation:async()=>{throw{status:409,details:{status:'completed'},message:'已到期'}},listMyReservations:async()=>({data:[{id:10,status:'completed',allowed_actions:[],usage_record:{duration_seconds:3600.125,end_reason:'auto_end'}}]})})
  await page.doAction({id:10,allowed_actions:['checkout']},'checkout')
  assert.match(page.actionError.value,/预约已结束/);assert.equal(page.list.value[0].usage_record.end_reason,'auto_end')
  const failed=await mine({listMyReservations:async()=>{throw new Error('使用记录刷新失败')}})
  await failed.doAction({id:10,allowed_actions:['checkout']},'checkout');assert.equal(failed.error.value,'使用记录刷新失败')
})
test('late usage list response cannot restore earlier running duration after final settlement',async()=>{
  const pending=[]
  const page=await mine({listMyReservations:()=>new Promise(resolve=>pending.push(resolve))})
  const old=page.refreshAll(),latest=page.refreshAll()
  pending[1]({data:[{id:10,status:'completed',allowed_actions:[],usage_record:{duration_seconds:3600.125,end_reason:'auto_end'}}]});await latest
  pending[0]({data:[{id:10,status:'checked_in',allowed_actions:['checkout'],usage_record:{duration_seconds:120.25,ended_at:null}}]});await old
  assert.equal(page.list.value[0].usage_record.duration_seconds,3600.125);assert.equal(page.list.value[0].status,'completed')
})
test('current UI removes temporary leave navigation and adapters while retaining explicit historical explanation',async()=>{
  const mineSource=await readFile(new URL('../src/views/MyReservationsView.vue',import.meta.url),'utf8')
  assert.doesNotMatch(mineSource,/key: 'temp_leave'|leave:\{key|return:\{key|leaveReservation|returnReservation|临离开/)
  assert.match(mineSource,/UsageRecordSummary :record="row.usage_record"/)
  assert.match(mineSource,/历史暂离登记/);assert.match(mineSource,/已结束/)
  const api=await readFile(new URL('../src/api/reservation.js',import.meta.url),'utf8');assert.doesNotMatch(api,/leaveReservation|returnReservation/)
  const home=await readFile(new URL('../src/views/HomeView.vue',import.meta.url),'utf8');assert.doesNotMatch(home,/临时离开/)
})
