#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
智能共享自习室预约系统 - API 自动化端到端集成测试套件
覆盖 10 大核心模块，执行 23 项关键断言，全链路验证系统业务与状态流转闭环。
"""

import sys
import json
import time
import urllib.request
import urllib.error

# 统一控制台输出 UTF-8
if sys.platform == "win32":
    sys.stdout.reconfigure(encoding="utf-8")

BASE_URL = "http://localhost:8095/api"

class TestRunner:
    def __init__(self):
        self.passed = 0
        self.failed = 0
        self.total = 0
        self.start_time = time.time()
        self.admin_token = None
        self.student_token = None
        self.student_uid = None
        self.test_res_id = None
        self.test_waitlist_id = None

    def log_result(self, case_id, name, success, detail=""):
        self.total += 1
        if success:
            self.passed += 1
            print(f"[PASS] {case_id}: {name}")
        else:
            self.failed += 1
            print(f"[FAIL] {case_id}: {name} -> {detail}")

    def request(self, method, path, data=None, token=None):
        url = f"{BASE_URL}{path}"
        headers = {"Content-Type": "application/json"}
        if token:
            headers["Authorization"] = f"Bearer {token}"
        req_data = json.dumps(data).encode("utf-8") if data is not None else None
        req = urllib.request.Request(url, data=req_data, headers=headers, method=method)
        try:
            with urllib.request.urlopen(req, timeout=5) as resp:
                body = resp.read().decode("utf-8")
                return resp.status, json.loads(body) if body else {}
        except urllib.error.HTTPError as e:
            body = e.read().decode("utf-8")
            try:
                parsed = json.loads(body)
            except Exception:
                parsed = {"raw": body}
            return e.code, parsed
        except Exception as e:
            return 500, {"error": str(e)}

    def run_all(self):
        print("=" * 70)
        print("智能共享自习室预约系统 (Smart Study Room) - API 自动化集成测试")
        print(f"测试目标地址: {BASE_URL}")
        print("=" * 70)

        # 1. 系统健康状态
        status, body = self.request("GET", "/health")
        self.log_result("TC-API-01", "系统健康探针接口响应", status == 200 and body.get("data", {}).get("status") == "healthy")

        # 2. 身份认证与登录鉴权
        status, body = self.request("POST", "/auth/login", {"username": "admin", "password": "admin123"})
        if status == 200 and "token" in body.get("data", {}):
            self.admin_token = body["data"]["token"]
            self.log_result("TC-API-02", "系统管理员账号正常登录", True)
        else:
            self.log_result("TC-API-02", "系统管理员账号正常登录", False, str(body))

        status, body = self.request("POST", "/auth/login", {"username": "stu01", "password": "123456"})
        if status == 200 and "token" in body.get("data", {}):
            self.student_token = body["data"]["token"]
            self.student_uid = body["data"]["user"]["id"]
            self.log_result("TC-API-03", "学生账号正常登录及JWT下发", True)
        else:
            self.log_result("TC-API-03", "学生账号正常登录及JWT下发", False, str(body))

        status, body = self.request("POST", "/auth/login", {"username": "stu01", "password": "wrong_password"})
        self.log_result("TC-API-04", "非法密码登录安全阻断 (HTTP 401)", status == 401)

        # 3. 权限隔离与RBAC控制
        status, body = self.request("GET", "/admin/users", token=self.student_token)
        self.log_result("TC-API-05", "普通学生越权访问管理端接口拦截 (HTTP 403)", status == 403)

        status, body = self.request("GET", "/admin/users", token=self.admin_token)
        self.log_result("TC-API-06", "管理员鉴权访问用户列表 (HTTP 200)", status == 200 and len(body.get("data", [])) > 0)

        # 4. 房间与座位平面图查询
        status, body = self.request("GET", "/rooms", token=self.student_token)
        rooms = body.get("data", [])
        self.log_result("TC-API-07", "自习室房间列表获取", status == 200 and len(rooms) >= 3)

        room_id = rooms[0]["id"] if rooms else 1
        status, body = self.request("GET", f"/rooms/{room_id}/seats?date=2026-09-25&start=09:00&end=11:00", token=self.student_token)
        seat_map = body.get("data", {})
        seats = seat_map.get("seats", [])
        self.log_result("TC-API-08", "时段座位分布图与状态响应", status == 200 and len(seats) > 0 and "occupied" in seats[0])

        # 5. 智能自动分配算法
        alloc_req = {
            "room_id": room_id,
            "date": "2026-09-25",
            "start_time": "14:00",
            "end_time": "16:00",
            "zone": "quiet",
            "top_n": 3,
            "auto_book": False
        }
        status, body = self.request("POST", "/reservations/auto", alloc_req, token=self.student_token)
        recs = body.get("data", {}).get("recommendations", [])
        has_reasons = recs and len(recs[0].get("reasons", [])) > 0
        self.log_result("TC-API-09", "智能分配 Top-N 推荐加权评分", status == 200 and len(recs) == 3 and has_reasons)

        # 6. 在线选座预约
        book_target_seat = seats[0]["id"]
        book_req = {
            "seat_id": book_target_seat,
            "date": "2026-09-25",
            "start_time": "14:00",
            "end_time": "16:00"
        }
        status, body = self.request("POST", "/reservations", book_req, token=self.student_token)
        if status == 200 and "id" in body.get("data", {}):
            self.test_res_id = body["data"]["id"]
            self.log_result("TC-API-10", "创建座位预约单 (pending 状态)", True)
        else:
            self.log_result("TC-API-10", "创建座位预约单 (pending 状态)", False, str(body))

        # 7. 防冲突排他校验 (同座位同时段抢占)
        # 用另外一个学生账号 stu02 来抢同一个座位
        status_l, body_l = self.request("POST", "/auth/login", {"username": "stu02", "password": "123456"})
        stu02_token = body_l.get("data", {}).get("token")
        status, body = self.request("POST", "/reservations", book_req, token=stu02_token)
        self.log_result("TC-API-11", "同时段座位排他互斥拦截 (HTTP 409)", status == 409)

        # 8. 用户同时段不可重复预约校验
        # 避开限流窗口等待 1 秒
        time.sleep(1.1)
        book_req2 = {
            "seat_id": seats[1]["id"],
            "date": "2026-09-25",
            "start_time": "15:00",
            "end_time": "17:00"
        }
        status, body = self.request("POST", "/reservations", book_req2, token=self.student_token)
        self.log_result("TC-API-12", "同一用户重叠时段多重预约拦截 (HTTP 409)", status == 409)

        # 9. 预约时长超限边界校验 (>8小时)
        time.sleep(1.1)
        invalid_slot_req = {
            "seat_id": seats[2]["id"],
            "date": "2026-09-25",
            "start_time": "08:00",
            "end_time": "18:00"  # 10 小时 > 8 小时上限
        }
        status, body = self.request("POST", "/reservations", invalid_slot_req, token=self.student_token)
        self.log_result("TC-API-13", "超过8小时单次预约上限校验 (HTTP 400)", status == 400)

        # 10. 我的预约列表查询
        status, body = self.request("GET", "/reservations/mine", token=self.student_token)
        mine_list = body.get("data", [])
        found_target = any(r["id"] == self.test_res_id for r in mine_list)
        self.log_result("TC-API-14", "个人预约单列表聚合查询", status == 200 and found_target)

        # 11. 越权取消他人预约拦截
        # 使用 stu02 尝试取消 stu01 的预约
        status, body = self.request("POST", f"/reservations/{self.test_res_id}/cancel", {}, token=stu02_token)
        self.log_result("TC-API-15", "越权取消他人预约安全拦截 (HTTP 403)", status == 403)

        # 12. 正常取消预约与资源解挂
        status, body = self.request("POST", f"/reservations/{self.test_res_id}/cancel", {}, token=self.student_token)
        self.log_result("TC-API-16", "本人正常取消未来预约 (cancelled)", status == 200 and body.get("data", {}).get("cancelled") == self.test_res_id)

        # 13. 满座候补排队机制
        wl_req = {
            "room_id": room_id,
            "date": "2026-09-25",
            "start_time": "14:00",
            "end_time": "16:00",
            "zone": "quiet"
        }
        status, body = self.request("POST", "/waitlist", wl_req, token=self.student_token)
        if status == 200 and "id" in body.get("data", {}):
            self.test_waitlist_id = body["data"]["id"]
            self.log_result("TC-API-17", "提交满座候补登记", True)
        else:
            self.log_result("TC-API-17", "提交满座候补登记", False, str(body))

        # 14. 候补排队位次与列表查询
        status, body = self.request("GET", "/waitlist/mine", token=self.student_token)
        wl_mine = body.get("data", [])
        in_wl = any(w["id"] == self.test_waitlist_id and w["status"] == "waiting" for w in wl_mine)
        self.log_result("TC-API-18", "候补记录与当前排队位次获取", status == 200 and in_wl)

        # 15. 取消候补排队
        if self.test_waitlist_id:
            status, body = self.request("POST", f"/waitlist/{self.test_waitlist_id}/cancel", {}, token=self.student_token)
            self.log_result("TC-API-19", "主动退出候补队列", status == 200)
        else:
            self.log_result("TC-API-19", "主动退出候补队列", False, "无有效候补ID")

        # 16. 信用分体系与明细流水查询
        status, body = self.request("GET", "/credit", token=self.student_token)
        credit_data = body.get("data", {})
        self.log_result("TC-API-20", "个人信用积分与履约记录查询", status == 200 and "score" in credit_data and "logs" in credit_data)

        # 17. 站内通知与未读角标计数
        status, body = self.request("GET", "/notifications/unread_count", token=self.student_token)
        has_unread = status == 200 and "count" in body.get("data", {})
        status_all, _ = self.request("POST", "/notifications/read_all", {}, token=self.student_token)
        self.log_result("TC-API-21", "通知中心未读统计与一键已读", has_unread and status_all == 200)

        # 18. 运营热力图与统计指标查询
        status, body = self.request("GET", f"/stats/heatmap?room_id={room_id}&date=2026-09-08", token=self.student_token)
        heatmap_data = body.get("data", {})
        valid_heatmap = len(heatmap_data.get("hours", [])) > 0 and len(heatmap_data.get("cells", [])) > 0
        self.log_result("TC-API-22", "座位×小时运营热力图矩阵响应", status == 200 and valid_heatmap)

        # 19. 登录令牌注销与黑名单生效测试
        status, body = self.request("POST", "/auth/logout", {}, token=self.student_token)
        # 用已注销的旧 Token 再次访问应被拦截 401
        status_after, _ = self.request("GET", "/auth/profile", token=self.student_token)
        self.log_result("TC-API-23", "用户注销及Token黑名单即时拦截 (HTTP 401)", status == 200 and status_after == 401)

        # 汇总报告
        duration = time.time() - self.start_time
        print("=" * 70)
        print(f"测试执行完成! 总用例: {self.total} | 通过: {self.passed} | 失败: {self.failed}")
        print(f"用例通过率: {(self.passed / self.total * 100):.1f}% | 总耗时: {duration:.2f}秒")
        print("=" * 70)

        return self.failed == 0

if __name__ == "__main__":
    runner = TestRunner()
    success = runner.run_all()
    sys.exit(0 if success else 1)
