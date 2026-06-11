package com.hmdp.utils;

public class RedisConstants {
    public static final String LOGIN_CODE_KEY = "login:code:";
    public static final Long LOGIN_CODE_TTL = 2L;
    public static final String LOGIN_USER_KEY = "login:token:";
    public static final Long LOGIN_USER_TTL = 24L;

    public static final Long CACHE_NULL_TTL = 2L;

    public static final Long CACHE_SHOP_TTL = 30L;
    public static final String CACHE_SHOP_KEY = "cache:shop:";

    public static final String LOCK_SHOP_KEY = "lock:shop:";
    public static final Long LOCK_SHOP_TTL = 10L;

    public static final String SECKILL_STOCK_KEY = "seckill:stock:";
    public static final String BLOG_LIKED_KEY = "blog:liked:";
    public static final String FEED_KEY = "feed:";
    public static final String SHOP_GEO_KEY = "shop:geo:";
    public static final String USER_SIGN_KEY = "sign:";

    // ───────────────────── 多租户 key 规范 ─────────────────────
    // 所有租户隔离的 Redis key 统一前缀:t:{tenantId}:{业务后缀}
    public static final String TENANT_KEY_PREFIX = "t:";

    /**
     * 拼接带租户前缀的 key,如 tenantKey(1, "kb:list") -> "t:1:kb:list"。
     * 与点评「key 前缀实战」一脉相承,保证不同租户数据在 Redis 层也隔离。
     */
    public static String tenantKey(Long tenantId, String suffix) {
        return TENANT_KEY_PREFIX + tenantId + ":" + suffix;
    }
}
