export const restrictionStatuses = {active:'有效',expired:'已到期',released:'已解除'}
export function restrictionPayload(form, releasing=false) {
  const reason=String(form.reason||'').trim(),verification_note=String(form.verification_note||'').trim()
  if(!reason||reason.length>1000)throw new Error('请填写1至1000字的公开原因')
  if(!verification_note||verification_note.length>2000)throw new Error('请填写1至2000字的管理私有核实依据')
  if(form.facts_confirmed!==true)throw new Error('请据实确认已核实本次管理操作依据')
  const body={reason,verification_note,facts_confirmed:true}
  if(!releasing){
    if(!Number.isSafeInteger(form.user_id)||form.user_id<=0)throw new Error('请明确选择真实普通用户账号')
    const local=String(form.ends_at||'')
    if(!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(?::\d{2}(?:\.\d{1,6})?)?$/.test(local)||!Number.isFinite(Date.parse(local+'+08:00')))throw new Error('请填写校园时间的有效截止时刻，最多保留六位小数秒')
    body.user_id=form.user_id;body.ends_at=local+'+08:00'
  }
  return body
}
export function validEligibility(value) {
  return typeof value?.can_book==='boolean'&&[null,'role','disabled','restrictions'].includes(value.blocked_by)&&Array.isArray(value.active_restrictions)&&typeof value.server_now==='string'
}
export function eligibilityText(value) {
  if(!value)return '预约资格尚未读取，不能据此判断是否可预约'
  if(value.can_book)return '服务端当前资格核对允许发起新预约，提交时仍须检查资源与时段条件'
  return ({role:'当前账号角色不能发起普通预约',disabled:'当前账号已停用，不能发起新预约',restrictions:'当前仍有有效预约限制，不能发起新预约'})[value.blocked_by]||'当前不能发起新预约，请刷新核对'
}
export function bookingRestrictionDetails(failure) {
  return failure?.status===403&&failure.details?.code==='booking_restricted'&&Array.isArray(failure.details.active_restrictions)?failure.details:null
}

export function offsetInstant(value) {
  const match=typeof value==='string'&&value.match(/^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2})(?::(\d{2})(?:\.(\d{1,6}))?)?([+-]\d{2}:\d{2})$/)
  if(!match)return null
  const whole=Date.parse(`${match[1]}:${match[2]||'00'}${match[4]}`)
  return Number.isFinite(whole)?BigInt(whole)*1000000n+BigInt((match[3]||'').padEnd(9,'0')):null
}
