import test from 'node:test'
import assert from 'node:assert/strict'
import vm from 'node:vm'
import { readFile } from 'node:fs/promises'
import { userPayload, contactPayload } from '../src/components/admin/userForm.js'

const valid = () => ({ username: 'new-user', real_name: '测试用户', campus_id: 'CAMPUS-1', role: 'staff', status: 'active', phone: '', email: '', password: '123456', reason: '创建教学账号' })
test('creation requires identity and operation reason; credentials honor BCrypt byte limit', () => {
  assert.throws(() => userPayload({ ...valid(), campus_id: '' }, true), /校园编号/)
  assert.throws(() => userPayload({ ...valid(), reason: ' ' }, true), /理由/)
  assert.throws(() => userPayload({ ...valid(), password: '密'.repeat(25) }, true), /72字节/)
  assert.equal(userPayload({ ...valid(), password: '密'.repeat(24) }, true).password.length, 24)
})
test('editing preserves unknown identity and omits an empty password', () => {
  const payload = userPayload({ ...valid(), campus_id: '', password: '' }, false)
  assert.equal(payload.campus_id, null)
  assert.equal('password' in payload, false)
})
test('personal contact payload only includes phone and email, supports clearing, and rejects invalid input', () => {
  assert.deepEqual(contactPayload({ phone: '  +86 123 ', email: ' user@example.com ', role: 'admin', campus_id: 'forbidden' }), { phone: '+86 123', email: 'user@example.com' })
  assert.deepEqual(contactPayload({ phone: '', email: '' }), { phone: '', email: '' })
  assert.throws(() => contactPayload({ email: 'invalid' }), /邮箱/)
})
async function management(overrides = {}) {
  const effects = { requests: [], summaries: [], profile: null }
  const profile = { ...valid(), password: undefined, id: 2 }
  const api = {
    listUsers: async () => { effects.requests.push(['list']); return { data: [profile] } },
    createUser: async data => { effects.requests.push(['create', data]); return { data: profile } },
    updateUser: async (id, data) => { effects.requests.push(['update', id, data]); return { data: { ...profile, id } } },
    setUserStatus: async () => {}, ...overrides
  }
  const mocks = {
    vue: { ref: value => ({ value }), computed: getter => ({ get value() { return getter() } }), onMounted: () => {} },
    '../../stores/auth': { ROLE_LABELS: { student: '学生', staff: '教职工', admin: '管理员' }, useAuthStore: () => ({ user: { id: 1 }, setUser: value => { effects.profile = value } }) },
    '../../api/admin': api,
    './userForm.js': { userPayload },
    './UserImport.vue': { default: {} },
    '../ui/feedback': { message: { success() {} }, promptDialog: async () => ({ value: '测试理由' }) }
  }
  for (const name of ['SButton', 'SInput', 'SSelect', 'SDialog', 'STag', 'SEmpty', 'SBanner']) mocks[`../ui/${name}.vue`] = { default: {} }
  const context = vm.createContext({ TextEncoder, defineEmits: () => (_, value) => effects.summaries.push(value), defineExpose() {} })
  const source = (await readFile(new URL('../src/components/admin/UserManagement.vue', import.meta.url), 'utf8')).split('<script setup>')[1].split('</script>')[0]
  const module = new vm.SourceTextModule(source + '\nexport { form, editing, openCreate, openEdit, saveUser, loadUsers, dialog, formError, error }', { context })
  await module.link(specifier => {
    const exports = mocks[specifier]
    assert.ok(exports, specifier)
    return new vm.SyntheticModule(Object.keys(exports), function () { for (const [key, value] of Object.entries(exports)) this.setExport(key, value) }, { context })
  })
  await module.evaluate()
  return { ...module.namespace, effects }
}
test('successful account creation refreshes real list and count', async () => {
  const page = await management()
  page.openCreate()
  page.form.value = valid()
  await page.saveUser()
  assert.equal(page.effects.requests[0][0], 'create')
  assert.equal(page.effects.requests[1][0], 'list')
  assert.equal(page.effects.summaries[0].total, 1)
  assert.equal(page.dialog.value, false)
})
test('account conflict preserves form for correction; refresh failure remains explicit', async () => {
  const page = await management({ createUser: async () => { throw new Error('校园编号已存在') }, listUsers: async () => { throw new Error('读取失败') } })
  page.openCreate()
  page.form.value = valid()
  await page.saveUser()
  assert.equal(page.form.value.campus_id, 'CAMPUS-1')
  assert.equal(page.dialog.value, true)
  assert.equal(page.formError.value, '校园编号已存在')
  await page.loadUsers()
  assert.equal(page.error.value, '读取失败')
  assert.equal(page.effects.summaries[0], null)
})
test('editing current administrator refreshes safe profile in authentication store', async () => {
  const page = await management()
  page.openEdit({ ...valid(), id: 1 })
  page.form.value.reason = '更新姓名'
  await page.saveUser()
  assert.equal(page.effects.profile.id, 1)
  assert.equal(page.effects.requests[0][0], 'update')
  assert.equal('password' in page.effects.requests[0][2], false)
})
test('a stale list read cannot overwrite a newer refresh after import', async () => {
  const pending = []
  const page = await management({ listUsers: () => new Promise(resolve => pending.push(resolve)) })
  const older = page.loadUsers()
  const latest = page.loadUsers()
  pending[1]({ data: [{ id: 10, status: 'active' }] })
  await latest
  pending[0]({ data: [] })
  await older
  assert.equal(page.effects.summaries.length, 1)
  assert.equal(page.effects.summaries[0].total, 1)
})
