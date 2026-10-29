echo "=== 库里 cid 以 dup-test 开头的消息 ==="
docker exec campus-mysql mysql -uroot -p123456 -t -e "use spring_cloud; select id, seq, client_msg_id, content from im_message where client_msg_id like 'dup-test%';" 2>/dev/null

echo "=== 条数 ==="
docker exec campus-mysql mysql -uroot -p123456 -N -e "use spring_cloud; select count(*) from im_message where client_msg_id like 'dup-test%';" 2>/dev/null
