package com.deepseek.demo.service;

/**
 * Agent 兜底回复生成器。
 * <p>
 * 当 Agent 无法正常处理用户请求时（无匹配工具、工具调用失败、LLM 异常等），
 * 生成用户友好的兜底回复，避免将原始异常暴露给用户。
 * 所有消息集中管理，便于后续 i18n 和文案优化。
 */
public final class AgentFallback {

    private AgentFallback() {}

    /** LLM API 调用失败（网络/限流/服务端错误） */
    public static String apiUnavailable() {
        return "抱歉，我的大脑暂时离线了，请稍后再试。";
    }

    /** LLM 返回了空或异常响应 */
    public static String modelResponseInvalid() {
        return "我收到了异常的返回结果，请重试或换个方式描述你的问题。";
    }

    /** 工具调用执行异常 */
    public static String toolExecutionFailed(String toolName, String errorDetail) {
        return "操作【" + toolName + "】执行时遇到问题: " + errorDetail + "。请检查输入后重试。";
    }

    /** 所有工具被跳过，无工具能处理用户请求 */
    public static String noSuitableTool() {
        return "我目前没有找到能够处理该请求的工具。你可以尝试更清晰地描述需求，或询问我能做什么。";
    }

    /** ReAct 循环达到最大迭代次数 */
    public static String maxIterationsReached() {
        return "这个问题需要多个步骤，无法在当前轮次内完成。建议把任务拆解后再逐步执行。";
    }
}
