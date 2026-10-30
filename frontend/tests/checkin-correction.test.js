import test from 'node:test'
import assert from 'node:assert/strict'
import vm from 'node:vm'
import {readFile} from 'node:fs/promises'
import * as vue from 'vue'
import * as helpers from '../src/composables/checkinCorrection.js'
const original=()=>({reservation:{id:70,status:'pending'},server_now:'2026-10-30T10:10:00.123456+08:00',deadline:'2026-10-30T10:15+08:00',can_correct:true,blocked_reason:null,management_correction:null})
const intent=()=>({checkin_at:'2026-10-30T10:05:00.123456+08:00',reason:'公开核实',verification_note:'PRIVATE_S23',facts_confirmed:true})
function receipt(frozen){return {...original(),operation_id:frozen.operation_id,replayed:false,can_correct:false,reservation:{id:70,status:'checked_in',usage_record:{id:8,origin:'admin_correction',checkin_at:'2026-10-30T10:05:00.123456',correction:{operated_at:'2026-10-30T10:10:00.123456+08:00',reason:frozen.reason,actor_display_name:'模拟管理员',source:'admin_self_report'}}},management_correction:{correction_operation:frozen.operation_id,correction_actor:2,corrected_at:'2026-10-30T10:10:00.123456',correction_note:frozen.verification_note}}}
async function page(request){const source=(await readFile(new URL('../src/components/admin/CheckinCorrection.vue',import.meta.url),'utf8')).split('<script setup>')[1].split('</script>')[0],cleanup=[],context=vm.createContext({crypto:{randomUUID:()=>crypto.randomUUID()}}),module=new vm.SourceTextModule(source+'\nexport {id,detail,busy,error,success,dialog,pending,stale,form,load,preview,submit}',{context});await module.link(specifier=>{const values=specifier==='vue'?{ref:vue.ref,onBeforeUnmount:fn=>cleanup.push(fn)}:specifier.endsWith('/request')?{default:request}:specifier.endsWith('checkinCorrection.js')?helpers:{default:{}};return new vm.SyntheticModule(Object.keys(values),function(){for(const[k,v]of Object.entries(values))this.setExport(k,v)},{context})});await module.evaluate();return {...module.namespace,cleanup}}
test('exact receipt rejects wrong target, business microsecond, reason, source, operation and missing actual operator data',()=>{
 const frozen={id:70,body:{...intent(),operation_id:'original'}};assert.equal(helpers.validCorrectionReceipt(receipt(frozen.body),frozen),true)
 for(const corrupt of [v=>v.reservation.id=71,v=>v.reservation.usage_record.checkin_at='2026-10-30T10:05:00.123455',v=>v.reservation.usage_record.correction.reason='different',v=>v.reservation.usage_record.correction.source='seat_code',v=>v.management_correction.correction_operation='other',v=>v.management_correction.correction_note='other',v=>v.reservation.usage_record.correction.actor_display_name='',v=>v.replayed=null,v=>v.reservation.status='completed']){const v=receipt(frozen.body);corrupt(v);assert.equal(helpers.validCorrectionReceipt(v,frozen),false)}
 const replay=receipt(frozen.body);replay.replayed=true;replay.reservation.status='completed';assert.equal(helpers.validCorrectionReceipt(replay,frozen),true)
})
test('unknown response freezes original UUID, target and body, and only explicit retry sends again',async()=>{
 const posts=[];const p=await page({get:async()=>({data:original()}),post:async(path,body)=>{posts.push({path,body:JSON.parse(JSON.stringify(body))});if(posts.length===1)throw {status:null,message:'结果未知'};return {data:receipt(body)}}});p.id.value='70';await p.load();p.form.value=intent();p.preview();await p.submit();assert.ok(p.pending.value);p.form.value.reason='later change';p.id.value='71';await p.load();assert.equal(posts.length,1);assert.equal(p.detail.value.reservation.id,70);await p.submit();assert.deepEqual(posts[0],posts[1]);assert.equal(p.pending.value,null);assert.match(p.success.value,/70/);await p.submit();assert.equal(posts.length,2)
})
test('definitive 409 preserves same-target draft and refresh requires a new confirmation, while another target starts blank',async()=>{
 let requested=70;const p=await page({get:async path=>({data:{...original(),reservation:{id:Number(path.match(/reservations\/(\d+)/)[1]),status:'pending'}}}),post:async()=>{throw {status:409,message:'已经过期'}}});p.id.value='70';await p.load();p.form.value=intent();p.preview();await p.submit();assert.equal(p.stale.value,true);assert.equal(p.pending.value,null);assert.equal(p.form.value.verification_note,'PRIVATE_S23');await p.load();assert.equal(p.form.value.reason,'公开核实');p.id.value='71';await p.load();assert.equal(p.form.value.reason,'');assert.equal(p.success.value,'');p.id.value='70';await p.load();assert.equal(p.form.value.verification_note,'PRIVATE_S23')
})
test('malformed success remains uncertain without false success and retries the original request',async()=>{
 let count=0;const p=await page({get:async()=>({data:original()}),post:async(path,body)=>{const value=receipt(body);if(++count===1)value.reservation.usage_record.checkin_at='2026-10-30T10:04:00';return {data:value}}});p.id.value='70';await p.load();p.form.value=intent();p.preview();await p.submit();assert.ok(p.pending.value);assert.equal(p.success.value,'');await p.submit();assert.equal(p.pending.value,null);assert.match(p.success.value,/已保存/)
})
test('unmount discards late read and write responses',async()=>{
 let release;const p=await page({get:()=>new Promise(resolve=>release=()=>resolve({data:original()}))});p.id.value='70';const loading=p.load();p.cleanup.forEach(fn=>fn());release();await loading;assert.equal(p.detail.value,null)
 let finish;const q=await page({get:async()=>({data:original()}),post:async(path,body)=>new Promise(resolve=>finish=()=>resolve({data:receipt(body)}))});q.id.value='70';await q.load();q.form.value=intent();q.preview();const saving=q.submit();q.cleanup.forEach(fn=>fn());finish();await saving;assert.equal(q.success.value,'')
})
test('intent validates required facts, text lengths and precise offset before confirmation',()=>{
 assert.equal(helpers.correctionIntent({...intent(),reason:' 公开核实 '}).reason,'公开核实');for(const change of [{facts_confirmed:false},{reason:''},{verification_note:'x'.repeat(2001)},{checkin_at:'2026-10-30T10:05'},{checkin_at:'2026-10-30T10:05:00.1234567+08:00'}])assert.throws(()=>helpers.correctionIntent({...intent(),...change}))
})
