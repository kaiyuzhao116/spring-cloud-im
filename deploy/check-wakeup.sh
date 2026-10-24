sleep 1
echo "=== 最近 3 条 outbox ==="
docker exec campus-mysql mysql -uroot -p123456 -e \
  "use spring_cloud; select id, status, retry, created_at, sent_at, timestampdiff(microsecond, created_at, sent_at) as cost_us from im_outbox order by id desc limit 3;" 2>/dev/null
