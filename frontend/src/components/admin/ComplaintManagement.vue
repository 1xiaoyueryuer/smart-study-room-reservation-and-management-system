<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { listAdminComplaints, getAdminComplaint, acceptComplaint, recordComplaintVerification, feedbackComplaint } from '../../api/complaint'
import { registerEventViolation } from '../../api/violation'
import { listUsers } from '../../api/admin'
import { getRooms } from '../../api/room'
import { complaintTypes, complaintStatuses, verificationOutcomes, reservationStatuses, positiveId, verificationPayload, feedbackPayload } from '../../composables/complaintForm.js'
import { confirmDialog } from '../ui/feedback'
import { useServiceTargetClock } from '../../composables/useServiceTargetClock.js'
import ServiceTargetSummary from '../ServiceTargetSummary.vue'
import ComplaintRecord from '../ComplaintRecord.vue'
import UsageRecordSummary from '../UsageRecordSummary.vue'
import SSelect from '../ui/SSelect.vue'
import SInput from '../ui/SInput.vue'
import SButton from '../ui/SButton.vue'
import SBanner from '../ui/SBanner.vue'
import {recordQuery,validRecordPage,recordFields} from '../../composables/recordPage.js'
import RecordFilters from '../RecordFilters.vue'
import RecordPageControls from '../RecordPageControls.vue'
const snapshot=ref(null),snapshotQuery=ref(null),fields=recordFields('complaint',true)
const filters=ref({status:null,type:null,room_id:null}),appliedFilters=ref(null),items=ref([]),loaded=ref(false),listLoading=ref(false),listError=ref('')
const selectedId=ref(null),detail=ref(null),detailLoading=ref(false),detailError=ref(''),users=ref([]),rooms=ref([]),optionsError=ref('')
const submitting=ref(false),unknown=ref(false),readPending=ref(false),actionError=ref(''),writeNotice=ref(''),pending=ref(null)
const busy=computed(()=>submitting.value||unknown.value||readPending.value)
const blank=()=>({outcome:'',occurred_at:'',public_note:'',private_note:'',subject_user_id:null,identity_confirmed:false,identity_basis:'',facts_confirmed:false,valid_reservation_confirmed:false})
const form=ref(blank()),feedbackForm=ref({result:'',basis:''}),violationReasons=ref({})
const outcomeOptions=computed(()=>Object.entries(verificationOutcomes).filter(([value])=>detail.value?.type==='occupation'?value!=='service_verified':!['cooperated','verified_refusal'].includes(value)).map(([value,label])=>({value,label})))
const occupancy=computed(()=>['cooperated','verified_refusal'].includes(form.value.outcome))
const subjectOptions=computed(()=>[{value:null,label:'身份未知，不关联账号'},...users.value.filter(user=>!occupancy.value||user.id!==detail.value?.reporter?.id).map(user=>({value:user.id,label:`#${user.id} ${user.real_name} · ${user.username} · ${user.campus_id || '校园编号未登记'}${user.status==='active'?'':'（账号已禁用，仍可核实身份）'}`}))])
const roomOptions=computed(()=>[{value:null,label:'全部空间'},...rooms.value.map(room=>({value:room.id,label:room.name}))])
let alive=true,listSequence=0,detailSequence=0,scope=0
const copy=value=>JSON.parse(JSON.stringify(value))
function explicitInstant(value){
  const match=typeof value==='string'&&value.match(/^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2})(?::(\d{2})(?:\.(\d{1,9}))?)?([+-]\d{2}:\d{2})$/)
  if(!match)return null
  const whole=Date.parse(`${match[1]}:${match[2]||'00'}${match[4]}`)
  return Number.isFinite(whole)?BigInt(whole)*1000000n+BigInt((match[3]||'').padEnd(9,'0')):null
}
const targetClock=useServiceTargetClock({records:computed(()=>[...items.value,...(detail.value?[detail.value]:[])]),blocked:computed(()=>busy.value||listLoading.value||detailLoading.value),onDue:()=>Promise.all([loadList(true),selectedId.value?readDetail(selectedId.value):Promise.resolve()])})
async function loadOptions(){optionsError.value='';try{const [accounts,resources]=await Promise.all([listUsers(),getRooms()]);if(alive){users.value=accounts.data;rooms.value=resources.data}}catch(failure){if(alive)optionsError.value=failure.message}}
async function loadList(force=true,next){if(busy.value&&force!==true)return false;const ticket=++listSequence,source=force===false?filters.value:appliedFilters.value||filters.value;let query;try{query=recordQuery(source,force===false?1:next||snapshot.value?.page||1)}catch(e){listError.value=e.message;return false}listLoading.value=true;listError.value='';try{const {data}=await listAdminComplaints(query);if(!alive||ticket!==listSequence)return false;if(!validRecordPage(data,query))throw new Error('管理投诉分页返回不完整');items.value=data.items;appliedFilters.value={...source};snapshot.value=data;snapshotQuery.value={...query};loaded.value=true;return true}catch(failure){if(alive&&ticket===listSequence)listError.value=failure.message;return false}finally{if(alive&&ticket===listSequence)listLoading.value=false}}
async function readDetail(id,{afterWrite=false}={}){const ticket=++detailSequence;detailLoading.value=true;detailError.value=''
  try{const response=await getAdminComplaint(id);if(!alive||ticket!==detailSequence)return false;const value=response.data;if(value?.id!==id||!Array.isArray(value.admin_events)||!Number.isInteger(value.version))throw new Error('管理详情返回不完整');detail.value=value
    if(afterWrite){readPending.value=false;if(pending.value?.action==='verification')form.value=blank();if(pending.value?.action==='feedback')feedbackForm.value={result:'',basis:''};if(pending.value?.action==='violation')delete violationReasons.value[pending.value.event_id];pending.value=null}return true
  }catch(failure){if(alive&&ticket===detailSequence)detailError.value=(afterWrite?'操作已保存，详情读取失败，请重试读取，不要重复写入：':'')+failure.message;return false}
  finally{if(alive&&ticket===detailSequence)detailLoading.value=false}
}
async function openDetail(id){if(busy.value)return;scope++;selectedId.value=id;detail.value=null;form.value=blank();feedbackForm.value={result:'',basis:''};violationReasons.value={};actionError.value='';writeNotice.value='';pending.value=null;return readDetail(id)}
function changeOutcome(value){if(busy.value)return;form.value.outcome=value
  if(value==='identity_pending'){form.value.subject_user_id=null;form.value.identity_confirmed=false}
  if(['cooperated','verified_refusal'].includes(value)&&form.value.subject_user_id===detail.value?.reporter?.id){form.value.subject_user_id=null;form.value.identity_confirmed=false}
}
function changeSubject(value){if(busy.value)return;form.value.subject_user_id=value;form.value.identity_confirmed=false;form.value.identity_basis=''}
async function sendPending(){actionError.value='';const operation=copy(pending.value),ticket=scope
  try{const response=await (operation.action==='violation'?registerEventViolation(operation.event_id,operation.body):({accept:acceptComplaint,verification:recordComplaintVerification,feedback:feedbackComplaint})[operation.action](operation.id,operation.body))
    if(!alive||ticket!==scope)return
    const value=response.data
    if(operation.action==='violation'){
      const violation=value?.violation
      if(value?.operation_id!==operation.body.operation_id||!Number.isSafeInteger(violation?.id)||violation.id<=0||violation.source_event_id!==operation.event_id||violation.user_id!==operation.fact.user_id||violation.reservation_id!==operation.fact.reservation_id||violation.type!=='occupation_refusal'||violation.source!=='manual'||violation.reason!==operation.body.reason||explicitInstant(violation.occurred_at)===null||explicitInstant(violation.occurred_at)!==explicitInstant(operation.fact.occurred_at)||!Number.isSafeInteger(violation.notification_id)||violation.notification_id<=0||explicitInstant(violation.notified_at)===null)throw new Error('违规登记回执不完整，请以原操作ID核对')
      unknown.value=false;readPending.value=true
      writeNotice.value=`违规#${violation.id}已登记${value.replayed?'（原操作幂等回执）':''}，请核对刷新后的登记状态；服务端将按累计规则和当前有效限制统一评估，以实际限制记录与资格为准。`
    }else{
      if(value?.operation_id!==operation.body.operation_id||!Number.isInteger(value.event_id)||value.event_id<=0||value.complaint?.id!==operation.id||!Number.isInteger(value.complaint.version)||!Array.isArray(value.complaint.admin_events))throw new Error('写入回执不完整，请以原操作ID核对')
      unknown.value=false;readPending.value=true;detail.value=value.complaint
      writeNotice.value=`操作已保存${value.replayed?'（原操作幂等回执）':''}，事件#${value.event_id}；当前${complaintStatuses[value.complaint.status] || value.complaint.status}。受理或核实不代表已解决，提醒不计违规。`
    }
    await Promise.all([readDetail(operation.id,{afterWrite:true}),loadList(true)])
  }catch(failure){if(!alive||ticket!==scope)return;unknown.value=failure.status==null||failure.status===408||failure.status>=500
    actionError.value=unknown.value?(operation.action==='violation'?'写入结果尚未确认，原事件、公开依据和操作ID已锁定；请主动以同一操作ID重试核对，不要另建操作。':'写入结果尚未确认，原动作、版本、内容和操作ID已锁定；请主动以同一操作ID重试核对，不要另建操作。'):failure.message
    if(!unknown.value&&failure.status===409){
      if(operation.action==='violation'){
        const existing=failure.details?.existing_violation_id
        actionError.value=actionError.value.replace(/[。.!！?？]+$/u,'')+(Number.isSafeInteger(existing)&&existing>0?`；已有违规#${existing}`:'')+'。已请求刷新真实登记状态，公开依据草稿保留；请勿重复登记同一事实。'
      }else actionError.value+='。请查看刷新后的真实进度和版本，本次未重复执行；核实草稿保留。'
      await Promise.all([readDetail(operation.id),loadList(true)])
    }
  }
}
async function act(action){if(busy.value||detailLoading.value||detailError.value||!detail.value)return
  if((action==='accept'&&detail.value.status!=='submitted')||(action==='verification'&&detail.value.status!=='processing')||(action==='feedback'&&(detail.value.status!=='processing'||!detail.value.admin_allowed_actions?.includes('feedback'))))return
  let payload={}
  try{if(action==='verification')payload=verificationPayload(form.value,detail.value);if(action==='feedback')payload=feedbackPayload(feedbackForm.value)}catch(failure){actionError.value=failure.message;return}
  pending.value={id:detail.value.id,action,body:{...payload,version:detail.value.version,operation_id:crypto.randomUUID()}}
  const ticket=scope;submitting.value=true;actionError.value=''
  try{try{await confirmDialog(action==='accept'?'确认受理并开始核查？首受理人将记录当前管理员，受理不代表已解决。':action==='feedback'?'确认发出本次正式反馈？结果与依据将公开给投诉人，并从本次反馈起给予7×24小时确认期；请勿复制私有身份或核实资料。':'确认追加这条核实记录？已有事件不会被覆盖；本次不登记违规或处罚。','确认管理操作',{type:'warning'})}catch{return}
    if(alive&&ticket===scope)await sendPending()
  }finally{if(alive&&ticket===scope)submitting.value=false}
}
async function actViolation(event){
  if(busy.value||detailLoading.value||detailError.value||!detail.value||event.outcome!=='verified_refusal'||event.violation_registration?.eligible!==true||event.violation_registration.existing_violation)return
  const reason=String(violationReasons.value[event.id]||'').trim()
  if(!reason||reason.length>1000){actionError.value='请独立填写1至1000字面向被登记者的公开事实依据，不要复制私有核实资料';return}
  pending.value={id:detail.value.id,action:'violation',event_id:event.id,body:{operation_id:crypto.randomUUID(),reason},fact:{user_id:event.subject?.id,reservation_id:detail.value.reservation_id,occurred_at:event.occurred_at}}
  const operation=copy(pending.value),ticket=scope;submitting.value=true;actionError.value='';writeNotice.value=''
  try{try{await confirmDialog(`被登记者：#${event.subject?.id} ${event.subject?.real_name} / ${event.subject?.username}。原预约：#${detail.value.reservation_id}，${detail.value.context_snapshot?.room_name||''} / ${detail.value.context_snapshot?.seat_no||''}。事实发生：${event.occurred_at}。公开事实依据：\n${operation.body.reason}\n\n确认从该不可改核实事件登记拒不配合占座违规？事实由管理员据实核实记录，登记后由服务端按累计规则和当前有效限制统一评估，以实际限制记录与资格为准。`,'确认独立登记违规',{type:'warning'})}catch{return}
    if(alive&&ticket===scope)await sendPending()
  }finally{if(alive&&ticket===scope)submitting.value=false}
}
async function retryUnknown(){if(!unknown.value||submitting.value||!pending.value)return;submitting.value=true;try{await sendPending()}finally{if(alive)submitting.value=false}}
function refreshDetail(){if(submitting.value||unknown.value||!selectedId.value)return;return readDetail(selectedId.value,{afterWrite:readPending.value})}
onMounted(()=>{void loadOptions();void loadList()})
onBeforeUnmount(()=>{alive=false;scope++;listSequence++;detailSequence++})
defineExpose({loadList})
</script>
<template>
  <section class="complaint-management">
    <section class="card"><h3>投诉管理</h3><p>受理表示开始核查，核实记录与公开进度分别保留。提醒不计违规，投诉人不自动成为待核对象。</p>
      <RecordFilters v-model="filters" :fields="fields" :disabled="busy||listLoading" @apply="loadList(false)"/><RecordPageControls :snapshot="snapshot" :query="snapshotQuery" :fields="fields" :disabled="busy||listLoading" @page="loadList(true,$event)"/>
      <p v-if="appliedFilters" class="muted">已应用：{{ complaintStatuses[appliedFilters.status]||'全部状态' }} / {{ complaintTypes[appliedFilters.type]||'全部类型' }} / {{ appliedFilters.room_id?'空间#'+appliedFilters.room_id:'全部空间' }}。修改筛选后请应用，列表不会自动冒充新条件。</p>
      <p v-if="listLoading" role="status">正在读取管理列表…</p><SBanner v-if="listError" type="error" :title="listError" /><p v-if="!loaded&&!listLoading&&!listError">尚未读取管理列表。</p><p v-if="loaded&&!listLoading&&!listError&&!items.length">当前条件暂无投诉。</p>
      <p v-if="loaded&&listError">下列保留上次成功读取的列表快照，不代表本次筛选或当前状态，请重试核对。</p><div v-if="loaded&&!listLoading" class="table-wrap"><div class="table-scroll" tabindex="0" aria-label="管理投诉列表，可横向滚动"><table class="table management-table"><thead><tr><th>记录</th><th>类型/状态</th><th>提交时间</th><th>首受理人</th><th>服务目标</th><th>详情</th></tr></thead><tbody><tr v-for="item in items" :key="item.id"><td>#{{ item.id }}</td><td>{{ complaintTypes[item.type] || item.type }} / {{ complaintStatuses[item.status] || item.status }}</td><td>{{ item.submitted_at }}</td><td>{{ item.handler?.display_name || '尚未受理' }}</td><td><ServiceTargetSummary :record="item" :progress="targetClock.progress(item)" compact /></td><td><SButton size="sm" :disabled="busy" @click="openDetail(item.id)">打开详情</SButton></td></tr></tbody></table></div></div>
    </section>
    <section v-if="selectedId" class="card management-detail"><div class="card-title-row"><h3>管理详情 #{{ selectedId }}</h3><SButton :loading="detailLoading" :disabled="submitting||unknown" @click="refreshDetail">{{ readPending?'重试读取已保存结果':'刷新详情' }}</SButton></div><p v-if="detailLoading" role="status">正在读取管理详情及当前关联…</p><SBanner v-if="detailError" type="error" :title="detailError" /><SBanner v-if="writeNotice" type="info" :title="writeNotice" /><SBanner v-if="actionError" type="error" :title="actionError" />
      <div v-if="unknown" class="unknown"><p>原操作ID：{{ pending?.body.operation_id }}</p><SButton :loading="submitting" @click="retryUnknown">以原操作ID重试核对</SButton></div>
      <template v-if="detail"><p>投诉记录版本：{{ detail.version }}。</p><p>投诉人：{{ detail.reporter?.real_name }} · {{ detail.reporter?.username }} · {{ detail.reporter?.campus_id || '校园编号未登记' }}。下方主体选择不会默认此人。</p><ComplaintRecord :record="detail" :target-progress="targetClock.progress(detail)" />
        <section class="current-context"><h4>当前关联只读核对</h4><p>读取时刻：{{ detail.current_context?.observed_at }}。当前提示不能代替事实发生当时的权利核实，也不能推定真实占用者。</p><template v-if="detail.current_context?.reservation"><p>{{ detail.current_context.reservation.room_name }} / {{ detail.current_context.reservation.seat_no }} / {{ detail.current_context.reservation.date }} {{ detail.current_context.reservation.start_time }}—{{ detail.current_context.reservation.end_time }} · 当前{{ reservationStatuses[detail.current_context.reservation.status] || detail.current_context.reservation.status }}</p><p>签到截止：{{ detail.current_context.reservation.checkin_deadline }}；是否已过：{{ detail.current_context.reservation.deadline_passed===true?'是':detail.current_context.reservation.deadline_passed===false?'否':'未提供' }}；原结束是否已过：{{ detail.current_context.reservation.scheduled_end_passed===true?'是':detail.current_context.reservation.scheduled_end_passed===false?'否':'未提供' }}</p><p>当前剩余资源合法：{{ detail.current_context.reservation.remaining_resource_legal===true?'是':detail.current_context.reservation.remaining_resource_legal===false?'否':'未提供' }}；{{ detail.current_context.reservation.closed_reasons?.join('；') }}</p><p>当前位置：{{ detail.current_context.reservation.building || '楼栋未登记' }} · {{ detail.current_context.reservation.floor || '楼层未登记' }} · {{ detail.current_context.reservation.location || '位置未登记' }}；剩余核对起点：{{ detail.current_context.reservation.remaining_start || '未提供' }}。当前开放：{{ detail.current_context.reservation.opening_windows?.length ? detail.current_context.reservation.opening_windows.map(window=>window.start+'—'+window.end).join('、') : '无开放窗口' }}。</p><UsageRecordSummary :record="detail.current_context.reservation.usage_record" /></template><p v-else-if="detail.current_context?.room">当前关联空间：{{ detail.current_context.room.room_name }}</p><p v-else>无当前预约关联，不据此补造预约或占座事实。</p></section>
        <SButton v-if="detail.status==='submitted'" variant="primary" :disabled="busy||detailLoading||!!detailError" :loading="submitting" @click="act('accept')">受理并开始核查</SButton>
        <section><h4>管理私有核实记录</h4><p v-if="!detail.admin_events.length">尚无管理事件。</p><article v-for="event in detail.admin_events" :key="event.id" class="private-event"><p>{{ ({submitted:'本人提交',accepted:'受理',verification:'核实',feedback:'正式反馈',withdrawn:'本人撤回',confirmed:'本人确认反馈',objected:'本人提出异议',auto_closed:'系统自动结案'})[event.type] || event.type }} · {{ event.source==='system'?'系统':event.actor_display_name || '记录人未提供' }} · 登记{{ event.created_at }} · 发生{{ event.occurred_at || '未提供' }}</p><p v-if="event.outcome">结果：{{ verificationOutcomes[event.outcome] || event.outcome }}</p><p>核实对象：{{ event.subject ? '#'+event.subject.id+' '+event.subject.real_name+' / '+event.subject.username : '未知或未关联账号' }}</p><p v-if="event.private_note">私有事实：{{ event.private_note }}</p><p v-if="event.identity_basis">身份依据（管理员自报）：{{ event.identity_basis }}</p><p>身份自报确认：{{ event.identity_confirmed===true?'是':event.identity_confirmed===false?'否':'未记录' }}；事实自报确认：{{ event.facts_confirmed===true?'是':event.facts_confirmed===false?'否':'未记录' }}；发生时有效预约权利自报确认：{{ event.valid_reservation_confirmed===true?'是':event.valid_reservation_confirmed===false?'否':'未记录' }}。</p><section v-if="event.outcome==='verified_refusal'" class="violation-registration"><h5>独立违规登记</h5><template v-if="event.violation_registration?.existing_violation"><p>已有违规#{{ event.violation_registration.existing_violation.id }} · {{ event.violation_registration.existing_violation.status==='revoked'?'已撤销（仍保留同一事实登记历史，不可重登）':'已登记' }} · 登记{{ event.violation_registration.existing_violation.registered_at }} · {{ event.violation_registration.existing_violation.registrant_display_name || '登记人未提供' }}。</p><p v-if="violationReasons[event.id]?.trim()" class="preserved-public-draft">本次未提交的公开依据草稿：<span>{{ violationReasons[event.id] }}</span></p></template><template v-else-if="event.violation_registration?.eligible"><p>被登记者：#{{ event.subject?.id }} {{ event.subject?.real_name }} / {{ event.subject?.username }}；原预约#{{ detail.reservation_id }}；事实发生{{ event.occurred_at }}。主体和事实固定取自此核实事件。</p><label>面向被登记者的公开事实依据<SInput v-model="violationReasons[event.id]" type="textarea" maxlength="1000" :disabled="busy" placeholder="独立填写1至1000字；勿复制管理私有记录、身份依据或投诉人资料" /></label><SButton variant="danger" size="sm" :disabled="busy||detailLoading||!!detailError" :loading="submitting&&pending?.event_id===event.id" @click="actViolation(event)">从此核实事件登记违规</SButton></template><p v-else>{{ event.violation_registration?.ineligible_reason || '尚未读取本事件的登记资格，请刷新详情核对' }}</p></section></article></section>
        <form v-if="detail.status==='processing' && detail.admin_allowed_actions?.includes('feedback')" class="verification-form formal-feedback" @submit.prevent="act('feedback')"><h4>发出正式反馈</h4><p>以下结果与依据将对投诉人公开，与管理私有核实记录分开。发出后等待本人确认或异议，确认期为本次反馈起7×24小时；待确认期间不能直接覆盖。</p><label>公开反馈结果<SInput v-model="feedbackForm.result" type="textarea" maxlength="2000" :disabled="busy" required /></label><label>公开反馈依据（勿复制私有身份或核实资料）<SInput v-model="feedbackForm.basis" type="textarea" maxlength="2000" :disabled="busy" required /></label><SButton type="submit" variant="primary" :disabled="busy||detailLoading||!!detailError" :loading="submitting">发出正式反馈并等待确认</SButton></form>
        <form v-if="detail.status==='processing'" class="verification-form" @submit.prevent="act('verification')"><h4>追加核实事件（不是反馈结案）</h4><p>所有启用管理员可追加，首受理人不改变。以下确认由管理员据实自报，不代表系统验证了物理身份或现场。</p><label>核实结果<SSelect :model-value="form.outcome" :options="outcomeOptions" :disabled="busy" @update:model-value="changeOutcome" /></label><label>事实发生时刻（Asia/Shanghai）<SInput v-model="form.occurred_at" type="datetime-local" step="0.000001" :disabled="busy" required /></label><label>给投诉人的公开进度（请勿填他人身份或联系方式）<SInput v-model="form.public_note" type="textarea" maxlength="1000" :disabled="busy" required /></label><label>管理私有事实与协调说明<SInput v-model="form.private_note" type="textarea" maxlength="2000" :disabled="busy" required placeholder="据实填写核实依据；工程模拟请明确注明，不冒充真实现场核查" /></label><label><span><input v-model="form.facts_confirmed" type="checkbox" :disabled="busy" /> 管理员自报已核实所述事实</span></label><label v-if="occupancy"><span><input v-model="form.valid_reservation_confirmed" type="checkbox" :disabled="busy" /> 我已核实事实发生时刻存在有效预约使用权（不以当前状态自动认定）</span></label>
          <SBanner v-if="optionsError" type="error" :title="optionsError"><SButton size="sm" :disabled="busy" @click="loadOptions">重试账号和空间选项</SButton></SBanner><label v-if="form.outcome!=='identity_pending'">{{ form.outcome==='verified_refusal'?'已核实主体账号（本结果必填，不得是投诉人）':'已核实主体账号（可未知，不默认投诉人）' }}<SSelect :model-value="form.subject_user_id" :options="subjectOptions" :disabled="busy" @update:model-value="changeSubject" /></label><template v-if="form.subject_user_id"><label><span><input v-model="form.identity_confirmed" type="checkbox" :disabled="busy" /> 管理员自报已核实该账号对应的身份</span></label><label>身份核实依据（仅管理可见）<SInput v-model="form.identity_basis" type="textarea" maxlength="1000" :disabled="busy" required /></label></template><p>配合纠正仅生成提醒，不计违规；其他分支也不会在本次操作自动记违规或处罚。私有依据不发送给投诉人。</p><SButton type="submit" variant="primary" :disabled="busy||detailLoading||!!detailError" :loading="submitting">追加核实记录</SButton></form>
      </template>
    </section>
  </section>
</template>
<style scoped>.complaint-management,.management-detail,.verification-form{display:grid;gap:16px;min-width:0}.filter-fields{display:flex;flex-wrap:wrap;gap:12px;align-items:end}.filter-fields label{min-width:130px;flex:1}.verification-form label,.filter-fields label{display:grid;gap:7px;font-size:var(--fs-caption)}.management-table{min-width:1040px}.management-table th:nth-child(1){width:6%}.management-table th:nth-child(2){width:13%}.management-table th:nth-child(3){width:17%}.management-table th:nth-child(4){width:14%}.management-table th:nth-child(5){width:34%}.management-table th:nth-child(6){width:16%}.current-context,.private-event{padding:12px;background:var(--surface-2);border-radius:var(--r-md);overflow-wrap:anywhere}.private-event{margin:10px 0}.violation-registration{display:grid;gap:9px;margin-top:12px;padding-top:12px;border-top:1px solid var(--border)}.violation-registration label{display:grid;gap:7px}.preserved-public-draft{white-space:pre-wrap;user-select:text}.unknown{overflow-wrap:anywhere}.current-context{display:grid;gap:8px}</style>
