import request from './request'

export function getAvailability(roomId, date) {
  return request.get('/availability', { params: { room_id: roomId, date, strategy: 'earliest' }, silent: true })
}
