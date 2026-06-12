package com.hmdp.config;

import com.hmdp.constant.IngestConstants;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.redis.RedisVectorStore;
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
                // 首启自动建 RediSearch 索引(已存在则跳过)
                .initializeSchema(true)
                .build();
    }
}
