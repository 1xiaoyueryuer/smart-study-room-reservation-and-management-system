import request from './request'

// 管理端 API
export function createRoom(data) {
  return request.post('/admin/rooms', data)
}

export function updateRoom(id, data) {
  return request.put(`/admin/rooms/${id}`, data)
}

export function deleteRoom(id) {
  return request.delete(`/admin/rooms/${id}`)
}

export function batchGenSeats(roomId, data) {
  return request.post(`/admin/rooms/${roomId}/seats/batch`, data)
}

export function updateSeat(id, data) {
  return request.put(`/admin/seats/${id}`, data)
}

export function deleteSeat(id) {
  return request.delete(`/admin/seats/${id}`)
}

export function listUsers() {
  return request.get('/admin/users')
}

export function setUserStatus(id, status, reason) {
  return request.put(`/admin/users/${id}/status`, { status, reason })
}

export function createUser(data) {
  return request.post('/admin/users', data)
}

export function updateUser(id, data) {
  return request.put(`/admin/users/${id}`, data)
}
