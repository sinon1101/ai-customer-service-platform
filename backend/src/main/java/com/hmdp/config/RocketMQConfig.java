package com.hmdp.config;

import com.hmdp.constant.IngestConstants;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.exception.MQClientException;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RocketMQ 原生客户端配置:手写 producer bean,绕开官方 starter 在 Spring Boot 3 的兼容坑。
 * consumer 端见 {@link com.hmdp.mq.DocIngestConsumer}(自管生命周期)。
 */
@Slf4j
@Configuration
public class RocketMQConfig {

    @Value("${rocketmq.name-server:127.0.0.1:9876}")
    private String nameServer;

    private DefaultMQProducer producer;

    @Bean
    public DefaultMQProducer docIngestProducer() throws MQClientException {
        producer = new DefaultMQProducer(IngestConstants.PRODUCER_GROUP);
        producer.setNamesrvAddr(nameServer);
        producer.start();
        log.info("RocketMQ producer 已启动,namesrv={}", nameServer);
        return producer;
    }

    @PreDestroy
    public void shutdown() {
        if (producer != null) {
            producer.shutdown();
            log.info("RocketMQ producer 已关闭");
        }
    }
}
