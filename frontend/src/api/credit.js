import request from './request'

// 历史信用资料只读查询，当前资格由服务端判断
export function getCreditOverview() {
  return request.get('/credit')
}
