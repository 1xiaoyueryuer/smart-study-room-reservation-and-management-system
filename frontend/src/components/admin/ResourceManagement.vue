<script setup>
import { onMounted, ref } from 'vue'
import { getRooms } from '../../api/room'
import { createRoom, updateRoom, deleteRoom, batchGenSeats, listRoomSeats, createSeat, updateSeat, deleteSeat } from '../../api/admin'
import { operationReason } from './userForm.js'
import { message } from '../ui/feedback'
import SButton from '../ui/SButton.vue'
import SInput from '../ui/SInput.vue'
import SInputNumber from '../ui/SInputNumber.vue'
import SSelect from '../ui/SSelect.vue'
import SDialog from '../ui/SDialog.vue'
import SBanner from '../ui/SBanner.vue'
import SEmpty from '../ui/SEmpty.vue'

const emit = defineEmits(['summary'])
const rooms = ref([]), loading = ref(false), error = ref('')
const roomDialog = ref(false), editingRoom = ref(null), roomForm = ref({})
const seatsDialog = ref(false), seatRoom = ref(null), seats = ref([]), seatLoading = ref(false), seatError = ref('')
const seatDialog = ref(false), editingSeat = ref(null), seatForm = ref({})
const batchDialog = ref(false), batchForm = ref({})
const deleteDialog = ref(false), deleteForm = ref({}), deletingSeat = ref(false)
const saving = ref(false), formError = ref('')
const zoneLabels = { quiet: '静音区', regular: '普通区', discussion: '研讨区', computer: '机房区' }
const stateLabels = { available: '可用', maintenance: '维护中', disabled: '停用' }
const zones = Object.entries(zoneLabels).map(([value, label]) => ({ value, label }))
const states = Object.entries(stateLabels).map(([value, label]) => ({ value, label }))
let loadSequence = 0
let seatSequence = 0
function failureText(failure) {
  return failure.status === 409 ? `${failure.message}。当前输入已保留；若版本过期，请关闭表单、刷新资源后重新核对编辑。` : failure.status === 403 ? `无资源管理权限：${failure.message}` : failure.message
}
async function loadRooms() {
  const sequence = ++loadSequence
  loading.value = true
  error.value = ''
  try {
    const response = await getRooms()
    if (sequence !== loadSequence) return
    rooms.value = response.data
    emit('summary', { total: rooms.value.length, seats: rooms.value.reduce((sum, room) => sum + room.seat_count, 0), capacity: rooms.value.reduce((sum, room) => sum + room.bookable_capacity, 0) })
  } catch (failure) {
    if (sequence !== loadSequence) return
    error.value = failureText(failure)
    emit('summary', null)
  } finally { if (sequence === loadSequence) loading.value = false }
}
function openCreate() {
  if (saving.value) return
  editingRoom.value = null
  roomForm.value = { name: '', building: '', floor: '', location: '', enabled: true, open_time: '08:00', close_time: '22:00', seat_rows: 1, seat_cols: 1, description: '', reason: '' }
  formError.value = ''
  roomDialog.value = true
}
function openEdit(room) {
  if (saving.value) return
  editingRoom.value = room.id
  roomForm.value = { ...room, building: room.building || '', floor: room.floor || '', location: room.location || '', description: room.description || '', open_time: room.open_time.slice(0, 5), close_time: room.close_time.slice(0, 5), reason: '' }
  formError.value = ''
  roomDialog.value = true
}
async function saveRoom() {
  if (saving.value) return
  formError.value = ''
  let reason
  try { reason = operationReason(roomForm.value.reason) } catch (failure) { formError.value = failure.message; return }
  const { name, building, floor, location, enabled, open_time, close_time, seat_rows, seat_cols, description, version } = roomForm.value
  if (!name.trim()) { formError.value = '请填写空间名称'; return }
  saving.value = true
  try {
    const body = { name: name.trim(), building, floor, location, enabled, open_time, close_time, seat_rows, seat_cols, description, version, reason }
    if (editingRoom.value === null) await createRoom(body)
    else await updateRoom(editingRoom.value, body)
    roomDialog.value = false
    message.success('空间已保存')
    await loadRooms()
  } catch (failure) { formError.value = failureText(failure) }
  finally { saving.value = false }
}
async function loadSeats() {
  if (!seatRoom.value) return
  const sequence = ++seatSequence
  seatLoading.value = true
  seatError.value = ''
  try {
    const response = await listRoomSeats(seatRoom.value.id)
    if (sequence !== seatSequence) return
    seatRoom.value = response.data.room
    seats.value = response.data.seats
  } catch (failure) { if (sequence === seatSequence) seatError.value = failureText(failure) }
  finally { if (sequence === seatSequence) seatLoading.value = false }
}
async function openSeats(room) {
  seatRoom.value = room
  seats.value = []
  seatsDialog.value = true
  await loadSeats()
}
function editSeat(seat = null) {
  if (saving.value || seatLoading.value) return
  editingSeat.value = seat?.id ?? null
  seatForm.value = seat ? { ...seat, reason: '' } : { seat_no: '', row_no: 1, col_no: 1, zone: 'regular', has_power: false, near_window: false, status: 'available', version: seatRoom.value.version, reason: '' }
  formError.value = ''
  seatDialog.value = true
}
async function saveSeat() {
  if (saving.value) return
  formError.value = ''
  let reason
  try { reason = operationReason(seatForm.value.reason) } catch (failure) { formError.value = failure.message; return }
  const { seat_no, row_no, col_no, zone, has_power, near_window, status, version } = seatForm.value
  if (!seat_no.trim()) { formError.value = '请填写座位编号'; return }
  saving.value = true
  try {
    const body = { seat_no: seat_no.trim(), row_no, col_no, zone, has_power, near_window, status, version, reason }
    if (editingSeat.value === null) await createSeat(seatRoom.value.id, body)
    else await updateSeat(editingSeat.value, body)
    seatDialog.value = false
    message.success('座位已保存')
    await Promise.all([loadRooms(), loadSeats()])
  } catch (failure) { formError.value = failureText(failure) }
  finally { saving.value = false }
}
function openBatch(room) {
  if (saving.value) return
  batchForm.value = { room_id: room.id, seat_rows: room.seat_rows, seat_cols: room.seat_cols, version: room.version, reason: '' }
  formError.value = ''
  batchDialog.value = true
}
async function saveBatch() {
  if (saving.value) return
  formError.value = ''
  let reason
  try { reason = operationReason(batchForm.value.reason) } catch (failure) { formError.value = failure.message; return }
  saving.value = true
  try {
    const { room_id, seat_rows, seat_cols, version } = batchForm.value
    const response = await batchGenSeats(room_id, { seat_rows, seat_cols, version, reason })
    batchDialog.value = false
    message.success(`已生成${response.data.created}个座位`)
    await loadRooms()
    if (seatRoom.value?.id === room_id) await loadSeats()
  } catch (failure) { formError.value = failureText(failure) }
  finally { saving.value = false }
}
function removeResource(target, isSeat = false) {
  if (saving.value) return
  deleteForm.value = { id: target.id, version: target.version, label: target.seat_no || target.name, reason: '' }
  deletingSeat.value = isSeat
  formError.value = ''
  deleteDialog.value = true
}
async function saveDelete() {
  if (saving.value) return
  formError.value = ''
  let reason
  try { reason = operationReason(deleteForm.value.reason) } catch (failure) { formError.value = failure.message; return }
  saving.value = true
  try {
    const target = deleteForm.value
    if (deletingSeat.value) await deleteSeat(target.id, { version: target.version, reason })
    else await deleteRoom(target.id, { version: target.version, reason })
    deleteDialog.value = false
    message.success('资源已删除')
    await loadRooms()
    if (deletingSeat.value) await loadSeats()
  } catch (failure) { formError.value = failureText(failure) }
  finally { saving.value = false }
}
onMounted(loadRooms)
defineExpose({ loadRooms, openCreate })
</script>

<template>
  <section class="card">
    <div class="card-title-row"><h3>自习室管理</h3><div class="row-actions"><SButton size="sm" :loading="loading" @click="loadRooms">刷新资源</SButton><SButton size="sm" variant="primary" @click="openCreate">新建自习室</SButton></div></div>
    <p v-if="loading" role="status">正在加载空间…</p>
    <SBanner v-if="error" type="error" :title="error"><SButton size="sm" @click="loadRooms">重试</SButton></SBanner>
    <div v-if="!loading && !error" class="table-wrap"><div class="table-scroll" tabindex="0" aria-label="资源管理列表，可左右滑动"><table class="table resource-table"><thead><tr><th>空间 / 位置</th><th>开放时间</th><th>真实座位 / 有效容量 / 可约容量</th><th>布局范围</th><th>状态 / 原因</th><th>版本</th><th>操作</th></tr></thead><tbody><tr v-for="room in rooms" :key="room.id"><td><b>{{ room.name }}</b><div>{{ room.building || '楼栋未登记' }} · {{ room.floor || '楼层未登记' }}</div><div>{{ room.location || '详细位置未登记' }}</div></td><td>{{ room.open_time.slice(0,5) }}–{{ room.close_time.slice(0,5) }}</td><td>{{ room.seat_count }} / {{ room.capacity }} / {{ room.bookable_capacity }}</td><td>{{ room.seat_rows }}行 × {{ room.seat_cols }}列</td><td>{{ room.enabled ? '启用' : '停用' }}<div>{{ room.resource_reason || '—' }}</div></td><td>{{ room.version }}</td><td><div class="row-actions"><SButton size="sm" @click="openEdit(room)">编辑空间</SButton><SButton size="sm" @click="openSeats(room)">座位维护</SButton><SButton v-if="room.seat_count === 0" size="sm" @click="openBatch(room)">生成座位</SButton><SButton size="sm" :disabled="saving" @click="removeResource(room)">删除空间</SButton></div></td></tr></tbody></table><SEmpty v-if="!rooms.length" description="暂无空间，可新建自习室" /></div></div>

    <SDialog v-model="roomDialog" :title="editingRoom === null ? '新建自习室' : '编辑自习室'" width="680px" :show-close="!saving" :close-on-click-modal="!saving">
      <form id="room-editor" class="resource-form" @submit.prevent="saveRoom"><p v-if="formError" role="alert" class="form-error">{{ formError }}</p>
        <label>空间名称<SInput v-model="roomForm.name" required maxlength="100" /></label><label>楼栋<SInput v-model="roomForm.building" maxlength="100" placeholder="未知可留空" /></label><label>楼层<SInput v-model="roomForm.floor" maxlength="30" placeholder="如B1，未知可留空" /></label><label>详细位置<SInput v-model="roomForm.location" maxlength="200" /></label>
        <label>开放时间<SInput v-model="roomForm.open_time" type="time" required /></label><label>关闭时间<SInput v-model="roomForm.close_time" type="time" required /></label><label>布局行数<SInputNumber v-model="roomForm.seat_rows" :min="1" :max="30" /></label><label>布局列数<SInputNumber v-model="roomForm.seat_cols" :min="1" :max="30" /></label>
        <label><span><input v-model="roomForm.enabled" type="checkbox" /> 启用空间</span></label><label>说明<SInput v-model="roomForm.description" type="textarea" maxlength="5000" /></label>
        <label class="full-width">资源原因（将展示给普通用户，请勿填写个人信息）<SInput v-model="roomForm.reason" type="textarea" required maxlength="1000" /></label><p class="muted full-width">容量由实际有效座位计算，布局行列不是座位数量。有有效预约的影响性变更会被拒绝，不会自动取消预约。</p>
      </form><template #footer><SButton :disabled="saving" @click="roomDialog=false">取消</SButton><SButton type="submit" form="room-editor" variant="primary" :loading="saving">保存空间</SButton></template>
    </SDialog>

    <SDialog v-model="seatsDialog" :title="`座位维护 - ${seatRoom?.name || ''}`" width="1000px">
      <p class="muted">{{ seatRoom?.building || '楼栋未登记' }} · {{ seatRoom?.floor || '楼层未登记' }} · {{ seatRoom?.location || '位置未登记' }}。实际座位{{ seatRoom?.seat_count }}，有效容量{{ seatRoom?.capacity }}；布局{{ seatRoom?.seat_rows }}行×{{ seatRoom?.seat_cols }}列。</p>
      <div class="row-actions"><SButton size="sm" :loading="seatLoading" @click="loadSeats">刷新座位</SButton><SButton size="sm" variant="primary" :disabled="seatLoading || !!seatError" @click="editSeat()">新增单座位</SButton></div>
      <p v-if="seatLoading" role="status">正在加载全部座位…</p><SBanner v-if="seatError" type="error" :title="seatError"><SButton size="sm" @click="loadSeats">重试</SButton></SBanner>
      <div v-if="!seatLoading && !seatError" class="table-wrap"><div class="table-scroll" tabindex="0" aria-label="管理座位列表，可左右滑动"><table class="table resource-table"><thead><tr><th>编号</th><th>行 / 列</th><th>区域</th><th>电源 / 靠窗</th><th>状态 / 原因</th><th>版本</th><th>操作</th></tr></thead><tbody><tr v-for="seat in seats" :key="seat.id"><td>{{ seat.seat_no }}</td><td>{{ seat.row_no }} / {{ seat.col_no }}</td><td>{{ zoneLabels[seat.zone] }}</td><td>{{ seat.has_power ? '有电源' : '无电源' }} / {{ seat.near_window ? '靠窗' : '非靠窗' }}</td><td>{{ stateLabels[seat.status] }}<div>{{ seat.resource_reason || '—' }}</div></td><td>{{ seat.version }}</td><td><div class="row-actions"><SButton size="sm" @click="editSeat(seat)">编辑座位</SButton><SButton size="sm" :disabled="saving" @click="removeResource(seat,true)">删除座位</SButton></div></td></tr></tbody></table><SEmpty v-if="!seats.length" description="尚无座位，可新增单座位或在空房批量生成" /></div></div>
    </SDialog>

    <SDialog v-model="seatDialog" :title="editingSeat === null ? '新增单座位' : '编辑座位'" width="640px" :show-close="!saving" :close-on-click-modal="!saving"><form id="seat-editor" class="resource-form" @submit.prevent="saveSeat"><p v-if="formError" role="alert" class="form-error">{{ formError }}</p><label>座位编号<SInput v-model="seatForm.seat_no" required maxlength="20" /></label><label>区域<SSelect v-model="seatForm.zone" :options="zones" /></label><label>行号<SInputNumber v-model="seatForm.row_no" :min="1" :max="seatRoom?.seat_rows || 30" /></label><label>列号<SInputNumber v-model="seatForm.col_no" :min="1" :max="seatRoom?.seat_cols || 30" /></label><label><span><input v-model="seatForm.has_power" type="checkbox" /> 有电源</span></label><label><span><input v-model="seatForm.near_window" type="checkbox" /> 靠窗</span></label><label>座位状态<SSelect v-model="seatForm.status" :options="states" /></label><label class="full-width">资源原因（公开，请勿填写个人信息）<SInput v-model="seatForm.reason" type="textarea" required maxlength="1000" /></label></form><template #footer><SButton :disabled="saving" @click="seatDialog=false">取消</SButton><SButton type="submit" form="seat-editor" variant="primary" :loading="saving">保存座位</SButton></template></SDialog>

    <SDialog v-model="batchDialog" title="空房批量生成座位" width="480px" :show-close="!saving"><form id="batch-editor" class="resource-form" @submit.prevent="saveBatch"><p v-if="formError" role="alert" class="form-error">{{ formError }}</p><label>行数<SInputNumber v-model="batchForm.seat_rows" :min="1" :max="30" /></label><label>列数<SInputNumber v-model="batchForm.seat_cols" :min="1" :max="30" /></label><label class="full-width">资源原因（公开，请勿填写个人信息）<SInput v-model="batchForm.reason" type="textarea" required maxlength="1000" /></label><p class="muted full-width">仅空房可生成，编号与布局位置均由后端校验；不覆盖现有座位。</p></form><template #footer><SButton :disabled="saving" @click="batchDialog=false">取消</SButton><SButton type="submit" form="batch-editor" variant="primary" :loading="saving">确认生成</SButton></template></SDialog>
    <SDialog v-model="deleteDialog" title="删除资源" width="480px" :show-close="!saving" :close-on-click-modal="!saving"><form id="resource-delete" class="resource-form" @submit.prevent="saveDelete"><p class="full-width">确认删除「{{ deleteForm.label }}」？有历史记录的资源禁止删除，失败不会清理历史或取消预约。</p><p v-if="formError" role="alert" class="form-error">{{ formError }}</p><label class="full-width">删除操作理由<SInput v-model="deleteForm.reason" type="textarea" required maxlength="1000" /></label></form><template #footer><SButton :disabled="saving" @click="deleteDialog=false">取消</SButton><SButton type="submit" form="resource-delete" variant="soft-danger" :loading="saving">确认删除资源</SButton></template></SDialog>
  </section>
</template>
<style scoped>
.resource-table { min-width: 920px; }
.resource-form { display: grid; grid-template-columns: repeat(2,minmax(0,1fr)); gap: 14px; }
.resource-form label { display: grid; gap: 7px; color: var(--text-2); font-size: var(--fs-caption); }
.full-width,.form-error { grid-column: 1 / -1; }
.form-error { color: var(--red-strong); }
@media(max-width:560px){ .resource-form { grid-template-columns:1fr; } }
</style>
