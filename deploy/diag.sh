#!/bin/bash
echo "=== rabbitmq 密码试探 ==="
for p in 123456 admin guest; do
  code=$(curl -s -o /dev/null -w '%{http_code}' -u "admin:$p" http://127.0.0.1:15672/api/whoami)
  echo "admin/$p -> $code"
done

echo "=== mysql 现有库 ==="
docker exec campus-mysql mysql -uroot -p123456 -N -e "show databases;" 2>/dev/null

echo "=== swap 现状 ==="
free -h | grep -i swap

echo "=== 内存大戶 ==="
ps -eo pid,rss,cmd --sort=-rss | head -6
