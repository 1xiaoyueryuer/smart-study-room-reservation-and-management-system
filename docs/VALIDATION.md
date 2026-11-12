# 工程验证记录（历史基线与S31整体验收）

日期：2026-10-29。环境：Windows、JDK17、Maven3.9.9、Node24、Docker PostgreSQL17、Redis7.2。以下为发布基线时的历史结果，与原作者报告独立；后续S01起的实际改动、测试数量和页面证据原位维护于所属 `docs/features/Fxx/` 两份方案。旧规则推荐/候补结果不表示当前仍开放这些入口，旧28条HTTP记录不作为新代码已复验的证明。

| 验证 | 结果 | 入口 |
|---|---|---|
| Java 编译与可执行 JAR 打包 | 通过 | `backend/target/studyroom-server-1.0.0.jar` |
| Vue 生产构建 | 通过 | `frontend/dist/`，静态资源已装入 JAR |
| 时间规则单元测试 | 5/5 | `SlotTest.java` |
| 前端请求字段契约 | 1/1 | `RequestContractTest.java` |
| 真实 PostgreSQL 集成测试 | 8/8 | `BookingIntegrationTest.java` |
| Java 服务 HTTP 联调 | 28/28 | `scripts/api_smoke.py` |

原始结果保存于 `docs/evidence/`；当前 Maven 原始报告为 `backend/target/surefire-reports/`。

关键场景：20个并发用户抢部分重叠时段的同一座位，仅1次成功、19次409；同一用户12个并发请求抢不同座位的相同时段，仅1次成功、11次409。验证首尾相接、取消释放、越权阻止、重复签退/超时不重复记流水、候补偏好与递补幂等、禁约及资源变更保护。

HTTP检查覆盖健康探针明确返回 PostgreSQL、登录、权限、座位图字段、规则推荐、创建和取消、冲突、时间格式、通知、信用、热力矩阵、资源管理及令牌注销即时失效。

页面检查覆盖登录、学生首页与选座图加载；未对所有3D交互、全部响应式尺寸和网络异常进行完整人工走查，不做生产容量或吞吐量承诺。当前需求差距见 `MIGRATION.md`，这些测试不代表全部课程需求已完成。

数据库使用独立 Docker 持久化卷。已实际重启 PostgreSQL 容器并核对重启前后均为158个座位；重复执行部署脚本后仍能使用原口令连接。已验证 Java 停止后8080监听消失，再次启动后健康探针恢复为 PostgreSQL。旧 openGauss 数据保持原样。

实际 PostgreSQL 版本为17.11，JDBC为42.7.14。本机入口：`http://127.0.0.1:8080`。数据库：`127.0.0.1:5433`，开发库与测试库分离。

## S31 可复现验证入口

上文是2026-10-29历史基线，旧28项及候补/信用断言不是当前结果。新版逐项覆盖原位见[正式E-014](requirements/附件/E-014_需求来源与验收追踪.md)的S31索引；实际批次、问题和环境边界见[F11实施方案](features/F11-统计与整体验收/统计与整体验收功能代码实施方案.md)。

后端全量：仓库根加载 `scripts/common.ps1`，设置进程环境 `STUDYROOM_INTEGRATION=true`，在backend执行 `Invoke-Maven -Arguments @('package')`；此单一生命周期包含全部单元/真实PostgreSQL与Redis集成/迁移测试和打包，不能将多次运行相加。前端执行 `node --experimental-vm-modules --test frontend/tests/*.test.js` 与 `npm run build`。清理测试只允许studyroom_java_test，先停止占用该库的工程服务；不得清理开发库、替换开发服务或全局清Redis。

性能的可提交来源是 `scripts/fixtures/S31PerformanceFixture.java` 与 `scripts/teaching_performance.py`。前者仅构造明确标注的基础用户、1000采样座位（30×20与20×20）及独立6座联调空间，拒绝覆盖同名夹具；业务预约由真实HTTP产生，脚本最后取消已知成功预约并保历史。先确认专用库及本轮唯一持有者；全部清库测试结束后再准备夹具，之后不可再跑清库测试而沿用旧manifest。

以下为PowerShell复现流程（已有本地连接配置且依赖就绪；不把配置或口令提交）。在根目录加载common后，于backend运行 `Invoke-Maven -Arguments @('dependency:build-classpath','-Dmdep.outputFile=../work/s31-runtime-classpath.txt','-DincludeScope=runtime')` 生成运行依赖路径。回根读取路径为 `$taskCp`，执行 `javac -encoding UTF-8 -cp "backend/target/classes;$taskCp" -d work/s31-performance-classes scripts/fixtures/S31PerformanceFixture.java`（预建work目录）；随后在backend运行 `java '-Dfile.encoding=UTF-8' -cp "../work/s31-performance-classes;target/classes;$taskCp" S31PerformanceFixture`。检查work/s31-performance-fixture.json，保持仅连接studyroom_java_test。

在backend以 `java '-Dfile.encoding=UTF-8' -jar target/studyroom-server-1.0.0.jar --server.port=8081 --spring.datasource.url=jdbc:postgresql://127.0.0.1:5433/studyroom_java_test --studyroom.seed=false --studyroom.scheduler-enabled=false` 启动独立服务。health确认库名后，另一终端根目录运行 `python scripts/teaching_performance.py`。每次保留独立UTC+UUID结果于work/s31-performance-runs，记录源码SHA、HEAD/dirty、环境、每项样本、全部慢/失败、未知提交及清理结果；不将最后成功结果覆盖早先失败。每个地图样本实际顺序读取两房共1000座，计两HTTP及JSON解码之和；磁盘落盘、登录与实际0.6秒限流间隔不计单样本。查询与创建各至少20成功且无失败，按最近秩ceil(0.95n)取p95，与2000ms教学目标比较；同机单客户端不证明校园高并发容量。

HTTPsmoke为 `python scripts/api_smoke.py`，可设置进程环境 `STUDYROOM_API`、`STUDYROOM_SMOKE_ADMIN/OWNER/OTHER_USERNAME`及各自`_PASSWORD`（三账号六项凭据）、`STUDYROOM_SMOKE_ROOM_ID`。S31历史真实批次使用当时的s31_smoke_admin/owner/other工程夹具；当前脚本默认已随S33改为9位演示账号`202630001`、`202600001`、`202600002`，不会自动启种子或改开放时间/解除限制。本轮夹具账号另staff供页面；只使用源码中明确的工程测试口令。日志不输出凭据/令牌。先health硬核测试库，再读取凭据、登录和业务操作；配置检查及故障清理断言由scripts/tests验证。

## S31 本轮实际结果（2026-10-30）

| 范围 | 实际结果与批次 |
|---|---|
| 后端 | 单次完整package565/0/0/0（含迁移125和跨域旅程1），22:26:37、09:13；非专项相加 |
| 前端 | V125 CSS修前319full/build1.23秒；仅CSS修后30相关/build1.56秒，未冒再跑319 |
| 脚本 | 20/20，含专用库保护/配置、坏回执清理、5xx未知提交与p95失败样本保留；py_compile通过 |
| 最终产物 | CSS修后skipTests重包22:40:25、8.176秒；84dist和131编译class逐字节匹配；JAR SHA256 `6d16cc0234a8871a22d6b41fbec4151e06082892b640f9c07df3ac7754de4e39` |
| 真实HTTP | 独立6座/模拟账号联调94/94；分钟/ACL/冲突/旧入口退出/消息/资源预览确认及取消清理 |
| 性能 | 首轮地图20/20 p95=208.1532ms、max438.6390；提交20/20 p95=118.6439ms、max151.8631；都≤2秒。无失败/未知/清理失败，20预约已取消 |
| Codex GUI | 管理资源与真实审计、学生390找座确认预约及开始前取消（记录2取消/违规0）、教职工普通入口及/admin重定向；管理/学生/教职工390与桌面布局实查，非独立人工PV |

40原测量及全部版本/源SHA/cleanup见[脱敏机器证据](evidence/S31-教学性能采样.json)。run `20261030T142922.771264Z-603b72cd`，22:29:22—22:29:42；1000座仅600+400两房，附加6联调座位排除。Windows11 build26200、Intel64 Family6 Model183 Stepping1、JDK17.0.12、PostgreSQL17.11、Redis7.2.12、Python3.13.12 urllib，同机单客户端，最近秩第19样本；不作校园高并发容量结论。实测时HEAD53cc27f/dirty=true，实际工具SHA在证据内，最终提交尚未产生；本轮实际编译夹具复用了已有work/s09-classpath.txt，上节给出新检出生成运行classpath的方式。

V122/V123是新旅程测试请求/派生态查询错误，修测试后专项1过并纳完整565；两轮原失败保留。V124是静态发现两验证工具坏回执/未知提交边界并修，5项新增反例通过，不称实发生产故障。V125是真实390首页83.6格容不下100.81/121.74标签，原CSS改两列/换行；修后167.2格均界内，桌面仍四列约229.5。诊断失败图与成功图分开，详情唯一F11 E-DEV-S31及正式A7问题表，不删除失败换通过。

所有集成/fixture/性能/smoke仅studyroom_java_test，持库串行；最终8081同包健康恢复，Vite5173代理8081，开发库/8080未操作。S01—31工程闭环完成；真实校园认证/部署、手机实扫/到场、真实网络故障、现场排班/协调、原RF/PV真人复验、独立审核及教师校方确认仍未验证；B5未另布置，不伪作完成。原各步结果与历史失败继续保留，不由本轮追认人工验收。
