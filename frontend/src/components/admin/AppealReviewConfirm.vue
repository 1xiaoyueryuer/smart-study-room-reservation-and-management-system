<script setup>
import {computed,onBeforeUnmount,ref} from 'vue'
import {previewAppealReview,confirmAppealReview} from '../../api/appeal'
import {validAppealDetail,appealStatuses} from '../../composables/appealForm.js'
import {validEligibility} from '../../composables/restrictionForm.js'
import AppealRecalculationSummary from '../AppealRecalculationSummary.vue'
import RestrictionRecord from '../RestrictionRecord.vue'
import SDialog from '../ui/SDialog.vue'
import SButton from '../ui/SButton.vue'
import SBanner from '../ui/SBanner.vue'
const visible=ref(false),loading=ref(false),submitting=ref(false),unknown=ref(false),stale=ref(true),preview=ref(null),error=ref(''),operationId=ref('')
const locked=computed(()=>submitting.value||unknown.value)
let targetId=null,intent=null,body=null,expected=null,resolver=null,alive=true,sequence=0
const copy=value=>JSON.parse(JSON.stringify(value))
function finish(result){submitting.value=false;unknown.value=false;loading.value=false;visible.value=false;sequence++;const resolve=resolver;resolver=null;resolve?.(result)}
function cancel(){if(locked.value)return;loading.value=false;finish(false)}
function run(id,value){if(locked.value)throw new Error('原审核结果未确认，请先以原操作ID核对');if(resolver)finish(false);targetId=id;intent=copy(value);preview.value=null;body=null;expected=null;unknown.value=false;error.value='';operationId.value='';visible.value=true;const pending=new Promise(resolve=>{resolver=resolve});void loadPreview();return pending}
async function loadPreview(){if(!alive||!visible.value||locked.value)return;const ticket=++sequence;loading.value=true;stale.value=true;error.value='';preview.value=null;body=null;expected=null;operationId.value=''
  try{const response=await previewAppealReview(targetId,copy(intent));if(!alive||ticket!==sequence)return;const value=response.data
    if(value?.appeal_id!==targetId||value.appeal_version!==intent.version||!value.normalized_intent||['version','decision','reason','verification_note','facts_confirmed'].some(key=>value.normalized_intent[key]!==intent[key])||!value.impact_hash||!Number.isSafeInteger(value.violation_change?.id)||value.violation_change.id<=0||!(intent.decision==='approved'?['revoked','already_revoked'].includes(value.violation_change.action):value.violation_change.action==='unchanged')||!Array.isArray(value.automatic_recalculations)||!Array.isArray(value.other_active_restrictions))throw new Error('审核预览返回不完整，请重新核对，尚未确认写入')
    preview.value=copy(value);operationId.value=crypto.randomUUID();stale.value=false
  }catch(failure){if(alive&&ticket===sequence)error.value=failure.message}
  finally{if(alive&&ticket===sequence)loading.value=false}
}
async function confirm(){if(!alive||!visible.value||submitting.value||loading.value||!preview.value||stale.value)return
  if(!unknown.value){body=copy({...preview.value.normalized_intent,impact_hash:preview.value.impact_hash,operation_id:operationId.value});expected={violation_id:preview.value.violation_change.id,version:preview.value.appeal_version+1,violation_action:preview.value.violation_change.action}}
  const ticket=sequence;submitting.value=true;error.value=''
  try{const response=await confirmAppealReview(targetId,copy(body));if(!alive||ticket!==sequence)return;const value=response.data
    if(value?.operation_id!==body.operation_id||!validAppealDetail(value.appeal)||value.appeal.id!==targetId||value.appeal.violation_id!==expected.violation_id||value.appeal.version!==expected.version||value.appeal.status!==body.decision||value.review?.violation_action!==expected.violation_action||value.effects?.violation_action!==expected.violation_action||value.appeal.review?.decision!==value.review?.decision||value.appeal.review?.reason!==value.review?.reason||value.appeal.review?.violation_action!==value.review?.violation_action||value.review?.decision!==body.decision||value.review.reason!==body.reason||value.appeal.review?.id!==value.review.id||value.review.verification_note!==body.verification_note||value.review.facts_confirmed!==true||!Number.isSafeInteger(value.review.id)||!Array.isArray(value.effects?.automatic_recalculations)||!validEligibility(value.eligibility))throw new Error('审核回执不完整，请以原操作ID核对')
    unknown.value=false;finish(value)
  }catch(failure){if(!alive||ticket!==sequence)return;unknown.value=failure.status==null||failure.status===408||failure.status>=500
    error.value=unknown.value?'审核结果尚未确认，原审核内容、预览指纹和操作ID已锁定；请主动以原请求重试核对，不要新建审核。':failure.message
    if(!unknown.value&&failure.status===409){stale.value=true;error.value=error.value.replace(/[。.!！?？]+$/u,'')+'。本次未确认审结；当前预览不可继续确认，请重新预览，原审核草稿保留。'}
  }finally{if(alive&&ticket===sequence)submitting.value=false}
}
onBeforeUnmount(()=>{alive=false;sequence++;resolver?.(false);resolver=null})
defineExpose({run})
</script>
<template><SDialog :model-value="visible" title="审核影响预览与确认" width="940px" :show-close="!locked" :close-on-click-modal="!locked" :close-on-escape="!locked" @update:model-value="value=>{if(!value)cancel()}"><section class="review-preview"><p v-if="unknown" role="status">提交结果尚未确认，不能据此认定尚未执行；请使用原操作ID核对。</p><p v-else-if="submitting" role="status">正在提交审核，请等待真实回执。</p><p v-else role="status">当前仅预览，尚未执行审核或纠错。</p><p v-if="loading" role="status">正在只读核对审核内容和原批次影响，尚未执行审核…</p><SBanner v-if="error" type="error" :title="error" /><template v-if="preview&&!loading"><h4>申诉#{{targetId}} · {{preview.normalized_intent.decision==='approved'?'拟同意申诉':'拟驳回申诉'}}</h4><p class="reason">公开审核理由：{{preview.normalized_intent.reason}}</p><p class="reason">管理私有核实依据：{{preview.normalized_intent.verification_note}}</p><p>管理员自报已核实依据，不代表系统验证现场；审核不受申请提交期再次限制。</p><p>违规#{{preview.violation_change.id}} · {{({revoked:'将撤销该违规',already_revoked:'已撤销，保留原撤销事实',unchanged:'保持原违规状态'})[preview.violation_change.action]||preview.violation_change.action}}。</p><AppealRecalculationSummary :items="preview.automatic_recalculations" preview /><h4>当前其他独立有效限制</h4><p v-if="!preview.other_active_restrictions.length">预览时未列出其他有效限制；不据此保证批准后可预约。</p><RestrictionRecord v-for="item in preview.other_active_restrictions" :key="item.id" :record="item" /><p v-if="preview.post_approval_recheck_required">批准后还将由服务端统一评估，可能因其他未消费违规产生新累计限制；最终资格以执行后真实结果为准。</p><p>预览时刻{{preview.server_now}}（{{preview.campus_zone}}）；原版本{{preview.appeal_version}}；操作ID{{operationId}}</p><p v-if="stale">这是已失效预览，不能继续确认。</p></template></section><template #footer><SButton :disabled="locked" @click="cancel">返回原草稿</SButton><SButton :disabled="locked||loading" @click="loadPreview">重新预览</SButton><SButton variant="primary" :loading="submitting" :disabled="loading||stale||!preview" @click="confirm">{{unknown?'以原操作ID重试核对':'确认上述审核'}}</SButton></template></SDialog></template>
<style scoped>.review-preview{display:grid;gap:13px;min-width:0;overflow-wrap:anywhere}.reason{white-space:pre-wrap}</style>
