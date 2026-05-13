package com.deepseek.demo.dto;

import java.util.List;
import java.util.Map;

/**
 * 确认点，表示需要用户确认的操作。
 * <p>
 * 两种类型：
 * <ul>
 *   <li>plan：操作计划确认，包含 LLM 建议的完整操作列表</li>
 *   <li>exec：写操作二次确认，包含单个工具的名称和参数</li>
 * </ul>
 */
public class ConfirmationPoint {

    /** 确认点唯一标识（UUID） */
    private String confirmationId;

    /** 对话会话 ID */
    private String conversationId;

    /** 确认点类型：plan / exec */
    private String type;

    /** LLM 的推理说明（plan 类型），描述为什么要执行这些操作 */
    private String reasoning;

    /** plan 类型：LLM 提议的完整操作列表 */
    private List<Map<String, Object>> plan;

    /** exec 类型：要执行的工具名称 */
    private String tool;

    /** exec 类型：工具参数 */
    private Map<String, Object> args;

    /** plan 类型：操作数量汇总 */
    private String summary;

    /** 创建时间戳 */
    private long createdAt;

    public ConfirmationPoint() {}

    public String getConfirmationId() { return confirmationId; }
    public void setConfirmationId(String confirmationId) { this.confirmationId = confirmationId; }
    public String getConversationId() { return conversationId; }
    public void setConversationId(String conversationId) { this.conversationId = conversationId; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getReasoning() { return reasoning; }
    public void setReasoning(String reasoning) { this.reasoning = reasoning; }
    public List<Map<String, Object>> getPlan() { return plan; }
    public void setPlan(List<Map<String, Object>> plan) { this.plan = plan; }
    public String getTool() { return tool; }
    public void setTool(String tool) { this.tool = tool; }
    public Map<String, Object> getArgs() { return args; }
    public void setArgs(Map<String, Object> args) { this.args = args; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }
}
