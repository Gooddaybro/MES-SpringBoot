package com.wangziyang.mes.manufacturing;

import com.wangziyang.mes.assistant.service.MaterialRoutingService;
import com.wangziyang.mes.system.entity.SysUser;
import com.wangziyang.mes.common.advice.ExceptionAdvice;
import org.apache.shiro.subject.Subject;
import org.apache.shiro.util.ThreadContext;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;

import static com.wangziyang.mes.manufacturing.MasterDataQueryServiceTest.row;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

public class ManufacturingControllerTest {
    private MasterDataQueryService master;
    private MaterialRoutingService routing;
    private WorkOrderService orders;
    private Subject subject;
    private MockMvc mvc;

    @Before public void setUp() {
        master = mock(MasterDataQueryService.class);
        routing = mock(MaterialRoutingService.class);
        orders = mock(WorkOrderService.class);
        subject = mock(Subject.class);
        when(subject.isAuthenticated()).thenReturn(true);
        when(subject.isPermitted(anyString())).thenReturn(true);
        when(subject.getPrincipal()).thenReturn("learner");
        ThreadContext.bind(subject);
        mvc = MockMvcBuilders.standaloneSetup(new ManufacturingController(master, routing, orders))
                .setControllerAdvice(new ExceptionAdvice()).build();
    }
    @After public void clearSubject() { ThreadContext.remove(); }

    @Test public void loginAndDomainPermissionPrecedeQueries() throws Exception {
        when(subject.isAuthenticated()).thenReturn(false);
        mvc.perform(get("/admin/manufacturing/material").param("materialCode", "MAT-001")).andExpect(status().isUnauthorized());
        when(subject.isAuthenticated()).thenReturn(true);
        when(subject.isPermitted("mes:assistant:material:read")).thenReturn(false);
        mvc.perform(get("/admin/manufacturing/material").param("materialCode", "MAT-001")).andExpect(status().isForbidden());
        verifyZeroInteractions(master, routing, orders);
    }

    @Test public void writePermissionIsIndependentFromRead() throws Exception {
        when(subject.isPermitted("mes:assistant:workorder:write")).thenReturn(false);
        mvc.perform(post("/admin/manufacturing/orders").contentType(MediaType.APPLICATION_JSON)
                .content("{\"orderCode\":\"WO-001\",\"materialCode\":\"MAT-001\",\"quantity\":10}"))
                .andExpect(status().isForbidden());
        verifyZeroInteractions(orders);
    }

    @Test public void createUsesAuthenticatedActorAndExactQuantity() throws Exception {
        when(orders.create("WO-001", "MAT-001", new BigDecimal("10.125"), "learner"))
                .thenReturn(row("status", "SUCCESS", "answer", "已创建"));
        mvc.perform(post("/admin/manufacturing/orders").contentType(MediaType.APPLICATION_JSON)
                .content("{\"orderCode\":\"WO-001\",\"materialCode\":\"MAT-001\",\"quantity\":\"10.125\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("SUCCESS"));
    }

    @Test public void extraFieldsAndInvalidQuantityNeverCallService() throws Exception {
        for(String body : new String[]{"{\"orderCode\":\"WO-001\",\"materialCode\":\"MAT-001\",\"quantity\":1,\"actor\":\"admin\"}",
                "{\"orderCode\":\"WO-001\",\"materialCode\":\"MAT-001\",\"quantity\":\"NaN\"}"}) {
            mvc.perform(post("/admin/manufacturing/orders").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
        verifyZeroInteractions(orders);
    }

    @Test public void realUserPrincipalUsesItsStableIdWithoutSerializingAccountFields() throws Exception {
        SysUser user = new SysUser(); user.setId("learner-id"); user.setUsername("learner");
        when(subject.getPrincipal()).thenReturn(user);
        when(orders.create("WO-001", "MAT-001", BigDecimal.ONE, "learner-id"))
                .thenReturn(row("status", "SUCCESS", "answer", "已创建"));
        mvc.perform(post("/admin/manufacturing/orders").contentType(MediaType.APPLICATION_JSON)
                .content("{\"orderCode\":\"WO-001\",\"materialCode\":\"MAT-001\",\"quantity\":1}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("SUCCESS"));
        verify(orders).create("WO-001", "MAT-001", BigDecimal.ONE, "learner-id");
    }

    @Test public void numericQuantityCannotRoundAnInvalidInputIntoAnAcceptedOne() throws Exception {
        for(String amount : new String[]{"0.00099999999999999999", "1.0000000000000001"}) {
            mvc.perform(post("/admin/manufacturing/orders").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"orderCode\":\"WO-001\",\"materialCode\":\"MAT-001\",\"quantity\":" + amount + "}"))
                    .andExpect(status().isBadRequest());
        }
        verifyZeroInteractions(orders);
    }

    @Test public void numericDecimalQuantityIsPreservedExactly() throws Exception {
        when(orders.create("WO-001", "MAT-001", new BigDecimal("10.125"), "learner"))
                .thenReturn(row("status", "SUCCESS", "answer", "已创建"));
        mvc.perform(post("/admin/manufacturing/orders").contentType(MediaType.APPLICATION_JSON)
                .content("{\"orderCode\":\"WO-001\",\"materialCode\":\"MAT-001\",\"quantity\":10.125}"))
                .andExpect(status().isOk());
        verify(orders).create("WO-001", "MAT-001", new BigDecimal("10.125"), "learner");
    }

    @Test public void invalidJsonKeepsASafe400EnvelopeWithTheRealGlobalAdviceInstalled() throws Exception {
        for(String body : new String[]{"{", "{\"orderCode\":\"WO-001\",\"materialCode\":\"MAT-001\",\"quantity\":\"NaN\"}",
                "{\"orderCode\":\"WO-001\",\"materialCode\":\"MAT-001\",\"quantity\":1,\"actor\":\"admin\"}"}) {
            mvc.perform(post("/admin/manufacturing/orders").contentType(MediaType.APPLICATION_JSON)
                    .header("X-Requested-With", "XMLHttpRequest").content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.data.status").value("INVALID_INPUT"))
                    .andExpect(jsonPath("$.data.answer").value("请求格式或字段无效，请检查工单参数。"));
        }
        verifyZeroInteractions(orders);
    }

    @Test public void businessConflictsAreSanitizedAndKeepTheirStatus() throws Exception {
        when(orders.transition("WO-001", "COMPLETE", "learner")).thenThrow(new BusinessException("CONFLICT", "请先开始工单"));
        mvc.perform(post("/admin/manufacturing/orders/transition").contentType(MediaType.APPLICATION_JSON)
                .content("{\"orderCode\":\"WO-001\",\"action\":\"COMPLETE\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.data.status").value("CONFLICT"));
    }

    @Test public void queriesForwardOnlyTheirOwnExplicitIdentifiers() throws Exception {
        when(master.operations("OP-10", "MAT-001")).thenReturn(row("status", "SUCCESS", "answer", "真实工序"));
        mvc.perform(get("/admin/manufacturing/operations").param("operationCode", "OP-10").param("materialCode", "MAT-001"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.answer").value("真实工序"));
        verify(master).operations("OP-10", "MAT-001");
    }
}
