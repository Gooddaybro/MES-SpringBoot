package com.wangziyang.mes.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangziyang.mes.system.config.shiro.SpLoginFormFilter;
import org.apache.shiro.session.Session;
import org.apache.shiro.subject.Subject;
import org.apache.shiro.util.ThreadContext;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.UUID;

import static org.junit.Assert.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class SpLoginFormFilterTest {
    private TestableLoginFilter filter;

    @Before
    public void setUp() {
        filter = new TestableLoginFilter();
        filter.setLoginUrl("/login-ui");
        Subject subject = mock(Subject.class);
        when(subject.getSession()).thenReturn(mock(Session.class));
        ThreadContext.bind(subject);
    }

    @After
    public void tearDown() {
        ThreadContext.unbindSubject();
    }

    @Test
    public void anonymousAssistantRequestsReceiveSameJson401WithOrWithoutAjaxHeader() throws Exception {
        for (String context : new String[]{"", "/mes"}) {
            for (String path : new String[]{"/admin/assistant", "/admin/assistant/chat", "/admin/assistant/material-routing"}) {
                for (boolean ajax : new boolean[]{false, true}) {
                    MockHttpServletRequest request = request(context, path, ajax);
                    request.setParameter("message", "private-question-not-for-logging");
                    MockHttpServletResponse response = new MockHttpServletResponse();
                    assertFalse(filter.deny(request, response));
                    assertEquals(401, response.getStatus());
                    assertNull(response.getRedirectedUrl());
                    assertTrue(response.getContentType().startsWith("application/json"));
                    JsonNode result = new ObjectMapper().readTree(response.getContentAsString());
                    assertEquals(1, result.path("code").asInt());
                    assertEquals("AUTH_REQUIRED", result.path("data").path("status").asText());
                    assertEquals("rule-based", result.path("data").path("mode").asText());
                    assertEquals("请先登录后使用助手。", result.path("data").path("answer").asText());
                    assertEquals(result.path("msg").asText(), result.path("data").path("answer").asText());
                    assertNotNull(UUID.fromString(result.path("data").path("traceId").asText()));
                    assertTrue(result.path("data").path("elapsedMs").isIntegralNumber());
                    assertTrue(result.path("data").path("elapsedMs").asLong() >= 0);
                    assertFalse(result.path("data").path("modelConnected").asBoolean(true));
                    assertEquals("RULE", result.path("data").path("recognitionSource").asText());
                    assertFalse(result.path("data").path("modelAttempted").asBoolean(true));
                    assertTrue(result.path("data").path("fallbackReason").isNull());
                    assertFalse(response.getContentAsString().contains("private-question-not-for-logging"));
                }
            }
        }
    }

    @Test
    public void nonAssistantAnonymousRequestsStillRedirectToLogin() throws Exception {
        for (String path : new String[]{"/admin/welcome-ui", "/admin/assistant-extra", "/admin/assistants/chat"}) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            assertFalse(filter.deny(request("", path, false), response));
            assertEquals(302, response.getStatus());
            assertEquals("/login-ui", response.getRedirectedUrl());
            assertEquals("", response.getContentAsString());
        }
    }

    @Test
    public void nonAssistantAnonymousAjaxRequestsStillReturnEmpty401() throws Exception {
        for (String path : new String[]{"/admin/welcome-ui", "/admin/assistant-extra", "/admin/assistants/chat"}) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            assertFalse(filter.deny(request("", path, true), response));
            assertEquals(401, response.getStatus());
            assertNull(response.getRedirectedUrl());
            assertEquals("", response.getContentAsString());
        }
    }

    private MockHttpServletRequest request(String context, String path, boolean ajax) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", context + path);
        request.setContextPath(context);
        if (ajax) {
            request.addHeader("X-Requested-With", "XMLHttpRequest");
        }
        return request;
    }

    private static class TestableLoginFilter extends SpLoginFormFilter {
        private boolean deny(MockHttpServletRequest request, MockHttpServletResponse response) throws Exception {
            return super.onAccessDenied(request, response);
        }
    }
}
