package com.hmdp.config;

import com.hmdp.constant.IngestConstants;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.redis.RedisVectorStore;
import org.springframework.ai.vectorstore.redis.RedisVectorStore.MetadataField;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import redis.clients.jedis.JedisPooled;

/**
 * Redis 向量库(RediSearch)配置。
 * <p>
 * 项目缓存/鉴权用的是 Lettuce;Spring AI 的 {@link RedisVectorStore} 基于 Jedis,
 * 这里给它一条专用的 {@link JedisPooled} 连接(同样指向 6381),两套客户端并存互不干扰。
 * 索引维度由 {@link EmbeddingModel#dimensions()} 自动推导(百炼 text-embedding-v3 默认 1024)。
 * <p>
 * M3 关键:把 {@code tenantId}/{@code kbId} 声明为 RediSearch <b>TAG</b> 字段,
 * 这样召回时才能用 filterExpression 按租户/知识库精确过滤(未声明的 metadata 不进索引,无法过滤)。
 * docId/docName 声明为 TEXT,仅用于引用溯源回显,不参与过滤。
 */
@Configuration
public class VectorStoreConfig {

    @Value("${spring.data.redis.host:localhost}")
    private String redisHost;

    @Value("${spring.data.redis.port:6381}")
    private int redisPort;

    @Bean
    public JedisPooled vectorStoreJedis() {
        return new JedisPooled(redisHost, redisPort);
    }

    @Bean
    public RedisVectorStore vectorStore(JedisPooled vectorStoreJedis, EmbeddingModel embeddingModel) {
        return RedisVectorStore.builder(vectorStoreJedis, embeddingModel)
                .indexName(IngestConstants.VECTOR_INDEX)
                .prefix(IngestConstants.VECTOR_PREFIX)
                // 多租户/知识库隔离过滤字段(TAG 精确匹配),溯源字段(TEXT)
                .metadataFields(
                        MetadataField.tag("tenantId"),
                        MetadataField.tag("kbId"),
                        MetadataField.text("docId"),
                        MetadataField.text("docName"))
                // 首启自动建 RediSearch 索引(已存在则跳过)
                .initializeSchema(true)
                .build();
    }
}
