# Smart Study Room Reservation and Management System

智慧自习室预约与管理系统。当前基线版本：**v1.0.0-postgresql（Java + PostgreSQL）**。后续需求实现继续在本项目迭代；此版本通过 Git 标签保留，源码快照不含个人口令、运行日志或本机工具配置。

基于 [imicola/smart-study-room](https://github.com/imicola/smart-study-room) 的课程项目二次开发：保留原 Vue 3 前端，后端改为 Java 17 + Spring Boot，使用 Docker PostgreSQL 17 和已有 Redis，已移除智能助手。保留原作者 MIT 许可证。

当前是可运行的迁移底座。本组“三种找座方案、投诉申诉、累计违规限制”等业务改造尚待下一阶段落实，详见 [迁移说明与需求差距](docs/MIGRATION.md)。

## 后续开发从这里开始

1. 阅读 [AGENTS.md](AGENTS.md)、[CONTRIBUTING.md](CONTRIBUTING.md)、[团队分工](docs/团队分工.md) 和 [文档索引](docs/README.md)。
2. **当前按单人模式直接在main开发、验证、中文提交并推送，不强制PR或独立审核。**较大新功能维护两份简明方案，小修复更新原文档即可。
3. 既有首版标签v1.0.0-postgresql保留。member/a～member/d只是未来协作准备；多人分工和审批流程以后由用户决定是否启用。
4. 仓库检查：`python scripts/check_repository.py`；检查器测试：`python -m unittest discover -s scripts/tests -v`。业务构建与验证见下文。

智能体开工提示：先读上述约定，当前为单人模式。本次功能为Fxx-名称，按用户授权连续完成设计、实现、相关验证和自查；不覆盖已有无关改动，不制造平行核心或重复报告。用户授权上传后直接推main，不额外等待独立审核。

## 本机运行

打开 Docker Desktop，在本项目目录运行：

```powershell
./scripts/Start.ps1
```

访问 **http://127.0.0.1:8080**。前端已经装入 Java JAR，无需另外启动 Node 服务。

| 角色 | 账号 | 口令 |
|---|---|---|
| 管理员 | admin | admin123 |
| 模拟学生 | stu01 至 stu40 | 123456 |

数据库初始化包含3个模拟空间、158个座位、14天模拟历史。预约按校园时区当天至后两天、整分钟、至少30分钟校验；单段结束可为24:00，不设单次或每日4小时额度。候补和属性偏好自动分配已退出新增入口，历史记录保留。空间分段、日期例外与临时停用统一决定合法开放窗口。

扫码签到：管理员在“管理端 → 座位维护 → 座位码”填写理由生成并查看二维码，可轮换或启停。普通用户从“我的预约 → 扫码签到”上传真实二维码图片，或主动开启摄像头；只有本人预约、匹配且有效的当前座位码和合法签到时段才能签到。图片在本机识别；设备与实体打印的实际验证范围见F03实施方案。

使用与结束：签到后生成唯一使用记录，“我的预约”展示签到、结束、结算登记和使用时长。提前签退释放剩余时段；到期自动按原结束时间结算，不额外处罚漏点签退。不再提供暂离/返回操作，历史暂离依据保留，短暂离开不延长原结束。新记录不再增加信用奖励或扣分。

停止 Java：`./scripts/Stop.ps1`。该脚本不会关闭共享数据库或 Redis。

修改代码后：

```powershell
./scripts/Build.ps1
./scripts/Stop.ps1
./scripts/Start.ps1
```

Java 从 `.local/server.jar` 独立副本运行，不锁定 Maven 构建产物。日志在 `.local/server.log` 与 `.local/server-error.log`。

## PostgreSQL 配置

| 项目 | 本机配置 |
|---|---|
| Docker 服务 / 容器 | `db` / `studyroom-java-db-1` |
| 镜像 | `postgres:17-alpine` |
| 地址 | `127.0.0.1:5433` |
| 用户 | `studyroom` |
| 开发库 | `studyroom_java` |
| 测试库 | `studyroom_java_test` |
| 持久化卷 | `studyroom-java_studyroom-postgres` |
| 应用配置 | `.local/application-local.properties` |
| Docker 密码文件 | `.local/postgres.env` |

随机口令只保存在上述被 Git 忽略的本地文件中。可在 IDEA、DataGrip、DBeaver 等工具中按该配置连接。数据库与旧 openGauss 的5432端口独立；没有改动原有业务数据。

数据库结构由 Flyway 增量迁移管理。旧版无迁移历史的数据库首次升级默认拒绝启动；停服务并备份后，通过应用参数 `--studyroom.migration.adopt-legacy=true` 一次接管，程序核对结构与V1一致后才升级，完成后移除参数。未知结构不能盲目baseline；恢复与验证见 [F01架构方案](docs/features/F01-校园账号与权限/校园账号与权限功能架构方案.md)。

首次部署或换一台电脑时：

```powershell
./scripts/SetupPostgres.ps1
```

脚本生成随机口令、同步应用配置、创建容器和持久化卷，首次自动创建开发库和测试库。重复运行不会清空数据或轮换口令。不要删除数据卷来修复普通启动问题；已有卷不会因修改 env 文件而自动修改数据库口令。

本机 Redis 复用 `my-redis` 的6379端口，使用3号库和 `studyroom-java:` 键前缀。可选 `docker compose --profile extra-redis up -d redis` 会另建6380实例，使用时需同步修改应用配置。默认启动不会创建重复 Redis。

## 开发与验证

需要 JDK 17、Maven 3.6+、Node 24+、Docker Desktop。前端二维码解码依赖要求Node24，本机实际为24.14.0。本机已配置 IDEA Maven 路径；换电脑可设置 `$env:STUDYROOM_MAVEN='你的mvn.cmd路径'`，或在 IDEA 打开 `backend/pom.xml`。

前端热更新：在 `frontend/` 运行 `npm ci`、`npm run dev`；默认5173代理到 Java 8080。

```powershell
./scripts/Build.ps1 -Integration
```

集成测试只使用并清理专用测试库 `studyroom_java_test`，代码会检查库名。普通构建可省略 `-Integration`。需要额外HTTP联调时，先在8081启动明确连接测试库且已有模拟种子的独立服务，再执行 `python ./scripts/api_smoke.py`；脚本默认8081，并在任何业务写入前核对健康响应的实际库名，只允许 `studyroom_java_test`。可通过 `STUDYROOM_API` 指定专用测试服务地址，但不能指向开发库；模拟预约最终取消，临时无历史房间会删除。

[本次验证结果](docs/VALIDATION.md) 与上游资料分开保存。`docs/upstream/` 和 `diagrams/` 是原作者材料，不等于本组贡献或本次测试结果。

后端入口为 `backend/src/main/java/cn/studyroom/`：`Requests` 定义前端契约，控制器提供接口，`AuthService`/`WebSecurity` 负责权限，`UserService` 维护校园账号，`ResourceService` 管理资源，`OpeningScheduleService` 计算合法窗口，`Slot`/`BookingService` 统一预约时间与事务，`StatsService` 汇总统计，`Store` 处理 SQL 和保真时间映射。

应用仅监听127.0.0.1。上游来源和许可证在本项目中保留；原作者材料不能作为本组的实现或测试结果。
