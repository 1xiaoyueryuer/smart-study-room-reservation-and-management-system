# PostgreSQL 基线验证记录

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
