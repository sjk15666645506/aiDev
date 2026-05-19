package com.deepseek.demo.controller;

import com.deepseek.demo.util.StringUtils;
import com.deepseek.demo.service.ILlmService;
import com.deepseek.demo.service.IVectorSearchService;
import com.deepseek.demo.service.GeneralRagService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.util.*;

@RestController
@RequestMapping("/api/chat")
public class DeepSeekController {

    private static final Logger log = LoggerFactory.getLogger(DeepSeekController.class);

    private final ILlmService deepSeekService;
    private final GeneralRagService generalRagService;
    private final IVectorSearchService vectorService;
    private final ObjectMapper objectMapper;

    /**
     * 构造 DeepSeekController
     *
     * @param deepSeekService   DeepSeek LLM 调用服务
     * @param generalRagService 通用 RAG 检索增强服务
     * @param vectorService     向量检索服务
     * @param objectMapper      JSON 序列化/反序列化
     */
    public DeepSeekController(ILlmService deepSeekService,
                              GeneralRagService generalRagService, IVectorSearchService vectorService,
                              ObjectMapper objectMapper) {
        this.deepSeekService = deepSeekService;
        this.generalRagService = generalRagService;
        this.vectorService = vectorService;
        this.objectMapper = objectMapper;
    }

    /**
     * 直接对话（非流式）
     * 请求体: {"message": "你好"}
     * 响应: {"reply": "你好！"}
     */
    @PostMapping
    public Map<String, Object> chat(@RequestBody Map<String, String> request) {
        /** 用户消息内容 */
        String message = request.get("message");
        log.info("收到非流式请求: message={}", StringUtils.truncate(message, 50));
        String reply = deepSeekService.chat(message);
        log.info("非流式响应返回");
        Map<String, Object> result = new HashMap<>();
        result.put("reply", reply);
        return result;
    }

    /**
     * 带系统提示词的非流式对话
     * 请求体: {"system": "你是一个助手", "message": "你好"}
     * 响应: {"reply": "你好！"}
     */
    @PostMapping("/system")
    public Map<String, Object> chatWithSystem(@RequestBody Map<String, String> request) {
        /** 系统提示词（角色设定） */
        String system = request.get("system");
        /** 用户消息内容 */
        String message = request.get("message");
        log.info("收到非流式请求(带系统提示): system={}, message={}",
                StringUtils.truncate(system, 30), StringUtils.truncate(message, 50));
        String reply = deepSeekService.chatWithSystem(system, message);
        log.info("非流式响应返回");
        Map<String, Object> result = new HashMap<>();
        result.put("reply", reply);
        return result;
    }

    /**
     * 流式对话（逐字符返回 JSON lines）
     * 请求体: {"message": "你好"}
     * 响应: SSE 格式，每行 {"reply":"x"}，结束行 {"done":true}
     */
    @PostMapping("/stream")
    public ResponseEntity<StreamingResponseBody> chatStream(@RequestBody Map<String, String> request) {
        /** 用户消息内容 */
        String message = request.get("message");
        log.info("收到流式请求: message={}", StringUtils.truncate(message, 50));

        StreamingResponseBody body = outputStream -> {
            try {
                deepSeekService.chatStream(message, chunk -> writeChunk(chunk, outputStream));
                writeDone(outputStream);
                log.info("流式响应完成");
            } catch (RuntimeException e) {
                if (e.getCause() instanceof IOException) {
                    log.info("客户端断开连接");
                } else {
                    log.error("流式处理异常", e);
                }
            }
        };

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
    }

    /**
     * 带系统提示词的流式对话
     * 请求体: {"system": "你是一个助手", "message": "你好"}
     * 响应: SSE 格式，每行 {"reply":"x"}，结束行 {"done":true}
     */
    @PostMapping("/stream/system")
    public ResponseEntity<StreamingResponseBody> chatStreamWithSystem(@RequestBody Map<String, String> request) {
        /** 系统提示词（角色设定） */
        String system = request.get("system");
        /** 用户消息内容 */
        String message = request.get("message");
        log.info("收到流式请求(带系统提示): system={}, message={}",
                StringUtils.truncate(system, 30), StringUtils.truncate(message, 50));

        StreamingResponseBody body = outputStream -> {
            try {
                deepSeekService.chatStream(system + "\n" + message, chunk -> writeChunk(chunk, outputStream));
                writeDone(outputStream);
                log.info("流式响应完成");
            } catch (RuntimeException e) {
                if (e.getCause() instanceof IOException) {
                    log.info("客户端断开连接");
                } else {
                    log.error("流式处理异常", e);
                }
            }
        };

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
    }

    /**
     * 知识库搜索（纯检索，不经过 AI）—— 搜索文档库，返回原始匹配内容
     * 请求体: {"question": "xxx", "limit": 5}
     * 响应: {"results": [{"text": "...", "file_name": "..."}]}
     */
    @PostMapping("/knowledge/search")
    public Map<String, Object> knowledgeSearch(@RequestBody Map<String, Object> request) {
        /** 搜索关键词 / 问题文本 */
        String question = (String) request.get("question");
        /** 返回结果条数，默认 5 */
        int limit = request.containsKey("limit") ? (int) request.get("limit") : 5;
        log.info("收到知识库搜索请求: question={}, limit={}", StringUtils.truncate(question, 50), limit);

        List<Map<String, Object>> rawResults = vectorService.searchDocsWithFullContent(question, limit);
        // 合并同文件的 chunks，只保留 text 和 file_name
        Map<String, String> merged = new LinkedHashMap<>();
        for (Map<String, Object> r : rawResults) {
            String fileName = (String) r.get("file_name");
            String text = (String) r.get("text");
            if (fileName != null && text != null) {
                merged.merge(fileName, text, (a, b) -> a + "\n" + b);
            }
        }
        List<Map<String, Object>> results = new ArrayList<>();
        for (Map.Entry<String, String> entry : merged.entrySet()) {
            Map<String, Object> item = new HashMap<>();
            item.put("text", entry.getValue());
            item.put("file_name", entry.getKey());
            results.add(item);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("results", results);
        return result;
    }

    /**
     * 通用知识库问答（非流式）—— 搜索文档库，自动组装完整文档内容
     * 请求体: {"question": "xxx", "limit": 5}
     * 响应: {"reply": "回答"}
     */
    @PostMapping("/knowledge")
    public Map<String, Object> knowledgeChat(@RequestBody Map<String, Object> request) {
        /** 用户提问 */
        String question = (String) request.get("question");
        /** 检索返回的最大结果数，默认 5 */
        int limit = request.containsKey("limit") ? (int) request.get("limit") : 5;
        log.info("收到知识库请求: question={}, limit={}", StringUtils.truncate(question, 50), limit);

        String reply = generalRagService.ragChat(question, limit);
        Map<String, Object> result = new HashMap<>();
        result.put("reply", reply);
        return result;
    }

    /**
     * 通用知识库问答（流式）—— 搜索文档库，自动组装完整文档内容
     * 请求体: {"question": "xxx", "limit": 5}
     * 响应: SSE 格式，每行 {"reply":"x"}，结束行 {"done":true}
     */
    @PostMapping("/knowledge/stream")
    public ResponseEntity<StreamingResponseBody> knowledgeChatStream(@RequestBody Map<String, Object> request) {
        /** 用户提问 */
        String question = (String) request.get("question");
        /** 检索返回的最大结果数，默认 5 */
        int limit = request.containsKey("limit") ? (int) request.get("limit") : 5;
        log.info("收到知识库流式请求: question={}, limit={}", StringUtils.truncate(question, 50), limit);

        StreamingResponseBody body = outputStream -> {
            try {
                generalRagService.ragChatStream(question, limit, new ConsumerWriter(outputStream));
                writeDone(outputStream);
                log.info("知识库流式响应完成");
            } catch (RuntimeException e) {
                if (e.getCause() instanceof IOException) {
                    log.info("客户端断开连接");
                } else {
                    log.error("知识库流式处理异常", e);
                }
            }
        };

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
    }

    /** 将 SSE 数据逐字符写入 OutputStream（JSON lines 格式） */
    private void writeChunk(String chunk, java.io.OutputStream outputStream) {
        try {
            for (char c : chunk.toCharArray()) {
                Map<String, Object> data = new HashMap<>();
                data.put("reply", String.valueOf(c));
                outputStream.write(objectMapper.writeValueAsBytes(data));
                outputStream.write('\n');
                outputStream.flush();
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /** 写入 SSE 结束标记 {"done":true} */
    private void writeDone(java.io.OutputStream outputStream) throws IOException {
        Map<String, Object> done = new HashMap<>();
        done.put("done", true);
        outputStream.write(objectMapper.writeValueAsBytes(done));
        outputStream.write('\n');
        outputStream.flush();
    }

    /**
     * 将流式响应字符逐个写入 OutputStream（JSON lines 格式）
     */
    /** 流式 Consumer：将 LLM 输出逐字符写入 SSE 响应流 */
    private class ConsumerWriter implements java.util.function.Consumer<String> {
        private final java.io.OutputStream outputStream;

        ConsumerWriter(java.io.OutputStream outputStream) {
            this.outputStream = outputStream;
        }

        @Override
        public void accept(String chunk) {
            for (char c : chunk.toCharArray()) {
                try {
                    Map<String, Object> data = new HashMap<>();
                    data.put("reply", String.valueOf(c));
                    outputStream.write(objectMapper.writeValueAsBytes(data));
                    outputStream.write('\n');
                    outputStream.flush();
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        }
    }
}
