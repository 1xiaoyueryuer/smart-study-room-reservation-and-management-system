import {recordTestImport} from './record-fixtures.js'
import test from 'node:test'
import assert from 'node:assert/strict'
import vm from 'node:vm'
import { readFile } from 'node:fs/promises'
import { ref, computed } from 'vue'
import { complaintPayload, validateEvidenceFile, positiveId, complaintTypes, complaintStatuses, reservationStatuses, ownerComplaintActions } from '../src/composables/complaintForm.js'
import {useEvidenceUploadQueue} from '../src/composables/useEvidenceUploadQueue.js'
const tick=()=>new Promise(resolve=>setImmediate(resolve))
async function script(file,mocks,exports,extra={}) {
  const context=vm.createContext({defineExpose(){},...extra}),source=(await readFile(new URL(file,import.meta.url),'utf8')).split('<script setup>')[1].split('</script>')[0],module=new vm.SourceTextModule(source+`\nexport {${exports}}`,{context})
  await module.link(specifier=>{const values=recordTestImport(specifier,mocks[specifier])||(specifier.endsWith('useServiceTargetClock.js')?{useServiceTargetClock:()=>({progress:()=>null})}:specifier.endsWith('.vue')?{default:{}}:null);assert.ok(values,specifier);return new vm.SyntheticModule(Object.keys(values),function(){for(const[key,value]of Object.entries(values))this.setExport(key,value)},{context})});await module.evaluate();return module.namespace
}
const meta={id:'00000000-0000-4000-8000-000000000001',display_name:'material-test.png',content_type:'image/png',size_bytes:100,width:10,height:10,sha256:'testhash',created_at:'2026-10-30T12:00+08:00',expires_at:'2026-10-31T12:00+08:00'}
const detail=body=>({id:77,operation_id:body.operation_id,type:body.type,status:'submitted',description:body.description,reservation_id:body.reservation_id||null,room_id:body.room_id||null,context_snapshot:null,submitted_at:'2026-10-30T12:00+08:00',attachment_count:body.attachment_ids.length,attachments:body.attachment_ids.map(id=>({...meta,id,expires_at:null})),events:[{id:1,type:'submitted',created_at:'2026-10-30T12:00+08:00',source:'user_submit'}]})
async function form(overrides={}) {
  const calls=[],cleanup=[];let saved,uuid=0
  const api={createComplaint:async body=>{calls.push(['create',body]);saved=detail(body);return{data:saved}},getComplaint:async id=>{calls.push(['detail',id]);return{data:saved}},uploadComplaintAttachment:async file=>{calls.push(['upload',file]);return{data:meta}},...overrides}
  const page=await script('../src/components/ComplaintForm.vue',{
    vue:{ref,computed,onMounted(){},onBeforeUnmount:handler=>cleanup.push(handler),watch:(source,callback)=>callback()},
    '../composables/useEvidenceUploadQueue.js':{useEvidenceUploadQueue},'../api/complaint':api,'../api/room':{getRooms:async()=>({data:[]})},'../api/reservation':{getReservation:overrides.getReservation|| (async id=>({data:{id,room_name:'模拟空间',seat_no:'A1',res_date:'2026-10-29',start_time:'08:00',end_time:'09:00',status:'completed'}})),listMyReservations:async()=>({data:[]})},
    '../composables/complaintForm.js':{complaintPayload,positiveId,complaintTypes,complaintStatuses,reservationStatuses,validateEvidenceFile:overrides.validateEvidenceFile|| (async()=>{})}
  },'type,description,useReservation,reservationId,roomId,context,contextError,choices,reservationOptions,items,uploading,submitting,uncertain,error,receipt,record,readError,locked,submit,selectFiles,uploadItem,removeItem,selectReservation,reset,refreshCreated',{defineProps:()=>({initialType:'service',initialReservationId:null}),defineEmits:()=>()=>{},crypto:{randomUUID:()=>`00000000-0000-4000-8000-${String(++uuid).padStart(12,'0')}`}})
  page.description.value='工程模拟：一般服务情况';return{...page,calls,cleanup}
}
test('complaint payload permits text-only service or own historical reservation, never guesses occupier or sends a mismatched room',()=>{
  const base={type:'service',description:'  工程模拟说明  ',reservationId:null,roomId:null,items:[],context:null}
  assert.equal(complaintPayload(base).description,'工程模拟说明');assert.equal(complaintPayload(base).attachment_ids.length,0)
  assert.throws(()=>complaintPayload({...base,type:'occupation'}),/必须关联本人预约/)
  const historical=complaintPayload({...base,type:'occupation',reservationId:5,roomId:99,context:{id:5,status:'cancelled'}})
  assert.equal(historical.reservation_id,5);assert.equal('room_id' in historical,false);assert.equal('occupier_id' in historical,false)
  assert.throws(()=>complaintPayload({...base,description:' '.repeat(10)}),/1至2000/);assert.throws(()=>complaintPayload({...base,description:'字'.repeat(2001)}),/1至2000/)
})
test('evidence validation enforces byte and pixel bounds and revokes local inspection URL on success and failure',async()=>{
  const revoked=[],urls={createObjectURL:()=> 'blob:local',revokeObjectURL:url=>revoked.push(url)}
  class Image {naturalWidth=5000;naturalHeight=4000;set src(value){queueMicrotask(()=>this.onload())}}
  await validateEvidenceFile({type:'image/png',size:100},{urls,ImageClass:Image});assert.equal(revoked.length,1)
  class TooLarge extends Image {naturalWidth=10001}
  await assert.rejects(validateEvidenceFile({type:'image/jpeg',size:100},{urls,ImageClass:TooLarge}),/10000/);assert.equal(revoked.length,2)
  await assert.rejects(validateEvidenceFile({type:'image/webp',size:100},{urls,ImageClass:Image}),/PNG或JPEG/)
  await assert.rejects(validateEvidenceFile({type:'image/png',size:5*1024*1024+1},{urls,ImageClass:Image}),/5MiB/)
})
test('text-only submission writes once, then reads persisted detail and stays submitted rather than accepted or solved',async()=>{
  const page=await form();await page.submit();assert.deepEqual(page.calls.map(call=>call[0]),['create','detail']);assert.equal(page.receipt.value.status,'submitted');assert.equal(page.record.value.status,'submitted');assert.equal(page.receipt.value.attachment_count,0)
  await page.submit();assert.equal(page.calls.filter(call=>call[0]==='create').length,1)
})
test('occupation uses real historical detail and rejects unavailable ownership context without changing reservation state',async()=>{
  const page=await form();page.type.value='occupation';page.useReservation.value=true;page.choices.value=[{id:5,room_name:'模拟空间',seat_no:'A1',res_date:'2026-10-29',start_time:'08:00',end_time:'09:00',status:'pending'}];await page.selectReservation('5');assert.match(page.reservationOptions.value[0].label,/已结束/);assert.equal(page.context.value.status,'completed');await page.submit();assert.equal(page.calls[0][1].reservation_id,5)
  const rejected=await form({getReservation:async()=>{throw{status:403,message:'不可读取他人预约'}}});rejected.type.value='occupation';rejected.useReservation.value=true;await rejected.selectReservation(9);await rejected.submit();assert.match(rejected.contextError.value,/他人预约/);assert.equal(rejected.calls.length,0)
})
test('failed selected photo remains with text and blocks create until explicit retry succeeds or file is removed',async()=>{
  let fail=true
  const page=await form({uploadComplaintAttachment:async()=>{if(fail)throw{status:400,message:'照片格式错误'};return{data:meta}}})
  const file={name:'proof.png',type:'image/png',size:100};await page.selectFiles({target:{files:[file],value:'selected'}})
  assert.equal(page.items.value.length,1);assert.equal(page.items.value[0].phase,'error');assert.equal(page.description.value,'工程模拟：一般服务情况');await page.submit();assert.equal(page.calls.length,0);assert.match(page.error.value,/全部已选照片/)
  fail=false;await page.uploadItem(page.items.value[0]);assert.equal(page.items.value[0].phase,'uploaded');await page.submit();assert.equal(page.calls[0][1].attachment_ids[0],meta.id)
})
test('unknown upload cannot supply an attachment ID and explicit local removal enables text-only submission',async()=>{
  const page=await form({uploadComplaintAttachment:async()=>{throw{status:null,message:'timeout'}}})
  await page.selectFiles({target:{files:[{name:'proof.jpg',type:'image/jpeg',size:100}],value:''}})
  assert.match(page.items.value[0].error,/上传结果未确认/);assert.equal(page.items.value[0].metadata,null);page.removeItem(page.items.value[0]);await page.submit();assert.equal(page.calls[0][1].attachment_ids.length,0)
})
test('unknown create freezes body and UUID and does not automatically retry despite live draft mutation',async()=>{
  const bodies=[];let saved
  const page=await form({createComplaint:async body=>{bodies.push(body);if(bodies.length===1)throw{status:null};saved=detail(body);return{data:{...saved,replayed:true}}},getComplaint:async()=>({data:saved})})
  await page.submit();assert.equal(page.uncertain.value,true);assert.equal(page.locked.value,true);assert.equal(bodies.length,1)
  page.description.value='后来改的内容不可污染原请求';await page.submit();assert.deepEqual(bodies[0],bodies[1]);assert.equal(page.receipt.value.replayed,true)
})
test('409 keeps editable original text, while acknowledged success and failed detail read never causes a duplicate POST',async()=>{
  const conflict=await form({createComplaint:async()=>{throw{status:409,message:'附件已绑定'}}});await conflict.submit();assert.match(conflict.error.value,/请求冲突/);assert.equal(conflict.uncertain.value,false);assert.equal(conflict.description.value,'工程模拟：一般服务情况')
  const failed=await form({getComplaint:async()=>{throw new Error('详情读取失败')}});await failed.submit();assert.equal(failed.receipt.value.status,'submitted');assert.match(failed.readError.value,/已经提交/);await failed.submit();assert.equal(failed.calls.filter(call=>call[0]==='create').length,1)
})
test('duplicate submits and late results after leaving do not update a new form',async()=>{
  let release,calls=0
  const page=await form({createComplaint:body=>{calls++;return new Promise(resolve=>{release=()=>resolve({data:detail(body)})})}})
  const first=page.submit();await page.submit();assert.equal(calls,1);page.cleanup[0]();release();await first;assert.equal(page.receipt.value,null)
})
test('authenticated attachment view revokes replaced blobs and ignores late responses after unmount',async()=>{
  const pending=[],revoked=[],created=[],cleanup=[]
  const page=await script('../src/components/ProtectedAttachment.vue',{vue:{ref,onBeforeUnmount:handler=>cleanup.push(handler),watch(){}},'../api/complaint':{getComplaintAttachment:()=>new Promise(resolve=>pending.push(resolve))}},'load,url,error',{defineProps:()=>({attachment:meta}),URL:{createObjectURL:()=>{const id=`blob:${created.length}`;created.push(id);return id},revokeObjectURL:id=>revoked.push(id)}})
  const first=page.load();pending[0](new Blob(['image']));await first;assert.equal(page.url.value,'blob:0')
  const second=page.load();assert.deepEqual(revoked,['blob:0']);cleanup[0]();pending[1](new Blob(['late']));await second;assert.equal(created.length,1)
})
test('complaint APIs use multipart single file and Bearer blob path, never static image paths or identity query',async()=>{
  const calls=[],context=vm.createContext({FormData}),source=await readFile(new URL('../src/api/complaint.js',import.meta.url),'utf8'),module=new vm.SourceTextModule(source,{context})
  await module.link(()=>new vm.SyntheticModule(['default'],function(){this.setExport('default',{post:(...args)=>calls.push(['post',...args]),get:(...args)=>calls.push(['get',...args])})},{context}));await module.evaluate()
  const file=new File(['image'],'proof.png',{type:'image/png'});module.namespace.uploadComplaintAttachment(file);assert.equal(calls[0][2].get('file').name,'proof.png');assert.equal([...calls[0][2].keys()].length,1)
  module.namespace.getComplaintAttachment(meta.id);assert.equal(calls[1][2].responseType,'blob');assert.match(calls[1][1],/\/content$/)
  module.namespace.createComplaint({operation_id:'same-id'});assert.equal(calls[2][3].timeout,120000)
})

test('over-limit selection retains file items and cannot silently become a photo-free successful complaint',async()=>{
  const page=await form();await page.selectFiles({target:{files:Array.from({length:4},(_,i)=>({name:`proof-${i}.png`,type:'image/png',size:100})),value:''}})
  assert.equal(page.items.value.length,4);assert.equal(page.items.value.every(item=>item.phase==='error'),true);await page.submit();assert.equal(page.calls.length,0)
  page.removeItem(page.items.value[3]);await page.uploadItem(page.items.value[0]);assert.equal(page.items.value[0].phase,'uploaded')
})
test('successful receipt omitting a chosen photo stays unknown instead of claiming all attachments saved',async()=>{
  const page=await form({createComplaint:async body=>({data:{...detail(body),attachments:[]}})})
  page.items.value=[{phase:'uploaded',metadata:meta}];await page.submit();assert.equal(page.receipt.value,null);assert.equal(page.uncertain.value,true)
})
test('own list distinguishes not-yet-read, failed and verified empty responses',async()=>{
  let fail=true
  const page=await script('../src/views/ComplaintsView.vue',{vue:{ref,computed,onMounted(){},onBeforeUnmount(){}},'vue-router':{useRoute:()=>({query:{}})},'../api/complaint':{listMyComplaints:async()=>{if(fail)throw new Error('本人列表失败');return{data:[]}}},'../composables/complaintForm.js':{complaintTypes,complaintStatuses}},'load,items,loaded,loading,error')
  assert.equal(page.loaded.value,false);await page.load();assert.equal(page.loaded.value,false);assert.match(page.error.value,/列表失败/)
  fail=false;await page.load();assert.equal(page.loaded.value,true);assert.equal(page.items.value.length,0);assert.equal(page.error.value,'')
})
test('independent detail query ignores an older response and never reads detail from a partial list',async()=>{
  const pending=[],props={id:'1'}
  const page=await script('../src/views/ComplaintDetailView.vue',{vue:{ref,computed,watch(){},onBeforeUnmount(){}},'../api/complaint':{getComplaint:id=>new Promise(resolve=>pending.push({id,resolve})),withdrawComplaint:async()=>{},confirmComplaintFeedback:async()=>{},objectComplaintFeedback:async()=>{}},'../composables/complaintForm.js':{ownerComplaintActions},'../components/ui/feedback':{confirmDialog:async()=>{}}},'load,record,error',{defineProps:()=>props})
  const old=page.load();props.id='2';const latest=page.load()
  pending[1].resolve({data:{id:2,attachments:[],events:[]}});await latest;pending[0].resolve({data:{id:1,attachments:[],events:[]}});await old
  assert.equal(page.record.value.id,2)
})

test('creation replay of an already processing complaint confirms original operation instead of freezing as a malformed receipt',async()=>{
  let saved
  const page=await form({createComplaint:async body=>{saved={...detail(body),replayed:true,status:'processing'};return{data:saved}},getComplaint:async()=>({data:saved})})
  await page.submit();assert.equal(page.uncertain.value,false);assert.equal(page.receipt.value.status,'processing');assert.equal(page.record.value.status,'processing')
})

test('creation replay accepts every implemented current state with matching operation and materials, but a new request cannot start past submitted',async()=>{
  for(const status of ['submitted','processing','awaiting_confirmation','closed','withdrawn']){
    let saved
    const page=await form({createComplaint:async body=>{saved={...detail(body),replayed:true,status};return{data:saved}},getComplaint:async()=>({data:saved})})
    await page.submit();assert.equal(page.uncertain.value,false);assert.equal(page.receipt.value.status,status);assert.equal(page.record.value.status,status)
  }
  const invalid=await form({createComplaint:async body=>({data:{...detail(body),replayed:false,status:'closed'}})});await invalid.submit();assert.equal(invalid.receipt.value,null);assert.equal(invalid.uncertain.value,true)
})
