package com.deepseek.demo.service;

import com.deepseek.demo.util.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;

/**
 * Layer 3: 能力校验守卫。
 * <p>
 * LLM 选择了工具后，在真正执行前校验该工具的能力
 * 是否与用户意图匹配。不匹配时拒绝执行并给出原因，
 * 由 AgentService 将原因注入下一轮 LLM reasoning。
 */
@Component
public class CapabilityGuard {

    private static final Logger log = LoggerFactory.getLogger(CapabilityGuard.class);

    /**
     * 校验工具能力是否匹配用户意图。
     */
    public Result validate(String userMessage, ToolMeta tool) {
        // 1. 如果工具没有声明能力，不拦截（兼容旧工具）
        if (tool.getCapabilities() == null || tool.getCapabilities().isEmpty()) {
            return Result.pass();
        }

        // 2. 从工具能力倒推适用的操作关键词
        Set<String> toolActionWords = CapabilityKeywords.extractFromCapabilities(
                new HashSet<>(tool.getCapabilities()));

        if (toolActionWords.isEmpty()) {
            return Result.pass();
        }

        // 3. 从用户 query 中提取操作关键词
        Set<String> queryWords = CapabilityKeywords.extract(userMessage);

        // 4. 交叉匹配（支持中文子串匹配，因为中文无空格分词）
        boolean matched = toolActionWords.stream().anyMatch(
                kw -> queryWords.contains(kw) || userMessage.contains(kw));

        if (!matched) {
            String reason = String.format(
                    "工具 [%s] 的能力 (%s) 与用户请求 \"%s\" 不匹配，可能选错了工具",
                    tool.getName(), tool.getCapabilities(), StringUtils.truncate(userMessage, 50));
            log.warn(reason);
            return Result.reject(reason);
        }

        return Result.pass();
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
