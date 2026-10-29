"""对正在运行的 Java 服务验证前端 API 契约；使用模拟账号，测试预约最终取消。"""
import datetime as dt
import json
import os
import sys
import time
import urllib.request
import urllib.error

BASE = os.environ.get('STUDYROOM_API', 'http://127.0.0.1:8080/api')
if sys.platform == 'win32':
    sys.stdout.reconfigure(encoding='utf-8')
checks = []

def call(method, path, body=None, token=None):
    headers = {'Content-Type': 'application/json'}
    if token:
        headers['Authorization'] = 'Bearer ' + token
    req = urllib.request.Request(BASE + path, data=None if body is None else json.dumps(body).encode(), headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=15) as r:
            return r.status, json.load(r)
    except urllib.error.HTTPError as e:
        return e.code, json.load(e)

def check(name, condition):
    checks.append((name, bool(condition)))
    print(('PASS ' if condition else 'FAIL ') + name)
    if not condition:
        raise AssertionError(name)

def main():
    status, body = call('GET', '/health')
    check('Java + PostgreSQL + Redis 健康检查', status == 200 and body['data']['backend'] == 'java' and body['data']['database'] == 'PostgreSQL')
    check('匿名请求被拦截', call('GET', '/rooms')[0] == 401)
    check('错误密码被拦截', call('POST', '/auth/login', {'username':'stu01','password':'wrong'})[0] == 401)
    status, body = call('POST','/auth/login',{'username':'admin','password':'admin123'})
    check('管理员登录', status == 200)
    admin = body['data']['token']
    status, body = call('POST','/auth/login',{'username':'stu01','password':'123456'})
    check('学生登录及 snake_case 用户字段', status == 200 and 'real_name' in body['data']['user'])
    student = body['data']['token']
    status, body = call('POST','/auth/login',{'username':'stu02','password':'123456'})
    other = body['data']['token']
    check('学生无管理权限',call('GET','/admin/users',token=student)[0] == 403)
    status, body = call('GET','/admin/users',token=admin)
    check('用户列表不含口令',status == 200 and len(body['data']) >= 41 and all('password_hash' not in u for u in body['data']))
    status, body = call('GET','/rooms',token=student)
    check('房间列表兼容原前端',status == 200 and len(body['data']) >= 3)
    room = body['data'][0]['id']
    date = (dt.datetime.now(dt.timezone(dt.timedelta(hours=8))).date() + dt.timedelta(days=1)).isoformat()
    status, body = call('GET',f'/rooms/{room}/seats?date={date}&start=10:00&end=11:00',token=student)
    check('座位图字段与布尔类型',status == 200 and isinstance(body['data']['seats'][0]['occupied'],bool))
    seats = [s for s in body['data']['seats'] if s['status']=='available' and not s['occupied']]
    seat = seats[-1]['id']
    status, body = call('POST','/reservations/auto',{'room_id':room,'date':date,'start_time':'10:00','end_time':'11:00','top_n':3,'auto_book':False},student)
    check('规则选座推荐（非智能助手）',status == 200 and len(body['data']['recommendations']) == 3)
    request = {'seat_id':seat,'date':date,'start_time':'10:00','end_time':'11:00'}
    res_id = None
    try:
        status, body = call('POST','/reservations',request,student)
        check('预约创建且日期不带午夜时间',status == 200 and body['data']['res_date'] == date)
        res_id = body['data']['id']
        status, _ = call('POST','/reservations',request,other)
        check('重叠座位返回409',status == 409)
        check('越权取消返回403',call('POST',f'/reservations/{res_id}/cancel',{},other)[0] == 403)
        check('未来预约不能提前签到',call('POST',f'/reservations/{res_id}/checkin',{},student)[0] == 400)
        status, body = call('GET','/reservations/mine',token=student)
        check('我的预约包含座位与房间',status == 200 and any(r['id']==res_id and 'room_name' in r for r in body['data']))
        time.sleep(1.1)
        status, _ = call('POST','/reservations',{**request,'start_time':'10:01'},student)
        check('时间格式约束返回400',status == 400)
    finally:
        if res_id:
            check('本人取消释放座位',call('POST',f'/reservations/{res_id}/cancel',{},student)[0] == 200)
    status, body = call('GET','/credit',token=student)
    check('信用概览及流水',status == 200 and 'score' in body['data'] and isinstance(body['data']['logs'],list))
    status, body = call('GET','/notifications',token=student)
    check('消息中心',status == 200 and isinstance(body['data'],list))
    check('消息一键已读',call('POST','/notifications/read_all',{},student)[0] == 200)
    status, body = call('GET','/stats/overview',token=admin)
    check('统计总览字段',status == 200 and len(body['data']['trend_14']) == 14)
    status, body = call('GET',f'/stats/heatmap?room_id={room}&date={date}',token=admin)
    check('热力矩阵尺寸',status == 200 and len(body['data']['cells']) == len(body['data']['seats']) and len(body['data']['cells'][0]) == len(body['data']['hours']))
    room_req = {'name':'API临时验证_'+str(time.time_ns()),'location':'验证楼','seat_rows':1,'seat_cols':2,'description':'','building':'API验证楼','floor':'B1','enabled':True,'reason':'API模拟资源验证'}
    status, body = call('POST','/admin/rooms',room_req,admin)
    check('管理端创建房间',status == 200)
    temp = body['data']['id']
    version = body['data']['version']
    try:
        status, generated = call('POST',f'/admin/rooms/{temp}/seats/batch',{'seat_rows':1,'seat_cols':2,'version':version,'reason':'API初次生成模拟座位'},admin)
        check('管理端生成座位', status == 200)
        version = generated['data']['room']['version']
        check('重复生成被阻止',call('POST',f'/admin/rooms/{temp}/seats/batch',{'seat_rows':1,'seat_cols':2,'version':version,'reason':'API重复生成应被拒绝'},admin)[0] == 409)
    finally:
        status, current = call('GET', '/rooms', token=admin)
        if status == 200:
            version = next((room['version'] for room in current['data'] if room['id'] == temp), version)
        check('删除无历史临时房间',call('DELETE',f'/admin/rooms/{temp}',{'version':version,'reason':'API清理无历史模拟资源'},admin)[0] == 200)
    check('注销成功',call('POST','/auth/logout',{},student)[0] == 200)
    check('注销令牌立即失效',call('GET','/auth/profile',token=student)[0] == 401)
    print(f'通过 {len(checks)}/{len(checks)}')

if __name__ == '__main__':
    main()
