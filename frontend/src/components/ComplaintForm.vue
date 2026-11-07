<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { createComplaint, getComplaint, getComplaintPriorityOptions, uploadComplaintAttachment } from '../api/complaint'
import { getRooms } from '../api/room'
import { getReservation, listMyReservations } from '../api/reservation'
import { complaintTypes, serviceKinds, serviceTopics, complaintStatuses, reservationStatuses, positiveId, complaintPayload, validateEvidenceFile } from '../composables/complaintForm.js'
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
const branch=ref(''), description=ref(''), serviceTopic=ref(''), reservationId=ref(null), roomId=ref(null), context=ref(null),faultType=ref(''),impactScope=ref(''),safetyRisk=ref(false),unsafeArea=ref(false)
const rooms=ref([]),priorityRules=ref([]), priorityOptionsError=ref(''), choices=choicesPager.items, roomsError=ref(''), choicesError=choicesPager.error, contextError=ref(''), contextLoading=ref(false), roomsLoading=ref(false), choicesLoading=choicesPager.loading
const submitting=ref(false), uncertain=ref(false), error=ref(''), receipt=ref(null), record=ref(null), reading=ref(false), readError=ref('')
const locked=computed(()=>submitting.value || uncertain.value || !!receipt.value)
defineExpose({targetReadBlocked:computed(()=>submitting.value||uncertain.value||reading.value||uploading.value)})
const branchOptions=[{value:'occupation',label:complaintTypes.occupation},{value:'facility_repair',label:serviceKinds.facility_repair},{value:'other_service',label:serviceKinds.other_service}]
const topicOptions=Object.entries(serviceTopics).map(([value,label])=>({value,label}))
const roomOptions=computed(()=>[{value:null,label:'选择所属空间'},...rooms.value.map(room=>({value:room.id,label:`${room.name} · ${room.building || '楼栋未登记'} · ${room.floor || '楼层未登记'}`}))])
const faultSuggestions=computed(()=>[...new Set(priorityRules.value.filter(rule=>rule.enabled).map(rule=>rule.fault_type))])
const scopeSuggestions=computed(()=>[...new Set(priorityRules.value.filter(rule=>rule.enabled).map(rule=>rule.impact_scope))])
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
function changeBranch(value) {if(locked.value)return;branch.value=value;serviceTopic.value='';faultType.value='';impactScope.value='';safetyRisk.value=false;unsafeArea.value=false;roomId.value=null;contextSequence++;context.value=null;contextError.value='';contextLoading.value=false;if(value==='occupation')void loadChoices()}
async function loadPriorityOptions(){try{const response=await getComplaintPriorityOptions();if(alive&&Array.isArray(response.data?.rules)){priorityRules.value=response.data.rules;priorityOptionsError.value=''}}catch(failure){if(alive)priorityOptionsError.value=failure.message}}
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
      const body=complaintPayload({branch:branch.value,serviceTopic:serviceTopic.value,description:description.value,reservationId:branch.value==='occupation'?reservationId.value:null,roomId:roomId.value,items:items.value,context:context.value,faultType:faultType.value,impactScope:impactScope.value,safetyRisk:safetyRisk.value,unsafeArea:unsafeArea.value})
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
    uncertain.value=false;receipt.value=value;record.value=null;emit('created',value.id)
    await refreshCreated()
  }catch(failure){if(alive&&ticket===scope){uncertain.value=failure.status==null||failure.status===408||failure.status>=500;error.value=uncertain.value?'提交结果尚未确认。原文字、材料ID和操作ID已锁定，请主动以原操作ID重试核对，不要重复创建。':failure.status===409?`请求冲突：${failure.message}。请核对本人列表和材料状态，未确认创建新记录。`:failure.message}}
  finally{if(alive&&ticket===scope)submitting.value=false}
}
function reset() {if(submitting.value||uncertain.value)return;scope++;readSequence++;receipt.value=null;record.value=null;readError.value='';description.value='';items.value=[];frozenBody=null;fingerprint='';error.value='';applyInitial()}
function applyInitial() {
  if(locked.value)return
  scope++;branch.value=props.initialType==='occupation'?'occupation':props.initialType==='facility_repair'?'facility_repair':props.initialType==='other_service'?'other_service':'';serviceTopic.value='';faultType.value='';impactScope.value='';safetyRisk.value=false;unsafeArea.value=false;roomId.value=null
  if(branch.value==='occupation'){void loadChoices();if(props.initialReservationId)void selectReservation(props.initialReservationId)}
  else {contextSequence++;reservationId.value=null;context.value=null;contextError.value='';contextLoading.value=false}
}
watch(()=>[props.initialType,props.initialReservationId],applyInitial,{immediate:true})
onMounted(()=>{void loadRooms();void loadPriorityOptions()})
onBeforeUnmount(()=>{queue.dispose();alive=false;choicesPager.dispose();scope++;contextSequence++;readSequence++})
</script>
<template>
  <section class="card complaint-form">
    <template v-if="!receipt"><h3>提交求助或投诉</h3><p class="muted">提交后为待受理，不代表已核实、已解决或现场恢复。座位被占无需猜测对方身份；历史预约可作关联，但提交不延长签到期限、不恢复预约。</p>
      <SBanner v-if="error" type="error" :title="error" /><p v-if="uncertain" class="operation-key">原操作ID：{{ frozenBody?.operation_id }}</p>
      <form class="fields" @submit.prevent="submit">
        <label>问题分支<SSelect :model-value="branch" :options="branchOptions" :disabled="locked" placeholder="请选择问题分支" @update:model-value="changeBranch" /></label>
        <div v-if="branch==='occupation'" class="fields"><label>关联本人预约（含历史）<SSelect :model-value="reservationId" :options="reservationOptions" :disabled="locked" placeholder="从本人预约选择" @update:model-value="selectReservation" /></label><RouterLink to="/mine">前往我的预约选择求助记录</RouterLink><RecordFilters v-model="choicesPager.filters.value" :fields="choicesFields" :disabled="locked||choicesLoading" @apply="choicesPager.apply"/><RecordPageControls :snapshot="choicesPager.snapshot.value" :query="choicesPager.snapshotQuery.value" :fields="choicesFields" :disabled="locked||choicesLoading" @page="choicesPager.turn"/><p v-if="choicesLoading" role="status">正在读取本人预约选项…</p><p v-if="choicesError" role="alert">预约选项读取失败：{{ choicesError }} <SButton size="sm" :disabled="locked" @click="loadChoices">重试选项</SButton></p><p v-if="contextLoading" role="status">正在核对本人预约…</p><p v-if="contextError" role="alert">{{ contextError }} <SButton size="sm" :disabled="locked" @click="selectReservation(reservationId)">重试预约</SButton></p><div v-if="context" class="context">{{ context.room_name }} · 座位{{ context.seat_no }} · {{ context.res_date }} {{ context.start_time }}—{{ context.end_time }}<br />当前状态：{{ reservationStatuses[context.status] || context.status }}；{{ context.building || '楼栋未登记' }} · {{ context.floor || '楼层未登记' }} · {{ context.location || '位置未登记' }}</div></div>
        <template v-else><label>所属空间<SSelect v-model="roomId" :options="roomOptions" :disabled="locked" /></label><p v-if="roomsLoading" role="status">正在读取空间选项…</p><p v-if="roomsError" role="alert">空间选项读取失败：{{ roomsError }} <SButton size="sm" :disabled="locked" @click="loadRooms">重试空间</SButton></p><label v-if="branch==='other_service'">诉求类别<SInput v-model="serviceTopic" list="service-topics" maxlength="100" :disabled="locked" placeholder="例如环境卫生或噪音秩序；也可据实填写其他类别" /><datalist id="service-topics"><option v-for="item in Object.values(serviceTopics)" :key="item" :value="item" /></datalist></label><template v-if="branch==='facility_repair'"><label>故障类型<SInput v-model="faultType" list="fault-types" maxlength="100" :disabled="locked" placeholder="按现场事实填写；当前已配置选项可参考下拉提示" /><datalist id="fault-types"><option v-for="item in faultSuggestions" :key="item" :value="item" /></datalist></label><label>影响范围<SInput v-model="impactScope" list="impact-scopes" maxlength="100" :disabled="locked" placeholder="按实际影响填写；未配置组合不会猜测等级" /><datalist id="impact-scopes"><option v-for="item in scopeSuggestions" :key="item" :value="item" /></datalist></label><label><span><input v-model="safetyRisk" type="checkbox" :disabled="locked" />存在安全风险（直接按5级处理）</span></label><label><span><input v-model="unsafeArea" type="checkbox" :disabled="locked" />区域无法安全使用（直接按5级处理）</span></label><p v-if="priorityOptionsError" class="muted">分级映射读取失败，仍可提交事实；无精确映射时服务端标记“未配置”，不会猜级别。</p><p v-else class="muted">当前映射规则由管理员维护；未命中规则时保存为“未配置”，不代表系统已自动判定等级。</p></template></template>
        <label>情况说明<SInput v-model="description" type="textarea" :rows="5" required maxlength="2000" :disabled="locked" placeholder="描述发生的情况；不必知道或猜测占用者的身份" /></label><p class="muted">{{ description.trim().length }}/2000字。仅文字也可提交，请勿填写不必要的个人信息。</p>
        <p v-if="branch==='other_service'" class="muted">其他服务问题不需要上传报修现场照片，可仅文字提交。</p><EvidenceUploadQueue v-else-if="branch==='facility_repair'||branch==='occupation'" :items="items" :disabled="locked" :uploading="uploading" @select="selectFiles" @retry="uploadItem" @remove="removeItem" />
        <SButton type="submit" variant="primary" :loading="submitting" :disabled="uploading || (!uncertain && items.some(item=>item.phase!=='uploaded'))">{{ uncertain ? '以原操作ID重试提交核对' : '提交，等待受理' }}</SButton>
      </form>
    </template>
    <template v-else><SBanner type="success" :title="`#${receipt.id} 已提交，当前${complaintStatuses[receipt.status] || receipt.status}${receipt.replayed?'（原请求幂等回执）':''}`" /><p v-if="reading" role="status">正在读取已保存详情…</p><SBanner v-if="readError" type="error" :title="readError"><SButton :loading="reading" @click="refreshCreated">重试读取详情</SButton></SBanner><ComplaintRecord :record="record || receipt" /><div class="actions"><RouterLink :to="`/complaints/${receipt.id}`">独立查看详情</RouterLink><SButton :disabled="reading" @click="reset">另起一条新提交</SButton></div></template>
  </section>
</template>
<style scoped>.complaint-form,.fields{display:grid;gap:14px;min-width:0}.fields label{display:grid;gap:7px;font-size:var(--fs-caption)}.fields input[type=file]{max-width:100%;min-width:0}.context,.file-item{padding:12px;background:var(--surface-2);border-radius:var(--r-md);overflow-wrap:anywhere}.actions{display:flex;flex-wrap:wrap;gap:10px;align-items:center}.operation-key{overflow-wrap:anywhere}</style>
