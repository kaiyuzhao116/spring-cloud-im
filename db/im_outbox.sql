-- =============================================================
--  本地消息表（Outbox）：解决"业务落库成功但 MQ 发送失败"的丢消息问题
--  思路：把"要发通知"写进同一个事务，事务提交后由定时任务补发
-- =============================================================

USE spring_cloud;

CREATE TABLE IF NOT EXISTS `im_outbox`
(
    `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `event_type`    VARCHAR(64)     NOT NULL COMMENT '事件类型，与路由键一致，如 message.created',
    `payload`       VARCHAR(4096)   NOT NULL COMMENT '事件体 JSON',
    `status`        TINYINT         NOT NULL DEFAULT 0 COMMENT '0待发 1发送中 2已发 3死信',
    `retry`         INT             NOT NULL DEFAULT 0 COMMENT '已重试次数',
    `next_retry_at` DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '下次可发送时间（退避）',
    `created_at`    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `sent_at`       DATETIME(3)              DEFAULT NULL,
    PRIMARY KEY (`id`),
    -- 扫描条件固定是 status + next_retry_at + 按 id 顺序，做成联合索引避免额外排序
    KEY `idx_pending` (`status`, `next_retry_at`, `id`),
    KEY `idx_sent_at` (`sent_at`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='本地消息表（Outbox）';
