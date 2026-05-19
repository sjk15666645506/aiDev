package com.deepseek.demo.service;

import com.deepseek.demo.annotation.ToolDomain;
import com.deepseek.demo.dto.ToolCall;

import java.util.List;
import java.util.Map;

public interface IToolRegistry {
    List<Map<String, Object>> toJsonSchema();
    List<Map<String, Object>> toJsonSchema(List<ToolMeta> toolMetas);
    ToolMeta getTool(String name);
    Map<String, ToolMeta> getAllTools();
    List<ToolMeta> getByDomain(ToolDomain domain);
    boolean isAutoConfirm(String toolName);
    String execute(ToolCall toolCall);
}
