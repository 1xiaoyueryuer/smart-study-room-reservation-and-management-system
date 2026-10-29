#!/usr/bin/env bash
# 初始化 PostgreSQL 用户级集群（无需 root，数据目录位于用户家目录）
# 用法: ./scripts/db_init.sh
set -euo pipefail

export PGDATA="$HOME/pgdata-studyroom"
PGPORT=5432
DBNAME=studyroom

if [ -d "$PGDATA" ]; then
  echo "集群已存在: $PGDATA（如需重建请先删除该目录）"
  exit 0
fi

echo "==> initdb 创建集群于 $PGDATA ..."
initdb -D "$PGDATA" --encoding=UTF8 --locale=C.UTF-8

echo "==> 启动集群 ..."
pg_ctl -D "$PGDATA" -l "$PGDATA/server.log" -w -o "-k /tmp" start

echo "==> 创建数据库 $DBNAME ..."
createdb -h /tmp -p "$PGPORT" "$DBNAME"

echo "完成。后续使用 ./scripts/db_start.sh / ./scripts/db_stop.sh 管理集群。"
