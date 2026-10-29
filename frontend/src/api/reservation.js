import request from './request'

// 预约
export function createReservation(data) {
  return request.post('/reservations', data, { silent: true })
}

export function listMyReservations() {
  return request.get('/reservations/mine')
}

export function cancelReservation(id) {
  return request.post(`/reservations/${id}/cancel`)
}

export function checkinReservation(id) {
  return request.post(`/reservations/${id}/checkin`)
}

export function leaveReservation(id) {
  return request.post(`/reservations/${id}/leave`)
}

export function returnReservation(id) {
  return request.post(`/reservations/${id}/return`)
}

export function checkoutReservation(id) {
  return request.post(`/reservations/${id}/checkout`)
}

export function getBookingPolicy() {
  return request.get('/booking-policy', { silent: true })
}
