-- ============================================================
-- M6 人工坐席 — 工单 + 实时会话消息建表
-- 沿用逻辑隔离:每张表带 tenant_id。
-- ticket 的 id 由 RedisIdWorker 生成(非自增),与会话/工单 ID 生成卖点一致。
-- 卷已存在时,init 目录不会自动执行,需手动 apply:
--   docker exec -i aics-mysql mysql -uroot -p123456 ai_customer_service < deploy/mysql/init/02-m6-ticket.sql
-- ============================================================

-- ─────────────────── 工单(转人工 → 待接入池 → 坐席抢单)───────────────────
CREATE TABLE IF NOT EXISTS ticket (
    id              BIGINT       NOT NULL COMMENT '工单ID(RedisIdWorker 生成)',
    tenant_id       BIGINT       NOT NULL COMMENT '所属租户',
    conversation_id VARCHAR(64)  NOT NULL COMMENT '关联的对话ID(链到 chat 多轮历史)',
    kb_id           BIGINT       DEFAULT NULL COMMENT '关联知识库(可空)',
    visitor_user_id BIGINT       NOT NULL COMMENT '访客(发起转人工的登录账号)',
    visitor_name    VARCHAR(50)  DEFAULT NULL COMMENT '访客昵称(冗余,便于坐席侧展示)',
    reason          VARCHAR(20)  NOT NULL DEFAULT 'USER_REQUEST' COMMENT '转人工原因:USER_REQUEST用户主动 / BOT_FAILED机器人答不了 / NOT_FOUND知识库无果',
    last_question   VARCHAR(500) DEFAULT NULL COMMENT '触发转人工的最后一问(给坐席的上下文)',
    status          VARCHAR(20)  NOT NULL DEFAULT 'WAITING' COMMENT '状态:WAITING待接入 / ASSIGNED已接入 / CLOSED已结束',
    agent_id        BIGINT       DEFAULT NULL COMMENT '抢到工单的坐席账号',
    agent_name      VARCHAR(50)  DEFAULT NULL COMMENT '坐席昵称(冗余)',
    create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '建单时间',
    assign_time     DATETIME     DEFAULT NULL COMMENT '被接入时间',
    close_time      DATETIME     DEFAULT NULL COMMENT '结束时间',
    PRIMARY KEY (id),
    KEY idx_tenant_status (tenant_id, status),
    KEY idx_agent (agent_id),
    KEY idx_conversation (conversation_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='人工坐席工单';

-- ─────────────────── 会话消息(坐席 ↔ 访客实时聊天的落库记录)───────────────────
-- 实时投递走 WebSocket + Redis Pub/Sub;这里持久化用于历史回看 / 重连补全。
CREATE TABLE IF NOT EXISTS chat_message (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '消息ID',
    tenant_id   BIGINT       NOT NULL COMMENT '所属租户',
    ticket_id   BIGINT       NOT NULL COMMENT '所属工单',
    sender_role VARCHAR(20)  NOT NULL COMMENT '发送方角色:VISITOR访客 / AGENT坐席 / SYSTEM系统提示',
    sender_id   BIGINT       DEFAULT NULL COMMENT '发送方账号ID(SYSTEM 为空)',
    sender_name VARCHAR(50)  DEFAULT NULL COMMENT '发送方昵称(冗余)',
    content     VARCHAR(2000) NOT NULL COMMENT '消息内容',
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '发送时间',
    PRIMARY KEY (id),
    KEY idx_ticket (ticket_id),
    KEY idx_tenant (tenant_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='坐席会话消息';
