import request from './request'

export function getAvailability(roomId, date, strategy = 'earliest', requiredMinutes = null, startTime = null, learningMinutes = null) {
  const params = { room_id: roomId, date, strategy }
  if (strategy === 'duration') params.required_minutes = requiredMinutes
  if (startTime !== null) params.start_time = startTime
  if (learningMinutes !== null) params.learning_minutes = learningMinutes
  return request.get('/availability', { params, silent: true })
}
