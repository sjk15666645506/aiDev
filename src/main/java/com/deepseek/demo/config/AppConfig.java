package com.deepseek.demo.config;

import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

@Configuration
public class AppConfig {

        @Bean
    public RestTemplate restTemplate() {
        // 连接池：支持最多 32 并发连接、单路由 8 个
        PoolingHttpClientConnectionManager cm = new PoolingHttpClientConnectionManager();
        cm.setMaxTotal(32);
        cm.setDefaultMaxPerRoute(8);

        var factory = new HttpComponentsClientHttpRequestFactory(
                HttpClientBuilder.create().setConnectionManager(cm).build());
        factory.setConnectTimeout(5000);   // 连接超时 5s
        factory.setReadTimeout(120000);     // 读取超时 120s（DeepSeek V4 Flash 回复可能较慢）

        return new RestTemplate(factory);
    }
}
