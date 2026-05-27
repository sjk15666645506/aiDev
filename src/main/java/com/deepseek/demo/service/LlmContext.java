package com.deepseek.demo.service;

/**
 * LLM 请求上下文，通过 ThreadLocal 传递当前 conversationId。
 */
public final class LlmContext {

    private static final ThreadLocal<String> CONVERSATION_ID = new ThreadLocal<>();

    private LlmContext() {}

    public static void setConversationId(String id) {
        CONVERSATION_ID.set(id);
    }

    public static String getConversationId() {
        return CONVERSATION_ID.get();
    }

    public static void clear() {
        CONVERSATION_ID.remove();
    }
}
