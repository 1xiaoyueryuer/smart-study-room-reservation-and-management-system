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
  const context = vm.createContext({ localStorage: { getItem: key => storage.get(key) || null }, Promise, Blob })
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

test('JSON Blob conflicts preserve structured message, code and details through the same error adapter', async()=>{
  const h=await harness()
  await assert.rejects(h.rejectResponse({response:{status:409,data:new Blob([JSON.stringify({code:'STALE_CODE_VERSION',message:'座位码版本已变更',details:{expected_version:1,current_version:2}})],{type:'application/json'})},config:{silent:true}}),error=>error.status===409&&error.code==='STALE_CODE_VERSION'&&error.message==='座位码版本已变更'&&error.details.current_version===2)
  assert.equal(h.effects.notices.length,0)
})
test('concurrent Blob 401 responses still log out and redirect exactly once', async()=>{
  const h=await harness('test-token')
  const failure=()=>({response:{status:401,data:new Blob(['{"code":"TOKEN_EXPIRED","message":"登录失效"}'],{type:'application/problem+json'})},config:{}})
  const result=await Promise.allSettled([h.rejectResponse(failure()),h.rejectResponse(failure())])
  assert.equal(result.every(item=>item.status==='rejected'&&item.reason.code==='TOKEN_EXPIRED'),true)
  assert.equal(h.effects.logouts,1);assert.equal(h.effects.routes.length,1);assert.equal(h.effects.notices.length,1)
  const skip=await harness('retained-token')
  await assert.rejects(skip.rejectResponse({...failure(),config:{silent:true,skipAuthHandling:true}}),error=>error.message==='登录失效')
  assert.equal(skip.effects.logouts,0);assert.equal(skip.effects.notices.length,0)
})
test('untyped recognizable JSON Blob decodes but invalid or binary Blob safely keeps HTTP fallback', async()=>{
  const h=await harness()
  await assert.rejects(h.rejectResponse({response:{status:403,data:new Blob(['{"message":"无座位码管理权限","code":"FORBIDDEN"}'])},config:{silent:true}}),error=>error.status===403&&error.code==='FORBIDDEN')
  for(const blob of [new Blob(['{broken'],{type:'application/json'}),new Blob([new Uint8Array([137,80,78,71])],{type:'image/png'})]) {
    await assert.rejects(h.rejectResponse({response:{status:502,data:blob},message:'Request failed with status code 502',code:'ERR_BAD_RESPONSE',config:{silent:true}}),error=>error.status===502&&error.message==='Request failed with status code 502'&&error.code==='ERR_BAD_RESPONSE'&&error.details===null)
  }
})
