package com.deepseek.demo.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public class DeepSeekChatResponse {

    /**
     * 响应 ID
     */
    private String id;
    /**
     * 对象类型，固定 "chat.completion"
     */
    private String object;
    /**
     * 创建时间戳（Unix 秒）
     */
    private long created;
    /**
     * 使用的模型名称
     */
    private String model;
    /**
     * 候选回复列表
     */
    private List<Choice> choices;
    /**
     * Token 用量统计
     */
    private Usage usage;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getObject() {
        return object;
    }

    public void setObject(String object) {
        this.object = object;
    }

    public long getCreated() {
        return created;
    }

    public void setCreated(long created) {
        this.created = created;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public List<Choice> getChoices() {
        return choices;
    }

    public void setChoices(List<Choice> choices) {
        this.choices = choices;
    }

    public Usage getUsage() {
        return usage;
    }

    public void setUsage(Usage usage) {
        this.usage = usage;
    }

    public static class Choice {

        /**
         * 候选序号
         */
        private int index;
        /**
         * 回复消息内容
         */
        private Message message;

        /**
         * 结束原因（stop/length/content_filter）
         */
        @JsonProperty("finish_reason")
        private String finishReason;

        public int getIndex() {
            return index;
        }

        public void setIndex(int index) {
            this.index = index;
        }

        public Message getMessage() {
            return message;
        }

        public void setMessage(Message message) {
            this.message = message;
        }

        public String getFinishReason() {
            return finishReason;
        }

        public void setFinishReason(String finishReason) {
            this.finishReason = finishReason;
        }
    }

    public static class Usage {

        /**
         * 提示词消耗的 token 数
         */
        @JsonProperty("prompt_tokens")
        private int promptTokens;

        /**
         * 生成内容消耗的 token 数
         */
        @JsonProperty("completion_tokens")
        private int completionTokens;

        /**
         * 总消耗 token 数
         */
        @JsonProperty("total_tokens")
        private int totalTokens;

        public int getPromptTokens() {
            return promptTokens;
        }

        public void setPromptTokens(int promptTokens) {
            this.promptTokens = promptTokens;
        }

        public int getCompletionTokens() {
            return completionTokens;
        }

        public void setCompletionTokens(int completionTokens) {
            this.completionTokens = completionTokens;
        }

        public int getTotalTokens() {
            return totalTokens;
        }

        public void setTotalTokens(int totalTokens) {
            this.totalTokens = totalTokens;
        }
    }
}
