<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { getComplaint, withdrawComplaint, confirmComplaintFeedback, objectComplaintFeedback } from '../api/complaint'
import { useServiceTargetClock } from '../composables/useServiceTargetClock.js'
import { ownerComplaintActions } from '../composables/complaintForm.js'
import { confirmDialog } from '../components/ui/feedback'
import ComplaintRecord from '../components/ComplaintRecord.vue'
import SButton from '../components/ui/SButton.vue'
import SInput from '../components/ui/SInput.vue'
import SBanner from '../components/ui/SBanner.vue'
const props=defineProps({id:{type:[String,Number],required:true}})
const record=ref(null),loading=ref(false),error=ref(''),description=ref(''),submitting=ref(false),unknown=ref(false),readPending=ref(false),pending=ref(null),actionError=ref(''),writeNotice=ref('')
const actions=computed(()=>ownerComplaintActions(record.value)),busy=computed(()=>submitting.value||unknown.value||readPending.value)
const actionLabels={withdraw:'撤回待受理投诉',confirm:'确认本次反馈并结案',object:'提交异议，回到处理中'}
const actionApis={withdraw:withdrawComplaint,confirm:confirmComplaintFeedback,object:objectComplaintFeedback}
let sequence=0,scope=0,alive=true
const targetClock=useServiceTargetClock({records:computed(()=>record.value?[record.value]:[]),blocked:computed(()=>busy.value||loading.value),onDue:()=>load({preserve:true})})
async function load({afterWrite=false,allowDuringWrite=false,preserve=false}={}){if((submitting.value||unknown.value)&&!afterWrite&&!allowDuringWrite)return false;const ticket=++sequence,id=props.id;loading.value=true;error.value='';if(!afterWrite&&!preserve)record.value=null
  try{const response=await getComplaint(id);if(alive&&ticket===sequence){if(String(response.data?.id)!==String(id)||!Array.isArray(response.data?.attachments)||!Array.isArray(response.data?.events))throw new Error('详情返回不完整');record.value=response.data;if(afterWrite){if(pending.value?.action==='object')description.value='';pending.value=null;readPending.value=false}return true}}
  catch(failure){if(alive&&ticket===sequence)error.value=(afterWrite?'操作已记录，详情读取失败，请重试读取，不要重复提交：':'')+failure.message;return false}
  finally{if(alive&&ticket===sequence)loading.value=false}
}
async function sendPending(){const operation=JSON.parse(JSON.stringify(pending.value)),ticket=scope;actionError.value=''
  try{const response=await actionApis[operation.action](operation.id,operation.body);if(!alive||ticket!==scope)return;const value=response.data
    if(value?.operation_id!==operation.body.operation_id||!Number.isInteger(value.event_id)||String(value.complaint?.id)!==String(operation.id)||!Array.isArray(value.complaint?.events))throw new Error('操作回执不完整，请以原操作ID核对')
    unknown.value=false;readPending.value=true;record.value=value.complaint;writeNotice.value=`${actionLabels[operation.action]}已记录${value.replayed?'（原请求幂等回执）':''}，请查看当前真实进度。`
    await load({afterWrite:true})
  }catch(failure){if(!alive||ticket!==scope)return;unknown.value=failure.status==null||failure.status===408||failure.status>=500
    actionError.value=unknown.value?'结果尚未确认，原动作、内容、版本和操作ID已锁定；请主动以同一操作ID重试核对，不要另起操作。':failure.status===409&&failure.details?.closure_type==='auto_closed'?`确认期已届满，系统已按到期规则结案；${actionLabels[operation.action]}未执行，请查看刷新后的真实状态。`:failure.message
    if(!unknown.value&&failure.status===409){actionError.value=actionError.value.replace(/[。.!！?？]+$/u,'')+'。已请求刷新真实状态。'+(operation.action==='object'?'原异议文字保留。':'');await load({allowDuringWrite:true})}
  }
}
async function act(action){if(busy.value||loading.value||error.value||!actions.value.includes(action)||!record.value)return
  const body={version:record.value.version,operation_id:crypto.randomUUID()}
  if(action==='object'){const text=description.value.trim();if(!text||text.length>2000){actionError.value='请填写1至2000字的异议说明';return}body.description=text}
  pending.value={id:record.value.id,action,body,feedback_id:record.value.current_feedback?.id,deadline:record.value.current_feedback?.deadline};const ticket=scope;submitting.value=true;actionError.value='';writeNotice.value=''
  try{try{await confirmDialog(action==='withdraw'?'确认撤回这条待受理投诉？撤回历史将保留，不改变关联预约。':action==='confirm'?'确认接受本次正式反馈并结案？请先核对当前反馈结果与公开依据。':'确认提交异议？本投诉将回到处理中，说明会保留，等待重新处理和反馈。',actionLabels[action],{type:'warning'})}catch{return}
    if(alive&&ticket===scope)await sendPending()
  }finally{if(alive&&ticket===scope)submitting.value=false}
}
async function retryUnknown(){if(!unknown.value||submitting.value||!pending.value)return;submitting.value=true;try{await sendPending()}finally{if(alive)submitting.value=false}}
function refresh(){if(submitting.value||unknown.value)return;return load({afterWrite:readPending.value})}
watch(()=>props.id,()=>{scope++;sequence++;record.value=null;pending.value=null;submitting.value=false;unknown.value=false;readPending.value=false;description.value='';actionError.value='';writeNotice.value='';void load()},{immediate:true})
onBeforeUnmount(()=>{alive=false;sequence++;scope++})
</script>
<template><div class="page-view complaint-detail"><header class="view-heading" data-page-title><h1>求助与投诉详情</h1><RouterLink to="/complaints">返回本人列表和提交表单</RouterLink></header><section class="card"><SButton :loading="loading" :disabled="submitting||unknown" @click="refresh">{{ readPending?'重试读取已记录结果':'刷新详情' }}</SButton><p v-if="loading" role="status">正在鉴权读取本人详情和当前允许动作…</p><SBanner v-if="error" type="error" :title="error"><SButton :disabled="submitting||unknown" @click="refresh">重试详情</SButton></SBanner><SBanner v-if="actionError" type="error" :title="actionError" /><SBanner v-if="writeNotice" type="info" :title="writeNotice" /><div v-if="unknown" class="unknown"><p>原操作ID：{{ pending?.body.operation_id }}</p><SButton :loading="submitting" @click="retryUnknown">以原操作ID重试核对</SButton></div><ComplaintRecord v-if="record&&!loading" :record="record" :target-progress="targetClock.progress(record)" /><section v-if="record&&!loading&&!error" class="owner-actions"><p>以下动作由本次服务端读取结果提供，提交时会再次检查版本及确认期限。</p><SButton v-if="actions.includes('withdraw')" :disabled="busy" @click="act('withdraw')">撤回待受理投诉</SButton><SButton v-if="actions.includes('confirm')" variant="primary" :disabled="busy" @click="act('confirm')">确认本次反馈并结案</SButton><template v-if="actions.includes('object')"><label>本次异议说明<SInput v-model="description" type="textarea" maxlength="2000" :rows="4" :disabled="busy" placeholder="1至2000字，说明对本次反馈的异议" /></label><SButton :disabled="busy" @click="act('object')">提交异议，回到处理中</SButton></template><p v-if="!actions.length">当前无本人可执行动作，请以真实状态和历史为准。</p></section></section></div></template>
<style scoped>.complaint-detail{max-width:1100px}.complaint-detail>.card,.owner-actions{display:grid;gap:16px;min-width:0}.owner-actions label{display:grid;gap:8px}.unknown{overflow-wrap:anywhere}</style>
