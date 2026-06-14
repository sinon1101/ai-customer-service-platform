-- ============================================================
-- 多租户 AI 智能客服平台 — M1 骨架建表
-- 隔离方式:逻辑隔离(共享库),每张业务表带 tenant_id
-- 库:ai_customer_service(由 docker-compose 的 MYSQL_DATABASE 创建)
-- ============================================================

-- ───────────────────────── 租户 ─────────────────────────
CREATE TABLE IF NOT EXISTS tenant (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '租户ID',
    name        VARCHAR(100) NOT NULL COMMENT '企业名称',
    code        VARCHAR(50)  NOT NULL COMMENT '租户编码(全局唯一,用作 Redis key 前缀)',
    status      TINYINT      NOT NULL DEFAULT 1 COMMENT '状态:1正常 0禁用',
    daily_quota INT          NOT NULL DEFAULT 10000 COMMENT '每日调用配额',
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_code (code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='租户';

-- ─────────────────── 租户后台账号(管理员/坐席)───────────────────
CREATE TABLE IF NOT EXISTS sys_user (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '账号ID',
    tenant_id   BIGINT       NOT NULL COMMENT '所属租户',
    username    VARCHAR(50)  NOT NULL COMMENT '登录名(全局唯一,登录时据此解析租户)',
    password    VARCHAR(128) NOT NULL COMMENT '加密密码(salt@md5)',
    nick_name   VARCHAR(50)  DEFAULT NULL COMMENT '昵称',
    role        VARCHAR(20)  NOT NULL DEFAULT 'ADMIN' COMMENT '角色:ADMIN管理员 / AGENT坐席',
    status      TINYINT      NOT NULL DEFAULT 1 COMMENT '状态:1正常 0禁用',
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_username (username),
    KEY idx_tenant (tenant_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='租户后台账号(管理员/坐席)';

-- ───────────────────────── 知识库 ─────────────────────────
CREATE TABLE IF NOT EXISTS knowledge_base (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '知识库ID',
    tenant_id   BIGINT       NOT NULL COMMENT '所属租户',
    name        VARCHAR(100) NOT NULL COMMENT '知识库名称',
    description VARCHAR(500) DEFAULT NULL COMMENT '描述',
    status      TINYINT      NOT NULL DEFAULT 1 COMMENT '状态:1启用 0禁用',
    doc_count   INT          NOT NULL DEFAULT 0 COMMENT '文档数(冗余计数)',
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_tenant (tenant_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='知识库';

-- ─────────────────── 知识库文档(M2 异步摄入管道)───────────────────
-- 上传即落库(PENDING),发 RocketMQ 异步切片+向量化,消费端推进状态。
CREATE TABLE IF NOT EXISTS kb_document (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '文档ID',
    tenant_id   BIGINT       NOT NULL COMMENT '所属租户',
    kb_id       BIGINT       NOT NULL COMMENT '所属知识库',
    name        VARCHAR(255) NOT NULL COMMENT '文档名',
    source_type VARCHAR(20)  NOT NULL DEFAULT 'TEXT' COMMENT '来源类型:TEXT / MD',
    content     MEDIUMTEXT   COMMENT '原始文本内容(本版纯文本/Markdown)',
    char_count  INT          NOT NULL DEFAULT 0 COMMENT '字符数',
    chunk_count INT          NOT NULL DEFAULT 0 COMMENT '切片数(向量化完成后回写)',
    status      VARCHAR(20)  NOT NULL DEFAULT 'PENDING' COMMENT '状态:PENDING/PROCESSING/COMPLETED/FAILED',
    error_msg   VARCHAR(500) DEFAULT NULL COMMENT '失败原因',
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_tenant (tenant_id),
    KEY idx_kb (kb_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='知识库文档';
