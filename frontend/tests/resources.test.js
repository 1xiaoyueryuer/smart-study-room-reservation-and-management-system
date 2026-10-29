import test from 'node:test'
import assert from 'node:assert/strict'
import vm from 'node:vm'
import { readFile } from 'node:fs/promises'
import {  validateSlot, minuteValue, minuteText, serverStartMinute } from '../src/composables/bookingPolicy.js'
import { bookingRestrictionDetails } from '../src/composables/restrictionForm.js'
import { windowKey, validateWindowSlot, queryRequiredMinutes, shortfallText } from '../src/composables/availabilitySelection.js'
import { operationReason } from '../src/components/admin/userForm.js'
import { ref, computed } from 'vue'

const room = () => ({ id: 1, name: '测试空间', building: '教学楼', floor: 'B1', location: '东侧', enabled: true, seat_count: 2, capacity: 1, bookable_capacity: 1, seat_rows: 30, seat_cols: 30, version: 3, open_time: '08:00', close_time: '22:00', description: '' })
const seat = () => ({ id: 2, seat_no: 'A1', row_no: 1, col_no: 1, zone: 'regular', status: 'available', has_power: false, near_window: false, occupied: false, bookable_for_slot: true, opening_windows: [{start:'08:00',end:'22:00'}], version: 5 })
async function script(path, mocks, exports, extra = {}) {
  const source = (await readFile(new URL(path, import.meta.url), 'utf8')).split('<script setup>')[1].split('</script>')[0]
  const context = vm.createContext({ defineEmits: () => () => {}, defineExpose() {}, ...extra })
  const module = new vm.SourceTextModule(source + `\nexport { ${exports} }`, { context })
  await module.link(specifier => {
    const values = mocks[specifier] || (specifier.endsWith('.vue') ? { default: {} } : null)
    assert.ok(values, specifier)
    return new vm.SyntheticModule(Object.keys(values), function () { for (const [key, value] of Object.entries(values)) this.setExport(key, value) }, { context })
  })
  await module.evaluate()
  return module.namespace
}
async function resources(overrides = {}) {
  const effects = { calls: [], summaries: [] }
  const api = {
    createRoom: async data => { effects.calls.push(['createRoom', data]); return { data: room() } },
    updateRoom: async (id, data) => { effects.calls.push(['updateRoom', id, data]); return { data: room() } },
    listRoomSeats: async () => { effects.calls.push(['seats']); return { data: { room: room(), seats: [seat()] } } },
    createSeat: async (id, data) => { effects.calls.push(['createSeat', id, data]); return { data: seat() } },
    updateSeat: async (id, data) => { effects.calls.push(['updateSeat', id, data]); return { data: seat() } },
    deleteRoom: async () => ({}), deleteSeat: async () => ({}), batchGenSeats: async () => ({ data: { created: 1 } }), ...overrides
  }
  const page = await script('../src/components/admin/ResourceManagement.vue', {
    vue: { ref, computed, onMounted() {} },
    '../../api/room': { getRooms: overrides.getRooms || (async () => { effects.calls.push(['rooms']); return { data: [room()] } }) },
    '../../api/admin': api,
    './userForm.js': { operationReason },
    '../ui/feedback': { message: { success() {} } }
  }, 'loadRooms, openCreate, openEdit, saveRoom, roomForm, roomDialog, formError, openSeats, editSeat, saveSeat, seatForm, seatDialog, seatRoom, removeResource, deleteForm, saveDelete, deleteDialog, changeConfirm, writeNotice', { defineEmits: () => (_, value) => effects.summaries.push(value) })
  page.changeConfirm.value = {run:overrides.confirmChange || (async proposal=>{effects.calls.push(['change',proposal]);return{operation_id:'test-operation',cancelled_count:0}})}
  return { ...page, effects }
}
test('resource summary uses actual seat counts and capacity rather than layout multiplication', async () => {
  const page = await resources()
  await page.loadRooms()
  assert.equal(page.effects.summaries[0].seats, 2)
  assert.equal(page.effects.summaries[0].capacity, 1)
})
test('room editing sends expected version, string B1 floor and explicit public reason', async () => {
  const page = await resources()
  page.openEdit(room())
  page.roomForm.value.reason = '更新公开位置'
  await page.saveRoom()
  const call = page.effects.calls[0]
  assert.equal(call[0], 'change');assert.equal(call[1].change_type,'room.update');assert.equal(call[1].target_id,1)
  assert.equal(call[1].payload.floor, 'B1')
  assert.equal(call[1].payload.version, 3)
  assert.equal(call[1].payload.reason, '更新公开位置')
  assert.equal('open_time' in call[1].payload, false)
  assert.equal('close_time' in call[1].payload, false)
  assert.equal(page.roomDialog.value, false)
})
test('single seat creation uses room version and editing uses seat version; both refresh real resource data', async () => {
  const page = await resources()
  await page.openSeats(room())
  page.editSeat()
  page.seatForm.value.seat_no = 'A2'
  page.seatForm.value.reason = '新增模拟座位'
  await page.saveSeat()
  const created = page.effects.calls.find(call => call[0] === 'createSeat')
  assert.equal(created[2].version, 3)
  assert.ok(page.effects.calls.some(call => call[0] === 'rooms'))
  page.editSeat(seat())
  page.seatForm.value.reason = '更新模拟座位'
  await page.saveSeat()
  assert.equal(page.effects.calls.find(call => call[0] === 'change' && call[1].change_type === 'seat.update')[1].payload.version, 5)
})
test('resource conflict preserves attempted input and instructs refresh rather than silently replacing version', async () => {
  const page = await resources({ confirmChange: async () => { throw { status: 409, message: '版本过期' } } })
  page.openEdit(room())
  page.roomForm.value.name = '希望更新的名称'
  page.roomForm.value.reason = '更新位置'
  await page.saveRoom()
  assert.equal(page.roomDialog.value, true)
  assert.equal(page.roomForm.value.name, '希望更新的名称')
  assert.equal(page.roomForm.value.version, 3)
  assert.match(page.formError.value, /刷新资源/)
})
test('historical resource deletion failure keeps operation reason in dialog', async () => {
  const page = await resources({ deleteSeat: async () => { throw { status: 409, message: '资源有历史记录' } } })
  page.removeResource(seat(), true)
  page.deleteForm.value.reason = '误建座位核查'
  await page.saveDelete()
  assert.equal(page.deleteDialog.value, true)
  assert.equal(page.deleteForm.value.reason, '误建座位核查')
  assert.match(page.formError.value, /历史记录/)
})
async function booking() {
  const calls = []
  const page = await script('../src/views/BookingView.vue', {
    vue: { ref, computed, watch() {}, onMounted() {}, onBeforeUnmount() {} },
    '../components/seat3d/seatPresentation': { seatType: () => '普通桌', zoneName: { regular: '普通区' }, getWindowSides: () => ({}) },
    '../components/ui/feedback': { message: { info() {}, warning() {}, success() {} }, confirmDialog: async () => {} },
    '../api/room': { getRooms: async () => ({ data: [] }), getSeatMap: async () => ({ data: { room: room(), seats: [seat()] } }) },
    '../api/availability': {getAvailability:async()=>({data:{}})},
    '../composables/restrictionForm.js':{bookingRestrictionDetails},
    '../composables/availabilitySelection.js':{windowKey,validateWindowSlot,queryRequiredMinutes,shortfallText},
    '../api/reservation': { createReservation: async () => { calls.push('create') }, getBookingPolicy: async () => ({data:{}}) },
    '../composables/bookingPolicy.js': {validateSlot,minuteValue,minuteText,serverStartMinute}
  }, 'room, rooms, seats, policy, date, start, end, locationFilter, filteredRooms, selected, onSeatClick, confirmBooking, seatStats')
  page.policy.value = {server_now:'2026-11-01T08:00:00+08:00',campus_today:'2026-11-01',dates:['2026-11-01','2026-11-02','2026-11-03'],campus_zone:'Asia/Shanghai',min_duration_minutes:30}
  page.date.value='2026-11-01';page.start.value='09:00';page.end.value='12:00'
  return { ...page, calls }
}
test('disabled room cannot submit through manual selection or direct confirmation', async () => {
  const page = await booking()
  page.room.value = { ...room(), enabled: false }
  page.onSeatClick(seat())
  assert.equal(page.selected.value, null)
  page.selected.value = seat()
  await page.confirmBooking()
  assert.deepEqual(page.calls, [])
})
test('public position filters preserve B1 and do not guess unknown metadata', async () => {
  const page = await booking()
  page.rooms.value = [room(), { ...room(), id: 2, building: null, floor: null, location: '旧位置' }]
  page.locationFilter.value = { building: '教学', floor: 'B1', location: '东' }
  assert.equal(page.filteredRooms.value.length, 1)
  assert.equal(page.filteredRooms.value[0].id, 1)
  page.locationFilter.value.floor = '3F'
  assert.equal(page.filteredRooms.value.length, 0)
})
test('resource DELETE API retains version and reason in JSON body', async () => {
  const calls = []
  const context = vm.createContext({})
  const module = new vm.SourceTextModule(await readFile(new URL('../src/api/admin.js', import.meta.url), 'utf8'), { context })
  await module.link(() => new vm.SyntheticModule(['default'], function () { this.setExport('default', { delete: (...args) => { calls.push(args) } }) }, { context }))
  await module.evaluate()
  module.namespace.deleteRoom(1, { version: 3, reason: '空间误建' })
  module.namespace.deleteSeat(2, { version: 5, reason: '座位误建' })
  assert.equal(calls[0][0], '/admin/rooms/1')
  assert.equal(calls[0][1].data.version, 3)
  assert.equal(calls[1][1].data.reason, '座位误建')
})

test('available seat outside authoritative opening window cannot bypass manual submission', async () => {
  const page = await booking()
  page.room.value = room()
  const closed = { ...seat(), available_windows: [], closed_reasons: ['临时停用'] }
  page.seats.value = [closed]
  page.onSeatClick(closed)
  assert.equal(page.selected.value, null)
  page.selected.value = closed
  await page.confirmBooking()
  assert.deepEqual(page.calls, [])
})

test('cancelled resource preview keeps the room form and reason and never closes it as saved',async()=>{
  const page=await resources({confirmChange:async()=>false})
  page.openEdit(room());page.roomForm.value.reason='工程模拟：取消预览';await page.saveRoom()
  assert.equal(page.roomDialog.value,true);assert.equal(page.roomForm.value.reason,'工程模拟：取消预览');assert.equal(page.effects.calls.length,0)
})
test('acknowledged resource write followed by reload failure is explicit and never retried as a new operation',async()=>{
  let changes=0
  const page=await resources({confirmChange:async()=>{changes++;return{cancelled_count:1}},getRooms:async()=>{throw new Error('读取失败')}})
  page.openEdit(room());page.roomForm.value.reason='工程模拟：资源维护';await page.saveRoom()
  assert.equal(changes,1);assert.equal(page.roomDialog.value,false);assert.match(page.writeNotice.value,/已确认写入/);assert.match(page.writeNotice.value,/不要重复提交/)
})
