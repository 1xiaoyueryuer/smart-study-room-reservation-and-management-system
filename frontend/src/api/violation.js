import request from './request'

export function listMyViolations() {
  return request.get('/violations/mine', { silent: true })
}
