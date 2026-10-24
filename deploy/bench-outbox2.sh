echo "=== 200 次查询总耗时（同一条连接内）==="
{
  echo "use spring_cloud;"
  for i in $(seq 1 200); do
    echo "SELECT count(*) FROM im_outbox WHERE status=0 AND next_retry_at <= NOW(3) ORDER BY id LIMIT 100;"
  done
} > /tmp/bench.sql

START=$(date +%s%3N)
docker exec -i campus-mysql mysql -uroot -p123456 -N < /tmp/bench.sql > /dev/null 2>&1
END=$(date +%s%3N)

TOTAL=$((END-START))
echo "总耗时: ${TOTAL} ms"
echo "单次平均: $((TOTAL/200)) ms（已扣除连接建立）"

echo
echo "=== 对照：一次普通消息写入（4 条 SQL）的量级 ==="
echo "insert im_message + upsert 2 行 member + insert outbox = 4 次写"

echo
echo "=== 索引情况 ==="
docker exec campus-mysql mysql -uroot -p123456 -N -e \
  "use spring_cloud; show index from im_outbox;" 2>/dev/null | awk -F'\t' '{print $3, $5}'
