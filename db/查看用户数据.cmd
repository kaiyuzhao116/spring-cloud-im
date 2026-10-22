@echo off
chcp 936 >nul
title spring_cloud.sys_user

set "MYSQL=C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe"

echo ============================================
echo   MySQL 实例与数据目录
echo ============================================
"%MYSQL%" -uroot -p123456 -t -e "SELECT @@hostname AS host, @@port AS port, @@version AS version, @@datadir AS datadir;" 2>nul

echo.
echo ============================================
echo   spring_cloud.sys_user 全部数据
echo ============================================
"%MYSQL%" -uroot -p123456 -t -e "SELECT id, account, nickname, status, created_at, LEFT(password_hash,20) AS hash_head FROM spring_cloud.sys_user;" 2>nul

echo.
echo ============================================
echo   表结构
echo ============================================
"%MYSQL%" -uroot -p123456 -t -e "DESC spring_cloud.sys_user;" 2>nul

echo.
echo 按任意键关闭...
pause >nul
