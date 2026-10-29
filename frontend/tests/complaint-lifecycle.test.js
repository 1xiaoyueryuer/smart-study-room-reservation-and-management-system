import test from 'node:test'
import assert from 'node:assert/strict'
import vm from 'node:vm'
import { readFile } from 'node:fs/promises'
import { ref,computed,createSSRApp } from 'vue'
import * as vue from 'vue'
import * as ssr from 'vue/server-renderer'
import { compileTemplate,parse } from '@vue/compiler-sfc'
import { ownerComplaintActions,complaintTypes,complaintStatuses,verificationOutcomes,reservationStatuses } from '../src/composables/complaintForm.js'
const tick=()=>new Promise(resolve=>setImmediate(resolve))
const awaiting=()=>({id:88,version:3,type:'service',status:'awaiting_confirmation',allowed_actions:['confirm','object'],server_now:'2026-10-30T10:00:00.000001+08:00',campus_zone:'Asia/Shanghai',description:'工程模拟情况',context_snapshot:null,submitted_at:'2026-10-29T08:00+08:00',attachment_count:0,attachments:[],events:[],current_feedback:{id:5,result:'工程模拟公开结果',basis:'工程模拟公开依据',feedback_at:'2026-10-30T09:00+08:00',deadline:'2026-11-06T09:00+08:00',response_type:null,responded_at:null,actor_display_name:'模拟管理员'}})
async function script(file,mocks,exports,extra={}){const source=(await readFile(new URL(file,import.meta.url),'utf8')).split('<script setup>')[1].split('</script>')[0],context=vm.createContext(extra),module=new vm.SourceTextModule(source+`\nexport {${exports}}`,{context});await module.link(specifier=>{const values=mocks[specifier]||(specifier.endsWith('.vue')?{default:{}}:null);assert.ok(values,specifier);return new vm.SyntheticModule(Object.keys(values),function(){for(const[key,value]of Object.entries(values))this.setExport(key,value)},{context})});await module.evaluate();return module.namespace}
async function owner(overrides={}){
  const calls=[],cleanup=[];let state=awaiting(),uuid=0,routeChanged
  const props={id:'88'}
  const write=action=>async(id,body)=>{calls.push([action,id,body]);state={...state,version:state.version+1,status:action==='object'?'processing':action==='withdraw'?'withdrawn':'closed',allowed_actions:[],closure_type:action==='confirm'?'confirmed':null,events:[{id:10,type:action==='object'?'objected':action==='withdraw'?'withdrawn':'confirmed',description:body.description}]};return{data:{operation_id:body.operation_id,event_id:10,replayed:false,complaint:state}}}
  const api={getComplaint:async id=>{calls.push(['detail',id]);return{data:structuredClone(state)}},withdrawComplaint:write('withdraw'),confirmComplaintFeedback:write('confirm'),objectComplaintFeedback:write('object'),...overrides}
  const page=await script('../src/views/ComplaintDetailView.vue',{vue:{ref,computed,onBeforeUnmount:fn=>cleanup.push(fn),watch:(source,fn)=>{routeChanged=fn;fn()}},'../api/complaint':api,'../composables/complaintForm.js':{ownerComplaintActions},'../components/ui/feedback':{confirmDialog:overrides.confirmDialog|| (async()=>{})}},'record,loading,error,description,submitting,unknown,readPending,pending,actionError,writeNotice,actions,busy,load,act,retryUnknown,refresh',{defineProps:()=>props,crypto:{randomUUID:()=>`00000000-0000-4000-8000-${String(++uuid).padStart(12,'0')}`}})
  await tick();return{...page,calls,cleanup,setState(value){state=value},changeId(value){props.id=value;routeChanged()}}
}
test('owner actions use server permission flags without browser date guesses and terminal states have no writes',()=>{
  assert.deepEqual(ownerComplaintActions({status:'submitted',allowed_actions:['withdraw','fake']}),['withdraw'])
  assert.deepEqual(ownerComplaintActions({...awaiting(),allowed_actions:[]}),[])
  assert.deepEqual(ownerComplaintActions({...awaiting(),status:'closed',allowed_actions:['confirm','object']}),[])
  assert.deepEqual(ownerComplaintActions({status:'processing'}),[])
})
test('owner confirmation records explicit user closure only after a real receipt and authoritative detail refresh',async()=>{
  const page=await owner();await page.act('confirm');assert.equal(page.calls.filter(call=>call[0]==='confirm').length,1);assert.equal(page.record.value.status,'closed');assert.equal(page.record.value.closure_type,'confirmed');assert.match(page.writeNotice.value,/确认本次反馈并结案已记录/);assert.equal(page.actions.value.length,0)
})
test('objection validates description, returns to processing and retains input on known conflict',async()=>{
  const page=await owner();await page.act('object');assert.match(page.actionError.value,/1至2000/);assert.equal(page.calls.filter(call=>call[0]==='object').length,0)
  page.description.value=' 工程模拟：需补充处理 ';await page.act('object');assert.equal(page.record.value.status,'processing');assert.equal(page.calls.find(call=>call[0]==='object')[2].description,'工程模拟：需补充处理');assert.equal(page.description.value,'')
  const rejected=await owner({objectComplaintFeedback:async()=>{throw{status:409,message:'版本变化'}}});rejected.description.value='工程模拟异议草稿';await rejected.act('object');assert.equal(rejected.description.value,'工程模拟异议草稿');assert.equal(rejected.writeNotice.value,'')
})
test('withdrawal is available for submitted only and uses the real owner endpoint',async()=>{
  const page=await owner();const submitted={...awaiting(),status:'submitted',version:1,current_feedback:null,allowed_actions:['withdraw']};page.setState(submitted);await page.load();await page.act('withdraw');assert.equal(page.record.value.status,'withdrawn');assert.equal(page.calls.filter(call=>call[0]==='withdraw').length,1);await page.act('withdraw');assert.equal(page.calls.filter(call=>call[0]==='withdraw').length,1)
})
test('expired action 409 shows actual system closure rather than user-confirmation success',async()=>{
  let page
  page=await owner({confirmComplaintFeedback:async()=>{page.setState({...awaiting(),status:'closed',version:4,closure_type:'auto_closed',closed_at:'2026-11-06T09:00:01+08:00',allowed_actions:[]});throw{status:409,message:'确认期限已届满',details:{complaint_id:88,status:'closed',closure_type:'auto_closed'}}}})
  await page.act('confirm');assert.equal(page.record.value.status,'closed');assert.equal(page.record.value.closure_type,'auto_closed');assert.match(page.actionError.value,/确认本次反馈并结案未执行/);assert.equal(page.writeNotice.value,'');assert.equal(page.actions.value.length,0)
})
test('unknown owner action keeps endpoint/id/version/body/key and only retries by explicit user action',async()=>{
  const bodies=[];let page
  page=await owner({objectComplaintFeedback:async(id,body)=>{bodies.push({id,body});if(bodies.length===1)throw{status:null};const saved={...awaiting(),status:'processing',version:4,allowed_actions:[]};page.setState(saved);return{data:{operation_id:body.operation_id,event_id:10,replayed:true,complaint:saved}}}})
  page.description.value='工程模拟原异议';await page.act('object');assert.equal(page.unknown.value,true);assert.equal(bodies.length,1);page.description.value='新文字不得污染';await page.retryUnknown();assert.deepEqual(bodies[0],bodies[1]);assert.equal(page.unknown.value,false);assert.equal(page.record.value.status,'processing')
})
test('successful owner write with failed read freezes writes and only retries GET while preserving acknowledgement',async()=>{
  let failed=false,posts=0
  const page=await owner({getComplaint:async()=>{if(failed)throw new Error('重读失败');return{data:awaiting()}},confirmComplaintFeedback:async(id,body)=>{posts++;failed=true;return{data:{operation_id:body.operation_id,event_id:10,complaint:{...awaiting(),status:'closed',allowed_actions:[]}}}}})
  await page.act('confirm');assert.equal(page.readPending.value,true);assert.match(page.error.value,/操作已记录/);assert.match(page.writeNotice.value,/已记录/);await page.act('confirm');await page.refresh();assert.equal(posts,1)
})
test('owner confirmation locks the exact complaint and body before dialog, and late response cannot contaminate a different route',async()=>{
  let release
  const page=await owner({confirmDialog:()=>new Promise(resolve=>{release=resolve})});page.description.value='工程模拟原草稿';const first=page.act('object');page.description.value='更改';await page.act('object');release();await first;assert.equal(page.calls.filter(call=>call[0]==='object').length,1);assert.equal(page.calls.find(call=>call[0]==='object')[2].description,'工程模拟原草稿')
  let finish
  const leaving=await owner({confirmComplaintFeedback:(id,body)=>new Promise(resolve=>{finish=()=>resolve({data:{operation_id:body.operation_id,event_id:10,complaint:{...awaiting(),status:'closed'}}})})});const attempt=leaving.act('confirm');await tick();leaving.changeId('99');finish();await attempt;assert.equal(leaving.writeNotice.value,'')
})
test('actual record SSR keeps current feedback, historical feedback, objection and system closure separate without fabricating a system actor',async()=>{
  const source=await readFile(new URL('../src/components/ComplaintRecord.vue',import.meta.url),'utf8'),{descriptor}=parse(source),compiled=compileTemplate({source:descriptor.template.content,filename:'ComplaintRecord.vue',id:'s16record',ssr:true,ssrCssVars:[]}),context=vm.createContext({}),module=new vm.SourceTextModule(compiled.code,{context})
  await module.link(specifier=>{const values=specifier==='vue'?vue:ssr;return new vm.SyntheticModule(Object.keys(values),function(){for(const[key,value]of Object.entries(values))this.setExport(key,value)},{context})});await module.evaluate()
  const record={...awaiting(),status:'closed',closure_type:'auto_closed',closed_at:'2026-11-06T09:00:01+08:00',events:[{id:1,type:'feedback',result:'第一次反馈',basis:'第一次公开依据',feedback_deadline:'2026-11-05T09:00+08:00',created_at:'2026-10-29T09:00+08:00'},{id:2,type:'objected',description:'第一次异议',created_at:'2026-10-30T08:00+08:00'},{id:3,type:'auto_closed',source:'system',actor_display_name:null,occurred_at:'2026-11-06T09:00+08:00',created_at:'2026-11-06T09:00:01+08:00'}]}
  const app=createSSRApp({props:['record'],setup:()=>({complaintTypes,complaintStatuses,verificationOutcomes,reservationStatuses}),ssrRender:module.namespace.ssrRender},{record});app.component('ProtectedAttachment',{render:()=>null});const html=await ssr.renderToString(app)
  assert.match(html,/工程模拟公开结果/);assert.match(html,/第一次反馈/);assert.match(html,/第一次异议/);assert.match(html,/确认期届满，系统自动结案/);assert.match(html,/系统自动记录/);assert.doesNotMatch(html,/本人确认反馈并结案|记录人：null/)
})
test('feedback notifications use Chinese types and only typed complaint IDs produce safe sibling RouterLinks',async()=>{
  const page=await script('../src/views/NotificationsView.vue',{vue:{ref,computed,onMounted(){}},'../composables/usePagination':{usePagination:items=>({page:ref(1),pages:computed(()=>1),pageItems:items})},'../components/ui/feedback':{message:{success(){}}},'../api/notification':{listNotifications:async()=>({data:[]}),markRead:async()=>{},markAllRead:async()=>({data:{marked:0}})},'../stores/notification':{useNotificationStore:()=>({refresh(){},decrement(){},clear(){}})}},'list,filterType,typeMeta,buckets,filtered,complaintTarget')
  page.list.value=[{id:1,type:'complaint_feedback',complaint_id:88},{id:2,type:'complaint_auto_closed',complaint_id:88}];assert.equal(page.typeMeta.complaint_feedback.text,'投诉反馈');assert.equal(page.typeMeta.complaint_auto_closed.text,'投诉自动结案');page.filterType.value='complaint_feedback';assert.equal(page.filtered.value.length,1)
  assert.equal(page.complaintTarget(page.list.value[0]),'/complaints/88');assert.equal(page.complaintTarget({type:'complaint_feedback',content:'投诉#222'}),null);assert.equal(page.complaintTarget({type:'complaint_feedback',complaint_id:'222'}),null)
  const source=await readFile(new URL('../src/views/NotificationsView.vue',import.meta.url),'utf8');for(const match of source.matchAll(/<button\b[\s\S]*?<\/button>/g))assert.doesNotMatch(match[0],/RouterLink/)
})

test('feedback and owner lifecycle adapters preserve version and UUID on exact authorized paths',async()=>{
  const calls=[],context=vm.createContext({FormData}),module=new vm.SourceTextModule(await readFile(new URL('../src/api/complaint.js',import.meta.url),'utf8'),{context})
  await module.link(()=>new vm.SyntheticModule(['default'],function(){this.setExport('default',{post:(...args)=>calls.push(args)})},{context}));await module.evaluate()
  const body={version:3,operation_id:'immutable-key'}
  module.namespace.feedbackComplaint(88,{...body,result:'公开反馈',basis:'公开依据'});module.namespace.withdrawComplaint(88,body);module.namespace.confirmComplaintFeedback(88,body);module.namespace.objectComplaintFeedback(88,{...body,description:'异议说明'})
  assert.deepEqual(calls.map(call=>call[0]),['/admin/complaints/88/feedback','/complaints/88/withdraw','/complaints/88/confirm','/complaints/88/object']);assert.equal(calls.every(call=>call[1].operation_id==='immutable-key'&&call[1].version===3),true);assert.equal(calls[3][1].description,'异议说明')
})
