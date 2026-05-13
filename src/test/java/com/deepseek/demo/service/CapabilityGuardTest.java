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
    void shouldRejectWhenCapabilityMismatches() throws NoSuchMethodException {
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
    void shouldPassWhenNoCapabilitiesDeclared() throws NoSuchMethodException {
        ToolMeta noCapTool = new ToolMeta(
                "simple_tool", "简单工具",
                List.of(), List.of(), ActionType.READ,
                ToolDomain.SEARCH,
                List.of(),
                null, Object.class.getMethod("toString")
        );

        CapabilityGuard.Result result = guard.validate("随便查查", noCapTool);
        assertTrue(result.isPassed());
    }
}
