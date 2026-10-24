#!/bin/bash
set -e

echo "=== 1. 解压 ==="
mkdir -p /opt/im
cd /opt/im
tar xzf /opt/im-deploy.tar.gz
ls -1

echo "=== 2. 导入表结构 ==="
docker exec -i campus-mysql mysql -uroot -p123456 --default-character-set=utf8mb4 < /opt/im/db/schema.sql
docker exec -i campus-mysql mysql -uroot -p123456 --default-character-set=utf8mb4 < /opt/im/db/im_schema.sql
echo "--- spring_cloud 表 ---"
docker exec campus-mysql mysql -uroot -p123456 -N -e "use spring_cloud; show tables;" 2>/dev/null

echo "=== 3. compose 版本 ==="
docker compose version 2>/dev/null || echo "NO_COMPOSE_PLUGIN"

echo "=== 4. 启动 ==="
cd /opt/im
docker compose up -d 2>&1 | tail -15

echo "=== 5. 状态 ==="
docker compose ps
