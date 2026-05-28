package com.deepseek.demo.service;

import com.deepseek.demo.annotation.ActionType;
import com.deepseek.demo.annotation.Tool;
import com.deepseek.demo.annotation.ToolDomain;
import com.deepseek.demo.annotation.ToolParam;
import com.deepseek.demo.util.StringUtils;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolParameters;
import dev.langchain4j.agent.tool.ToolSpecification;
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
import javax.annotation.PreDestroy;
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
public class ToolRegistry implements IToolRegistry, ApplicationContextAware, ApplicationListener<ContextRefreshedEvent> {

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

    /** 工具执行超时秒数 */
    private final int defaultTimeoutSeconds;

    /** 工具执行最大重试次数（超时后重试） */
    private final int maxRetries;

    /** 指数退避初始延迟（毫秒） */
    private final long backoffInitialMs;

    /** 工具执行线程池（共享，避免每次调用创建新线程） */
    private final ExecutorService toolExecutor = Executors.newCachedThreadPool();

    /**
     * 构造 ToolRegistry
     *
     * @param whitelistStr      白名单配置（逗号分隔），来自 application.yml 的 tool.whitelist
     * @param objectMapper      Jackson ObjectMapper
     * @param defaultTimeoutSeconds 工具执行超时秒数
     * @param maxRetries        工具执行超时后最大重试次数
     * @param backoffInitialMs  指数退避初始延迟（毫秒）
     */
    public ToolRegistry(@Value("${tool.whitelist:}") String whitelistStr,
                        ObjectMapper objectMapper,
                        @Value("${tool.execution.timeout-seconds:10}") int defaultTimeoutSeconds,
                        @Value("${tool.execution.max-retries:2}") int maxRetries,
                        @Value("${tool.execution.backoff-initial-ms:1000}") long backoffInitialMs) {
        this.objectMapper = objectMapper;
        this.defaultTimeoutSeconds = defaultTimeoutSeconds;
        this.maxRetries = maxRetries;
        this.backoffInitialMs = backoffInitialMs;
        // 将逗号分隔的白名单字符串解析为 Set
        this.whitelist = whitelistStr == null || whitelistStr.isEmpty()
                ? Collections.emptySet()
                : Arrays.stream(whitelistStr.split(","))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .collect(Collectors.toCollection(HashSet::new));
        log.info("工具白名单初始化完成: {}", this.whitelist);
        log.info("工具执行配置: timeout={}s, maxRetries={}, backoffInitialMs={}ms",
                defaultTimeoutSeconds, maxRetries, backoffInitialMs);
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
        return toJsonSchema(new ArrayList<>(tools.values()));
    }

    /**
     * 将指定工具列表转换为 DeepSeek function calling 的 JSON Schema 格式。
     *
     * @param toolMetas 要转换的工具列表
     * @return JSON Schema 列表
     */
     public List<Map<String, Object>> toJsonSchema(List<ToolMeta> toolMetas) {
        if (toolMetas == null || toolMetas.isEmpty()) {
            return new ArrayList<>();
        }
        List<Map<String, Object>> schemas = new ArrayList<>(toolMetas.size());
        for (ToolMeta meta : toolMetas) {
            schemas.add(buildJsonSchema(meta));
        }
        return schemas;
    }

    /**
     * 将所有已注册的工具转换为 LangChain4j {@link ToolSpecification} 列表。
     * <p>
     * 直接生成 LangChain4j 原生工具规格，Phase 3 中 ReActEngine → AiServices
     * 迁移时将直接使用此方法，跳过 Map 中间格式。
     *
     * @return ToolSpecification 列表
     */
    public List<ToolSpecification> toToolSpecifications() {
        return toToolSpecifications(new ArrayList<>(tools.values()));
    }

    /**
     * 将指定工具列表转换为 LangChain4j {@link ToolSpecification} 列表。
     *
     * @param toolMetas 要转换的工具元数据列表
     * @return ToolSpecification 列表
     */
    public List<ToolSpecification> toToolSpecifications(List<ToolMeta> toolMetas) {
        if (toolMetas == null || toolMetas.isEmpty()) return new ArrayList<>();
        List<ToolSpecification> specs = new ArrayList<>(toolMetas.size());
        for (ToolMeta meta : toolMetas) {
            specs.add(toToolSpecification(meta));
        }
        return specs;
    }

    /**
     * 将单个工具元数据转换为 LangChain4j {@link ToolSpecification}。
     */
    private ToolSpecification toToolSpecification(ToolMeta meta) {
        ToolSpecification.Builder builder = ToolSpecification.builder()
                .name(meta.getName())
                .description(meta.getDescription());

        ToolParameters.Builder tpBuilder = ToolParameters.builder()
                .type("object");

        Map<String, Map<String, Object>> propsMap = new LinkedHashMap<>();
        for (Map<String, Object> param : meta.getParameters()) {
            String paramName = (String) param.get("name");
            Map<String, Object> prop = new LinkedHashMap<>();
            prop.put("type", param.get("type"));
            prop.put("description", param.get("description"));
            propsMap.put(paramName, prop);
        }
        tpBuilder.properties(propsMap);

        if (!meta.getRequiredParams().isEmpty()) {
            tpBuilder.required(new ArrayList<>(meta.getRequiredParams()));
        }

        builder.parameters(tpBuilder.build());
        return builder.build();
    }

    /**
     * 为单个工具构建 JSON Schema（委托给 LangChain4j ToolSpecification）。
     */
    private Map<String, Object> buildJsonSchema(ToolMeta meta) {
        ToolSpecification spec = toToolSpecification(meta);
        ToolParameters tp = spec.parameters();

        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", spec.name());
        function.put("description", spec.description());

        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("type", tp.type());
        parameters.put("properties", tp.properties() != null
                ? new LinkedHashMap<>(tp.properties()) : new LinkedHashMap<>());
        if (tp.required() != null && !tp.required().isEmpty()) {
            parameters.put("required", tp.required());
        }
        function.put("parameters", parameters);

        Map<String, Object> schema = new LinkedHashMap<>();
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
     * 解析 tool_call 中的参数 JSON，通过反射调用对应方法。
     * 内置超时保护 + 超时自动重试（指数退避 + 随机抖动）。
     *
     * @param toolCall DeepSeek API 返回的 tool_call 对象
     * @return 工具执行结果字符串
     * @throws IllegalArgumentException 如果工具不存在或参数解析失败
     * @throws RuntimeException 如果工具执行超时（重试耗尽）或非可重试异常
     */
    @Override
    public String execute(ToolExecutionRequest request) {
        String toolName = request.name();
        log.info("执行工具: name={}, arguments={}", toolName,
                StringUtils.truncate(request.arguments(), 100));

        ToolMeta meta = getTool(toolName);

        // 解析 JSON 参数
        Map<String, Object> args;
        try {
            args = objectMapper.readValue(request.arguments(),
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

        // 执行（带超时重试）
        String result = executeWithRetry(meta, methodArgs, toolName);
        log.info("工具执行成功: name={}, result={}", toolName, StringUtils.truncate(result, 100));
        return result;
    }

    /**
     * 带指数退避重试的工具执行。
     * <p>
     * 仅在超时（TimeoutException）时重试，非可重试异常直接抛出。
     * 每次重试间隔 = backoffInitialMs * 2^(attempt-1) + 随机抖动(±25%)。
     *
     * @param meta       工具元数据
     * @param methodArgs 方法参数数组
     * @param toolName   工具名称（日志用）
     * @return 执行结果字符串
     */
    private String executeWithRetry(ToolMeta meta, Object[] methodArgs, String toolName) {
        int attempt = 0;
        TimeoutException lastTimeout = null;

        while (attempt <= maxRetries) {
            if (attempt > 0) {
                // 指数退避：base * 2^(attempt-1) + jitter ±25%
                long delay = (long) (backoffInitialMs * Math.pow(2, attempt - 1));
                delay += (long) (delay * (Math.random() - 0.5) * 0.5);
                log.warn("重试工具: name={}, attempt={}/{}, delay={}ms",
                        toolName, attempt, maxRetries, delay);
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("工具执行被中断: " + toolName);
                }
            }

            attempt++;
            try {
                Future<Object> future = toolExecutor.submit(() ->
                        meta.getMethod().invoke(meta.getBean(), methodArgs));
                Object result = future.get(defaultTimeoutSeconds, TimeUnit.SECONDS);
                return result != null ? result.toString() : "";
            } catch (TimeoutException e) {
                lastTimeout = e;
                log.warn("工具执行超时: name={}, timeout={}s, attempt={}/{}",
                        toolName, defaultTimeoutSeconds, attempt, maxRetries + 1);
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                log.error("工具执行异常(不可重试): name={}, error={}",
                        toolName, cause != null ? cause.getMessage() : e.getMessage());
                throw new RuntimeException("工具执行失败: " + (cause != null ? cause.getMessage() : e.getMessage()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("工具执行被中断: name={}", toolName);
                throw new RuntimeException("工具执行被中断: " + toolName);
            }
        }

        // 重试耗尽，抛出原始超时异常
        throw new RuntimeException("工具执行超时(已重试" + maxRetries + "次): " + toolName);
    }

    @PreDestroy
    public void shutdown() {
        toolExecutor.shutdown();
    }
}
