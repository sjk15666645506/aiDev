package com.deepseek.demo.controller;

import com.deepseek.demo.dto.AgentResponse;
import com.deepseek.demo.dto.ConfirmationPoint;
import com.deepseek.demo.service.AgentService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.matchesRegex;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AgentController.class)
class AgentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AgentService agentService;

    // --- /api/agent/chat tests ---

    @Test
    void chat_ShouldReturnDone_WhenServiceReturnsDone() throws Exception {
        when(agentService.chat("conv-1", "Hello"))
                .thenReturn(AgentResponse.done("Hi there!"));

        mockMvc.perform(post("/api/agent/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"conversation_id\":\"conv-1\",\"message\":\"Hello\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("done"))
                .andExpect(jsonPath("$.reply").value("Hi there!"));
    }

    @Test
    void chat_ShouldReturnConfirmation_WhenServiceReturnsConfirmation() throws Exception {
        ConfirmationPoint cp = new ConfirmationPoint();
        cp.setConfirmationId("cnf-1");
        cp.setConversationId("conv-1");
        cp.setType("plan");
        cp.setReasoning("Need to search tickets");

        when(agentService.chat("conv-1", "search tickets"))
                .thenReturn(AgentResponse.waitConfirm(cp));

        mockMvc.perform(post("/api/agent/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"conversation_id\":\"conv-1\",\"message\":\"search tickets\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("confirmation"))
                .andExpect(jsonPath("$.confirmationPoint.confirmationId").value("cnf-1"))
                .andExpect(jsonPath("$.confirmationPoint.type").value("plan"));
    }

    @Test
    void chat_ShouldReturnError_WhenServiceReturnsError() throws Exception {
        when(agentService.chat("conv-1", "do something dangerous"))
                .thenReturn(AgentResponse.error("Operation rejected"));

        mockMvc.perform(post("/api/agent/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"conversation_id\":\"conv-1\",\"message\":\"do something dangerous\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("error"))
                .andExpect(jsonPath("$.reply").value("Operation rejected"));
    }

    @Test
    void chat_ShouldAutoGenerateConversationId_WhenNotProvided() throws Exception {
        when(agentService.chat(anyString(), eq("Hello")))
                .thenReturn(AgentResponse.done("Hi!"));

        mockMvc.perform(post("/api/agent/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"Hello\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("done"))
                .andExpect(jsonPath("$.conversationId").value(notNullValue()))
                .andExpect(jsonPath("$.conversationId").value(matchesRegex(
                        "[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")));
    }

    @Test
    void chat_ShouldReturnBadRequest_WhenMessageMissing() throws Exception {
        mockMvc.perform(post("/api/agent/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"conversation_id\":\"conv-1\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("error"))
                .andExpect(jsonPath("$.reply").value("message 不能为空"));
    }

    @Test
    void chat_ShouldAutoGenerateConversationId_WhenEmpty() throws Exception {
        when(agentService.chat(anyString(), eq("Hello")))
                .thenReturn(AgentResponse.done("Hi!"));

        mockMvc.perform(post("/api/agent/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"conversation_id\":\"\",\"message\":\"Hello\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("done"))
                .andExpect(jsonPath("$.conversationId").value(notNullValue()));
    }

    @Test
    void chat_ShouldReturnBadRequest_WhenMessageEmpty() throws Exception {
        mockMvc.perform(post("/api/agent/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"conversation_id\":\"conv-1\",\"message\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("error"))
                .andExpect(jsonPath("$.reply").value("message 不能为空"));
    }

    // --- /api/agent/confirm tests ---

    @Test
    void confirm_ShouldReturnDone_WhenServiceReturnsDone() throws Exception {
        when(agentService.confirm("conv-1", "cnf-1", true, null))
                .thenReturn(AgentResponse.done("Task completed"));

        mockMvc.perform(post("/api/agent/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"conversation_id\":\"conv-1\",\"confirmation_id\":\"cnf-1\",\"confirm\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("done"))
                .andExpect(jsonPath("$.reply").value("Task completed"));
    }

    @Test
    void confirm_ShouldReturnConfirmation_WhenServiceReturnsConfirmation() throws Exception {
        ConfirmationPoint cp = new ConfirmationPoint();
        cp.setConfirmationId("cnf-exec-1");
        cp.setConversationId("conv-1");
        cp.setType("exec");

        when(agentService.confirm("conv-1", "cnf-plan-1", true, null))
                .thenReturn(AgentResponse.waitConfirm(cp));

        mockMvc.perform(post("/api/agent/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"conversation_id\":\"conv-1\",\"confirmation_id\":\"cnf-plan-1\",\"confirm\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("confirmation"))
                .andExpect(jsonPath("$.confirmationPoint.confirmationId").value("cnf-exec-1"));
    }

    @Test
    void confirm_ShouldReturnError_WhenConversationIdMissing() throws Exception {
        mockMvc.perform(post("/api/agent/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"confirmation_id\":\"cnf-1\",\"confirm\":true}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("error"))
                .andExpect(jsonPath("$.reply").value("conversation_id 和 confirmation_id 不能为空"));
    }

    @Test
    void confirm_ShouldReturnError_WhenConfirmationIdMissing() throws Exception {
        mockMvc.perform(post("/api/agent/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"conversation_id\":\"conv-1\",\"confirm\":true}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("error"))
                .andExpect(jsonPath("$.reply").value("conversation_id 和 confirmation_id 不能为空"));
    }

    @Test
    void confirm_ShouldPassFeedback_WhenFeedbackProvided() throws Exception {
        when(agentService.confirm("conv-1", "cnf-1", true, "change assignee"))
                .thenReturn(AgentResponse.done("Adjusted and completed"));

        mockMvc.perform(post("/api/agent/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"conversation_id\":\"conv-1\",\"confirmation_id\":\"cnf-1\",\"confirm\":true,\"feedback\":\"change assignee\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("done"))
                .andExpect(jsonPath("$.reply").value("Adjusted and completed"));
    }

    @Test
    void confirm_ShouldPassConfirmFalse_WhenConfirmIsFalse() throws Exception {
        when(agentService.confirm("conv-1", "cnf-1", false, null))
                .thenReturn(AgentResponse.done("Cancelled"));

        mockMvc.perform(post("/api/agent/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"conversation_id\":\"conv-1\",\"confirmation_id\":\"cnf-1\",\"confirm\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("done"))
                .andExpect(jsonPath("$.reply").value("Cancelled"));
    }
}
