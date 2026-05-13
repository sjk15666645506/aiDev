package com.deepseek.demo.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.List;

public class DeepSeekChatRequest {

    /**
     * LLM 模型名称，默认 deepseek-chat
     */
    private String model = "deepseek-chat";

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
}
