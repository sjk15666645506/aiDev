package com.deepseek.demo.dto;

/**
 * DeepSeek function calling 中的函数调用详情。
 * 包含 LLM 决定调用的函数名和 JSON 格式的参数。
 */
public class FunctionCall {

    /** 函数名称，对应 @Tool.name() */
    private String name;

    /** 参数 JSON 字符串，如 "{\"title\":\"需求评审\",\"assignee\":\"张三\"}" */
    private String arguments;

    public FunctionCall() {}

    public FunctionCall(String name, String arguments) {
        this.name = name;
        this.arguments = arguments;
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getArguments() { return arguments; }
    public void setArguments(String arguments) { this.arguments = arguments; }
}
