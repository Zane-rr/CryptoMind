package com.cryptomind.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * ChatClient Bean配置，复用 spring.ai.openai.* 中已配置的 DeepSeek 参数。
 * ChatModel 由 Spring AI 自动配置注入，此处仅负责构建 ChatClient 实例。
 * 模块：Agent
 */
@Configuration
public class ChatClientConfig {

    /**
     * 构建 ChatClient Bean，供 CryptoAgentService 注入使用。
     *
     * @param chatModel Spring AI 自动配置的 ChatModel（对应 DeepSeek）
     * @return ChatClient 实例
     */
    @Bean
    public ChatClient chatClient(ChatModel chatModel) {
        return ChatClient.builder(chatModel).build();
    }
}
