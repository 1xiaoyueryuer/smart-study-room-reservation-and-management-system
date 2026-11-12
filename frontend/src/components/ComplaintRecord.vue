<script setup>
import ServiceTargetSummary from './ServiceTargetSummary.vue'
import ProtectedAttachment from './ProtectedAttachment.vue'
import { complaintDisplayType, complaintNumber, impactScopeText, serviceTopics, complaintStatuses, verificationOutcomes, reservationStatuses } from '../composables/complaintForm.js'
import { formatCampusDateTime } from '../composables/campusDateTime.js'

const props = defineProps({
  record: { type: Object, required: true },
  targetProgress: { type: Object, default: null },
  compact: { type: Boolean, default: false }
})

const eventLabels = {
  submitted: '已提交', accepted: '已受理', verification: '核实进度', feedback: '正式回复',
  service_progress: '维修进度', withdrawn: '已撤回', confirmed: '已确认回复',
  objected: '已提交异议', auto_closed: '系统结案'
}
function eventLabel(event) {
  return event.type === 'service_progress'
    ? (event.service_progress_stage === 'handoff' ? '已交接维修渠道' : '维修进度更新')
    : (eventLabels[event.type] || '处理进度更新')
}
</script>

<template>
  <section class="complaint-record">
    <header class="ticket-heading">
      <div>
        <span class="ticket-number-label">投诉/维修单号</span>
        <strong class="ticket-number">{{ complaintNumber(record.id) }}</strong>
      </div>
      <div class="ticket-status">
        <span>{{ complaintDisplayType(record) }}</span>
        <span class="pill" :class="record.status === 'closed' ? 'pill-success' : record.status === 'submitted' ? 'pill-neutral' : 'pill-warning'">
          {{ complaintStatuses[record.status] || record.status }}
        </span>
      </div>
    </header>

    <section class="record-section record-summary">
      <div class="record-meta">
        <div><span>提交时间</span><b>{{ formatCampusDateTime(record.submitted_at) }}</b></div>
        <div v-if="record.handler"><span>首受理人</span><b>{{ record.handler.display_name }}<small v-if="record.accepted_at"> · {{ formatCampusDateTime(record.accepted_at) }}</small></b></div>
      </div>
      <p class="description">{{ record.description }}</p>
      <p v-if="record.initial_arrangement" class="public-plan"><b>初步安排</b>{{ record.initial_arrangement }}</p>
      <p v-if="record.status === 'closed'" class="closure-note">
        {{ record.closure_type === 'auto_closed' ? '确认期届满，系统自动结案' : record.closure_type === 'confirmed' ? '已确认回复并结案' : '已结案' }}
        <span v-if="record.closed_at"> · {{ formatCampusDateTime(record.closed_at) }}</span>
      </p>
    </section>

    <ServiceTargetSummary :record="record" :progress="targetProgress" :compact="compact" />

    <section v-if="record.service_kind === 'facility_repair'" class="record-section">
      <h4>报修信息</h4>
      <div class="record-meta">
        <div><span>故障类型</span><b>{{ record.fault_type || '未填写' }}</b></div>
        <div><span>影响范围</span><b>{{ impactScopeText(record.impact_scope) }}</b></div>
        <div><span>安全风险</span><b>{{ record.safety_risk ? '存在' : '无' }}<template v-if="record.unsafe_area"> · 区域暂不可安全使用</template></b></div>
        <div><span>当前优先级</span><b>{{ record.triaged_priority ? `${record.triaged_priority}级（管理员核定）` : record.initial_priority ? `${record.initial_priority}级` : '待管理员核定' }}</b></div>
      </div>
      <p v-if="record.triaged_reason" class="muted">核定说明：{{ record.triaged_reason }}</p>
      <p class="muted">等级用于处理排序，不代表维修期限或完成承诺。</p>
    </section>
    <section v-else-if="record.service_kind === 'other_service'" class="record-section">
      <h4>服务类别</h4><p>{{ serviceTopics[record.service_topic] || record.service_topic || '其他服务问题' }}</p>
    </section>

    <section v-if="record.context_snapshot" class="record-section">
      <h4>关联位置</h4>
      <p>{{ record.context_snapshot.room_name }}<span v-if="record.context_snapshot.building"> · {{ record.context_snapshot.building }}</span><span v-if="record.context_snapshot.floor"> · {{ record.context_snapshot.floor }}</span><span v-if="record.context_snapshot.location"> · {{ record.context_snapshot.location }}</span></p>
      <p v-if="record.context_snapshot.reservation_id" class="muted">关联预约 #{{ record.context_snapshot.reservation_id }} · 座位 {{ record.context_snapshot.seat_no }} · {{ record.context_snapshot.date }} {{ record.context_snapshot.start_time }}—{{ record.context_snapshot.end_time }} · 提交时{{ reservationStatuses[record.context_snapshot.status_at_submission] || record.context_snapshot.status_at_submission }}</p>
    </section>

    <section v-if="record.current_feedback" class="record-section feedback-record">
      <h4>最新正式回复</h4>
      <p class="description"><b>处理结果：</b>{{ record.current_feedback.result }}</p>
      <p class="description"><b>回复说明：</b>{{ record.current_feedback.basis }}</p>
      <div class="record-meta">
        <div><span>回复时间</span><b>{{ formatCampusDateTime(record.current_feedback.feedback_at) }}</b></div>
        <div v-if="record.current_feedback.deadline && record.status === 'awaiting_confirmation'"><span>确认截止</span><b>{{ formatCampusDateTime(record.current_feedback.deadline) }}</b></div>
      </div>
      <p v-if="record.current_feedback.response_type" class="muted">
        {{ ({confirmed:'已确认回复',objected:'已提出异议，重新处理中',auto_closed:'确认期届满，系统结案'})[record.current_feedback.response_type] || '已记录回复' }}
        <span v-if="record.current_feedback.responded_at"> · {{ formatCampusDateTime(record.current_feedback.responded_at) }}</span>
      </p>
    </section>

    <details class="record-section attachments-section">
      <summary>照片材料（{{ record.attachment_count }}张）</summary>
      <p v-if="!record.attachments?.length" class="muted">本次未附照片。</p>
      <div v-else class="attachments">
        <ProtectedAttachment v-for="attachment in record.attachments" :key="attachment.id" :attachment="attachment" />
      </div>
    </details>

    <section v-if="record.events?.some(event => event.type !== 'submitted')" class="record-section">
      <h4>处理进度</h4>
      <ol class="progress-list">
        <li v-for="event in record.events.filter(event => event.type !== 'submitted')" :key="event.id">
          <div class="progress-heading"><b>{{ eventLabel(event) }}</b><time>{{ formatCampusDateTime(event.created_at) }}</time></div>
          <p v-if="event.occurred_at">事实发生时间：{{ formatCampusDateTime(event.occurred_at) }}</p>
          <p v-if="event.outcome">{{ verificationOutcomes[event.outcome] || event.outcome }}</p>
          <p v-if="event.public_note">{{ event.public_note }}</p>
          <p v-if="event.service_progress_stage">维修渠道：{{ event.maintenance_channel }}<span v-if="event.handoff_at"> · 交接 {{ formatCampusDateTime(event.handoff_at) }}</span></p>
          <p v-if="event.triage_priority">核定优先级：{{ event.triage_priority }}级<span v-if="event.triage_reason"> · {{ event.triage_reason }}</span></p>
          <p v-if="event.result"><b>处理结果：</b>{{ event.result }}</p>
          <p v-if="event.basis"><b>回复说明：</b>{{ event.basis }}</p>
          <p v-if="event.feedback_deadline">确认截止：{{ formatCampusDateTime(event.feedback_deadline) }}</p>
          <p v-if="event.description" class="description">异议说明：{{ event.description }}</p>
          <p v-if="event.actor_display_name" class="muted">记录人：{{ event.actor_display_name }}</p>
        </li>
      </ol>
    </section>

    <p class="record-disclaimer">提交不代表已受理或解决；现场问题以实际核实结果为准。</p>
  </section>
</template>

<style scoped>
.complaint-record{display:grid;gap:14px;min-width:0;overflow-wrap:anywhere}
.ticket-heading{display:flex;justify-content:space-between;align-items:center;gap:14px;padding:14px 16px;border:1px solid var(--border-strong);border-radius:var(--r-lg);background:var(--surface-2)}
.ticket-number-label,.record-meta span{display:block;color:var(--text-3);font-size:var(--fs-caption)}
.ticket-number{font-size:var(--fs-body);letter-spacing:.03em;color:var(--primary-active)}
.ticket-status{display:grid;justify-items:end;gap:6px;color:var(--text-2);font-size:var(--fs-caption)}
.record-section{display:grid;gap:10px;min-width:0;padding:14px 16px;border:1px solid var(--border-strong);border-radius:var(--r-lg);background:var(--surface)}
.record-section h4,.record-section p{margin:0}
.record-meta{display:grid;grid-template-columns:repeat(auto-fit,minmax(min(220px,100%),1fr));gap:10px 18px}
.record-meta b{display:block;margin-top:3px;font-weight:600;overflow-wrap:anywhere}
.description{white-space:pre-wrap;line-height:1.65}
.public-plan{padding:10px 12px;border-radius:var(--r-md);background:var(--surface-2);line-height:1.6}
.closure-note,.record-disclaimer{color:var(--text-3);font-size:var(--fs-caption);line-height:1.6}
.attachments-section summary{cursor:pointer;font-weight:600}
.attachments{display:grid;grid-template-columns:repeat(auto-fit,minmax(min(220px,100%),1fr));gap:12px}
.progress-list{list-style:none;margin:0;padding:0;display:grid;gap:10px}
.progress-list li{display:grid;gap:6px;padding:12px;border-left:3px solid var(--primary);border-radius:0 var(--r-md) var(--r-md) 0;background:var(--surface-2)}
.progress-list li p{line-height:1.55}
.progress-heading{display:flex;justify-content:space-between;align-items:baseline;gap:12px;flex-wrap:wrap}
.progress-heading time{color:var(--text-3);font-size:var(--fs-caption);font-variant-numeric:tabular-nums}
.feedback-record{border-color:var(--primary-weak)}
@media(max-width:560px){.ticket-heading{align-items:flex-start;flex-direction:column}.ticket-status{justify-items:start}.record-section{padding:12px}}
</style>
