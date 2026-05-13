package com.deepseek.demo.dto;

/**
 * Agent 服务的统一响应体。
 * <p>
 * 三种响应类型：
 * <ul>
 *   <li>done：LLM 最终回答，type="done", reply 包含回答文本</li>
 *   <li>confirmation：需要用户确认，type="confirmation", confirmationPoint 包含确认信息</li>
 *   <li>error：异常终止，type="error", reply 包含错误信息</li>
 * </ul>
 */
public class AgentResponse {

    /** 响应类型：done / confirmation / error */
    private String type;

    /** 对话会话 ID，首次请求由服务端生成，后续请求由客户端传入 */
    private String conversationId;

    /** LLM 回答文本（type=done/error 时有效） */
    private String reply;

    /** 确认点信息（type=confirmation 时有效） */
    private ConfirmationPoint confirmationPoint;

    public AgentResponse() {}

    /**
     * 创建最终回答响应。
     *
     * @param reply LLM 生成的回答
     * @return type="done" 的响应
     */
    public static AgentResponse done(String reply) {
        AgentResponse r = new AgentResponse();
        r.setType("done");
        r.setReply(reply);
        return r;
    }

    /**
     * 创建等待确认响应。
     *
     * @param cp 确认点信息
     * @return type="confirmation" 的响应
     */
    public static AgentResponse waitConfirm(ConfirmationPoint cp) {
        AgentResponse r = new AgentResponse();
        r.setType("confirmation");
        r.setConfirmationPoint(cp);
        return r;
    }

    /**
     * 创建错误响应。
     *
     * @param error 错误描述
     * @return type="error" 的响应
     */
    public static AgentResponse error(String error) {
        AgentResponse r = new AgentResponse();
        r.setType("error");
        r.setReply(error);
        return r;
    }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getConversationId() { return conversationId; }
    public void setConversationId(String conversationId) { this.conversationId = conversationId; }
    public String getReply() { return reply; }
    public void setReply(String reply) { this.reply = reply; }
    public ConfirmationPoint getConfirmationPoint() { return confirmationPoint; }
    public void setConfirmationPoint(ConfirmationPoint confirmationPoint) { this.confirmationPoint = confirmationPoint; }
}
