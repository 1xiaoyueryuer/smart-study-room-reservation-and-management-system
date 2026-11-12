export const complaintTypes = { occupation: '预约与座位问题', service: '设施报修与服务反馈' }
export const serviceKinds = { facility: '设施报修', other: '其他服务问题' }
export const complaintStatuses = { submitted: '待受理', processing: '处理中', awaiting_confirmation: '待确认反馈', closed: '已结案', withdrawn: '已撤回' }
export const verificationOutcomes = { cooperated: '已配合纠正并提醒（不计违规）', identity_pending: '身份待核', unsubstantiated: '未查实', verified_refusal: '核实拒不配合（核实动作不自动登记）', service_verified: '服务事实已核实' }
export const reservationStatuses = { pending: '待签到', checked_in: '使用中', completed: '已结束', cancelled: '已取消', unused_ended: '未使用结束', violation: '旧违约状态（历史）', temp_leave: '旧暂离状态（历史）' }

export const faultTypes = ['照明/电气', '空调通风', '供水排水', '门窗锁具', '桌椅家具', '网络或设备', '其他设施']
export const impactScopes = [
  { value: 'minor', label: '外观或轻微异常，不影响使用' },
  { value: 'inconvenient', label: '有轻微不便，但仍可安全使用' },
  { value: 'one_seat', label: '一个座位或单台设施不能使用' },
  { value: 'multiple', label: '多个座位、较大区域或共享设施受影响' }
]
export const safetyRisks = [{ value: '', label: '请选择安全风险' }, { value: 'none', label: '无安全风险' }, { value: 'yes', label: '存在安全风险（直接按5级）' }]
export const otherRequestTypes = ['环境卫生', '噪音秩序', '服务态度', '其他服务问题']
export const serviceTopics = { 环境卫生: '环境卫生', 噪音秩序: '噪音秩序' }

export function repairPriority({ faultType, impactScope, safetyRisk, unsafeArea, rules = [] }) {
  if (safetyRisk === 'yes' || unsafeArea === 'yes') return 5
  const fault = String(faultType || '').trim().normalize('NFC').toLocaleLowerCase()
  const scope = String(impactScope || '').trim().normalize('NFC').toLocaleLowerCase()
  const match = rules.find(rule => rule.enabled !== false && String(rule.fault_type || '').trim().normalize('NFC').toLocaleLowerCase() === fault && String(rule.impact_scope || '').trim().normalize('NFC').toLocaleLowerCase() === scope)
  return Number.isInteger(match?.priority) && match.priority >= 1 && match.priority <= 4 ? match.priority : null
}
export function repairPriorityText(level) { return level === 5 ? '5级 · 存在安全风险或区域无法安全使用' : Number.isInteger(level) ? `${level}级 · 当前映射命中` : '该组合映射未配置；由管理员受理时核定，不自动猜测级别' }
export function repairSummary({ faultType, impactScope, safetyRisk }) {
  const safety = safetyRisks.find(item => item.value === safetyRisk)?.label || '未选择安全风险'
  return `【设施报修】故障类型：${faultType || '未选择'}；影响范围：${impactScope || '未选择'}；安全风险：${safety}。`
}
export function parseRepair(description) {
  const text = String(description || '')
  if (!text.startsWith('【设施报修】')) return null
  const line = text.split(/\r?\n/)[0]
  return { faultType: line.match(/故障类型：([^；]+)/)?.[1] || '', impact: line.match(/影响范围：([^；]+)/)?.[1] || '', safety: line.match(/安全风险：([^。]+)/)?.[1] || '', priority: Number(line.match(/初始优先级：([1-5])级/)?.[1] || 0) || null }
}
export function complaintDisplayType(record) { return record?.type === 'service' ? ({ facility_repair: '设施报修', other_service: '其他服务问题' }[record.service_kind] || '历史服务问题（类别未留存）') : complaintTypes[record?.type] || record?.type || '投诉' }
export function impactScopeText(value) { return impactScopes.find(item => item.value === value)?.label || value || '未填写' }
export function complaintNumber(id) { const value=Number(id);return Number.isSafeInteger(value)&&value>0?`SQ-${String(value).padStart(6,'0')}`:'SQ-待生成' }
export function complaintCategory(type, description) { if (type === 'occupation') return '预约与座位问题'; if (type === 'service') return parseRepair(description) ? '设施报修' : '其他服务问题'; return complaintTypes[type] || type }
export function positiveId(value) { if (value === null || value === undefined || value === '') return null; if (!/^[0-9]+$/.test(String(value)) || !Number.isSafeInteger(Number(value)) || Number(value) <= 0) throw new Error('关联编号无效，请重新选择'); return Number(value) }

/** 将同学前端的展示枚举转换为当前后端V1.1正式字段；不改变页面布局。 */
export function complaintPayload({ type, description, reservationId, roomId, items, context, serviceKind, repair, unsafeArea, otherRequestType }) {
  if (!Object.hasOwn(complaintTypes, type)) throw new Error('请选择求助或投诉类型')
  const reservation_id = positiveId(reservationId), room_id = positiveId(roomId), text = String(description || '').trim()
  if (!text || text.length > 2000) throw new Error('请填写1至2000字的情况说明')
  if (type === 'occupation' && !reservation_id) throw new Error('预约与座位问题必须关联本人预约')
  if (type === 'service' && !room_id) throw new Error('设施报修与其他服务问题必须选择所属空间')
  if (type === 'service' && reservation_id) throw new Error('设施报修与其他服务问题只关联空间，不关联预约')
  if (reservation_id && context?.id !== reservation_id) throw new Error('关联预约尚未核对成功，请重试读取')
  if (items.length > 3 || items.some(item => item.phase !== 'uploaded' || !item.metadata?.id)) throw new Error('请先成功上传全部已选照片，或明确移除失败照片后再提交')
  const attachment_ids = items.map(item => item.metadata.id)
  if (new Set(attachment_ids).size !== attachment_ids.length) throw new Error('不能重复关联同一份材料')
  if (type === 'occupation') return { type, description: text, attachment_ids, reservation_id }
  if (serviceKind === 'facility') {
    if (!String(repair?.faultType || '').trim() || String(repair.faultType).trim().length > 100 || !String(repair?.impactScope || '').trim() || String(repair.impactScope).trim().length > 100 || !['none','yes'].includes(repair?.safetyRisk) || !['yes','no'].includes(unsafeArea)) throw new Error('请完整填写设施报修字段')
    const priority = repairPriority({ ...repair, unsafeArea })
    const note = priority === 5 ? `安全规则初始优先级：5级（${repairPriorityText(5)}）。` : '初始优先级以服务端返回为准；未命中已配置映射时由管理员受理核定。'
    const payloadDescription = `${repairSummary(repair)}${note}\n具体情况：\n${text}`
    if (payloadDescription.length > 2000) throw new Error('报修信息与说明总长度不能超过2000字')
    return { type, description: payloadDescription, attachment_ids, room_id, service_kind: 'facility_repair', fault_type: repair.faultType.trim(), impact_scope: repair.impactScope.trim(), safety_risk: repair.safetyRisk === 'yes', unsafe_area: unsafeArea === 'yes' }
  }
  if (serviceKind === 'other') {
    if (items.length) throw new Error('其他服务问题不接收设施报修现场照片，请移除所选材料')
    if (!String(otherRequestType || '').trim() || String(otherRequestType).trim().length > 100) throw new Error('请填写不超过100字的诉求类型')
    const payloadDescription = `【其他服务问题】诉求类型：${otherRequestType.trim()}。\n具体情况：\n${text}`
    if (payloadDescription.length > 2000) throw new Error('服务问题信息与说明总长度不能超过2000字')
    return { type, description: payloadDescription, attachment_ids: [], room_id, service_kind: 'other_service', service_topic: otherRequestType.trim() }
  }
  throw new Error('请选择具体服务类型')
}

export { validateEvidenceFile } from './evidenceFile.js'
export function verificationPayload(form, detail) {
  const outcome = form.outcome, public_note = form.public_note.trim(), private_note = form.private_note.trim()
  if (!Object.hasOwn(verificationOutcomes, outcome)) throw new Error('请选择核实结果')
  if (!public_note || public_note.length > 1000 || !private_note || private_note.length > 2000) throw new Error('公开说明须1至1000字，私有核实事实须1至2000字')
  if (!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(?::\d{2}(?:\.\d{1,6})?)?$/.test(form.occurred_at)) throw new Error('请填写事实发生时刻（校园时间，精度不超过微秒）')
  const occurred_at = form.occurred_at + (form.occurred_at.length === 16 ? ':00' : '') + '+08:00', subject_user_id = positiveId(form.subject_user_id), identity_basis = form.identity_basis.trim(), occupancy = ['cooperated', 'verified_refusal'].includes(outcome)
  if (occupancy && (detail.type !== 'occupation' || !detail.reservation_id || !form.facts_confirmed || !form.valid_reservation_confirmed)) throw new Error('占座处置须有关联预约，并由管理员明确核实事实及发生时刻的有效预约使用权')
  if (occupancy && subject_user_id === detail.reporter?.id) throw new Error('投诉人不能被当作本次占座处置对象，请核对真实身份')
  if (outcome === 'identity_pending' && (subject_user_id || form.identity_confirmed)) throw new Error('身份待核结果不得关联账号或声称身份已核实')
  if (subject_user_id && (!form.identity_confirmed || !identity_basis || identity_basis.length > 1000)) throw new Error('关联账号须明确核实身份并填写1至1000字身份依据')
  if (outcome === 'verified_refusal' && !subject_user_id) throw new Error('核实拒不配合必须明确关联已核实身份的账号')
  if (outcome === 'service_verified' && (detail.type !== 'service' || !form.facts_confirmed)) throw new Error('服务事实核实仅用于一般服务投诉，且须明确核实事实')
  return { outcome, occurred_at, public_note, private_note, subject_user_id, identity_confirmed: !!form.identity_confirmed, identity_basis: subject_user_id ? identity_basis : null, facts_confirmed: !!form.facts_confirmed, valid_reservation_confirmed: !!form.valid_reservation_confirmed }
}
export function feedbackPayload(form) { const result = form.result.trim(), basis = form.basis.trim(); if (!result || result.length > 2000 || !basis || basis.length > 2000) throw new Error('正式反馈结果和公开依据均须1至2000字'); return { result, basis } }
export function serviceProgressPayload(form) { const stage = form.stage, maintenance_channel = String(form.maintenance_channel || '').trim(), public_note = String(form.public_note || '').trim(), local = String(form.handed_off_at || ''); if (!['handoff', 'progress'].includes(stage)) throw new Error('维修进度类型无效'); if (!maintenance_channel || maintenance_channel.length > 150) throw new Error('请填写1至150字的现有维修渠道名称'); if (!public_note || public_note.length > 1000) throw new Error('请填写1至1000字的公开维修进度'); if (!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(?::\d{2}(?:\.\d{1,6})?)?$/.test(local)) throw new Error('请填写交接时刻（校园时间，精度不超过微秒）'); return { stage, maintenance_channel, handed_off_at: local + (local.length === 16 ? ':00' : '') + '+08:00', public_note } }
export function ownerComplaintActions(record) { if (!record || ['closed', 'withdrawn'].includes(record.status) || !Array.isArray(record.allowed_actions)) return []; return record.allowed_actions.filter(action => ['withdraw', 'confirm', 'object'].includes(action)) }
