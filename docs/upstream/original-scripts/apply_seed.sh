#!/usr/bin/env bash
# 应用演示种子数据(可重复执行: 先清理再插入)
set -euo pipefail
cd "$(dirname "$0")/.."
psql -h /tmp -d studyroom -v ON_ERROR_STOP=1 -f backend/seed/seed.sql
echo "种子数据应用完成"
