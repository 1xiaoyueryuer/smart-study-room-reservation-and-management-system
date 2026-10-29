import request from './request'

export function getAvailability(roomId, date, strategy = 'earliest', requiredMinutes = null) {
  const params = { room_id: roomId, date, strategy }
  if (strategy === 'duration') params.required_minutes = requiredMinutes
  return request.get('/availability', { params, silent: true })
}
