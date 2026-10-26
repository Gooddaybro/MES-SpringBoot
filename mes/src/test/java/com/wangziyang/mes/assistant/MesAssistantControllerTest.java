package com.wangziyang.mes.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangziyang.mes.assistant.service.MaterialRoutingService;
import com.wangziyang.mes.assistant.service.MesAssistantService;
import com.wangziyang.mes.assistant.controller.MesAssistantController;
import com.wangziyang.mes.system.entity.SysUser;
import org.apache.shiro.subject.Subject;
import org.apache.shiro.util.ThreadContext;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionTimedOutException;

import java.sql.SQLTimeoutException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

public class MesAssistantControllerTest {
    private static final String PERMISSION = "mes:assistant:routing:read";
    private MaterialRoutingService routing;
    private Subject subject;
    private MockMvc mvc;

    @Before
    public void setUp() throws Exception {
        routing = mock(MaterialRoutingService.class);
        subject = mock(Subject.class);
        when(subject.isAuthenticated()).thenReturn(true);
        when(subject.isPermitted(PERMISSION)).thenReturn(true);
        ThreadContext.bind(subject);
        mvc = MockMvcBuilders.standaloneSetup(new MesAssistantController(new MesAssistantService(routing), routing)).build();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "SUCCESS");
        result.put("mode", "database");
        result.put("answer", "查到现有工艺路线。");
        result.put("tool", "get_material_routing");
        when(routing.query(anyString())).thenReturn(result);
    }

    @After
    public void tearDown() {
        ThreadContext.unbindSubject();
    }

    @Test
    public void unauthenticatedChatAndToolReturn401BeforeAnyRead() throws Exception {
        when(subject.isAuthenticated()).thenReturn(false);
        mvc.perform(post("/admin/assistant/chat").param("message", "100112"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.data.status").value("AUTH_REQUIRED"));
        mvc.perform(get("/admin/assistant/material-routing").param("materialCode", "100112"))
                .andExpect(status().isUnauthorized());
        verify(subject, never()).isPermitted(anyString());
        verifyZeroInteractions(routing);
    }

    @Test
    public void dedicatedPermissionIsRequiredForBothEndpoints() throws Exception {
        when(subject.isPermitted(PERMISSION)).thenReturn(false);
        when(subject.isPermitted("user:add")).thenReturn(true);
        SysUser admin = new SysUser();
        admin.setUsername("admin");
        when(subject.getPrincipal()).thenReturn(admin);
        mvc.perform(post("/admin/assistant/chat").param("message", "100112"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.data.status").value("FORBIDDEN"));
        mvc.perform(get("/admin/assistant/material-routing").param("materialCode", "100112"))
                .andExpect(status().isForbidden());
        verify(subject, never()).isPermitted("user:add");
        verifyZeroInteractions(routing);
    }

    @Test
    public void authorizedChatIncludesTraceTimingAndModelDisclosure() throws Exception {
        MvcResult response = mvc.perform(post("/admin/assistant/chat").param("message", "查100112的工艺路线"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.mode").value("database"))
                .andExpect(jsonPath("$.data.modelConnected").value(false)).andReturn();
        JsonNode body = new ObjectMapper().readTree(response.getResponse().getContentAsString());
        assertNotNull(UUID.fromString(body.path("data").path("traceId").asText()));
        assertTrue(body.path("data").path("elapsedMs").asLong() >= 0);
        verify(routing).query("100112");
    }

    @Test
    public void directToolSupportsFullValidCode() throws Exception {
        String code = "A_1-2/3.4";
        mvc.perform(get("/admin/assistant/material-routing").param("materialCode", code))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.tool").value("get_material_routing"));
        verify(routing).query(code);
    }

    @Test
    public void missingEmptyAndOverlongChatParametersReturn400() throws Exception {
        mvc.perform(post("/admin/assistant/chat")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.status").value("INVALID_INPUT"));
        for (String message : new String[]{"", " ", String.join("", Collections.nCopies(1001, "a"))}) {
            mvc.perform(post("/admin/assistant/chat").param("message", message))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(1));
        }
        verifyZeroInteractions(routing);
    }

    @Test
    public void toolRejectsMissingMalformedAndOverlongCodesBeforeRead() throws Exception {
        mvc.perform(get("/admin/assistant/material-routing")).andExpect(status().isBadRequest());
        for (String code : new String[]{"", " 100112", "100112 ", "A@B", "中文", String.join("", Collections.nCopies(256, "A"))}) {
            mvc.perform(get("/admin/assistant/material-routing").param("materialCode", code))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.data.status").value("INVALID_INPUT"));
        }
        verifyZeroInteractions(routing);
    }

    @Test
    public void overlongExtractedCodeReturns400WithoutQueryingPrefix() throws Exception {
        mvc.perform(post("/admin/assistant/chat").param("message", "查" + String.join("", Collections.nCopies(256, "A")) + "工艺路线"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.data.status").value("INVALID_INPUT"));
        verifyZeroInteractions(routing);
    }

    @Test
    public void noDataDomainStateRetainsCodeZeroWithHonestMessage() throws Exception {
        Map<String, Object> noData = new LinkedHashMap<>();
        noData.put("status", "NO_MATERIAL");
        noData.put("mode", "database");
        noData.put("answer", "没有找到正常可用物料。");
        when(routing.query("404")).thenReturn(noData);
        mvc.perform(get("/admin/assistant/material-routing").param("materialCode", "404"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.msg").value("没有找到正常可用物料。"));
    }

    @Test
    public void writeRequestIsUnsupportedAndNeverReachesTool() throws Exception {
        mvc.perform(post("/admin/assistant/chat").param("message", "删除100112的工艺路线"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.status").value("UNSUPPORTED"));
        verifyZeroInteractions(routing);
    }

    @Test
    public void databaseFailureReturnsGeneric503WithoutSqlOrSecrets() throws Exception {
        when(routing.query(anyString())).thenThrow(new DataAccessResourceFailureException("SELECT secret FROM production password=secret"));
        MvcResult result = mvc.perform(post("/admin/assistant/chat").param("message", "100112"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.data.status").value("DATA_UNAVAILABLE")).andReturn();
        assertFalse(result.getResponse().getContentAsString().contains("secret"));
        assertFalse(result.getResponse().getContentAsString().contains("SELECT"));
    }

    @Test
    public void databaseErrorsRetainToolAndSourceEvidence() throws Exception {
        when(routing.query(anyString())).thenThrow(new DataAccessResourceFailureException("private error"));
        mvc.perform(get("/admin/assistant/material-routing").param("materialCode", "100112"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.data.tool").value("get_material_routing"))
                .andExpect(jsonPath("$.data.source").value("sp_materile → sp_flow → sp_flow_oper_relation → sp_oper"))
                .andExpect(jsonPath("$.data.queriedAt").isString());
    }

    @Test
    public void transactionFailureReturns503() throws Exception {
        when(routing.query(anyString())).thenThrow(new CannotCreateTransactionException("credentials"));
        mvc.perform(get("/admin/assistant/material-routing").param("materialCode", "100112"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.data.status").value("DATA_UNAVAILABLE"));
    }

    @Test
    public void queryAndTransactionTimeoutReturn504() throws Exception {
        when(routing.query(anyString())).thenThrow(new QueryTimeoutException("raw SQL"));
        mvc.perform(get("/admin/assistant/material-routing").param("materialCode", "100112"))
                .andExpect(status().isGatewayTimeout()).andExpect(jsonPath("$.data.status").value("TIMEOUT"));
        reset(routing);
        when(routing.query(anyString())).thenThrow(new TransactionTimedOutException("raw SQL"));
        mvc.perform(post("/admin/assistant/chat").param("message", "100112"))
                .andExpect(status().isGatewayTimeout());
    }

    @Test
    public void wrappedSqlTimeoutReturns504() throws Exception {
        when(routing.query(anyString())).thenThrow(new DataAccessResourceFailureException("raw SQL", new SQLTimeoutException("raw SQL")));
        mvc.perform(get("/admin/assistant/material-routing").param("materialCode", "100112"))
                .andExpect(status().isGatewayTimeout()).andExpect(jsonPath("$.data.status").value("TIMEOUT"));
    }

    @Test
    public void unexpectedFailureReturnsGeneric500() throws Exception {
        when(routing.query(anyString())).thenThrow(new IllegalStateException("internal-secret"));
        MvcResult result = mvc.perform(get("/admin/assistant/material-routing").param("materialCode", "100112"))
                .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.data.status").value("ERROR"))
                .andExpect(jsonPath("$.data.modelConnected").value(false)).andReturn();
        assertFalse(result.getResponse().getContentAsString().contains("internal-secret"));
    }
}
