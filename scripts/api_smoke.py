"""仅对studyroom_java_test专用服务验证API；模拟预约最终取消，投诉保留测试历史。"""
import json
import os
import sys
import time
import uuid
from datetime import datetime, timedelta, timezone
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
    status, eligibility = call('GET', '/booking-eligibility', token=student)
    check('本人资格读取服务端独立限制集合',status == 200 and isinstance(eligibility['data']['can_book'],bool) and isinstance(eligibility['data']['active_restrictions'],list) and eligibility['data']['campus_zone'] == 'Asia/Shanghai')
    status, restrictions = call('GET', '/restrictions/mine', token=student)
    check('本人独立限制历史不公开管理私有核实依据',status == 200 and isinstance(restrictions['data'],list) and all(not any(key in item for key in ('verification_note','release_verification_note','subject')) for item in restrictions['data']))
    check('自动限制仅展示真实三来源批次快照', all('automatic_evaluation' in item and (item['automatic_evaluation'] is None if item['source'] != 'automatic' else item['automatic_evaluation']['threshold'] == 3 and item['automatic_evaluation']['duration_hours'] == 168 and len(item['automatic_evaluation']['source_violations']) == 3 and all(not any(key in source for key in ('source_event_id','complaint_id','reporter_id','verification_note')) for source in item['automatic_evaluation']['source_violations'])) for item in restrictions['data']))
    check('普通用户不能读取全校限制管理',call('GET','/admin/restrictions',token=student)[0] == 403)
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
    check('违规通知时间只来自可靠关联且不公开核实私有资料',all('notified_at' in item and 'notification_id' in item and not any(key in item for key in ('source_event_id','complaint_id','reporter','private_note','identity_basis')) and (item['source'] != 'manual' or item['reservation_id'] is None) for item in body['data']))
    check('开始前取消未登记独立违规',all(v['reservation_id'] != res_id for v in body['data']))
    status, appeals = call('GET','/appeals/mine',token=student)
    check('本人申诉进度与原通知依据',status == 200 and isinstance(appeals['data'],list) and all('notification_basis' in item and 'violation_snapshot' in item and not any(key in item for key in ('subject','source_event_id','complaint_id','private_note')) for item in appeals['data']))
    check('普通账号不能查看管理申诉待办',call('GET','/admin/appeals',token=student)[0] == 403)
    if body['data']:
        status, appeal_context = call('GET',f"/violations/{body['data'][0]['id']}/appeal-eligibility",token=student)
        check('申诉条件读取服务端通知期限而非客户端推断',status == 200 and isinstance(appeal_context['data']['eligibility']['can_submit'],bool) and 'deadline' in appeal_context['data']['eligibility'] and appeal_context['data']['campus_zone'] == 'Asia/Shanghai')
    complaint_req = {'type':'occupation','reservation_id':res_id,'description':'API工程模拟：关联本人取消历史留证，不延期或复活预约','attachment_ids':[],'operation_id':str(uuid.uuid4())}
    check('不能关联他人预约求助',call('POST','/complaints',{**complaint_req,'operation_id':str(uuid.uuid4())},other)[0] == 403)
    status, submitted = call('POST','/complaints',complaint_req,student)
    check('本人历史求助提交后仅待受理',status == 200 and submitted['data']['status'] == 'submitted' and submitted['data']['reservation_id'] == res_id and submitted['data']['attachment_count'] == 0)
    complaint_id = submitted['data']['id']
    status, repeated = call('POST','/complaints',complaint_req,student)
    check('同操作重试不重复投诉',status == 200 and repeated['data']['id'] == complaint_id and repeated['data']['replayed'] is True)
    status, detail = call('GET',f'/complaints/{complaint_id}',token=student)
    check('本人详情保留提交事件',status == 200 and any(event['type'] == 'submitted' for event in detail['data']['events']))
    check('他人不能读投诉详情',call('GET',f'/complaints/{complaint_id}',token=other)[0] == 403)
    status, complaints = call('GET','/complaints/mine',token=student)
    check('刷新仍能查到本人投诉',status == 200 and any(item['id'] == complaint_id for item in complaints['data']))
    service_req = {'type':'service','description':'API工程模拟：无预约的一般服务投诉','attachment_ids':[],'operation_id':str(uuid.uuid4())}
    status, service = call('POST','/complaints',service_req,student)
    check('一般服务投诉可无预约或图片',status == 200 and service['data']['reservation_id'] is None and service['data']['attachment_count'] == 0)
    service_target = service['data']['service_target']
    check('一般服务首次反馈目标从提交起算24小时',service_target['kind'] == 'first_feedback' and service_target['basis'] == 'service_24h' and service_target['status'] == 'running' and datetime.fromisoformat(service_target['due_at']) - datetime.fromisoformat(service['data']['submitted_at']) == timedelta(hours=24))
    service_id = service['data']['id']
    check('普通用户不能读管理投诉',call('GET',f'/admin/complaints/{service_id}',token=student)[0] == 403)
    status, managed = call('GET',f'/admin/complaints/{service_id}',token=admin)
    check('管理员读取待受理服务投诉',status == 200 and managed['data']['status'] == 'submitted')
    acceptance = {'version':managed['data']['version'],'operation_id':str(uuid.uuid4())}
    status, accepted = call('POST',f'/admin/complaints/{service_id}/accept',acceptance,admin)
    check('受理只转处理中并保留处理人',status == 200 and accepted['data']['complaint']['status'] == 'processing' and accepted['data']['complaint']['handler'] is not None)
    check('受理不能冒充一般服务首次正式反馈',accepted['data']['complaint']['service_target']['status'] == 'running' and accepted['data']['complaint']['service_target']['due_at'] == service_target['due_at'])
    status, accepted_again = call('POST',f'/admin/complaints/{service_id}/accept',acceptance,admin)
    check('受理原操作重试不重复',status == 200 and accepted_again['data']['replayed'] is True)
    verification = {'version':accepted['data']['complaint']['version'],'operation_id':str(uuid.uuid4()),'outcome':'service_verified','occurred_at':datetime.now(timezone(timedelta(hours=8))).isoformat(timespec='microseconds'),'public_note':'API工程模拟：服务情况已核实，尚待后续反馈','private_note':'API_PRIVATE_VERIFICATION_TEST_ONLY','facts_confirmed':True}
    status, verified = call('POST',f'/admin/complaints/{service_id}/verifications',verification,admin)
    check('无预约服务问题可核实且仍处理中',status == 200 and verified['data']['complaint']['status'] == 'processing')
    manual_path = f"/admin/complaint-events/{verified['data']['event_id']}/violations"
    check('一般服务核实事件不能登记拒不配合违规',call('POST',manual_path,{'operation_id':str(uuid.uuid4()),'reason':'API工程模拟：不合格来源必须拒绝，不实际处罚'},admin)[0] == 400)
    check('普通用户不能手动登记违规',call('POST',manual_path,{'operation_id':str(uuid.uuid4()),'reason':'API工程模拟越权拒绝'},student)[0] == 403)
    status, verified_again = call('POST',f'/admin/complaints/{service_id}/verifications',verification,admin)
    check('核实原操作重试不重复事件',status == 200 and verified_again['data']['replayed'] is True)
    status, public_progress = call('GET',f'/complaints/{service_id}',token=student)
    check('本人只读公开核实进度',status == 200 and any(event.get('outcome') == 'service_verified' and event.get('public_note') == verification['public_note'] for event in public_progress['data']['events']) and verification['private_note'] not in json.dumps(public_progress,ensure_ascii=False))
    status, replayed_service = call('POST','/complaints',service_req,student)
    check('已受理后原提交重放返回真实进度',status == 200 and replayed_service['data']['replayed'] is True and replayed_service['data']['status'] == 'processing')
    status, reminders = call('GET','/reminders/mine',token=student)
    check('本人提醒明确不计违规且不泄投诉来源',status == 200 and all(item['counts_as_violation'] is False and not any(key in item for key in ('complaint_id','reporter','private_note','identity_basis')) for item in reminders['data']))
    feedback_req = {'version':verified['data']['complaint']['version'],'operation_id':str(uuid.uuid4()),'result':'API工程模拟：服务处理结果待本人确认','basis':'工程测试核实记录，仅验证流程，不代表真实现场处理'}
    status, feedback = call('POST',f'/admin/complaints/{service_id}/feedback',feedback_req,admin)
    check('正式反馈转待确认并给出七天',status == 200 and feedback['data']['complaint']['status'] == 'awaiting_confirmation')
    first_feedback = feedback['data']['complaint']['current_feedback']
    first_target = feedback['data']['complaint']['service_target']
    check('首个正式反馈停止服务目标且保留原截止',first_target['status'] == 'completed' and first_target['completed_at'] == first_feedback['feedback_at'] and first_target['due_at'] == service_target['due_at'] and first_target['remaining_seconds'] is None)
    check('确认期从本次反馈精确起算7乘24小时',datetime.fromisoformat(first_feedback['deadline']) - datetime.fromisoformat(first_feedback['feedback_at']) == timedelta(days=7))
    objection = {'version':feedback['data']['complaint']['version'],'operation_id':str(uuid.uuid4()),'description':'API工程模拟：补充异议，要求继续核查'}
    check('他人不能替本人提交异议',call('POST',f'/complaints/{service_id}/object',objection,other)[0] == 403)
    status, objected = call('POST',f'/complaints/{service_id}/object',objection,student)
    check('本人异议回到处理中',status == 200 and objected['data']['complaint']['status'] == 'processing')
    status, feedback_again = call('POST',f'/admin/complaints/{service_id}/feedback',{**feedback_req,'version':objected['data']['complaint']['version'],'operation_id':str(uuid.uuid4()),'result':'API工程模拟：继续核查后的新反馈'},admin)
    check('再次反馈生成新一轮并保留旧反馈',status == 200 and feedback_again['data']['complaint']['current_feedback']['id'] != first_feedback['id'])
    latest_feedback = feedback_again['data']['complaint']['current_feedback']
    check('异议后新反馈不重启首次反馈目标',feedback_again['data']['complaint']['service_target']['completed_at'] == first_target['completed_at'] and feedback_again['data']['complaint']['service_target']['due_at'] == first_target['due_at'])
    check('新一轮仍按本次反馈起算七天',datetime.fromisoformat(latest_feedback['deadline']) - datetime.fromisoformat(latest_feedback['feedback_at']) == timedelta(days=7))
    confirmation = {'version':feedback_again['data']['complaint']['version'],'operation_id':str(uuid.uuid4())}
    status, confirmed = call('POST',f'/complaints/{service_id}/confirm',confirmation,student)
    check('本人确认结案保留真实关闭类型',status == 200 and confirmed['data']['complaint']['status'] == 'closed' and confirmed['data']['complaint']['closure_type'] == 'confirmed')
    status, confirmation_again = call('POST',f'/complaints/{service_id}/confirm',confirmation,student)
    check('确认原操作重试不重复结案',status == 200 and confirmation_again['data']['replayed'] is True)
    status, retractable = call('POST','/complaints',{**service_req,'description':'API工程模拟：未受理撤回验证','operation_id':str(uuid.uuid4())},student)
    check('撤回测试记录保持待受理',status == 200 and retractable['data']['status'] == 'submitted')
    retract_id = retractable['data']['id']
    status, withdrawn = call('POST',f'/complaints/{retract_id}/withdraw',{'version':retractable['data']['version'],'operation_id':str(uuid.uuid4())},student)
    check('本人仅撤回尚未受理记录',status == 200 and withdrawn['data']['complaint']['status'] == 'withdrawn')
    check('撤回停止目标但不伪造完成',withdrawn['data']['complaint']['service_target']['status'] == 'withdrawn' and withdrawn['data']['complaint']['service_target']['completed_at'] is None and withdrawn['data']['complaint']['service_target']['stopped_at'] is not None)
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
