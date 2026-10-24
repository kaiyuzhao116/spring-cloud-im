#!/bin/bash
set -e

echo "=== 1. 创建 2G swap（内存只有 3.6G，已有服务占 1.5G） ==="
if swapon --show | grep -q /swapfile; then
  echo "swap 已存在，跳过"
else
  fallocate -l 2G /swapfile 2>/dev/null || dd if=/dev/zero of=/swapfile bs=1M count=2048 status=none
  chmod 600 /swapfile
  mkswap /swapfile >/dev/null
  swapon /swapfile
  grep -q '^/swapfile' /etc/fstab || echo '/swapfile none swap sw 0 0' >> /etc/fstab
  echo "swap 已启用"
fi
free -h | grep -iE 'mem|swap'

echo "=== 2. 创建数据库 spring_cloud ==="
docker exec campus-mysql mysql -uroot -p123456 -e \
  "CREATE DATABASE IF NOT EXISTS spring_cloud DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;" 2>/dev/null
docker exec campus-mysql mysql -uroot -p123456 -N -e "show databases;" 2>/dev/null | grep spring_cloud && echo "库已就绪"

echo "=== 3. 准备目录 ==="
mkdir -p /opt/im
echo "完成"
