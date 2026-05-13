package com.deepseek.demo.dto;

public class Message {

    /** 角色：system / user / assistant */
    private String role;

    /** 消息文本内容 */
    private String content;

    public Message() {
    }

    public Message(String role, String content) {
        this.role = role;
        this.content = content;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }
}
