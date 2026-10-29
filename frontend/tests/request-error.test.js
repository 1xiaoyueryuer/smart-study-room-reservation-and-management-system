import test from 'node:test'
import assert from 'node:assert/strict'
import { requestError } from '../src/api/requestError.js'

for (const status of [401, 403, 409]) {
  test(`HTTP ${status} preserves failure contract`, () => {
    const source = { response: { status, data: { code: status, message: '操作拒绝', details: { field: 'seat_id' } } } }
    const error = requestError(source)
    assert.ok(error instanceof Error)
    assert.equal(error.message, '操作拒绝')
    assert.equal(error.status, status)
    assert.equal(error.code, status)
    assert.deepEqual(error.details, { field: 'seat_id' })
    assert.equal(error.cause, source)
  })
}
for (const code of ['ERR_NETWORK', 'ECONNABORTED']) {
  test(`${code} differs from an HTTP response`, () => {
    const error = requestError({ code, message: '连接失败' })
    assert.equal(error.status, null)
    assert.equal(error.code, code)
    assert.equal(error.details, null)
    assert.equal(error.message, '连接失败')
  })
}
