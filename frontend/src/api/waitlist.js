import request from './request'

// 候补队列
export function listMyWaitlist() {
  return request.get('/waitlist/mine')
}

export function cancelWaitlist(id) {
  return request.post(`/waitlist/${id}/cancel`)
}
