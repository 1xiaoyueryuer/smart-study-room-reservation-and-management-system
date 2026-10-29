import test from 'node:test'
import assert from 'node:assert/strict'
import vm from 'node:vm'
import { readFile } from 'node:fs/promises'
import { ref, computed, createSSRApp } from 'vue'
import * as vue from 'vue'
import * as ssr from 'vue/server-renderer'
import { compileTemplate, parse } from '@vue/compiler-sfc'
import { complaintTypes,complaintStatuses,verificationOutcomes,reservationStatuses,positiveId,verificationPayload,feedbackPayload } from '../src/composables/complaintForm.js'
const tick=()=>new Promise(resolve=>setImmediate(resolve))
const base=()=>({id:88,type:'occupation',status:'submitted',version:1,operation_id:'create-id',reporter:{id:9,real_name:'模拟投诉人',username:'reporter',campus_id:'DEMO9'},handler:null,accepted_at:null,description:'工程模拟：被占情况',submitted_at:'2026-10-30T08:00+08:00',reservation_id:5,room_id:1,context_snapshot:{room_name:'模拟提交空间',seat_no:'A1',reservation_id:5,date:'2026-10-29',start_time:'08:00',end_time:'10:00',status_at_submission:'completed'},current_context:{observed_at:'2026-10-30T09:00+08:00',reservation:null,room:null},attachment_count:0,attachments:[],events:[],admin_events:[]})
const verification=(overrides={})=>({outcome:'cooperated',occurred_at:'2026-10-29T08:30',public_note:'工程模拟：已记录配合情况',private_note:'工程模拟：核实事实，不代表真人现场检查',subject_user_id:null,identity_confirmed:false,identity_basis:'',facts_confirmed:true,valid_reservation_confirmed:true,...overrides})
async function script(file,mocks,exports,extra={}){const source=(await readFile(new URL(file,import.meta.url),'utf8')).split('<script setup>')[1].split('</script>')[0],context=vm.createContext({defineExpose(){},...extra}),module=new vm.SourceTextModule(source+`\nexport {${exports}}`,{context});await module.link(specifier=>{const values=mocks[specifier]||(specifier.endsWith('useServiceTargetClock.js')?{useServiceTargetClock:()=>({progress:()=>null})}:specifier.endsWith('.vue')?{default:{}}:null);assert.ok(values,specifier);return new vm.SyntheticModule(Object.keys(values),function(){for(const[key,value]of Object.entries(values))this.setExport(key,value)},{context})});await module.evaluate();return module.namespace}
async function management(overrides={}){
  const calls=[],cleanup=[];let state=base(),uuid=0
  const api={listAdminComplaints:async query=>{calls.push(['list',query]);return{data:[state]}},getAdminComplaint:async id=>{calls.push(['detail',id]);return{data:structuredClone(state)}},acceptComplaint:async(id,body)=>{calls.push(['accept',id,body]);state={...state,status:'processing',version:2,handler:{id:1,display_name:'模拟首受理人'},accepted_at:'2026-10-30T09:00+08:00'};return{data:{operation_id:body.operation_id,event_id:10,replayed:false,complaint:structuredClone(state)}}},recordComplaintVerification:async(id,body)=>{calls.push(['verification',id,body]);state={...state,version:state.version+1,admin_events:[...state.admin_events,{id:11,type:'verification',actor_display_name:'模拟追加管理员',outcome:body.outcome,private_note:body.private_note}]};return{data:{operation_id:body.operation_id,event_id:11,replayed:false,complaint:structuredClone(state)}}},feedbackComplaint:async()=>({}),...overrides}
  const page=await script('../src/components/admin/ComplaintManagement.vue',{vue:{ref,computed,onMounted(){},onBeforeUnmount:fn=>cleanup.push(fn)},'../../api/complaint':api,'../../api/admin':{listUsers:async()=>({data:[{id:9,username:'reporter',real_name:'模拟投诉人',status:'active'},{id:10,username:'subject',real_name:'模拟待核账号',status:'disabled'}]})},'../../api/room':{getRooms:async()=>({data:[]})},'../../composables/complaintForm.js':{complaintTypes,complaintStatuses,verificationOutcomes,reservationStatuses,positiveId,verificationPayload,feedbackPayload},'../ui/feedback':{confirmDialog:overrides.confirmDialog|| (async()=>{})}},'filters,appliedFilters,items,loaded,listError,selectedId,detail,detailError,form,submitting,unknown,readPending,pending,busy,actionError,writeNotice,loadOptions,loadList,openDetail,refreshDetail,changeOutcome,changeSubject,act,retryUnknown,subjectOptions,outcomeOptions,feedbackForm',{crypto:{randomUUID:()=>`00000000-0000-4000-8000-${String(++uuid).padStart(12,'0')}`}})
  return{...page,calls,cleanup,setState(value){state=value}}
}
test('verification guards distinguish facts and reservation rights, known identity and unrelated service without forcing occupancy',()=>{
  const row=base()
  assert.equal(verificationPayload(verification(),row).occurred_at,'2026-10-29T08:30:00+08:00')
  assert.throws(()=>verificationPayload(verification({valid_reservation_confirmed:false}),row),/有效预约/)
  assert.throws(()=>verificationPayload(verification({facts_confirmed:false}),row),/明确核实/)
  assert.throws(()=>verificationPayload(verification({subject_user_id:9,identity_confirmed:true,identity_basis:'工程模拟身份'}),row),/投诉人不能/)
  assert.throws(()=>verificationPayload(verification({subject_user_id:10}),row),/身份依据/)
  assert.throws(()=>verificationPayload(verification({outcome:'verified_refusal'}),row),/必须明确关联/)
  assert.throws(()=>verificationPayload(verification({outcome:'identity_pending',subject_user_id:10,identity_confirmed:true}),row),/不得关联账号/)
  assert.equal(verificationPayload(verification({outcome:'service_verified',valid_reservation_confirmed:false}),{...row,type:'service',reservation_id:null}).outcome,'service_verified')
  assert.throws(()=>verificationPayload(verification({outcome:'service_verified'}),row),/一般服务投诉/)
})
test('management list forwards applied real filters and detail starts with no guessed subject or identity confirmation',async()=>{
  const page=await management();assert.equal(page.loaded.value,false)
  page.filters.value={status:'submitted',type:'occupation',room_id:1};await page.loadList();assert.equal(page.calls[0][1].room_id,1);assert.equal(page.calls[0][1].type,'occupation')
  await page.openDetail(88);assert.equal(page.form.value.subject_user_id,null);assert.equal(page.form.value.identity_confirmed,false);assert.equal(page.detail.value.reporter.id,9)
  await page.loadOptions();page.changeOutcome('cooperated');assert.equal(page.subjectOptions.value.some(item=>item.value===9),false);assert.equal(page.subjectOptions.value.some(item=>item.value===10),true)
  page.form.value.identity_confirmed=true;page.form.value.identity_basis='旧目标依据';page.changeSubject(10);assert.equal(page.form.value.identity_confirmed,false);assert.equal(page.form.value.identity_basis,'')
})
test('acceptance refreshes real processing state while later administrator append does not replace first handler',async()=>{
  const page=await management();await page.openDetail(88);await page.act('accept')
  assert.equal(page.detail.value.status,'processing');assert.equal(page.detail.value.handler.id,1)
  page.form.value=verification();await page.act('verification');assert.equal(page.detail.value.handler.id,1);assert.equal(page.detail.value.admin_events[0].actor_display_name,'模拟追加管理员');assert.match(page.writeNotice.value,/提醒不计违规/)
})
test('competing admin acceptance conflict reloads server state and preserves verification draft instead of overwriting first handler',async()=>{
  const page=await management({acceptComplaint:async()=>{page.setState({...base(),status:'processing',version:2,handler:{id:2,display_name:'先受理管理员'}});throw{status:409,message:'已受理'}}})
  await page.openDetail(88);page.form.value.private_note='工程模拟草稿保留';await page.act('accept');assert.equal(page.detail.value.handler.id,2);assert.match(page.actionError.value,/刷新后的真实进度/);assert.equal(page.form.value.private_note,'工程模拟草稿保留')
})
test('unknown operation retries same endpoint, id, version and immutable body without duplicate automatic writes',async()=>{
  const bodies=[];let page
  page=await management({acceptComplaint:async(id,body)=>{bodies.push({id,body});if(bodies.length===1)throw{status:null};const saved={...base(),status:'processing',version:2,handler:{id:1,display_name:'模拟管理员'}};page.setState(saved);return{data:{operation_id:body.operation_id,event_id:10,replayed:true,complaint:saved}}}})
  await page.openDetail(88);await page.act('accept');assert.equal(page.unknown.value,true);assert.equal(bodies.length,1);await page.openDetail(99);assert.equal(page.selectedId.value,88)
  page.form.value.public_note='不能污染原受理body';await page.retryUnknown();assert.deepEqual(bodies[0],bodies[1]);assert.equal(page.unknown.value,false);assert.equal(page.actionError.value,'')
})
test('acknowledged write followed by failed GET locks further writes and only retries reads',async()=>{
  let broken=false,accepted=0
  const page=await management({getAdminComplaint:async()=>{if(broken)throw new Error('重读失败');return{data:base()}},acceptComplaint:async(id,body)=>{accepted++;broken=true;return{data:{operation_id:body.operation_id,event_id:10,replayed:false,complaint:{...base(),status:'processing',version:2}}}}})
  await page.openDetail(88);await page.act('accept');assert.equal(page.readPending.value,true);assert.match(page.detailError.value,/操作已保存/);await page.act('accept');assert.equal(accepted,1);await page.refreshDetail();assert.equal(accepted,1)
})
test('confirmation freezes verification fields before dialog and prevents duplicate submissions',async()=>{
  let release
  const page=await management({confirmDialog:()=>new Promise(resolve=>{release=resolve})});page.setState({...base(),status:'processing',handler:{id:1,display_name:'管理员'}});await page.openDetail(88);page.form.value=verification()
  const first=page.act('verification');assert.equal(page.submitting.value,true);page.form.value.public_note='后来更改';await page.act('verification');release();await first
  const writes=page.calls.filter(call=>call[0]==='verification');assert.equal(writes.length,1);assert.equal(writes[0][2].public_note,'工程模拟：已记录配合情况')
})
test('older detail response and response after unmount cannot replace a newer selected complaint',async()=>{
  const pending=[],page=await management({getAdminComplaint:id=>new Promise(resolve=>pending.push({id,resolve}))})
  const old=page.openDetail(88),latest=page.openDetail(99);pending[1].resolve({data:{...base(),id:99}});await latest;pending[0].resolve({data:base()});await old;assert.equal(page.detail.value.id,99)
  const last=page.openDetail(100);page.cleanup[0]();pending[2].resolve({data:{...base(),id:100}});await last;assert.equal(page.detail.value,null)
})
test('reminder panel keeps unread/failure distinct from zero and never combines reminders with violations or complaint links',async()=>{
  let fail=true
  const page=await script('../src/components/RemindersPanel.vue',{vue:{ref,onMounted(){},onBeforeUnmount(){}},'../api/complaint':{listMyReminders:async()=>{if(fail)throw new Error('提醒读取失败');return{data:[]}}}},'items,loaded,error,load')
  assert.equal(page.loaded.value,false);await page.load();assert.equal(page.loaded.value,false);assert.match(page.error.value,/读取失败/);fail=false;await page.load();assert.equal(page.loaded.value,true);assert.equal(page.items.value.length,0)
  const source=await readFile(new URL('../src/components/RemindersPanel.vue',import.meta.url),'utf8');assert.doesNotMatch(source,/complaint_id|RouterLink|violations\.length/);assert.match(source,/不计违规/)
})

test('actual public record SSR shows processing and public timeline without management-only identity or facts',async()=>{
  const source=await readFile(new URL('../src/components/ComplaintRecord.vue',import.meta.url),'utf8'),{descriptor}=parse(source)
  const compiled=compileTemplate({source:descriptor.template.content,id:'complaint-record',filename:'ComplaintRecord.vue',ssr:true,ssrCssVars:[]}),context=vm.createContext({}),module=new vm.SourceTextModule(compiled.code,{context})
  assert.equal(compiled.errors.length,0)
  await module.link(specifier=>{const values=specifier==='vue'?vue:ssr;return new vm.SyntheticModule(Object.keys(values),function(){for(const[key,value]of Object.entries(values))this.setExport(key,value)},{context})});await module.evaluate()
  const record={...base(),status:'processing',handler:{display_name:'模拟首受理人'},accepted_at:'2026-10-30T09:00+08:00',events:[{id:1,type:'accepted',created_at:'2026-10-30T09:00+08:00',public_note:'开始核查'},{id:2,type:'verification',created_at:'2026-10-30T10:00+08:00',outcome:'identity_pending',public_note:'正在核实相关情况'}],admin_events:[{id:2,private_note:'PRIVATE_SECRET_NEVER_PUBLIC',identity_basis:'IDENTITY_SECRET',subject:{id:10,real_name:'SUBJECT_SECRET'}}]}
  const app=createSSRApp({props:['record'],setup:()=>({complaintTypes,complaintStatuses,verificationOutcomes,reservationStatuses}),ssrRender:module.namespace.ssrRender},{record});app.component('ProtectedAttachment',{render:()=>null})
  const html=await ssr.renderToString(app)
  assert.match(html,/处理中/);assert.match(html,/已受理，开始核查/);assert.match(html,/身份待核/);assert.match(html,/正在核实相关情况/);assert.doesNotMatch(html,/PRIVATE_SECRET|IDENTITY_SECRET|SUBJECT_SECRET/)
})
test('admin complaint tab is lazy on first use and retains state after leaving the tab; old resource help is corrected',async()=>{
  const page=await script('../src/views/admin/AdminView.vue',{vue:{ref}},'activeTab,complaintsMounted,selectTab')
  assert.equal(page.complaintsMounted.value,false);page.selectTab('users');assert.equal(page.complaintsMounted.value,false);page.selectTab('complaints');assert.equal(page.complaintsMounted.value,true);page.selectTab('rooms');assert.equal(page.complaintsMounted.value,true)
  const source=await readFile(new URL('../src/views/admin/AdminView.vue',import.meta.url),'utf8');assert.match(source,/v-if="complaintsMounted"/);assert.match(source,/管理员自报已现场协调/);assert.doesNotMatch(source,/有有效预约的影响性变更会拒绝/)
})
test('management API adapters keep real filter, version and operation UUID contracts and reminders use a separate owner endpoint',async()=>{
  const calls=[],context=vm.createContext({FormData}),module=new vm.SourceTextModule(await readFile(new URL('../src/api/complaint.js',import.meta.url),'utf8'),{context})
  await module.link(()=>new vm.SyntheticModule(['default'],function(){this.setExport('default',{get:(...args)=>calls.push(['get',...args]),post:(...args)=>calls.push(['post',...args])})},{context}));await module.evaluate()
  module.namespace.listAdminComplaints({status:'processing',type:'service'});module.namespace.acceptComplaint(88,{version:2,operation_id:'same-key'});module.namespace.recordComplaintVerification(88,{version:3,operation_id:'verification-key'});module.namespace.listMyReminders()
  assert.equal(calls[0][2].params.status,'processing');assert.equal(calls[1][1],'/admin/complaints/88/accept');assert.equal(calls[1][2].version,2);assert.equal(calls[2][1],'/admin/complaints/88/verifications');assert.equal(calls[3][1],'/reminders/mine')
})

test('formal feedback uses independent public result/basis and current version, without copying verification private notes',async()=>{
  let body,writes=0
  const page=await management({feedbackComplaint:async(id,input)=>{writes++;body=input;const saved={...base(),status:'awaiting_confirmation',version:3,admin_allowed_actions:[]};page.setState(saved);return{data:{operation_id:input.operation_id,event_id:12,replayed:false,complaint:saved}}}})
  page.setState({...base(),status:'processing',version:2,admin_allowed_actions:['feedback']});await page.openDetail(88)
  page.form.value.private_note='PRIVATE_MUST_NOT_BE_COPIED';page.feedbackForm.value={result:'工程模拟公开结果',basis:'工程模拟公开依据'};await page.act('feedback')
  assert.equal(body.result,'工程模拟公开结果');assert.equal(body.basis,'工程模拟公开依据');assert.equal(body.version,2);assert.equal('private_note' in body,false);assert.equal(page.detail.value.status,'awaiting_confirmation');assert.equal(page.feedbackForm.value.result,'')
  await page.act('feedback');assert.equal(page.detail.value.version,3);assert.equal(writes,1)
})
test('feedback guard rejects empty or oversized public fields and never introduces an awaiting overwrite action',async()=>{
  assert.throws(()=>feedbackPayload({result:'',basis:'依据'}),/1至2000/);assert.throws(()=>feedbackPayload({result:'字'.repeat(2001),basis:'依据'}),/1至2000/)
  let writes=0;const page=await management({feedbackComplaint:async()=>{writes++;return{}}});page.setState({...base(),status:'awaiting_confirmation',admin_allowed_actions:[]});await page.openDetail(88);await page.act('feedback');assert.equal(writes,0)
})

test('formal feedback unknown retry keeps the original feedback endpoint, public fields and operation key',async()=>{
  const bodies=[];let page
  page=await management({feedbackComplaint:async(id,body)=>{bodies.push({id,body});if(bodies.length===1)throw{status:null};const saved={...base(),status:'awaiting_confirmation',version:3,admin_allowed_actions:[]};page.setState(saved);return{data:{operation_id:body.operation_id,event_id:12,replayed:true,complaint:saved}}}})
  page.setState({...base(),status:'processing',version:2,admin_allowed_actions:['feedback']});await page.openDetail(88);page.feedbackForm.value={result:'工程模拟原反馈',basis:'工程模拟公开依据'};await page.act('feedback');assert.equal(page.unknown.value,true);assert.equal(bodies.length,1)
  page.feedbackForm.value.result='后来编辑不能污染原请求';await page.retryUnknown();assert.deepEqual(bodies[0],bodies[1]);assert.equal(page.detail.value.status,'awaiting_confirmation');assert.equal(page.unknown.value,false)
})
