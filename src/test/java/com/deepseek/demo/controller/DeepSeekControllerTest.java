package com.deepseek.demo.controller;

import com.deepseek.demo.service.ILlmService;
import com.deepseek.demo.service.GeneralRagService;
import com.deepseek.demo.service.VectorService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(DeepSeekController.class)
class DeepSeekControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ILlmService deepSeekService;

    @MockBean
    private GeneralRagService generalRagService;

    @MockBean
    private VectorService vectorService;

    @Test
    void chat_ShouldReturnReply_WhenMessageProvided() throws Exception {
        when(deepSeekService.chat("Hello")).thenReturn("Hi there!");

        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"Hello\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reply").value("Hi there!"));
    }

    @Test
    void chat_ShouldReturnNullReply_WhenServiceReturnsNull() throws Exception {
        when(deepSeekService.chat("Hello")).thenReturn(null);

        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"Hello\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reply").isEmpty());
    }

    @Test
    void chatWithSystem_ShouldReturnReply_WhenSystemAndMessageProvided() throws Exception {
        when(deepSeekService.chatWithSystem("Be concise", "Tell me about Java"))
                .thenReturn("Java is a programming language.");

        mockMvc.perform(post("/api/chat/system")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"system\":\"Be concise\",\"message\":\"Tell me about Java\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reply").value("Java is a programming language."));
    }

    @Test
    void chatWithSystem_ShouldReturnNullReply_WhenServiceReturnsNull() throws Exception {
        when(deepSeekService.chatWithSystem(anyString(), anyString())).thenReturn(null);

        mockMvc.perform(post("/api/chat/system")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"system\":\"Be concise\",\"message\":\"Tell me about Java\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reply").doesNotExist());
    }
}
