import test from 'node:test'
import assert from 'node:assert/strict'
import vm from 'node:vm'
import { readFile } from 'node:fs/promises'
import { requestError } from '../src/api/requestError.js'

async function harness(token = '') {
  const effects = { notices: [], routes: [], logouts: 0 }
  const storage = new Map(token ? [['token', token]] : [])
  let rejectResponse, requestConfig
  const instance = { interceptors: {
    request: { use(fn) { requestConfig = fn } },
    response: { use(ok, fail) { rejectResponse = fail; effects.success = ok } }
  } }
  const mocks = {
    axios: { default: { create: () => instance } },
    './requestError.js': { requestError },
    '../components/ui/feedback': { message: { error: value => effects.notices.push(value) } },
    '../router': { default: { currentRoute: { value: { fullPath: '/mine' } }, push: route => effects.routes.push(route) } },
    '../stores/auth': { useAuthStore: () => ({ logout() { effects.logouts++; storage.delete('token') } }) }
  }
  const context = vm.createContext({ localStorage: { getItem: key => storage.get(key) || null }, Promise })
  const source = await readFile(new URL('../src/api/request.js', import.meta.url), 'utf8')
  const module = new vm.SourceTextModule(source, { context })
  await module.link(specifier => {
    const exports = mocks[specifier]
    return new vm.SyntheticModule(Object.keys(exports), function () {
      for (const [key, value] of Object.entries(exports)) this.setExport(key, value)
    }, { context })
  })
  await module.evaluate()
  return { effects, rejectResponse, requestConfig }
}

test('401 logs out once, redirects, and remains a rejected error', async () => {
  const h = await harness('test-token')
  assert.equal(h.requestConfig({ headers: {} }).headers.Authorization, 'Bearer test-token')
  await assert.rejects(h.rejectResponse({ response: { status: 401, data: { message: '过期' } }, config: {} }), e => e.status === 401)
  assert.equal(h.effects.logouts, 1)
  assert.equal(h.effects.routes[0].name, 'login')
  assert.deepEqual(h.effects.notices, ['登录已过期，请重新登录'])
})
test('silent and skipAuthHandling preserve login failure for caller', async () => {
  const h = await harness('test-token')
  await assert.rejects(h.rejectResponse({ response: { status: 401, data: { code: 401, message: '口令错误' } }, config: { silent: true, skipAuthHandling: true } }), e => e.status === 401 && e.message === '口令错误')
  assert.equal(h.effects.logouts, 0)
  assert.equal(h.effects.notices.length, 0)
})
test('conflict prompts once; silent permission failure still rejects', async () => {
  const h = await harness()
  await assert.rejects(h.rejectResponse({ response: { status: 409, data: { message: '冲突', details: { seat_id: 1 } } }, config: {} }), e => e.status === 409 && e.details.seat_id === 1)
  await assert.rejects(h.rejectResponse({ response: { status: 403 }, config: { silent: true } }), e => e.status === 403)
  assert.deepEqual(h.effects.notices, ['冲突'])
  assert.equal(h.effects.success({ data: { code: 0 } }).code, 0)
})
