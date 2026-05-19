package com.deepseek.demo.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * 对话消息体，表示 system/user/assistant/tool 角色的消息。
 * <p>
 * 扩展支持 DeepSeek function calling：
 * <ul>
 *   <li>assistant 角色的消息可携带 {@link #toolCalls} 表示 LLM 要调用的工具</li>
 *   <li>tool 角色的消息需设置 {@link #toolCallId} 关联到对应的 tool_call</li>
 * </ul>
 */
public class Message {

    /** 角色：system / user / assistant / tool */
    private String role;

    /** 消息文本内容，tool_calls 时可为 null */
    private String content;

    /**
     * assistant 角色专用：DeepSeek V4 推理内容。
     * V4 的 thinking mode 强制要求此字段在后续请求中原样传回，
     * 否则返回 HTTP 400 "reasoning_content must be passed back"。
     */
    @JsonProperty("reasoning_content")
    private String reasoningContent;

    /**
     * assistant 角色专用：LLM 发起的工具调用列表。
     * 当 LLM 决定调用函数时，此字段非空，content 可能为 null。
     */
    @JsonProperty("tool_calls")
    private List<ToolCall> toolCalls;

    /**
     * tool 角色专用：对应 tool_call 的 ID。
     * 用于将工具执行结果关联回原始的 tool_call。
     */
    @JsonProperty("tool_call_id")
    private String toolCallId;

    /**
     * tool 角色专用：工具名称。
     */
    private String name;

    public Message() {}

    public Message(String role, String content) {
        this.role = role;
        this.content = content;
    }

    /**
     * 构造 tool role 消息。
     *
     * @param role       固定为 "tool"
     * @param content    工具执行结果
     * @param toolCallId 关联的 tool_call ID
     */
    public Message(String role, String content, String toolCallId) {
        this.role = role;
        this.content = content;
        this.toolCallId = toolCallId;
    }

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getReasoningContent() { return reasoningContent; }
    public void setReasoningContent(String reasoningContent) { this.reasoningContent = reasoningContent; }
    public List<ToolCall> getToolCalls() { return toolCalls; }
    public void setToolCalls(List<ToolCall> toolCalls) { this.toolCalls = toolCalls; }
    public String getToolCallId() { return toolCallId; }
    public void setToolCallId(String toolCallId) { this.toolCallId = toolCallId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
}
