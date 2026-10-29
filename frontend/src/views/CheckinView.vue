<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { getReservation, checkinReservation } from '../api/reservation'
import { createSeatScanner } from '../composables/seatScanner.js'
import SButton from '../components/ui/SButton.vue'
import SBanner from '../components/ui/SBanner.vue'
import UsageRecordSummary from '../components/UsageRecordSummary.vue'
const props = defineProps({ id: { type: [String, Number], required: true } })
const reservation = ref(null), loading = ref(false), detailError = ref(''), scanError = ref(''), scanNotice = ref(''), uncertainResult = ref(false), accepted = ref(false), success = ref(false), submitting = ref(false), decoding = ref(false), cameraActive = ref(false), video = ref(null)
const canCheckin = computed(() => !loading.value && !detailError.value && reservation.value?.allowed_actions?.includes('checkin'))
let loadSequence = 0, reservationScope = 0, disposed = false
const scanner = createSeatScanner({ onCode: submitCode, onError: text => { scanError.value = text }, onCamera: active => { cameraActive.value = active } })
async function load() {
  if (disposed) return
  const ticket = ++loadSequence, scope = reservationScope, reservationId = props.id
  loading.value = true; detailError.value = ''
  try {
    const result = await getReservation(reservationId)
    if (ticket === loadSequence && scope === reservationScope && !disposed) {
      reservation.value = result.data
      const managedCancellation = reservation.value?.status === 'cancelled' && ['admin_resource_change','admin_restriction'].includes(reservation.value?.termination_reason)
      const cancellationLabel = reservation.value?.termination_reason === 'admin_restriction' ? '预约限制管理' : '资源管理'
      const usageConfirmed = ['checked_in', 'completed'].includes(reservation.value?.status) && !!reservation.value?.usage_record?.checkin_at
      if (uncertainResult.value && (usageConfirmed || managedCancellation)) {
        scanError.value = ''; scanNotice.value = managedCancellation ? `查询确认预约已因${cancellationLabel}取消，请查看当次公开原因及使用记录；无需再次签到。` : reservation.value.status === 'completed' ? '原签到请求未收到确认；查询确认已登记签到并结束使用，无需重试。' : '原签到请求未收到确认；查询确认当前已签到，无需重试。'; uncertainResult.value = false
      }
      if (accepted.value) {
        success.value = usageConfirmed
        scanNotice.value = managedCancellation ? `预约已因${cancellationLabel}取消，请查看当次公开原因及使用记录；当前不能再次签到。` : usageConfirmed ? '' : '签到请求已接收，尚未查得可核对的使用记录，请刷新核对。'
      }
    }
  }
  catch (error) {
    if (ticket === loadSequence && scope === reservationScope && !disposed) {
      detailError.value = error.message
      if (accepted.value && !success.value) scanNotice.value = '签到请求已接收，最新状态和使用记录尚未核对，请重试查询。'
    }
  }
  finally { if (ticket === loadSequence && scope === reservationScope && !disposed) loading.value = false }
}
async function submitCode(code) {
  if (!canCheckin.value || submitting.value || disposed) return
  const reservationId = props.id, scope = reservationScope
  const current = () => !disposed && scope === reservationScope && reservationId === props.id
  let uncertain = false
  submitting.value = true; success.value = false; accepted.value = false; scanError.value = ''; scanNotice.value = ''; uncertainResult.value = false
  try {
    await checkinReservation(reservationId, code)
    if (current()) accepted.value = true
  } catch (error) {
    if (!current()) return
    uncertain = error.status == null; uncertainResult.value = uncertain
    scanError.value = error.details?.termination_reason === 'no_show' ? '已超过签到期限，预约已结束并登记未签到违约；本次未签到。请在我的预约查看最新状态及违规明细。' : uncertain ? '网络中断，签到结果尚未确认。请先刷新预约核对，确认仍可签到后再主动重试。' : error.message
  } finally {
    if (current()) {
      scanner.stop()
      await load()
      if (current()) {
        submitting.value = false
      }
    }
  }
}
async function upload(event) {
  const file = event.target.files?.[0]
  event.target.value = ''
  if (!file || !canCheckin.value || submitting.value || decoding.value) return
  const scope = reservationScope
  decoding.value = true; scanError.value = ''; scanNotice.value = ''; success.value = false
  try { await scanner.file(file) } finally { if (!disposed && scope === reservationScope) decoding.value = false }
}
async function startCamera() {
  if (!canCheckin.value || submitting.value || decoding.value || cameraActive.value) return
  scanError.value = ''; scanNotice.value = ''; success.value = false
  await scanner.camera(video.value)
}
function refresh() { scanner.stop(); return load() }
watch(() => props.id, () => { reservationScope++; uncertainResult.value = false; accepted.value = false; scanner.stop(); submitting.value = false; decoding.value = false; reservation.value = null; scanError.value = ''; scanNotice.value = ''; success.value = false; void load() }, { immediate: true })
onBeforeUnmount(() => { disposed = true; loadSequence++; scanner.stop() })
</script>
<template>
  <div class="page-view checkin-page">
    <header class="view-heading" data-page-title><div><h1>扫码签到</h1><p class="heading-sub">扫描本人预约座位的二维码；签到窗口为开始起至开始后15分钟之前。</p></div></header>
    <section class="card">
      <div class="card-title-row"><h3>预约信息</h3><SButton :disabled="submitting || decoding" :loading="loading" @click="refresh">刷新预约</SButton></div>
      <p v-if="loading" role="status">正在核对本人预约及签到权限…</p>
      <SBanner v-if="detailError" type="error" :title="detailError"><SButton :disabled="submitting || decoding" @click="refresh">重试查询</SButton></SBanner>
      <template v-if="reservation && !loading && !detailError"><p>{{ reservation.room_name }} · {{ reservation.seat_no }} · {{ reservation.res_date }} {{ reservation.start_time }}—{{ reservation.end_time }}</p><p v-if="!canCheckin && !success" role="status">当前预约不可签到（{{ reservation.status === 'unused_ended' ? '未使用结束' : reservation.status === 'checked_in' ? '使用中' : reservation.status === 'completed' ? '已结束' : reservation.status === 'cancelled' ? '已取消' : '请以我的预约当前操作为准' }}）。<RouterLink to="/mine">返回我的预约</RouterLink></p><p v-if="['admin_resource_change','admin_restriction'].includes(reservation.termination_reason)">管理取消（不记用户违约），当次公开原因：{{ reservation.termination_note || '未提供' }}</p><UsageRecordSummary :record="reservation.usage_record" /></template>
      <SBanner v-if="success" type="success" title="签到及使用记录已确认，请查看当前预约状态。"><RouterLink to="/mine">返回我的预约</RouterLink></SBanner>
      <SBanner v-if="scanNotice" type="info" :title="scanNotice" />
      <SBanner v-if="scanError" type="error" :title="scanError" />
      <div class="scan-controls">
        <label class="upload-label">上传座位码图片<input type="file" accept="image/png,image/jpeg,image/webp" :disabled="!canCheckin || submitting || decoding || cameraActive" @change="upload" /></label>
        <p class="muted">支持PNG、JPEG、WebP，最大5MiB、边长不超过4096像素。图片只在本机解码；请勿上传个人证件。</p>
        <p v-if="decoding || submitting" role="status">{{ submitting ? '正在提交签到，请勿重复操作…' : '正在识别图片…' }}</p>
        <div class="row-actions"><SButton variant="primary" :disabled="!canCheckin || submitting || decoding || cameraActive" @click="startCamera">开启摄像头扫码</SButton><SButton v-if="cameraActive" :disabled="submitting" @click="scanner.stop">停止摄像头</SButton><RouterLink to="/mine">返回我的预约</RouterLink></div>
        <p v-if="cameraActive" role="status">请将完整座位二维码对准镜头；识别后将提交签到。</p>
        <video v-show="cameraActive" ref="video" class="scan-video" muted playsinline aria-label="座位码摄像头预览" />
      </div>
    </section>
  </div>
</template>
<style scoped>
.checkin-page{max-width:900px}.scan-controls{display:grid;gap:14px;margin-top:20px}.upload-label{display:grid;gap:8px;max-width:100%;overflow-wrap:anywhere}.upload-label input{max-width:100%;min-width:0}.scan-video{width:100%;max-height:440px;border-radius:var(--r-md);background:#111}.row-actions{display:flex;flex-wrap:wrap;gap:10px;align-items:center}
</style>
