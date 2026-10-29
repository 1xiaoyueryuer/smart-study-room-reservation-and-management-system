#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
智能共享自习室预约系统 - 高并发抢座互斥自动化测试
模拟 20 个不同学生账号瞬间并发请求同一自习室、同一日期、同一时段的同一个座位。
核心验证目标：
1. 依赖 Redis 分布式锁与 PostgreSQL 排除约束双重屏障；
2. 绝对防止超卖：严格有且仅有 1 个请求获得成功 (HTTP 200/201)；
3. 其余 19 个并发请求全部安全拦截并返回资源冲突 (HTTP 409 Conflict)；
4. 统计并发请求的响应时间分布与 P95 延时。
"""

import sys
import json
import time
import base64
import hmac
import hashlib
import concurrent.futures
import urllib.request
import urllib.error

# 控制台 UTF-8
if sys.platform == "win32":
    sys.stdout.reconfigure(encoding="utf-8")

BASE_URL = "http://localhost:8095/api"
CONCURRENCY = 20
JWT_SECRET = "studyroom-dev-secret"

def b64url_encode(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b'=').decode('utf-8')

def make_jwt(uid: int, username: str, role: str = "student") -> str:
    """基于标准库直接签发标准 HS256 JWT，无需第三方依赖且不触发登录频次限制"""
    header = {"alg": "HS256", "typ": "JWT"}
    now = int(time.time())
    payload = {
        "uid": uid,
        "username": username,
        "role": role,
        "iat": now,
        "exp": now + 86400,
        "iss": "studyroom"
    }
    part1 = b64url_encode(json.dumps(header, separators=(',', ':')).encode('utf-8'))
    part2 = b64url_encode(json.dumps(payload, separators=(',', ':')).encode('utf-8'))
    signing_input = f"{part1}.{part2}".encode('utf-8')
    sig = hmac.new(JWT_SECRET.encode('utf-8'), signing_input, hashlib.sha256).digest()
    part3 = b64url_encode(sig)
    return f"{part1}.{part2}.{part3}"

def book_seat(token, seat_id, date, start_time, end_time):
    url = f"{BASE_URL}/reservations"
    data = json.dumps({
        "seat_id": seat_id,
        "date": date,
        "start_time": start_time,
        "end_time": end_time
    }).encode("utf-8")
    headers = {
        "Content-Type": "application/json",
        "Authorization": f"Bearer {token}"
    }
    req = urllib.request.Request(url, data=data, headers=headers, method="POST")
    t0 = time.time()
    try:
        with urllib.request.urlopen(req, timeout=10) as resp:
            elapsed = (time.time() - t0) * 1000
            body = json.loads(resp.read().decode("utf-8"))
            return resp.status, body, elapsed
    except urllib.error.HTTPError as e:
        elapsed = (time.time() - t0) * 1000
        try:
            body = json.loads(e.read().decode("utf-8"))
        except Exception:
            body = {}
        return e.code, body, elapsed
    except Exception as e:
        elapsed = (time.time() - t0) * 1000
        return 500, {"error": str(e)}, elapsed

def main():
    print("=" * 70)
    print(f"智能共享自习室预约系统 - 高并发抢座压力测试 (并发数: {CONCURRENCY})")
    print(f"目标接口: POST {BASE_URL}/reservations")
    print("=" * 70)

    # 准备 20 个测试学生的独立 Token (stu11 ~ stu30, UID: 12 ~ 31)
    print("正在准备 20 个独立学生用户的会话凭据...")
    tokens = []
    for i in range(11, 11 + CONCURRENCY):
        uname = f"stu{i:02d}"
        uid = i + 1  # 数据库中 admin=1, stu01=2, stu02=3 ... stu11=12
        token = make_jwt(uid, uname)
        tokens.append((uname, token))

    print("20 个测试账号 Token 就绪，开始发起瞬间并发抢座！\n")

    # 抢座目标：座位 12, 日期 2026-09-28, 14:00 - 16:00
    target_seat_id = 12
    target_date = "2026-09-28"
    target_start = "14:00"
    target_end = "16:00"

    results = []
    start_all = time.time()

    with concurrent.futures.ThreadPoolExecutor(max_workers=CONCURRENCY) as executor:
        futures = [
            executor.submit(book_seat, token, target_seat_id, target_date, target_start, target_end)
            for _, token in tokens
        ]
        for f in concurrent.futures.as_completed(futures):
            results.append(f.result())

    total_time = (time.time() - start_all) * 1000

    # 统计结果
    success_count = sum(1 for status, _, _ in results if status == 200)
    conflict_count = sum(1 for status, _, _ in results if status == 409)
    other_count = sum(1 for status, _, _ in results if status not in (200, 409))
    latencies = sorted([elapsed for _, _, elapsed in results])

    avg_latency = sum(latencies) / len(latencies)
    p95_latency = latencies[int(len(latencies) * 0.95)]
    min_latency = latencies[0]
    max_latency = latencies[-1]

    # 输出抢座细节
    for idx, (status, body, elapsed) in enumerate(results, 1):
        msg = body.get("message", "ok") if status == 200 else body.get("message", "conflict")
        status_tag = "[SUCCESS]" if status == 200 else "[CONFLICT]"
        print(f"  请求 #{idx:02d}: HTTP {status} {status_tag} 耗时: {elapsed:5.1f}ms - 响应: {msg}")

    print("\n" + "=" * 70)
    print("高并发抢座实测指标与防超卖断言分析:")
    print("=" * 70)
    print(f"  - 总并发请求数:       {CONCURRENCY}")
    print(f"  - 成功预约数 (200):   {success_count} (预期: 严格等于 1)")
    print(f"  - 冲突拦截数 (409):   {conflict_count} (预期: 严格等于 {CONCURRENCY - 1})")
    print(f"  - 异常失败数:         {other_count} (预期: 0)")
    print(f"  - 最小响应时间:       {min_latency:.2f} ms")
    print(f"  - 最大响应时间:       {max_latency:.2f} ms")
    print(f"  - 平均响应延时:       {avg_latency:.2f} ms")
    print(f"  - P95 响应延时:       {p95_latency:.2f} ms")
    print(f"  - 20并发总耗时:       {total_time:.2f} ms")

    # 核心安全断言
    passed = (success_count == 1 and conflict_count == CONCURRENCY - 1 and other_count == 0)
    if passed:
        print("\n[TEST PASSED] 互斥防超卖断言成功：高并发分布式锁与排除约束生效，严格 1 人抢中，19 人冲突拦截！")
    else:
        print(f"\n[TEST FAILED] 互斥断言失败：出现超卖或异常，成功数={success_count}")

    # 清理现场：如果抢座成功，取消该预约以保持数据库整洁
    if success_count == 1:
        for status, body, _ in results:
            if status == 200 and "data" in body and "id" in body["data"]:
                booked_id = body["data"]["id"]
                # 找到抢到的用户 token 取消
                for user, token in tokens:
                    url = f"{BASE_URL}/reservations/{booked_id}/cancel"
                    req = urllib.request.Request(url, data=b"{}", headers={"Content-Type": "application/json", "Authorization": f"Bearer {token}"}, method="POST")
                    try:
                        with urllib.request.urlopen(req, timeout=3) as resp:
                            if resp.status == 200:
                                print(f"测试现场自动清理完成: 已取消测试预约单 ID={booked_id}")
                                break
                    except Exception:
                        pass

    return 0 if passed else 1

if __name__ == "__main__":
    sys.exit(main())
