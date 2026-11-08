# 智慧自习室预约与管理系统

**Smart Study Room Reservation and Management System**

当前版本：`v1.0.0-postgresql`（见 [VERSION](VERSION)）

面向高校自习空间的预约与管理系统，由学生、教职工与管理员共同使用。系统把「找座 → 预约 → 扫码签到 → 使用结算 → 异常求助 → 核实处置 → 违规与申诉 → 记录与统计」串成一个可追踪的闭环：前端为 Vue 3 单页应用，后端为 Java 17 / Spring Boot 服务，数据库使用 PostgreSQL 17，Redis 承担限流与令牌撤销；前端构建产物直接打进后端 JAR，运行时只需一个 Java 进程。

| | |
|---|---|
| 后端 | Java 17 · Spring Boot 2.7.18 · Spring JDBC |
| 前端 | Vue 3 · Vite · Pinia · ECharts · Three.js |
| 存储 | PostgreSQL 17（Flyway 增量迁移）· Redis 7.2 |
| 入口 | <http://127.0.0.1:8080>（应用仅监听本机回环地址） |
| 许可 | [MIT](LICENSE) |

## 目录

- [项目简介](#项目简介)
- [功能一览](#功能一览)
- [技术栈](#技术栈)
- [系统架构](#系统架构)
- [目录结构](#目录结构)
- [快速开始](#快速开始)
- [数据库](#数据库)
- [测试与验证](#测试与验证)
- [文档索引](#文档索引)
- [团队分工](#团队分工)

## 项目简介

自习空间是高校里最紧张的资源之一，实际使用中存在三类典型问题：满座时难以判断哪里还能约、座位图信息密集难以快速看清可约状态、预约权益与现场就座之间缺少衔接。本系统围绕这三类问题设计：

- **找得到**：以真实合法空档为基础，提供「最早可约」「连续可用最久」「满足所需学习时长」三种找座方案；平面图、逐段列表与 3D 视图读取同一份计算结果。
- **看得懂**：座位编号、位置与可约时段在图上和列表中都可见，状态不依赖颜色单一表达。
- **管得住**：预约、签到、使用记录、资源变更、投诉、违规、限制与申诉各有明确状态与失败反馈；关键动作与操作理由留痕，越权与冲突在服务端拒绝。

时间规则统一以校园时区 `Asia/Shanghai` 为准：可预约日期为校园自然日当天及后两天（D、D+1、D+2），起止取整分钟、单次至少 30 分钟，`24:00` 表示所选日的右开边界。系统不设单次或每日累计时长上限，也不提供自动分配与新增候补入口。

## 功能一览

| 编号 | 模块 | 主要内容 |
|---|---|---|
| F01 | 校园账号与权限 | 学生 / 教职工 / 管理员三种角色；账号维护与 CSV 批量导入；本人联系方式维护；JWT 登录、登录限流与服务端权限强制；关键动作审计 |
| F02 | 资源与开放安排 | 楼栋、楼层、空间与座位的维护和启停；星期分段开放窗口、日期整日例外与临时停用共同决定合法开放区间；容量按实际可用座位计算 |
| F03 | 预约生命周期 | 创建、开始前取消、本人扫码签到、提前结束与到期自动结算；未签到与主动放弃的统一处理；每预约唯一使用记录 |
| F04 | 三种找座方案 | 在扣除座位占用、本人跨空间安排与停用限制后的连续空档上比较方案；查询为快照，提交时服务端重新校验 |
| F05 | 资源变更善后 | 管理端修改资源前先看影响清单，确认后按管理原因取消受影响预约；使用中预约按确认时刻结算，操作幂等且不记用户违规 |
| F06 | 投诉核实反馈 | 座位被占求助与一般服务投诉；最多三张受控图片材料；不可覆盖的核实记录、正式反馈与确认期，逾期自动结案；受理与首次反馈有处理目标 |
| F07 | 违规累计限制 | 由已核实事实登记手动违规；30 天滚动内 3 次有效违规生成 7 天限制；预约资格统一由限制事实判定，支持解除与留痕 |
| F08 | 申诉与签到纠错 | 本人在真实违规通知后 7 天内提交申诉；管理员只读预览后审核，同意时撤销原违规并按原批次重算；异常情况下的核实补签 |
| F09 | 使用评价 | 本人对已结算的真实使用评价 1—5 星；管理员回复或有理由隐藏；登录角色可查看服务端白名单过滤后的匿名公开内容 |
| F10 | 记录与消息查询 | 本人与管理记录的服务端条件筛选与稳定分页；站内消息分类筛选、已读标记与真实业务跳转；管理审计只读安全投影 |
| F11 | 统计与整体验收 | 统一统计快照与近七日趋势、热力图；页面可见时每 10 秒刷新且失败保留上次数据；S01—S31 整体工程验收 |

各功能的规则边界、状态模型与验证记录见 `docs/features/Fxx-*/` 下的两份方案。

## 技术栈

| 层次 | 选型 | 说明 |
|---|---|---|
| 后端 | Java 17、Spring Boot 2.7.18、Spring JDBC、Validation、Spring Data Redis | REST 接口、业务规则与数据访问；`Store` 是唯一 SQL 入口 |
| 数据库 | PostgreSQL 17（`postgres:17-alpine`），JDBC 驱动 42.7.14 | 开发库与测试库分离，`127.0.0.1:5433` |
| 结构管理 | Flyway 10.20.1（`flyway-core` + `flyway-database-postgresql`） | V1—V24 增量迁移；校验开启、禁止 clean |
| 缓存与限流 | Redis 7.2 | 登录限流、预约限流与注销令牌撤销；3 号库、键前缀 `studyroom-java:` |
| 认证与口令 | `com.auth0:java-jwt` 4.4.0（HMAC256，有效期 7 天）、BCrypt | 令牌签发与校验、口令哈希 |
| 二维码 | ZXing core 3.5.4（后端生成 PNG）、`@zxing/browser` 0.2.1（前端本机解码） | 每座位当前码，用于签到核验座位身份 |
| 数据导入 | Apache Commons CSV 1.14.1 | 校园用户批量导入的模板、预览与确认 |
| 前端 | Vue 3.5、vue-router 5、Pinia 4、Axios、ECharts 6、Three.js、GSAP | 单页应用、角色路由守卫、2D/3D 座位图与统计图表 |
| 构建与测试 | Maven 3.6+、Vite 8、Node 24+、Python 3.12+ | 后端 JUnit + 真实 PostgreSQL/Redis 集成测试；前端 `node --test`；脚本自测 |

## 系统架构

应用分为四层，业务规则只在业务层实现一次，查询与提交共用同一套判定：

1. **表现层**：`frontend/` 的 Vue 3 单页应用，vue-router 负责路由与角色守卫，Pinia 管理状态，Axios 统一封装请求（`baseURL='/api'`、注入 Bearer 令牌、统一拆包与错误提示，401 注销并跳转登录）。构建产物 `frontend/dist` 打包进 JAR 的 `static/`，运行时无需另起 Node 服务。
2. **接口层**：`backend/src/main/java/cn/studyroom/` 下 19 个 `*Controller`，统一前缀 `/api`，返回 `{code, message, data}` 信封；参数类异常 400、上传超限 413、唯一键与外键冲突按 SQLState 转 409，其余 500 并记日志。
3. **业务层**：`AuthService`、`UserService`、`UserImportService`、`ResourceService`、`OpeningScheduleService`、`BookingService`、`UsageService`、`AvailabilityService`、`ResourceChangeService`、`Complaint*Service`、`RestrictionPolicy` / `RestrictionWorkflow`、`ViolationService`、`AppealService` / `AppealReviewService`、`ReviewService`、`SeatCodeService`、`AttachmentService`、`NotificationService`、`AuditService`、`StatsService` 等，集中承载时长、日期、开放、冲突、资格与时限规则。
4. **数据访问与存储层**：`Store.java` 封装 `JdbcTemplate`，是 SQL 与行映射的唯一入口（并对 PostgreSQL `TIME` 的 `24:00:00` 做保真读取）；结构由 Flyway 管理，Redis 用于限流与令牌撤销，审计与通知随原业务事务一起提交。

```text
┌──────────────────────────────────────────────────────────────────────┐
│ 浏览器：Vue 3 单页应用（frontend/dist 已装入 JAR 的 static/）           │
│ vue-router 路由与角色守卫 · Pinia · Axios(/api) · ECharts · Three.js   │
└──────────────────────────────┬───────────────────────────────────────┘
                               │ HTTP JSON（snake_case 字段，Bearer 令牌）
┌──────────────────────────────▼───────────────────────────────────────┐
│ Spring Boot 2.7.18（Java 17），仅监听 127.0.0.1:8080                   │
│ WebSecurity 拦截器：health/OPTIONS 放行；登录按 IP 限 10 次/60 秒；     │
│ 其余需 Bearer 令牌；/api/admin/** 与 /api/stats/** 强制 admin；        │
│ 预约写操作限 2 次/秒                                                   │
│ 19 个 Controller · Service 层业务规则 · Store 唯一 SQL 入口            │
│ 定时任务：预约生命周期 10s · 投诉目标 60s · 确认期 60s · 材料清理 600s  │
│ 时区统一 Asia/Shanghai                                                │
└───────┬───────────────────────────────────────┬──────────────────────┘
        │ JDBC（PostgreSQL JDBC 42.7.14）        │ Redis（3 号库）
┌───────▼──────────────────────────────┐ ┌──────▼───────────────────────┐
│ PostgreSQL 17（postgres:17-alpine）   │ │ Redis 7.2                    │
│ 127.0.0.1:5433                       │ │ 键前缀 studyroom-java:       │
│ 开发库 studyroom_java                 │ │ · 登录 / 预约限流            │
│ 测试库 studyroom_java_test            │ │ · 注销令牌撤销标记           │
│ Flyway V1—V24 增量迁移                │ └──────────────────────────────┘
│ 写事务先锁定 booking_mutex 固定行      │
└──────────────────────────────────────┘
```

写事务串行化：所有预约、状态流转与资源写事务先对 `booking_mutex` 固定行执行 `SELECT ... FOR UPDATE`，再校验冲突并写入；失败时预约、流水与通知同事务回滚。该设计适应单机教学与验收场景，不作为高并发容量结论。

## 目录结构

```text
smart-study-room-reservation-and-management-system/
├── backend/                     # Java 17 / Spring Boot 服务端
│   ├── src/main/java/cn/studyroom/     # 63 个类：Controller / Service / Policy / Store
│   ├── src/main/resources/
│   │   └── db/migration/               # Flyway V1—V24 增量迁移
│   └── src/test/java/cn/studyroom/     # 单元与真实 PostgreSQL / Redis 集成测试
├── frontend/                    # Vue 3 单页应用
│   ├── src/{api,components,composables,config,layouts,router,stores,styles,views}/
│   ├── tests/                          # node --test 前端测试
│   └── vite.config.js
├── database/init/               # 容器首次初始化脚本（创建测试库）
├── scripts/                     # 环境部署、构建启停、仓库检查、HTTP 联调与性能采样
├── docs/                        # 需求、功能方案、验证记录与证据
│   ├── features/Fxx-*/                 # 每项功能一份架构方案 + 一份代码实施方案
│   ├── requirements/  reports/  evidence/
│   └── README.md                       # 文档索引与唯一来源
├── .github/                     # CI 工作流与仓库规则
├── docker-compose.yml           # PostgreSQL 17（可选 Redis 实例）
├── AGENTS.md  CONTRIBUTING.md   # 开发与协作约定
└── VERSION
```

## 快速开始

### 环境要求

| 软件 | 版本 | 用途 |
|---|---|---|
| JDK | 17 | 后端编译与运行 |
| Maven | 3.6+ | 后端依赖解析、测试与打包 |
| Node.js | 24+ | 前端依赖安装、构建与测试 |
| Python | 3.12+ | 仓库检查、HTTP 联调与性能脚本（仅用标准库） |
| Docker Desktop | 能运行 Linux 容器 | PostgreSQL 17 容器（Redis 可选） |
| 浏览器 | 支持 ES2020+ | 页面访问与验收 |

### 首次部署

```powershell
git clone https://github.com/1xiaoyueryuer/smart-study-room-reservation-and-management-system.git
cd smart-study-room-reservation-and-management-system

# 1. 生成数据库随机口令与本地配置，并启动 PostgreSQL 容器（可重复运行，不清空数据、不轮换口令）
./scripts/SetupPostgres.ps1

# 2. 构建前端与后端并启动（首次会自动构建，产物位于 .local/server.jar）
./scripts/Start.ps1
```

打开 <http://127.0.0.1:8080> 即可使用。应用仅监听本机回环地址，这是有意的默认设置。

本机凭据（数据库口令、JWT 密钥）只保存在被 `.gitignore` 忽略的 `.local/` 目录中，不随仓库提交；如需手工调整，可复制 `application-local.example.properties` 到 `.local/application-local.properties` 后修改。

### 内置账号与演示数据

| 角色 | 账号 | 口令 |
|---|---|---|
| 管理员 | `admin` | `admin123` |
| 模拟学生 | `stu01`—`stu40` | `123456` |

首次启动时，种子数据会写入 3 个模拟空间、158 个座位及近 14 天的模拟历史记录（带 `demo_seed` 来源标记，仅用于演示与验收）。可通过 `STUDYROOM_SEED=false` 关闭。

### 日常开发

```powershell
./scripts/Build.ps1        # 前端 npm ci + build，后端 mvn test package
./scripts/Stop.ps1         # 停止 Java 进程（不关闭共享的数据库与 Redis）
./scripts/Start.ps1        # 重新启动
```

前端热更新（可选）：在 `frontend/` 执行 `npm ci` 与 `npm run dev`，访问 <http://127.0.0.1:5173>，`/api` 自动代理到后端 8080 端口。

常用环境变量：`STUDYROOM_HOST_PORT`（应用端口，默认 8080）、`STUDYROOM_DB_URL` / `_USERNAME` / `_PASSWORD`、`STUDYROOM_REDIS_HOST` / `_PORT` / `_PASSWORD` / `_DATABASE`、`STUDYROOM_JWT_SECRET`（必填，≥32 字符随机串）、`STUDYROOM_SEED`、`STUDYROOM_SCHEDULER_ENABLED`、`STUDYROOM_MAVEN`（指定 `mvn.cmd` 路径）。

若本机没有名为 `my-redis` 的 Redis 容器，可改用 `docker compose --profile extra-redis up -d redis` 另起 6380 实例并同步修改本地配置，或连接自行管理的 Redis 7.x；`./scripts/Start.ps1 -SkipContainers` 可跳过容器启动。

## 数据库

| 项目 | 配置 |
|---|---|
| 容器编排 | `docker-compose.yml`，项目名 `studyroom-java`，服务 `db` |
| 镜像 / 实际版本 | `postgres:17-alpine` / PostgreSQL 17.11 |
| 地址 | `127.0.0.1:5433`（容器内 5432） |
| 开发库 / 测试库 | `studyroom_java` / `studyroom_java_test` |
| 持久化卷 | `studyroom-java_studyroom-postgres` |
| 连接池 | HikariCP，最大 12 连接，超时 10 秒 |
| 上传限制 | 单文件 6 MB、单请求 7 MB |
| 时区 | `Asia/Shanghai`（Jackson 与业务规则一致） |

数据库结构由 Flyway 增量迁移管理，当前 V1—V24 共 24 个脚本、32 张业务表，覆盖账号与权限、资源与开放安排、预约与使用、通知与审计、投诉与受控材料、违规与限制、申诉、评价等分组。迁移策略为 `baseline-on-migrate=false`、`clean-disabled=true`、`validate-on-migrate=true`；若需接管一个结构已存在但没有迁移历史的非空数据库，默认拒绝启动，需在停服务并备份后由操作人显式使用 `--studyroom.migration.adopt-legacy=true` 一次性接管（程序核对结构与 V1 一致后才建立基线）。

测试与联调只使用 `studyroom_java_test`：集成测试、HTTP 联调与性能脚本都会先核对健康响应中的实际库名，不允许指向开发库或清空全局 Redis。

## 测试与验证

```powershell
# 后端全量：单元测试 + 真实 PostgreSQL / Redis 集成测试 + 迁移测试 + 打包
./scripts/Build.ps1 -Integration

# 前端单元测试与构建
node --experimental-vm-modules --test frontend/tests/*.test.js
npm run build            # 在 frontend/ 目录

# 仓库规范检查
python scripts/check_repository.py
python -m unittest discover -s scripts/tests -v

# HTTP 联调（先在 8081 启动一个明确连接测试库的独立服务）
python scripts/api_smoke.py

# 教学性能采样
python scripts/teaching_performance.py
```

最近一次整体验收（S01—S31）的实际结果：

| 范围 | 结果 |
|---|---|
| 后端 | 单次完整 `package` 565 项通过、0 失败 |
| 前端 | 构建通过；响应式标签重叠问题修复后相关 30 项通过 |
| 脚本 | 20 / 20 通过 |
| 真实 HTTP 联调 | 94 / 94 通过（独立 6 座测试空间与专用模拟账号） |
| 教学性能 | 1000 座地图查询 20/20，p95 ≈ 208 ms；预约提交 20/20，p95 ≈ 119 ms，均在 2 秒目标内 |

批次、失败样本、环境与脱敏证据见 [验证记录](docs/VALIDATION.md) 与 `docs/evidence/`。上述数字为单机、单客户端、教学规模下的实测结果，不代表校园高并发容量；真实校园身份接入与部署、手机实扫到场、真实网络故障与现场排班协调等仍需另行验证。

## 文档索引

| 位置 | 用途 |
|---|---|
| [docs/README.md](docs/README.md) | 文档索引与唯一来源说明 |
| [docs/requirements/01_需求规格说明书.md](docs/requirements/01_需求规格说明书.md) | 正式需求规格说明书（FR / BR / AC 编号来源） |
| [docs/features/](docs/features/) | 每项功能的架构方案与代码实施方案 |
| [docs/VALIDATION.md](docs/VALIDATION.md) | 工程验证记录与实际批次 |
| [docs/evidence/](docs/evidence/) | 脱敏验证证据与索引 |
| [docs/BASELINE-v1.0.0-postgresql.md](docs/BASELINE-v1.0.0-postgresql.md) | 首个 PostgreSQL 基线的范围与验证 |
| [docs/reports/](docs/reports/) | 需求分析质量验证报告与 AI 智能体使用验证报告 |
| [CONTRIBUTING.md](CONTRIBUTING.md) | 分支、提交与审核流程 |
| [AGENTS.md](AGENTS.md) | 团队协作与开发约定 |

## 团队分工

本组 4 人，各自一个固定分支，详见 [团队分工](docs/团队分工.md)：

| 角色 | 成员 | 固定分支 | 主责方向 |
|---|---|---|---|
| A | 吴妍萱 | `member/a` | 前端、原型与页面交互 |
| B | 潘烨东 | `member/b` | 预约核心、权限与业务接口 |
| C | 张连君 | `member/c` | 配套业务与数据规则 |
| D | 潘煜（[@1xiaoyueryuer](https://github.com/1xiaoyueryuer)） | `member/d` | 工程、测试集成、仓库协调与第一审核 |

## 许可

本项目采用 MIT 许可证，详见 [LICENSE](LICENSE)。
