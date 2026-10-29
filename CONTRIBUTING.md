# 开发、提交与审核

## 固定分支与责任

采用 main + member/a～member/d，不设 develop，不按任务另建分支。负责人 D / @1xiaoyueryuer 统筹仓库，A/B/C 账号待配置，具体路径见 [团队分工](docs/团队分工.md)。成员身份或范围未知且不能从用户授权确定时先核实，不自行认领他人模块。

main 保存交付内容；成员只提交、推送本人分支。每人同时一个待合并功能、一个指向 main 的 PR；修正原 PR，合并后同步 main 再做下一项。负责人初始化固定分支属于仓库建设，普通功能任务不能另开或删除分支。

## 每项功能

1. 阅读相关需求与代码，明确操作者、范围、接口和验收；共享或跨模块改动在方案中记录实际授权，不由 AI 补造“已同意”。
2. 工作区干净后拉取本人分支，再合入 origin/main。不要覆盖他人未提交或未审核的改动。
3. 新功能按模板先写架构方案、再写代码实施方案，然后连续完成实现、相关测试和自查。纯文档和小修复可更新原方案，不新造空文档。
4. 一项可说明、可验证的小功能形成闭环后 commit，精准暂存，检查 diff 与敏感配置；不要按每次调试提交。
5. 有上传授权后只推本人固定分支，建立到 main 的 PR，填写实际验证与未验证项。
6. D 首审其他成员 PR，跨模块由对应成员复核；D 自己的 PR 需要另一位已授权成员独立审核。AI 不能冒充人工批准或自行合并。
7. 检查通过、人工审核完成后，负责人使用普通 Merge commit；保留固定分支，合并后同步再开始新功能。

示例（成员 A，首次跟踪及后续同步不要盲目重复执行）：

```bash
git fetch origin
git switch --track origin/member/a
# 后续任务
git pull --ff-only origin member/a
git fetch origin
git merge origin/main -m "工程：同步主分支到成员A分支"
# 完成功能、方案和验证后，只暂存本次文件
git add <本次文件>
git diff --cached
git commit -m "前端：实现连续可用方案列表"
git push origin member/a
```

## 中文提交

标题为 `类别：具体完成的中文行为`，类别为 **前端、后端、数据、测试、文档、工程**，使用全角冒号。例如 `后端：实现本人预约的时段冲突校验`。不使用“更新”“修改一下”“fix bug”等空话。PR 标题和普通合并标题同样使用中文。

未分享且无人依赖的同功能修正可以 amend；共享历史追加修正，不擅自重写或强推。同步 main 必要时允许单独的中文工程合并提交。v1.0.0-postgresql 基线保留，不因后续规则要求重写原作者或既有基线提交；检查只针对新增提交范围。

长期固定分支使用普通合并保留祖先关系，避免 Squash/Rebase 后重复出现旧改动。不要 push --all/--mirror 或擅自发布标签。

## 检查与实际权限

```powershell
python scripts/check_repository.py
python -m unittest discover -s scripts/tests -v
./scripts/Build.ps1 -Integration
python scripts/api_smoke.py
```

业务检查按变更选择，不因文档调整重复跑全部数据库测试。真实集成库为 studyroom_java_test；HTTP联调针对运行中的模拟服务。

仓库检查验证目录、方案配对、分支、标题与禁止提交的本地文件；不能证明模块授权、功能完整或人工审核真实。CODEOWNERS 只是审核责任人声明，未开启 main 保护时不会强制批准；固定分支命名也不构成每人的独占写权限。

建议 main 要求 PR、至少一名独立批准、Code Owner、讨论解决及 repository-check / backend / frontend 通过。**当前先准备协作流程与四个分支，成员权限、独立审核人和强制保护尚待负责人配置，不声称已经启用。**只有 D 时不能用 D 自己批准 D 的 PR，也不靠临时降低保护绕过审核。新增独立审核人的账号和权限须经负责人确认，按正常 PR 更新责任表。
