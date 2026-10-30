import {offsetInstant} from './restrictionForm.js'
const localInstant=value=>offsetInstant(typeof value==='string'&&!/(?:Z|[+-]\d\d:\d\d)$/.test(value)?value+'+08:00':value)
export function correctionIntent(form){
 const reason=String(form.reason||'').trim(),verification_note=String(form.verification_note||'').trim(),checkin_at=String(form.checkin_at||'')
 if(offsetInstant(checkin_at)===null)throw new Error('请填写含时区且最多六位小数的业务签到时刻')
 if(!reason||reason.length>1000||!verification_note||verification_note.length>2000||form.facts_confirmed!==true)throw new Error('请填写公开理由和私有核实依据，并明确确认核实')
 return {checkin_at,reason,verification_note,facts_confirmed:true}
}
export function validCorrectionDetail(value,id){return value?.reservation?.id===id&&Number.isSafeInteger(id)&&id>0&&typeof value.can_correct==='boolean'&&offsetInstant(value.server_now)!==null&&offsetInstant(value.deadline)!==null&&typeof value.reservation.status==='string'}
export function validCorrectionReceipt(value,frozen){
 if(!validCorrectionDetail(value,frozen.id)||value.operation_id!==frozen.body.operation_id||typeof value.replayed!=='boolean'||value.can_correct!==false)return false
 const r=value.reservation,u=r.usage_record,c=u?.correction,m=value.management_correction,business=offsetInstant(frozen.body.checkin_at),operated=offsetInstant(c?.operated_at),server=offsetInstant(value.server_now)
 return Number.isSafeInteger(u?.id)&&u.id>0&&u.origin==='admin_correction'&&localInstant(u.checkin_at)===business&&operated!==null&&operated>=business&&operated<=server&&c.source==='admin_self_report'&&c.reason===frozen.body.reason&&typeof c.actor_display_name==='string'&&!!c.actor_display_name&&m?.correction_operation===frozen.body.operation_id&&m.correction_note===frozen.body.verification_note&&Number.isSafeInteger(m.correction_actor)&&m.correction_actor>0&&localInstant(m.corrected_at)===operated&&(r.status==='checked_in'||value.replayed&&['completed','cancelled'].includes(r.status))
}
