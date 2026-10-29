import { minuteValue, validateSlot } from './bookingPolicy.js'
export const windowKey = window => window ? `${window.room_id}:${window.seat_id}:${window.available_start}:${window.available_end}` : ''
export function validateWindowSlot(policy, date, start, end, window) {
  const duration = validateSlot(policy, date, start, end)
  if (!window || minuteValue(start) < minuteValue(window.available_start) || minuteValue(end, true) > minuteValue(window.available_end, true)) throw new Error('本次预约起止须完整位于所选连续空档内，请调整时间或重新选择片段')
  return duration
}

export function queryRequiredMinutes(strategy, draft) {
  if (strategy !== 'duration') return null
  if (!/^[0-9]+$/.test(String(draft))) throw new Error('所需时长须为至少30分钟的十进制整数，不支持小数、符号、空格或科学记法')
  const value = Number(draft)
  if (!Number.isSafeInteger(value) || value > 2147483647) throw new Error('所需分钟数超出可处理的整数范围，请减少输入')
  if (value < 30) throw new Error('所需时长至少30分钟')
  return value
}
export function shortfallText(requiredMinutes, start, end) {
  if (requiredMinutes === null || requiredMinutes === undefined) return ''
  try {
    const actual = minuteValue(end, true) - minuteValue(start)
    return actual >= 30 && actual < requiredMinutes ? `本次${actual}分钟，短于找座时输入的${requiredMinutes}分钟` : ''
  } catch { return '' }
}
