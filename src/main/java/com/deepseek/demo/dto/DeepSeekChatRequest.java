package com.deepseek.demo.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class DeepSeekChatRequest {

    /**
     * LLM 模型名称，默认 deepseek-chat
     */
    private String model = "deepseek-v4-flash";

    /**
     * 消息列表（system/user/assistant 角色）
     */
    private List<Message> messages;

    /**
     * 最大输出 token 数
     */
    @JsonProperty("max_tokens")
    private Integer maxTokens;

    /**
     * 生成温度，控制随机性（0~2）
     */
    private Double temperature;

    /**
     * 是否启用流式响应
     */
    private boolean stream = false;

    /**
     * DeepSeek function calling 的工具定义列表。
     * 每个元素描述一个可用函数的名称、参数 JSON Schema。
     */
    private List<Map<String, Object>> tools;

    /**
     * 工具调用策略：
     * "auto"（默认）— LLM 自行决定是否调用
     * "none" — 禁止调用
     * "required" — 强制调用
     * {"type":"function","function":{"name":"xxx"}} — 指定特定函数
     */
    @JsonProperty("tool_choice")
    private Object toolChoice = "auto";

    public DeepSeekChatRequest() {
        this.messages = new ArrayList<>();
    }

    public DeepSeekChatRequest(List<Message> messages) {
        this.messages = messages;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public List<Message> getMessages() {
        return messages;
    }

    public void setMessages(List<Message> messages) {
        this.messages = messages;
    }

    public Integer getMaxTokens() {
        return maxTokens;
    }

    public void setMaxTokens(Integer maxTokens) {
        this.maxTokens = maxTokens;
    }

    public Double getTemperature() {
        return temperature;
    }

    public void setTemperature(Double temperature) {
        this.temperature = temperature;
    }

    public boolean isStream() {
        return stream;
    }

    public void setStream(boolean stream) {
        this.stream = stream;
    }

    public List<Map<String, Object>> getTools() {
        return tools;
    }

    public void setTools(List<Map<String, Object>> tools) {
        this.tools = tools;
    }

    public Object getToolChoice() {
        return toolChoice;
    }

    public void setToolChoice(Object toolChoice) {
        this.toolChoice = toolChoice;
    }
}
