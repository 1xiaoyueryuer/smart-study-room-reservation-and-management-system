const campusDateTime = new Intl.DateTimeFormat('en-CA', {
  timeZone: 'Asia/Shanghai',
  year: 'numeric', month: '2-digit', day: '2-digit',
  hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23'
})

export function formatCampusDateTime(value) {
  if (typeof value !== 'string' || !value.trim()) return '—'
  const input = value.trim()
  // Backend timestamps without an explicit offset are campus-local values.
  const campusLocal = /^\d{4}-\d{2}-\d{2}[T ]\d{2}:\d{2}(?::\d{2}(?:\.\d+)?)?$/.test(input)
  const date = new Date(campusLocal ? `${input.replace(' ', 'T')}+08:00` : input)
  if (!Number.isFinite(date.getTime())) return value
  const parts = Object.fromEntries(campusDateTime.formatToParts(date).map(part => [part.type, part.value]))
  return `${parts.year}-${parts.month}-${parts.day} ${parts.hour}:${parts.minute}:${parts.second}`
}
