import {recordTestImport} from './record-fixtures.js'
import * as reviewManagement from '../src/composables/reviewManagement.js'
import test from 'node:test'
import assert from 'node:assert/strict'
import vm from 'node:vm'
import { readFile } from 'node:fs/promises'
import { ref, computed, createSSRApp } from 'vue'
import * as vue from 'vue'
import * as ssr from 'vue/server-renderer'
import { compileTemplate } from '@vue/compiler-sfc'
const tick=()=>new Promise(resolve=>setImmediate(resolve))
async function component(file,mocks,exports,extra={}) {
  const source=(await readFile(new URL(file,import.meta.url),'utf8')).split('<script setup>')[1].split('</script>')[0]
  const context=vm.createContext({defineExpose(){},...extra}),module=new vm.SourceTextModule(source+`\nexport {${exports}}`,{context})
  await module.link(specifier=>{const values=recordTestImport(specifier,mocks[specifier])||(specifier.endsWith('/reviewManagement.js')?reviewManagement:null)||(specifier.endsWith('.vue')?{default:{}}:null);assert.ok(values,specifier);return new vm.SyntheticModule(Object.keys(values),function(){for(const[key,value]of Object.entries(values))this.setExport(key,value)},{context})})
  await module.evaluate();return module.namespace
}
const proposal=()=>({change_type:'room.update',target_id:1,payload:{version:2,reason:'工程模拟：检修停用',enabled:false}})
function preview(value,inUse=[]) {return{data:{normalized_proposal:value,versions:{room_id:1,room_version:2},impact_hash:'test-hash',pending:[],in_use:inUse,summary:{immediate_termination:true},server_now:'2026-10-30T12:00:00+08:00',campus_zone:'Asia/Shanghai'}}}
async function panel(overrides={}) {
  const calls=[],cleanup=[];let uuid=0
  const page=await component('../src/components/admin/ResourceChangeConfirm.vue',{
    vue:{ref,computed,onBeforeUnmount:handler=>cleanup.push(handler)},
    '../../api/resourceChange':{previewResourceChange:async value=>{calls.push(['preview',value]);return preview(value)},confirmResourceChange:async body=>{calls.push(['confirm',body]);return{data:{operation_id:body.operation_id,cancelled_count:0,replayed:false}}},...overrides}
  },'run,loadPreview,cancel,confirm,visible,preview,error,operationId,coordinationConfirmed,coordinationNote,uncertain,stale,submitting,locked,changeSummary',{crypto:{randomUUID:()=>`00000000-0000-4000-8000-${String(++uuid).padStart(12,'0')}`}})
  return{...page,calls,cleanup}
}
test('preview cancellation performs zero confirms and preserves original proposal input',async()=>{
  const page=await panel(),value=proposal();const outcome=page.run(value);await tick()
  assert.equal(page.calls.length,1);assert.equal(page.calls[0][0],'preview');page.cancel();assert.equal(await outcome,false)
  assert.equal(value.payload.reason,'工程模拟：检修停用');assert.equal(page.calls.filter(call=>call[0]==='confirm').length,0)
})
test('late preview cannot overwrite a newer context and confirm freezes normalized fields',async()=>{
  const pending=[],page=await panel({previewResourceChange:value=>new Promise(resolve=>pending.push({value,resolve}))})
  const first=page.run(proposal());page.cancel();assert.equal(await first,false)
  const next=proposal();next.payload.reason='工程模拟：新理由';const second=page.run(next)
  pending[1].resolve(preview(pending[1].value));await tick();pending[0].resolve(preview(pending[0].value));await tick()
  next.payload.reason='调用方后来改动';assert.equal(page.preview.value.normalized_proposal.payload.reason,'工程模拟：新理由')
  await page.confirm();assert.ok(await second);assert.equal(page.calls[0][1].proposal.payload.reason,'工程模拟：新理由')
})
test('in-use coordination requires actual self-report checkbox and note, never prefilled confirmation',async()=>{
  const page=await panel({previewResourceChange:async value=>preview(value,[{reservation_id:9,status:'checked_in'}])})
  const result=page.run(proposal());await tick();await page.confirm();assert.match(page.error.value,/已现场协调/);assert.equal(page.calls.length,0)
  page.coordinationConfirmed.value=true;await page.confirm();assert.equal(page.calls.length,0)
  page.coordinationNote.value='工程模拟：测试协调guard，不代表真实现场确认';await page.confirm();await result
  assert.equal(page.calls[0][1].coordination.confirmed,true);assert.match(page.calls[0][1].coordination.note,/工程模拟/);assert.equal('source' in page.calls[0][1].coordination,false)
})
test('unknown response locks body and UUID for deliberate retry without preview or changed coordination',async()=>{
  const bodies=[];let attempts=0
  const page=await panel({confirmResourceChange:async body=>{bodies.push(body);if(++attempts===1)throw{status:null};return{data:{operation_id:body.operation_id,cancelled_count:0,replayed:true}}}})
  const outcome=page.run(proposal());await tick();await page.confirm();assert.equal(page.uncertain.value,true);assert.equal(page.locked.value,true)
  const id=page.operationId.value;page.cancel();assert.equal(page.visible.value,true);await page.loadPreview();assert.equal(page.calls.length,1)
  page.coordinationNote.value='新值不能进入旧请求';await assert.rejects(page.run(proposal()),/结果尚未确认/)
  await page.confirm();const result=await outcome;assert.equal(result.replayed,true);assert.deepEqual(bodies[0],bodies[1]);assert.equal(bodies[1].operation_id,id);assert.equal(page.locked.value,false)
})
test('known conflict blocks blind reuse and re-preview creates a fresh UUID before another confirmation',async()=>{
  let first=true
  const page=await panel({confirmResourceChange:async body=>{if(first){first=false;throw{status:409,message:'影响清单变化'}}return{data:{operation_id:body.operation_id,cancelled_count:0}}}})
  const outcome=page.run(proposal());await tick();const oldId=page.operationId.value
  await page.confirm();assert.equal(page.stale.value,true);assert.match(page.error.value,/重新预览/);await page.confirm();assert.equal(page.stale.value,true)
  await page.loadPreview();assert.notEqual(page.operationId.value,oldId);await page.confirm();assert.ok(await outcome)
})
test('duplicate confirm is blocked while request is pending and unmount prevents late result from updating UI',async()=>{
  let release,calls=0
  const page=await panel({confirmResourceChange:body=>{calls++;return new Promise(resolve=>{release=()=>resolve({data:{operation_id:body.operation_id,cancelled_count:0}})})}})
  const outcome=page.run(proposal());await tick();const first=page.confirm();await page.confirm();assert.equal(calls,1)
  page.cleanup[0]();assert.equal(await outcome,false);release();await first
})
test('malformed successful receipt stays unknown and does not fabricate a committed result',async()=>{
  const page=await panel({confirmResourceChange:async()=>({data:{}})})
  const outcome=page.run(proposal());await tick();await page.confirm();assert.equal(page.uncertain.value,true);assert.match(page.error.value,/尚未确认/);page.cleanup[0]();await outcome
})
test('dialog Escape lock is opt-in and keeps existing default closing behavior',async()=>{
  const events=[],props={modelValue:true,closeOnEscape:false}
  const dialog=await component('../src/components/ui/SDialog.vue',{vue:{ref},'../../composables/useDialogFocus':{useDialogFocus:()=>({trap(){}})}},'onKeydown',{defineProps:()=>props,defineEmits:()=> (...args)=>events.push(args)})
  const escape={key:'Escape',stopPropagation(){}};dialog.onKeydown(escape);assert.equal(events.length,0)
  props.closeOnEscape=true;dialog.onKeydown(escape);assert.equal(events[0][0],'update:modelValue')
})
test('resource change adapters use fixed endpoint bodies with bounded confirmation timeout',async()=>{
  const calls=[],context=vm.createContext({}),source=await readFile(new URL('../src/api/resourceChange.js',import.meta.url),'utf8'),module=new vm.SourceTextModule(source,{context})
  await module.link(()=>new vm.SyntheticModule(['default'],function(){this.setExport('default',{post:(...args)=>calls.push(args)})},{context}));await module.evaluate()
  module.namespace.previewResourceChange(proposal());module.namespace.confirmResourceChange({operation_id:'same-id'})
  assert.equal(calls[0][0],'/admin/resource-changes/preview');assert.equal(calls[1][0],'/admin/resource-changes/confirm');assert.equal(calls[1][2].timeout,120000);assert.equal(calls[1][2].silent,true)
})

async function renderSummary(page) {
  const source=await readFile(new URL('../src/components/admin/ResourceChangeConfirm.vue',import.meta.url),'utf8')
  const fragment=source.match(/<ul class="change-summary">[\s\S]*?<\/ul>/)[0]
  const compiled=compileTemplate({source:fragment,filename:'ResourceChangeConfirm.vue',id:'change-summary',ssr:true,ssrCssVars:[]})
  assert.equal(compiled.errors.length,0)
  const context=vm.createContext({}),module=new vm.SourceTextModule(compiled.code,{context})
  await module.link(specifier=>{const values=specifier==='vue'?vue:ssr;return new vm.SyntheticModule(Object.keys(values),function(){for(const[key,value]of Object.entries(values))this.setExport(key,value)},{context})});await module.evaluate()
  return ssr.renderToString(createSSRApp({setup:()=>({changeSummary:page.changeSummary}),ssrRender:module.namespace.ssrRender}))
}
test('actual typed summary renders room changes, full-day closure and original block cancellation target without requiring raw JSON',async()=>{
  const page=await panel()
  page.preview.value=preview({change_type:'room.update',target_id:1,payload:{name:'工程模拟空间',building:'模拟楼',floor:'B1',location:'东侧',enabled:false,seat_rows:2,seat_cols:3,description:''}}).data
  const roomHtml=await renderSummary(page);assert.match(roomHtml,/模拟楼 · B1 · 东侧/);assert.match(roomHtml,/空间状态：停用；布局2行×3列/)
  page.preview.value=preview({change_type:'opening.save',target_id:1,payload:{date:'2026-11-01',windows:[]}}).data
  assert.match(await renderSummary(page),/整日闭馆（空窗口）/)
  page.preview.value={...preview({change_type:'block.cancel',target_id:9,payload:{reason:'恢复'}}).data,target_label:'工程模拟空间 / A1 / 2026-11-01T08:00—12:00'}
  const cancelHtml=await renderSummary(page);assert.match(cancelHtml,/A1 \/ 2026-11-01T08:00—12:00/);assert.match(cancelHtml,/其他重叠停用记录/)
})

test('management cancellation and user cancellation retain distinct exact server categories and full-owner counts',async()=>{
 const page=await component('../src/views/NotificationsView.vue',{
  vue:{ref,computed,onMounted(){}},'../components/ui/feedback':{message:{success(){}}},'../api/notification':{listNotifications:async()=>({data:[]}),markRead:async()=>{},markAllRead:async()=>({data:{marked:0}})},'../stores/notification':{useNotificationStore:()=>({refresh(){},beginUpdate(){},acceptUnread(){}})}
 },'list,filters,typeMeta,buckets,snapshot,typeNav')
 page.list.value=[{id:1,type:'management_cancelled',is_read:false}]
 page.snapshot.value={counts:{total:30,unread:8,by_type:{reservation_cancelled:4,management_cancelled:9,reservation_success:17}}}
 assert.equal(page.typeMeta.management_cancelled.text,'资源管理取消');assert.equal(page.buckets.value.reservation_cancelled,4);assert.equal(page.buckets.value.management_cancelled,9);assert.equal(page.buckets.value.all,30)
 page.filters.value={type:'reservation_cancelled'};assert.equal(page.list.value.length,1);assert.equal(page.list.value[0].type,'management_cancelled');assert.equal(page.typeNav.value.some(item=>item.key==='management_cancelled'),true);assert.equal(page.typeMeta.reservation_checkin.text,'预约签到成功')
})
