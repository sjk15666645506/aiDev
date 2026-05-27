package com.deepseek.demo.service;

import com.deepseek.demo.annotation.ToolDomain;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class DomainRouterTest {

    @MockBean
    private ILlmService deepSeekService;

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
        verify(deepSeekService).chatWithSystem(anyString(), anyString());
    }

    @Test
    void shouldFallbackToSearchOnNullResponse() {
        when(deepSeekService.chatWithSystem(anyString(), anyString()))
                .thenReturn(null);

        assertEquals(ToolDomain.SEARCH, router.classify("任意消息"));
        verify(deepSeekService).chatWithSystem(anyString(), anyString());
    }

    @Test
    void shouldFallbackToSearchOnBlankResponse() {
        when(deepSeekService.chatWithSystem(anyString(), anyString()))
                .thenReturn("   ");

        assertEquals(ToolDomain.SEARCH, router.classify("任意消息"));
        verify(deepSeekService).chatWithSystem(anyString(), anyString());
    }

    @Test
    void shouldFallbackToSearchOnInvalidDomainResponse() {
        when(deepSeekService.chatWithSystem(anyString(), anyString()))
                .thenReturn("UNKNOWN_DOMAIN");

        assertEquals(ToolDomain.SEARCH, router.classify("任意消息"));
        verify(deepSeekService).chatWithSystem(anyString(), anyString());
    }

    @Test
    void shouldClassifyCiCdQuery() {
        when(deepSeekService.chatWithSystem(anyString(), anyString()))
                .thenReturn("CI_CD");

        assertEquals(ToolDomain.CI_CD,
                router.classify("把最新代码部署到测试环境"));
    }

    @Test
    void shouldClassifyCodeQuery() {
        when(deepSeekService.chatWithSystem(anyString(), anyString()))
                .thenReturn("CODE_REPOSITORY");

        assertEquals(ToolDomain.CODE_REPOSITORY,
                router.classify("查一下这个仓库的分支列表"));
    }

    @Test
    void shouldClassifySearchOnUnknownQuery() {
        when(deepSeekService.chatWithSystem(anyString(), anyString()))
                .thenReturn("SEARCH");

        assertEquals(ToolDomain.SEARCH,
                router.classify("今天天气怎么样"));
    }
}
