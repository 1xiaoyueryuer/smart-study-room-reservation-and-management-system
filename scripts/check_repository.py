"""检查自习室仓库的固定分支、中文标题、文档配对和本地文件排除约定。"""
import argparse
import os
from pathlib import Path, PurePosixPath
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
REPOSITORY = '1xiaoyueryuer/smart-study-room-reservation-and-management-system'
MEMBER_BRANCHES = {'member/a', 'member/b', 'member/c', 'member/d'}
TITLE = re.compile(r'^(前端|后端|数据|测试|文档|工程)：\s*(\S.*)$')
TRIVIAL = {'更新', '修改', '修复', '调整', '完善', '更新文档', '修改一下', '修复一些问题'}
REQUIRED = {'AGENTS.md', 'CONTRIBUTING.md', 'README.md', 'docs/README.md', 'docs/团队分工.md'}


def title_error(title):
    match = TITLE.fullmatch(title.strip())
    if not match:
        return '标题须为 类别：具体中文行为，类别为前端/后端/数据/测试/文档/工程'
    body = match.group(2).strip()
    if body in TRIVIAL or len(body) < 4 or not re.search(r'[\u4e00-\u9fff]', body):
        return '标题须说明具体中文交付结果，不能只写更新或修改等空话'
    return None


def branch_errors(branch, *, pull_request=False, head_repository=None):
    allowed = MEMBER_BRANCHES if pull_request else MEMBER_BRANCHES | {'main'}
    result = []
    if branch not in allowed:
        result.append(f'不符合固定分支约定：{branch!r}')
    if pull_request and head_repository != REPOSITORY:
        result.append('团队PR必须来自本仓库已分配的固定成员分支')
    return result


def file_errors(paths):
    errors = [f'缺少入口：{p}' for p in sorted(REQUIRED - set(paths))]
    pairs = {}
    for name in paths:
        path = PurePosixPath(name)
        if any(part in {'.local', 'node_modules', 'target', '__pycache__', 'work'} for part in path.parts) or path.name == '.env':
            errors.append(f'不应提交个人配置、缓存或草稿：{name}')
        if path.suffix.lower() == '.md':
            if len(path.parts) == 1 and name not in {'AGENTS.md', 'CONTRIBUTING.md', 'README.md'}:
                errors.append(f'根目录不散放Markdown：{name}')
            if path.name.lower() in {'agent.md', 'agents.md'} and name != 'AGENTS.md':
                errors.append(f'智能体规则只维护根AGENTS.md：{name}')
        if path.parts[:2] != ('docs', 'features'):
            continue
        if len(path.parts) != 4:
            errors.append(f'功能目录仅放两份方案：{name}')
            continue
        folder = path.parts[2]
        match = re.fullmatch(r'F[0-9]{2,}-(.+)', folder)
        if not match:
            errors.append(f'功能目录编号或名称不符：{name}')
            continue
        feature = match.group(1)
        expected = {f'{feature}功能架构方案.md', f'{feature}功能代码实施方案.md'}
        if path.name not in expected:
            errors.append(f'功能中存在非约定文档：{name}')
        pairs.setdefault(folder, set()).add(path.name)
    for folder, present in pairs.items():
        feature = folder.split('-', 1)[1]
        for missing in {f'{feature}功能架构方案.md', f'{feature}功能代码实施方案.md'} - present:
            errors.append(f'功能方案不成对：docs/features/{folder}/{missing}')
    return errors


def git(*args):
    return subprocess.run(['git', *args], cwd=ROOT, capture_output=True, encoding='utf-8', check=True).stdout


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base', help='仅检查该完整SHA之后的新增提交，不追溯上游历史')
    parser.add_argument('--head', default='HEAD')
    parser.add_argument('--check-head', action='store_true')
    parser.add_argument('--branch')
    args = parser.parse_args()
    try:
        paths = set(git('ls-files', '-z', '--cached', '--others', '--exclude-standard').split('\0'))
        paths = {p for p in paths if p and (ROOT / p).is_file()}
        errors = file_errors(paths)
        pr_branch = os.environ.get('PR_HEAD_BRANCH')
        branch = pr_branch if pr_branch is not None else args.branch or git('branch', '--show-current').strip()
        errors += branch_errors(branch, pull_request=pr_branch is not None, head_repository=os.environ.get('PR_HEAD_REPOSITORY'))
        titles = []
        if args.base:
            if not re.fullmatch(r'[a-fA-F0-9]{40}', args.base) or not re.fullmatch(r'[a-fA-F0-9]{40}|HEAD', args.head):
                raise ValueError('提交范围须使用完整SHA（head也可为HEAD）')
            titles = git('log', '--format=%s', f'{args.base}..{args.head}').splitlines()
        elif args.check_head:
            titles = [git('log', '-1', '--format=%s').strip()]
        if os.environ.get('PR_TITLE') is not None:
            titles.append(os.environ['PR_TITLE'])
        for title in titles:
            problem = title_error(title)
            if problem:
                errors.append(f'{title!r}：{problem}')
    except (ValueError, subprocess.CalledProcessError) as exc:
        print(f'检查无法完成：{exc}', file=sys.stderr)
        return 1
    if errors:
        print('\n'.join('错误：' + e for e in errors), file=sys.stderr)
        return 1
    print('固定分支、中文标题、文档位置、方案配对及本地文件排除检查通过。')
    print('此检查不证明成员授权、权限隔离、功能完整或人工审核。')
    return 0


if __name__ == '__main__':
    if sys.platform == 'win32':
        sys.stdout.reconfigure(encoding='utf-8')
        sys.stderr.reconfigure(encoding='utf-8')
    sys.exit(main())
