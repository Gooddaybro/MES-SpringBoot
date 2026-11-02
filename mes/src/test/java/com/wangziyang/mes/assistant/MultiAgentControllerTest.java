package com.wangziyang.mes.assistant;

import com.wangziyang.mes.assistant.agent.MultiAgentService;
import com.wangziyang.mes.assistant.controller.MultiAgentController;
import org.apache.shiro.subject.Subject;
import org.apache.shiro.util.ThreadContext;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

public class MultiAgentControllerTest {
    private MultiAgentService service;
    private Subject subject;
    private MockMvc mvc;
    @Before public void setUp() {
        service = mock(MultiAgentService.class); subject = mock(Subject.class); when(subject.isAuthenticated()).thenReturn(true); ThreadContext.bind(subject);
        mvc = MockMvcBuilders.standaloneSetup(new MultiAgentController(service)).build();
    }
    @After public void tearDown() { ThreadContext.unbindSubject(); }
    @Test public void loginRequiredBeforeServiceAndCallerRoleIsNotAnAuthorizationInput() throws Exception {
        when(subject.isAuthenticated()).thenReturn(false);
        mvc.perform(post("/admin/assistant/multi/chat").param("message", "物料").param("role", "admin"))
            .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.data.status").value("AUTH_REQUIRED"));
        verifyZeroInteractions(service);
    }
    @Test public void queryParametersReachServiceAndResponseGetsTrace() throws Exception {
        Map<String, Object> data = AssistantResponses.reply("SUCCESS", "multi-agent", "真实结果");
        when(service.chat("综合", "c", subject, "M1", "O1", "W1")).thenReturn(data);
        mvc.perform(post("/admin/assistant/multi/chat").param("message", "综合").param("conversationId", "c")
            .param("materialCode", "M1").param("operationCode", "O1").param("workOrderCode", "W1"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0)).andExpect(jsonPath("$.data.traceId").isString());
    }
    @Test public void structuredFailuresUseExistingEnvelopeAndAppropriateStatus() throws Exception {
        for (Object[] item : new Object[][]{{"INVALID_INPUT", 400}, {"FORBIDDEN", 403}, {"AGENT_UNAVAILABLE", 503}, {"TIMEOUT", 504}, {"INVALID_AGENT_RESPONSE", 502}}) {
            when(service.chat(eq("物料"), any(), eq(subject), any(), any(), any())).thenReturn(AssistantResponses.reply((String)item[0], "multi-agent", "请重试"));
            mvc.perform(post("/admin/assistant/multi/chat").param("message", "物料"))
                .andExpect(status().is((Integer)item[1])).andExpect(jsonPath("$.code").value(1));
        }
    }
}
