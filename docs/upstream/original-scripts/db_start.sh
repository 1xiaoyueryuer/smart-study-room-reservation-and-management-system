#!/usr/bin/env bash
# 启动用户级 PostgreSQL 集群
set -euo pipefail
export PGDATA="$HOME/pgdata-studyroom"
pg_ctl -D "$PGDATA" -l "$PGDATA/server.log" -w -o "-k /tmp" start
echo "PostgreSQL 已启动（端口 5432，数据库 studyroom）"
