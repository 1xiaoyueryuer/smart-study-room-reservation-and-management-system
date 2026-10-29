#!/usr/bin/env bash
# 停止用户级 PostgreSQL 集群
set -euo pipefail
export PGDATA="$HOME/pgdata-studyroom"
pg_ctl -D "$PGDATA" -m fast stop
echo "PostgreSQL 已停止"
