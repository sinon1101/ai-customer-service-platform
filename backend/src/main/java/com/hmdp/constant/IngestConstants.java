package com.hmdp.constant;

/**
 * M2 知识库摄入管道相关常量:RocketMQ topic/group 与 Redis 向量索引名。
 */
public class IngestConstants {

    /** 文档摄入 topic */
    public static final String TOPIC_DOC_INGEST = "KB_DOC_INGEST";

    /** producer group */
    public static final String PRODUCER_GROUP = "aics-doc-ingest-producer";

    /** consumer group */
    public static final String CONSUMER_GROUP = "aics-doc-ingest-consumer";

    /** RediSearch 向量索引名 */
    public static final String VECTOR_INDEX = "aics-kb-index";

    /** 向量 key 前缀(RedisVectorStore 写入 HASH 的 key 前缀) */
    public static final String VECTOR_PREFIX = "aics:kb:";

    private IngestConstants() {
    }
}
