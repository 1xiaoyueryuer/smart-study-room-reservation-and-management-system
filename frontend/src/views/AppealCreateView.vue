<script setup>
import {computed,onBeforeUnmount,ref,watch} from 'vue'
import {useRoute} from 'vue-router'
import {getAppealEligibility,createAppeal,getAppeal,uploadAppealAttachment,getAppealAttachment} from '../api/appeal'
import {appealBlockedText,appealPayload,validAppealDetail,validAppealEligibility,violationLabels} from '../composables/appealForm.js'
import {offsetInstant} from '../composables/restrictionForm.js'
import {positiveId} from '../composables/complaintForm.js'
import {useEvidenceUploadQueue} from '../composables/useEvidenceUploadQueue.js'
import EvidenceUploadQueue from '../components/EvidenceUploadQueue.vue'
import AppealRecord from '../components/AppealRecord.vue'
import SInput from '../components/ui/SInput.vue'
import SButton from '../components/ui/SButton.vue'
import SBanner from '../components/ui/SBanner.vue'
const route=useRoute(),violation=ref(null),eligibility=ref(null),serverNow=ref(''),campusZone=ref(''),loading=ref(false),loadError=ref(''),description=ref(''),submitting=ref(false),unknown=ref(false),error=ref(''),receipt=ref(null),record=ref(null),reading=ref(false),readError=ref('')
const locked=computed(()=>submitting.value||unknown.value||!!receipt.value),canSubmit=computed(()=>!!eligibility.value?.can_submit&&!loading.value&&!loadError.value)
let alive=true,scope=0,sequence=0,readSequence=0,violationId=null,frozen=null,frozenBasis=null,fingerprint=''
const queue=useEvidenceUploadQueue({uploadFile:uploadAppealAttachment,isLocked:()=>locked.value||!canSubmit.value,isActive:()=>alive,setError:text=>{error.value=text}})
const {items,uploading,uploadItem,selectFiles,removeItem}=queue
async function loadEligibility(){if(locked.value)return;const ticket=++sequence,id=violationId;loading.value=true;loadError.value='';if(!id){loading.value=false;loadError.value='请从本人真实违规记录选择申诉条件';return}
  try{const response=await getAppealEligibility(id);if(!alive||ticket!==sequence)return;if(!validAppealEligibility(response.data,id))throw new Error('申诉资格返回不完整');violation.value=response.data.violation;eligibility.value=response.data.eligibility;serverNow.value=response.data.server_now;campusZone.value=response.data.campus_zone}
  catch(failure){if(alive&&ticket===sequence)loadError.value=failure.message}
  finally{if(alive&&ticket===sequence)loading.value=false}
}
async function refreshCreated(){if(!receipt.value)return;const ticket=++readSequence,id=receipt.value.id;reading.value=true;readError.value=''
  try{const response=await getAppeal(id);if(!alive||ticket!==readSequence)return;if(!validAppealDetail(response.data)||response.data.id!==id)throw new Error('申诉详情返回不完整');record.value=response.data}
  catch(failure){if(alive&&ticket===readSequence)readError.value='申诉已保存，详情读取失败，请只重试读取，不要重复提交：'+failure.message}
  finally{if(alive&&ticket===readSequence)reading.value=false}
}
async function submit(){if(submitting.value||uploading.value||receipt.value||(!unknown.value&&!canSubmit.value))return;error.value=''
  if(!unknown.value){try{const body=appealPayload(description.value,items.value),signature=JSON.stringify(body);if(!frozen||fingerprint!==signature){frozen={...body,operation_id:crypto.randomUUID()};frozenBasis={id:eligibility.value.notification_id,notified_at:eligibility.value.notified_at,deadline:eligibility.value.deadline};fingerprint=signature}}catch(failure){error.value=failure.message;return}}
  const ticket=scope,id=violationId,body=JSON.parse(JSON.stringify(frozen));submitting.value=true
  try{const response=await createAppeal(id,body);if(!alive||ticket!==scope)return;const value=response.data,detail=value?.appeal
    if(value?.operation_id!==body.operation_id||!validAppealDetail(detail)||detail.violation_id!==id||detail.status!=='submitted'||detail.notification_basis.id!==frozenBasis?.id||offsetInstant(detail.notification_basis.notified_at)!==offsetInstant(frozenBasis.notified_at)||offsetInstant(detail.notification_basis.deadline)!==offsetInstant(frozenBasis.deadline)||detail.description!==body.description||detail.attachments.length!==body.attachment_ids.length||new Set(detail.attachments.map(item=>item.id)).size!==body.attachment_ids.length||detail.attachments.some(item=>!body.attachment_ids.includes(item.id)))throw new Error('申诉回执不完整，请以原操作ID核对')
    unknown.value=false;receipt.value=detail;record.value=null;await refreshCreated()
  }catch(failure){if(!alive||ticket!==scope)return;unknown.value=failure.status==null||failure.status===408||failure.status>=500
    error.value=unknown.value?'提交结果尚未确认，原违规、说明、材料ID和操作ID已锁定；请主动以原操作ID重试核对，不要另建申诉。':failure.message
    if(!unknown.value&&failure.status===409){if(failure.details?.code==='appeal_pending'&&Number.isSafeInteger(failure.details.existing_appeal?.id))eligibility.value={...eligibility.value,can_submit:false,blocked_by:'pending',existing_appeal:failure.details.existing_appeal};else if(failure.details?.code==='appeal_not_eligible'&&typeof failure.details.eligibility?.can_submit==='boolean'){eligibility.value=failure.details.eligibility;if(typeof failure.details.server_now==='string')serverNow.value=failure.details.server_now;if(typeof failure.details.campus_zone==='string')campusZone.value=failure.details.campus_zone;}error.value=error.value.replace(/[。.!！?？]+$/u,'')+'。原说明和照片项保留，尚未确认本次提交成功。'}
  }finally{if(alive&&ticket===scope)submitting.value=false}
}
watch(()=>route.query.violation_id,()=>{scope++;sequence++;readSequence++;queue.reset();violation.value=null;eligibility.value=null;receipt.value=null;record.value=null;description.value='';error.value='';readError.value='';unknown.value=false;submitting.value=false;reading.value=false;frozen=null;frozenBasis=null;fingerprint='';try{violationId=positiveId(route.query.violation_id)}catch(failure){violationId=null}void loadEligibility()},{immediate:true})
onBeforeUnmount(()=>{alive=false;scope++;sequence++;readSequence++;queue.dispose()})
</script>
<template><div class="page-view appeal-create"><header class="view-heading" data-page-title><h1>本人违规申诉</h1><RouterLink to="/profile#violations">返回本人违规与进度</RouterLink></header><section class="card"><p>期限与是否可提交由服务端核对。提交仅登记待审核，不撤销违规、不解除限制、不恢复预约。</p><SButton :loading="loading" :disabled="locked" @click="loadEligibility">刷新申诉条件</SButton><p v-if="loading" role="status">正在核对本人违规及真实通知期限…</p><SBanner v-if="loadError" type="error" :title="loadError" /><section v-if="violation"><p>违规#{{violation.id}} · {{violationLabels[violation.type]||violation.type}} · {{violation.status==='active'?'有效':violation.status==='revoked'?'已撤销':violation.status}}</p><p>发生{{violation.occurred_at}}；登记{{violation.registered_at}}</p><p class="text">公开事实依据：{{violation.reason}}</p><p v-if="violation.source==='manual'">关联预约涉及他人，不公开编号</p><p v-if="eligibility?.notified_at">真实通知生成：{{eligibility.notified_at}}；原申诉截止：{{eligibility.deadline}}（Asia/Shanghai）。生成通知不代表已阅读。</p><p v-if="eligibility&&!eligibility.can_submit">{{appealBlockedText[eligibility.blocked_by]||'当前不能提交，请刷新核对'}}</p><RouterLink v-if="eligibility?.existing_appeal?.id" :to="`/appeals/${eligibility.existing_appeal.id}`">查看已有申诉#{{eligibility.existing_appeal.id}}</RouterLink><p v-if="serverNow">服务端核对：{{serverNow}}<span v-if="campusZone">（{{campusZone}}）</span></p></section><SBanner v-if="error" type="error" :title="error" /><p v-if="unknown">原操作ID：{{frozen?.operation_id}}</p><form v-if="violation&&!receipt" class="appeal-fields" @submit.prevent="submit"><label>申诉说明<SInput v-model="description" type="textarea" :rows="5" maxlength="2000" :disabled="locked||!canSubmit" placeholder="1至2000字，可仅文字提交；请勿填写不必要的个人资料" /></label><EvidenceUploadQueue :items="items" :disabled="locked||!canSubmit" :uploading="uploading" :load-content="getAppealAttachment" @select="selectFiles" @retry="uploadItem" @remove="removeItem" /><p v-if="!canSubmit&&!unknown&&(description||items.length)">原输入仅保留供查看，当前服务端不允许新提交；不会丢弃文字或静默移除照片。</p><SButton type="submit" variant="primary" :loading="submitting" :disabled="uploading||(!unknown&&!canSubmit)">{{unknown?'以原操作ID重试提交核对':'提交申诉，等待审核'}}</SButton></form><section v-if="receipt"><p>申诉#{{receipt.id}}已保存为待审核，提交本身不会解除限制。</p><RouterLink :to="`/appeals/${receipt.id}`">打开独立申诉详情</RouterLink><SBanner v-if="readError" type="error" :title="readError" /><SButton :loading="reading" @click="refreshCreated">重试读取已保存申诉</SButton><AppealRecord v-if="record" :record="record" /></section></section></div></template>
<style scoped>.appeal-create{max-width:1100px}.appeal-create>.card,.appeal-fields{display:grid;gap:16px;min-width:0}.appeal-fields label{display:grid;gap:7px}.text{white-space:pre-wrap;overflow-wrap:anywhere}</style>
