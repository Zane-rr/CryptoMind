package com.cryptomind.config;

import okhttp3.OkHttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * OkHttpClient Spring Bean配置，全局共享单一实例以复用连接池。
 * 模块：Market / Exchange
 */
@Configuration
public class OkHttpConfig {

    /** 连接超时时间（秒） */
    private static final int CONNECT_TIMEOUT_SECONDS = 10;

    /** 读取超时时间（秒） */
    private static final int READ_TIMEOUT_SECONDS = 30;

    /**
     * 构造全局共享的 OkHttpClient Bean。
     * 复用连接池可显著降低币安API的连接开销。
     *
     * @return 配置好超时参数的OkHttpClient实例
     */
    @Bean
    public OkHttpClient okHttpClient() {
        return new OkHttpClient.Builder()
                .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build();
    }
}
