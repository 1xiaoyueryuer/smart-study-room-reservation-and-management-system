import { ref, watch, onMounted, onBeforeUnmount } from 'vue'

const offsetTime = value => typeof value === 'string' && /(?:Z|[+-]\d{2}:\d{2})$/.test(value) ? Date.parse(value) : NaN
export const targetFingerprint = record => {
  const target = record?.service_target
  return target ? JSON.stringify([record.id, target.kind, target.due_at, target.room_version, target.calculated_at]) : null
}
export function targetProjection(record, elapsedSeconds = 0) {
  const target = record?.service_target
  if (!target) return null
  const elapsed = Math.max(0, elapsedSeconds)
  const remaining = target.status === 'running' && Number.isFinite(target.remaining_seconds)
    ? Math.max(0, target.remaining_seconds - elapsed) : null
  const server = offsetTime(record.server_now), due = offsetTime(target.due_at)
  return {
    remaining_seconds: remaining,
    awaiting_check: target.status === 'running' && remaining === 0,
    overdue_seconds: target.status === 'overdue' && Number.isFinite(server) && Number.isFinite(due)
      ? Math.max(0, (server - due) / 1000 + elapsed) : null,
  }
}
export function formatTargetDuration(seconds) {
  if (!Number.isFinite(seconds)) return '未提供'
  const total = Math.ceil(Math.max(0, seconds)), days = Math.floor(total / 86400)
  const hours = Math.floor(total % 86400 / 3600), minutes = Math.floor(total % 3600 / 60), rest = total % 60
  return `${days ? days + '天' : ''}${hours ? hours + '小时' : ''}${minutes ? minutes + '分' : ''}${rest}秒`
}
// One timer per page. Only elapsed monotonic time advances the server snapshot.
export function useServiceTargetClock({ records, blocked, onDue, monotonic = () => performance.now(), schedule = setInterval, cancel = clearInterval }) {
  const tick = ref(0), anchors = new WeakMap(), requested = new Set()
  let timer, mounted = false, alive = true, refreshing = false
  function anchor(record) {
    if (!anchors.has(record)) anchors.set(record, monotonic())
    return anchors.get(record)
  }
  function progress(record) {
    tick.value
    return targetProjection(record, (monotonic() - anchor(record)) / 1000)
  }
  async function checkDue() {
    if (!alive || blocked.value || refreshing) return
    const due = records.value.filter(record => record && progress(record)?.awaiting_check && !requested.has(targetFingerprint(record)))
    if (!due.length) return
    due.forEach(record => requested.add(targetFingerprint(record)))
    refreshing = true
    try { await onDue(due) } catch { /* The owning page retains its explicit read error and retry action. */ }
    finally { refreshing = false }
  }
  function syncTimer() {
    const active = records.value.some(record => ['running', 'overdue'].includes(record?.service_target?.status))
    if (mounted && active && timer === undefined) timer = schedule(() => { tick.value++; void checkDue() }, 1000)
    if (!active && timer !== undefined) { cancel(timer); timer = undefined }
  }
  const stop = watch([records, blocked], () => {
    records.value.filter(Boolean).forEach(anchor)
    syncTimer()
    void checkDue()
  }, { immediate: true })
  onMounted(() => { mounted = true; syncTimer() })
  onBeforeUnmount(() => { alive = false; stop(); if (timer !== undefined) cancel(timer) })
  return { progress }
}
