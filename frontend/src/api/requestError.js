// 保留 Error/message 兼容性，status=null 明确表示未收到 HTTP 响应。
export function requestError(err) {
  const body = err.response?.data
  const failure = new Error(body?.message || err.message || '网络异常，请稍后重试', { cause: err })
  failure.status = err.response?.status ?? null
  failure.code = body?.code ?? err.code ?? null
  failure.details = body?.details ?? null
  return failure
}
