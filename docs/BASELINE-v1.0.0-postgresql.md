# v1.0.0-postgresql baseline

**Smart Study Room Reservation and Management System**（智慧自习室预约与管理系统）。

2026-10-29 的第一个可运行 PostgreSQL 版本，作为后续按小组需求报告继续开发的基线。Git 标签 `v1.0.0-postgresql` 固定本版本，后续在 `main` 分支继续迭代。

- Java17 / Spring Boot 后端、原 Vue3 前端、Docker PostgreSQL17、Redis。
- 保留普通规则选座推荐。
- 已有预约、签到签退、信用、候补、通知、统计和基础管理功能。
- 尚未落实本组的三种连续空档方案、投诉申诉、累计违规限制等新增需求，详见 `MIGRATION.md`。
- 验证包括14项后端测试和28项 HTTP 接口检查，详见 `VALIDATION.md`。

本地 `.local/` 中的连接口令、JWT 密钥、工具路径和运行文件不上传。新机器按 README 部署 PostgreSQL、填写本地配置后构建运行。

基于 `imicola/smart-study-room` 二次开发，保留原始 Git 历史和 MIT 许可证。
