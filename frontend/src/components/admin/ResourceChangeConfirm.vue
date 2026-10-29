<script setup>
import { computed, onBeforeUnmount, ref } from 'vue'
import { previewResourceChange, confirmResourceChange } from '../../api/resourceChange'
import SDialog from '../ui/SDialog.vue'
import SButton from '../ui/SButton.vue'
import SInput from '../ui/SInput.vue'
import SBanner from '../ui/SBanner.vue'
const visible = ref(false), loading = ref(false), submitting = ref(false), uncertain = ref(false), stale = ref(false)
const preview = ref(null), error = ref(''), operationId = ref(''), coordinationConfirmed = ref(false), coordinationNote = ref('')
const locked = computed(() => submitting.value || uncertain.value)
const changeLabels = { 'room.update':'编辑空间', 'seat.update':'编辑座位', 'opening.save':'保存开放安排', 'opening.delete_exception':'删除日期例外', 'block.create':'新增临时停用', 'block.update':'编辑临时停用', 'block.cancel':'撤销临时停用' }
const changeSummary = computed(() => {
  if (!preview.value) return []
  const {change_type, payload:p} = preview.value.normalized_proposal
  if (change_type === 'room.update') return [`名称：${p.name}`, `位置：${p.building || '楼栋未登记'} · ${p.floor || '楼层未登记'} · ${p.location || '详细位置未登记'}`, `空间状态：${p.enabled ? '启用' : '停用'}；布局${p.seat_rows}行×${p.seat_cols}列`, `说明：${p.description || '未填写'}`]
  if (change_type === 'seat.update') return [`新编号：${p.seat_no}；位置：${p.row_no}行${p.col_no}列`, `状态：${({available:'可用',maintenance:'维护中',disabled:'停用'})[p.status] || p.status}`, `区域：${({regular:'普通区',quiet:'静音区',discussion:'研讨区',computer:'机房区'})[p.zone] || p.zone}；${p.has_power ? '有电源' : '无电源'}；${p.near_window ? '靠窗' : '非靠窗'}`]
  if (change_type === 'opening.save') return [`安排：${p.date || '星期' + ['','一','二','三','四','五','六','日'][p.weekday]}`, `开放：${p.windows.length ? p.windows.map(window=>`${window.start}–${window.end}`).join('、') : '整日闭馆（空窗口）'}`]
  if (change_type === 'opening.delete_exception') return [`删除${p.date}的日期例外，恢复该日星期安排`]
  if (change_type === 'block.create' || change_type === 'block.update') return [`停用范围：${p.seat_id ? '单座位（ID ' + p.seat_id + '）' : '整个空间'}`, `停用起止：${p.start_at}—${p.end_at}（Asia/Shanghai）`]
  if (change_type === 'block.cancel') return [`撤销这条临停：${preview.value.target_label || '记录ID ' + preview.value.normalized_proposal.target_id}`, '其他重叠停用记录仍按各自状态生效']
  return []
})
let proposal = null, confirmBody = null, resolveRun = null, sequence = 0, alive = true
const copy = value => JSON.parse(JSON.stringify(value))
function finish(result) { submitting.value = false; uncertain.value = false; loading.value = false; visible.value = false; sequence++; const resolve = resolveRun; resolveRun = null; resolve?.(result) }
function cancel() { if (locked.value) return; loading.value = false; finish(false) }
function onVisibility(value) { if (!value) cancel() }
function run(value) {
  if (!alive || locked.value) return Promise.reject(new Error('已有提交结果尚未确认，请先以当前操作ID核对，不要新建变更'))
  if (resolveRun) finish(false)
  proposal = copy(value); preview.value = null; confirmBody = null; error.value = ''; operationId.value = ''; stale.value = false; uncertain.value = false; coordinationConfirmed.value = false; coordinationNote.value = ''; visible.value = true
  const result = new Promise(resolve => { resolveRun = resolve })
  void loadPreview()
  return result
}
async function loadPreview() {
  if (!alive || !visible.value || locked.value || !proposal) return
  const ticket = ++sequence
  loading.value = true; error.value = ''; stale.value = true; preview.value = null; confirmBody = null; operationId.value = ''; coordinationConfirmed.value = false
  try {
    const response = await previewResourceChange(copy(proposal))
    if (!alive || ticket !== sequence) return
    const value = response.data
    if (!value?.normalized_proposal || !value.impact_hash || !value.versions || !Array.isArray(value.pending) || !Array.isArray(value.in_use) || value.summary?.immediate_termination !== true) throw new Error('影响预览数据不完整，请重试；尚未执行变更')
    preview.value = copy(value); operationId.value = crypto.randomUUID(); stale.value = false
  } catch (failure) { if (alive && ticket === sequence) error.value = `${failure.message}。尚未确认写入；若资源版本已变化，请返回表单刷新并重新核对，原输入保留。` }
  finally { if (alive && ticket === sequence) loading.value = false }
}
async function confirm() {
  if (!alive || !visible.value || submitting.value || loading.value || !preview.value || stale.value) return
  error.value = ''
  if (!uncertain.value) {
    if (preview.value.in_use.length && (!coordinationConfirmed.value || !coordinationNote.value.trim() || coordinationNote.value.trim().length > 1000)) { error.value = '有使用中预约：请由管理员勾选已现场协调，并填写不超过1000字的实际协调依据。系统仅记录自报。'; return }
    confirmBody = copy({ proposal: preview.value.normalized_proposal, impact_hash: preview.value.impact_hash, operation_id: operationId.value, ...(preview.value.in_use.length ? { coordination: { confirmed: true, note: coordinationNote.value.trim() } } : {}) })
  }
  const ticket = sequence
  submitting.value = true
  try {
    const response = await confirmResourceChange(copy(confirmBody))
    if (!alive || ticket !== sequence) return
    if (response.data?.operation_id !== confirmBody.operation_id || !Number.isInteger(response.data?.cancelled_count) || response.data.cancelled_count < 0) throw new Error('变更回执不完整，需以原操作ID核对')
    uncertain.value = false
    finish(response.data)
  } catch (failure) {
    if (!alive || ticket !== sequence) return
    uncertain.value = failure.status == null || failure.status === 408 || failure.status >= 500
    if (uncertain.value) error.value = '提交结果尚未确认。请保留当前操作，以同一操作ID主动重试核对；不要重新创建变更，也不要据此认定资源或预约未改变。'
    else if (failure.status === 409) { stale.value = true; error.value = `${failure.message}。当前预览已失效，本次未执行新变更；请重新预览并核对清单，不能沿用旧清单确认。` }
    else error.value = failure.status === 403 ? `无资源变更权限：${failure.message}` : failure.message
  } finally { if (alive && ticket === sequence) submitting.value = false }
}
onBeforeUnmount(() => { alive = false; sequence++; const resolve = resolveRun; resolveRun = null; resolve?.(false) })
defineExpose({ run })
</script>
<template>
  <SDialog :model-value="visible" title="资源变更影响预览与确认" width="1000px" :show-close="!locked" :close-on-click-modal="!locked" :close-on-escape="!locked" @update:model-value="onVisibility">
    <section class="change-preview">
      <p v-if="loading" role="status">正在只读核对变更和受影响预约，尚未执行写入…</p>
      <SBanner v-if="error" type="error" :title="error" />
      <template v-if="preview && !loading">
        <h4>{{ changeLabels[preview.normalized_proposal.change_type] }} · {{ preview.target_label || '对象ID ' + preview.normalized_proposal.target_id }}</h4>
        <ul class="change-summary"><li v-for="line in changeSummary" :key="line">{{ line }}</li></ul>
        <p>本次公开变更理由：{{ preview.normalized_proposal.payload.reason }}</p>
        <details><summary>核对规范变更内容、版本和指纹</summary><pre>{{ JSON.stringify(preview.normalized_proposal, null, 2) }}</pre><pre>{{ JSON.stringify(preview.versions, null, 2) }}</pre><p class="fingerprint">影响指纹：{{ preview.impact_hash }}</p></details>
        <p class="muted">预览时刻：{{ preview.server_now }}（{{ preview.campus_zone }}）。操作ID：{{ operationId }}</p>
        <SBanner type="warning" title="确认将立即取消整条受影响预约，使用中按确认时刻结算。即使临停安排在未来，也会立即结束受影响的当前使用；如需继续使用，请稍后执行。" />
        <p v-if="preview.summary.warning">{{ preview.summary.warning }}</p>
        <p>待签到{{ preview.pending.length }}条，使用中{{ preview.in_use.length }}条。不新增用户违规或信用奖惩；恢复资源不会复活已取消预约。</p>
        <div v-for="group in [{label:'待签到，将立即管理取消',rows:preview.pending},{label:'使用中，将立即终止并结算，需现场协调',rows:preview.in_use}]" :key="group.label">
          <h4>{{ group.label }}</h4><p v-if="!group.rows.length" class="muted">本次无该类受影响预约。</p>
          <div v-else class="table-wrap"><div class="table-scroll" tabindex="0" :aria-label="group.label"><table class="table impact-table"><thead><tr><th>预约 / 使用者</th><th>座位</th><th>日期 / 原时段</th><th>原状态 / 已登记签到</th></tr></thead><tbody><tr v-for="row in group.rows" :key="row.reservation_id"><td>#{{ row.reservation_id }} · {{ row.real_name || `用户${row.user_id}` }}</td><td>{{ row.seat_no }}</td><td>{{ row.date }} {{ row.start_time }}–{{ row.end_time }}</td><td>{{ row.status === 'checked_in' ? '使用中' : '待签到' }}<div>{{ row.checkin_at || '无使用签到记录' }}</div></td></tr></tbody></table></div></div>
        </div>
        <div v-if="preview.in_use.length" class="coordination">
          <label><input v-model="coordinationConfirmed" type="checkbox" :disabled="locked || stale" /> 我已现场协调，并确认立即终止上述使用中预约（管理员自报）</label>
          <label>实际协调依据（私有自报记录，不作为公开资源原因）<SInput v-model="coordinationNote" type="textarea" maxlength="1000" :disabled="locked || stale" placeholder="填写实际现场协调情况；工程模拟请明确写明工程模拟，不冒充真实现场核实" /></label>
          <p class="muted">系统只记录管理员自报，不证明已经实际腾退或恢复使用。请勿填写不必要的个人信息。</p>
        </div>
        <p v-if="stale" role="status">这是已失效的预览，不能用于确认。</p>
      </template>
    </section>
    <template #footer>
      <div class="change-actions">
      <SButton :disabled="locked" @click="cancel">取消，保留原表单</SButton>
      <SButton v-if="!uncertain" :loading="loading" :disabled="submitting" @click="loadPreview">重新预览</SButton>
      <SButton variant="soft-danger" :loading="submitting" :disabled="loading || !preview || stale" @click="confirm">{{ uncertain ? '以原操作ID重试确认' : `确认立即变更${preview ? '并取消' + (preview.pending.length + preview.in_use.length) + '条预约' : ''}` }}</SButton>
      </div>
    </template>
  </SDialog>
</template>
<style scoped>
.change-actions{display:flex;flex-wrap:wrap;justify-content:flex-end;gap:10px;width:100%;min-width:0}.change-actions :deep(.s-btn){max-width:100%;height:auto;min-height:34px}.change-actions :deep(.s-btn__content){white-space:normal}.change-preview{display:grid;gap:12px;min-width:0}.change-summary{padding-left:20px;line-height:1.8;overflow-wrap:anywhere}.change-preview pre{white-space:pre-wrap;overflow-wrap:anywhere;font-size:var(--fs-caption)}.fingerprint{overflow-wrap:anywhere}.impact-table{min-width:650px}.coordination{display:grid;gap:10px}.coordination label{display:block;overflow-wrap:anywhere}.coordination label:has(textarea){display:grid;gap:8px}
</style>
