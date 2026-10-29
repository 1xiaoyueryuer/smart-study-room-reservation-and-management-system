import test from 'node:test'
import assert from 'node:assert/strict'
import vm from 'node:vm'
import { readFile } from 'node:fs/promises'
import * as vue from 'vue'
import * as ssr from 'vue/server-renderer'
import { compileTemplate,parse } from '@vue/compiler-sfc'
import { targetProjection,targetFingerprint,formatTargetDuration } from '../src/composables/useServiceTargetClock.js'
const settle=()=>new Promise(resolve=>setImmediate(resolve))
const row=(patch={})=>({id:88,server_now:'2026-10-30T10:00:00+08:00',campus_zone:'Asia/Shanghai',service_target:{kind:'acceptance',calculation_status:'calculated',status:'running',anchor_at:'2026-10-30T09:45:02+08:00',due_at:'2026-10-30T10:00:02+08:00',remaining_seconds:2,reason:'工程模拟：提交时开放',room_version:1,calculated_at:'2026-10-30T09:45:02+08:00',basis:'open_at_submission',completed_at:null,stopped_at:null,was_late:null,overdue_event:null,...patch}})
async function clock({initial=[row()],onDue=async()=>{},locked=false}={}) {
  const mounted=[],cleanup=[],timers=new Map();let now=100,serial=0
  const context=vm.createContext({Date,performance:{now:()=>now},setInterval(){},clearInterval(){}})
  const module=new vm.SourceTextModule(await readFile(new URL('../src/composables/useServiceTargetClock.js',import.meta.url),'utf8'),{context})
  await module.link(()=>new vm.SyntheticModule(['ref','watch','onMounted','onBeforeUnmount'],function(){this.setExport('ref',vue.ref);this.setExport('watch',(source,callback,options)=>vue.watch(source,callback,{...options,flush:'sync'}));this.setExport('onMounted',fn=>mounted.push(fn));this.setExport('onBeforeUnmount',fn=>cleanup.push(fn))},{context}));await module.evaluate()
  const records=vue.ref(initial),blocked=vue.ref(locked),value=module.namespace.useServiceTargetClock({records,blocked,onDue,monotonic:()=>now,schedule:fn=>{const id=++serial;timers.set(id,fn);return id},cancel:id=>timers.delete(id)})
  mounted.forEach(fn=>fn())
  return{...value,records,blocked,timers,async advance(ms){now+=ms;[...timers.values()].forEach(fn=>fn());await settle()},stop(){cleanup.forEach(fn=>fn())}}
}
test('target clock advances only monotonic elapsed despite browser wall clock jumps and retains fractional server seconds',async()=>{
  const page=await clock({initial:[row({remaining_seconds:90.25})]});const original=Date.now
  try{Date.now=()=>-999999999999;await page.advance(500);assert.equal(page.progress(page.records.value[0]).remaining_seconds,89.75);Date.now=()=>9999999999999;await page.advance(1000);assert.equal(page.progress(page.records.value[0]).remaining_seconds,88.75)}finally{Date.now=original;page.stop()}
})
test('multiple targets expiring together trigger one batched GET and each fingerprint is checked at most once',async()=>{
  const batches=[];const page=await clock({initial:[row(),{...row(),id:89}],onDue:async records=>batches.push(records.map(record=>record.id))});await page.advance(2000);assert.equal(batches.length,1);assert.deepEqual([...batches[0]],[88,89]);assert.equal(page.progress(page.records.value[0]).awaiting_check,true)
  await page.advance(60000);page.records.value=page.records.value.map(record=>structuredClone(vue.toRaw(record)));await settle();assert.equal(batches.length,1);page.stop()
})
test('writing or unknown-result lock postpones due refresh until recovery without changing target state locally',async()=>{
  let reads=0;const page=await clock({locked:true,onDue:async()=>reads++});await page.advance(5000);assert.equal(reads,0);assert.equal(page.records.value[0].service_target.status,'running');page.blocked.value=false;await settle();assert.equal(reads,1);await page.advance(1000);assert.equal(reads,1);page.stop()
})
test('terminal and unknown targets have no countdown timer, completion does not restart on later feedback, and unmount cancels it',async()=>{
  for(const status of ['completed','late_completed','withdrawn','pending','legacy_unresolved']){const page=await clock({initial:[row({status,remaining_seconds:null,due_at:status==='pending'?null:row().service_target.due_at})]});assert.equal(page.timers.size,0);assert.equal(page.progress(page.records.value[0]).remaining_seconds,null);page.stop()}
  const page=await clock();assert.equal(page.timers.size,1);page.records.value=[row({status:'completed',completed_at:'2026-10-30T10:00:01+08:00',remaining_seconds:null})];assert.equal(page.timers.size,0);page.stop();assert.equal(page.timers.size,0)
})
test('new server snapshot reanchors remaining time instead of applying prior elapsed or changing a frozen due date',async()=>{
  const page=await clock({initial:[row({remaining_seconds:20})]});await page.advance(5000);assert.equal(page.progress(page.records.value[0]).remaining_seconds,15);page.records.value=[{...row({remaining_seconds:14.75}),server_now:'2026-10-30T10:00:05.25+08:00'}];assert.equal(page.progress(page.records.value[0]).remaining_seconds,14.75);await page.advance(1000);assert.equal(page.progress(page.records.value[0]).remaining_seconds,13.75);assert.equal(page.records.value[0].service_target.due_at,'2026-10-30T10:00:02+08:00');page.stop()
})
test('failed due GET leaves local pending verification and never retries automatically; manual server refresh can confirm actual overdue',async()=>{
  let reads=0;const page=await clock({onDue:async()=>{reads++;throw new Error('读取失败')}});await page.advance(2500);assert.equal(page.progress(page.records.value[0]).awaiting_check,true);await page.advance(100000);assert.equal(reads,1);page.records.value=[{...row({status:'overdue',remaining_seconds:0}),server_now:'2026-10-30T10:00:05+08:00'}];assert.equal(page.progress(page.records.value[0]).awaiting_check,false);assert.equal(page.progress(page.records.value[0]).overdue_seconds,3);page.stop()
})
test('server overdue estimates use explicit offset snapshots, unknown terminal remaining is not forged as zero',()=>{
  assert.equal(targetProjection({...row({status:'overdue'}),server_now:'2026-10-30T10:00:05+08:00'},1.25).overdue_seconds,4.25)
  assert.equal(targetProjection({...row({status:'overdue'}),server_now:'2026-10-30T10:00:05'},100).overdue_seconds,null)
  assert.equal(targetProjection(row({status:'pending',remaining_seconds:null,due_at:null})).remaining_seconds,null)
  assert.equal(formatTargetDuration(86461.01),'1天1分2秒');assert.equal(targetFingerprint(row()),targetFingerprint({...row(),server_now:'2030-01-01T00:00+08:00'}))
})
async function targetHtml(record,progress=null){
  const source=await readFile(new URL('../src/components/ServiceTargetSummary.vue',import.meta.url),'utf8'),{descriptor}=parse(source),context=vm.createContext({defineProps:()=>({record,progress,compact:false})})
  const script=new vm.SourceTextModule(descriptor.scriptSetup.content+'\nexport {target,view,statuses,bases,formatTargetDuration}',{context});await script.link(specifier=>{const values=specifier==='vue'?vue:{targetProjection,formatTargetDuration};return new vm.SyntheticModule(Object.keys(values),function(){for(const[key,value]of Object.entries(values))this.setExport(key,value)},{context})});await script.evaluate()
  const compiled=compileTemplate({source:descriptor.template.content,filename:'ServiceTargetSummary.vue',id:'s17target',ssr:true,ssrCssVars:[]}),module=new vm.SourceTextModule(compiled.code,{context});await module.link(specifier=>{const values=specifier==='vue'?vue:ssr;return new vm.SyntheticModule(Object.keys(values),function(){for(const[key,value]of Object.entries(values))this.setExport(key,value)},{context})});await module.evaluate()
  return ssr.renderToString(vue.createSSRApp({props:['record','progress','compact'],setup:()=>script.namespace,ssrRender:module.namespace.ssrRender},{record,progress,compact:false}))
}
test('actual target template distinguishes unresolved, completed without due, withdrawn and recorded late detection',async()=>{
  const unresolved=await targetHtml(row({status:'legacy_unresolved',due_at:null,remaining_seconds:null,reason:'缺少提交时开放历史依据'}));assert.match(unresolved,/缺少提交时开放历史依据/);assert.doesNotMatch(unresolved,/冻结目标时刻|剩余：0/)
  const completed=await targetHtml(row({calculation_status:'legacy_unresolved',status:'completed',due_at:null,remaining_seconds:null,completed_at:'2026-10-30T10:00+08:00',was_late:null}));assert.match(completed,/已完成目标/);assert.match(completed,/未判定是否按时/);assert.doesNotMatch(completed,/已按时完成/)
  const withdrawn=await targetHtml(row({status:'withdrawn',remaining_seconds:null,stopped_at:'2026-10-30T10:00+08:00'}));assert.match(withdrawn,/撤回停止不等于目标已完成/)
  const overdue=await targetHtml(row({status:'overdue',remaining_seconds:0,overdue_event:{occurred_at:'2026-10-30T10:00:02+08:00',detected_at:'2026-10-30T10:05+08:00',source:'legacy_derived'}}));assert.match(overdue,/实际检测登记/);assert.match(overdue,/历史派生目标首次检测/);assert.match(overdue,/不计用户违规/)
  const local=await targetHtml(row(),{remaining_seconds:0,awaiting_check:true,overdue_seconds:null});assert.match(local,/待服务端核对/);assert.doesNotMatch(local,/服务端已确认超时/)
})

test('actual owner page auto-read preserves prior target on failure, respects write locks and rejects a late old target response',async()=>{
  const source=(await readFile(new URL('../src/views/ComplaintDetailView.vue',import.meta.url),'utf8')).split('<script setup>')[1].split('</script>')[0]
  const props={id:'88'},pending=[],cleanup=[];let binding,changed
  const context=vm.createContext({defineProps:()=>props,crypto:{randomUUID:()=> 'fixed-key'}}),module=new vm.SourceTextModule(source+'\nexport {record,error,load,refresh,submitting,unknown,readPending,description}',{context})
  await module.link(specifier=>{const values=specifier==='vue'?{ref:vue.ref,computed:vue.computed,watch:(value,fn)=>{changed=fn;fn()},onBeforeUnmount:fn=>cleanup.push(fn)}:specifier==='../api/complaint'?{getComplaint:id=>new Promise((resolve,reject)=>pending.push({id,resolve,reject})),withdrawComplaint(){},confirmComplaintFeedback(){},objectComplaintFeedback(){}}:specifier.endsWith('useServiceTargetClock.js')?{useServiceTargetClock:options=>{binding=options;return{progress:()=>null}}}:specifier.endsWith('complaintForm.js')?{ownerComplaintActions:()=>[]}:specifier.endsWith('/feedback')?{confirmDialog:async()=>{}}:{default:{}};return new vm.SyntheticModule(Object.keys(values),function(){for(const[key,value]of Object.entries(values))this.setExport(key,value)},{context})});await module.evaluate();const page=module.namespace
  const detail=id=>({...row(),id,events:[],attachments:[]});pending.shift().resolve({data:detail(88)});await settle();page.description.value='原输入'
  page.submitting.value=true;assert.equal(binding.blocked.value,true);page.submitting.value=false;page.unknown.value=true;assert.equal(binding.blocked.value,true);page.unknown.value=false;page.readPending.value=true;assert.equal(binding.blocked.value,true);page.readPending.value=false
  const auto=binding.onDue();pending.shift().reject(new Error('工程模拟核对失败'));await auto;assert.equal(page.record.value.id,88);assert.equal(page.record.value.service_target.status,'running');assert.match(page.error.value,/核对失败/);assert.equal(page.description.value,'原输入')
  const retry=page.refresh();pending.shift().resolve({data:detail(88)});await retry;assert.equal(page.error.value,'')
  const old=binding.onDue(),oldRequest=pending.shift();props.id='99';changed();pending.shift().resolve({data:detail(99)});await settle();oldRequest.resolve({data:detail(88)});await old;assert.equal(page.record.value.id,99);cleanup.forEach(fn=>fn())
})
