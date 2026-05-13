package com.deepseek.demo.service.tools;

import com.deepseek.demo.annotation.ActionType;
import com.deepseek.demo.annotation.Tool;
import com.deepseek.demo.annotation.ToolDomain;
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
          domain = ToolDomain.NOTIFICATION,
          capabilities = {"notify:send"},
          parameters = {
              @ToolParam(name = "webhook_url", type = "string",
                         description = "飞书机器人 Webhook 地址", required = true),
              @ToolParam(name = "content", type = "string",
                         description = "消息内容", required = true)
          },
          action = ActionType.WRITE)
    public String sendFeishuMessage(String webhookUrl, String content) {
        log.info("发送飞书消息: webhookUrl={}, content={}", webhookUrl, truncate(content, 50));
        return "飞书消息发送成功";
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return null;
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}
