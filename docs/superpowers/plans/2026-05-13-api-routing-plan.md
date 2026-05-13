# API 三层路由引擎 — 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 当系统接入 50+ 外部 API 时，保证 LLM 能准确选择正确的工具调用，避免误选、漏选。

**Architecture:** 三层路由架构——Layer 1 DomainRouter 确定性分类用户意图归属领域；Layer 2 ToolRetriever 在领域内用语义检索 + 频率衰减召回 top K 工具；Layer 3 CapabilityGuard 在 LLM 选定工具后执行前校验能力是否匹配。三层递进缩小 LLM 选择范围、增加确定性兜底。

**Tech Stack:** Java 11, Spring Boot 2.7.9, Ollama embedding (nomic-embed-text, 768维), DeepSeek API (低成本意图分类), 内存向量存储

---

## File Structure

### New Files
| File | Responsibility |
|------|----------------|
| `annotation/ToolDomain.java` | 领域枚举，工具归属分类 |
| `service/DomainRouter.java` | Layer 1: 用户意图 → 领域分类 |
| `service/ToolVectorStore.java` | 工具 embedding 的内存向量存储（余弦距离搜索） |
| `service/ToolRetriever.java` | Layer 2: 领域内语义检索 + 频率衰减召回 |
| `service/CapabilityGuard.java` | Layer 3: 工具/能力/意图匹配校验 |

### Modified Files
| File | Changes |
|------|---------|
| `annotation/Tool.java` | 新增 `domain()` 和 `capabilities()` 字段 |
| `service/ToolMeta.java` | 新增 domain、capabilities 字段和 getter |
| `service/ToolRegistry.java` | 解析新注解字段；新增 `getByDomain()`、`getAllTools()` 方法；`toJsonSchema()` 可接受 tool 列表 |
| `service/AgentService.java` | chat() 入口集成三层路由；agentLoop() 集成 CapabilityGuard 校验 |

---

### Task 1: 新增 ToolDomain 枚举

**Files:**
- Create: `src/main/java/com/deepseek/demo/annotation/ToolDomain.java`

- [ ] **Step 1: 创建 ToolDomain 枚举**

```java
package com.deepseek.demo.annotation;

/**
 * API 工具归属领域，用于 DomainRouter 按领域分类。
 * 每个 ToolDomain 包含一个中文描述和关键词，供分类器匹配。
 */
public enum ToolDomain {

    TASK_MANAGEMENT("任务管理", "任务,工单,待办,jira,需求,story,缺陷,bug"),
    CODE_REPOSITORY("代码仓库", "代码,仓库,分支,pr,merge request,commit,github,gitlab"),
    CI_CD("CI/CD", "部署,发布,流水线,构建,jenkins,action,workflow"),
    MONITORING("监控告警", "监控,告警,metric,日志,链路,sentry,prometheus"),
    NOTIFICATION("消息通知", "通知,消息,飞书,钉钉,企微,webhook,发送"),
    DOCUMENT("文档知识库", "文档,知识库,confluence,notion,语雀,wiki"),
    SEARCH("搜索查询", "搜索,查询,检索,查找,全文搜索"),
    USER_MANAGEMENT("用户权限", "用户,组织,权限,角色,成员,部门");

    private final String displayName;
    private final String keywords;

    ToolDomain(String displayName, String keywords) {
        this.displayName = displayName;
        this.keywords = keywords;
    }

    public String getDisplayName() { return displayName; }
    public String getKeywords() { return keywords; }
}
```

- [ ] **Step 2: Commit**

```bash
git add src/main/java/com/deepseek/demo/annotation/ToolDomain.java
git commit -m "feat: add ToolDomain enum for API classification"
```

---

### Task 2: 增强 @Tool 注解

**Files:**
- Modify: `src/main/java/com/deepseek/demo/annotation/Tool.java`

- [ ] **Step 1: 添加 domain 和 capabilities 字段**

```java
package com.deepseek.demo.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记一个方法为 LLM 可调用的工具。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Tool {

    /** 工具名称，用于 DeepSeek tool_call 中的 function name */
    String name();

    /** 工具描述，帮助 LLM 理解何时调用此工具 */
    String description();

    /** 工具的参数列表 */
    ToolParam[] parameters() default {};

    /** 操作类型，决定是否需要二次确认 */
    ActionType action() default ActionType.READ;

    /** 工具归属领域。DomainRouter 据此按领域筛选工具 */
    ToolDomain domain() default ToolDomain.SEARCH;

    /**
     * 工具提供的能力标签，用于 CapabilityGuard 校验。
     * 格式: "domain:action"，如 "task:read"、"task:write"、"code:search"
     */
    String[] capabilities() default {};
}
```

- [ ] **Step 2: Commit**

```bash
git add src/main/java/com/deepseek/demo/annotation/Tool.java
git commit -m "feat: add domain and capabilities to @Tool annotation"
```

---

### Task 3: 更新 ToolMeta

**Files:**
- Modify: `src/main/java/com/deepseek/demo/service/ToolMeta.java`

- [ ] **Step 1: 添加 domain 和 capabilities 字段**

```java
package com.deepseek.demo.service;

import com.deepseek.demo.annotation.ActionType;
import com.deepseek.demo.annotation.ToolDomain;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

public class ToolMeta {

    private final String name;
    private final String description;
    private final List<Map<String, Object>> parameters;
    private final List<String> requiredParams;
    private final ActionType action;
    /** 工具归属领域 */
    private final ToolDomain domain;
    /** 工具提供的能力标签 */
    private final List<String> capabilities;
    private final Object bean;
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
        this.capabilities = capabilities;
        this.bean = bean;
        this.method = method;
    }

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
```

- [ ] **Step 2: 更新 ToolRegistry 中 ToolMeta 创建处**

In `ToolRegistry.java`, find the `ToolMeta meta = new ToolMeta(...)` line and add the new parameters:

```java
// 收集 capabilities
List<String> capabilities = Arrays.asList(toolAnnotation.capabilities());

ToolMeta meta = new ToolMeta(
    name,
    toolAnnotation.description(),
    params,
    requiredParams,
    toolAnnotation.action(),
    toolAnnotation.domain(),
    capabilities,
    bean,
    method
);
```

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/deepseek/demo/service/ToolMeta.java \
       src/main/java/com/deepseek/demo/service/ToolRegistry.java
git commit -m "feat: add domain and capabilities to ToolMeta"
```

---

### Task 4: 更新 ToolRegistry — 新增按领域获取方法

**Files:**
- Modify: `src/main/java/com/deepseek/demo/service/ToolRegistry.java`

- [ ] **Step 1: 添加 getAllTools() 和 getByDomain() 方法**

```java
    /**
     * 获取所有已注册的工具（只读视图）
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
```

- [ ] **Step 2: 添加 toJsonSchema(List<ToolMeta>) 重载**

```java
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
```

- [ ] **Step 3: 添加 imports（如果缺失）**

Add to imports:
```java
import com.deepseek.demo.annotation.ToolDomain;
```

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/deepseek/demo/service/ToolRegistry.java
git commit -m "feat: add getAllTools, getByDomain, and filtered toJsonSchema to ToolRegistry"
```

---

### Task 5: 创建 ToolVectorStore — 工具向量存储

**Files:**
- Create: `src/main/java/com/deepseek/demo/service/ToolVectorStore.java`

- [ ] **Step 1: 写测试**

```java
package com.deepseek.demo.service;

import com.deepseek.demo.annotation.ToolDomain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ToolVectorStoreTest {

    private ToolVectorStore store;

    @BeforeEach
    void setUp() {
        store = new ToolVectorStore();
    }

    @Test
    void shouldSaveAndSearchByCosineSimilarity() {
        // 两个平行向量（余弦相似度 ≈ 1）
        store.save("tool-a", new float[]{1, 0, 0});
        // 一个垂直向量（余弦相似度 ≈ 0）
        store.save("tool-b", new float[]{0, 1, 0});

        float[] query = new float[]{0.99f, 0.01f, 0};
        List<ToolVectorStore.ScoredTool> results = store.search(query, 5);

        assertEquals(2, results.size());
        assertEquals("tool-a", results.get(0).getName());
        assertTrue(results.get(0).getScore() > 0.9);
    }

    @Test
    void shouldRespectTopK() {
        store.save("t1", new float[]{1, 0});
        store.save("t2", new float[]{1, 0});
        store.save("t3", new float[]{1, 0});

        List<ToolVectorStore.ScoredTool> results = store.search(new float[]{1, 0}, 2);

        assertEquals(2, results.size());
    }

    @Test
    void shouldReturnEmptyWhenNoTools() {
        List<ToolVectorStore.ScoredTool> results = store.search(new float[]{1, 0}, 5);

        assertTrue(results.isEmpty());
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn test -Dtest=ToolVectorStoreTest -q`
Expected: BUILD FAILURE (class not found)

- [ ] **Step 3: 实现 ToolVectorStore**

```java
package com.deepseek.demo.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 工具 Embedding 内存向量存储。
 * <p>
 * 在系统启动时接收所有工具的 Name + Description 的 Embedding 向量，
 * 并提供余弦相似度检索，供 ToolRetriever 按语义召回相关工具。
 * 纯内存实现，无需 Qdrant 等外部依赖。
 */
@Component
public class ToolVectorStore {

    private static final Logger log = LoggerFactory.getLogger(ToolVectorStore.class);

    /** 工具名称 → Embedding 向量 */
    private final Map<String, float[]> store = new ConcurrentHashMap<>();

    /**
     * 保存工具的 Embedding 向量。
     *
     * @param toolName 工具名称（唯一标识）
     * @param vector   Embedding 向量（768 维 float 数组）
     */
    public void save(String toolName, float[] vector) {
        store.put(toolName, vector);
    }

    /**
     * 从存储中移除工具。
     *
     * @param toolName 工具名称
     */
    public void remove(String toolName) {
        store.remove(toolName);
    }

    /**
     * 清空所有向量。
     */
    public void clear() {
        store.clear();
    }

    /**
     * 向量数量。
     *
     * @return 当前存储的工具数
     */
    public int size() {
        return store.size();
    }

    /**
     * 余弦相似度检索，按相似度降序返回。
     *
     * @param query 查询向量
     * @param topK  返回 top K 个结果
     * @return 按相似度降序排列的结果列表
     */
    public List<ScoredTool> search(float[] query, int topK) {
        if (store.isEmpty()) {
            return Collections.emptyList();
        }

        return store.entrySet().stream()
                .map(entry -> new ScoredTool(
                        entry.getKey(),
                        cosineSimilarity(query, entry.getValue())))
                .filter(r -> r.getScore() > 0)  // 过滤掉负分（完全不相关）
                .sorted((a, b) -> Float.compare(b.getScore(), a.getScore()))
                .limit(topK)
                .collect(Collectors.toList());
    }

    /**
     * 计算两个向量的余弦相似度。
     */
    private float cosineSimilarity(float[] a, float[] b) {
        if (a.length != b.length) {
            throw new IllegalArgumentException("向量维度不匹配: " + a.length + " vs " + b.length);
        }

        float dotProduct = 0;
        float normA = 0;
        float normB = 0;

        for (int i = 0; i < a.length; i++) {
            dotProduct += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }

        float denominator = (float) (Math.sqrt(normA) * Math.sqrt(normB));
        if (denominator == 0) return 0;

        return dotProduct / denominator;
    }

    /**
     * 带得分的检索结果。
     */
    public static class ScoredTool {
        private final String name;
        private final float score;

        public ScoredTool(String name, float score) {
            this.name = name;
            this.score = score;
        }

        public String getName() { return name; }
        public float getScore() { return score; }
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn test -Dtest=ToolVectorStoreTest -q`
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/deepseek/demo/service/ToolVectorStore.java \
       src/test/java/com/deepseek/demo/service/ToolVectorStoreTest.java
git commit -m "feat: add ToolVectorStore for in-memory tool embedding search"
```

---

### Task 6: 创建 DomainRouter — Layer 1 意图分类

**Files:**
- Create: `src/main/java/com/deepseek/demo/service/DomainRouter.java`

- [ ] **Step 1: 写测试（Mock LLM 返回）**

```java
package com.deepseek.demo.service;

import com.deepseek.demo.annotation.ToolDomain;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DomainRouterTest {

    /** 模拟 LLM 分类响应 */
    private final DomainRouter router = new DomainRouter(null, null) {
        @Override
        public ToolDomain classify(String userMessage) {
            if (userMessage.contains("任务") || userMessage.contains("工单"))
                return ToolDomain.TASK_MANAGEMENT;
            if (userMessage.contains("部署") || userMessage.contains("发布"))
                return ToolDomain.CI_CD;
            if (userMessage.contains("代码") || userMessage.contains("仓库"))
                return ToolDomain.CODE_REPOSITORY;
            return ToolDomain.SEARCH;
        }
    };

    @Test
    void shouldClassifyTaskQuery() {
        assertEquals(ToolDomain.TASK_MANAGEMENT,
                router.classify("帮我查一下张三的待办任务"));
    }

    @Test
    void shouldClassifyDeployQuery() {
        assertEquals(ToolDomain.CI_CD,
                router.classify("把最新代码部署到测试环境"));
    }

    @Test
    void shouldClassifyCodeQuery() {
        assertEquals(ToolDomain.CODE_REPOSITORY,
                router.classify("查一下这个仓库的分支列表"));
    }

    @Test
    void shouldFallbackToSearch() {
        assertEquals(ToolDomain.SEARCH,
                router.classify("今天天气怎么样"));
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn test -Dtest=DomainRouterTest -q`
Expected: BUILD FAILURE

- [ ] **Step 3: 实现 DomainRouter**

```java
package com.deepseek.demo.service;

import com.deepseek.demo.annotation.ToolDomain;
import com.deepseek.demo.dto.Message;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Layer 1: 领域路由器。
 * <p>
 * 通过一次轻量 LLM 调用对用户意图进行领域分类。
 * 使用独立的快速调用（temperature=0），仅消耗几十个 token，
 * 将结果限定到 1-2 个领域，大幅缩小后续 LLM 的工具选择范围。
 */
@Component
public class DomainRouter {

    private static final Logger log = LoggerFactory.getLogger(DomainRouter.class);

    private final DeepSeekService deepSeekService;
    private final ObjectMapper objectMapper;

    public DomainRouter(DeepSeekService deepSeekService, ObjectMapper objectMapper) {
        this.deepSeekService = deepSeekService;
        this.objectMapper = objectMapper;
    }

    /**
     * 对用户消息进行意图分类，返回最匹配的领域。
     * 分类失败时降级为 SEARCH（最通用的领域）。
     *
     * @param userMessage 用户输入
     * @return 匹配的领域
     */
    public ToolDomain classify(String userMessage) {
        try {
            // 构建分类 prompt
            String systemPrompt = buildClassifierPrompt();
            String response = deepSeekService.chatWithSystem(systemPrompt, userMessage);

            if (response == null || response.isBlank()) {
                log.warn("分类器返回空，降级为 SEARCH");
                return ToolDomain.SEARCH;
            }

            // 解析 LLM 返回的领域名
            String domainStr = response.trim().toUpperCase();
            return ToolDomain.valueOf(domainStr);

        } catch (Exception e) {
            log.warn("意图分类失败，降级为 SEARCH: {}", e.getMessage());
            return ToolDomain.SEARCH;
        }
    }

    /**
     * 构建领域分类器的系统提示词。
     */
    private String buildClassifierPrompt() {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一个领域分类器。请判断用户请求属于以下哪个领域，只返回领域名称本身，不要返回其他内容。\n\n");

        for (ToolDomain domain : ToolDomain.values()) {
            sb.append("- ").append(domain.name())
              .append(": ").append(domain.getDisplayName())
              .append("（").append(domain.getKeywords()).append("）\n");
        }

        sb.append("\n示例：\n");
        sb.append("用户：帮我查一下张三的工单\n");
        sb.append("返回：TASK_MANAGEMENT\n\n");
        sb.append("用户：把这段代码提交到仓库\n");
        sb.append("返回：CODE_REPOSITORY\n\n");
        sb.append("如果都不匹配，返回：SEARCH");

        return sb.toString();
    }
}
```

> 注意：需要 `DeepSeekService` 已有 `chatWithSystem(String systemPrompt, String userMessage)` 方法。如果不存在，在测试中 mock。

- [ ] **Step 4: 更新测试（如果 DeepSeekService 需要 mock）**

Tests for DomainRouter should use @SpringBootTest + @MockBean for DeepSeekService:

```java
package com.deepseek.demo.service;

import com.deepseek.demo.annotation.ToolDomain;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@SpringBootTest
class DomainRouterTest {

    @MockBean
    private DeepSeekService deepSeekService;

    @Autowired
    private DomainRouter router;

    @Test
    void shouldClassifyTaskQuery() {
        when(deepSeekService.chatWithSystem(anyString(), anyString()))
                .thenReturn("TASK_MANAGEMENT");

        assertEquals(ToolDomain.TASK_MANAGEMENT,
                router.classify("帮我查一下张三的待办任务"));
    }

    @Test
    void shouldFallbackToSearchOnError() {
        when(deepSeekService.chatWithSystem(anyString(), anyString()))
                .thenThrow(new RuntimeException("API error"));

        assertEquals(ToolDomain.SEARCH,
                router.classify("任意消息"));
    }
}
```

- [ ] **Step 5: 运行测试确认通过**

Run: `mvn test -Dtest=DomainRouterTest -q`
Expected: BUILD SUCCESS

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/deepseek/demo/service/DomainRouter.java \
       src/test/java/com/deepseek/demo/service/DomainRouterTest.java
git commit -m "feat: add DomainRouter for intent-to-domain classification"
```

---

### Task 7: 创建 ToolRetriever — Layer 2 工具召回

**Files:**
- Create: `src/main/java/com/deepseek/demo/service/ToolRetriever.java`
- Modify: `src/test/java/com/deepseek/demo/service/ToolRetrieverTest.java`

- [ ] **Step 1: 实现 ToolRetriever**

```java
package com.deepseek.demo.service;

import com.deepseek.demo.annotation.ToolDomain;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Layer 2: 工具召回器。
 * <p>
 * 在 DomainRouter 确定的领域内，结合语义检索和频率衰减，
 * 从该领域工具中召回最相关的 top K 个工具。
 * <p>
 * 语义检索：复用 ToolVectorStore，用用户 query 的 embedding 搜索。
 * 频率衰减：最近频繁使用的工具降低权重，给新工具曝光机会。
 */
@Component
public class ToolRetriever {

    private static final Logger log = LoggerFactory.getLogger(ToolRetriever.class);

    private final ToolRegistry toolRegistry;
    private final ToolVectorStore vectorStore;
    private final VectorService vectorService;
    private final FrequencyTracker frequencyTracker;

    public ToolRetriever(ToolRegistry toolRegistry,
                         ToolVectorStore vectorStore,
                         VectorService vectorService,
                         FrequencyTracker frequencyTracker) {
        this.toolRegistry = toolRegistry;
        this.vectorStore = vectorStore;
        this.vectorService = vectorService;
        this.frequencyTracker = frequencyTracker;
    }

    /**
     * 系统启动后，为所有已注册工具构建 Embedding 索引。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void init() {
        log.info("开始构建工具 Embedding 索引...");
        for (ToolMeta meta : toolRegistry.getAllTools().values()) {
            String text = buildEmbeddingText(meta);
            try {
                float[] vector = vectorService.embed(text);
                vectorStore.save(meta.getName(), vector);
            } catch (Exception e) {
                log.warn("工具 Embedding 失败: name={}, error={}", meta.getName(), e.getMessage());
            }
        }
        log.info("工具 Embedding 索引构建完成，共 {} 个工具", vectorStore.size());
    }

    /**
     * 从用户 query 中召回最相关的 top K 个工具。
     * 限制在指定领域内。
     */
    public List<ToolMeta> retrieve(String userQuery, ToolDomain domain, int topK) {
        // 1. 获取该领域的所有工具作为候选池
        List<ToolMeta> candidates = toolRegistry.getByDomain(domain);
        if (candidates.isEmpty()) {
            log.warn("领域 {} 下无已注册工具", domain);
            return Collections.emptyList();
        }

        // 2. 尝试语义检索
        List<ToolVectorStore.ScoredTool> scored;
        try {
            float[] queryVector = vectorService.embed(userQuery);
            scored = vectorStore.search(queryVector, topK);
        } catch (Exception e) {
            log.warn("语义检索失败，降级为频率排序: {}", e.getMessage());
            scored = Collections.emptyList();
        }

        // 3. 按领域过滤语义结果 + 补充频率最高的工具
        Set<String> selected = new LinkedHashSet<>();

        // 先加语义检索到的（必须是目标领域）
        for (ToolVectorStore.ScoredTool st : scored) {
            ToolMeta meta = toolRegistry.getTool(st.getName());
            if (meta != null && meta.getDomain() == domain) {
                selected.add(st.getName());
            }
        }

        // 如果不足 topK，补充该领域最近使用频率最高的
        if (selected.size() < topK) {
            List<String> popular = frequencyTracker.getMostUsed(domain, topK - selected.size());
            for (String name : popular) {
                if (selected.size() >= topK) break;
                selected.add(name);
            }
        }

        // 如果还不够，从该领域按注册顺序补全
        if (selected.size() < topK) {
            for (ToolMeta meta : candidates) {
                if (selected.size() >= topK) break;
                selected.add(meta.getName());
            }
        }

        List<ToolMeta> result = selected.stream()
                .map(toolRegistry::getTool)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        log.debug("工具召回: domain={}, query={}, candidates={}, selected={}",
                domain, truncate(userQuery, 30), candidates.size(), result.size());
        return result;
    }

    /**
     * 构建用于 Embedding 的文本：名字 + description + capabilities。
     */
    private String buildEmbeddingText(ToolMeta meta) {
        StringBuilder sb = new StringBuilder();
        sb.append(meta.getName()).append(": ").append(meta.getDescription());
        if (meta.getCapabilities() != null && !meta.getCapabilities().isEmpty()) {
            sb.append(". Capabilities: ").append(String.join(", ", meta.getCapabilities()));
        }
        return sb.toString();
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return null;
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}
```

- [ ] **Step 2: 创建 FrequencyTracker（ToolRetriever 依赖）**

```java
package com.deepseek.demo.service;

import com.deepseek.demo.annotation.ToolDomain;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 工具调用频率追踪器。
 * 用于 ToolRetriever 在语义检索不足时补充高频工具。
 */
@Component
public class FrequencyTracker {

    private static class ToolFreq {
        int count = 0;
        long lastUsed = System.currentTimeMillis();
    }

    private final Map<String, ToolFreq> freqs = new ConcurrentHashMap<>();

    /** 记录一次工具调用 */
    public void recordCall(String toolName) {
        freqs.computeIfAbsent(toolName, k -> new ToolFreq());
        ToolFreq f = freqs.get(toolName);
        f.count++;
        f.lastUsed = System.currentTimeMillis();
    }

    /**
     * 获取指定领域中最频繁使用的 N 个工具。
     * 按 "调用次数 × 时间衰减" 排序。
     */
    public List<String> getMostUsed(ToolDomain domain, int limit) {
        long now = System.currentTimeMillis();
        return freqs.entrySet().stream()
                .map(e -> {
                    ToolFreq f = e.getValue();
                    double daysSinceUse = (now - f.lastUsed) / 86400000.0;
                    double score = f.count * Math.exp(-daysSinceUse * 0.1);
                    return new AbstractMap.SimpleEntry<>(e.getKey(), score);
                })
                .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                .limit(limit)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
    }

    /** 获取总调用次数（用于测试和监控） */
    public int getTotalCalls() {
        return freqs.values().stream().mapToInt(f -> f.count).sum();
    }

    /** 重置所有记录 */
    public void reset() {
        freqs.clear();
    }
}
```

- [ ] **Step 3: 写 ToolRetriever 测试**

```java
package com.deepseek.demo.service;

import com.deepseek.demo.annotation.ToolDomain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ToolRetrieverTest {

    @Mock
    private ToolRegistry toolRegistry;
    @Mock
    private ToolVectorStore vectorStore;
    @Mock
    private VectorService vectorService;

    private FrequencyTracker frequencyTracker;
    private ToolRetriever retriever;

    @BeforeEach
    void setUp() {
        frequencyTracker = new FrequencyTracker();
        retriever = new ToolRetriever(toolRegistry, vectorStore,
                vectorService, frequencyTracker);
    }

    @Test
    void shouldReturnEmptyWhenNoToolsInDomain() {
        when(toolRegistry.getByDomain(ToolDomain.TASK_MANAGEMENT))
                .thenReturn(Collections.emptyList());

        List<ToolMeta> result = retriever.retrieve(
                "查任务", ToolDomain.TASK_MANAGEMENT, 5);

        assertTrue(result.isEmpty());
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn test -Dtest=ToolRetrieverTest -q`
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/deepseek/demo/service/ToolRetriever.java \
       src/main/java/com/deepseek/demo/service/FrequencyTracker.java \
       src/test/java/com/deepseek/demo/service/ToolRetrieverTest.java
git commit -m "feat: add ToolRetriever with semantic search and frequency-based tool recall"
```

---

### Task 8: 创建 CapabilityGuard — Layer 3 执行前校验

**Files:**
- Create: `src/main/java/com/deepseek/demo/service/CapabilityGuard.java`

- [ ] **Step 1: 写测试**

```java
package com.deepseek.demo.service;

import com.deepseek.demo.annotation.ActionType;
import com.deepseek.demo.annotation.ToolDomain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class CapabilityGuardTest {

    private CapabilityGuard guard;
    private ToolMeta taskQueryTool;

    @BeforeEach
    void setUp() throws NoSuchMethodException {
        guard = new CapabilityGuard();
        taskQueryTool = new ToolMeta(
                "jira_query_task", "查询 Jira 任务",
                List.of(), List.of(), ActionType.READ,
                ToolDomain.TASK_MANAGEMENT,
                List.of("task:read", "task:search"),
                null, Object.class.getMethod("toString")
        );
    }

    @Test
    void shouldPassWhenCapabilityMatches() {
        CapabilityGuard.Result result = guard.validate(
                "帮我查一下张三的工单",
                taskQueryTool);

        assertTrue(result.isPassed());
    }

    @Test
    void shouldRejectWhenCapabilityMismatches() {
        // 创建一个部署工具（能力不匹配查询意图）
        ToolMeta deployTool = new ToolMeta(
                "jenkins_deploy", "部署到测试环境",
                List.of(), List.of(), ActionType.WRITE,
                ToolDomain.CI_CD,
                List.of("deploy:execute"),
                null, Object.class.getMethod("toString")
        );

        CapabilityGuard.Result result = guard.validate(
                "帮我查一下张三的工单",
                deployTool);

        assertFalse(result.isPassed());
        assertNotNull(result.getReason());
    }

    @Test
    void shouldPassWhenNoCapabilitiesDeclared() {
        ToolMeta noCapTool = new ToolMeta(
                "simple_tool", "简单工具",
                List.of(), List.of(), ActionType.READ,
                ToolDomain.SEARCH,
                List.of(), // 无能力声明
                null, Object.class.getMethod("toString")
        );

        // 没有声明能力时不拦截（兼容旧工具）
        CapabilityGuard.Result result = guard.validate("随便查查", noCapTool);
        assertTrue(result.isPassed());
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn test -Dtest=CapabilityGuardTest -q`
Expected: BUILD FAILURE

- [ ] **Step 3: 实现 CapabilityGuard**

```java
package com.deepseek.demo.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Layer 3: 能力校验守卫。
 * <p>
 * LLM 选择了工具后，在真正执行前校验该工具的能力
 * 是否与用户意图匹配。不匹配时拒绝执行并给出原因，
 * 由 AgentService 将原因注入下一轮 LLM reasoning。
 * <p>
 * 能力校验基于关键词匹配——从用户 query 中提取操作关键词，
 * 与工具声明的 capabilities 做交叉验证。
 */
@Component
public class CapabilityGuard {

    private static final Logger log = LoggerFactory.getLogger(CapabilityGuard.class);

    /**
     * 校验工具能力是否匹配用户意图。
     *
     * @param userMessage 原始用户消息
     * @param tool        被选中的工具
     * @return 校验结果
     */
    public Result validate(String userMessage, ToolMeta tool) {
        // 1. 如果工具没有声明能力，不拦截（兼容旧工具）
        if (tool.getCapabilities() == null || tool.getCapabilities().isEmpty()) {
            return Result.pass();
        }

        // 2. 从工具能力倒推适用的操作关键词
        Set<String> toolActionWords = CapabilityKeywords.extract(tool.getCapabilities());

        if (toolActionWords.isEmpty()) {
            return Result.pass();
        }

        // 3. 从用户 query 中提取操作关键词
        Set<String> queryWords = CapabilityKeywords.extract(userMessage);

        // 4. 交叉匹配
        boolean matched = toolActionWords.stream().anyMatch(queryWords::contains);

        if (!matched) {
            String reason = String.format(
                    "工具 [%s] 的能力 (%s) 与用户请求 \"%s\" 不匹配，可能选错了工具",
                    tool.getName(), tool.getCapabilities(), truncate(userMessage, 50));
            log.warn(reason);
            return Result.reject(reason);
        }

        return Result.pass();
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return null;
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }

    /**
     * 校验结果。
     */
    public static class Result {
        private final boolean passed;
        private final String reason;

        private Result(boolean passed, String reason) {
            this.passed = passed;
            this.reason = reason;
        }

        public static Result pass() {
            return new Result(true, null);
        }

        public static Result reject(String reason) {
            return new Result(false, reason);
        }

        public boolean isPassed() { return passed; }
        public String getReason() { return reason; }
    }
}
```

```java
package com.deepseek.demo.service;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 能力关键词工具类。
 * 将 tool capabilities（如 "task:read"）和用户 query 统一映射到操作关键词集合。
 */
class CapabilityKeywords {

    private static final Set<String> STOP_WORDS = Set.of(
            "的", "了", "在", "是", "我", "有", "和", "就", "不", "人", "都", "一",
            "一个", "上", "也", "很", "到", "说", "要", "去", "你", "会", "着",
            "没有", "看", "好", "自己", "这", "他", "她", "它", "们"
    );

    /** capability 格式转关键词：如 "task:read" → {任务, 查询} */
    private static final java.util.Map<String, Set<String>> CAPABILITY_KEYWORD_MAP = java.util.Map.of(
        "task:read", Set.of("任务", "工单", "查询", "查看", "搜索", "待办"),
        "task:write", Set.of("创建", "新建", "任务", "工单", "修改", "更新", "删除"),
        "task:search", Set.of("搜索", "查询", "查找", "过滤", "筛选"),
        "code:read", Set.of("代码", "仓库", "分支", "查看", "读取"),
        "code:write", Set.of("提交", "推送", "代码", "仓库", "分支", "合并"),
        "deploy:execute", Set.of("部署", "发布", "上线", "更新"),
        "notify:send", Set.of("通知", "发送", "消息", "提醒"),
        "search:fulltext", Set.of("搜索", "查找", "检索", "查询"),
        "doc:read", Set.of("文档", "知识库", "查看", "搜索"),
        "user:read", Set.of("用户", "成员", "组织", "查看", "查询"),
        "user:write", Set.of("创建", "添加", "用户", "成员", "权限")
    );

    /**
     * 从工具的 capabilities 列表中提取操作关键词。
     */
    static Set<String> extractFromCapabilities(Set<String> capabilities) {
        return capabilities.stream()
                .map(cap -> CAPABILITY_KEYWORD_MAP.getOrDefault(cap, Set.of(cap)))
                .flatMap(Set::stream)
                .collect(Collectors.toSet());
    }

    /**
     * 从 capabilities 提取关键词。
     */
    static Set<String> extract(String[] capabilities) {
        return extractFromCapabilities(new HashSet<>(Arrays.asList(capabilities)));
    }

    /**
     * 从用户 query 文本中提取操作关键词。
     */
    static Set<String> extract(String userMessage) {
        if (userMessage == null || userMessage.isBlank()) {
            return Set.of();
        }

        // 简单分词：按非中文字符切分，保留中文词
        return Arrays.stream(userMessage.split("[\\s,，。.!！？?、；;：:（）()【】\\[\\]{}]+"))
                .filter(w -> w.length() >= 2)
                .filter(w -> !STOP_WORDS.contains(w))
                .collect(Collectors.toSet());
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn test -Dtest=CapabilityGuardTest -q`
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/deepseek/demo/service/CapabilityGuard.java \
       src/test/java/com/deepseek/demo/service/CapabilityGuardTest.java
git commit -m "feat: add CapabilityGuard for pre-execution tool validation"
```

---

### Task 9: 集成到 AgentService

**Files:**
- Modify: `src/main/java/com/deepseek/demo/service/AgentService.java`

- [ ] **Step 1: 注入新组件**

```java
// AgentService 新增依赖
private final DomainRouter domainRouter;
private final ToolRetriever toolRetriever;
private final CapabilityGuard capabilityGuard;
```

Update constructor signature and assignments.

- [ ] **Step 2: 修改 chat() 方法——集成 Layer 1 + Layer 2**

```java
public AgentResponse chat(String conversationId, String userMessage) {
    log.info("Agent 对话开始: conversationId={}, message={}",
            conversationId, truncate(userMessage, 50));

    try {
        // 1. 检索知识库
        String knowledgeContext = retrieveKnowledge(userMessage);

        // 2. Layer 1: 意图分类 → 确定领域
        ToolDomain domain = domainRouter.classify(userMessage);
        log.info("意图分类结果: domain={}", domain);

        // 3. Layer 2: 工具召回 → 只携带相关工具
        List<ToolMeta> selectedTools = toolRetriever.retrieve(userMessage, domain, 5);
        log.info("工具召回结果: count={}, tools={}",
                selectedTools.size(),
                selectedTools.stream().map(ToolMeta::getName).collect(Collectors.toList()));

        // 4. 获取或创建消息历史
        List<Message> messages = conversationStore.getMessages(conversationId);
        if (messages.isEmpty()) {
            messages.add(new Message("system", buildSystemPrompt(knowledgeContext)));
            messages.add(new Message("user", userMessage));
        }

        // 5. 进入 ReAct 循环（传入选定的工具 schemas）
        List<Map<String, Object>> toolSchemas = toolRegistry.toJsonSchema(selectedTools);
        return agentLoop(conversationId, messages, toolSchemas);

    } catch (Exception e) {
        log.error("Agent 对话异常", e);
        return AgentResponse.error("处理失败: " + e.getMessage());
    }
}
```

- [ ] **Step 3: 修改 agentLoop()——集成 Layer 3 能力校验**

Add a new overloaded `agentLoop` that takes `List<Map<String, Object>> toolSchemas`:

```java
private AgentResponse agentLoop(String conversationId, List<Message> messages,
                                 List<Map<String, Object>> toolSchemas) {
    boolean planConfirmed = conversationStore.getPlanConfirmed(conversationId);

    for (int i = 0; i < MAX_ITERATIONS; i++) {
        conversationStore.saveCheckpoint(conversationId, messages, i);

        DeepSeekChatResponse response;
        try {
            response = deepSeekService.chatWithTools(messages, toolSchemas);
        } catch (Exception e) {
            log.error("DeepSeek API 调用失败(第{}轮)", i, e);
            return AgentResponse.error("服务暂时不可用，请稍后再试");
        }

        if (response == null || response.getChoices() == null
                || response.getChoices().isEmpty()) {
            log.error("DeepSeek API 返回空响应(第{}轮)", i);
            return AgentResponse.error("模型返回异常，请重试");
        }

        Message responseMessage = response.getChoices().get(0).getMessage();
        List<ToolCall> toolCalls = response.getChoices().get(0).getToolCalls();
        messages.add(responseMessage);

        if (toolCalls == null || toolCalls.isEmpty()) {
            conversationStore.clearCheckpoint(conversationId);
            String content = responseMessage.getContent();
            return AgentResponse.done(content != null ? content : "");
        }

        if (!planConfirmed) {
            return createPlanConfirmation(conversationId, messages, toolCalls);
        }

        // ——已确认计划，逐个执行——

        // 记录这次循环是带什么 tools 的（用于下一轮重新召回）
        List<ToolMeta> currentTools = toolRegistry.getAllTools().values().stream()
                .filter(t -> toolSchemas.stream()
                        .anyMatch(s -> t.getName().equals(s.get("function", Map.class)
                                .map(f -> (String) f.get("name")).orElse(""))))
                .collect(Collectors.toList());

        for (int t = 0; t < toolCalls.size(); t++) {
            ToolCall tc = toolCalls.get(t);
            String toolName = tc.getFunction().getName();

            // Layer 3: CapabilityGuard 校验
            ToolMeta meta = toolRegistry.getTool(toolName);
            if (meta == null) {
                log.warn("工具不存在，跳过: tool={}", toolName);
                messages.add(new Message("system", "工具 \"" + toolName + "\" 不存在，已跳过。"));
                // 记录频率
                continue;
            }

            // 能力校验
            CapabilityGuard.Result guardResult = capabilityGuard.validate(
                    messages.stream()
                            .filter(m -> "user".equals(m.getRole()))
                            .map(Message::getContent)
                            .reduce((first, second) -> second) // 取最后一条 user 消息
                            .orElse(""),
                    meta);

            if (!guardResult.isPassed()) {
                log.warn("能力校验不通过: tool={}, reason={}", toolName, guardResult.getReason());
                messages.add(new Message("system",
                        "你选择的工具 [" + toolName + "] 可能不适用于当前请求。\n"
                        + "请从以下可用工具中重新选择：\n"
                        + currentTools.stream().map(ToolMeta::getName)
                                .collect(Collectors.joining(", "))));
                continue;
            }

            // 记录调用频率
            // Note: frequencyTracker is now a dependency of AgentService
            // We need to record the call here or through ToolRetriever

            // 其余执行逻辑不变（检查已批准计划、ACTION 类型、确认/执行...）
            // ... 复用原有代码 ...
        }
    }

    conversationStore.clearCheckpoint(conversationId);
    return AgentResponse.done("任务未完全执行，已达最大处理轮次。请尝试简化操作需求。");
}
```

> 注意：由于 `agentLoop` 新增了参数，原有的 `agentLoop(conversationId, messages)` 调用处（如 `handlePlanConfirm`、`handleExecConfirm`）也需要同步修改为 `agentLoop(conversationId, messages, toolSchemas)`。建议将 toolSchemas 作为会话状态的一部分存储（在 ConversationStore 中），或在确认回调中重新召回一次。

为简化，最干净的方案是将 selectedTools 保存到 ConversationStore，确认回调时恢复：

```java
// ConversationStore 新增
public void setSelectedTools(String conversationId, List<ToolMeta> tools) { ... }
public List<ToolMeta> getSelectedTools(String conversationId) { ... }
```

然后在 `confirm()` → `handlePlanConfirm()` / `handleExecConfirm()` 中恢复并传给 `agentLoop`。

- [ ] **Step 4: 编译验证**

Run: `mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 5: 更新测试**

更新 `AgentServiceTest.java`，验证 DomainRouter/ToolRetriever/CapabilityGuard 的 mock 注入和交互。

- [ ] **Step 6: 运行全部测试**

Run: `mvn test -q`
Expected: BUILD SUCCESS

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/deepseek/demo/service/AgentService.java \
       src/test/java/com/deepseek/demo/service/AgentServiceTest.java
git commit -m "feat: integrate three-layer routing into AgentService"
```

---

### Task 10: 更新示例工具的注解

**Files:**
- Modify: `src/main/java/com/deepseek/demo/service/tools/TaskTools.java`
- Modify: `src/main/java/com/deepseek/demo/service/tools/ExternalTools.java`

- [ ] **Step 1: 修改 TaskTools 注解**

```java
@Tool(name = "query_task",
      description = "查询任务列表，可按负责人和状态筛选",
      domain = ToolDomain.TASK_MANAGEMENT,
      capabilities = {"task:read", "task:search"},
      parameters = {
          @ToolParam(name = "assignee", type = "string",
                     description = "负责人姓名", required = true),
          @ToolParam(name = "status", type = "string",
                     description = "筛选状态：待办/进行中/已完成")
      },
      action = ActionType.READ)
```

```java
@Tool(name = "create_task",
      description = "创建新任务，需要指定任务标题",
      domain = ToolDomain.TASK_MANAGEMENT,
      capabilities = {"task:write"},
      parameters = {
          @ToolParam(name = "title", ...),
          @ToolParam(name = "assignee", ...),
          @ToolParam(name = "due_date", ...)
      },
      action = ActionType.WRITE)
```

- [ ] **Step 2: 修改 ExternalTools 注解**

```java
@Tool(name = "feishu_send_message",
      description = "通过 Webhook 发送飞书消息通知",
      domain = ToolDomain.NOTIFICATION,
      capabilities = {"notify:send"},
      ...
      action = ActionType.WRITE)
```

- [ ] **Step 3: 编译验证**

Run: `mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 4: 运行全部测试**

Run: `mvn test -q`
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/deepseek/demo/service/tools/
git commit -m "feat: add domain and capabilities to example tools"
```

---

### Task 11: 更新架构文档

**Files:**
- Modify: `AIDev-architecture.md`
- Modify: `docs/superpowers/specs/2026-05-13-llm-tool-calling-design.md`

- [ ] **Step 1: 更新 AIDev-architecture.md**

在核心组件部分新增 DomainRouter、ToolRetriever、CapabilityGuard 的描述。

- [ ] **Step 2: 更新设计文档**

在 `docs/superpowers/specs/` 的设计文档中新增三层路由架构节的说明。

- [ ] **Step 3: Commit**

```bash
git add AIDev-architecture.md docs/superpowers/specs/
git commit -m "docs: add three-layer API routing architecture documentation"
```
