<script setup>
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { getSeatCode, getSeatCodePng, createSeatCode, rotateSeatCode, updateSeatCode } from '../../api/admin'
import { operationReason } from './userForm.js'
import SButton from '../ui/SButton.vue'
import SInput from '../ui/SInput.vue'
import SBanner from '../ui/SBanner.vue'
const props = defineProps({ seat: { type: Object, required: true }, roomName: { type: String, default: '' } })
const emit = defineEmits(['busy'])
const metadata = ref(null), loading = ref(false), saving = ref(false), error = ref(''), pngError = ref(''), imageUrl = ref(''), imageReady = ref(false), reason = ref(''), notice = ref(''), formError = ref('')
let sequence = 0, imageSequence = 0, disposed = false
function releaseImage() { imageReady.value = false; if (imageUrl.value) URL.revokeObjectURL(imageUrl.value); imageUrl.value = '' }
async function loadImage() {
  const ticket = ++imageSequence
  pngError.value = ''; releaseImage()
  try {
    const png = await getSeatCodePng(props.seat.id, metadata.value.version)
    if (!disposed && ticket === imageSequence) imageUrl.value = URL.createObjectURL(png)
  } catch (failure) { if (!disposed && ticket === imageSequence) pngError.value = failure.status === 409 ? `${failure.message}。图片与元数据版本不一致，请刷新座位码后重新核对；当前图片不可打印。` : failure.message }
}
async function load() {
  const ticket = ++sequence
  loading.value = true; imageSequence++; error.value = ''; releaseImage()
  try {
    const response = await getSeatCode(props.seat.id)
    if (ticket !== sequence || disposed) return
    metadata.value = response.data
    if (metadata.value.configured) await loadImage()
  } catch (failure) { if (ticket === sequence && !disposed) error.value = failure.message }
  finally { if (ticket === sequence && !disposed) loading.value = false }
}
async function mutate(action) {
  if (saving.value || loading.value || error.value || !metadata.value) return
  let text
  formError.value = ''
  try { text = operationReason(reason.value) } catch (failure) { formError.value = failure.message; return }
  saving.value = true; emit('busy', true); error.value = ''; notice.value = ''
  try {
    const body = { version: metadata.value.version, reason: text }
    if (action === 'create') await createSeatCode(props.seat.id, body)
    else if (action === 'rotate') await rotateSeatCode(props.seat.id, body)
    else await updateSeatCode(props.seat.id, { ...body, enabled: !metadata.value.enabled })
    reason.value = ''; notice.value = '座位码写入已成功；请以下方读取结果核对当前状态。'
    await load()
  } catch (failure) { error.value = failure.status === 409 ? `${failure.message}。请刷新当前码状态后核对重试，理由已保留。` : failure.message }
  finally { saving.value = false; emit('busy', false) }
}
function printCode() {
  if (!imageReady.value || !imageUrl.value || saving.value || loading.value) return
  const popup = window.open('', '_blank')
  if (!popup) { pngError.value = '打印窗口被阻止，请允许弹出窗口后重试。'; return }
  // 仅安全DOM写入文字，不把空间/座位名称拼成HTML。
  const title = popup.document.createElement('h1'); title.textContent = `${props.roomName} · ${props.seat.seat_no}`
  const hint = popup.document.createElement('p'); hint.textContent = `座位码管理版本 ${metadata.value.version} · ${metadata.value.enabled ? '启用' : '停用'}`
  const image = popup.document.createElement('img'); image.alt = '座位二维码'; image.style.width = '320px'; image.style.maxWidth = '100%'
  image.onload = () => { popup.focus(); popup.print() }
  image.onerror = () => { pngError.value = '打印图片加载失败，请重试。'; popup.close() }
  popup.document.title = '座位码打印'
  popup.document.body.append(title, hint, image)
  image.src = imageUrl.value
}
onMounted(load)
onBeforeUnmount(() => { disposed = true; sequence++; imageSequence++; releaseImage() })
</script>
<template>
  <section class="seat-code-panel">
    <div class="card-title-row"><h3>{{ roomName }} · {{ seat.seat_no }}</h3><SButton size="sm" :loading="loading" :disabled="saving" @click="load">刷新座位码</SButton></div>
    <p v-if="loading" role="status">正在读取座位码…</p>
    <SBanner v-if="error" type="error" :title="error"><SButton size="sm" :disabled="saving || loading" @click="load">重新读取状态</SButton></SBanner>
    <SBanner v-if="notice" type="success" :title="notice" />
    <template v-if="metadata && !loading">
      <p v-if="!metadata.configured">尚未生成座位码。首次生成后启用。</p>
      <template v-else><p>管理版本{{ metadata.version }} · {{ metadata.enabled ? '启用' : '停用（不能签到）' }}</p><p class="muted">创建：{{ metadata.created_at || '—' }}；更新：{{ metadata.updated_at || '—' }}</p><img v-if="imageUrl" :src="imageUrl" class="seat-code-image" alt="当前座位二维码" @load="imageReady=true" @error="imageReady=false;pngError='座位码图片无法显示，请重新读取'" /><p v-if="imageUrl && !imageReady" role="status">等待二维码图片加载…</p><SBanner v-if="pngError" type="error" :title="pngError"><SButton size="sm" :disabled="saving" @click="load">刷新码状态与图片</SButton></SBanner><SButton :disabled="!imageReady || saving || !!error" @click="printCode">打印当前座位码</SButton></template>
      <p class="muted">轮换会立即使旧码失效；停用后当前码不能签到，重新启用沿用当前二维码内容，轮换前旧码不会恢复。请妥善保管打印件。</p>
      <p v-if="formError" role="alert">{{ formError }}</p><label class="reason-field">操作理由<SInput v-model="reason" type="textarea" maxlength="1000" :disabled="saving" placeholder="首次生成、轮换或启停均需填写理由" /></label>
      <div class="row-actions"><SButton v-if="!metadata.configured" variant="primary" :loading="saving" :disabled="!!error" @click="mutate('create')">首次生成</SButton><template v-else><SButton :loading="saving" :disabled="!!error" @click="mutate('rotate')">轮换座位码</SButton><SButton :loading="saving" :disabled="!!error" @click="mutate('toggle')">{{ metadata.enabled ? '停用座位码' : '启用座位码' }}</SButton></template></div>
    </template>
  </section>
</template>
<style scoped>
.seat-code-panel{display:grid;gap:14px;min-width:0}.seat-code-image{width:280px;max-width:100%;height:auto;justify-self:center}.reason-field{display:grid;gap:8px}.row-actions{display:flex;gap:10px;flex-wrap:wrap}
</style>
