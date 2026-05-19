package com.deepseek.demo.service;

import com.deepseek.demo.dto.DeepSeekChatResponse;
import com.deepseek.demo.dto.Message;
import com.deepseek.demo.dto.ToolCall;

import java.util.List;

public final class DeepSeekResponseNormalizer {

    private DeepSeekResponseNormalizer() {}

    /**
     * 从 DeepSeek V4 响应中提取并规范化 tool_calls。
     * V4 将 tool_calls 放在 message 内部（非 choice 层），
     * 同时 reasoning_content 需要从 choice 层回填到 message。
     *
     * @param choice DeepSeek API 返回的第一个 choice
     * @return tool_calls 列表，可能为 null
     */
    public static List<ToolCall> normalizeToolCalls(DeepSeekChatResponse.Choice choice) {
        List<ToolCall> toolCalls = choice.getToolCalls();
        Message message = choice.getMessage();
        if ((toolCalls == null || toolCalls.isEmpty()) && message != null && message.getToolCalls() != null) {
            toolCalls = message.getToolCalls();
        }
        if (choice.getReasoningContent() != null && message != null) {
            message.setReasoningContent(choice.getReasoningContent());
        }
        return toolCalls;
    }
}
