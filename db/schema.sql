-- 最小可用库表：用户 + 登录相关
-- MySQL 8.0，执行方式：mysql -uroot -p123456 < db/schema.sql

CREATE DATABASE IF NOT EXISTS `spring_cloud`
    DEFAULT CHARACTER SET utf8mb4
    COLLATE utf8mb4_general_ci;

USE `spring_cloud`;

CREATE TABLE IF NOT EXISTS `sys_user`
(
    `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '用户ID',
    `account`       VARCHAR(64)     NOT NULL COMMENT '登录账号',
    `password_hash` VARCHAR(100)    NOT NULL COMMENT 'BCrypt 密码哈希',
    `nickname`      VARCHAR(64)     NOT NULL DEFAULT '' COMMENT '昵称',
    `avatar`        VARCHAR(255)             DEFAULT NULL COMMENT '头像地址',
    `status`        TINYINT         NOT NULL DEFAULT 1 COMMENT '1正常 0禁用',
    `created_at`    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at`    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_account` (`account`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT = '用户表';

-- 测试账号：admin / 123456（BCrypt cost 10）
INSERT IGNORE INTO `sys_user` (`account`, `password_hash`, `nickname`, `status`)
VALUES ('admin', '$2a$10$oAhOz9YQMsvL.KtOPixjSeN7wWLO66v7A999pdZBsv5uJtA.hxLo6', '管理员', 1);

SELECT `id`, `account`, `nickname`, `status`, `created_at` FROM `sys_user`;
