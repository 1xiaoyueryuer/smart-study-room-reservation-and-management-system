# Smart Study Room Reservation and Management System

智慧自习室预约与管理系统。当前基线版本：**v1.0.0-postgresql（Java + PostgreSQL）**。后续需求实现继续在本项目迭代；此版本通过 Git 标签保留，源码快照不含个人口令、运行日志或本机工具配置。

基于 [imicola/smart-study-room](https://github.com/imicola/smart-study-room) 的课程项目二次开发：保留原 Vue 3 前端，后端改为 Java 17 + Spring Boot，使用 Docker PostgreSQL 17 和已有 Redis，已移除智能助手。保留原作者 MIT 许可证。

当前是可运行的迁移底座。本组“三种找座方案、投诉申诉、累计违规限制”等业务改造尚待下一阶段落实，详见 [迁移说明与需求差距](docs/MIGRATION.md)。

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

数据库初始化包含3个模拟空间、158个座位、14天模拟历史。预约演示请选择未来有效时段，目前仍保留上游半小时粒度和提前10分钟规则。

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

首次部署或换一台电脑时：

```powershell
./scripts/SetupPostgres.ps1
```

脚本生成随机口令、同步应用配置、创建容器和持久化卷，首次自动创建开发库和测试库。重复运行不会清空数据或轮换口令。不要删除数据卷来修复普通启动问题；已有卷不会因修改 env 文件而自动修改数据库口令。

本机 Redis 复用 `my-redis` 的6379端口，使用3号库和 `studyroom-java:` 键前缀。可选 `docker compose --profile extra-redis up -d redis` 会另建6380实例，使用时需同步修改应用配置。默认启动不会创建重复 Redis。

## 开发与验证

需要 JDK 17、Maven 3.6+、Node 22.12+/24、Docker Desktop。本机已配置 IDEA Maven 路径；换电脑可设置 `$env:STUDYROOM_MAVEN='你的mvn.cmd路径'`，或在 IDEA 打开 `backend/pom.xml`。

前端热更新：在 `frontend/` 运行 `npm ci`、`npm run dev`；默认5173代理到 Java 8080。

```powershell
./scripts/Build.ps1 -Integration
python ./scripts/api_smoke.py
```

集成测试只使用并清理专用测试库 `studyroom_java_test`，代码会检查库名。HTTP联调使用开发服务的模拟账号，测试预约最终取消，临时无历史房间会删除。普通构建可省略 `-Integration`。

[本次验证结果](docs/VALIDATION.md) 与上游资料分开保存。`docs/upstream/` 和 `diagrams/` 是原作者材料，不等于本组贡献或本次测试结果。

后端入口为 `backend/src/main/java/cn/studyroom/`：`Requests` 定义前端契约，`ApiController` 提供接口，`AuthService`/`WebSecurity` 负责权限，`ResourceService` 管理资源，`BookingService` 统一预约与候补事务，`StatsService` 汇总统计，`Store` 处理 SQL 和行映射。

应用仅监听127.0.0.1。上游来源和许可证在本项目中保留；原作者材料不能作为本组的实现或测试结果。
