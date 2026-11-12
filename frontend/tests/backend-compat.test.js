import test from 'node:test'
import assert from 'node:assert/strict'
import { complaintPayload, repairPriority } from '../src/composables/complaintForm.js'

test('repair form uses exact server mappings and never invents a non-safety level', () => {
  const input = { type: 'service', description: '实际报修情况', roomId: 8, items: [], serviceKind: 'facility', repair: { faultType: '灯具', impactScope: '局部影响', safetyRisk: 'none' }, unsafeArea: 'no' }
  const unmapped = complaintPayload(input)
  assert.equal(unmapped.service_kind, 'facility_repair')
  assert.equal(unmapped.unsafe_area, false)
  assert.match(unmapped.description, /未命中已配置映射/)
  assert.equal(repairPriority({ ...input.repair, rules: [] }), null)
  const mapped = complaintPayload(input)
  assert.equal(mapped.description.includes('初始优先级：1级'), false)
  assert.equal(repairPriority({ ...input.repair, rules: [{ fault_type: '灯具', impact_scope: '局部影响', priority: 3, enabled: true }] }), 3)
})

test('either safety condition forces level five and sends explicit booleans', () => {
  const value = complaintPayload({ type: 'service', description: '安全风险事实', roomId: 9, items: [], serviceKind: 'facility', repair: { faultType: '插座', impactScope: '单座', safetyRisk: 'none' }, unsafeArea: 'yes' })
  assert.equal(value.safety_risk, false)
  assert.equal(value.unsafe_area, true)
  assert.match(value.description, /安全规则初始优先级：5级/)
  assert.equal(repairPriority({ safetyRisk: 'yes', rules: [] }), 5)
})

test('other-service alias maps to the backend service topic contract', () => {
  const value = complaintPayload({ type: 'service', description: '卫生问题', roomId: 4, items: [], serviceKind: 'other', otherRequestType: '环境卫生' })
  assert.equal(value.service_kind, 'other_service')
  assert.equal(value.service_topic, '环境卫生')
  assert.equal(value.room_id, 4)
  assert.deepEqual(value.attachment_ids, [])
})
