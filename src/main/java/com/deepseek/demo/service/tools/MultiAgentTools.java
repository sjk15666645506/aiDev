package com.deepseek.demo.service.tools;

import com.deepseek.demo.annotation.ActionType;
import com.deepseek.demo.annotation.Tool;
import com.deepseek.demo.annotation.ToolDomain;
import com.deepseek.demo.annotation.ToolParam;
import com.deepseek.demo.service.SubAgent;
import org.springframework.stereotype.Component;

/**
 * 多 Agent 协作工具集。
 * <p>
 * 提供 {@code delegate_task} 工具，供主 Agent 将子任务委派给
 * 指定领域的子 Agent 执行，实现多 Agent 分工协作。
 */
@Component
public class MultiAgentTools {

    private final SubAgent subAgent;

    public MultiAgentTools(SubAgent subAgent) {
        this.subAgent = subAgent;
    }

    /**
     * 将子任务委派给指定领域的专家 Agent 执行。
     * 当你遇到需要其他领域专业知识或技能来完成的任务时使用。
     * 子 Agent 会自主调用该领域内的工具并返回执行结果。
     *
     * @param domain 目标领域，可选值: TASK_MANAGEMENT, CODE_REPOSITORY, CI_CD,
     *               MONITORING, NOTIFICATION, DOCUMENT, SEARCH, USER_MANAGEMENT, FINANCE, SYSTEM
     * @param task   要委派给子 Agent 的详细任务描述，应包含所有必要的上下文信息
     * @return 子 Agent 执行结果
     */
    @Tool(
            name = "delegate_task",
            description = "将子任务委派给指定领域的专家 Agent 独立执行。当你遇到需要其他领域专业知识的复杂任务时使用。子 Agent 会自主调用工具并返回结果。",
            parameters = {
                    @ToolParam(name = "domain", type = "string",
                            description = "目标领域，可选值: TASK_MANAGEMENT, CODE_REPOSITORY, CI_CD, MONITORING, NOTIFICATION, DOCUMENT, SEARCH, USER_MANAGEMENT, FINANCE, SYSTEM",
                            required = true),
                    @ToolParam(name = "task", type = "string",
                            description = "要委派给子 Agent 的详细任务描述，包含上下文、目标和约束",
                            required = true)
            },
            action = ActionType.WRITE,
            domain = ToolDomain.SYSTEM,
            capabilities = {"system:delegate"}
    )
    public String delegateTask(String domain, String task) {
        return subAgent.execute(task, domain);
    }
}
