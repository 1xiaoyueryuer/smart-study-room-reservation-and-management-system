import request from './request'

// 管理端 API
export function createRoom(data) {
  return request.post('/admin/rooms', data)
}

export function updateRoom(id, data) {
  return request.put(`/admin/rooms/${id}`, data)
}

export function deleteRoom(id, data) {
  return request.delete(`/admin/rooms/${id}`, { data })
}

export function batchGenSeats(roomId, data) {
  return request.post(`/admin/rooms/${roomId}/seats/batch`, data)
}

export function updateSeat(id, data) {
  return request.put(`/admin/seats/${id}`, data)
}

export function deleteSeat(id, data) {
  return request.delete(`/admin/seats/${id}`, { data })
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

export function userImportTemplate() {
  return request.get('/admin/users/import/template', { responseType: 'blob' })
}

export function previewUserImport(file) {
  const data = new FormData()
  data.append('file', file)
  return request.post('/admin/users/import/preview', data)
}

export function confirmUserImport(file, fileSha256, initialPassword, reason) {
  const data = new FormData()
  data.append('file', file)
  data.append('file_sha256', fileSha256)
  data.append('initial_password', initialPassword)
  data.append('reason', reason)
  return request.post('/admin/users/import/confirm', data, { timeout: 120000, silent: true })
}

export function listRoomSeats(roomId) {
  return request.get(`/admin/rooms/${roomId}/seats`)
}

export function createSeat(roomId, data) {
  return request.post(`/admin/rooms/${roomId}/seats`, data)
}

export function getRoomSchedule(roomId) { return request.get(`/admin/rooms/${roomId}/opening-windows`) }
export function saveRoomSchedule(roomId, data) { return request.put(`/admin/rooms/${roomId}/opening-windows`, data) }
export function deleteDateException(roomId, data) { return request.delete(`/admin/rooms/${roomId}/opening-windows`, { data }) }
export function listUnavailability(roomId) { return request.get(`/admin/rooms/${roomId}/unavailability`) }
export function createUnavailability(roomId, data) { return request.post(`/admin/rooms/${roomId}/unavailability`, data) }
export function updateUnavailability(roomId, id, data) { return request.put(`/admin/rooms/${roomId}/unavailability/${id}`, data) }
export function cancelUnavailability(roomId, id, data) { return request.delete(`/admin/rooms/${roomId}/unavailability/${id}`, { data }) }

// 座位码拥有独立版本，PNG通过同一Bearer请求获取。
export const getSeatCode = id => request.get(`/admin/seats/${id}/code`, { silent: true })
export const createSeatCode = (id, data) => request.post(`/admin/seats/${id}/code`, data, { silent: true })
export const rotateSeatCode = (id, data) => request.post(`/admin/seats/${id}/code/rotate`, data, { silent: true })
export const updateSeatCode = (id, data) => request.patch(`/admin/seats/${id}/code`, data, { silent: true })
export const getSeatCodePng = (id, expectedVersion) => request.get(`/admin/seats/${id}/code/png`, { params: { expected_version: expectedVersion }, responseType: 'blob', silent: true })
