<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { createComplaint, getComplaint, uploadComplaintAttachment } from '../api/complaint'
import { getRooms } from '../api/room'
import { getReservation, listMyReservations } from '../api/reservation'
import { complaintTypes, complaintStatuses, reservationStatuses, positiveId, complaintPayload, validateEvidenceFile, serviceKinds, faultTypes, impactScopes, safetyRisks, otherRequestTypes, repairPriority, repairPriorityText } from '../composables/complaintForm.js'
import EvidenceUploadQueue from './EvidenceUploadQueue.vue'
import {useEvidenceUploadQueue} from '../composables/useEvidenceUploadQueue.js'
import ComplaintRecord from './ComplaintRecord.vue'
import SInput from './ui/SInput.vue'
import SSelect from './ui/SSelect.vue'
import SButton from './ui/SButton.vue'
import SBanner from './ui/SBanner.vue'
import {createRecordPager,recordFields} from '../composables/recordPage.js'
import RecordFilters from './RecordFilters.vue'
import RecordPageControls from './RecordPageControls.vue'
const choicesPager=createRecordPager(listMyReservations,{blocked:()=>locked.value}),choicesFields=recordFields('reservation')
const props = defineProps({ initialType:{type:String,default:'service'}, initialReservationId:{type:[String,Number],default:null} })
const emit = defineEmits(['created'])
const type=ref('service'), description=ref(''), useReservation=ref(false), reservationId=ref(null), roomId=ref(null), context=ref(null)
const serviceKind=ref('facility'), repair=ref({faultType:'照明', impactScope:'inconvenient', safetyRisk:'none'}), otherRequestType=ref('环境卫生'), extensionNotice=ref('')
const rooms=ref([]), choices=choicesPager.items, roomsError=ref(''), choicesError=choicesPager.error, contextError=ref(''), contextLoading=ref(false), roomsLoading=ref(false), choicesLoading=choicesPager.loading
const submitting=ref(false), uncertain=ref(false), error=ref(''), receipt=ref(null), record=ref(null), reading=ref(false), readError=ref('')
const locked=computed(()=>submitting.value || uncertain.value || !!receipt.value)
defineExpose({targetReadBlocked:computed(()=>submitting.value||uncertain.value||reading.value||uploading.value)})
const typeOptions=Object.entries(complaintTypes).map(([value,label])=>({value,label}))
const serviceKindOptions=Object.entries(serviceKinds).map(([value,label])=>({value,label}))
const faultTypeOptions=faultTypes.map(value=>({value,label:value}))
const impactOptions=impactScopes
const safetyOptions=safetyRisks
const otherRequestOptions=otherRequestTypes.map(value=>({value,label:value}))
const priorityPreview=computed(()=>repairPriority(repair.value))
const priorityPreviewText=computed(()=>repairPriorityText(priorityPreview.value))
const roomOptions=computed(()=>[{value:null,label:'不关联空间'},...rooms.value.map(room=>({value:room.id,label:`${room.name} · ${room.building || '楼栋未登记'} · ${room.floor || '楼层未登记'}`}))])
const reservationOptions=computed(()=>{
  const values=choices.value.map(row=>row.id===context.value?.id?context.value:row)
  if(context.value && !values.some(row=>row.id===context.value.id)) values.unshift(context.value)
  return values.map(row=>({value:row.id,label:`#${row.id} ${row.room_name} ${row.seat_no} ${row.res_date} ${row.start_time}—${row.end_time} · ${reservationStatuses[row.status] || row.status}`}))
})
let alive=true, contextSequence=0, readSequence=0, scope=0, frozenBody=null, fingerprint=''
const queue=useEvidenceUploadQueue({uploadFile:uploadComplaintAttachment,isLocked:()=>locked.value,isActive:()=>alive,setError:text=>{error.value=text},validateFile:validateEvidenceFile,newKey:()=>crypto.randomUUID()})
const {items,uploading,uploadItem,selectFiles,removeItem}=queue
async function loadRooms() {roomsLoading.value=true;try{const response=await getRooms();if(alive){rooms.value=response.data;roomsError.value=''}}catch(failure){if(alive)roomsError.value=failure.message}finally{if(alive)roomsLoading.value=false}}
const loadChoices=()=>choicesPager.refresh()
async function selectReservation(value) {
  if(locked.value) return
  const ticket=++contextSequence
  reservationId.value=value;context.value=null;contextError.value='';contextLoading.value=false
  let id
  try{id=positiveId(value)}catch(failure){contextError.value=failure.message;return}
  if(!id)return
  reservationId.value=id
  contextLoading.value=true
  try{const response=await getReservation(id);if(alive&&ticket===contextSequence){if(response.data?.id!==id)throw new Error('预约上下文返回不完整');context.value=response.data}}
  catch(failure){if(alive&&ticket===contextSequence)contextError.value=failure.message}
  finally{if(alive&&ticket===contextSequence)contextLoading.value=false}
}
function changeType(value) {if(locked.value)return;type.value=value;if(value==='occupation'){useReservation.value=true;void loadChoices()}}
function toggleReservation() {if(locked.value)return;if(useReservation.value)void loadChoices();else{contextSequence++;reservationId.value=null;context.value=null;contextError.value='';contextLoading.value=false}}
async function refreshCreated() {
  if(!receipt.value)return
  const ticket=++readSequence,id=receipt.value.id
  reading.value=true;readError.value=''
  try{const response=await getComplaint(id);if(alive&&ticket===readSequence){if(response.data?.id!==id||!Array.isArray(response.data?.attachments)||!Array.isArray(response.data?.events))throw new Error('详情返回不完整');record.value=response.data}}
  catch(failure){if(alive&&ticket===readSequence)readError.value=`已经提交，详情读取失败：${failure.message}。请重试读取，不要重复提交。`}
  finally{if(alive&&ticket===readSequence)reading.value=false}
}
async function submit() {
  if(submitting.value||uploading.value||receipt.value)return
  error.value=''
  if(!uncertain.value){
    try{
      if(contextLoading.value||contextError.value)throw new Error('关联预约尚未核对成功，请先重试读取或清除关联')
      const body=complaintPayload({type:type.value,description:description.value,reservationId:useReservation.value?reservationId.value:null,roomId:roomId.value,items:items.value,context:context.value,serviceKind:serviceKind.value,repair:repair.value,otherRequestType:otherRequestType.value})
      const signature=JSON.stringify(body)
      if(!frozenBody||signature!==fingerprint){frozenBody={...body,operation_id:crypto.randomUUID()};fingerprint=signature}
    }catch(failure){error.value=failure.message;return}
  }
  const ticket=scope
  submitting.value=true
  try{
    const response=await createComplaint(JSON.parse(JSON.stringify(frozenBody)))
    if(!alive||ticket!==scope)return
    const value=response.data
    if(!Number.isSafeInteger(value?.id) || value.id<=0 || value.operation_id!==frozenBody.operation_id || !(value.status==='submitted' || (value.replayed===true && Object.hasOwn(complaintStatuses,value.status))) || !Array.isArray(value.attachments) || !Array.isArray(value.events) || value.attachment_count!==frozenBody.attachment_ids.length || value.attachments.length!==frozenBody.attachment_ids.length || value.attachments.some(item=>!frozenBody.attachment_ids.includes(item.id)))throw new Error('提交回执不完整，结果需以原操作ID核对')
    uncertain.value=false;receipt.value=value;record.value=null;extensionNotice.value=''
    if(type.value==='occupation'){
      const extension=value.extension_result
      if(extension?.applied){extensionNotice.value=`本次求助已受理；签到截止已延长至 ${extension.checkin_deadline || '—'}${extension.capped_by_reservation_end?'（不超过原预约结束）':''}。`}
      else if(extension){extensionNotice.value=`本次求助已受理；本次未延长签到截止：${({already_used:'该预约已使用过延期',base_deadline_passed:'已过原签到截止',reservation_ended:'预约已结束',reservation_not_pending:'预约不是待签到状态',not_occupation:'不是座位被占求助'}[extension.reason_code]||'不符合延期条件')}。`}
      else{extensionNotice.value='本次求助已提交；如符合规则，服务端会更新签到截止，请在我的预约核对。'}
    }
    emit('created',value.id)
    await refreshCreated()
  }catch(failure){if(alive&&ticket===scope){uncertain.value=failure.status==null||failure.status===408||failure.status>=500;error.value=uncertain.value?'提交结果尚未确认。原文字、材料ID和操作ID已锁定，请主动以原操作ID重试核对，不要重复创建。':failure.status===409?`请求冲突：${failure.message}。请核对本人列表和材料状态，未确认创建新记录。`:failure.message}}
  finally{if(alive&&ticket===scope)submitting.value=false}
}
function reset() {if(submitting.value||uncertain.value)return;scope++;readSequence++;receipt.value=null;record.value=null;readError.value='';description.value='';items.value=[];frozenBody=null;fingerprint='';error.value='';extensionNotice.value='';applyInitial()}
function applyInitial() {
  if(locked.value)return
  scope++;type.value=props.initialType||'service';useReservation.value=type.value==='occupation'||!!props.initialReservationId
  if(useReservation.value){void loadChoices();void selectReservation(props.initialReservationId)}
  else {contextSequence++;reservationId.value=null;context.value=null;contextError.value='';contextLoading.value=false}
}
watch(()=>[props.initialType,props.initialReservationId],applyInitial,{immediate:true})
onMounted(loadRooms)
onBeforeUnmount(()=>{queue.dispose();alive=false;choicesPager.dispose();scope++;contextSequence++;readSequence++})
</script>
<template>
  <section class="card complaint-form">
    <template v-if="!receipt"><h3>新建求助</h3><p class="muted">提交后为待受理，不代表已核实或解决。预约与座位问题请关联本人预约；符合规则的座位被占求助可将签到截止延长一次 15 分钟，但不恢复已释放预约。</p>
      <SBanner v-if="error" type="error" :title="error" /><p v-if="uncertain" class="operation-key">原操作ID：{{ frozenBody?.operation_id }}</p>
      <form class="fields" @submit.prevent="submit">
        <label>事项类型<SSelect :model-value="type" :options="typeOptions" :disabled="locked" @update:model-value="changeType" /></label>
        <template v-if="type==='service'">
          <label>具体类型<SSelect v-model="serviceKind" :options="serviceKindOptions" :disabled="locked" /></label>
          <template v-if="serviceKind==='facility'">
            <div class="field-grid">
              <label>故障类型<SSelect v-model="repair.faultType" :options="faultTypeOptions" :disabled="locked" /></label>
              <label>影响范围<SSelect v-model="repair.impactScope" :options="impactOptions" :disabled="locked" /></label>
            </div>
            <label>安全风险<SSelect v-model="repair.safetyRisk" :options="safetyOptions" :disabled="locked" /></label>
            <div class="priority-preview">
              <div class="priority-head"><span>系统初始优先级</span><b>{{ priorityPreview }}级</b></div>
              <p>{{ priorityPreviewText }}</p>
              <small>按故障类型、影响范围和安全风险生成；管理员受理时结合现场核实或调整并记录理由。任何安全风险直接按 5 级。</small>
            </div>
          </template>
          <label v-else>诉求类型<SSelect v-model="otherRequestType" :options="otherRequestOptions" :disabled="locked" /></label>
        </template>
        <label v-if="type==='service'"><span><input v-model="useReservation" type="checkbox" :disabled="locked" @change="toggleReservation" /> 关联本人预约（可选）</span></label>
        <div v-if="useReservation" class="fields"><label>关联本人预约（含历史）<SSelect :model-value="reservationId" :options="reservationOptions" :disabled="locked" placeholder="从本人预约选择" @update:model-value="selectReservation" /></label><RouterLink to="/mine">前往我的预约选择求助记录</RouterLink><RecordFilters v-model="choicesPager.filters.value" :fields="choicesFields" :disabled="locked||choicesLoading" @apply="choicesPager.apply"/><RecordPageControls :snapshot="choicesPager.snapshot.value" :query="choicesPager.snapshotQuery.value" :fields="choicesFields" :disabled="locked||choicesLoading" @page="choicesPager.turn"/><p v-if="choicesLoading" role="status">正在读取本人预约选项…</p><p v-if="choicesError" role="alert">预约选项读取失败：{{ choicesError }} <SButton size="sm" :disabled="locked" @click="loadChoices">重试选项</SButton></p><p v-if="contextLoading" role="status">正在核对本人预约…</p><p v-if="contextError" role="alert">{{ contextError }} <SButton size="sm" :disabled="locked" @click="selectReservation(reservationId)">重试预约</SButton></p><div v-if="context" class="context">{{ context.room_name }} · 座位{{ context.seat_no }} · {{ context.res_date }} {{ context.start_time }}—{{ context.end_time }}<br />当前状态：{{ reservationStatuses[context.status] || context.status }}；{{ context.building || '楼栋未登记' }} · {{ context.floor || '楼层未登记' }} · {{ context.location || '位置未登记' }}</div></div>
        <label v-else>关联空间（必须选择）<SSelect v-model="roomId" :options="roomOptions" :disabled="locked" /></label><p v-if="roomsLoading" role="status">正在读取空间选项…</p><p v-if="roomsError" role="alert">空间选项读取失败：{{ roomsError }} <SButton size="sm" :disabled="locked" @click="loadRooms">重试空间</SButton></p>
        <label>情况说明<SInput v-model="description" type="textarea" :rows="5" required maxlength="2000" :disabled="locked" placeholder="描述发生的情况；不必知道或猜测占用者的身份" /></label><p class="muted">{{ description.trim().length }}/2000字。仅文字也可提交，请勿填写不必要的个人信息。</p>
        <EvidenceUploadQueue :items="items" :disabled="locked" :uploading="uploading" @select="selectFiles" @retry="uploadItem" @remove="removeItem" />
        <SButton type="submit" variant="primary" :loading="submitting" :disabled="uploading || (!uncertain && items.some(item=>item.phase!=='uploaded'))">{{ uncertain ? '以原操作ID重试提交核对' : '提交，等待受理' }}</SButton>
      </form>
    </template>
    <template v-else><SBanner type="success" :title="`#${receipt.id} 已提交，当前${complaintStatuses[receipt.status] || receipt.status}${receipt.replayed?'（原请求幂等回执）':''}`" /><SBanner v-if="extensionNotice" type="info" :title="extensionNotice" /><p v-if="reading" role="status">正在读取已保存详情…</p><SBanner v-if="readError" type="error" :title="readError"><SButton :loading="reading" @click="refreshCreated">重试读取详情</SButton></SBanner><ComplaintRecord :record="record || receipt" /><div class="actions"><RouterLink :to="`/complaints/${receipt.id}`">独立查看详情</RouterLink><SButton :disabled="reading" @click="reset">另起一条新提交</SButton></div></template>
  </section>
</template>
<style scoped>.complaint-form,.fields{display:grid;gap:14px;min-width:0}.fields label{display:grid;gap:7px;font-size:var(--fs-caption)}.fields input[type=file]{max-width:100%;min-width:0}.context,.file-item{padding:12px;background:var(--surface-2);border-radius:var(--r-md);overflow-wrap:anywhere}.actions{display:flex;flex-wrap:wrap;gap:10px;align-items:center}.operation-key{overflow-wrap:anywhere}
.field-grid{display:grid;grid-template-columns:1fr 1fr;gap:12px}
.priority-preview{padding:14px 16px;border-radius:var(--r-md);background:var(--primary-faint);border:1px solid var(--primary-weak)}
.priority-head{display:flex;align-items:baseline;justify-content:space-between;gap:10px;color:var(--text-2);font-size:var(--fs-caption)}
.priority-head b{font-family:var(--font-display);font-size:26px;color:var(--primary);line-height:1}
.priority-preview p{margin-top:6px;color:var(--text-1);font-size:var(--fs-body-sm)}
.priority-preview small{display:block;margin-top:6px;color:var(--text-3);font-size:11px;line-height:1.6}
@media(max-width:620px){.field-grid{grid-template-columns:1fr}}</style>
