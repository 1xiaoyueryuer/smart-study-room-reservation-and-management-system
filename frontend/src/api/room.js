import request from './request'

// 房间与座位
export function getRooms() {
  return request.get('/rooms')
}

export function getSeatMap(roomId, date, start, end) {
  return request.get(`/rooms/${roomId}/seats`, { params: { date, start, end } })
}

export function getOpeningWindows(roomId, date, seatId) {
  return request.get(`/rooms/${roomId}/opening-windows`, { params: { date, ...(seatId ? { seat_id: seatId } : {}) } })
}
