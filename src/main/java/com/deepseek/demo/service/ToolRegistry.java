package com.deepseek.demo.service;

import com.deepseek.demo.annotation.ActionType;
import com.deepseek.demo.annotation.Tool;
import com.deepseek.demo.annotation.ToolDomain;
import com.deepseek.demo.annotation.ToolParam;
import com.deepseek.demo.dto.ToolCall;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * 工具注册中心，负责扫描并管理所有 @Tool 注解的工具。
 * <p>
 * 在 Spring 容器初始化完成后，扫描所有 Bean 中的 @Tool 方法，
 * 构建 {@link ToolMeta} 元数据，并提供 JSON Schema 生成、
 * 白名单校验以及工具执行能力。
 */
@Component
public class ToolRegistry implements ApplicationContextAware, ApplicationListener<ContextRefreshedEvent> {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);

    /** 工具白名单集合，白名单内的工具可自动执行（跳过二次确认） */
    private final Set<String> whitelist;

    /** Jackson ObjectMapper，用于 JSON 序列化/反序列化 */
    private final ObjectMapper objectMapper;

    /** Spring 应用上下文 */
    private ApplicationContext applicationContext;

    /** 已注册的工具映射：名称 -> ToolMeta */
    private final Map<String, ToolMeta> tools = new HashMap<>();

    /** 是否已完成 @Tool 扫描（确保 ContextRefreshedEvent 只处理一次） */
    private boolean scanned = false;

    /**
     * 构造 ToolRegistry
     *
     * @param whitelistStr 白名单配置（逗号分隔），来自 application.yml 的 tool.whitelist
     * @param objectMapper Jackson ObjectMapper
     */
    public ToolRegistry(@Value("${tool.whitelist:}") String whitelistStr,
                        ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        // 将逗号分隔的白名单字符串解析为 Set
        this.whitelist = whitelistStr == null || whitelistStr.isEmpty()
                ? Collections.emptySet()
                : Arrays.stream(whitelistStr.split(","))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .collect(Collectors.toCollection(HashSet::new));
        log.info("工具白名单初始化完成: {}", this.whitelist);
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    @Override
    public void onApplicationEvent(ContextRefreshedEvent event) {
        // 确保只扫描一次
        if (scanned) return;
        scanned = true;

        log.info("开始扫描 @Tool 注解...");
        // 获取所有 Spring Bean 的名称
        String[] beanNames = applicationContext.getBeanDefinitionNames();
        int totalFound = 0;

        for (String beanName : beanNames) {
            // 跳过自身，避免在初始化过程中调用 getBean 导致循环引用
            if (beanName.equals("toolRegistry")) {
                continue;
            }
            Object bean = applicationContext.getBean(beanName);
            Class<?> beanClass = bean.getClass();

            // 遍历 Bean 的所有方法（包括继承的方法）
            for (Method method : beanClass.getMethods()) {
                Tool toolAnnotation = method.getAnnotation(Tool.class);
                if (toolAnnotation == null) {
                    continue;
                }

                // 解析 @Tool 注解
                String name = toolAnnotation.name();
                ActionType action = toolAnnotation.action();
                ToolParam[] toolParams = toolAnnotation.parameters();

                // 构建参数 schema 列表
                List<Map<String, Object>> parameters = new ArrayList<>();
                List<String> requiredParams = new ArrayList<>();

                for (ToolParam param : toolParams) {
                    Map<String, Object> paramSchema = new HashMap<>();
                    paramSchema.put("name", param.name());
                    paramSchema.put("type", param.type());
                    paramSchema.put("description", param.description());
                    parameters.add(paramSchema);

                    if (param.required()) {
                        requiredParams.add(param.name());
                    }
                }

                // 收集 capabilities
                List<String> capabilities = Arrays.asList(toolAnnotation.capabilities());

                // 注册工具
                ToolMeta meta = new ToolMeta(
                        name,
                        toolAnnotation.description(),
                        parameters,
                        requiredParams,
                        toolAnnotation.action(),
                        toolAnnotation.domain(),
                        capabilities,
                        bean,
                        method
                );
                tools.put(name, meta);
                totalFound++;
                log.info("注册工具: name={}, action={}, bean={}, method={}",
                        name, action, beanClass.getSimpleName(), method.getName());
            }
        }

        log.info("@Tool 注解扫描完成，共发现 {} 个工具", totalFound);
    }

    /**
     * 将所有已注册的工具转换为 DeepSeek function calling 的 JSON Schema 格式。
     * <p>
     * 返回的列表可直接作为 {@code tools} 参数传递给 DeepSeek API。
     *
     * @return JSON Schema 列表
     */
    public List<Map<String, Object>> toJsonSchema() {
        List<Map<String, Object>> schemas = new ArrayList<>();

        for (ToolMeta meta : tools.values()) {
            Map<String, Object> schema = buildJsonSchema(meta);
            schemas.add(schema);
        }

        return schemas;
    }

    /**
     * 将指定工具列表转换为 DeepSeek function calling 的 JSON Schema 格式。
     *
     * @param toolMetas 要转换的工具列表
     * @return JSON Schema 列表
     */
    public List<Map<String, Object>> toJsonSchema(List<ToolMeta> toolMetas) {
        List<Map<String, Object>> schemas = new ArrayList<>();
        for (ToolMeta meta : toolMetas) {
            schemas.add(buildJsonSchema(meta));
        }
        return schemas;
    }

    /**
     * 为单个工具构建 JSON Schema
     */
    private Map<String, Object> buildJsonSchema(ToolMeta meta) {
        Map<String, Object> function = new HashMap<>();
        function.put("name", meta.getName());
        function.put("description", meta.getDescription());

        Map<String, Object> parameters = new HashMap<>();
        parameters.put("type", "object");

        // 构建 properties
        Map<String, Object> properties = new HashMap<>();
        for (Map<String, Object> param : meta.getParameters()) {
            String paramName = (String) param.get("name");
            Map<String, Object> property = new HashMap<>();
            property.put("type", param.get("type"));
            property.put("description", param.get("description"));
            properties.put(paramName, property);
        }
        parameters.put("properties", properties);

        // 构建 required
        if (!meta.getRequiredParams().isEmpty()) {
            parameters.put("required", new ArrayList<>(meta.getRequiredParams()));
        }

        function.put("parameters", parameters);

        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "function");
        schema.put("function", function);

        return schema;
    }

    /**
     * 根据工具名称获取工具元数据
     *
     * @param name 工具名称
     * @return 工具元数据
     * @throws IllegalArgumentException 如果指定名称的工具不存在
     */
    public ToolMeta getTool(String name) {
        ToolMeta meta = tools.get(name);
        if (meta == null) {
            throw new IllegalArgumentException("未知工具: " + name);
        }
        return meta;
    }

    /**
     * 获取所有已注册的工具（只读视图）
     *
     * @return 工具名称到元数据的不可变映射
     */
    public Map<String, ToolMeta> getAllTools() {
        return Collections.unmodifiableMap(tools);
    }

    /**
     * 按领域获取工具。
     *
     * @param domain 目标领域
     * @return 该领域下的所有工具列表
     */
    public List<ToolMeta> getByDomain(ToolDomain domain) {
        return tools.values().stream()
                .filter(meta -> meta.getDomain() == domain)
                .collect(Collectors.toList());
    }

    /**
     * 判断指定工具是否在白名单中，白名单内的工具可自动执行。
     *
     * @param toolName 工具名称
     * @return true 如果工具在白名单中
     */
    public boolean isAutoConfirm(String toolName) {
        return whitelist.contains(toolName);
    }

    /**
     * 执行 LLM 发起的工具调用。
     * <p>
     * 解析 tool_call 中的参数 JSON，通过反射调用对应方法，
     * 并设置 10 秒超时保护。
     *
     * @param toolCall DeepSeek API 返回的 tool_call 对象
     * @return 工具执行结果字符串
     * @throws IllegalArgumentException 如果工具不存在或参数解析失败
     * @throws RuntimeException 如果工具执行超时或执行异常
     */
    public String execute(ToolCall toolCall) {
        String toolName = toolCall.getFunction().getName();
        log.info("执行工具: name={}, arguments={}", toolName,
                truncate(toolCall.getFunction().getArguments(), 100));

        ToolMeta meta = getTool(toolName);

        // 解析 JSON 参数
        Map<String, Object> args;
        try {
            args = objectMapper.readValue(toolCall.getFunction().getArguments(),
                    objectMapper.getTypeFactory().constructMapType(Map.class, String.class, Object.class));
        } catch (Exception e) {
            throw new IllegalArgumentException("工具参数解析失败: " + e.getMessage(), e);
        }

        // 按参数顺序构建参数数组
        List<Map<String, Object>> paramDefs = meta.getParameters();
        Object[] methodArgs = new Object[paramDefs.size()];
        for (int i = 0; i < paramDefs.size(); i++) {
            String paramName = (String) paramDefs.get(i).get("name");
            methodArgs[i] = args.get(paramName);
        }

        // 使用 ExecutorService + Future.get() 执行，带 10 秒超时
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Object> future = executor.submit(() ->
                    meta.getMethod().invoke(meta.getBean(), methodArgs));

            Object result = future.get(10, TimeUnit.SECONDS);
            String resultStr = result != null ? result.toString() : "";
            log.info("工具执行成功: name={}, result={}", toolName, truncate(resultStr, 100));
            return resultStr;

        } catch (TimeoutException e) {
            log.error("工具执行超时: name={}, timeout=10s", toolName);
            throw new RuntimeException("工具执行超时: " + toolName);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            log.error("工具执行异常: name={}, error={}", toolName, cause != null ? cause.getMessage() : e.getMessage());
            throw new RuntimeException("工具执行失败: " + (cause != null ? cause.getMessage() : e.getMessage()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("工具执行被中断: name={}", toolName);
            throw new RuntimeException("工具执行被中断: " + toolName);
        } finally {
            executor.shutdown();
        }
    }

    /** 截断长文本用于日志输出 */
    private String truncate(String s, int maxLen) {
        if (s == null) return null;
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}
