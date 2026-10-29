import { minuteValue, validateSlot } from './bookingPolicy.js'
export const windowKey = window => window ? `${window.room_id}:${window.seat_id}:${window.available_start}:${window.available_end}` : ''
export function validateWindowSlot(policy, date, start, end, window) {
  const duration = validateSlot(policy, date, start, end)
  if (!window || minuteValue(start) < minuteValue(window.available_start) || minuteValue(end, true) > minuteValue(window.available_end, true)) throw new Error('本次预约起止须完整位于所选连续空档内，请调整时间或重新选择片段')
  return duration
}
