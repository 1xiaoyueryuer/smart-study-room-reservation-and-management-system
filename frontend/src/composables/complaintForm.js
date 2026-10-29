export const complaintTypes = { occupation: '座位被占求助', service: '一般服务投诉' }
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
export async function validateEvidenceFile(file, { urls = URL, ImageClass = Image } = {}) {
  if (!['image/png','image/jpeg'].includes(file.type)) throw new Error('照片仅支持PNG或JPEG，请重新选择')
  if (!file.size || file.size > 5 * 1024 * 1024) throw new Error('每张照片须大于0且不超过5MiB')
  const url = urls.createObjectURL(file)
  try {
    await new Promise((resolve,reject) => {
      const image = new ImageClass()
      image.onload = () => image.naturalWidth > 0 && image.naturalHeight > 0 && image.naturalWidth <= 10000 && image.naturalHeight <= 10000 && image.naturalWidth * image.naturalHeight <= 20000000 ? resolve() : reject(new Error('照片单边不能超过10000像素，总像素不能超过2000万'))
      image.onerror = () => reject(new Error('照片损坏或无法解码，请更换PNG/JPEG文件'))
      image.src = url
    })
  } finally { urls.revokeObjectURL(url) }
}
