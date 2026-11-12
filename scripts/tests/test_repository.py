import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('repository_check', Path(__file__).resolve().parents[1] / 'check_repository.py')
check = importlib.util.module_from_spec(spec)
spec.loader.exec_module(check)


class RepositoryConventionsTest(unittest.TestCase):
    def test_completed_chinese_change_can_include_technical_names(self):
        self.assertIsNone(check.title_error('后端：实现预约API的时段冲突检查'))
        self.assertIsNone(check.title_error('工程：建立固定成员分支协作规范'))

    def test_vague_or_english_only_commit_is_rejected(self):
        for title in ['fix bug', '前端: 完成预约页面', '文档：更新', '工程：update API']:
            with self.subTest(title=title):
                self.assertIsNotNone(check.title_error(title))

    def test_four_members_and_main_are_valid_push_branches(self):
        for branch in ['main', 'member/a', 'member/b', 'member/c', 'member/d']:
            self.assertEqual([], check.branch_errors(branch))
        self.assertTrue(check.branch_errors('feature/seat-map'))

    def test_pull_request_must_be_local_member_branch(self):
        self.assertEqual([], check.branch_errors('member/d', pull_request=True, head_repository=check.REPOSITORY))
        self.assertTrue(check.branch_errors('main', pull_request=True, head_repository=check.REPOSITORY))
        self.assertTrue(check.branch_errors('member/a', pull_request=True, head_repository='someone/fork'))

    def test_feature_has_two_documents_without_completion_report(self):
        paths = set(check.REQUIRED)
        folder = 'docs/features/F01-找座方案/'
        paths.add(folder + '找座方案功能架构方案.md')
        self.assertTrue(check.file_errors(paths))
        paths.add(folder + '找座方案功能代码实施方案.md')
        self.assertEqual([], check.file_errors(paths))
        paths.add(folder + '完成报告.md')
        self.assertTrue(check.file_errors(paths))

    def test_archived_reference_is_not_treated_as_a_new_feature(self):
        self.assertEqual([], check.file_errors(set(check.REQUIRED) | {'docs/archive/旧版说明.md', 'docs/MIGRATION.md'}))

    def test_personal_config_and_build_outputs_cannot_be_tracked(self):
        for path in ['.local/postgres.env', '.env', 'backend/target/app.jar', 'frontend/node_modules/vue/index.js', 'work/debug.txt']:
            with self.subTest(path=path):
                self.assertTrue(check.file_errors(set(check.REQUIRED) | {path}))
        self.assertEqual([], check.file_errors(set(check.REQUIRED) | {'application-local.example.properties', '.env.example'}))

    def test_only_one_agent_entry_and_no_extra_root_reports(self):
        for path in ['agents.md', 'backend/AGENTS.md', '完成报告.md']:
            with self.subTest(path=path):
                self.assertTrue(check.file_errors(set(check.REQUIRED) | {path}))


if __name__ == '__main__':
    unittest.main()
