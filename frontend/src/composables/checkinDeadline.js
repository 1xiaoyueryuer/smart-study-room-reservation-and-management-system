/**
 * 签到截止时间的唯一前端展示口径。
 *
 * 服务端字段优先；字段尚未接入时，仅按“开始时刻 + 15 分钟”计算展示值，
 * 不据此判断是否可签到。是否可签、能否延期一律以服务端返回的
 * checkin_deadline / server_now / can_request_extension 为准。
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
  const apiBase = row.base_checkin_deadline || ''
  const extended = Boolean(row.extension_applied) || row.checkin_deadline_source === 'extension'
  const baseText = timeText(apiBase) || fallbackBaseText(row)
  const text = timeText(apiDeadline) || baseText
  if (!text) return null
  return {
    text,
    baseText,
    extended: extended && text !== baseText,
    source: row.checkin_deadline_source || (extended ? 'extension' : 'base'),
    canRequest: typeof row.can_request_extension === 'boolean' ? row.can_request_extension : null,
    blockedReason: row.extension_blocked_reason || '',
    pendingVerification: Boolean(row.extension_verification_pending),
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
