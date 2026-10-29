import test from 'node:test'
import assert from 'node:assert/strict'
import vm from 'node:vm'
import { readFile } from 'node:fs/promises'
import { ref, computed } from 'vue'
import { operationReason } from '../src/components/admin/userForm.js'
import { seatState } from '../src/components/seat3d/seatPresentation.js'

async function schedule(overrides = {}) {
  const calls = []
  const api = {
    getRoomSchedule: async () => ({ data: { room_version: 7, weekly: [{ weekday: 1, windows: [{start:'08:00',end:'12:00'}] }], exceptions: [] } }),
    listUnavailability: async () => ({ data: { room_version: 7, items: [{ id: 20, seat_id: null, start_at:'2026-11-01T22:00',end_at:'2026-11-02T09:00',reason:'教学停用',cancelled:true }] } }),
    listRoomSeats: async () => ({ data: { room: { version: 7 }, seats: [{ id: 2, seat_no: 'A1' }] } }),
    saveRoomSchedule: async (id, body) => { calls.push(['schedule', id, body]) },
    deleteDateException: async (id, body) => { calls.push(['deleteException', id, body]) },
    createUnavailability: async (id, body) => { calls.push(['createBlock', id, body]) },
    updateUnavailability: async (id, blockId, body) => { calls.push(['updateBlock', id, blockId, body]) },
    cancelUnavailability: async (id, blockId, body) => { calls.push(['cancelBlock', id, blockId, body]) }, ...overrides
  }
  const context = vm.createContext({ defineProps: () => ({ roomId: 1 }), defineEmits: () => () => {} })
  const source = (await readFile(new URL('../src/components/admin/OpeningManagement.vue', import.meta.url), 'utf8')).split('<script setup>')[1].split('</script>')[0]
  const module = new vm.SourceTextModule(source + '\nexport { loadAll, mode, weekday, date, windows, scheduleReason, saveSchedule, formError, error, version, editBlock, blockId, blockForm, saveBlock, blocks, openCancellation, cancellation, cancelReason, cancelBlock }', { context })
  const mocks = { vue: { ref, computed, onMounted() {} }, '../../api/admin': api, './userForm.js': { operationReason } }
  await module.link(specifier => {
    const values = mocks[specifier] || (specifier.endsWith('.vue') ? { default: {} } : null)
    assert.ok(values, specifier)
    return new vm.SyntheticModule(Object.keys(values), function () { for (const [key, value] of Object.entries(values)) this.setExport(key, value) }, { context })
  })
  await module.evaluate()
  const page = module.namespace
  await page.loadAll()
  return { ...page, calls }
}
test('date exception with empty windows explicitly closes day; deleting it uses version and reason', async () => {
  const page = await schedule()
  page.mode.value = 'date'; page.date.value = '2026-11-01'; page.windows.value = []
  page.scheduleReason.value = '教学闭馆'
  await page.saveSchedule(false)
  assert.equal(page.calls[0][2].date, '2026-11-01')
  assert.equal(page.calls[0][2].windows.length, 0)
  assert.equal('weekday' in page.calls[0][2], false)
  assert.equal(page.calls[0][2].version, 7)
  page.scheduleReason.value = '恢复星期安排'
  await page.saveSchedule(true)
  assert.equal(page.calls[1][0], 'deleteException')
  assert.equal(page.calls[1][2].reason, '恢复星期安排')
})
test('weekly input sends segments unchanged for backend normalization and displays normalized response', async () => {
  const page = await schedule()
  page.windows.value = [{start:'08:00',end:'10:00'},{start:'10:00',end:'12:00'}]
  page.scheduleReason.value = '分段安排'
  await page.saveSchedule(false)
  assert.equal(page.calls[0][2].windows.length, 2)
  assert.equal(page.windows.value.length, 1)
  assert.equal(page.windows.value[0].end, '12:00')
})
test('schedule conflict keeps requested segments and reason without inventing a successful change', async () => {
  const page = await schedule({ saveRoomSchedule: async () => { throw {status:409,message:'开放窗口重叠'} } })
  page.windows.value = [{start:'08:00',end:'12:00'},{start:'10:00',end:'24:00'}]
  page.scheduleReason.value = '测试重叠'
  await page.saveSchedule(false)
  assert.equal(page.windows.value.length, 2)
  assert.equal(page.windows.value[1].end, '24:00')
  assert.equal(page.scheduleReason.value, '测试重叠')
  assert.match(page.formError.value, /重叠/)
})
test('cross-day seat unavailability remains campus-local and cancelled records remain visible', async () => {
  const page = await schedule()
  page.blockForm.value = {seat_id:2,start_at:'2026-11-01T22:00',end_at:'2026-11-02T09:00',reason:'跨日设备维护'}
  await page.saveBlock()
  const call = page.calls[0]
  assert.equal(call[0], 'createBlock')
  assert.equal(call[2].seat_id, 2)
  assert.equal(call[2].start_at, '2026-11-01T22:00')
  assert.equal(call[2].end_at, '2026-11-02T09:00')
  assert.equal(call[2].version, 7)
  assert.equal(page.blocks.value[0].cancelled, true)
  assert.equal(page.blockForm.value.start_at, '')
})
test('cancelling one unavailability submits only its identifier and preserves other records', async () => {
  const page = await schedule()
  page.openCancellation({id:99})
  page.cancelReason.value = '只恢复该条记录'
  await page.cancelBlock()
  assert.equal(page.calls[0][0], 'cancelBlock')
  assert.equal(page.calls[0][2], 99)
  assert.equal(page.calls[0][3].version, 7)
  assert.equal(page.blocks.value.length, 1)
})
test('mixed management read versions are rejected rather than blessing stale edits', async () => {
  const page = await schedule({ listUnavailability: async () => ({ data:{room_version:8,items:[]} }) })
  assert.match(page.error.value, /读取期间资源版本已变化/)
  assert.equal(page.version.value, null)
})
test('3D seat presentation marks an otherwise available seat closed by opening policy as disabled', () => {
  assert.equal(seatState({id:1,status:'available',bookable_for_slot:false,occupied:false}, null), 'disabled')
})
test('opening API adapters preserve selector exclusivity and cancellation reason in body', async () => {
  const calls = []
  const context = vm.createContext({})
  const module = new vm.SourceTextModule(await readFile(new URL('../src/api/admin.js', import.meta.url), 'utf8'), { context })
  await module.link(() => new vm.SyntheticModule(['default'], function () { this.setExport('default', { put: (...args) => calls.push(['put',...args]), delete: (...args) => calls.push(['delete',...args]) }) }, { context }))
  await module.evaluate()
  module.namespace.saveRoomSchedule(1, {weekday:1,windows:[],version:7,reason:'闭馆'})
  module.namespace.deleteDateException(1, {date:'2026-11-01',version:7,reason:'恢复'})
  module.namespace.cancelUnavailability(1, 9, {version:7,reason:'撤销本条'})
  assert.equal(calls[0][1], '/admin/rooms/1/opening-windows')
  assert.equal('date' in calls[0][2], false)
  assert.equal(calls[1][2].data.date, '2026-11-01')
  assert.equal(calls[2][1], '/admin/rooms/1/unavailability/9')
  assert.equal(calls[2][2].data.reason, '撤销本条')
})
