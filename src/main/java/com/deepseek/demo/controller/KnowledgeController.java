package com.deepseek.demo.controller;

import com.deepseek.demo.service.FileParser;
import com.deepseek.demo.service.MeiliSearchService;
import com.deepseek.demo.service.IVectorSearchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

@RestController
@RequestMapping("/api/knowledge")
public class KnowledgeController {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeController.class);

    /** 文档分片：按段落分割以保持语义完整 */
    private static final int CHUNK_SIZE_DOCS = 1500;
    private static final int CHUNK_OVERLAP_DOCS = 300;

    private final IVectorSearchService vectorService;
    private final FileParser fileParser;
    private final MeiliSearchService meiliSearchService;

    /** 构造 KnowledgeController
     * @param vectorService       向量检索服务（写入 Qdrant）
     * @param fileParser          文件解析服务（docx/xlsx 等格式）
     * @param meiliSearchService  Meilisearch 全文搜索服务 */
    public KnowledgeController(IVectorSearchService vectorService, FileParser fileParser,
                               MeiliSearchService meiliSearchService) {
        this.vectorService = vectorService;
        this.fileParser = fileParser;
        this.meiliSearchService = meiliSearchService;
    }

    /**
     * 索引通用文档到文档库（aiknowledge_doc）—— 自动解析文本后按段落分块存储
     * 请求体: {"path": "/绝对路径/到/文档", "docId": "自定义ID（可选，默认使用文件路径）"}
     * 支持格式: .docx .xlsx .xls .txt .md .csv .json .xml .yml .yaml .properties .html .css
     * 排除: 图片 / PDF（文本提取不完整）/ PPT
     * 分片策略: 按段落分割，每片包含完整段落（~1500字），overlap ~300字
     * 搜索时自动按上下文窗口（匹配 chunk 前后各2个）组装内容交给 AI
     */
    @PostMapping("/ingest/doc")
    public Map<String, Object> ingestDoc(@RequestBody Map<String, String> request) throws IOException {
        /** 待索引文件的绝对路径 */
        String filePath = request.get("path");
        if (filePath == null || filePath.isEmpty()) {
            return error("path 不能为空");
        }

        Path path = Path.of(filePath);
        if (!Files.exists(path) || !Files.isRegularFile(path)) {
            return error("文件不存在: " + filePath);
        }

        String content = fileParser.extractText(path);
        if (content.isBlank()) {
            return error("文件内容为空: " + filePath);
        }

        List<String> chunks = chunkDocText(content);
        /** 自定义文档 ID，默认使用文件路径 */
        String docId = request.getOrDefault("docId", filePath);
        for (int i = 0; i < chunks.size(); i++) {
            vectorService.upsertDoc(UUID.randomUUID().toString(), chunks.get(i), docId, i, chunks.size());
        }

        Map<String, Object> result = new HashMap<>();
        result.put("chunks", chunks.size());
        result.put("file", filePath);
        return result;
    }

    /** 将文档文本按段落分块，每块包含完整段落
     * @param content 文档原始文本
     * @return 分块列表 */
    private List<String> chunkDocText(String content) {
        if (content.isBlank()) return Collections.emptyList();

        // 先按段落分割（连续两个及以上换行视为段落分隔）
        String normalized = content.replaceAll("\r\n?", "\n");
        String[] paragraphs = normalized.split("\n\\s*\n", -1);
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        for (String para : paragraphs) {
            String stripped = para.strip();
            if (stripped.isEmpty()) continue;

            String toAppend = stripped + "\n\n";
            // 如果当前块加上新段落超出上限，且当前块已有内容
            if (current.length() + toAppend.length() > CHUNK_SIZE_DOCS && current.length() > 0) {
                chunks.add(current.toString().strip());
                // overlap：保留末尾若干完整段落
                String overlap = trimToLastParagraphs(current.toString(), CHUNK_OVERLAP_DOCS);
                current = new StringBuilder(overlap);
                if (overlap.length() > 0) current.append('\n');
            }
            current.append(stripped).append("\n\n");
        }
        if (current.length() > 0) {
            chunks.add(current.toString().strip());
        }

        return chunks;
    }

    /** 从文本末尾保留完整段落直至达到目标长度
     * @param text      原始文本
     * @param targetLen 目标长度
     * @return 截断后的文本 */
    private String trimToLastParagraphs(String text, int targetLen) {
        if (text.length() <= targetLen) return text;
        String[] paragraphs = text.split("\n\n", -1);
        StringBuilder sb = new StringBuilder();
        for (int i = paragraphs.length - 1; i >= 0 && sb.length() < targetLen; i--) {
            String p = paragraphs[i].strip();
            if (!p.isEmpty()) {
                sb.insert(0, p + "\n\n");
            }
        }
        return sb.toString().strip();
    }

    /** 全量同步 Qdrant aiknowledge_doc → Meilisearch
     * @param request 请求体：{collection, meiliIndex, limit}
     * @return 同步结果 */
    @PostMapping("/sync/meilisearch")
    public Map<String, Object> syncToMeilisearch(@RequestBody Map<String, String> request) {
        String collection = request.getOrDefault("collection", "aiknowledge-doc");
        String meiliIndex = request.getOrDefault("meiliIndex", collection);
        int pageSize = Integer.parseInt(request.getOrDefault("limit", "1000"));

        log.info("开始同步 Qdrant[{}] → Meilisearch[{}]", collection, meiliIndex);

        try {
            // 清空 Meilisearch 索引，避免残留已删除文档
            String meiliClearUrl = "http://localhost:7700/indexes/" + meiliIndex + "/documents";
            new RestTemplate().exchange(meiliClearUrl, HttpMethod.DELETE, null, JsonNode.class);
            log.info("已清空 Meilisearch[{}] 现有数据", meiliIndex);

            String scrollUrl = "http://localhost:" + vectorService.getQdrantPort()
                    + "/collections/" + collection + "/points/scroll";

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            RestTemplate scrollClient = new RestTemplate();

            int totalSynced = 0;
            JsonNode nextOffset = null;

            do {
                Map<String, Object> scrollBody = new HashMap<>();
                scrollBody.put("limit", pageSize);
                scrollBody.put("with_payload", true);
                scrollBody.put("with_vectors", false);
                if (nextOffset != null) {
                    scrollBody.put("offset", nextOffset);
                }

                HttpEntity<Map<String, Object>> entity = new HttpEntity<>(scrollBody, headers);
                ResponseEntity<JsonNode> response = scrollClient.postForEntity(scrollUrl, entity, JsonNode.class);
                JsonNode result = response.getBody().path("result");
                JsonNode points = result.path("points");

                if (!points.isArray() || points.size() == 0) break;

                List<Map<String, Object>> docs = new ArrayList<>();
                for (JsonNode pt : points) {
                    JsonNode pl = pt.path("payload");
                    Map<String, Object> doc = new HashMap<>();
                    doc.put("id", pt.path("id").asText());
                    doc.put("text", pl.has("text") ? pl.get("text").asText()
                            : pl.has("content") ? pl.get("content").asText() : "");
                    doc.put("file_path", pl.has("file_path") ? pl.get("file_path").asText()
                            : pl.has("filePath") ? pl.get("filePath").asText() : "");
                    doc.put("file_name", pl.has("file_name") ? pl.get("file_name").asText()
                            : pl.has("fileName") ? pl.get("fileName").asText() : "");
                    doc.put("chunk_index", pl.has("chunk_index") ? pl.get("chunk_index").asInt()
                            : pl.has("chunkIndex") ? pl.get("chunkIndex").asInt() : 0);
                    doc.put("file_type", pl.path("file_type").asText(""));
                    doc.put("file_hash", pl.path("file_hash").asText(""));
                    docs.add(doc);
                }

                String meiliUrl = "http://localhost:7700/indexes/" + meiliIndex + "/documents";
                HttpEntity<List<Map<String, Object>>> meiliEntity = new HttpEntity<>(docs, headers);
                scrollClient.postForEntity(meiliUrl, meiliEntity, JsonNode.class);

                totalSynced += docs.size();
                log.info("同步进度: {} 条写入 Meilisearch[{}]", totalSynced, meiliIndex);

                nextOffset = result.path("next_page_offset");
            } while (nextOffset != null && !nextOffset.isNull());

            log.info("同步完成: 共 {} 条文档写入 Meilisearch[{}]", totalSynced, meiliIndex);
            Map<String, Object> result = new HashMap<>();
            result.put("sync", collection);
            result.put("documents", totalSynced);
            result.put("meiliIndex", meiliIndex);
            return result;
        } catch (Exception e) {
            log.error("同步失败: {}", e.getMessage());
            return error("同步失败: " + e.getMessage());
        }
    }

    /** 返回错误响应
     * @param msg 错误描述
     * @return {"error": msg} */
    private Map<String, Object> error(String msg) {
        Map<String, Object> result = new HashMap<>();
        result.put("error", msg);
        return result;
    }
}
