"""仅对studyroom_java_test专用服务验证API；模拟预约最终取消。"""
import json
import os
import sys
import time
import uuid
import urllib.request
import urllib.error

BASE = os.environ.get('STUDYROOM_API', 'http://127.0.0.1:8081/api')
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
    check('写入前确认专用测试库', body['data'].get('database_name') == 'studyroom_java_test')
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
    status, policy = call('GET', '/booking-policy', token=student)
    check('校园日期政策与分钟规则', status == 200 and policy['data']['campus_zone'] == 'Asia/Shanghai' and policy['data']['min_duration_minutes'] == 30 and len(policy['data']['dates']) == 3)
    date = policy['data']['dates'][1]
    status, available = call('GET',f'/availability?room_id={room}&date={date}&strategy=earliest',token=student)
    check('最早连续空档与图列表共享结果',status == 200 and available['data']['strategy'] == 'earliest' and isinstance(available['data']['results'],list) and all('available_windows' in seat for seat in available['data']['seats']))
    check('空档最短30分钟且无他人私密记录',all(row['duration_minutes'] >= 30 and not any(key in row for key in ('user_id','username','real_name','code_value')) for row in available['data']['results']))
    status, longest = call('GET',f'/availability?room_id={room}&date={date}&strategy=longest',token=student)
    check('最长按完整单段时长降序',status == 200 and longest['data']['strategy'] == 'longest' and [row['duration_minutes'] for row in longest['data']['results']] == sorted((row['duration_minutes'] for row in longest['data']['results']),reverse=True))
    status, duration = call('GET',f'/availability?room_id={room}&date={date}&strategy=duration&required_minutes=120',token=student)
    check('所需时长只筛足够连续的片段',status == 200 and duration['data']['required_minutes'] == 120 and all(row['duration_minutes'] >= 120 and 'suggested_start' in row and 'suggested_end' in row for row in duration['data']['results']))
    status, body = call('GET',f'/rooms/{room}/seats?date={date}&start=10:00&end=11:00',token=student)
    check('座位图字段与布尔类型',status == 200 and isinstance(body['data']['seats'][0]['occupied'],bool))
    seats = [s for s in body['data']['seats'] if s['status']=='available' and s['bookable_for_slot'] and not s['occupied']]
    seat = seats[-1]['id']
    status, body = call('POST','/reservations/auto',{'room_id':room,'date':date,'start_time':'10:00','end_time':'11:00','top_n':3,'auto_book':False},student)
    check('旧偏好自动分配入口已退出',status == 410)
    check('旧候补新增入口已退出', call('POST', '/waitlist', {'room_id':room,'date':date,'start_time':'10:00','end_time':'11:00'},student)[0] == 410)
    request = {'seat_id':seat,'date':date,'start_time':'10:00','end_time':'11:00'}
    res_id = None
    try:
        status, body = call('POST','/reservations',request,student)
        check('预约创建且日期不带午夜时间',status == 200 and body['data']['res_date'] == date)
        res_id = body['data']['id']
        status, _ = call('POST','/reservations',request,other)
        check('重叠座位返回409',status == 409)
        check('越权取消返回403',call('POST',f'/reservations/{res_id}/cancel',{},other)[0] == 403)
        check('旧无座位码签到请求被拒绝',call('POST',f'/reservations/{res_id}/checkin',{},student)[0] == 400)
        check('旧暂离和返回入口退出',all(call('POST',f'/reservations/{res_id}/{action}',{},student)[0] == 410 for action in ('leave','return')))
        status, body = call('GET','/reservations/mine',token=student)
        check('我的预约包含座位与房间',status == 200 and any(r['id']==res_id and 'room_name' in r for r in body['data']))
        check('未签到预约没有伪造使用记录',next(r for r in body['data'] if r['id']==res_id)['usage_record'] is None)
        time.sleep(1.1)
        status, _ = call('POST','/reservations',{**request,'start_time':'10:01','end_time':'10:30'},student)
        check('不足30分钟返回400',status == 400)
    finally:
        if res_id:
            check('本人取消释放座位',call('POST',f'/reservations/{res_id}/cancel',{},student)[0] == 200)
    status, body = call('GET','/violations/mine',token=student)
    check('本人独立违规明细',status == 200 and isinstance(body['data'],list))
    check('开始前取消未登记独立违规',all(v['reservation_id'] != res_id for v in body['data']))
    status, body = call('GET','/credit',token=student)
    check('只读历史信用及流水',status == 200 and body['data']['history_only'] is True and 'score' in body['data'] and isinstance(body['data']['logs'],list))
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
        proposal = {'change_type':'room.update','target_id':temp,'payload':{**room_req,'version':version,'description':'API无预约影响变更验证','reason':'API核对只读预览与幂等确认'}}
        status, preview = call('POST','/admin/resource-changes/preview',proposal,admin)
        check('资源变更只读预览返回无影响清单',status == 200 and preview['data']['summary']['total_count'] == 0 and bool(preview['data']['target_label']))
        confirmation = {'proposal':preview['data']['normalized_proposal'],'impact_hash':preview['data']['impact_hash'],'operation_id':str(uuid.uuid4())}
        status, changed = call('POST','/admin/resource-changes/confirm',confirmation,admin)
        check('管理员按预览确认无影响变更',status == 200 and changed['data']['cancelled_count'] == 0 and changed['data']['replayed'] is False)
        status, replayed = call('POST','/admin/resource-changes/confirm',confirmation,admin)
        check('同操作确认幂等返回原结果',status == 200 and replayed['data']['operation_id'] == confirmation['operation_id'] and replayed['data']['replayed'] is True)
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
