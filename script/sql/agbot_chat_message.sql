-- 通道聊天记录（one-agbot 自有表，与 sai_* 分开）
-- 已有库：mysql -u root -p one-agbot < script/sql/agbot_chat_message.sql

CREATE TABLE IF NOT EXISTS agbot_chat_message (
    id                    BIGINT        NOT NULL COMMENT '主键（雪花）',
    platform              VARCHAR(32)   NOT NULL COMMENT '通道：wechat / example …',
    adapter               VARCHAR(32)   NOT NULL COMMENT '适配器：golem / example …',
    account_id            VARCHAR(64)   NOT NULL COMMENT '机器人账号',
    message_id            VARCHAR(128)  NOT NULL COMMENT '通道消息 id',
    session_id            VARCHAR(128)  NOT NULL COMMENT 'group:… / user:…',
    session_type          VARCHAR(16)   NOT NULL COMMENT 'group / private',
    direction             VARCHAR(16)   NOT NULL COMMENT 'inbound / outbound',
    sender_id             VARCHAR(64)   NOT NULL COMMENT '通道用户 id',
    sender_name           VARCHAR(128)  NOT NULL DEFAULT '' COMMENT '展示名',
    msg_type              VARCHAR(32)   NOT NULL COMMENT '统一类型：text/image/emoji/…',
    content_text          VARCHAR(4000) NOT NULL DEFAULT '' COMMENT '可读摘要，超长截断',
    adapter_extra         TEXT          NULL COMMENT '适配器私有 JSON',
    refer_message_id      VARCHAR(128)  NULL COMMENT '引用的消息 id',
    caused_by_message_id  VARCHAR(128)  NULL COMMENT '出站对应的入站 id',
    reply_index           INT           NOT NULL DEFAULT 0 COMMENT '同一入站的第几条回复',
    reply_status          VARCHAR(16)   NULL COMMENT '出站：sent / failed',
    msg_time              DATETIME      NOT NULL COMMENT '通道侧时间',
    create_by             BIGINT        NULL COMMENT '创建者，无登录 -1',
    create_time           DATETIME      NULL COMMENT '入库时间',
    update_by             BIGINT        NULL COMMENT '更新者，无登录 -1',
    update_time           DATETIME      NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_agbot_chat_msg (platform, account_id, message_id),
    KEY idx_agbot_chat_session (session_id, id),
    KEY idx_agbot_chat_adapter (adapter, session_id, id),
    KEY idx_agbot_chat_caused (caused_by_message_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='通道聊天记录';
