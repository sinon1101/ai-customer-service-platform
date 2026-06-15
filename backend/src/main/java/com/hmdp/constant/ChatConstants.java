package com.hmdp.constant;

/**
 * M3 RAG 问答相关常量:向量召回参数、多轮上下文窗口与 Redis key 规范。
 */
public class ChatConstants {

    /** 向量召回 Top-K(取最相关的 K 个知识片段拼进 Prompt) */
    public static final int TOP_K = 4;

    /** 相似度阈值(0~1,越大越严;低于此分的片段视为不相关,过滤掉) */
    public static final double SIMILARITY_THRESHOLD = 0.3;

    /** 多轮上下文窗口:最多保留最近 N 条消息(user+assistant 合计),控制 token 与成本 */
    public static final int HISTORY_WINDOW = 10;

    /** 多轮上下文在 Redis 中的存活时间(分钟):会话空闲超时自动清理 */
    public static final long HISTORY_TTL_MINUTES = 30;

    /** 会话历史 key 后缀:与 tenantKey 组合 → t:{tenantId}:chat:hist:{conversationId} */
    public static final String HISTORY_KEY_SUFFIX = "chat:hist:";

    /** RedisIdWorker 生成 conversationId 用的业务前缀 */
    public static final String CONVERSATION_ID_PREFIX = "chat";

    /**
     * 「未在知识库找到答案」兜底语的稳定子串(见 system prompt)。
     * 用于判定本轮是否真的答出来了 —— 决定语义缓存按有效答案(长 TTL)还是空值(短 TTL)存。
     * 注意:召回阈值较松,docs 非空不代表答出来了,必须以答案文本为准。
     */
    public static final String NOT_FOUND_MARKER = "知识库中暂未找到";

    private ChatConstants() {
    }
}
