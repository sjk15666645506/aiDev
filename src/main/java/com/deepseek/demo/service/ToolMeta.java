package com.deepseek.demo.service;

import com.deepseek.demo.annotation.ActionType;
import com.deepseek.demo.annotation.ToolDomain;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

/**
 * 工具元数据，存储从 {@link com.deepseek.demo.annotation.Tool} 注解解析的信息。
 * 包含工具的名称、描述、参数 schema、操作方法引用以及操作类型。
 */
public class ToolMeta {

    /** 工具名称 */
    private final String name;
    /** 工具描述 */
    private final String description;
    /** 参数的 JSON Schema properties */
    private final List<Map<String, Object>> parameters;
    /** 必需参数名称列表 */
    private final List<String> requiredParams;
    /** 操作类型 */
    private final ActionType action;
    /** 工具归属领域 */
    private final ToolDomain domain;
    /** 工具提供的能力标签 */
    private final List<String> capabilities;
    /** Bean 实例引用 */
    private final Object bean;
    /** 方法引用 */
    private final Method method;

    public ToolMeta(String name, String description,
                    List<Map<String, Object>> parameters,
                    List<String> requiredParams,
                    ActionType action,
                    ToolDomain domain,
                    List<String> capabilities,
                    Object bean, Method method) {
        this.name = name;
        this.description = description;
        this.parameters = parameters;
        this.requiredParams = requiredParams;
        this.action = action;
        this.domain = domain;
        this.capabilities = capabilities == null
                ? List.of()
                : List.copyOf(capabilities);
        this.bean = bean;
        this.method = method;
    }

    // getters
    public String getName() { return name; }
    public String getDescription() { return description; }
    public List<Map<String, Object>> getParameters() { return parameters; }
    public List<String> getRequiredParams() { return requiredParams; }
    public ActionType getAction() { return action; }
    public ToolDomain getDomain() { return domain; }
    public List<String> getCapabilities() { return capabilities; }
    public Object getBean() { return bean; }
    public Method getMethod() { return method; }
}
