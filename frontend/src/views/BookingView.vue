<script setup>
import SAnimatedNumber from '../components/ui/SAnimatedNumber.vue'
import { ref, computed, onMounted, onBeforeUnmount, watch } from 'vue'
import SeatExplorer from '../components/seat3d/SeatExplorer.vue'
import SeatPlanDesk from '../components/SeatPlanDesk.vue'
import { seatType, zoneName, getWindowSides } from '../components/seat3d/seatPresentation'
import { message, confirmDialog } from '../components/ui/feedback'
import { getRooms } from '../api/room'
import { createReservation, getBookingPolicy } from '../api/reservation'
import { getAvailability } from '../api/availability'
import { windowKey, validateWindowSlot, shortfallText } from '../composables/availabilitySelection.js'
import { bookingRestrictionDetails } from '../composables/restrictionForm.js'
import RestrictionRecord from '../components/RestrictionRecord.vue'
import AppIcon from '../components/AppIcon.vue'
import SButton from '../components/ui/SButton.vue'
import SSelect from '../components/ui/SSelect.vue'
import STag from '../components/ui/STag.vue'
import SInput from '../components/ui/SInput.vue'
import SBanner from '../components/ui/SBanner.vue'
import { minuteValue, serverStartMinute } from '../composables/bookingPolicy.js'

// ---- 条件区状态 ----
const rooms = ref([])
const roomsLoading = ref(false)
const roomsError = ref('')
const filteredRooms = computed(() => rooms.value)
const roomId = ref(null)
const strategy = ref('earliest'), requiredDraft = ref('120'), searchStart = ref(''), queryDirty = ref(true)
const strategyOptions = [{value:'earliest',label:'最早可约'},{value:'longest',label:'连续可用最久'},{value:'duration',label:'满足学习时长'}]
const strategyLabel = computed(() => strategyOptions.find(item => item.value === strategy.value)?.label || '找座方案')
const sortExplanation = computed(() => strategy.value === 'longest' ? '按开始时间和学习时长筛选后，连续空档最长的座位排在前面。' : strategy.value === 'duration' ? '显示开始时间后能连续满足所需学习时长的座位。' : '显示符合条件的座位，并按最早可约时间排列。')
const shortfall = computed(() => shortfallText(availability.value?.learning_minutes, start.value, end.value))
const date = ref(''), start = ref(''), end = ref('')
const timeOptions = Array.from({ length: 29 }, (_, index) => {
  const minute = 8 * 60 + index * 30
  return `${String(Math.floor(minute / 60)).padStart(2, '0')}:${String(minute % 60).padStart(2, '0')}`
})
const timePicker = ref(null), timeMenuOpen = ref(false)
const selectedWindow = ref(null), results = ref([]), availability = ref(null), presentation = ref('map')
const filterApplied = ref(false)
let alive = true, policyRequest = 0, roomsRequest = 0
const policy = ref(null), policyLoading = ref(false), policyError = ref(''), inputError = ref(''), bookingError = ref(''), restrictionFailure = ref(null)
const dateOptions = computed(() => (policy.value?.dates || []).map(value => ({ value, label:value })))
function pickRoom(id) {
  if (submitting.value || roomId.value === id) return
  roomId.value = id
  invalidateQuery()
  return searchStart.value ? loadAvailability() : loadInitialLayout()
}
function pickDate(value) {
  if (submitting.value || date.value === value) return
  date.value = value
  invalidateQuery()
  return searchStart.value ? loadAvailability() : loadInitialLayout()
}
function dateLabel(value) {
  const today = policy.value?.campus_today
  const index = (policy.value?.dates || []).indexOf(value)
  const weekday = new Date(value + 'T00:00:00Z').getUTCDay()
  const week = ['周日', '周一', '周二', '周三', '周四', '周五', '周六'][weekday] || ''
  const sub = value ? value.slice(5).replace('-', '/') + ' ' + week : ''
  if (!today) return { top: sub, sub: '' }
  if (value === today) return { top: '今天', sub }
  if (index === 1) return { top: '明天', sub }
  if (index === 2) return { top: '后天', sub }
  return { top: week, sub }
}
const duration = computed(() => { try { return minuteValue(end.value,true) - minuteValue(start.value) } catch { return null } })
async function loadPolicy(initialize = false) {
  const ticket = ++policyRequest
  policyLoading.value = true
  policyError.value = ''
  try {
    const response = await getBookingPolicy()
    if (!alive || ticket !== policyRequest) return false
    const value = response.data
    if (!value || !Array.isArray(value.dates) || !value.dates.includes(value.campus_today) || value.min_duration_minutes !== 30) throw new Error('预约政策返回不完整，请重试')
    serverStartMinute(value)
    policy.value = value
    if (initialize) {
      date.value = value.campus_today; clearSelection()
    }
    return true
  } catch (failure) {
    if (!alive || ticket !== policyRequest) return false
    policy.value = null
    policyError.value = failure.message
    return false
  } finally { if (alive && ticket === policyRequest) policyLoading.value = false }
}

async function refreshPolicy() {
  if (submitting.value || policyLoading.value) return
  if (!await loadPolicy(!date.value)) return
  if (!rooms.value.length) await loadRooms()
  else if (searchStart.value) await loadAvailability(true)
}

// ---- 平面图 ----
const room = ref(null)
const seats = ref([])
const loading = ref(false)
const selected = ref(null)
function invalidateQuery() {
  mapRequest++; queryDirty.value = true; loading.value = false
  clearSelection(); results.value = []; availability.value = null; hoverTip.value = null
}
function changeRequired(value) {
  if (submitting.value) return
  requiredDraft.value = value
  if (!searchStart.value) filterApplied.value = false
  invalidateQuery(); inputError.value = ''; mapError.value = false; mapErrorText.value = ''
}
function changeSearchStart(value) {
  if (submitting.value) return
  searchStart.value = value
  if (!value) filterApplied.value = false
  invalidateQuery(); inputError.value = ''; mapError.value = false; mapErrorText.value = ''
}
function toggleTimeMenu() {
  if (!submitting.value && policy.value) timeMenuOpen.value = !timeMenuOpen.value
}
function chooseSearchTime(value) {
  changeSearchStart(value)
  timeMenuOpen.value = false
}
function closeTimeMenuOnOutside(event) {
  if (!timePicker.value?.contains(event.target)) timeMenuOpen.value = false
}
function closeTimeMenuOnKey(event) {
  if (event.key === 'Escape') timeMenuOpen.value = false
}
function changeStrategy(value) {
  if (submitting.value || strategy.value === value) return
  strategy.value = value
  invalidateQuery(); inputError.value = ''; mapError.value = false; mapErrorText.value = ''
}
function currentQueryMatches(query) {
  return roomId.value === query.room_id && date.value === query.date && strategy.value === query.strategy && searchStart.value === query.filter_start_time && Number(requiredDraft.value) === query.learning_minutes
}
function clearSelection() { selected.value = null; selectedWindow.value = null; start.value = ''; end.value = '' }
function hasWindows(seat) {
  const windows = filterApplied.value ? seat?.matching_windows : seat?.all_windows
  return Array.isArray(windows) && windows.length > 0
}
function chooseWindow(window) {
  if (queryDirty.value || loading.value || mapError.value || submitting.value || !room.value?.enabled) return
  const seat = seats.value.find(item => item.id === window.seat_id && item.status === 'available')
  const actual = seat?.matching_windows?.find(item => windowKey(item) === windowKey(window))
  if (!actual) return
  selected.value = seat; selectedWindow.value = actual; inputError.value = ''
  start.value = actual.suggested_start; end.value = actual.suggested_end
}
function showWindows(windows) { return windows?.length ? windows.map(window => `${window.start}–${window.end}`).join('、') : '无开放时段' }
function validateSearchCriteria() {
  if (!/^(?:[01]\d|2[0-3]):[0-5]\d$/.test(searchStart.value)) throw new Error('请填写24小时制开始时间（HH:mm）')
  const startMinute = minuteValue(searchStart.value)
  if (startMinute < 8 * 60 || startMinute > 22 * 60) throw new Error('开始时间须在08:00—22:00之间')
  if (!/^[0-9]+$/.test(String(requiredDraft.value)) || !Number.isSafeInteger(Number(requiredDraft.value)) || Number(requiredDraft.value) < 30) throw new Error('学习时长须为至少30分钟的整数')
  return { startTime: searchStart.value, learningMinutes: Number(requiredDraft.value) }
}

const seatCard = ref(null)
const explorer = ref(null)
const show3D = ref(false)
const submitting = ref(false)
const mapError = ref(false)
const mapErrorText = ref('')
let mapRequest = 0
function open3D() {
  if (submitting.value || queryDirty.value || !room.value || loading.value || mapError.value || !seats.value.length) return
  hoverTip.value = null
  show3D.value = true
}

async function loadRooms() {
  const ticket = ++roomsRequest
  roomsLoading.value = true
  roomsError.value = ''
  try {
    const response = await getRooms()
    if (!alive || ticket !== roomsRequest) return
    rooms.value = response.data
    if (rooms.value.length && !roomId.value) {
      roomId.value = filteredRooms.value[0]?.id ?? null
      if (roomId.value) {
        if (searchStart.value) await loadAvailability()
        else await loadInitialLayout()
      }
    }
  } catch (failure) { if (alive && ticket === roomsRequest) roomsError.value = failure.message }
  finally { if (alive && ticket === roomsRequest) roomsLoading.value = false }
}
watch(filteredRooms, available => {
  if (!available.some(item => item.id === roomId.value)) {
    ++mapRequest
    loading.value = false
    roomId.value = available[0]?.id ?? null
    clearSelection(); results.value = []; availability.value = null
    if (roomId.value && searchStart.value) void loadAvailability()
    else if (roomId.value) void loadInitialLayout()
  }
})

async function loadInitialLayout() {
  const requestId = ++mapRequest
  if (!roomId.value || !date.value) return
  loading.value = true; queryDirty.value = true; mapError.value = false; mapErrorText.value = ''
  try {
    if (!policy.value?.dates.includes(date.value)) throw new Error('所选日期已无效，请刷新后重试')
    const response = await getAvailability(roomId.value, date.value, 'earliest')
    if (!alive || requestId !== mapRequest || roomId.value !== response.data?.room?.id || date.value !== response.data?.date) return
    const value = response.data
    if (!value || !Array.isArray(value.seats) || value.seats.some(seat => !Array.isArray(seat.all_windows))) throw new Error('座位布局读取不完整，请重试')
    room.value = value.room; seats.value = value.seats; results.value = []; availability.value = value
  } catch (failure) {
    if (alive && requestId === mapRequest) { mapError.value = true; mapErrorText.value = failure.message }
  } finally { if (alive && requestId === mapRequest) loading.value = false }
}

async function loadAvailability(preserveSelection = false) {
  const requestId = ++mapRequest
  const query = { roomId: roomId.value, date: date.value, strategy: strategy.value, requiredMinutes: null }
  let criteria
  try { criteria = validateSearchCriteria(); inputError.value = '' }
  catch (failure) { inputError.value = failure.message; queryDirty.value = true; clearSelection(); return }
  const previous = preserveSelection === true && selectedWindow.value ? { key: windowKey(selectedWindow.value), start: start.value, end: end.value } : null
  clearSelection(); results.value = []; hoverTip.value = null
  if (!query.roomId || !query.date) { loading.value = false; return }
  loading.value = true; queryDirty.value = true; mapError.value = false; mapErrorText.value = ''; inputError.value = ''
  try {
    if (!policy.value?.dates.includes(query.date)) throw new Error('所选预约日期已无效，请刷新政策并选择服务端日期')
    query.requiredMinutes = query.strategy === 'duration' ? criteria.learningMinutes : null
    query.learningMinutes = criteria.learningMinutes; query.startTime = criteria.startTime
    const response = await getAvailability(query.roomId, query.date, query.strategy, query.requiredMinutes, query.startTime, query.learningMinutes)
    if (!alive || requestId !== mapRequest || !currentQueryMatches({room_id:query.roomId,date:query.date,strategy:query.strategy,filter_start_time:query.startTime,learning_minutes:query.learningMinutes})) return
    const value = response.data
    if (!value || value.room?.id !== query.roomId || value.date !== query.date || value.strategy !== query.strategy || value.required_minutes !== query.requiredMinutes || value.learning_minutes !== query.learningMinutes || value.start_time !== query.startTime || value.campus_zone !== 'Asia/Shanghai' || !value.computed_at || !Array.isArray(value.seats) || !Array.isArray(value.results) || value.seats.some(seat => !Array.isArray(seat.available_windows) || !Array.isArray(seat.all_windows) || !Array.isArray(seat.matching_windows))) throw new Error('连续空档响应不完整，请重新查询')
    const windows = value.seats.flatMap(seat => seat.matching_windows)
    const keys = new Set(windows.map(windowKey))
    const byKey = new Map(windows.map(window => [windowKey(window), window]))
    if (windows.length !== value.results.length || keys.size !== windows.length || value.results.some(window => !keys.has(windowKey(window)) || ['duration_minutes','wait_minutes','seat_no','row_no','col_no','suggested_start','suggested_end'].some(key => window[key] !== byKey.get(windowKey(window))[key])) || value.seats.some(seat => seat.all_windows.some(window => window.seat_id !== seat.id || window.room_id !== query.roomId || minuteValue(window.available_end,true) - minuteValue(window.available_start) !== window.duration_minutes || !Number.isInteger(window.wait_minutes) || window.wait_minutes < 0) || seat.matching_windows.some(window => window.seat_id !== seat.id || window.room_id !== query.roomId || window.duration_minutes < query.learningMinutes || minuteValue(window.suggested_start) < minuteValue(query.startTime) || minuteValue(window.suggested_end,true) - minuteValue(window.suggested_start) !== query.learningMinutes || minuteValue(window.suggested_end,true) > minuteValue(window.available_end,true)))) throw new Error('图与列表空档数据不一致，请重新查询')
    queryDirty.value = false; filterApplied.value = true; room.value = value.room; seats.value = value.seats; results.value = value.results; availability.value = value
    if (previous) {
      const window = results.value.find(item => windowKey(item) === previous.key)
      if (window && room.value.enabled) {
        selected.value = seats.value.find(item => item.id === window.seat_id && item.status === 'available') || null
        if (selected.value) { selectedWindow.value = window; start.value = previous.start; end.value = previous.end }
      }
      if (!selectedWindow.value) message.info('原片段已变化，已清除选择，请重新核对连续空档')
    }
  } catch (failure) {
    if (alive && requestId === mapRequest) { mapError.value = true; mapErrorText.value = failure.message; clearSelection(); if (previous) { start.value = previous.start; end.value = previous.end } }
  } finally { if (alive && requestId === mapRequest) loading.value = false }
}

// 行列网格（座位区）
const gridStyle = computed(() => ({
  gridTemplateColumns: `repeat(${room.value?.seat_cols || 8}, minmax(76px, 1fr))`,
  gap: '10px'
}))

// 房间框架（墙/窗包裹座位区），最小宽度保证小容器可横向滚动
const frameStyle = computed(() => {
  const cols = room.value?.seat_cols || 8
  const contentMin = cols * 76 + (cols - 1) * 10 + 24 + 36
  return { minWidth: `${Math.max(480, contentMin)}px` }
})

// 靠窗方位：某条边只有当其全部座位连续靠窗时才判定为窗（避免角落座位误判两条边）
const windowSides = computed(() => getWindowSides(room.value, seats.value))

// 靠窗座位旁的窗条方向：仅在该侧边确认为窗时渲染
function nearWindowSide(s) {
  const sides = windowSides.value
  const rows = room.value?.seat_rows || 0
  const cols = room.value?.seat_cols || 0
  if (sides.right && s.col_no === cols) return 'seat-near-window-right'
  if (sides.left && s.col_no === 1) return 'seat-near-window-left'
  if (sides.top && s.row_no === 1) return 'seat-near-window-top'
  if (sides.bottom && s.row_no === rows) return 'seat-near-window-bottom'
  return ''
}

function seatClass(s) {
  let cls
  if (!room.value?.enabled || s.status !== 'available') cls = 'seat seat-disabled'
  else if (queryDirty.value && filterApplied.value) cls = 'seat seat-filtered-out'
  else if (!hasWindows(s)) cls = 'seat seat-filtered-out'
  else if (selected.value?.id === s.id) cls = 'seat seat-selected'
  else cls = 'seat seat-free'
  if (s.near_window) {
    const side = nearWindowSide(s)
    if (side) cls += ' ' + side
  }
  return cls
}

function seatTip(s) {
  const free = s.all_windows || s.available_windows || []
  return `${s.seat_no} · ${seatType(s)} · ${zoneName[s.zone] || s.zone}${s.near_window ? ' · 靠窗' : ''} · ${s.row_no}行${s.col_no}列${s.resource_reason ? ' · 原因：' + s.resource_reason : ''}${s.closed_reasons?.length ? ' · ' + s.closed_reasons.join('；') : ''} · ${free.length ? `全部空闲时段：${free.map(item => `${item.available_start}–${item.available_end}`).join('、')}` : '无空闲时段'}${s.status !== 'available' ? ' · ' + (s.status === 'maintenance' ? '维护中' : '停用') : ''}`
}

// 座位悬浮提示（共享单例，fixed 定位避免被滚动容器裁剪）
const hoverTip = ref(null)
function onGridOver(e) {
  const seatEl = e.target.closest('.seat')
  if (!seatEl) return
  const s = seats.value.find((x) => String(x.id) === seatEl.dataset.id)
  if (!s) return
  const rect = seatEl.getBoundingClientRect()
  hoverTip.value = {
    text: seatTip(s),
    x: Math.min(Math.max(rect.left + rect.width / 2, 90), window.innerWidth - 90),
    y: rect.top
  }
}
function onGridLeave() {
  hoverTip.value = null
}

function onSeatClick(s) {
  if (loading.value || mapError.value || submitting.value) return
  if (!room.value?.enabled) { message.info(`空间已停用：${room.value?.resource_reason || '请查看资源说明'}`); return }
  if (s.status !== 'available') {
    message.info(`${s.status === 'maintenance' ? '该座位维护中' : '该座位已停用'}：${s.resource_reason || '未提供原因'}`)
    return
  }
  if (!hasWindows(s)) { message.info((s.closed_reasons || []).join('；') || '该座位在所选日期无满足条件的连续空档'); return }
  if (selected.value?.id !== s.id) clearSelection()
  selected.value = s
  const first = s.matching_windows?.[0] || s.available_windows?.[0]
  if (first) chooseWindow(first)
}

async function confirmBooking() {
  const selectedSeat = selected.value
  if (queryDirty.value || !policy.value || !room.value?.enabled || !selectedSeat || submitting.value || loading.value || mapError.value || !selectedWindow.value || selectedSeat.status !== 'available' || !hasWindows(selectedSeat)) return
  if (selectedWindow.value.seat_id !== selectedSeat.id || selectedWindow.value.room_id !== room.value.id) { bookingError.value = '所选座位与空档不一致，请重新选择'; return }
  const attempt = {room_id:room.value.id, seat_id:selectedSeat.id, date:date.value, start_time:start.value, end_time:end.value, filter_start_time:searchStart.value, learning_minutes:Number(requiredDraft.value), room_name:room.value.name, seat_no:selectedSeat.seat_no, window:{...selectedWindow.value}, computed_at:availability.value?.computed_at, strategy:availability.value?.strategy, required_minutes:availability.value?.required_minutes}
  submitting.value = true
  bookingError.value = ''; restrictionFailure.value = null
  try {
    try {
      const minutes = validateWindowSlot(policy.value,attempt.date,attempt.start_time,attempt.end_time,attempt.window)
      await confirmDialog(`确认预约 ${attempt.room_name} ${attempt.seat_no}？\n${attempt.date} ${attempt.start_time}–${attempt.end_time}（${minutes}分钟，Asia/Shanghai）\n方案：${strategyOptions.find(item => item.value === attempt.strategy)?.label}；筛选条件：${attempt.filter_start_time}开始，连续学习${attempt.learning_minutes}分钟。\n完整空档：${attempt.window.available_start}–${attempt.window.available_end}；本次预约：${attempt.start_time}–${attempt.end_time}。\n请在开始起至开始后15分钟之前扫码签到。`, '预约确认', { confirmButtonText:'确认预约',cancelButtonText:'再想想' })
    } catch (failure) {
      if (failure?.message) bookingError.value = failure.message
      return
    }
    if (!alive || !await loadPolicy(false)) return
    validateWindowSlot(policy.value,attempt.date,attempt.start_time,attempt.end_time,attempt.window)
    const response = await createReservation({ seat_id:attempt.seat_id,date:attempt.date,start_time:attempt.start_time,end_time:attempt.end_time })
    if (!alive) return
    message.success(`预约成功：${response.data.room_name} ${response.data.seat_no}`)
    clearSelection()
    if (currentQueryMatches({room_id:attempt.room_id,date:attempt.date,strategy:attempt.strategy,filter_start_time:attempt.filter_start_time,learning_minutes:attempt.learning_minutes})) await loadAvailability()
    explorer.value?.close()
  } catch (failure) {
    if (!alive) return
    restrictionFailure.value = bookingRestrictionDetails(failure)
    bookingError.value = failure.status === 409 ? `${failure.message}。本次未完成预约；正在按原日期和空间刷新连续空档，请重新核对。` : failure.message
    if (currentQueryMatches({room_id:attempt.room_id,date:attempt.date,strategy:attempt.strategy,filter_start_time:attempt.filter_start_time,learning_minutes:attempt.learning_minutes})) await loadAvailability(true)
  } finally { if (alive) submitting.value = false }
}

// 统计右栏实时数据
const seatStats = computed(() => {
  const total = seats.value.length
  const down = seats.value.filter(seat => !room.value?.enabled || seat.status !== 'available').length
  const free = seats.value.filter(seat => room.value?.enabled && seat.status === 'available' && hasWindows(seat)).length
  return { total, free, occ: total - down - free, down }
})
onBeforeUnmount(() => {
  alive = false; mapRequest++; policyRequest++; roomsRequest++; hoverTip.value = null
  document.removeEventListener('pointerdown', closeTimeMenuOnOutside)
  document.removeEventListener('keydown', closeTimeMenuOnKey)
})

onMounted(async () => {
  document.addEventListener('pointerdown', closeTimeMenuOnOutside)
  document.addEventListener('keydown', closeTimeMenuOnKey)
  if (await loadPolicy(true)) await loadRooms()
})
</script>

<template>
  <div v-reveal class="page-view">
    <header class="view-heading" data-page-title>
      <h1>座位预约</h1>
      <p class="heading-sub">比较最早、最长或满足学习时长的连续空档，用图或列表选择后确认本次预约</p>
    </header>

    <section v-if="policy" class="booking-top">
      <div class="room-tabs" role="tablist" aria-label="选择自习室">
        <button v-for="item in filteredRooms" :key="item.id" type="button" role="tab" :aria-selected="item.id===roomId" :class="{active: item.id===roomId}" :disabled="submitting" @click="pickRoom(item.id)">
          <b>{{ item.name }}</b>
          <small>{{ item.location || '查看连续空档' }}</small>
        </button>
      </div>
      <div class="date-strip" role="tablist" aria-label="选择预约日期">
        <button v-for="option in dateOptions" :key="option.value" type="button" role="tab" :aria-selected="option.value===date" :class="{active: option.value===date}" :disabled="submitting" @click="pickDate(option.value)">
          <b>{{ dateLabel(option.value).top }}</b>
          <small>{{ dateLabel(option.value).sub }}</small>
        </button>
      </div>
    </section>

    <!-- 预约条件、紧凑信息条、座位图 -->
    <div class="split booking-split">
    <!-- 筛选与统计操作区 -->
    <div class="split-left">
      <section class="card responsive-compact">
        <div class="card-title-row">
          <h3>预约条件</h3>
        </div>
        <p v-if="policyLoading" role="status">正在读取预约政策…</p>
        <SBanner v-if="policyError" type="error" :title="policyError"><SButton size="sm" :disabled="submitting" @click="refreshPolicy">重试</SButton></SBanner>
        <SBanner v-if="inputError" type="error" :title="inputError" />
        <p v-if="roomsLoading" role="status">正在加载空间…</p>
        <SBanner v-if="roomsError" type="error" :title="roomsError"><SButton size="sm" :disabled="submitting" @click="loadRooms">重试</SButton></SBanner>
        <div class="filter-group">
          <label class="filter-row">开始时间（08:00—22:00，24小时制）
            <div ref="timePicker" class="time-picker" @keydown.esc="timeMenuOpen = false">
              <SInput :model-value="searchStart" inputmode="numeric" maxlength="5" placeholder="HH:mm，可手动输入" aria-label="开始时间，24小时制08:00至22:00" :disabled="submitting || !policy" @update:model-value="changeSearchStart" />
              <button class="time-picker__toggle" type="button" :disabled="submitting || !policy" :aria-expanded="timeMenuOpen" aria-label="展开开始时间选项" aria-controls="booking-start-time-options" @click="toggleTimeMenu">
                <svg viewBox="0 0 24 24" aria-hidden="true"><path d="m6 9 6 6 6-6" /></svg>
              </button>
              <div v-if="timeMenuOpen" id="booking-start-time-options" class="time-picker__menu" role="listbox" aria-label="开始时间（08:00至22:00，每30分钟）">
                <button v-for="time in timeOptions" :key="time" class="time-picker__option" type="button" role="option" :aria-selected="searchStart === time" @click="chooseSearchTime(time)">{{ time }}</button>
              </div>
            </div>
          </label>
          <label class="filter-row">学习时长（分钟）<SInput :model-value="requiredDraft" inputmode="numeric" :disabled="submitting || !policy" placeholder="至少30的整数" @update:model-value="changeRequired" /></label>
          <div class="filter-row"><label>找座方案</label><SSelect :model-value="strategy" :options="strategyOptions" :disabled="submitting || !policy" @update:model-value="changeStrategy" /></div>
          <p class="muted">{{ sortExplanation }}</p>
          <SButton variant="primary" :disabled="submitting || loading || !policy || !roomId" @click="loadAvailability()">筛选座位</SButton>
          <p v-if="queryDirty && !loading && !mapError" role="status">设置开始时间和学习时长后，点击“筛选座位”。</p>
          <SBanner v-if="shortfall" type="warning" :title="shortfall" />
        </div>
      </section>
      <SBanner v-if="bookingError" type="error" :title="bookingError" />
      <section v-if="restrictionFailure" class="card"><h4>预约限制</h4><RestrictionRecord v-for="record in restrictionFailure.active_restrictions" :key="record.id" :record="record" /><RouterLink to="/profile#restrictions">查看当前资格</RouterLink></section>
      <section class="card selected-card" :class="{ 'selected-card--empty': !selected }">
        <div class="sel-info">
          <div class="sel-icon"><AppIcon name="seat" :size="25" /></div>
          <div class="sel-detail" :key="selected?.id || 'empty'">
            <template v-if="selected">
              <div class="sel-title">已选择 <b>{{ selected.seat_no }}</b><STag>{{ zoneName[selected.zone] || selected.zone }}</STag><STag v-if="selected.has_power" type="success">电源</STag><STag v-if="selected.near_window" type="warning">靠窗</STag></div>
              <div class="muted">{{ date }} · {{ start }}–{{ end }} · {{ room?.name }} · {{ room?.location || '' }} · {{ selected.row_no }}行{{ selected.col_no }}列</div>
              <div class="window-choices" aria-label="符合条件的空档"><SButton v-for="window in selected.matching_windows" :key="windowKey(window)" size="sm" :variant="windowKey(window) === windowKey(selectedWindow) ? 'primary' : 'secondary'" :disabled="submitting || loading" @click="chooseWindow(window)">{{ window.available_start }}–{{ window.available_end }} · 建议{{ window.suggested_start }}–{{ window.suggested_end }}</SButton></div>
              <p v-if="selectedWindow" class="muted">空档：{{ selectedWindow.available_start }}–{{ selectedWindow.available_end }}；本次预约：{{ start }}–{{ end }}（{{ duration }}分钟）</p>
              <p v-if="!selectedWindow" class="muted">请选择一个符合条件的空闲时段。</p>
            </template>
            <template v-else><div class="sel-placeholder">请选择座位</div><div class="muted">座位按筛选结果自动灰显不可选项；可查看座位的全部空闲时段。</div></template>
          </div>
        </div>
        <SButton variant="primary" size="lg" :disabled="!policy || policyLoading || !room?.enabled || !selected || !selectedWindow || loading || mapError || queryDirty" :loading="submitting" @click="confirmBooking">提交预约</SButton>
      </section>
    </div>

    <!-- 右栏：座位平面图 + 已选条 -->
    <div class="split-right">
      <section ref="seatCard" class="card seat-card">
        <div class="card-title-row">
          <div class="seat-heading">
            <h3>选择座位</h3>
            <div class="row-actions">
              <SButton size="sm" :variant="presentation === 'map' ? 'primary' : 'secondary'" @click="presentation='map'">平面图</SButton>
              <SButton size="sm" :variant="presentation === 'list' ? 'primary' : 'secondary'" @click="presentation='list'">空档列表</SButton>
              <SButton size="sm" :disabled="submitting || !room || loading" @click="searchStart ? loadAvailability(true) : loadInitialLayout()">刷新</SButton>
            </div>
            <span v-if="room" class="room-brief">{{ room.name }} · 开放 {{ showWindows(room.opening_windows) }}</span>
            <SButton :disabled="submitting || queryDirty || !room || loading || mapError || !seats.length" aria-label="打开 3D 选座" @click="open3D">3D</SButton>
          </div>
        </div>

        <SBanner v-if="room?.closed_reasons?.length" type="warning" :title="room.closed_reasons.join('；')" />
        <SBanner v-if="room && !room.enabled" type="warning" :title="`空间已停用：${room.resource_reason || '未提供原因'}`" />
        <SBanner v-if="mapError && !inputError" type="error" :title="mapErrorText || '座位查询失败，请核对条件并重试'"><SButton size="sm" :disabled="submitting" @click="searchStart ? loadAvailability(true) : loadInitialLayout()">重试</SButton></SBanner>
        <p v-if="!loading && !mapError && !queryDirty && availability && !results.length" role="status">当前条件下没有可约空档，请调整时间或空间。</p>
        <p v-if="loading" role="status">正在查询可约空档…</p>
        <div v-if="!loading && !mapError && results.length && presentation === 'list'" class="table-wrap"><div class="table-scroll" tabindex="0" aria-label="连续空档列表，可左右滑动"><table class="table availability-table"><thead><tr><th>顺序 / 座位</th><th>位置</th><th>完整空档</th><th>完整连续时长 / 建议</th><th>等待</th><th>选择</th></tr></thead><tbody><tr v-for="(window,index) in results" :key="windowKey(window)" :class="{ 'earliest-row': index === 0, 'chosen-row': windowKey(window) === windowKey(selectedWindow) }"><td>{{ index + 1 }} · {{ window.seat_no }}<STag v-if="index===0">{{ strategyLabel }}首选</STag></td><td>{{ window.row_no }}行{{ window.col_no }}列</td><td>{{ window.available_start }}–{{ window.available_end }}</td><td>{{ window.duration_minutes }}分钟<div>建议{{ window.suggested_start }}–{{ window.suggested_end }}</div></td><td>{{ window.wait_minutes }}分钟</td><td><SButton size="sm" :disabled="submitting" @click="chooseWindow(window)">选择该片段</SButton></td></tr></tbody></table></div></div>
        <div class="responsive-scroll" tabindex="0" aria-label="座位平面图，可左右滑动">
        <div v-loading="loading" class="seat-grid-wrap" @mouseover="onGridOver" @mouseleave="onGridLeave">
          <div v-if="!room" class="empty-tip muted">请先选择自习室并设置预约条件</div>
          <div v-else :style="frameStyle" class="room-frame">
            <!-- 顶部：窗或墙 -->
            <div class="wall wall-top" :class="{ window: windowSides.top }" aria-hidden="true"></div>
            <!-- 左：窗或墙 -->
            <div class="wall wall-left" :class="{ window: windowSides.left }" aria-hidden="true"></div>
            <!-- 座位网格 -->
            <div :style="gridStyle" class="seat-grid">
              <button
                v-for="s in seats"
                :key="s.id"
                :class="seatClass(s)"
                :data-id="s.id"
                type="button"
                :style="{ gridRow: s.row_no, gridColumn: s.col_no }"
                :aria-label="seatTip(s)"
                :aria-pressed="selected?.id === s.id"
                :aria-disabled="!room?.enabled || !hasWindows(s) || s.status !== 'available' || loading || submitting || queryDirty"
                :disabled="!room?.enabled || !hasWindows(s) || s.status !== 'available' || loading || submitting || queryDirty"
                @click="onSeatClick(s)"
              >
                <SeatPlanDesk :computer="s.zone === 'computer'" :power="s.has_power" />
                <span class="seat-no">{{ s.seat_no }}<span v-if="results[0]?.seat_id === s.id"> · 首选</span></span><span v-if="s.matching_windows?.length" class="window-count">{{ s.matching_windows.length }}段</span>
              </button>
            </div>
            <!-- 右：窗或墙 -->
            <div class="wall wall-right" :class="{ window: windowSides.right }" aria-hidden="true"></div>
            <!-- 底部：窗或墙（有门时底部为墙） -->
            <div class="wall wall-bottom" :class="{ window: windowSides.bottom }">
              <template v-if="!windowSides.bottom">
                <span class="wall-seg" aria-hidden="true"></span>
                <span class="door" aria-hidden="true"></span>
                <span class="wall-seg" aria-hidden="true"></span>
              </template>
            </div>
          </div>
        </div>
        </div>
      </section>

    </div>
  </div>

  <SeatExplorer v-if="show3D" ref="explorer" :origin="seatCard" :room="room" :seats="seats" :selected-id="selected?.id" :selected-window="selectedWindow" :date="date" :start="start" :end="end" :requested-start="searchStart" :loading="loading" :submitting="submitting" :submit-error="bookingError || policyError" :restriction-failure="restrictionFailure" :policy-ready="!!policy && !policyLoading && !queryDirty" :strategy-label="strategyLabel" :required-minutes="availability?.learning_minutes" :shortfall="shortfall" @select="onSeatClick" @select-window="chooseWindow" @submit="confirmBooking" @closed="show3D = false" />

  <!-- 座位悬浮提示 -->
  <Teleport to="body">
    <div
      v-if="hoverTip"
      class="seat-hover-tip"
      :style="{ left: `${hoverTip.x}px`, top: `${hoverTip.y}px` }"
      aria-hidden="true"
    >
      {{ hoverTip.text }}
    </div>
  </Teleport>


  </div>
</template>

<style scoped>
.booking-split{grid-template-columns:360px minmax(0,1fr)}
.booking-split .filter-group{display:grid;grid-template-columns:1fr;gap:14px}
.booking-split .split-left{display:flex;flex-direction:column;gap:14px;min-width:0}
.booking-split .selected-card{display:flex;flex-direction:column;align-items:stretch;gap:14px}
.booking-split .selected-card .sel-info{min-width:0}
.booking-split .selected-card .sel-detail{min-width:0;overflow-wrap:anywhere}
.time-range{display:grid;grid-template-columns:minmax(0,1fr) auto minmax(0,1fr);gap:10px;align-items:end}
.time-range label{display:grid;gap:6px;font-size:var(--fs-caption)}
.time-sep{padding-bottom:10px;color:var(--text-3);font-size:var(--fs-body-sm)}
.time-duration{margin:0;color:var(--text-2);font-size:var(--fs-body-sm)}
.time-picker{position:relative;display:flex;align-items:center;gap:8px;width:100%}
.time-picker :deep(.s-input){flex:1;min-width:0}
.time-picker__toggle{display:grid;place-items:center;flex:0 0 42px;width:42px;height:38px;padding:0;border:1px solid var(--border);border-radius:var(--r-md);background:var(--surface);color:var(--text-2);cursor:pointer;transition:background var(--dur-1) var(--ease),border-color var(--dur-1) var(--ease),color var(--dur-1) var(--ease)}
.time-picker__toggle:hover:not(:disabled){background:var(--primary-faint);border-color:var(--primary);color:var(--primary)}
.time-picker__toggle:disabled{opacity:.55;cursor:not-allowed}
.time-picker__toggle svg{width:18px;height:18px;fill:none;stroke:currentColor;stroke-width:1.8;stroke-linecap:round;stroke-linejoin:round}
.time-picker__menu{position:absolute;z-index:var(--z-popover);top:calc(100% + 6px);right:0;left:0;display:grid;grid-template-columns:1fr;gap:3px;max-height:222px;overflow-y:auto;padding:6px;border:1px solid var(--border);border-radius:var(--r-md);background:var(--surface);box-shadow:var(--shadow-2);overscroll-behavior:contain}
.time-picker__option{min-height:32px;padding:5px 10px;border:1px solid transparent;border-radius:var(--r-sm);background:transparent;color:var(--text-1);text-align:left;font:inherit;font-variant-numeric:tabular-nums;cursor:pointer}
.time-picker__option:hover,.time-picker__option[aria-selected='true']{border-color:var(--primary);background:var(--primary-faint);color:var(--primary)}
.more-filters{border-top:1px solid var(--hairline);padding-top:10px;display:grid;gap:10px}
.more-filters summary{cursor:pointer;color:var(--text-3);font-size:var(--fs-caption);list-style:none}
.more-filters summary::-webkit-details-marker{display:none}
.more-filters summary::before{content:'＋ ';color:var(--primary)}
.more-filters[open] summary::before{content:'－ '}
.room-brief{color:var(--text-3);font-size:var(--fs-caption);white-space:nowrap;overflow:hidden;text-overflow:ellipsis;min-width:0}
@media(max-width:1100px){.booking-split{grid-template-columns:minmax(0,1fr)}}
.booking-top{display:flex;flex-direction:column;gap:14px;margin-bottom:20px}
.room-tabs{display:grid;grid-template-columns:repeat(auto-fit,minmax(180px,1fr));gap:12px}
.room-tabs button{all:unset;display:flex;flex-direction:column;gap:4px;padding:15px 18px;border:1px solid var(--border);border-radius:var(--r-lg);background:var(--surface);cursor:pointer;transition:border-color var(--dur-1) var(--ease),background var(--dur-1) var(--ease)}
.room-tabs button:hover{border-color:var(--border-strong)}
.room-tabs button.active{border-color:var(--primary);background:var(--primary-faint);box-shadow:inset 0 0 0 1px var(--primary)}
.room-tabs b{font-family:var(--font-display);font-size:18px;color:var(--text-1);white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.room-tabs small{color:var(--text-3);font-size:11px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.date-strip{display:flex;gap:6px;flex-wrap:wrap;padding:6px;background:var(--surface);border:1px solid var(--border);border-radius:var(--r-lg)}
.date-strip button{all:unset;display:flex;flex-direction:column;align-items:center;gap:2px;min-width:92px;padding:9px 14px;border-radius:var(--r-md);cursor:pointer;color:var(--text-2);transition:background var(--dur-1) var(--ease),color var(--dur-1) var(--ease)}
.date-strip button:hover{background:var(--surface-2)}
.date-strip button.active{background:var(--primary);color:#fff}
.date-strip b{font-size:14px;font-weight:600}
.date-strip small{font-size:11px;opacity:.8}
@media(max-width:720px){.room-tabs{grid-template-columns:1fr 1fr}.date-strip button{min-width:76px;padding:8px 10px}}
.shortfall-warning{color:var(--amber-strong)}.window-choices{display:flex;gap:8px;flex-wrap:wrap;margin-top:10px}.availability-table{min-width:680px}.earliest-row{background:var(--primary-weak)}.chosen-row{outline:1px solid var(--primary)}.window-count{font-size:11px;color:var(--text-2)}
.resource-summary { font-size:var(--fs-caption); color:var(--text-3); line-height:1.7; overflow-wrap:anywhere; margin-bottom:12px; }
.seat-card .seat-heading { width: 100%; display: flex; align-items: center; gap: 10px 16px; flex-wrap: wrap; }
.seat-heading h3 { flex: 0 0 auto; }
/* 自习室选择器 + 示意卡片：与标题相邻、整体靠左 */
.seat-head-tools {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  min-width: 0;
  margin-inline-end: auto; /* 把右侧空间推给 3D 按钮，本组自身靠左 */
}
/* 示意卡片：单行概要 */
.room-chip {
  min-width: 0;
  max-width: min(360px, 46vw);
  padding: 5px 12px;
  background: var(--surface-2);
  border: 1px solid var(--hairline);
  border-radius: 999px;
  box-shadow: inset 0 1px 0 #ffffff0a;
}
.room-chip__meta {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: var(--fs-caption);
  color: var(--text-4);
  line-height: 1.5;
}
.room-select { flex: 0 0 150px; width: 150px; }
@media (max-width: 720px) {
  .seat-head-tools { margin-inline-end: 0; }
  .room-chip { flex: 1 1 auto; max-width: 100%; }
  .room-select { flex: 0 0 150px; }
}


/* 时段选择并排 */
.time-pair {
  display: grid;
  grid-template-columns: 1fr 20px 1fr;
  gap: 6px;
  align-items: center;
}
.time-pair .arrow {
  text-align: center;
  color: var(--text-4);
}

.booking-summary {
  display: grid;
  grid-template-columns: minmax(260px, 1fr) minmax(320px, 1.15fr) max-content;
  align-items: center;
  gap: 16px;
  padding: 14px 18px;
}
.booking-counts { min-width: 0; }
.booking-counts .kpi-grid { grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 0; }
.booking-counts .kpi { padding: 6px 10px; }
.booking-counts .kpi-num { font-size: 24px; }
.booking-counts .kpi-label { font-size: 12px; white-space: nowrap; }
.booking-legends { display: grid; gap: 12px; min-width: 0; padding-inline: 16px; border-inline: 1px solid var(--border); }
.booking-legends .legend { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 8px; padding: 0; border: 0; margin: 0; }
.booking-legends .legend-item { gap: 5px; white-space: nowrap; font-size: 12px; }
.booking-actions { display: flex; flex-direction: column; align-items: stretch; justify-content: center; align-self: stretch; gap: 8px; margin: 0; padding: 0; }
.booking-actions :deep(.s-btn) { margin: 0; }
@media (max-width: 1024px) {
  .booking-summary { grid-template-columns: minmax(0, 1fr); gap: 14px; }
  .booking-legends { width: 100%; padding: 14px 0; border-inline: 0; border-block: 1px solid var(--border); }
  .booking-actions { width: 100%; flex-direction: row; align-items: center; }
  .booking-actions :deep(.s-btn) { flex: 1; min-width: 0; }
}
@media (max-width: 420px) {
  .booking-counts .kpi { padding-inline: 4px; }
  .booking-counts .kpi-num { font-size: 22px; }
  .booking-counts .kpi-label, .booking-legends .legend-item { font-size: 11px; }
  .booking-legends .legend { gap: 6px; }
  .booking-legends .dot { width: 11px; height: 11px; flex-shrink: 0; }
}


.legend {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 8px 12px;
  padding-top: 12px;
  border-top: 1px solid var(--hairline);
}
.legend-item {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: var(--fs-caption);
  color: var(--text-3);
}
.dot {
  width: 15px;
  height: 15px;
  border-radius: 5px;
  display: inline-block;
  border: 1px solid transparent;
}
.dot-free     { background: var(--seat-free-bg); border-color: var(--seat-free-border); }
.dot-selected { background: var(--primary); }
.dot-occupied { background: var(--seat-occupied-bg); border-color: var(--seat-occupied-border); }
.dot-disabled { background: var(--surface-3); border-color: var(--border); }

/* 桌型 / 窗户 图例 */
.type-ico {
  width: 16px;
  height: 16px;
  display: inline-grid;
  place-items: center;
  color: var(--text-3);
  flex: 0 0 auto;
}
.type-ico--window {
  background:
    linear-gradient(180deg, var(--window-glass-hi), transparent 60%),
    repeating-linear-gradient(90deg, var(--window-frame) 0 2px, var(--window-glass) 2px 8px);
  border: 1px solid var(--window-frame);
  border-radius: 3px;
}
.legend--status {
  margin-top: 10px;
  padding-top: 10px;
  border-top: 1px solid var(--hairline);
}

/* 座位图 */
.seat-card {
  min-height: 0;
}
.booking-split > .split-right { align-self: start; }
.booking-split > .split-right > .seat-card:first-child { flex: 0 0 auto; }
.seat-grid-wrap {
  margin-top: 8px;
  padding: 12px;
  background: var(--surface-2);
  border: 1px solid var(--hairline);
  border-radius: var(--r-lg);
  min-height: 0;
  display: grid;
  place-items: start stretch;
}
.empty-tip {
  padding: 60px 0;
  font-size: var(--fs-body);
}
/* 房间框架：墙 + 窗 + 门 包裹座位区 */
.room-frame {
  width: 100%;
  display: grid;
  grid-template-columns: 18px minmax(0, 1fr) 18px;
  grid-template-rows: 18px auto 18px;
  border-radius: var(--r-lg);
  overflow: hidden;
}
.wall { background: linear-gradient(135deg, #ffffff12, #00000010), var(--wall-solid); box-shadow: inset 0 1px 0 #ffffff12, inset 0 -2px 3px #0002; }
.wall-top { grid-column: 1 / -1; grid-row: 1; }
.wall-left { grid-column: 1; grid-row: 2; }
.wall-right { grid-column: 3; grid-row: 2; }
.wall-bottom {
  grid-column: 1 / -1;
  grid-row: 3;
  display: flex;
  align-items: stretch;
}
.wall-seg { flex: 1 1 auto; }

/* 窗户：水平墙（顶/底）竖窗棂，竖直墙（左/右）横窗棂 */
.wall-top.window,
.wall-bottom.window {
  background:
    linear-gradient(180deg, var(--window-glass-hi), transparent 60%),
    repeating-linear-gradient(90deg, var(--window-frame) 0 3px, var(--window-glass) 3px 22px);
}
.wall-left.window,
.wall-right.window {
  background:
    linear-gradient(90deg, var(--window-glass-hi), transparent 60%),
    repeating-linear-gradient(180deg, var(--window-frame) 0 3px, var(--window-glass) 3px 22px);
}
.wall.window { box-shadow: inset 0 0 0 1px var(--window-frame), inset 3px 0 8px #d0f3ff22, 0 0 12px #95d5ff15; }

/* 门：底墙中间开口 */
.door {
  flex: 0 0 46px;
  position: relative;
  background: var(--door);
  border: 2px solid var(--door-frame);
  border-bottom: none;
  border-radius: 6px 6px 0 0;
}
.door::after {
  content: '';
  position: absolute;
  right: 7px;
  top: 6px;
  width: 4px;
  height: 4px;
  border-radius: 50%;
  background: var(--door-frame);
}

/* 座位区 */
.seat-grid {
  grid-column: 2;
  grid-row: 2;
  min-width: 0;
  display: grid;
  padding: 12px;
  background: linear-gradient(125deg, #d5e6ef0c, transparent 65%), repeating-linear-gradient(0deg, transparent 0 43px, #87999d12 43px 44px), repeating-linear-gradient(90deg, transparent 0 87px, #87999d12 87px 88px), var(--room-floor);
  box-shadow: inset 0 2px 8px #00000012;
}
.seat {
  min-height: 62px;
  min-width: 0;
  padding: 6px 3px;
  appearance: none;
  font-family: inherit;
  border-radius: var(--r-md);
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 5px;
  font-variant-numeric: tabular-nums;
  letter-spacing: .02em;
  cursor: pointer;
  position: relative;
  transition: transform var(--dur-1) var(--ease), box-shadow var(--dur-1) var(--ease),
    background var(--dur-1) var(--ease), border-color var(--dur-1) var(--ease);
  user-select: none;
  border: 1px solid transparent;
}
.seat:hover {
  transform: translateY(-1px);
}
.seat:focus-visible { outline: 2px solid var(--primary); outline-offset: 2px; z-index: 1; }
.seat[aria-disabled='true']:hover { transform: none; }
.seat-no {
  font-size: 10px;
  font-weight: 700;
  line-height: 1;
  display: inline-flex;
  align-items: center;
  gap: 5px;
  opacity: 1;
}
.seat-no::before { content: ''; width: 5px; height: 5px; border-radius: 50%; background: currentColor; box-shadow: 0 0 5px currentColor; }
/* 靠窗座位：桌面靠窗一侧淡蓝窗条（方向随数据） */
.seat-near-window-right::after,
.seat-near-window-left::after {
  content: '';
  position: absolute;
  top: 7px;
  bottom: 7px;
  width: 3px;
  border-radius: 2px;
  background: var(--window-frame);
}
.seat-near-window-right::after { right: 3px; }
.seat-near-window-left::after { left: 3px; }
.seat-near-window-top::after,
.seat-near-window-bottom::after {
  content: '';
  position: absolute;
  left: 7px;
  right: 7px;
  height: 3px;
  border-radius: 2px;
  background: var(--window-frame);
}
.seat-near-window-top::after { top: 3px; }
.seat-near-window-bottom::after { bottom: 3px; }
.seat-free {
  background: linear-gradient(145deg, #ffffff09, transparent), color-mix(in srgb, var(--seat-free-bg) 55%, transparent);
  color: var(--seat-free-color);
  border-color: var(--seat-free-border);
  box-shadow: inset 0 1px 0 #ffffff0c, 0 2px 3px #0000000a;
}
.seat-free:hover {
  background: var(--seat-free-hover-bg);
  border-color: var(--seat-free-hover-border);
}
.seat-selected {
  background: linear-gradient(135deg, #ffffff22, transparent), var(--primary);
  color: #fff;
  border-color: var(--primary-active);
  box-shadow: 0 0 0 3px var(--primary-ring), 0 4px 12px rgba(59, 102, 218, .32);
}
.seat-occupied {
  background: var(--seat-occupied-bg);
  color: var(--seat-occupied-color);
  border-color: var(--seat-occupied-border);
  cursor: not-allowed;
}
.seat-disabled {
  background:
    repeating-linear-gradient(135deg, var(--surface-3) 0 5px, var(--seat-disabled-stripe) 5px 9px);
  color: var(--text-4);
  border-color: var(--border);
  cursor: not-allowed;
  text-decoration: line-through;
  text-decoration-color: var(--text-4);
}
.seat.seat-filtered-out,
.seat:disabled.seat-filtered-out {
  background: color-mix(in srgb, var(--surface-3) 82%, var(--text-4) 18%);
  color: var(--text-4);
  border-color: var(--border);
  filter: grayscale(1);
  opacity: .68;
  cursor: not-allowed;
  box-shadow: none;
  text-decoration: none;
}
.seat.seat-filtered-out :deep(.plan-desk) { filter: grayscale(1) saturate(.15); opacity: .55; }
.seat-disabled :deep(.plan-desk), .seat-occupied :deep(.plan-desk) { filter: saturate(.4) brightness(.85); }
@media (prefers-reduced-motion: reduce) { .seat { transition: none; }.seat:hover { transform: none; } }

/* 座位悬浮提示 */
.seat-hover-tip {
  position: fixed;
  z-index: var(--z-popover);
  transform: translate(-50%, calc(-100% - 10px));
  background: var(--seat-tip-bg);
  color: var(--seat-tip-text);
  font-size: var(--fs-caption);
  padding: 5px 10px;
  border-radius: var(--r-sm);
  pointer-events: none;
  white-space: nowrap;
  box-shadow: var(--shadow-2);
}

.seat-hover-tip::after {
  content: '';
  position: absolute;
  left: 50%;
  bottom: -4px;
  width: 8px;
  height: 8px;
  transform: translateX(-50%) rotate(45deg);
  background: var(--seat-tip-bg);
}

/* 已选条 */
.selected-card {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  background: var(--primary-faint);
  border-color: var(--border-strong);
}
.sel-info {
  display: flex;
  align-items: center;
  gap: 14px;
  min-width: 0;
}
.sel-detail {
  min-width: 0;
}
.sel-icon {
  width: 46px;
  height: 46px;
  border-radius: var(--r-lg);
  background: var(--surface);
  display: grid;
  place-items: center;
  color: var(--primary);
  box-shadow: var(--shadow-1);
  flex: 0 0 auto;
}
.sel-title {
  font-size: var(--fs-title);
  font-weight: 600;
  color: var(--text-1);
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 2px;
}
/* 未选择座位：常驻提示态 */
.selected-card--empty {
  background: var(--surface);
  border-color: var(--border);
}
.selected-card--empty .sel-icon {
  background: var(--surface-3);
  color: var(--text-4);
  box-shadow: none;
}
.selected-card--empty .sel-detail .muted {
  color: var(--text-4);
}
.sel-placeholder {
  font-size: var(--fs-title);
  font-weight: 600;
  color: var(--text-3);
  margin-bottom: 2px;
}

@media (max-width: 720px) {
  .seat-grid-wrap { padding: 10px; min-width: max-content; }
  .seat-grid { padding: 8px; }
  .seat { border-radius: var(--r-sm); font-size: 10px; }
  .selected-card { align-items: stretch; flex-direction: column; }
  .selected-card :deep(.s-btn) { width: 100%; }
  .seat-card { overflow: hidden; }
  .sel-info { min-width: 0; align-items: flex-start; }
  .sel-title { flex-wrap: wrap; }
}
</style>
