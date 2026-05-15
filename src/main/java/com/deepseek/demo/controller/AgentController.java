package com.deepseek.demo.controller;

import com.deepseek.demo.dto.AgentResponse;
import com.deepseek.demo.service.AgentService;
import com.deepseek.demo.store.ConversationStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

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
    private final ConversationStore conversationStore;

    public AgentController(AgentService agentService, ConversationStore conversationStore) {
        this.agentService = agentService;
        this.conversationStore = conversationStore;
    }

    /**
     * Agent 对话入口。
     * <p>
     * 请求体格式：
     * <pre>
     * {
     *   "conversation_id": "conv-xxx",  // 可选，首次请求由服务端自动生成
     *   "message": "帮我查一下张三的工单"    // 用户输入
     * }
     * </pre>
     * <p>
     * 首次请求可不传 {@code conversation_id}，服务端自动生成 UUID 并返回。
     * <p>
     * 响应体格式（三种类型）：
     * <pre>
     * // 最终回答
     * {"type": "done", "reply": "查询结果...", "conversation_id": "uuid-xxx"}
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

        // conversation_id 为空时自动生成，首次请求无需客户端构造
        if (conversationId == null || conversationId.isEmpty()) {
            conversationId = UUID.randomUUID().toString();
            log.debug("已自动生成 conversationId={}", conversationId);
        }
        if (message == null || message.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(AgentResponse.error("message 不能为空"));
        }

        // 生成追踪 ID，注入 MDC 以便所有日志自动关联
        String traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        MDC.put("traceId", traceId);
        conversationStore.setTraceId(conversationId, traceId);

        try {
            log.info("收到 Agent 请求: conversationId={}, message={}",
                    truncate(conversationId, 20), truncate(message, 50));

            AgentResponse response = agentService.chat(conversationId, message);
            response.setConversationId(conversationId);
            response.setTraceId(traceId);

            log.info("Agent 响应: type={}", response.getType());
            return ResponseEntity.ok(response);
        } finally {
            MDC.remove("traceId");
        }
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

        // 恢复追踪 ID 使 confirm 请求的日志与原始 chat 请求关联
        String traceId = conversationStore.getTraceId(conversationId);
        if (traceId != null) {
            MDC.put("traceId", traceId);
        }

        try {
            log.info("收到确认请求: confirmationId={}, confirm={}, feedback={}",
                    confirmationId, confirm, feedback);

            AgentResponse response = agentService.confirm(
                    conversationId, confirmationId, confirm, feedback);
            response.setTraceId(traceId);

            log.info("确认响应: type={}", response.getType());
            return ResponseEntity.ok(response);
        } finally {
            MDC.remove("traceId");
        }
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return null;
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}
