import request from './request'

// 预约
export function createReservation(data) {
  return request.post('/reservations', data, { silent: true })
}

export function listMyReservations(params) {
  return request.get('/reservations/mine',{params,silent:true})
}

export function cancelReservation(id) {
  return request.post(`/reservations/${id}/cancel`)
}

export function getReservation(id) {
  return request.get(`/reservations/${id}`, { silent: true })
}

export function checkinReservation(id, seatCode) {
  return request.post(`/reservations/${id}/checkin`, { seat_code: seatCode }, { silent: true })
}

export function checkoutReservation(id) {
  return request.post(`/reservations/${id}/checkout`)
}

export function getBookingPolicy() {
  return request.get('/booking-policy', { silent: true })
}

export function abandonReservation(id) {
  return request.post(`/reservations/${id}/abandon`, null, { silent:true })
}

export const listAdminReservations=params=>request.get('/admin/reservations',{params,silent:true})
export const getAdminReservation=id=>request.get(`/admin/reservations/${id}`,{silent:true})
