echo "=== 1. 执行计划（看是否走索引）==="
docker exec campus-mysql mysql -uroot -p123456 -e \
  "use spring_cloud; EXPLAIN SELECT id, event_type, payload, retry FROM im_outbox WHERE status=0 AND next_retry_at <= NOW(3) ORDER BY id LIMIT 100;" 2>/dev/null

echo
echo "=== 2. 表规模 ==="
docker exec campus-mysql mysql -uroot -p123456 -N -e \
  "use spring_cloud; select count(*) as total, sum(status=0) as pending, sum(status=2) as sent from im_outbox;" 2>/dev/null

echo
echo "=== 3. 连续执行 200 次，单次平均耗时（服务端计时，不含网络）==="
docker exec campus-mysql mysql -uroot -p123456 -N -e "
use spring_cloud;
SET profiling = 1;
SELECT count(*) INTO @x FROM im_outbox WHERE status=0 AND next_retry_at <= NOW(3) LIMIT 100;
" 2>/dev/null || true

docker exec campus-mysql mysql -uroot -p123456 -N -e "
use spring_cloud;
SET @i = 0;
SET @ts = NOW(6);
WHILE @i < 200 DO
  SELECT count(*) INTO @x FROM im_outbox WHERE status=0 AND next_retry_at <= NOW(3) ORDER BY id LIMIT 100;
  SET @i = @i + 1;
END WHILE;
" 2>&1 | head -3
