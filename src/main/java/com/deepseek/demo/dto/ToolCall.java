package com.deepseek.demo.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * DeepSeek function calling 返回的 tool_call 对象。
 * 对应 API 响应中 choices[0].message.tool_calls[i] 的结构。
 */
public class ToolCall {

    /** tool_call 的唯一标识，后续 tool role 消息需引用此 ID */
    private String id;

    /** 固定为 "function" */
    private String type = "function";

    /** 函数调用详情 */
    private FunctionCall function;

    public ToolCall() {}

    public ToolCall(String id, String type, FunctionCall function) {
        this.id = id;
        this.type = type;
        this.function = function;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public FunctionCall getFunction() { return function; }
    public void setFunction(FunctionCall function) { this.function = function; }
}
