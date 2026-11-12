/**
 * 签到截止时间的唯一前端展示口径。
 *
 * 当前后端不支持签到延期。这里只展示服务端截止；缺字段时按原15分钟规则显示，
 * 不自行推断签到资格，也不显示延期能力。
 */
const pad = value => String(value).padStart(2, '0')

function timeText(value) {
  if (!value) return ''
  if (/^\d{1,2}:\d{2}(:\d{2})?$/.test(value)) {
    const [h, m, s = '00'] = value.split(':')
    return `${pad(h)}:${m}:${s.slice(0, 2)}`
  }
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return String(value)
  return new Intl.DateTimeFormat('zh-CN', {
    timeZone: 'Asia/Shanghai',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hour12: false
  }).format(date)
}

function fallbackBaseText(row) {
  const date = String(row?.res_date || '')
  const start = String(row?.start_time || '')
  if (!date || !start) return ''
  const [y, mo, d] = date.split('-').map(Number)
  const [h, mi, s = 0] = start.split(':').map(Number)
  if (![y, mo, d, h, mi].every(Number.isFinite)) return ''
  const at = new Date(Date.UTC(y, mo - 1, d, h, mi, Number(s) || 0))
  at.setUTCMinutes(at.getUTCMinutes() + 15)
  return `${pad(at.getUTCHours())}:${pad(at.getUTCMinutes())}:${pad(at.getUTCSeconds())}`
}

export function checkinDeadlineOf(row) {
  if (!row) return null
  const apiDeadline = row.checkin_deadline || row.deadline || ''
  const baseText = fallbackBaseText(row)
  const text = timeText(apiDeadline) || baseText
  if (!text) return null
  return {
    text,
    baseText,
    extended: false,
    source: 'base',
    canRequest: false,
    blockedReason: '',
    pendingVerification: false,
    raw: apiDeadline || null
  }
}

export function deadlineLabel(value) {
  if (!value) return '—'
  if (typeof value === 'string') return timeText(value) || value
  return value.text || '—'
}

/** 服务端时间字符串转毫秒；用于展示倒计时，不作为业务判定。 */
export function serverNowMs(row) {
  const value = row?.server_now
  if (!value) return null
  const time = new Date(value).getTime()
  return Number.isNaN(time) ? null : time
}
