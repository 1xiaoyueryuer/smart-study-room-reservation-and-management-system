import {offsetInstant} from './restrictionForm.js'
export const appealBlockedText={revoked:'该违规已撤销，当前不能新提交申诉',pending:'已有待审核申诉，请查看原申诉进度',notification_unknown:'历史未留存可靠通知时间，无法确认线上申诉期限；可联系管理核查',before_notification:'尚未到服务端记录的通知时刻，当前不能提交',deadline_passed:'原通知起算的申诉期限已届满，当前不能提交'}
export const violationLabels={abandon:'开始后主动放弃',no_show:'超时未签到',occupation_refusal:'核实拒不配合占座'}
export function appealPayload(description,items){const text=String(description||'').trim();if(!text||text.length>2000)throw new Error('请填写1至2000字的申诉说明')
  if(items.length>3||items.some(item=>item.phase!=='uploaded'||!item.metadata?.id))throw new Error('请成功上传全部已选照片，或明确移除失败照片后再提交')
  const attachment_ids=items.map(item=>item.metadata.id);if(new Set(attachment_ids).size!==attachment_ids.length)throw new Error('不能重复关联同一份材料')
  return{description:text,attachment_ids}
}
export function validAppealDetail(value){return Number.isSafeInteger(value?.id)&&value.id>0&&Number.isSafeInteger(value.violation_id)&&value.violation_id>0&&typeof value.status==='string'&&Number.isInteger(value.version)&&value.violation_snapshot?.id===value.violation_id&&Number.isSafeInteger(value.notification_basis?.id)&&value.notification_basis.id>0&&offsetInstant(value.notification_basis.notified_at)!==null&&offsetInstant(value.notification_basis.deadline)!==null&&Array.isArray(value.attachments)&&Array.isArray(value.events)}
export function validAppealEligibility(value,id){return value?.violation?.id===id&&typeof value.eligibility?.can_submit==='boolean'&&[null,...Object.keys(appealBlockedText)].includes(value.eligibility.blocked_by)&&(!value.eligibility.can_submit||(Number.isSafeInteger(value.eligibility.notification_id)&&value.eligibility.notification_id>0&&offsetInstant(value.eligibility.notified_at)!==null&&offsetInstant(value.eligibility.deadline)!==null))}
