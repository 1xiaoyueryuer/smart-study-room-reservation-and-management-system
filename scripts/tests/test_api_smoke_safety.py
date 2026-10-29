"""联调脚本误配开发地址时必须在第一次业务调用前停止。"""
import contextlib
import importlib.util
import io
from pathlib import Path
import unittest
from unittest.mock import patch

MODULE = Path(__file__).resolve().parents[1] / 'api_smoke.py'
spec = importlib.util.spec_from_file_location('api_smoke_safety_subject', MODULE)
smoke = importlib.util.module_from_spec(spec)
spec.loader.exec_module(smoke)


class SmokeDatabaseSafetyTest(unittest.TestCase):
    def setUp(self):
        smoke.checks.clear()

    def health(self, database):
        data = {'backend': 'java', 'database': 'PostgreSQL'}
        if database is not None:
            data['database_name'] = database
        return 200, {'data': data}

    def test_development_unknown_and_missing_database_stop_before_business_calls(self):
        for database in ('studyroom_java', 'other_test_database', None):
            with self.subTest(database=database):
                with patch.object(smoke, 'call', return_value=self.health(database)) as api:
                    with contextlib.redirect_stdout(io.StringIO()):
                        with self.assertRaisesRegex(AssertionError, '专用测试库'):
                            smoke.main()
                api.assert_called_once_with('GET', '/health')

    def test_verified_test_database_proceeds_to_read_only_authentication_probe(self):
        class ProbeReached(Exception):
            pass
        def response(method, path):
            if path == '/health':
                return self.health('studyroom_java_test')
            raise ProbeReached()
        with patch.object(smoke, 'call', side_effect=response) as api:
            with contextlib.redirect_stdout(io.StringIO()):
                with self.assertRaises(ProbeReached):
                    smoke.main()
        self.assertEqual(api.call_args_list, [unittest.mock.call('GET', '/health'), unittest.mock.call('GET', '/rooms')])


if __name__ == '__main__':
    unittest.main()
