package com.deepseek.demo.service;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
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

    /** capability 格式转关键词 */
    private static final Map<String, Set<String>> CAPABILITY_KEYWORD_MAP = new HashMap<>();
    static {
        CAPABILITY_KEYWORD_MAP.put("task:read", Set.of("任务", "工单", "查询", "查看", "搜索", "待办"));
        CAPABILITY_KEYWORD_MAP.put("task:write", Set.of("创建", "新建", "任务", "工单", "修改", "更新", "删除"));
        CAPABILITY_KEYWORD_MAP.put("task:search", Set.of("搜索", "查询", "查找", "过滤", "筛选"));
        CAPABILITY_KEYWORD_MAP.put("code:read", Set.of("代码", "仓库", "分支", "查看", "读取"));
        CAPABILITY_KEYWORD_MAP.put("code:write", Set.of("提交", "推送", "代码", "仓库", "分支", "合并"));
        CAPABILITY_KEYWORD_MAP.put("deploy:execute", Set.of("部署", "发布", "上线", "更新"));
        CAPABILITY_KEYWORD_MAP.put("notify:send", Set.of("通知", "发送", "消息", "提醒"));
        CAPABILITY_KEYWORD_MAP.put("search:fulltext", Set.of("搜索", "查找", "检索", "查询"));
        CAPABILITY_KEYWORD_MAP.put("doc:read", Set.of("文档", "知识库", "查看", "搜索"));
        CAPABILITY_KEYWORD_MAP.put("user:read", Set.of("用户", "成员", "组织", "查看", "查询"));
        CAPABILITY_KEYWORD_MAP.put("user:write", Set.of("创建", "添加", "用户", "成员", "权限"));
    }

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
     * 从 capabilities 数组提取关键词。
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

        return Arrays.stream(userMessage.split("[\\s,，。.!！？?、；;：:（）()【】\\[\\]{}]+"))
                .filter(w -> w.length() >= 2)
                .filter(w -> !STOP_WORDS.contains(w))
                .collect(Collectors.toSet());
    }
}
