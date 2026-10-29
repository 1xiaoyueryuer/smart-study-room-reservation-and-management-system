<script setup>
import { computed, onMounted, ref } from 'vue'
import { getRoomSchedule, saveRoomSchedule, deleteDateException, listUnavailability, createUnavailability, updateUnavailability, cancelUnavailability, listRoomSeats } from '../../api/admin'
import { operationReason } from './userForm.js'
import SButton from '../ui/SButton.vue'
import SInput from '../ui/SInput.vue'
import SSelect from '../ui/SSelect.vue'
import SBanner from '../ui/SBanner.vue'

const props = defineProps({ roomId: { type: Number, required: true } })
const emit = defineEmits(['changed'])
const loading = ref(false), error = ref(''), formError = ref(''), success = ref(''), saving = ref(false)
const version = ref(null), weekly = ref([]), exceptions = ref([]), blocks = ref([]), seats = ref([])
const mode = ref('weekday'), weekday = ref(1), date = ref(''), windows = ref([]), scheduleReason = ref('')
const blockId = ref(null), blockForm = ref({ seat_id: null, start_at: '', end_at: '', reason: '' })
const cancellation = ref(null), cancelReason = ref('')
const weekdays = Array.from({ length: 7 }, (_, index) => ({ value: index + 1, label: `星期${['一','二','三','四','五','六','日'][index]}` }))
const scopes = computed(() => [{ value: null, label: '整个空间' }, ...seats.value.map(seat => ({ value: seat.id, label: `座位 ${seat.seat_no}` }))])
const existingException = computed(() => exceptions.value.some(item => item.date === date.value))
function failureText(failure) { return failure.status === 409 ? `${failure.message}。输入保留，请重新加载版本并核对影响后再提交。` : failure.status === 403 ? `无管理权限：${failure.message}` : failure.message }
async function loadAll() {
  loading.value = true
  error.value = ''
  try {
    const [schedule, unavailability, resources] = await Promise.all([getRoomSchedule(props.roomId), listUnavailability(props.roomId), listRoomSeats(props.roomId)])
    if (new Set([schedule.data.room_version, unavailability.data.room_version, resources.data.room.version]).size !== 1) throw new Error('读取期间资源版本已变化，请重新加载核对')
    version.value = resources.data.room.version
    weekly.value = schedule.data.weekly
    exceptions.value = schedule.data.exceptions
    blocks.value = unavailability.data.items
    seats.value = resources.data.seats
  } catch (failure) { error.value = failureText(failure) }
  finally { loading.value = false }
}
function loadSelection() {
  const selected = mode.value === 'weekday' ? weekly.value.find(item => item.weekday === weekday.value) : exceptions.value.find(item => item.date === date.value)
  windows.value = (selected?.windows || []).map(window => ({ ...window }))
  scheduleReason.value = ''
  formError.value = ''
}
function addWindow() { windows.value.push({ start: '08:00', end: '22:00' }) }
async function afterWrite() {
  await loadAll()
  emit('changed')
}
async function saveSchedule(remove = false) {
  if (saving.value) return
  formError.value = ''; success.value = ''
  let reason
  try { reason = operationReason(scheduleReason.value) } catch (failure) { formError.value = failure.message; return }
  if (mode.value === 'date' && !date.value) { formError.value = '请选择日期例外的日期'; return }
  saving.value = true
  try {
    const target = mode.value === 'weekday' ? { weekday: weekday.value } : { date: date.value }
    if (remove) await deleteDateException(props.roomId, { date: date.value, version: version.value, reason })
    else await saveRoomSchedule(props.roomId, { ...target, windows: windows.value.map(window => ({ start: window.start, end: window.end })), version: version.value, reason })
    success.value = remove ? '日期例外已删除，恢复星期安排' : '开放安排已保存'
    await afterWrite()
    if (!error.value) loadSelection()
  } catch (failure) { formError.value = failureText(failure) }
  finally { saving.value = false }
}
function editBlock(block = null) {
  if (saving.value) return
  blockId.value = block?.id ?? null
  blockForm.value = block ? { seat_id: block.seat_id, start_at: block.start_at.slice(0,16), end_at: block.end_at.slice(0,16), reason: '' } : { seat_id: null, start_at: '', end_at: '', reason: '' }
  formError.value = ''; success.value = ''
}
async function saveBlock() {
  if (saving.value) return
  formError.value = ''; success.value = ''
  let reason
  try { reason = operationReason(blockForm.value.reason) } catch (failure) { formError.value = failure.message; return }
  if (!blockForm.value.start_at || !blockForm.value.end_at) { formError.value = '请填写停用起止时间'; return }
  saving.value = true
  try {
    const body = { ...blockForm.value, version: version.value, reason }
    if (blockId.value === null) await createUnavailability(props.roomId, body)
    else await updateUnavailability(props.roomId, blockId.value, body)
    success.value = '停用区间已保存'
    await afterWrite()
    if (!error.value) { blockId.value = null; blockForm.value = { seat_id: null, start_at: '', end_at: '', reason: '' } }
  } catch (failure) { formError.value = failureText(failure) }
  finally { saving.value = false }
}
function openCancellation(block) { cancellation.value = block; cancelReason.value = ''; formError.value = ''; success.value = '' }
async function cancelBlock() {
  if (saving.value || !cancellation.value) return
  formError.value = ''; success.value = ''
  let reason
  try { reason = operationReason(cancelReason.value) } catch (failure) { formError.value = failure.message; return }
  saving.value = true
  try {
    await cancelUnavailability(props.roomId, cancellation.value.id, { version: version.value, reason })
    cancellation.value = null
    success.value = '该停用记录已撤销，其他重叠停用记录保持生效'
    await afterWrite()
  } catch (failure) { formError.value = failureText(failure) }
  finally { saving.value = false }
}
onMounted(async () => { await loadAll(); if (!error.value) loadSelection() })
</script>
<template>
  <section class="schedule-management">
    <p class="muted">统一使用校园时区 Asia/Shanghai。开放窗口不扣预约，不代表连续空档；有影响的变更会拒绝，不自动取消预约。版本：{{ version ?? '—' }}。</p>
    <SButton size="sm" :loading="loading" :disabled="saving" @click="loadAll">重新加载安排和版本</SButton>
    <p v-if="loading" role="status">正在读取开放安排及停用记录…</p><SBanner v-if="error" type="error" :title="error" />
    <SBanner v-if="formError" type="error" :title="formError" /><SBanner v-if="success" type="success" :title="success" />
    <div v-if="!loading && !error" class="schedule-content">
      <h3>每周安排 / 日期例外</h3>
      <form class="schedule-form" @submit.prevent="saveSchedule(false)">
        <label>安排类型<SSelect v-model="mode" :disabled="saving" :options="[{value:'weekday',label:'每周安排'},{value:'date',label:'日期例外'}]" @change="loadSelection" /></label>
        <label v-if="mode==='weekday'">ISO星期<SSelect v-model="weekday" :options="weekdays" :disabled="saving" @change="loadSelection" /></label>
        <label v-else>例外日期<SInput v-model="date" type="date" required :disabled="saving" @change="loadSelection" /></label>
        <p class="muted full-width">日期例外覆盖整天星期安排；保留空窗口即明确闭馆。删除例外才恢复星期安排。相邻窗口由后端合并，重叠会拒绝。</p>
        <div v-for="(window,index) in windows" :key="index" class="window-row full-width"><label>开始<SInput v-model="window.start" type="time" required :disabled="saving" /></label><label>结束（可填24:00）<SInput v-model="window.end" required maxlength="5" pattern="([01][0-9]|2[0-3]):[0-5][0-9]|24:00" placeholder="22:00或24:00" :disabled="saving" /></label><SButton size="sm" :disabled="saving" @click="windows.splice(index,1)">移除时段</SButton></div>
        <p v-if="!windows.length" class="muted full-width">当前为空窗口：保存后该星期/日期闭馆。</p>
        <SButton :disabled="saving" @click="addWindow">添加开放时段</SButton>
        <label class="full-width">安排变更理由（公开，不含个人信息）<SInput v-model="scheduleReason" type="textarea" required maxlength="1000" :disabled="saving" /></label>
        <div class="row-actions full-width"><SButton type="submit" variant="primary" :loading="saving">保存开放安排</SButton><SButton v-if="mode==='date'" :disabled="saving || !existingException" @click="saveSchedule(true)">删除日期例外</SButton></div>
      </form>
      <h4>现有日期例外</h4><p v-if="!exceptions.length" class="muted">尚无日期例外，使用星期安排。</p><ul v-else><li v-for="item in exceptions" :key="item.date">{{ item.date }}：{{ item.windows.length ? item.windows.map(window=>`${window.start}–${window.end}`).join('、') : '闭馆' }} <SButton size="sm" :disabled="saving" @click="mode='date';date=item.date;loadSelection()">编辑例外</SButton></li></ul>
      <hr />
      <h3>临时停用区间</h3><p class="muted">空间或单座位可跨日停用；重叠记录独立保留，撤销其中一条不会自动撤销其他记录。</p>
      <div class="table-wrap"><div class="table-scroll" tabindex="0" aria-label="临时停用记录，可左右滑动"><table class="table block-table"><thead><tr><th>对象</th><th>校园时间起止</th><th>原因</th><th>状态</th><th>操作</th></tr></thead><tbody><tr v-for="block in blocks" :key="block.id"><td>{{ block.seat_id ? `座位 ${seats.find(seat=>seat.id===block.seat_id)?.seat_no || block.seat_id}` : '整个空间' }}</td><td>{{ block.start_at }}<br />{{ block.end_at }}</td><td>{{ block.reason }}<div v-if="block.cancel_reason">撤销理由：{{ block.cancel_reason }}</div></td><td>{{ block.cancelled ? '已撤销，保留历史' : '生效' }}</td><td><div v-if="!block.cancelled" class="row-actions"><SButton size="sm" :disabled="saving" @click="editBlock(block)">编辑停用</SButton><SButton size="sm" :disabled="saving" @click="openCancellation(block)">撤销停用</SButton></div></td></tr></tbody></table><p v-if="!blocks.length" class="muted">暂无临时停用记录。</p></div></div>
      <form v-if="cancellation" class="schedule-form" @submit.prevent="cancelBlock"><label class="full-width">撤销第{{ cancellation.id }}条停用记录的理由<SInput v-model="cancelReason" type="textarea" required maxlength="1000" :disabled="saving" /></label><div class="row-actions"><SButton type="submit" :loading="saving">确认撤销此记录</SButton><SButton :disabled="saving" @click="cancellation=null">保留记录</SButton></div></form>
      <h4>{{ blockId===null ? '新增停用区间' : '编辑停用区间' }}</h4><SButton v-if="blockId!==null" size="sm" :disabled="saving" @click="editBlock()">改为新增</SButton>
      <form class="schedule-form" @submit.prevent="saveBlock"><label class="full-width">停用对象<SSelect v-model="blockForm.seat_id" :options="scopes" :disabled="saving" /></label><label>停用开始（Asia/Shanghai）<SInput v-model="blockForm.start_at" type="datetime-local" required :disabled="saving" /></label><label>停用结束（Asia/Shanghai）<SInput v-model="blockForm.end_at" type="datetime-local" required :disabled="saving" /></label><label class="full-width">停用理由（公开，不含个人信息）<SInput v-model="blockForm.reason" type="textarea" required maxlength="1000" :disabled="saving" /></label><SButton type="submit" variant="primary" :loading="saving">保存停用区间</SButton></form>
    </div>
  </section>
</template>
<style scoped>
.schedule-management { display:grid; gap:14px; }
.schedule-content { display:grid; gap:14px; }
.schedule-form { display:grid; grid-template-columns:repeat(2,minmax(0,1fr)); gap:14px; }
.schedule-form label { display:grid; gap:7px; font-size:var(--fs-caption); color:var(--text-2); }
.full-width { grid-column:1 / -1; }
.window-row { display:flex; gap:12px; align-items:end; }
.window-row label { flex:1; min-width:0; }
.block-table { min-width:750px; }
@media(max-width:560px){ .schedule-form { grid-template-columns:1fr; } .window-row { flex-wrap:wrap; } }
</style>
