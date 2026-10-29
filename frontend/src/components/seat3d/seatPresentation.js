export const zoneName = { quiet: '静音区', regular: '普通区', discussion: '研讨区', computer: '机房区' }
export function seatPosition(room, seat) {
  return { x: (seat.col_no - (room.seat_cols + 1) / 2) * 1.8, z: (seat.row_no - (room.seat_rows + 1) / 2) * 1.9 }
}
export function seatType(s) {
  return s.zone === 'computer' ? '电脑桌' : s.has_power ? '插座桌' : '普通桌'
}
export function seatState(s, selectedId) {
  if (s.status !== 'available') return s.status === 'maintenance' ? 'maintenance' : 'disabled'
  if (Array.isArray(s.available_windows)) return s.available_windows.length ? (s.id === selectedId ? 'selected' : 'available') : 'no_window'
  if (s.bookable_for_slot === false) return 'disabled'
  if (s.occupied) return 'occupied'
  return s.id === selectedId ? 'selected' : 'available'
}
export const stateLabels = { available: '有连续可约空档', selected: '已选座位', no_window: '无满足条件空档', occupied: '已占用', maintenance: '维护中', disabled: '已停用' }
export const stateColors = { available: '#69bca8', no_window: '#e3a363', selected: '#5578ee', occupied: '#e3a363', maintenance: '#b19bbd', disabled: '#89929e' }
export function getWindowSides(room, seats) {
  const sides = { top: false, bottom: false, left: false, right: false }
  if (!room || !seats.length) return sides
  const edge = { top: [], bottom: [], left: [], right: [] }
  for (const s of seats) {
    if (s.row_no === 1) edge.top.push(s)
    if (s.row_no === room.seat_rows) edge.bottom.push(s)
    if (s.col_no === 1) edge.left.push(s)
    if (s.col_no === room.seat_cols) edge.right.push(s)
  }
  for (const side of Object.keys(sides)) {
    sides[side] = edge[side].length === (['top', 'bottom'].includes(side) ? room.seat_cols : room.seat_rows) && edge[side].every(s => s.near_window)
  }
  return sides
}
