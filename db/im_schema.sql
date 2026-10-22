-- =============================================================
--  im 模块最小表结构（单聊 MVP）
--  库：spring_cloud（与 user 服务同库，表名统一 im_ 前缀，便于将来拆库）
--  原则：先落库再推送，数据库是唯一真相
-- =============================================================

USE spring_cloud;

-- -------------------------------------------------------------
-- 1. 消息表：一行 = 一条消息（所有会话共享，写密集）
-- -------------------------------------------------------------
CREATE TABLE IF NOT EXISTS im_message (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '消息ID（自增）',
    conversation_id VARCHAR(64)  NOT NULL COMMENT '会话ID：单聊 s_{minUid}_{maxUid}',
    seq             BIGINT       NOT NULL COMMENT '会话内自增序号（Redis INCR），保序用',
    from_uid        BIGINT       NOT NULL COMMENT '发送者',
    to_uid          BIGINT       NOT NULL DEFAULT 0 COMMENT '单聊对端；群聊填 0',
    msg_type        TINYINT      NOT NULL DEFAULT 1 COMMENT '1文本 2图片 3语音 4文件',
    content         VARCHAR(2000) NOT NULL DEFAULT '' COMMENT '文本内容或资源URL',
    client_msg_id   VARCHAR(64)  NOT NULL COMMENT '客户端幂等键（必传，UUID）',
    status          TINYINT      NOT NULL DEFAULT 0 COMMENT '0正常 1已撤回',
    created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '发送时间（毫秒）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_conv_seq (conversation_id, seq) COMMENT '拉消息/翻历史 + 防重复seq',
    UNIQUE KEY uk_client (from_uid, client_msg_id) COMMENT '幂等：同一人同一clientMsgId只存一条'
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT = 'IM消息表';

-- -------------------------------------------------------------
-- 2. 会话成员表：一行 = 某人在某会话里的视图（读密集）
--    单聊一个会话 2 行，群聊 N 行
-- -------------------------------------------------------------
CREATE TABLE IF NOT EXISTS im_conversation_member (
    id               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    conversation_id  VARCHAR(64)  NOT NULL COMMENT '会话ID',
    uid              BIGINT       NOT NULL COMMENT '这一行属于谁',
    peer_uid         BIGINT       NOT NULL DEFAULT 0 COMMENT '单聊对端，列表展示用；群聊填 0',
    last_seq         BIGINT       NOT NULL DEFAULT 0 COMMENT '该会话最新seq',
    last_read_seq    BIGINT       NOT NULL DEFAULT 0 COMMENT '我读到哪（未读 = last_seq - last_read_seq）',
    last_msg_preview VARCHAR(100) NOT NULL DEFAULT '' COMMENT '列表页最后一句话（截断）',
    last_msg_at      DATETIME(3)  NULL DEFAULT NULL COMMENT '最后一条消息时间（仅消息到达时更新，勿用 updated_at）',
    created_at       DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_conv_uid (conversation_id, uid) COMMENT '一人一会话仅一行',
    KEY idx_uid_time (uid, last_msg_at DESC) COMMENT '会话列表：按人过滤 + 按时间倒序'
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT = 'IM会话成员表（每人每会话一行）';
