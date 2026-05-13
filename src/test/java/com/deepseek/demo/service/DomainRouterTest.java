package com.deepseek.demo.service;

import com.deepseek.demo.annotation.ToolDomain;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class DomainRouterTest {

    @MockBean
    private DeepSeekService deepSeekService;

    @Autowired
    private DomainRouter router;

    @Test
    void shouldClassifyTaskQuery() {
        when(deepSeekService.chatWithSystem(anyString(), anyString()))
                .thenReturn("TASK_MANAGEMENT");

        assertEquals(ToolDomain.TASK_MANAGEMENT,
                router.classify("帮我查一下张三的待办任务"));
    }

    @Test
    void shouldFallbackToSearchOnError() {
        when(deepSeekService.chatWithSystem(anyString(), anyString()))
                .thenThrow(new RuntimeException("API error"));

        assertEquals(ToolDomain.SEARCH,
                router.classify("任意消息"));
    }
}
