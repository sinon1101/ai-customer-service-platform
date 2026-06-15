package com.hmdp.constant;

/**
 * M4 语义缓存相关常量。
 * <p>
 * 语义缓存:把「问题 → 答案」按问题向量存进一个<b>独立</b>的缓存向量索引,
 * 新问题先在这里按高相似度阈值找历史问答,命中直接返回(省一次 LLM 调用)。
 * 与知识库索引分库(不同 index / prefix),便于单独给缓存项设 TTL。
 */
public class CacheConstants {

    /** 语义缓存向量索引名(与知识库索引 aics-kb-index 分开) */
    public static final String CACHE_INDEX = "aics-cache-index";

    /** 缓存向量 key 前缀(用于单独 expire,实现 TTL/雪崩抖动) */
    public static final String CACHE_PREFIX = "aics:cache:";

    /**
     * 缓存命中阈值(0~1,越大越严)。召回阈值是 0.3,缓存必须更严:
     * 几乎同义才命中,否则会把「相近但不同」的问题误判命中、答错。
     */
    public static final double HIT_SIMILARITY_THRESHOLD = 0.92;

    /** 正常答案缓存基础 TTL(秒):2 小时 */
    public static final long ANSWER_TTL_SECONDS = 2 * 60 * 60;

    /** 雪崩防护:在基础 TTL 上叠加 [0, JITTER) 的随机抖动(秒),避免大量缓存同时过期 */
    public static final long ANSWER_TTL_JITTER_SECONDS = 30 * 60;

    /**
     * 穿透防护(空值缓存语义版):知识库找不到答案的「未命中型」回答也入缓存,
     * 但只给很短的 TTL(秒),让相似的无解提问短路、不反复打 LLM。
     */
    public static final long NULL_ANSWER_TTL_SECONDS = 120;

    /** 击穿防护:重建缓存互斥锁 key 后缀 → t:{tenantId}:cache:lock:{hash} */
    public static final String LOCK_KEY_SUFFIX = "cache:lock:";

    /** 互斥锁 TTL(秒):防止持锁线程异常后死锁(应略大于一次 LLM 调用耗时) */
    public static final long LOCK_TTL_SECONDS = 10;

    /**
     * 未抢到锁时,等待持锁者重建完成的最长时间(毫秒)。
     * 需覆盖一次 LLM 调用耗时(秒级),否则等待者会提前放弃、各自打 LLM,锁失去意义。
     */
    public static final long LOCK_WAIT_MAX_MILLIS = 8000;

    /** 等待持锁者时轮询锁是否释放的间隔(毫秒)。轮询只 GET 锁键,廉价,不触发 embedding。 */
    public static final long LOCK_POLL_MILLIS = 80;

    /** kbId 为空(全库检索)时在缓存里使用的归一化哨兵值(KB 自增 id 从 1 起,0 安全) */
    public static final String KB_ALL_SENTINEL = "0";

    private CacheConstants() {
    }
}
