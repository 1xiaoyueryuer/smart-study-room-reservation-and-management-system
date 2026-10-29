// 只将服务端政策转换为表单输入；最终资格、开放和冲突仍由提交接口复核。
export function minuteValue(value, end = false) {
  if (end && value === '24:00') return 1440
  if (!/^([01][0-9]|2[0-3]):[0-5][0-9]$/.test(value || '')) throw new Error('时间须为HH:mm，开始不可24:00，结束可24:00')
  return Number(value.slice(0,2)) * 60 + Number(value.slice(3,5))
}
export function minuteText(value) {
  return `${String(Math.floor(value / 60)).padStart(2,'0')}:${String(value % 60).padStart(2,'0')}`
}
export function serverStartMinute(policy) {
  const match = policy.server_now?.match(/T([01][0-9]|2[0-3]):([0-5][0-9])(?::([0-5][0-9])(?:\.([0-9]+))?)?\+08:00$/)
  if (!match || policy.campus_zone !== 'Asia/Shanghai') throw new Error('服务端校园时间格式无效，请重新读取预约政策')
  return Number(match[1]) * 60 + Number(match[2]) + ((Number(match[3] || 0) > 0 || /[1-9]/.test(match[4] || '')) ? 1 : 0)
}
export function initialSlot(policy) {
  const start = serverStartMinute(policy)
  if (start + policy.min_duration_minutes <= 1440) return { date:policy.campus_today,start:minuteText(start),end:minuteText(start + policy.min_duration_minutes) }
  const next = policy.dates.find(date => date > policy.campus_today)
  if (!next) throw new Error('服务端未提供可初始化的预约日期，请刷新政策')
  return {date:next,start:'00:00',end:minuteText(policy.min_duration_minutes)}
}
export function validateSlot(policy, date, start, end) {
  if (!policy || !policy.dates.includes(date)) throw new Error(`预约日期${date || '未选择'}已无效，请刷新政策并从服务端日期中选择`)
  const begin = minuteValue(start), finish = minuteValue(end,true)
  if (finish - begin < policy.min_duration_minutes) throw new Error(`预约至少${policy.min_duration_minutes}分钟，结束须晚于开始且不可跨预约日期`)
  if (date === policy.campus_today && begin < serverStartMinute(policy)) throw new Error('起点早于当前服务端时刻，请调整开始时间后重新查询')
  return finish - begin
}
