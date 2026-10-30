"""Seed-compatible fixture configuration; all HTTP is mocked, never contact a DB."""
import contextlib
import importlib.util
import io
import os
from pathlib import Path
import unittest
from unittest.mock import patch

MODULE = Path(__file__).resolve().parents[1] / 'api_smoke.py'
spec = importlib.util.spec_from_file_location('api_smoke_config_subject', MODULE)
smoke = importlib.util.module_from_spec(spec)
spec.loader.exec_module(smoke)


class SmokeConfigurationTest(unittest.TestCase):
    def setUp(self):
        smoke.checks.clear()

    def test_seed_defaults_remain_compatible_without_room_override(self):
        with patch.dict(os.environ, {}, clear=True):
            accounts, room = smoke.smoke_settings()
        self.assertEqual(accounts, {'admin': {'username': 'admin', 'password': 'admin123'},
            'owner': {'username': 'stu01', 'password': '123456'}, 'other': {'username': 'stu02', 'password': '123456'}})
        self.assertIsNone(room)

    def test_six_overrides_drive_real_login_requests_without_printing_secrets(self):
        config = {f'STUDYROOM_SMOKE_{role}_{field}': f'fixture_{role.lower()}_{field.lower()}'
                  for role in ('ADMIN', 'OWNER', 'OTHER') for field in ('USERNAME', 'PASSWORD')}
        config['STUDYROOM_SMOKE_ROOM_ID'] = '7'
        class ProfileReached(Exception):
            pass
        def response(method, path, body=None, token=None):
            if path == '/health':
                return 200, {'data': {'backend': 'java', 'database': 'PostgreSQL', 'database_name': 'studyroom_java_test'}}
            if path == '/rooms':
                return 401, {}
            if path == '/auth/login':
                if body['password'].startswith('intentionally-wrong-'):
                    return 401, {}
                role = 'admin' if body['username'] == config['STUDYROOM_SMOKE_ADMIN_USERNAME'] else 'staff'
                return 200, {'data': {'token': 'synthetic-test-token', 'user': {'role': role, 'status': 'active'}}}
            if path == '/auth/profile':
                raise ProfileReached()
            self.fail('unexpected HTTP call')
        output = io.StringIO()
        with patch.dict(os.environ, config, clear=True), patch.object(smoke, 'call', side_effect=response) as api:
            with contextlib.redirect_stdout(output), self.assertRaises(ProfileReached):
                smoke.main()
            accounts, room = smoke.smoke_settings()
        self.assertEqual(room, 7)
        logins = [item.args[2] for item in api.call_args_list if item.args[:2] == ('POST', '/auth/login')]
        self.assertEqual(logins[0]['username'], accounts['owner']['username'])
        self.assertEqual(logins[1:], [accounts['admin'], accounts['owner'], accounts['other']])
        for account in accounts.values():
            self.assertNotIn(account['password'], output.getvalue())
        self.assertNotIn('synthetic-test-token', output.getvalue())

    def test_bad_room_or_conflicting_credentials_fail_explicitly(self):
        for room in ('', '0', '-1', 'not-an-id', '9223372036854775808'):
            with self.subTest(room=room), patch.dict(os.environ, {'STUDYROOM_SMOKE_ROOM_ID': room}, clear=True):
                with self.assertRaisesRegex(AssertionError, '空间编号'):
                    smoke.smoke_settings()
        for config in ({'STUDYROOM_SMOKE_ADMIN_PASSWORD': ''}, {'STUDYROOM_SMOKE_OTHER_USERNAME': 'stu01'}):
            with patch.dict(os.environ, config, clear=True), self.assertRaises(AssertionError):
                smoke.smoke_settings()

    def test_login_does_not_accept_disabled_or_wrong_role_with_a_200_shell(self):
        for role, status in (('student', 'active'), ('admin', 'disabled')):
            with patch.object(smoke, 'call', return_value=(200, {'data': {'token': 'synthetic', 'user': {'role': role, 'status': status}}})):
                with contextlib.redirect_stdout(io.StringIO()), self.assertRaises(AssertionError):
                    smoke.login_account('test admin', {'username': 'fixture', 'password': 'fixture'}, {'admin'})

    def test_room_selection_uses_real_legal_free_seats_without_seed_size_or_writes(self):
        rooms = [{'id': 1, 'enabled': False, 'seat_count': 2}, {'id': 2, 'enabled': True, 'seat_count': 1}, {'id': 7, 'enabled': True, 'seat_count': 1}]
        def response(method, path, token=None):
            self.assertEqual(method, 'GET')
            return 200, {'data': {'seats': [{'status': 'available', 'bookable_for_slot': True, 'occupied': path.startswith('/rooms/2/')}]}}
        with patch.object(smoke, 'call', side_effect=response) as api, contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(7, smoke.choose_room(rooms, None, 'synthetic', '2026-11-01'))
            self.assertEqual(2, api.call_count)
        with patch.object(smoke, 'call', side_effect=response) as api, contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(7, smoke.choose_room(rooms, 7, 'synthetic', '2026-11-01'))
            api.assert_called_once_with('GET', '/rooms/7/seats?date=2026-11-01&start=10:00&end=11:00', token='synthetic')
        with patch.object(smoke, 'call') as api, contextlib.redirect_stdout(io.StringIO()):
            for configured in (999, 1):
                with self.assertRaises(AssertionError):
                    smoke.choose_room(rooms, configured, 'synthetic', '2026-11-01')
            api.assert_not_called()

    def test_successful_creation_with_bad_date_receipt_still_cancels_the_known_reservation(self):
        for data in ({'id': 17, 'res_date': 'wrong-contract-value'}, {'id': 17}):
            with self.subTest(data=data):
                def response(method, path, body=None, token=None):
                    if (method, path) == ('POST', '/reservations'):
                        return 200, {'data': data}
                    self.assertEqual((method, path, body, token), ('POST', '/reservations/17/cancel', {}, 'owner-token'))
                    return 200, {'data': {'id': 17, 'status': 'cancelled'}}
                output = io.StringIO()
                with patch.object(smoke, 'call', side_effect=response) as api, contextlib.redirect_stdout(output):
                    with self.assertRaisesRegex(AssertionError, '预约创建且日期不带午夜时间'):
                        smoke.verify_reservation_roundtrip({'seat_id': 1}, 'owner-token', 'other-token', '2026-11-01')
                self.assertEqual(api.call_count, 2)
                self.assertIn('FAIL 预约创建且日期不带午夜时间', output.getvalue())
                self.assertIn('PASS 本人取消释放座位', output.getvalue())
                self.assertNotIn('owner-token', output.getvalue())

    def test_untrusted_creation_identifiers_never_become_cleanup_targets(self):
        for identifier in (True, False, 0, -1, '17', 9223372036854775808):
            with self.subTest(identifier=identifier):
                with patch.object(smoke, 'call', return_value=(200, {'data': {'id': identifier, 'res_date': '2026-11-01'}})) as api:
                    with contextlib.redirect_stdout(io.StringIO()), self.assertRaises(AssertionError):
                        smoke.verify_reservation_roundtrip({}, 'owner-token', 'other-token', '2026-11-01')
                api.assert_called_once_with('POST', '/reservations', {}, 'owner-token')

    def test_missing_room_creation_version_recovers_authoritative_version_for_cleanup(self):
        def response(method, path, body=None, token=None):
            if (method, path) == ('POST', '/admin/rooms'):
                return 200, {'data': {'id': 23}}
            if (method, path) == ('GET', '/rooms'):
                return 200, {'data': [{'id': 23, 'version': 4}]}
            self.assertEqual((method, path, token), ('DELETE', '/admin/rooms/23', 'admin-token'))
            self.assertEqual(body['version'], 4)
            return 200, {'data': {}}
        output = io.StringIO()
        with patch.object(smoke, 'call', side_effect=response) as api, contextlib.redirect_stdout(output):
            with self.assertRaisesRegex(AssertionError, '新建房间版本完整'):
                smoke.verify_temporary_room('admin-token')
        self.assertEqual(api.call_count, 3)
        self.assertIn('FAIL 新建房间版本完整', output.getvalue())
        self.assertIn('PASS 删除无历史临时房间', output.getvalue())
        self.assertNotIn('admin-token', output.getvalue())


if __name__ == '__main__':
    unittest.main()
