#!/usr/bin/env bash
# 按序应用 backend/migrations 下的迁移脚本
set -euo pipefail
cd "$(dirname "$0")/.."

for f in backend/migrations/*.sql; do
  echo "==> 应用 $f"
  psql -h /tmp -d studyroom -v ON_ERROR_STOP=1 -f "$f"
done
echo "迁移完成"
