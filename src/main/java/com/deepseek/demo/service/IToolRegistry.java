package com.deepseek.demo.service;

import com.deepseek.demo.annotation.ToolDomain;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;

import java.util.List;
import java.util.Map;

public interface IToolRegistry {
    List<ToolSpecification> toToolSpecifications();
    List<ToolSpecification> toToolSpecifications(List<ToolMeta> toolMetas);
    ToolMeta getTool(String name);
    Map<String, ToolMeta> getAllTools();
    List<ToolMeta> getByDomain(ToolDomain domain);
    boolean isAutoConfirm(String toolName);
    String execute(ToolExecutionRequest request);
}
