# LLM Function Calling + ReAct Agent Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Enable LLM to proactively call APIs (internal/external) through function calling and ReAct loop, with human-in-the-loop confirmation.

**Architecture:** `@Tool` annotation defines callable APIs → `ToolRegistry` scans and generates JSON schema for DeepSeek → `AgentService` orchestrates ReAct loop (plan confirm → execute with secondary write confirm) → `ConversationStore`/`ConfirmationStore` persist context across confirmation boundaries.

**Tech Stack:** Java 11, Spring Boot 2.7.9, DeepSeek API (function calling), Jackson, Redis (stores)

---

## File Structure

### New Files
| File | Responsibility |
|------|----------------|
| `annotation/Tool.java` | `@Tool` annotation declaring API metadata |
| `annotation/ToolParam.java` | `@ToolParam` for parameter schema |
| `annotation/ActionType.java` | Enum: READ / WRITE |
| `dto/ToolCall.java` | ToolCall DTO matching DeepSeek format |
| `dto/FunctionCall.java` | FunctionCall DTO (name + arguments) |
| `dto/AgentResponse.java` | Unified response wrapper |
| `dto/ConfirmationPoint.java` | Confirmation data model |
| `service/ToolRegistry.java` | Scan, register, execute tools |
| `service/AgentService.java` | ReAct engine core |
| `store/ConversationStore.java` | In-memory conversation context store |
| `store/ConfirmationStore.java` | In-memory confirmation point store |
| `controller/AgentController.java` | `/api/agent/*` endpoints |

### Modified Files
| File | Changes |
|------|---------|
| `dto/Message.java` | Add toolCalls, toolCallId, name fields |
| `dto/DeepSeekChatRequest.java` | Add tools field |
| `dto/DeepSeekChatResponse.java` | Add tool_calls to Choice |
| `service/DeepSeekService.java` | Add chatWithTools method |
| `resources/application.yml` | Add tool.whitelist |

---

### Task 1: Annotation layer — @Tool, @ToolParam, ActionType

**Files:**
- Create: `src/main/java/com/deepseek/demo/annotation/ActionType.java`
- Create: `src/main/java/com/deepseek/demo/annotation/ToolParam.java`
- Create: `src/main/java/com/deepseek/demo/annotation/Tool.java`

- [ ] **Step 1: Create ActionType enum**

```java
package com.deepseek.demo.annotation;

/**
 * 操作类型枚举，用于标记 API 的风险等级。
 * <ul>
 *   <li>READ：读操作，自动执行，无需额外确认</li>
 *   <li>WRITE：写操作，非白名单的需要二次确认</li>
 * </ul>
 */
public enum ActionType {
    READ,
    WRITE
}
```

- [ ] **Step 2: Create @ToolParam annotation**

```java
package com.deepseek.demo.annotation;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * 工具参数的元数据注解，用于描述 {@link Tool} 的每个参数。
 * 每个实例对应 JSON Schema 中的一个 property。
 */
@Retention(RetentionPolicy.RUNTIME)
public @interface ToolParam {

    /** 参数名称，作为 JSON Schema property 的 key */
    String name();

    /** 参数类型，支持 "string"、"number"、"boolean" */
    String type() default "string";

    /** 参数描述，帮助 LLM 理解参数的用途 */
    String description();

    /** 是否为必需参数 */
    boolean required() default false;
}
```

- [ ] **Step 3: Create @Tool annotation**

```java
package com.deepseek.demo.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记一个方法为 LLM 可调用的工具。
 * 被标注的方法会被 {@link com.deepseek.demo.service.ToolRegistry}
 * 扫描并注册为 DeepSeek function calling 的可用工具。
 * <p>
 * 方法参数必须与 {@link #parameters()} 中的定义一一对应，
 * 且方法签名应使用 String 类型接收所有参数（由 Jackson 反序列化后传入）。
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
}
```

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/deepseek/demo/annotation/
git commit -m "feat: add @Tool, @ToolParam annotations and ActionType enum"
```

---

### Task 2: Update Message DTO

**Files:**
- Modify: `src/main/java/com/deepseek/demo/dto/Message.java`
- Create: `src/main/java/com/deepseek/demo/dto/ToolCall.java`
- Create: `src/main/java/com/deepseek/demo/dto/FunctionCall.java`

- [ ] **Step 1: Create ToolCall DTO**

```java
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
```

- [ ] **Step 2: Create FunctionCall DTO**

```java
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
```

- [ ] **Step 3: Modify Message DTO — add tool_calls, toolCallId, name**

```java
package com.deepseek.demo.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * 对话消息体，表示 system/user/assistant/tool 角色的消息。
 * <p>
 * 扩展支持 DeepSeek function calling：
 * <ul>
 *   <li>assistant 角色的消息可携带 {@link #toolCalls} 表示 LLM 要调用的工具</li>
 *   <li>tool 角色的消息需设置 {@link #toolCallId} 关联到对应的 tool_call</li>
 * </ul>
 */
public class Message {

    /** 角色：system / user / assistant / tool */
    private String role;

    /** 消息文本内容，tool_calls 时可为 null */
    private String content;

    /**
     * assistant 角色专用：LLM 发起的工具调用列表。
     * 当 LLM 决定调用函数时，此字段非空，content 可能为 null。
     */
    @JsonProperty("tool_calls")
    private List<ToolCall> toolCalls;

    /**
     * tool 角色专用：对应 tool_call 的 ID。
     * 用于将工具执行结果关联回原始的 tool_call。
     */
    @JsonProperty("tool_call_id")
    private String toolCallId;

    /**
     * tool 角色专用：工具名称。
     */
    private String name;

    public Message() {}

    public Message(String role, String content) {
        this.role = role;
        this.content = content;
    }

    /**
     * 构造 tool role 消息。
     *
     * @param role       固定为 "tool"
     * @param content    工具执行结果
     * @param toolCallId 关联的 tool_call ID
     */
    public Message(String role, String content, String toolCallId) {
        this.role = role;
        this.content = content;
        this.toolCallId = toolCallId;
    }

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public List<ToolCall> getToolCalls() { return toolCalls; }
    public void setToolCalls(List<ToolCall> toolCalls) { this.toolCalls = toolCalls; }
    public String getToolCallId() { return toolCallId; }
    public void setToolCallId(String toolCallId) { this.toolCallId = toolCallId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
}
```

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/deepseek/demo/dto/ToolCall.java \
        src/main/java/com/deepseek/demo/dto/FunctionCall.java \
        src/main/java/com/deepseek/demo/dto/Message.java
git commit -m "feat: add ToolCall, FunctionCall DTOs and extend Message for function calling"
```

---

### Task 3: Update DeepSeek API DTOs — request and response

**Files:**
- Modify: `src/main/java/com/deepseek/demo/dto/DeepSeekChatRequest.java`
- Modify: `src/main/java/com/deepseek/demo/dto/DeepSeekChatResponse.java`

- [ ] **Step 1: Add tools field to DeepSeekChatRequest**

Add to `DeepSeekChatRequest.java`:

```java
    /**
     * DeepSeek function calling 的工具定义列表。
     * 每个元素描述一个可用函数的名称、参数 JSON Schema。
     */
    private List<Map<String, Object>> tools;

    /**
     * 工具调用策略：
     * "auto"（默认）— LLM 自行决定是否调用
     * "none" — 禁止调用
     * "required" — 强制调用
     * {"type":"function","function":{"name":"xxx"}} — 指定特定函数
     */
    @JsonProperty("tool_choice")
    private Object toolChoice = "auto";
```

Also add getter/setter and import `java.util.Map`.

- [ ] **Step 2: Add tool_calls to Choice in DeepSeekChatResponse**

Add to the inner `Choice` class in `DeepSeekChatResponse.java`:

```java
        /**
         * LLM 发起的工具调用列表。
         * 当模型决定调用函数时，此字段非空，message.content 可能为 null。
         */
        @JsonProperty("tool_calls")
        private List<ToolCall> toolCalls;
```

Add getter/setter and import `java.util.List` and the `ToolCall` class.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/deepseek/demo/dto/DeepSeekChatRequest.java \
        src/main/java/com/deepseek/demo/dto/DeepSeekChatResponse.java
git commit -m "feat: add tools/tool_choice to request and tool_calls to response"
```

---

### Task 4: Create AgentResponse and ConfirmationPoint DTOs

**Files:**
- Create: `src/main/java/com/deepseek/demo/dto/AgentResponse.java`
- Create: `src/main/java/com/deepseek/demo/dto/ConfirmationPoint.java`

- [ ] **Step 1: Create AgentResponse**

```java
package com.deepseek.demo.dto;

import java.util.List;
import java.util.Map;

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

    // getters and setters
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getReply() { return reply; }
    public void setReply(String reply) { this.reply = reply; }
    public ConfirmationPoint getConfirmationPoint() { return confirmationPoint; }
    public void setConfirmationPoint(ConfirmationPoint confirmationPoint) { this.confirmationPoint = confirmationPoint; }
}
```

- [ ] **Step 2: Create ConfirmationPoint**

```java
package com.deepseek.demo.dto;

import java.util.List;
import java.util.Map;

/**
 * 确认点，表示需要用户确认的操作。
 * <p>
 * 两种类型：
 * <ul>
 *   <li>plan：操作计划确认，包含 LLM 建议的完整操作列表</li>
 *   <li>exec：写操作二次确认，包含单个工具的名称和参数</li>
 * </ul>
 */
public class ConfirmationPoint {

    /** 确认点唯一标识（UUID） */
    private String confirmationId;

    /** 对话会话 ID */
    private String conversationId;

    /** 确认点类型：plan / exec */
    private String type;

    /** LLM 的推理说明（plan 类型），描述为什么要执行这些操作 */
    private String reasoning;

    /** plan 类型：LLM 提议的完整操作列表 */
    private List<Map<String, Object>> plan;

    /** exec 类型：要执行的工具名称 */
    private String tool;

    /** exec 类型：工具参数 */
    private Map<String, Object> args;

    /** plan 类型：操作数量汇总 */
    private String summary;

    /** 创建时间戳 */
    private long createdAt;

    public ConfirmationPoint() {}

    // getters and setters
    public String getConfirmationId() { return confirmationId; }
    public void setConfirmationId(String confirmationId) { this.confirmationId = confirmationId; }
    public String getConversationId() { return conversationId; }
    public void setConversationId(String conversationId) { this.conversationId = conversationId; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getReasoning() { return reasoning; }
    public void setReasoning(String reasoning) { this.reasoning = reasoning; }
    public List<Map<String, Object>> getPlan() { return plan; }
    public void setPlan(List<Map<String, Object>> plan) { this.plan = plan; }
    public String getTool() { return tool; }
    public void setTool(String tool) { this.tool = tool; }
    public Map<String, Object> getArgs() { return args; }
    public void setArgs(Map<String, Object> args) { this.args = args; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }
}
```

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/deepseek/demo/dto/AgentResponse.java \
        src/main/java/com/deepseek/demo/dto/ConfirmationPoint.java
git commit -m "feat: add AgentResponse and ConfirmationPoint DTOs"
```

---

### Task 5: Implement in-memory stores

**Files:**
- Create: `src/main/java/com/deepseek/demo/store/ConversationStore.java`
- Create: `src/main/java/com/deepseek/demo/store/ConfirmationStore.java`

- [ ] **Step 1: Create ConversationStore**

```java
package com.deepseek.demo.store;

import com.deepseek.demo.dto.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 对话上下文存储（内存实现）。
 * <p>
 * 以 conversationId 为 key 存储完整对话状态，包括：
 * <ul>
 *   <li>消息历史（messages）</li>
 *   <li>操作计划确认状态（planConfirmed）</li>
 *   <li>已批准的操作计划（approvedPlan）</li>
 *   <li>当前 ReAct 轮次（checkpoint）</li>
 * </ul>
 * <p>
 * 每 5 分钟清理超过 30 分钟未活跃的会话。
 */
@Component
public class ConversationStore {

    private static final Logger log = LoggerFactory.getLogger(ConversationStore.class);
    private static final long SESSION_TTL_MS = 30 * 60 * 1000L; // 30 分钟

    /** 存储结构：conversationId → ConversationState */
    private final ConcurrentHashMap<String, ConversationState> store = new ConcurrentHashMap<>();

    /** 定时清理过期会话 */
    private final ScheduledExecutorService cleaner = Executors.newSingleThreadScheduledExecutor();

    public ConversationStore() {
        cleaner.scheduleAtFixedRate(this::cleanup, 5, 5, TimeUnit.MINUTES);
    }

    /**
     * 获取指定会话的消息历史，不存在时返回空列表。
     *
     * @param conversationId 会话 ID
     * @return 消息列表
     */
    public List<Message> getMessages(String conversationId) {
        ConversationState state = store.get(conversationId);
        if (state == null) {
            return new ArrayList<>();
        }
        state.lastAccessTime = System.currentTimeMillis();
        return state.messages;
    }

    /**
     * 保存会话消息历史。
     *
     * @param conversationId 会话 ID
     * @param messages 消息列表
     */
    public void saveMessages(String conversationId, List<Message> messages) {
        ConversationState state = store.computeIfAbsent(conversationId,
                k -> new ConversationState());
        state.messages = new ArrayList<>(messages);
        state.lastAccessTime = System.currentTimeMillis();
    }

    /**
     * 保存 ReAct 循环的 checkpoint。
     *
     * @param conversationId 会话 ID
     * @param messages 当前消息列表
     * @param iteration 当前迭代轮次
     */
    public void saveCheckpoint(String conversationId, List<Message> messages, int iteration) {
        ConversationState state = store.computeIfAbsent(conversationId,
                k -> new ConversationState());
        state.messages = new ArrayList<>(messages);
        state.checkpoint = iteration;
        state.lastAccessTime = System.currentTimeMillis();
    }

    /**
     * 清除 checkpoint 并标记会话为已完成。
     *
     * @param conversationId 会话 ID
     */
    public void clearCheckpoint(String conversationId) {
        ConversationState state = store.get(conversationId);
        if (state != null) {
            state.checkpoint = 0;
            state.lastAccessTime = System.currentTimeMillis();
        }
    }

    /**
     * 获取操作计划是否已确认。
     *
     * @param conversationId 会话 ID
     * @return true 表示已确认
     */
    public boolean getPlanConfirmed(String conversationId) {
        ConversationState state = store.get(conversationId);
        return state != null && state.planConfirmed;
    }

    /**
     * 设置操作计划确认状态。
     *
     * @param conversationId 会话 ID
     * @param confirmed 是否已确认
     */
    public void setPlanConfirmed(String conversationId, boolean confirmed) {
        ConversationState state = store.computeIfAbsent(conversationId,
                k -> new ConversationState());
        state.planConfirmed = confirmed;
        state.lastAccessTime = System.currentTimeMillis();
    }

    /**
     * 获取已批准的操作计划。
     *
     * @param conversationId 会话 ID
     * @return 操作计划列表，可能为 null
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getApprovedPlan(String conversationId) {
        ConversationState state = store.get(conversationId);
        return state != null ? (List<Map<String, Object>>) state.approvedPlan : null;
    }

    /**
     * 设置已批准的操作计划。
     *
     * @param conversationId 会话 ID
     * @param plan 操作计划列表
     */
    public void setApprovedPlan(String conversationId, List<Map<String, Object>> plan) {
        ConversationState state = store.computeIfAbsent(conversationId,
                k -> new ConversationState());
        state.approvedPlan = plan;
        state.lastAccessTime = System.currentTimeMillis();
    }

    /** 定时清理过期会话 */
    private void cleanup() {
        long now = System.currentTimeMillis();
        store.entrySet().removeIf(entry -> {
            boolean expired = (now - entry.getValue().lastAccessTime) > SESSION_TTL_MS;
            if (expired) {
                log.debug("清理过期会话: {}", entry.getKey());
            }
            return expired;
        });
    }

    @PreDestroy
    public void shutdown() {
        cleaner.shutdown();
    }

    /** 内部状态类 */
    private static class ConversationState {
        List<Message> messages = new ArrayList<>();
        int checkpoint = 0;
        boolean planConfirmed = false;
        Object approvedPlan = null; // List<Map<String, Object>>
        long lastAccessTime = System.currentTimeMillis();
    }
}
```

- [ ] **Step 2: Create ConfirmationStore**

```java
package com.deepseek.demo.store;

import com.deepseek.demo.dto.ToolCall;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.annotation.PreDestroy;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 确认点存储（内存实现）。
 * <p>
 * 以 confirmationId 为 key 存储待确认的操作信息。
 * 确认点 TTL 为 5 分钟，超时自动清理。
 */
@Component
public class ConfirmationStore {

    private static final Logger log = LoggerFactory.getLogger(ConfirmationStore.class);
    private static final long CONFIRM_TTL_MS = 5 * 60 * 1000L; // 5 分钟

    /** 存储结构：confirmationId → ConfirmationState */
    private final ConcurrentHashMap<String, ConfirmationState> store = new ConcurrentHashMap<>();

    /** 定时清理过期确认点 */
    private final ScheduledExecutorService cleaner = Executors.newSingleThreadScheduledExecutor();

    public ConfirmationStore() {
        cleaner.scheduleAtFixedRate(this::cleanup, 1, 1, TimeUnit.MINUTES);
    }

    /**
     * 创建 plan 类型确认点。
     *
     * @param conversationId 会话 ID
     * @param plan LLM 提议的完整操作列表
     * @return 确认点 ID
     */
    public String createPlanConfirmation(String conversationId,
                                          List<Map<String, Object>> plan) {
        String id = UUID.randomUUID().toString();
        ConfirmationState state = new ConfirmationState();
        state.conversationId = conversationId;
        state.type = "plan";
        state.planToolCalls = plan;
        state.createdAt = System.currentTimeMillis();
        store.put(id, state);
        log.debug("创建 plan 确认点: id={}, conv={}, planSize={}",
                id, conversationId, plan.size());
        return id;
    }

    /**
     * 创建 exec 类型确认点。
     *
     * @param conversationId 会话 ID
     * @param toolCall 待确认的 tool_call
     * @param pendingToolCalls 同一响应中剩余未执行的 tool_calls
     * @return 确认点 ID
     */
    public String createExecConfirmation(String conversationId,
                                          ToolCall toolCall,
                                          List<ToolCall> pendingToolCalls) {
        String id = UUID.randomUUID().toString();
        ConfirmationState state = new ConfirmationState();
        state.conversationId = conversationId;
        state.type = "exec";
        state.toolName = toolCall.getFunction().getName();
        state.toolCallId = toolCall.getId();
        state.toolArguments = toolCall.getFunction().getArguments();
        state.pendingToolCalls = pendingToolCalls;
        state.createdAt = System.currentTimeMillis();
        store.put(id, state);
        log.debug("创建 exec 确认点: id={}, conv={}, tool={}",
                id, conversationId, state.toolName);
        return id;
    }

    /**
     * 获取确认点状态。
     *
     * @param confirmationId 确认点 ID
     * @return 确认点状态，不存在或已过期返回 null
     */
    public ConfirmationState get(String confirmationId) {
        ConfirmationState state = store.get(confirmationId);
        if (state == null) return null;
        if (isExpired(state)) {
            store.remove(confirmationId);
            return null;
        }
        return state;
    }

    /**
     * 消费确认点（标记为已处理）。
     *
     * @param confirmationId 确认点 ID
     */
    public void consume(String confirmationId) {
        ConfirmationState state = store.get(confirmationId);
        if (state != null) {
            state.consumed = true;
        }
    }

    /**
     * 判断确认点是否已消费。
     *
     * @param confirmationId 确认点 ID
     * @return true 表示已消费
     */
    public boolean isConsumed(String confirmationId) {
        ConfirmationState state = store.get(confirmationId);
        return state != null && state.consumed;
    }

    private boolean isExpired(ConfirmationState state) {
        return (System.currentTimeMillis() - state.createdAt) > CONFIRM_TTL_MS;
    }

    private void cleanup() {
        long now = System.currentTimeMillis();
        store.entrySet().removeIf(entry -> {
            boolean expired = (now - entry.getValue().createdAt) > CONFIRM_TTL_MS;
            if (expired) {
                log.debug("清理过期确认点: {}", entry.getKey());
            }
            return expired;
        });
    }

    @PreDestroy
    public void shutdown() {
        cleaner.shutdown();
    }

    /** 确认点状态 */
    public static class ConfirmationState {
        /** 所属会话 ID */
        private String conversationId;
        /** 确认点类型：plan / exec */
        private String type;
        /** plan 类型：完整操作计划 */
        private List<Map<String, Object>> planToolCalls;
        /** exec 类型：工具名称 */
        private String toolName;
        /** exec 类型：tool_call ID */
        private String toolCallId;
        /** exec 类型：tool_call 参数字符串（JSON），跨确认边界保留参数 */
        private String toolArguments;
        /** exec 类型：剩余未执行的 tool_calls */
        private List<ToolCall> pendingToolCalls;
        /** 创建时间 */
        private long createdAt;
        /** 是否已消费 */
        private boolean consumed = false;

        // getters
        public String getConversationId() { return conversationId; }
        public String getType() { return type; }
        public List<Map<String, Object>> getPlanToolCalls() { return planToolCalls; }
        public String getToolName() { return toolName; }
        public String getToolCallId() { return toolCallId; }
        public String getToolArguments() { return toolArguments; }
        public List<ToolCall> getPendingToolCalls() { return pendingToolCalls; }
        public long getCreatedAt() { return createdAt; }
        public boolean isConsumed() { return consumed; }
    }
}
```

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/deepseek/demo/store/
git commit -m "feat: add in-memory ConversationStore and ConfirmationStore"
```

---

### Task 6: Add chatWithTools to DeepSeekService

**Files:**
- Modify: `src/main/java/com/deepseek/demo/service/DeepSeekService.java`

- [ ] **Step 1: Add chatWithTools with retry and error handling**

Add method `chatWithTools(List<Message> messages, List<Map<String, Object>> tools)` with retry logic:

```java
    /**
     * 带 function calling 工具的非流式对话。
     * <p>
     * 在原有对话基础上追加 tools 参数，使 LLM 可以在适当时机调用预定义的函数。
     * 内置重试和错误处理：
     * <ul>
     *   <li>HTTP 429 限流：等待 2s 后重试，最多 2 次</li>
     *   <li>HTTP 401 鉴权：不重试，直接返回错误</li>
     *   <li>网络超时：重试 1 次</li>
     * </ul>
     *
     * @param messages 消息列表（含 system/user/assistant/tool 角色）
     * @param tools    DeepSeek function calling 的工具定义 JSON Schema 列表
     * @return DeepSeek API 原始响应（含 tool_calls）
     * @throws RuntimeException 所有重试失败后抛出
     */
    public DeepSeekChatResponse chatWithTools(List<Message> messages,
                                               List<Map<String, Object>> tools) {
        String url = baseUrl + "/v1/chat/completions";

        DeepSeekChatRequest request = new DeepSeekChatRequest(messages);
        request.setTools(tools);
        request.setToolChoice("auto");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);

        HttpEntity<DeepSeekChatRequest> entity = new HttpEntity<>(request, headers);

        // 最大重试次数
        int maxAttempts = 2;
        int attempt = 0;

        while (attempt < maxAttempts) {
            attempt++;
            try {
                log.debug("调用 DeepSeek API(带 tools): url={}, toolsCount={}, attempt={}/{}",
                        url, tools != null ? tools.size() : 0, attempt, maxAttempts);

                long start = System.currentTimeMillis();
                ResponseEntity<DeepSeekChatResponse> response = restTemplate.postForEntity(
                        url, entity, DeepSeekChatResponse.class);
                long elapsed = System.currentTimeMillis() - start;

                DeepSeekChatResponse body = response.getBody();
                boolean hasToolCalls = body != null && body.getChoices() != null
                        && !body.getChoices().isEmpty()
                        && body.getChoices().get(0).getToolCalls() != null;

                log.info("DeepSeek API(带 tools)响应: status={}, 耗时={}ms, hasToolCalls={}",
                        response.getStatusCode(), elapsed, hasToolCalls);

                return body;

            } catch (org.springframework.web.client.HttpClientErrorException e) {
                if (e.getRawStatusCode() == 429) {
                    // 限流：等待后重试
                    log.warn("DeepSeek API 限流(429), 等待重试: attempt={}/{}", attempt, maxAttempts);
                    if (attempt < maxAttempts) {
                        try { Thread.sleep(2000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                        continue;
                    }
                    throw new RuntimeException("请求过于频繁，请稍后再试");
                } else if (e.getRawStatusCode() == 401) {
                    // 鉴权失败：不重试
                    log.error("DeepSeek API 鉴权失败(401)");
                    throw new RuntimeException("API 认证失败");
                }
                // 其他 HTTP 错误
                log.error("DeepSeek API HTTP 错误: status={}", e.getRawStatusCode());
                if (attempt < maxAttempts) continue;
                throw new RuntimeException("服务异常");

            } catch (org.springframework.web.client.ResourceAccessException e) {
                // 网络错误（连接超时、DNS 等）
                log.warn("DeepSeek API 网络错误, 重试: attempt={}/{}", attempt, maxAttempts);
                if (attempt < maxAttempts) continue;
                throw new RuntimeException("服务暂时不可用，请稍后再试");
            }
        }

        throw new RuntimeException("服务暂时不可用，请稍后再试");
    }
```

And update `DeepSeekChatRequest` import: replace `java.util.List` with `java.util.List, java.util.Map`.

- [ ] **Step 2: Commit**

```bash
git add src/main/java/com/deepseek/demo/service/DeepSeekService.java
git commit -m "feat: add chatWithTools method supporting function calling"
```

---

### Task 7: Create ToolRegistry

**Files:**
- Create: `src/main/java/com/deepseek/demo/service/ToolRegistry.java`
- Create: `src/main/java/com/deepseek/demo/service/ToolMeta.java`
- Modify: `src/main/resources/application.yml`

- [ ] **Step 1: Create ToolMeta — internal tool metadata model**

```java
package com.deepseek.demo.service;

import com.deepseek.demo.annotation.ActionType;
import java.lang.reflect.Method;

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
    private final java.util.List<java.util.Map<String, Object>> parameters;
    /** 必需参数名称列表 */
    private final java.util.List<String> requiredParams;
    /** 操作类型 */
    private final ActionType action;
    /** Bean 实例引用 */
    private final Object bean;
    /** 方法引用 */
    private final Method method;

    public ToolMeta(String name, String description,
                    java.util.List<java.util.Map<String, Object>> parameters,
                    java.util.List<String> requiredParams,
                    ActionType action, Object bean, Method method) {
        this.name = name;
        this.description = description;
        this.parameters = parameters;
        this.requiredParams = requiredParams;
        this.action = action;
        this.bean = bean;
        this.method = method;
    }

    // getters
    public String getName() { return name; }
    public String getDescription() { return description; }
    public java.util.List<java.util.Map<String, Object>> getParameters() { return parameters; }
    public java.util.List<String> getRequiredParams() { return requiredParams; }
    public ActionType getAction() { return action; }
    public Object getBean() { return bean; }
    public Method getMethod() { return method; }
}
```

- [ ] **Step 2: Create ToolRegistry**

```java
package com.deepseek.demo.service;

import com.deepseek.demo.annotation.ActionType;
import com.deepseek.demo.annotation.Tool;
import com.deepseek.demo.annotation.ToolParam;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 工具注册中心，管理所有被 {@link Tool} 注解标记的可调用工具。
 * <p>
 * 职责：
 * <ul>
 *   <li>启动时扫描 Spring 容器中所有带 @Tool 注解的方法并注册</li>
 *   <li>将注册的工具转换为 DeepSeek function calling 所需的 JSON Schema</li>
 *   <li>根据白名单判断写操作是否需要二次确认</li>
 *   <li>根据工具名称和参数反射调用对应的方法</li>
 * </ul>
 */
@Component
public class ToolRegistry implements InitializingBean, ApplicationContextAware {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);

    private ApplicationContext applicationContext;

    /** 工具名称 → 工具元数据 */
    private final Map<String, ToolMeta> tools = new ConcurrentHashMap<>();

    /** 自动确认的白名单工具名称（从配置文件注入） */
    private final Set<String> whitelist;

    private final ObjectMapper objectMapper;

    /**
     * 构造 ToolRegistry。
     *
     * @param whitelistStr 白名单工具名称列表，逗号分隔，从 ${tool.whitelist} 注入
     * @param objectMapper Jackson ObjectMapper，用于解析 JSON 参数
     */
    public ToolRegistry(@Value("${tool.whitelist:}") String whitelistStr,
                        ObjectMapper objectMapper) {
        this.whitelist = Arrays.stream(whitelistStr.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
        this.objectMapper = objectMapper;
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    /**
     * 初始化时扫描所有 Bean，注册带有 {@link Tool} 注解的方法。
     */
    @Override
    public void afterPropertiesSet() {
        String[] beanNames = applicationContext.getBeanNamesForAnnotation(
                org.springframework.stereotype.Component.class);
        // 也扫描 Service、Controller 等注解
        beanNames = applicationContext.getBeanDefinitionNames();

        for (String beanName : beanNames) {
            Object bean = applicationContext.getBean(beanName);
            Class<?> clazz = bean.getClass();

            for (Method method : clazz.getMethods()) {
                Tool toolAnn = method.getAnnotation(Tool.class);
                if (toolAnn == null) continue;

                String name = toolAnn.name();
                if (tools.containsKey(name)) {
                    log.warn("工具名称重复: {}, 后者将覆盖前者", name);
                }

                // 构建参数 schema
                List<Map<String, Object>> params = new ArrayList<>();
                List<String> requiredParams = new ArrayList<>();
                for (ToolParam param : toolAnn.parameters()) {
                    Map<String, Object> paramSchema = new LinkedHashMap<>();
                    paramSchema.put("name", param.name());
                    paramSchema.put("type", param.type());
                    paramSchema.put("description", param.description());
                    params.add(paramSchema);
                    if (param.required()) {
                        requiredParams.add(param.name());
                    }
                }

                ToolMeta meta = new ToolMeta(name, toolAnn.description(),
                        params, requiredParams, toolAnn.action(), bean, method);
                tools.put(name, meta);
                log.info("注册工具: name={}, action={}, params={}",
                        name, toolAnn.action(), params.size());
            }
        }

        log.info("ToolRegistry 初始化完成: 共注册 {} 个工具, 白名单 {} 个: {}",
                tools.size(), whitelist.size(), whitelist);
    }

    /**
     * 将所有注册的工具转换为 DeepSeek function calling 的 tools JSON 格式。
     *
     * @return tools 列表，格式符合 DeepSeek API 要求
     */
    public List<Map<String, Object>> toJsonSchema() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (ToolMeta meta : tools.values()) {
            result.add(buildToolSchema(meta));
        }
        return result;
    }

    /**
     * 将单个工具元数据转换为 JSON Schema。
     */
    private Map<String, Object> buildToolSchema(ToolMeta meta) {
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();

        for (Map<String, Object> param : meta.getParameters()) {
            Map<String, Object> prop = new LinkedHashMap<>();
            prop.put("type", param.get("type"));
            prop.put("description", param.get("description"));
            properties.put((String) param.get("name"), prop);

            if (meta.getRequiredParams().contains(param.get("name"))) {
                required.add((String) param.get("name"));
            }
        }

        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("type", "object");
        parameters.put("properties", properties);
        if (!required.isEmpty()) {
            parameters.put("required", required);
        }

        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", meta.getName());
        function.put("description", meta.getDescription());
        function.put("parameters", parameters);

        Map<String, Object> tool = new LinkedHashMap<>();
        tool.put("type", "function");
        tool.put("function", function);

        return tool;
    }

    /**
     * 根据名称获取工具元数据。
     *
     * @param name 工具名称
     * @return 工具元数据，不存在返回 null
     */
    public ToolMeta getTool(String name) {
        return tools.get(name);
    }

    /**
     * 判断工具是否在白名单中（可自动执行写操作）。
     *
     * @param toolName 工具名称
     * @return true 表示在白名单中，无需二次确认
     */
    public boolean isAutoConfirm(String toolName) {
        return whitelist.contains(toolName);
    }

    /**
     * 执行指定名称的工具。
     * <p>
     * 内置 10 秒超时保护，超时时向 LLM 返回超时信息以便重试。
     *
     * @param toolCall 包含工具名称和 JSON 参数的 tool_call
     * @return 工具执行结果的文本表示
     * @throws IllegalArgumentException 工具不存在或参数解析失败
     * @throws Exception 工具方法执行异常或超时
     */
    public String execute(com.deepseek.demo.dto.ToolCall toolCall) throws Exception {
        String name = toolCall.getFunction().getName();
        String arguments = toolCall.getFunction().getArguments();

        ToolMeta meta = tools.get(name);
        if (meta == null) {
            throw new IllegalArgumentException("工具不存在: " + name);
        }

        // 解析 JSON 参数为 Map
        Map<String, Object> argsMap = objectMapper.readValue(
                arguments,
                new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {}
        );

        // 按方法参数顺序构建参数数组
        List<Object> methodArgs = new ArrayList<>();
        for (Map<String, Object> param : meta.getParameters()) {
            String paramName = (String) param.get("name");
            Object value = argsMap.get(paramName);
            if (value != null) {
                methodArgs.add(String.valueOf(value));
            }
        }

        // 带超时的反射调用（工具执行超时 10s）
        java.util.concurrent.ExecutorService executor =
                java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            java.util.concurrent.Future<String> future = executor.submit(() -> {
                try {
                    Object result = meta.getMethod().invoke(meta.getBean(),
                            methodArgs.toArray());
                    log.debug("执行工具成功: name={}", name);
                    return result != null ? result.toString() : "执行成功（无返回结果）";
                } catch (Exception e) {
                    throw new RuntimeException(e.getCause() != null
                            ? e.getCause() : e);
                }
            });

            String result = future.get(10, java.util.concurrent.TimeUnit.SECONDS);
            log.info("工具执行完成: name={}, resultLength={}", name, result.length());
            return result;

        } catch (java.util.concurrent.TimeoutException e) {
            future.cancel(true);
            log.error("工具执行超时(10s): name={}", name);
            throw new RuntimeException("工具调用超时（10s），请稍后重试");
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            log.error("工具执行失败: name={}", name, cause);
            throw cause instanceof Exception ? (Exception) cause
                    : new RuntimeException(cause.getMessage());
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * 获取所有已注册的工具名称。
     */
    public Set<String> getToolNames() {
        return tools.keySet();
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return null;
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}
```

- [ ] **Step 3: Update application.yml**

```yaml
# application.yml — add at end
tool:
  whitelist: ${TOOL_WHITELIST:send_notification,update_task_status,feishu_send_message}
```

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/deepseek/demo/service/ToolRegistry.java \
        src/main/java/com/deepseek/demo/service/ToolMeta.java \
        src/main/resources/application.yml
git commit -m "feat: add ToolRegistry with @Tool scanning, JSON Schema generation, and execution"
```

---

### Task 8: Create AgentService (core engine)

**Files:**
- Create: `src/main/java/com/deepseek/demo/service/AgentService.java`

- [ ] **Step 1: Create AgentService**

```java
package com.deepseek.demo.service;

import com.deepseek.demo.annotation.ActionType;
import com.deepseek.demo.dto.*;
import com.deepseek.demo.store.ConfirmationStore;
import com.deepseek.demo.store.ConversationStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Agent 服务，实现基于 ReAct 循环的自动操作引擎。
 * <p>
 * 核心流程：
 * <ol>
 *   <li>接收用户问题 → RAG 检索知识库 → 拼入 system prompt</li>
 *   <li>调用 DeepSeek API（带 tools）→ LLM 返回 tool_calls 或文本</li>
 *   <li>首次 tool_calls → 生成操作计划确认点，等待用户确认</li>
 *   <li>用户确认后 → 逐个执行 tool（读自动，写按白名单判断）</li>
 *   <li>非白名单写操作 → 二次确认 → 确认后执行 → 结果回 LLM</li>
 *   <li>所有操作完成后 → LLM 汇总最终回答</li>
 * </ol>
 */
@Service
public class AgentService {

    private static final Logger log = LoggerFactory.getLogger(AgentService.class);

    /** ReAct 循环最大迭代次数，防止无限循环 */
    private static final int MAX_ITERATIONS = 10;

    private final DeepSeekService deepSeekService;
    private final ToolRegistry toolRegistry;
    private final ConversationStore conversationStore;
    private final ConfirmationStore confirmationStore;
    private final VectorService vectorService;
    private final ObjectMapper objectMapper;

    /**
     * 构造 AgentService。
     */
    public AgentService(DeepSeekService deepSeekService,
                        ToolRegistry toolRegistry,
                        ConversationStore conversationStore,
                        ConfirmationStore confirmationStore,
                        VectorService vectorService,
                        ObjectMapper objectMapper) {
        this.deepSeekService = deepSeekService;
        this.toolRegistry = toolRegistry;
        this.conversationStore = conversationStore;
        this.confirmationStore = confirmationStore;
        this.vectorService = vectorService;
        this.objectMapper = objectMapper;
    }

    /**
     * Agent 对话入口。
     * <p>
     * 接收用户消息，检索知识库，进入 ReAct 循环。
     *
     * @param conversationId 会话 ID
     * @param userMessage 用户消息
     * @return AgentResponse（done / confirmation / error）
     */
    public AgentResponse chat(String conversationId, String userMessage) {
        log.info("Agent 对话开始: conversationId={}, message={}",
                conversationId, truncate(userMessage, 50));

        try {
            // 1. 检索知识库
            String knowledgeContext = retrieveKnowledge(userMessage);

            // 2. 获取或创建消息历史
            List<Message> messages = conversationStore.getMessages(conversationId);
            if (messages.isEmpty()) {
                messages.add(new Message("system", buildSystemPrompt(knowledgeContext)));
                messages.add(new Message("user", userMessage));
            }

            // 3. 进入 ReAct 循环
            return agentLoop(conversationId, messages);

        } catch (Exception e) {
            log.error("Agent 对话异常", e);
            return AgentResponse.error("处理失败: " + e.getMessage());
        }
    }

    /**
     * 确认回调，处理用户的确认/拒绝/反馈操作。
     * <p>
     * 三种场景：
     * <ul>
     *   <li>plan + confirm + feedback → 调整后执行</li>
     *   <li>plan + confirm → 批准，开始执行</li>
     *   <li>plan + !confirm + feedback → 有反馈但未确认，LLM 调整方案</li>
     *   <li>plan + !confirm → 明确拒绝，终止流程</li>
     *   <li>exec 类型同理</li>
     * </ul>
     *
     * @param conversationId 会话 ID
     * @param confirmationId 确认点 ID
     * @param confirm 是否确认执行
     * @param feedback 可选的修改意见
     * @return AgentResponse（done / confirmation / error）
     */
    public AgentResponse confirm(String conversationId, String confirmationId,
                                  boolean confirm, String feedback) {
        log.info("确认回调: confirmationId={}, confirm={}, feedback={}",
                confirmationId, confirm, feedback);

        // 1. 校验确认点
        ConfirmationStore.ConfirmationState cp = confirmationStore.get(confirmationId);
        if (cp == null) {
            return AgentResponse.error("确认点无效或已过期，请重新提问");
        }
        if (cp.isConsumed()) {
            return AgentResponse.error("该操作已处理，请勿重复确认");
        }
        confirmationStore.consume(confirmationId);

        // 2. 恢复消息历史
        List<Message> messages = conversationStore.getMessages(conversationId);

        // 3. 根据确认点类型处理
        if ("plan".equals(cp.getType())) {
            return handlePlanConfirm(conversationId, messages, cp, confirm, feedback);
        } else if ("exec".equals(cp.getType())) {
            return handleExecConfirm(conversationId, messages, cp, confirm, feedback);
        } else {
            return AgentResponse.error("未知的确认点类型: " + cp.getType());
        }
    }

    /**
     * 处理 plan 类型确认。
     * <p>
     * confirm=true: 批准操作计划，开始执行
     * confirm=false + feedback: LLM 调整方案
     * confirm=false: 终止流程
     */
    private AgentResponse handlePlanConfirm(String conversationId,
                                             List<Message> messages,
                                             ConfirmationStore.ConfirmationState cp,
                                             boolean confirm, String feedback) {
        if (confirm) {
            // 批准计划
            conversationStore.setPlanConfirmed(conversationId, true);
            conversationStore.setApprovedPlan(conversationId, cp.getPlanToolCalls());

            if (feedback != null && !feedback.isEmpty()) {
                // 批准但有修改意见 → 移除上一条 assistant tool_calls，让 LLM 调整
                removeLastAssistantMessage(messages);
                messages.add(new Message("user",
                        "操作计划已确认，但请按以下调整后执行: " + feedback));
            } else {
                // 直接批准 → 注入已批准计划，要求 LLM 严格按计划执行
                messages.add(new Message("system",
                        "用户已确认操作计划。请严格按以下计划逐项执行，不得增删改操作。\n" +
                        "已批准的计划:\n" + formatPlan(cp.getPlanToolCalls())));
            }
            return agentLoop(conversationId, messages);

        } else if (feedback != null && !feedback.isEmpty()) {
            // 有反馈但未确认 → LLM 调整方案
            removeLastAssistantMessage(messages);
            messages.add(new Message("user",
                    "请根据以下意见调整方案: " + feedback));
            return agentLoop(conversationId, messages);

        } else {
            // 明确拒绝 → 终止
            conversationStore.clearCheckpoint(conversationId);
            log.info("用户取消操作计划: conversationId={}", conversationId);
            return AgentResponse.done("操作计划已被用户取消");
        }
    }

    /**
     * 处理 exec 类型确认。
     * <p>
     * confirm=true: 执行工具
     * confirm=true + feedback: 调整后执行
     * confirm=false + feedback: LLM 调整
     * confirm=false: 终止流程
     */
    private AgentResponse handleExecConfirm(String conversationId,
                                             List<Message> messages,
                                             ConfirmationStore.ConfirmationState cp,
                                             boolean confirm, String feedback) {
        if (!confirm) {
            if (feedback != null && !feedback.isEmpty()) {
                // 有反馈但未确认 → LLM 调整 tool_call
                removeLastAssistantMessage(messages);
                messages.add(new Message("user",
                        "请按以下调整后重新执行: " + feedback));
                appendPendingHint(messages, cp.getPendingToolCalls());
                return agentLoop(conversationId, messages);
            }
            // 明确拒绝 → 终止
            conversationStore.clearCheckpoint(conversationId);
            log.info("用户取消操作: conversationId={}, tool={}",
                    conversationId, cp.getToolName());
            return AgentResponse.done("操作已被用户取消");
        }

        if (feedback != null && !feedback.isEmpty()) {
            // 确认但有修改意见 → LLM 调整后执行
            removeLastAssistantMessage(messages);
            messages.add(new Message("user",
                    "确认执行，但请按以下调整: " + feedback));
            appendPendingHint(messages, cp.getPendingToolCalls());
            return agentLoop(conversationId, messages);
        }

        // 确认 + 无修改 → 执行工具
        try {
            // 重建 ToolCall 以执行（从 ConfirmationState 中恢复参数字符串）
            String toolArgs = cp.getToolArguments() != null
                    ? cp.getToolArguments() : "{}";
            com.deepseek.demo.dto.ToolCall toolCall = new com.deepseek.demo.dto.ToolCall();
            toolCall.setId(cp.getToolCallId());
            FunctionCall func = new FunctionCall(cp.getToolName(), toolArgs);
            toolCall.setFunction(func);

            String result = toolRegistry.execute(toolCall);
            messages.add(new Message("tool", result, cp.getToolCallId()));
            log.info("工具执行完成: tool={}, resultLength={}",
                    cp.getToolName(), result.length());

            // 提示剩余操作
            appendPendingHint(messages, cp.getPendingToolCalls());
            return agentLoop(conversationId, messages);

        } catch (Exception e) {
            log.error("工具执行失败: tool={}", cp.getToolName(), e);
            // 将异常信息以 tool role 返回，让 LLM 决定如何处理
            messages.add(new Message("tool",
                    "工具执行异常: " + e.getMessage(), cp.getToolCallId()));
            return agentLoop(conversationId, messages);
        }
    }

    /**
     * ReAct 循环核心。
     * <p>
     * 循环调用 DeepSeek API（带 tools），根据返回结果决定下一步：
     * <ul>
     *   <li>无 tool_calls → 返回最终回答</li>
     *   <li>有 tool_calls + 未确认计划 → 创建计划确认点</li>
     *   <li>有 tool_calls + 已确认 → 逐个执行，写操作需二次确认</li>
     * </ul>
     *
     * @param conversationId 会话 ID
     * @param messages 当前消息列表
     * @return AgentResponse
     */
    private AgentResponse agentLoop(String conversationId, List<Message> messages) {
        boolean planConfirmed = conversationStore.getPlanConfirmed(conversationId);

        for (int i = 0; i < MAX_ITERATIONS; i++) {
            // 保存 checkpoint
            conversationStore.saveCheckpoint(conversationId, messages, i);

            // 调用 DeepSeek API（带 tools）
            DeepSeekChatResponse response;
            try {
                response = deepSeekService.chatWithTools(messages, toolRegistry.toJsonSchema());
            } catch (Exception e) {
                log.error("DeepSeek API 调用失败(第{}轮)", i, e);
                return AgentResponse.error("服务暂时不可用，请稍后再试");
            }

            // 解析响应
            if (response == null || response.getChoices() == null
                    || response.getChoices().isEmpty()) {
                log.error("DeepSeek API 返回空响应(第{}轮)", i);
                return AgentResponse.error("模型返回异常，请重试");
            }

            Message responseMessage = response.getChoices().get(0).getMessage();
            List<ToolCall> toolCalls = response.getChoices().get(0).getToolCalls();
            messages.add(responseMessage);

            if (toolCalls == null || toolCalls.isEmpty()) {
                // 无 tool_calls → 最终回答
                conversationStore.clearCheckpoint(conversationId);
                String content = responseMessage.getContent();
                log.info("Agent 返回最终回答(第{}轮): length={}", i,
                        content != null ? content.length() : 0);
                return AgentResponse.done(content != null ? content : "");
            }

            if (!planConfirmed) {
                // 首次 tool_calls → 生成操作计划确认点
                log.info("生成操作计划确认点(第{}轮): toolCalls={}",
                        i, toolCalls.size());
                return createPlanConfirmation(conversationId, messages, toolCalls);
            }

            // 已确认计划 → 逐个执行
            List<Map<String, Object>> approvedPlan = conversationStore.getApprovedPlan(conversationId);

            for (int t = 0; t < toolCalls.size(); t++) {
                ToolCall tc = toolCalls.get(t);
                String toolName = tc.getFunction().getName();

                // 校验是否在已批准计划中
                if (approvedPlan != null && !isInApprovedPlan(toolName, approvedPlan)) {
                    log.warn("工具不在已批准计划中，跳过: tool={}", toolName);
                    messages.add(new Message("system",
                            "操作 \"" + toolName + "\" 不在已批准计划中，已跳过。"));
                    continue;
                }

                ToolMeta meta = toolRegistry.getTool(toolName);
                if (meta == null) {
                    log.warn("工具不存在，跳过: tool={}", toolName);
                    messages.add(new Message("system",
                            "工具 \"" + toolName + "\" 不存在，已跳过。"));
                    continue;
                }

                if (meta.getAction() == ActionType.WRITE
                        && !toolRegistry.isAutoConfirm(meta.getName())) {
                    // 非白名单写操作 → 二次确认
                    List<ToolCall> remaining = toolCalls.subList(t + 1, toolCalls.size());
                    log.info("二次确认: tool={}, remaining={}", toolName, remaining.size());
                    return createExecConfirmation(conversationId, messages, tc, remaining);
                }

                // 直接执行（READ 或白名单 WRITE）
                try {
                    String result = toolRegistry.execute(tc);
                    messages.add(new Message("tool", result, tc.getId()));
                    log.debug("工具执行成功: tool={}", toolName);
                } catch (Exception e) {
                    log.error("工具执行失败: tool={}", toolName, e);
                    messages.add(new Message("tool",
                            "执行异常: " + e.getMessage(), tc.getId()));
                }
            }
        }

        // 达到最大迭代次数
        log.warn("ReAct 循环达到最大迭代次数: conversationId={}", conversationId);
        conversationStore.clearCheckpoint(conversationId);
        return AgentResponse.done("任务未完全执行，已达最大处理轮次。请尝试简化操作需求。");
    }

    /**
     * 创建 plan 类型确认点。
     */
    private AgentResponse createPlanConfirmation(String conversationId,
                                                  List<Message> messages,
                                                  List<ToolCall> toolCalls) {
        // 保存消息历史
        conversationStore.saveMessages(conversationId, messages);

        // 提取操作计划摘要
        List<Map<String, Object>> plan = new ArrayList<>();
        for (ToolCall tc : toolCalls) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("tool", tc.getFunction().getName());
            item.put("action", "READ"); // 默认 READ，后续由 ToolRegistry 覆盖

            ToolMeta meta = toolRegistry.getTool(tc.getFunction().getName());
            if (meta != null) {
                item.put("action", meta.getAction() == ActionType.WRITE ? "WRITE" : "READ");
            }

            // 解析参数（可能失败，不影响整体）
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> args = objectMapper.readValue(
                        tc.getFunction().getArguments(), Map.class);
                item.put("args", args);
            } catch (Exception e) {
                item.put("args", Collections.singletonMap("raw", tc.getFunction().getArguments()));
            }
            plan.add(item);
        }

        // 生成确认 ID
        String confirmationId = confirmationStore.createPlanConfirmation(
                conversationId, plan);

        // 构建确认点响应
        long readCount = plan.stream().filter(p -> "READ".equals(p.get("action"))).count();
        long writeCount = plan.stream().filter(p -> "WRITE".equals(p.get("action"))).count();

        ConfirmationPoint cp = new ConfirmationPoint();
        cp.setConfirmationId(confirmationId);
        cp.setConversationId(conversationId);
        cp.setType("plan");
        cp.setPlan(plan);
        cp.setSummary("共 " + plan.size() + " 项操作：" + readCount + " 项查询 + " + writeCount + " 项写入");
        cp.setCreatedAt(System.currentTimeMillis());

        // 尝试获取 reasoning：由于 DeepSeek 文本与 tool_calls 互斥，
        // 当前不额外调用 LLM 生成推理文本，由客户端根据 plan 渲染
        cp.setReasoning("");

        log.info("创建 plan 确认点: id={}, planSize={}", confirmationId, plan.size());
        return AgentResponse.waitConfirm(cp);
    }

    /**
     * 创建 exec 类型确认点。
     */
    private AgentResponse createExecConfirmation(String conversationId,
                                                  List<Message> messages,
                                                  ToolCall toolCall,
                                                  List<ToolCall> pendingToolCalls) {
        conversationStore.saveMessages(conversationId, messages);

        String confirmationId = confirmationStore.createExecConfirmation(
                conversationId, toolCall, pendingToolCalls);

        ConfirmationPoint cp = new ConfirmationPoint();
        cp.setConfirmationId(confirmationId);
        cp.setConversationId(conversationId);
        cp.setType("exec");
        cp.setTool(toolCall.getFunction().getName());

        // 解析参数供客户端展示
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> args = objectMapper.readValue(
                    toolCall.getFunction().getArguments(), Map.class);
            cp.setArgs(args);
        } catch (Exception e) {
            Map<String, Object> rawArgs = new LinkedHashMap<>();
            rawArgs.put("raw", toolCall.getFunction().getArguments());
            cp.setArgs(rawArgs);
        }

        cp.setReasoning("即将执行写操作，请确认");
        cp.setCreatedAt(System.currentTimeMillis());

        log.info("创建 exec 确认点: id={}, tool={}, pending={}",
                confirmationId, toolCall.getFunction().getName(),
                pendingToolCalls != null ? pendingToolCalls.size() : 0);
        return AgentResponse.waitConfirm(cp);
    }

    /**
     * 检索知识库。
     */
    private String retrieveKnowledge(String question) {
        try {
            List<Map<String, Object>> contexts = vectorService.searchDocsWithFullContent(question, 5);
            if (contexts == null || contexts.isEmpty()) {
                return "";
            }
            return contexts.stream()
                    .map(ctx -> {
                        String text = (String) ctx.get("text");
                        String source = (String) ctx.get("file_name");
                        if (source != null) {
                            return "[来源: " + source + "]\n" + text;
                        }
                        return text;
                    })
                    .collect(Collectors.joining("\n\n---\n\n"));
        } catch (Exception e) {
            log.warn("知识库检索失败，降级为空上下文", e);
            return "";
        }
    }

    /**
     * 构建系统提示词。
     */
    private String buildSystemPrompt(String context) {
        if (context == null || context.isEmpty()) {
            return "你是一个智能助手，请根据你的知识回答用户的问题。"
                    + "你可以调用可用的工具来帮助用户完成操作。"
                    + "如果需要执行多个操作，请一次性列出所有操作。"
                    + "请用中文回答。";
        }
        return "你是一个智能知识库助手。请基于以下参考内容回答用户的问题。"
                + "你可以调用可用的工具来帮助用户完成操作。"
                + "如果需要执行多个操作，请一次性列出所有操作。"
                + "请用中文回答。\n\n参考内容：\n" + context;
    }

    /**
     * 格式化操作计划为可读文本。
     */
    private String formatPlan(List<Map<String, Object>> plan) {
        if (plan == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < plan.size(); i++) {
            Map<String, Object> item = plan.get(i);
            sb.append(i + 1).append(". ");
            sb.append("[").append(item.get("action")).append("] ");
            sb.append(item.get("tool"));
            if (item.get("args") instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> args = (Map<String, Object>) item.get("args");
                if (!args.isEmpty()) {
                    sb.append(" ").append(args);
                }
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    /**
     * 判断工具是否在已批准的计划中。
     */
    private boolean isInApprovedPlan(String toolName, List<Map<String, Object>> plan) {
        return plan.stream().anyMatch(item -> toolName.equals(item.get("tool")));
    }

    /**
     * 移除消息列表中最后一条 assistant 角色的消息（通常是带 tool_calls 的）。
     * 在需要让 LLM 重新生成 tool_calls 时使用。
     */
    private void removeLastAssistantMessage(List<Message> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if ("assistant".equals(messages.get(i).getRole())) {
                messages.remove(i);
                return;
            }
        }
    }

    /**
     * 追加剩余操作提示。
     */
    private void appendPendingHint(List<Message> messages,
                                    List<com.deepseek.demo.dto.ToolCall> pendingToolCalls) {
        if (pendingToolCalls != null && !pendingToolCalls.isEmpty()) {
            StringBuilder sb = new StringBuilder("上一步已完成。你还需继续执行以下操作:\n");
            // 从 tool_calls 中提取名称
            for (int i = 0; i < pendingToolCalls.size(); i++) {
                String name = pendingToolCalls.get(i).getFunction().getName();
                sb.append(i + 1).append(". ").append(name).append("\n");
            }
            messages.add(new Message("system", sb.toString()));
        }
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return null;
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add src/main/java/com/deepseek/demo/service/AgentService.java
git commit -m "feat: add AgentService with ReAct loop and dual confirmation"
```

---

### Task 9: Create AgentController

**Files:**
- Create: `src/main/java/com/deepseek/demo/controller/AgentController.java`

- [ ] **Step 1: Create AgentController**

```java
package com.deepseek.demo.controller;

import com.deepseek.demo.dto.AgentResponse;
import com.deepseek.demo.service.AgentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Agent API 控制器，提供 LLM function calling + ReAct 的 HTTP 端点。
 * <p>
 * 提供两个端点：
 * <ul>
 *   <li>POST /api/agent/chat — Agent 对话入口</li>
 *   <li>POST /api/agent/confirm — 确认回调</li>
 * </ul>
 * <p>
 * 流式端点（POST /api/agent/stream）作为第二阶段迭代，当前未实现。
 */
@RestController
@RequestMapping("/api/agent")
public class AgentController {

    private static final Logger log = LoggerFactory.getLogger(AgentController.class);

    private final AgentService agentService;

    public AgentController(AgentService agentService) {
        this.agentService = agentService;
    }

    /**
     * Agent 对话入口。
     * <p>
     * 请求体格式：
     * <pre>
     * {
     *   "conversation_id": "conv-xxx",  // 客户端生成，用于保持对话上下文
     *   "message": "帮我查一下张三的工单"    // 用户输入
     * }
     * </pre>
     * <p>
     * 响应体格式（三种类型）：
     * <pre>
     * // 最终回答
     * {"type": "done", "reply": "查询结果..."}
     *
     * // 需要确认
     * {"type": "confirmation", "confirmation_point": {...}}
     *
     * // 错误
     * {"type": "error", "reply": "错误描述"}
     * </pre>
     */
    @PostMapping("/chat")
    public ResponseEntity<AgentResponse> chat(@RequestBody Map<String, String> request) {
        String conversationId = request.get("conversation_id");
        String message = request.get("message");

        if (conversationId == null || conversationId.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(AgentResponse.error("conversation_id 不能为空"));
        }
        if (message == null || message.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(AgentResponse.error("message 不能为空"));
        }

        log.info("收到 Agent 请求: conversationId={}, message={}",
                truncate(conversationId, 20), truncate(message, 50));

        AgentResponse response = agentService.chat(conversationId, message);

        log.info("Agent 响应: type={}", response.getType());
        return ResponseEntity.ok(response);
    }

    /**
     * 确认回调，处理用户的确认/拒绝/反馈操作。
     * <p>
     * 请求体格式：
     * <pre>
     * {
     *   "conversation_id": "conv-xxx",
     *   "confirmation_id": "cnf-plan-xxx",
     *   "confirm": true,
     *   "feedback": "把 assignee 改成李四"  // 可选
     * }
     * </pre>
     */
    @PostMapping("/confirm")
    public ResponseEntity<AgentResponse> confirm(@RequestBody Map<String, Object> request) {
        String conversationId = (String) request.get("conversation_id");
        String confirmationId = (String) request.get("confirmation_id");
        boolean confirm = request.get("confirm") instanceof Boolean
                ? (Boolean) request.get("confirm")
                : false;
        String feedback = (String) request.get("feedback");

        if (conversationId == null || confirmationId == null) {
            return ResponseEntity.badRequest()
                    .body(AgentResponse.error("conversation_id 和 confirmation_id 不能为空"));
        }

        log.info("收到确认请求: confirmationId={}, confirm={}, feedback={}",
                confirmationId, confirm, feedback);

        AgentResponse response = agentService.confirm(
                conversationId, confirmationId, confirm, feedback);

        log.info("确认响应: type={}", response.getType());
        return ResponseEntity.ok(response);
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return null;
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add src/main/java/com/deepseek/demo/controller/AgentController.java
git commit -m "feat: add AgentController with /api/agent/chat and /api/agent/confirm endpoints"
```

---

### Task 10: Add example tools

**Files:**
- Create: `src/main/java/com/deepseek/demo/service/tools/TaskTools.java`
- Create: `src/main/java/com/deepseek/demo/service/tools/ExternalTools.java`

- [ ] **Step 1: Create example internal tools**

```java
package com.deepseek.demo.service.tools;

import com.deepseek.demo.annotation.ActionType;
import com.deepseek.demo.annotation.Tool;
import com.deepseek.demo.annotation.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 示例工具集：任务管理相关的内部 API。
 * <p>
 * 这些工具会被 ToolRegistry 自动扫描并注册，
 * 供 LLM 在适当时机调用。
 */
@Component
public class TaskTools {

    private static final Logger log = LoggerFactory.getLogger(TaskTools.class);

    /**
     * 查询任务列表。
     *
     * @param assignee 负责人姓名
     * @param status 筛选状态（可选）
     * @return 查询结果文本
     */
    @Tool(name = "query_task",
          description = "查询任务列表，可按负责人和状态筛选",
          parameters = {
              @ToolParam(name = "assignee", type = "string",
                         description = "负责人姓名", required = true),
              @ToolParam(name = "status", type = "string",
                         description = "筛选状态：待办/进行中/已完成")
          },
          action = ActionType.READ)
    public String queryTask(String assignee, String status) {
        log.info("查询任务: assignee={}, status={}", assignee, status);
        // TODO: 对接实际的工单系统 API
        return "用户 " + assignee + " 的工单：\n"
                + "- 需求评审 (进行中)\n"
                + "- 代码审查 (待办)\n"
                + "- 部署上线 (待办)";
    }

    /**
     * 创建新任务。
     *
     * @param title 任务标题
     * @param assignee 负责人（可选）
     * @param dueDate 截止日期 yyyy-MM-dd（可选）
     * @return 创建结果
     */
    @Tool(name = "create_task",
          description = "创建新任务，需要指定任务标题",
          parameters = {
              @ToolParam(name = "title", type = "string",
                         description = "任务标题", required = true),
              @ToolParam(name = "assignee", type = "string",
                         description = "负责人"),
              @ToolParam(name = "due_date", type = "string",
                         description = "截止日期，格式 yyyy-MM-dd")
          },
          action = ActionType.WRITE)
    public String createTask(String title, String assignee, String dueDate) {
        log.info("创建任务: title={}, assignee={}, dueDate={}", title, assignee, dueDate);
        // TODO: 对接实际的工单系统 API
        return "已创建任务【" + title + "】"
                + (assignee != null ? "，负责人 " + assignee : "")
                + (dueDate != null ? "，截止日期 " + dueDate : "");
    }
}
```

- [ ] **Step 2: Create example external tools**

```java
package com.deepseek.demo.service.tools;

import com.deepseek.demo.annotation.ActionType;
import com.deepseek.demo.annotation.Tool;
import com.deepseek.demo.annotation.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 示例工具集：外部服务 API。
 * <p>
 * 这些工具会被 ToolRegistry 自动扫描并注册，
 * 供 LLM 在适当时机调用。
 */
@Component
public class ExternalTools {

    private static final Logger log = LoggerFactory.getLogger(ExternalTools.class);

    /**
     * 发送飞书消息。
     * <p>
     * 此工具在白名单中（默认配置），写操作无需二次确认。
     *
     * @param webhookUrl 飞书 Webhook 地址
     * @param content 消息内容
     * @return 发送结果
     */
    @Tool(name = "feishu_send_message",
          description = "通过 Webhook 发送飞书消息通知",
          parameters = {
              @ToolParam(name = "webhook_url", type = "string",
                         description = "飞书机器人 Webhook 地址", required = true),
              @ToolParam(name = "content", type = "string",
                         description = "消息内容", required = true)
          },
          action = ActionType.WRITE)
    public String sendFeishuMessage(String webhookUrl, String content) {
        log.info("发送飞书消息: webhookUrl={}, content={}", webhookUrl, truncate(content, 50));
        // TODO: 对接实际的飞书 Webhook API
        return "飞书消息发送成功";
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return null;
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}
```

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/deepseek/demo/service/tools/
git commit -m "feat: add example TaskTools and ExternalTools"
```

---

### Task 11: Add unit tests

**Files:**
- Create: `src/test/java/com/deepseek/demo/service/ToolRegistryTest.java`

- [ ] **Step 1: Write ToolRegistryTest**

```java
package com.deepseek.demo.service;

import com.deepseek.demo.annotation.ActionType;
import com.deepseek.demo.annotation.Tool;
import com.deepseek.demo.annotation.ToolParam;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ToolRegistry 单元测试，验证注解扫描、JSON Schema 生成和工具执行。
 */
@SpringBootTest
class ToolRegistryTest {

    @Autowired
    private ToolRegistry toolRegistry;

    @Test
    void testToJsonSchema_containsExpectedTools() {
        List<Map<String, Object>> schema = toolRegistry.toJsonSchema();
        assertNotNull(schema);
        assertFalse(schema.isEmpty(), "应该有已注册的工具");

        // 验证 query_task 工具存在
        boolean hasQueryTask = schema.stream()
                .anyMatch(tool -> {
                    Map<String, Object> func = (Map<String, Object>) tool.get("function");
                    return func != null && "query_task".equals(func.get("name"));
                });
        assertTrue(hasQueryTask, "query_task 应该在 schema 中");
    }

    @Test
    void testGetTool_returnsMeta() {
        ToolMeta meta = toolRegistry.getTool("query_task");
        assertNotNull(meta, "query_task 应该已注册");
        assertEquals("query_task", meta.getName());
        assertNotNull(meta.getDescription());
    }

    @Test
    void testGetTool_notFound_returnsNull() {
        assertNull(toolRegistry.getTool("nonexistent_tool"));
    }

    @Test
    void testIsAutoConfirm_whitelisted() {
        // feishu_send_message 在默认白名单中
        assertTrue(toolRegistry.isAutoConfirm("feishu_send_message"));
    }

    @Test
    void testIsAutoConfirm_notWhitelisted() {
        assertFalse(toolRegistry.isAutoConfirm("create_task"));
    }
}
```

- [ ] **Step 2: Run ToolRegistryTest**

Run: `mvn test -Dtest=ToolRegistryTest -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/deepseek/demo/service/ToolRegistryTest.java
git commit -m "test: add ToolRegistry unit tests"
```

### Task 12: Compile and verify

**Files:**
- Verify the project compiles and tests pass

- [ ] **Step 1: Compile the project**

Run: `cd /Users/sunjiakai/localProject/AIDev/AIDev && mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 2: Run existing tests to check no regressions**

Run: `cd /Users/sunjiakai/localProject/AIDev/AIDev && mvn test`
Expected: All tests pass

- [ ] **Step 3: Fix any compilation errors or test failures**
