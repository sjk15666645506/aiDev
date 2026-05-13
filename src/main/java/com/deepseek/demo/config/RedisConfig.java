package com.deepseek.demo.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Redis 配置，提供 StringRedisTemplate Bean。
 * <p>
 * 使用 String 序列化 + Jackson ObjectMapper 手动序列化/反序列化，
 * 避免 Spring Data Redis 默认的 JdkSerializationRedisSerializer 带来的
 * 类版本兼容问题和二进制不可读问题。
 */
@Configuration
public class RedisConfig {

    /**
     * 配置 StringRedisTemplate（Spring Boot 自动注入连接工厂）。
     * <p>
     * 键和值均使用 String 序列化，复杂对象由业务代码通过 ObjectMapper
     * 序列化为 JSON 字符串存储。
     */
    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory connectionFactory) {
        StringRedisTemplate template = new StringRedisTemplate();
        template.setConnectionFactory(connectionFactory);
        return template;
    }
}
