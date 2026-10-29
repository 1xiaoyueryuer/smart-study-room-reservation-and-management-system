import request from './request'

export function listMyViolations() {
  return request.get('/violations/mine', { silent: true })
}

export function registerEventViolation(eventId, body) {
  return request.post(`/admin/complaint-events/${eventId}/violations`, body, { silent: true, timeout: 120000 })
}
