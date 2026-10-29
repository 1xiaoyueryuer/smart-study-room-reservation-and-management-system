<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { createComplaint, getComplaint, uploadComplaintAttachment } from '../api/complaint'
import { getRooms } from '../api/room'
import { getReservation, listMyReservations } from '../api/reservation'
import { complaintTypes, reservationStatuses, positiveId, complaintPayload, validateEvidenceFile } from '../composables/complaintForm.js'
import ProtectedAttachment from './ProtectedAttachment.vue'
import ComplaintRecord from './ComplaintRecord.vue'
import SInput from './ui/SInput.vue'
import SSelect from './ui/SSelect.vue'
import SButton from './ui/SButton.vue'
import SBanner from './ui/SBanner.vue'
const props = defineProps({ initialType:{type:String,default:'service'}, initialReservationId:{type:[String,Number],default:null} })
const emit = defineEmits(['created'])
const type=ref('service'), description=ref(''), useReservation=ref(false), reservationId=ref(null), roomId=ref(null), context=ref(null)
const rooms=ref([]), choices=ref([]), roomsError=ref(''), choicesError=ref(''), contextError=ref(''), contextLoading=ref(false), roomsLoading=ref(false), choicesLoading=ref(false)
const items=ref([]), uploading=ref(false), submitting=ref(false), uncertain=ref(false), error=ref(''), receipt=ref(null), record=ref(null), reading=ref(false), readError=ref('')
const locked=computed(()=>submitting.value || uncertain.value || !!receipt.value)
const typeOptions=Object.entries(complaintTypes).map(([value,label])=>({value,label}))
const roomOptions=computed(()=>[{value:null,label:'不关联空间'},...rooms.value.map(room=>({value:room.id,label:`${room.name} · ${room.building || '楼栋未登记'} · ${room.floor || '楼层未登记'}`}))])
const reservationOptions=computed(()=>{
  const values=choices.value.map(row=>row.id===context.value?.id?context.value:row)
  if(context.value && !values.some(row=>row.id===context.value.id)) values.unshift(context.value)
  return values.map(row=>({value:row.id,label:`#${row.id} ${row.room_name} ${row.seat_no} ${row.res_date} ${row.start_time}—${row.end_time} · ${reservationStatuses[row.status] || row.status}`}))
})
let alive=true, contextSequence=0, readSequence=0, scope=0, frozenBody=null, fingerprint=''
async function loadRooms() {roomsLoading.value=true;try{const response=await getRooms();if(alive){rooms.value=response.data;roomsError.value=''}}catch(failure){if(alive)roomsError.value=failure.message}finally{if(alive)roomsLoading.value=false}}
async function loadChoices() {choicesLoading.value=true;try{const response=await listMyReservations();if(alive){choices.value=response.data;choicesError.value=''}}catch(failure){if(alive)choicesError.value=failure.message}finally{if(alive)choicesLoading.value=false}}
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
async function uploadItem(item) {
  if(locked.value || uploading.value || !items.value.includes(item))return
  if(items.value.length>3){item.error='请先明确移除多余照片，最多3张后再上传';return}
  uploading.value=true;item.phase='uploading';item.error='';item.metadata=null
  let uploadStarted=false
  try{
    await validateEvidenceFile(item.file)
    if(!alive)return
    uploadStarted=true
    const response=await uploadComplaintAttachment(item.file)
    if(!alive)return
    const metadata=response.data
    if(!metadata?.id || !metadata.display_name || !['image/png','image/jpeg'].includes(metadata.content_type) || !metadata.size_bytes || !metadata.width || !metadata.height) throw new Error('上传未返回可核对的材料ID和信息')
    item.metadata=metadata;item.phase='uploaded'
  }catch(failure){if(alive){item.phase='error';item.error=uploadStarted && (failure.status==null || failure.status===408 || failure.status>=500) ? '上传结果未确认，未取得附件ID。请主动重试或移除此文件；未绑定暂存材料会按24小时规则清理。' : failure.message}}
  finally{if(alive)uploading.value=false}
}
async function selectFiles(event) {
  const selected=Array.from(event.target.files||[]);event.target.value=''
  if(locked.value||uploading.value)return
  const added=selected.map(file=>({key:crypto.randomUUID(),file,phase:'queued',metadata:null,error:''}));items.value.push(...added);error.value=''
  if(items.value.length>3){error.value='最多3张照片，所选文件项已保留，请明确移除多余文件后逐项重试上传';for(const item of added){item.phase='error';item.error='超过照片数量上限，尚未上传'};return}
  for(const item of added)await uploadItem(item)
}
function removeItem(item) {if(!locked.value&&!uploading.value)items.value=items.value.filter(value=>value!==item)}
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
      const body=complaintPayload({type:type.value,description:description.value,reservationId:useReservation.value?reservationId.value:null,roomId:roomId.value,items:items.value,context:context.value})
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
    if(!value?.id || value.operation_id!==frozenBody.operation_id || value.status!=='submitted' || !Array.isArray(value.attachments) || !Array.isArray(value.events) || value.attachment_count!==frozenBody.attachment_ids.length || value.attachments.length!==frozenBody.attachment_ids.length || value.attachments.some(item=>!frozenBody.attachment_ids.includes(item.id)))throw new Error('提交回执不完整，结果需以原操作ID核对')
    uncertain.value=false;receipt.value=value;record.value=null;emit('created',value.id)
    await refreshCreated()
  }catch(failure){if(alive&&ticket===scope){uncertain.value=failure.status==null||failure.status===408||failure.status>=500;error.value=uncertain.value?'提交结果尚未确认。原文字、材料ID和操作ID已锁定，请主动以原操作ID重试核对，不要重复创建。':failure.status===409?`请求冲突：${failure.message}。请核对本人列表和材料状态，未确认创建新记录。`:failure.message}}
  finally{if(alive&&ticket===scope)submitting.value=false}
}
function reset() {if(submitting.value||uncertain.value)return;scope++;readSequence++;receipt.value=null;record.value=null;readError.value='';description.value='';items.value=[];frozenBody=null;fingerprint='';error.value='';applyInitial()}
function applyInitial() {
  if(locked.value)return
  scope++;type.value=props.initialType||'service';useReservation.value=type.value==='occupation'||!!props.initialReservationId
  if(useReservation.value){void loadChoices();void selectReservation(props.initialReservationId)}
  else {contextSequence++;reservationId.value=null;context.value=null;contextError.value='';contextLoading.value=false}
}
watch(()=>[props.initialType,props.initialReservationId],applyInitial,{immediate:true})
onMounted(loadRooms)
onBeforeUnmount(()=>{alive=false;scope++;contextSequence++;readSequence++})
</script>
<template>
  <section class="card complaint-form">
    <template v-if="!receipt"><h3>提交求助或投诉</h3><p class="muted">提交后为待受理，不代表已核实、已解决或现场恢复。座位被占无需猜测对方身份；历史预约可作关联，但提交不延长签到期限、不恢复预约。</p>
      <SBanner v-if="error" type="error" :title="error" /><p v-if="uncertain" class="operation-key">原操作ID：{{ frozenBody?.operation_id }}</p>
      <form class="fields" @submit.prevent="submit">
        <label>类型<SSelect :model-value="type" :options="typeOptions" :disabled="locked" @update:model-value="changeType" /></label>
        <label v-if="type==='service'"><span><input v-model="useReservation" type="checkbox" :disabled="locked" @change="toggleReservation" /> 关联本人预约（可选）</span></label>
        <div v-if="useReservation" class="fields"><label>关联本人预约（含历史）<SSelect :model-value="reservationId" :options="reservationOptions" :disabled="locked" placeholder="从本人预约选择" @update:model-value="selectReservation" /></label><RouterLink to="/mine">前往我的预约选择求助记录</RouterLink><p v-if="choicesLoading" role="status">正在读取本人预约选项…</p><p v-if="choicesError" role="alert">预约选项读取失败：{{ choicesError }} <SButton size="sm" :disabled="locked" @click="loadChoices">重试选项</SButton></p><p v-if="contextLoading" role="status">正在核对本人预约…</p><p v-if="contextError" role="alert">{{ contextError }} <SButton size="sm" :disabled="locked" @click="selectReservation(reservationId)">重试预约</SButton></p><div v-if="context" class="context">{{ context.room_name }} · 座位{{ context.seat_no }} · {{ context.res_date }} {{ context.start_time }}—{{ context.end_time }}<br />当前状态：{{ reservationStatuses[context.status] || context.status }}；{{ context.building || '楼栋未登记' }} · {{ context.floor || '楼层未登记' }} · {{ context.location || '位置未登记' }}</div></div>
        <label v-else>关联空间（可选）<SSelect v-model="roomId" :options="roomOptions" :disabled="locked" /></label><p v-if="roomsLoading" role="status">正在读取空间选项…</p><p v-if="roomsError" role="alert">空间选项读取失败：{{ roomsError }} <SButton size="sm" :disabled="locked" @click="loadRooms">重试空间</SButton></p>
        <label>情况说明<SInput v-model="description" type="textarea" :rows="5" required maxlength="2000" :disabled="locked" placeholder="描述发生的情况；不必知道或猜测占用者的身份" /></label><p class="muted">{{ description.trim().length }}/2000字。仅文字也可提交，请勿填写不必要的个人信息。</p>
        <label>可选照片（最多3张）<input type="file" accept="image/png,image/jpeg" multiple :disabled="locked||uploading" @change="selectFiles" /></label><p class="muted">PNG/JPEG，每张不超过5MiB、单边10000像素、总像素2000万。所选材料必须全部上传成功，或由您明确移除；本地移除不删除已保存历史材料。</p>
        <article v-for="item in items" :key="item.key" class="file-item"><p>{{ item.file.name }} · {{ item.file.size }}字节 · {{ ({queued:'等待上传',uploading:'上传中',uploaded:'已上传',error:'上传失败或待核对'})[item.phase] }}</p><p v-if="item.error" role="alert">{{ item.error }}</p><ProtectedAttachment v-if="item.metadata" :attachment="item.metadata" /><div class="actions"><SButton size="sm" :disabled="locked||uploading" @click="uploadItem(item)">{{ item.phase==='uploaded'?'重新上传此照片':'重试上传此照片' }}</SButton><SButton size="sm" :disabled="locked||uploading" @click="removeItem(item)">从本次提交移除</SButton></div></article>
        <SButton type="submit" variant="primary" :loading="submitting" :disabled="uploading || (!uncertain && items.some(item=>item.phase!=='uploaded'))">{{ uncertain ? '以原操作ID重试提交核对' : '提交，等待受理' }}</SButton>
      </form>
    </template>
    <template v-else><SBanner type="success" :title="`#${receipt.id} 已提交，待受理${receipt.replayed?'（原请求幂等回执）':''}`" /><p v-if="reading" role="status">正在读取已保存详情…</p><SBanner v-if="readError" type="error" :title="readError"><SButton :loading="reading" @click="refreshCreated">重试读取详情</SButton></SBanner><ComplaintRecord :record="record || receipt" /><div class="actions"><RouterLink :to="`/complaints/${receipt.id}`">独立查看详情</RouterLink><SButton :disabled="reading" @click="reset">另起一条新提交</SButton></div></template>
  </section>
</template>
<style scoped>.complaint-form,.fields{display:grid;gap:14px;min-width:0}.fields label{display:grid;gap:7px;font-size:var(--fs-caption)}.fields input[type=file]{max-width:100%;min-width:0}.context,.file-item{padding:12px;background:var(--surface-2);border-radius:var(--r-md);overflow-wrap:anywhere}.actions{display:flex;flex-wrap:wrap;gap:10px;align-items:center}.operation-key{overflow-wrap:anywhere}</style>
