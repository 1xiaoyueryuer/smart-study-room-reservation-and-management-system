import test from 'node:test'
import assert from 'node:assert/strict'
import vm from 'node:vm'
import { readFile } from 'node:fs/promises'
import { createPinia, setActivePinia } from 'pinia'
import { useAuthStore } from '../src/stores/auth.js'

async function auth(role) {
  const values = new Map()
  globalThis.localStorage = { getItem: key => values.get(key) ?? null, setItem: (key, value) => values.set(key, value), removeItem: key => values.delete(key) }
  setActivePinia(createPinia())
  const store = useAuthStore()
  store.setLogin({ token: 'test-token', user: { role, credit_score: 100 } })
  return store
}
async function sourceModule(source, mocks, extra = {}) {
  const context = vm.createContext({ console, ...extra })
  const module = new vm.SourceTextModule(source, { context })
  await module.link(specifier => {
    assert.ok(specifier in mocks, `Unmocked import: ${specifier}`)
    const exports = mocks[specifier]
    return new vm.SyntheticModule(Object.keys(exports), function () {
      for (const [name, value] of Object.entries(exports)) this.setExport(name, value)
    }, { context })
  })
  await module.evaluate()
  return module.namespace
}
async function router(store) {
  let guard, routes
  const source = await readFile(new URL('../src/router/index.js', import.meta.url), 'utf8')
  await sourceModule(source, {
    'vue-router': { createWebHistory: () => ({}), createRouter: config => { routes = config.routes; return { beforeEach: callback => { guard = callback } } } },
    '../stores/auth': { useAuthStore: () => store },
    '../config/branding': { SYSTEM_NAME: 'Test' }
  }, { document: {} })
  return { guard, route: name => routes[1].children.find(item => item.name === name), routeForPath: path => routes[1].children.find(item => item.path === path) }
}
for (const role of ['student', 'staff']) {
  test(`${role} can use the same ordinary routes and retains identity`, async () => {
    const store = await auth(role)
    assert.equal(store.isOrdinary, true)
    assert.equal(store.isAdmin, false)
    assert.equal(store.roleLabel, role === 'staff' ? '教职工' : '学生')
    const navigation = await router(store)
    for (const name of ['home', 'booking', 'mine', 'waitlist', 'notifications', 'profile']) {
      const route = navigation.route(name)
      assert.equal(navigation.guard({ ...route, fullPath: route.path }), undefined)
    }
    assert.equal(navigation.guard(navigation.route('admin')), '/')
    assert.equal(navigation.guard(navigation.route('analytics')), '/')
    assert.equal(store.isLoggedIn, true)
  })
}
test('administrator has management routes and cannot enter ordinary booking routes', async () => {
  const store = await auth('admin')
  const navigation = await router(store)
  assert.equal(store.isOrdinary, false)
  assert.equal(store.roleLabel, '管理员')
  assert.equal(navigation.guard(navigation.route('admin')), undefined)
  assert.equal(navigation.guard(navigation.route('analytics')), undefined)
  assert.equal(navigation.guard(navigation.route('booking')), '/admin')
})
test('unknown identity is removed rather than treated as an ordinary user', async () => {
  const store = await auth('unknown')
  const navigation = await router(store)
  assert.equal(navigation.guard(navigation.route('home')).name, 'login')
  assert.equal(store.isLoggedIn, false)
})
for (const role of ['student', 'staff', 'admin']) {
  test(`${role} homepage only requests management statistics for administrator`, async () => {
    const store = await auth(role)
    let mounted, statsCalls = 0
    const source = (await readFile(new URL('../src/views/HomeView.vue', import.meta.url), 'utf8')).split('<script setup>')[1].split('</script>')[0]
    const mocks = {
      vue: { ref: value => ({ value }), computed: getter => ({ get value() { return getter() } }), onMounted: callback => { mounted = callback }, onUnmounted: () => {} },
      'vue-router': { useRouter: () => ({ push() {} }) },
      '../stores/auth': { useAuthStore: () => store },
      '../stores/notification': { useNotificationStore: () => ({ unread: 0, refresh() {} }) },
      '../api/room': { getRooms: async () => ({ data: [] }) },
      '../api/stats': { getOverview: async () => { statsCalls++; return { data: { total_seats: 0, in_use_now: 0 } } } },
      '../api/credit': { getCreditOverview: async () => ({ data: { score: 100 } }) }
    }
    for (const component of ['StudyTips', 'ui/SAnimatedNumber', 'AppIcon', 'ui/SBanner', 'ui/SButton']) mocks[`../components/${component}.vue`] = { default: {} }
    const homepage = await sourceModule(source + '\nexport { shortcuts }', mocks, { setInterval: () => 1, clearInterval() {} })
    await mounted()
    assert.equal(statsCalls, role === 'admin' ? 1 : 0)
    const navigation = await router(store)
    for (const shortcut of homepage.shortcuts.value) {
      const route = navigation.routeForPath(shortcut.to)
      assert.ok(route, `Shortcut ${shortcut.title} must resolve to an existing route`)
      assert.equal(navigation.guard(route), undefined, `${role} must be able to access ${shortcut.to}`)
    }
  })
}
