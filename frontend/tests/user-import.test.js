import test from 'node:test'
import assert from 'node:assert/strict'
import vm from 'node:vm'
import { readFile } from 'node:fs/promises'
import { operationReason, validatePassword } from '../src/components/admin/userForm.js'

const file = () => new File(['campus_id,real_name,role\n202630001,测试,staff'], 'users.csv', { type: 'text/csv' })
const validPreview = () => ({ file_sha256: 'sha256-test', total: 1, valid_count: 1, invalid_count: 0, rows: [{ row_number: 2, campus_id: '202630001', username: '202630001', real_name: '测试', role: 'staff', phone: '', email: '', errors: [] }] })
async function importer(overrides = {}) {
  const effects = { confirms: 0, emitted: [] }
  const api = { userImportTemplate: async () => new Blob(['campus_id,real_name,role,phone,email']), previewUserImport: async () => ({ data: validPreview() }), confirmUserImport: async (...args) => { effects.confirms++; effects.args = args; return { data: { imported_count: 1 } } }, ...overrides }
  const mocks = {
    vue: { ref: value => ({ value }), computed: getter => ({ get value() { return getter() } }) },
    '../../api/admin': api,
    './userForm.js': { operationReason, validatePassword },
    '../../stores/auth': { ROLE_LABELS: { student: '学生', staff: '教职工', admin: '管理员' } }
  }
  for (const name of ['SButton', 'SInput', 'SBanner']) mocks[`../ui/${name}.vue`] = { default: {} }
  const context = vm.createContext({ defineEmits: () => (...args) => effects.emitted.push(args) })
  const source = (await readFile(new URL('../src/components/admin/UserImport.vue', import.meta.url), 'utf8')).split('<script setup>')[1].split('</script>')[0]
  const module = new vm.SourceTextModule(source + '\nexport { selectFile, inspectFile, confirmImport, preview, canConfirm, initialPassword, reason, error, result, unknownResult, conflictRows }', { context })
  await module.link(specifier => {
    const exports = mocks[specifier]
    assert.ok(exports, specifier)
    return new vm.SyntheticModule(Object.keys(exports), function () { for (const [key, value] of Object.entries(exports)) this.setExport(key, value) }, { context })
  })
  await module.evaluate()
  return { ...module.namespace, effects }
}
async function ready(page) {
  page.selectFile({ target: { files: [file()] } })
  await page.inspectFile()
  page.initialPassword.value = '123456'
  page.reason.value = '教学批次'
}
test('file replacement invalidates previous preview and oversized files are rejected', async () => {
  const page = await importer()
  await ready(page)
  assert.equal(page.canConfirm.value, true)
  page.selectFile({ target: { files: [file()] } })
  assert.equal(page.preview.value, null)
  assert.equal(page.canConfirm.value, false)
  assert.equal(page.initialPassword.value, '')
  page.selectFile({ target: { files: [{ size: 1024 * 1024 + 1 }] } })
  assert.match(page.error.value, /1MiB/)
})
test('rows with errors cannot be confirmed or bypassed', async () => {
  const page = await importer({ previewUserImport: async () => ({ data: { ...validPreview(), valid_count: 0, invalid_count: 1 } }) })
  await ready(page)
  await page.confirmImport()
  assert.equal(page.effects.confirms, 0)
})
test('successful confirmation binds preview hash, clears credential and triggers real list refresh', async () => {
  const page = await importer()
  await ready(page)
  await page.confirmImport()
  assert.equal(page.effects.args[1], 'sha256-test')
  assert.equal(page.effects.args[2], '123456')
  assert.equal(page.effects.args[3], '教学批次')
  assert.equal(page.initialPassword.value, '')
  assert.equal(page.effects.emitted[0][0], 'imported')
  assert.equal(page.result.value.imported_count, 1)
  assert.equal(page.canConfirm.value, false)
})
test('confirmation conflict exposes failed rows and requires a fresh preview', async () => {
  const rows = [{ row_number: 2, errors: ['校园编号已存在'] }]
  const page = await importer({ confirmUserImport: async () => { throw { status: 409, message: '请重新预检', details: { rows } } } })
  await ready(page)
  await page.confirmImport()
  assert.equal(page.conflictRows.value[0].row_number, 2)
  assert.equal(page.preview.value, null)
  assert.equal(page.reason.value, '教学批次')
  assert.equal(page.initialPassword.value, '')
})
test('timeout retains uncertain outcome, clears password, and never automatically retries', async () => {
  let attempts = 0
  const page = await importer({ confirmUserImport: async () => { attempts++; throw { status: null, code: 'ECONNABORTED' } } })
  await ready(page)
  await page.confirmImport()
  await page.confirmImport()
  assert.equal(attempts, 1)
  assert.equal(page.unknownResult.value, true)
  assert.match(page.error.value, /结果尚未确认/)
  assert.equal(page.initialPassword.value, '')
})
test('import API sends credentials only in multipart body with dedicated bounded timeout', async () => {
  const calls = []
  const request = { get: (...args) => { calls.push(['get', ...args]); return Promise.resolve(new Blob(['template'])) }, post: (...args) => { calls.push(['post', ...args]); return Promise.resolve({ data: {} }) } }
  const context = vm.createContext({ FormData })
  const source = await readFile(new URL('../src/api/admin.js', import.meta.url), 'utf8')
  const module = new vm.SourceTextModule(source, { context })
  await module.link(() => new vm.SyntheticModule(['default'], function () { this.setExport('default', request) }, { context }))
  await module.evaluate()
  await module.namespace.userImportTemplate()
  await module.namespace.previewUserImport(file())
  await module.namespace.confirmUserImport(file(), 'sha', 'secret-test-password', '教学')
  assert.equal(calls[0][2].responseType, 'blob')
  assert.equal(calls[1][2].has('initial_password'), false)
  assert.equal(calls[2][1], '/admin/users/import/confirm')
  assert.equal(calls[2][2].get('initial_password'), 'secret-test-password')
  assert.equal(calls[2][2].get('file_sha256'), 'sha')
  assert.equal(calls[2][3].timeout, 120000)
})

test('CSV record identifiers are described as logical records including the header', async () => {
  const source = await readFile(new URL('../src/components/admin/UserImport.vue', import.meta.url), 'utf8')
  assert.match(source, /记录号（含表头）/)
  assert.match(source, /字段内换行不另计记录号/)
  assert.match(source, /第\{\{ row.row_number \}\}条记录/)
  assert.doesNotMatch(source, /CSV行号|第\{\{ row.row_number \}\}行/)
})
