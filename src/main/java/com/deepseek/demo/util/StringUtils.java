package com.deepseek.demo.util;

public final class StringUtils {

    private StringUtils() {}

    public static String truncate(String s, int maxLen) {
        if (s == null) return null;
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }

    /**
     * 将 LLM 调用异常转换为用户友好的错误提示。
     */
    public static String friendlyLlmError(Exception e) {
        String msg = e.getMessage();
        if (msg == null) return "分析服务暂不可用";

        if (msg.contains("timeout") || msg.contains("Timeout")
                || msg.contains("CANCEL") || msg.contains("stream was reset")) {
            return "DeepSeek API 响应超时（分析较复杂，请稍后重试）";
        }
        if (msg.contains("429") || msg.contains("Too Many Requests") || msg.contains("Rate limit")) {
            return "DeepSeek API 请求过于频繁，请稍后重试";
        }
        if (msg.contains("401") || msg.contains("Unauthorized") || msg.contains("API key")) {
            return "DeepSeek API Key 无效，请检查配置";
        }
        if (msg.contains("5") && (msg.contains("50") || msg.contains("Internal Server Error"))) {
            return "DeepSeek 服务端错误，请稍后重试";
        }
        return "分析服务暂不可用: " + msg;
    }
}
