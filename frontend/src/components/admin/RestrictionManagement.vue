<script setup>
import {computed,onBeforeUnmount,onMounted,ref} from 'vue'
import {listUsers} from '../../api/admin'
import {listAdminRestrictions,createRestriction,releaseRestriction} from '../../api/restriction'
import {restrictionStatuses,restrictionPayload,validEligibility,eligibilityText,offsetInstant} from '../../composables/restrictionForm.js'
import {confirmDialog} from '../ui/feedback'
import RestrictionRecord from '../RestrictionRecord.vue'
import SButton from '../ui/SButton.vue'
import SInput from '../ui/SInput.vue'
import SSelect from '../ui/SSelect.vue'
import SBanner from '../ui/SBanner.vue'
const users=ref([]),usersError=ref(''),items=ref([]),loaded=ref(false),loading=ref(false),error=ref(''),filters=ref({user_id:null,status:null}),applied=ref(null)
const blank=()=>({reason:'',verification_note:'',facts_confirmed:false})
const form=ref({...blank(),user_id:null,ends_at:''}),releaseForm=ref(blank()),selected=ref(null),submitting=ref(false),unknown=ref(false),readPending=ref(false),pending=ref(null),actionError=ref(''),receipt=ref(null)
const busy=computed(()=>submitting.value||unknown.value||readPending.value)
const userOptions=computed(()=>[{value:null,label:'明确选择普通用户'},...users.value.filter(user=>['student','staff'].includes(user.role)).map(user=>({value:user.id,label:`#${user.id} ${user.real_name} / ${user.username} / ${user.campus_id||'校园编号未登记'}${user.status==='active'?'':'（已停用）'}`}))])
const filterUserOptions=computed(()=>[{value:null,label:'全部账号记录（含历史余效）'},...users.value.map(user=>({value:user.id,label:`#${user.id} ${user.real_name} / ${user.username}${user.role==='admin'?'（管理员历史记录）':''}`}))])
const copy=value=>JSON.parse(JSON.stringify(value));let alive=true,sequence=0
async function loadUsers(){usersError.value='';try{const response=await listUsers();if(alive)users.value=response.data}catch(failure){if(alive)usersError.value=failure.message}}
async function loadList({afterWrite=false,useApplied=false,allowDuringWrite=false}={}){
  if((submitting.value||unknown.value)&&!afterWrite&&!allowDuringWrite)return false
  const ticket=++sequence,source=useApplied&&applied.value?applied.value:filters.value,query={}
  if(source.user_id)query.user_id=source.user_id;if(source.status)query.status=source.status
  applied.value=copy(source);loading.value=true;error.value=''
  try{const response=await listAdminRestrictions(query);if(!alive||ticket!==sequence)return false;if(!Array.isArray(response.data))throw new Error('管理限制列表返回不完整');items.value=response.data;loaded.value=true
    if(selected.value)selected.value=items.value.find(item=>item.id===selected.value.id)||{...selected.value,unverified:true}
    if(afterWrite){if(pending.value?.action==='create')form.value={...blank(),user_id:form.value.user_id,ends_at:''};else releaseForm.value=blank();readPending.value=false;pending.value=null}return true
  }catch(failure){if(alive&&ticket===sequence)error.value=(afterWrite?'操作已保存，列表读取失败；请重试读取，不要重复写入：':'')+failure.message;return false}
  finally{if(alive&&ticket===sequence)loading.value=false}
}
function choose(record){if(busy.value)return;selected.value=record;releaseForm.value=blank();actionError.value=''}
function validReceipt(value,operation){
  const record=value?.restriction,effects=value?.effects
  return value?.operation_id===operation.body.operation_id&&Number.isSafeInteger(record?.id)&&record.id>0&&record.subject?.id===operation.user_id&&Number.isInteger(record.version)&&['active','expired','released'].includes(record.status)&&validEligibility(value.eligibility)&&Array.isArray(effects?.cancelled_pending)&&Number.isSafeInteger(effects.settled_unused_count)&&effects.settled_unused_count>=0&&Number.isSafeInteger(effects.continued_checked_in_count)&&effects.continued_checked_in_count>=0&&effects.cancelled_pending.every(row=>Number.isSafeInteger(row.reservation_id)&&row.reservation_id>0&&row.status==='cancelled'&&row.termination_reason==='admin_restriction')&&(operation.action==='create'?record.source==='manual'&&offsetInstant(record.starts_at)!==null&&record.reason===operation.body.reason&&offsetInstant(record.ends_at)!==null&&offsetInstant(record.ends_at)===offsetInstant(operation.body.ends_at)&&Number.isSafeInteger(record.notification_id)&&record.notification_id>0&&offsetInstant(record.notified_at)!==null:record.id===operation.id&&record.status==='released'&&offsetInstant(record.released_at)!==null&&record.release_reason===operation.body.reason&&Number.isSafeInteger(record.release_notification_id)&&record.release_notification_id>0&&offsetInstant(record.release_notified_at)!==null)
}
async function sendPending(){const operation=copy(pending.value);actionError.value=''
  try{const response=await (operation.action==='create'?createRestriction(operation.body):releaseRestriction(operation.id,operation.body));if(!alive)return
    if(!validReceipt(response.data,operation))throw new Error('限制操作回执不完整，请以原操作ID核对')
    receipt.value=response.data;unknown.value=false;readPending.value=true;await loadList({afterWrite:true,useApplied:true})
  }catch(failure){if(!alive)return;unknown.value=failure.status==null||failure.status===408||failure.status>=500
    actionError.value=unknown.value?'结果尚未确认，原动作、账号、内容与操作ID已锁定；请主动以原操作ID重试核对，不要另建操作。':failure.message
    if(!unknown.value&&failure.status===409){actionError.value=actionError.value.replace(/[。.!！?？]+$/u,'')+'。本次未确认成功；请查看刷新后的真实状态，原输入保留。';await loadList({useApplied:true,allowDuringWrite:true})}
  }
}
async function act(action){if(busy.value||loading.value)return
  let payload,user,record
  try{if(action==='create'){payload=restrictionPayload(form.value);user=users.value.find(item=>item.id===payload.user_id&&['student','staff'].includes(item.role));if(!user)throw new Error('请先读取并明确选择真实普通账号')}else{record=selected.value;if(!record||record.status!=='active'||record.unverified)return;payload={...restrictionPayload(releaseForm.value,true),version:record.version};user=record.subject}}
  catch(failure){actionError.value=failure.message;return}
  pending.value={action,id:record?.id||null,user_id:user.id,body:{...payload,operation_id:crypto.randomUUID()}};submitting.value=true;actionError.value='';receipt.value=null
  const operation=copy(pending.value)
  try{try{await confirmDialog(`账号：#${user.id} ${user.real_name} / ${user.username}。\n${action==='create'?'立即按服务端时刻生效，截止：'+payload.ends_at:'仅解除限制#'+record.id+'，其他有效限制仍分别保留'}\n公开原因：\n${payload.reason}\n\n管理私有核实依据：\n${payload.verification_note}\n\n核实由管理员据实自报，不代表系统验证了现场。已到签到期限的预约先按原规则结算；仍有效的待签到预约会管理取消且不新增违约；使用中的预约继续至原结束。解除不复活已取消预约；全部限制结束时可能立即按未消费违规生成新的累计限制，请以实际当前资格为准。`,'确认预约限制操作',{type:'warning'})}catch{return}
    if(alive&&pending.value?.body.operation_id===operation.body.operation_id)await sendPending()
  }finally{if(alive)submitting.value=false}
}
async function retryUnknown(){if(!unknown.value||submitting.value||!pending.value)return;submitting.value=true;try{await sendPending()}finally{if(alive)submitting.value=false}}
function refresh(){return loadList({afterWrite:readPending.value,useApplied:readPending.value})}
onMounted(()=>{void loadUsers();void loadList()});onBeforeUnmount(()=>{alive=false;sequence++})
defineExpose({loadList:refresh})
</script>
<template><section class="restriction-management"><section class="card"><h3>预约限制管理</h3><p>每条限制独立保存；登记核实依据由管理员据实自报。使用中继续原结束，单条解除不清除其他限制，也不复活已取消预约。</p><form class="filter-fields" @submit.prevent="refresh"><label>账号<SSelect v-model="filters.user_id" :options="filterUserOptions" :disabled="busy||loading" /></label><label>状态<SSelect v-model="filters.status" :options="[{value:null,label:'全部状态'},...Object.entries(restrictionStatuses).map(([value,label])=>({value,label}))]" :disabled="busy||loading" /></label><SButton type="submit" :disabled="busy" :loading="loading">应用筛选并刷新</SButton></form><p v-if="loading" role="status">正在读取真实限制列表…</p><SBanner v-if="error" type="error" :title="error"><SButton :disabled="submitting||unknown" @click="refresh">{{readPending?'重试读取已保存结果':'重试查询'}}</SButton></SBanner><p v-if="!loaded&&!loading&&!error">尚未读取限制列表。</p><p v-if="loaded&&!loading&&!error&&!items.length">当前条件暂无限制记录。</p><div v-if="loaded&&!loading&&!error" class="records"><section v-for="record in items" :key="record.id"><p>账号#{{record.subject.id}} {{record.subject.real_name}} / {{record.subject.username}} · {{record.subject.role==='staff'?'教职工':record.subject.role==='student'?'学生':'管理员'}} · {{record.subject.status==='active'?'启用':'停用'}}；记录版本{{record.version}}</p><RestrictionRecord :record="record" /><p v-if="record.verification_note" class="private-note">管理私有核实依据：{{record.verification_note}}</p><p v-if="record.release_verification_note" class="private-note">解除私有核实依据：{{record.release_verification_note}}</p><SButton v-if="record.status==='active'" size="sm" :disabled="busy" @click="choose(record)">选择解除此条</SButton></section></div></section>
<SBanner v-if="usersError" type="error" :title="usersError"><SButton :disabled="busy" @click="loadUsers">重试账号选项</SButton></SBanner><SBanner v-if="actionError" type="error" :title="actionError" /><div v-if="unknown"><p>原操作ID：{{pending?.body.operation_id}}</p><SButton :loading="submitting" @click="retryUnknown">以原操作ID重试核对</SButton></div><section v-if="receipt" class="card write-receipt"><h4>操作已保存{{receipt.replayed?'（原操作幂等回执）':''}} · 限制#{{receipt.restriction.id}}</h4><p>{{eligibilityText(receipt.eligibility)}}。资格为回执时快照，提交新预约仍由服务端复核。</p><p>本次原执行结果：管理取消待签到{{receipt.effects.cancelled_pending.length}}条，按原规则结算未使用{{receipt.effects.settled_unused_count}}条，继续使用{{receipt.effects.continued_checked_in_count}}条。幂等重放不会再次执行这些处理。</p><ul><li v-for="row in receipt.effects.cancelled_pending" :key="row.reservation_id">预约#{{row.reservation_id}} · 已管理取消 · {{row.ended_at}} · 公开原因{{row.termination_note}}</li></ul><p v-if="receipt.eligibility.active_restrictions.length">当前仍有{{receipt.eligibility.active_restrictions.length}}条独立有效限制。</p><RestrictionRecord v-for="record in receipt.eligibility.active_restrictions" :key="record.id" :record="record" /></section>
<form class="card write-form" @submit.prevent="act('create')"><h4>新增立即生效的独立限制</h4><label>普通账号（明确选择，不默认任何投诉人或违规主体）<SSelect v-model="form.user_id" :options="userOptions" :disabled="busy" /></label><label>截止时刻（Asia/Shanghai）<SInput v-model="form.ends_at" type="datetime-local" step="0.000001" :disabled="busy" required /></label><label>本人可见的公开原因<SInput v-model="form.reason" type="textarea" maxlength="1000" :disabled="busy" required /></label><label>管理私有核实依据<SInput v-model="form.verification_note" type="textarea" maxlength="2000" :disabled="busy" required placeholder="据实填写；工程模拟请明确说明，不冒充真人现场核查" /></label><label><span><input v-model="form.facts_confirmed" type="checkbox" :disabled="busy" /> 管理员自报已核实本次限制依据</span></label><SButton type="submit" variant="primary" :disabled="busy||loading" :loading="submitting&&pending?.action==='create'">核对并新增限制</SButton></form>
<form v-if="selected" class="card write-form" @submit.prevent="act('release')"><h4>仅解除限制#{{selected.id}} · {{restrictionStatuses[selected.status]}}</h4><RestrictionRecord :record="selected" /><p v-if="selected.unverified">本次列表未包含原选记录，当前状态尚未核对；请调整筛选并重新选择，原输入保留。</p><p>其他有效限制仍保留；全部限制结束时可能立即生成新的累计限制，是否可新预约取服务端统一资格。已到期记录不能冒作人工解除。</p><label>公开解除原因<SInput v-model="releaseForm.reason" type="textarea" maxlength="1000" :disabled="busy||selected.unverified||selected.status!=='active'" required /></label><label>管理私有核实依据<SInput v-model="releaseForm.verification_note" type="textarea" maxlength="2000" :disabled="busy||selected.unverified||selected.status!=='active'" required /></label><label><span><input v-model="releaseForm.facts_confirmed" type="checkbox" :disabled="busy||selected.unverified||selected.status!=='active'" /> 管理员自报已核实本次解除依据</span></label><SButton type="submit" :disabled="busy||loading||selected.unverified||selected.status!=='active'" :loading="submitting&&pending?.action==='release'">核对并仅解除此条</SButton></form></section></template>
<style scoped>.restriction-management,.write-form,.records{display:grid;gap:16px;min-width:0}.write-form label,.filter-fields label{display:grid;gap:7px}.filter-fields{display:flex;flex-wrap:wrap;gap:12px;align-items:end}.filter-fields label{flex:1;min-width:160px}.private-note{white-space:pre-wrap;overflow-wrap:anywhere;padding:10px;background:var(--surface-2)}.write-receipt{overflow-wrap:anywhere}</style>
