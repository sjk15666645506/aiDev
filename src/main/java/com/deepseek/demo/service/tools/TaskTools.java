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
        return "已创建任务【" + title + "】"
                + (assignee != null ? "，负责人 " + assignee : "")
                + (dueDate != null ? "，截止日期 " + dueDate : "");
    }
}
