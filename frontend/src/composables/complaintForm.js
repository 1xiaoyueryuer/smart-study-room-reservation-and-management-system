export const complaintTypes = { occupation: '座位被占求助', service: '一般服务投诉' }
export const complaintStatuses = { submitted:'待受理', processing:'处理中',awaiting_confirmation:'待确认反馈',closed:'已结案',withdrawn:'已撤回' }
export const verificationOutcomes = { cooperated:'已配合纠正并提醒（不计违规）',identity_pending:'身份待核',unsubstantiated:'未查实',verified_refusal:'核实拒不配合（核实动作不自动登记）',service_verified:'服务事实已核实' }
export const reservationStatuses = { pending:'待签到',checked_in:'使用中',completed:'已结束',cancelled:'已取消',unused_ended:'未使用结束',violation:'旧违约状态（历史）',temp_leave:'旧暂离状态（历史）' }
export function positiveId(value) {
  if (value === null || value === undefined || value === '') return null
  if (!/^[0-9]+$/.test(String(value)) || !Number.isSafeInteger(Number(value)) || Number(value) <= 0) throw new Error('关联编号无效，请重新选择')
  return Number(value)
}
export function complaintPayload({ type, description, reservationId, roomId, items, context }) {
  if (!Object.hasOwn(complaintTypes, type)) throw new Error('请选择求助或投诉类型')
  const text = description.trim()
  if (!text || text.length > 2000) throw new Error('请填写1至2000字的情况说明')
  const reservation_id = positiveId(reservationId), room_id = positiveId(roomId)
  if (type === 'occupation' && !reservation_id) throw new Error('座位被占求助必须关联本人预约，历史预约也可选择')
  if (reservation_id && context?.id !== reservation_id) throw new Error('关联预约尚未核对成功，请重试读取')
  if (items.length > 3 || items.some(item => item.phase !== 'uploaded' || !item.metadata?.id)) throw new Error('请先成功上传全部已选照片，或明确移除失败照片后再提交')
  const attachment_ids = items.map(item => item.metadata.id)
  if (new Set(attachment_ids).size !== attachment_ids.length) throw new Error('不能重复关联同一份材料')
  return { type, description:text, attachment_ids, ...(reservation_id ? {reservation_id} : room_id ? {room_id} : {}) }
}
export { validateEvidenceFile } from './evidenceFile.js'

export function verificationPayload(form, detail) {
  const outcome = form.outcome
  if (!Object.hasOwn(verificationOutcomes,outcome)) throw new Error('请选择核实结果')
  const public_note = form.public_note.trim(), private_note = form.private_note.trim()
  if (!public_note || public_note.length > 1000 || !private_note || private_note.length > 2000) throw new Error('公开说明须1至1000字，私有核实事实须1至2000字')
  if (!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(?::\d{2}(?:\.\d{1,6})?)?$/.test(form.occurred_at)) throw new Error('请填写事实发生时刻（校园时间，精度不超过微秒）')
  const occurred_at = form.occurred_at + (form.occurred_at.length === 16 ? ':00' : '') + '+08:00'
  const subject_user_id = positiveId(form.subject_user_id)
  const identity_basis = form.identity_basis.trim()
  const occupancy = ['cooperated','verified_refusal'].includes(outcome)
  if (occupancy && (detail.type !== 'occupation' || !detail.reservation_id || !form.facts_confirmed || !form.valid_reservation_confirmed)) throw new Error('占座处置须有关联预约，并由管理员明确核实事实及发生时刻的有效预约使用权')
  if (occupancy && subject_user_id === detail.reporter?.id) throw new Error('投诉人不能被当作本次占座处置对象，请核对真实身份')
  if (outcome === 'identity_pending' && (subject_user_id || form.identity_confirmed)) throw new Error('身份待核结果不得关联账号或声称身份已核实')
  if (subject_user_id && (!form.identity_confirmed || !identity_basis || identity_basis.length > 1000)) throw new Error('关联账号须明确核实身份并填写1至1000字身份依据，不能仅凭账号选择认定身份')
  if (outcome === 'verified_refusal' && !subject_user_id) throw new Error('核实拒不配合必须明确关联已核实身份的账号')
  if (outcome === 'service_verified' && (detail.type !== 'service' || !form.facts_confirmed)) throw new Error('服务事实核实仅用于一般服务投诉，且须明确核实事实')
  return {outcome,occurred_at,public_note,private_note,subject_user_id,identity_confirmed:!!form.identity_confirmed,identity_basis:subject_user_id?identity_basis:null,facts_confirmed:!!form.facts_confirmed,valid_reservation_confirmed:!!form.valid_reservation_confirmed}
}

export function feedbackPayload(form) {
  const result = form.result.trim(), basis = form.basis.trim()
  if (!result || result.length > 2000 || !basis || basis.length > 2000) throw new Error('正式反馈结果和公开依据均须1至2000字，请勿复制管理私有核实记录')
  return {result,basis}
}
export function ownerComplaintActions(record) {
  if (!record || ['closed','withdrawn'].includes(record.status) || !Array.isArray(record.allowed_actions)) return []
  return record.allowed_actions.filter(action=>['withdraw','confirm','object'].includes(action))
}
