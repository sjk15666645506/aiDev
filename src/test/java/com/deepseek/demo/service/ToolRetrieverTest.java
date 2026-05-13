package com.deepseek.demo.service;

import com.deepseek.demo.annotation.ToolDomain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ToolRetrieverTest {

    @Mock
    private ToolRegistry toolRegistry;
    @Mock
    private ToolVectorStore vectorStore;
    @Mock
    private VectorService vectorService;

    private FrequencyTracker frequencyTracker;
    private ToolRetriever retriever;

    @BeforeEach
    void setUp() {
        frequencyTracker = new FrequencyTracker();
        retriever = new ToolRetriever(toolRegistry, vectorStore,
                vectorService, frequencyTracker);
    }

    @Test
    void shouldReturnEmptyWhenNoToolsInDomain() {
        when(toolRegistry.getByDomain(ToolDomain.TASK_MANAGEMENT))
                .thenReturn(Collections.emptyList());

        List<ToolMeta> result = retriever.retrieve(
                "查任务", ToolDomain.TASK_MANAGEMENT, 5);

        assertTrue(result.isEmpty());
    }
}
