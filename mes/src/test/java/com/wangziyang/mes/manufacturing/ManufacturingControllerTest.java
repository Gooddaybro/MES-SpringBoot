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
    private OperationImpactService impact;
    private RoutingComparisonService comparison;
    private Subject subject;
    private MockMvc mvc;

    @Before public void setUp() {
        master = mock(MasterDataQueryService.class);
        routing = mock(MaterialRoutingService.class);
        orders = mock(WorkOrderService.class);
        impact = mock(OperationImpactService.class); comparison = mock(RoutingComparisonService.class);
        subject = mock(Subject.class);
        when(subject.isAuthenticated()).thenReturn(true);
        when(subject.isPermitted(anyString())).thenReturn(true);
        when(subject.getPrincipal()).thenReturn("learner");
        ThreadContext.bind(subject);
        mvc = MockMvcBuilders.standaloneSetup(new ManufacturingController(master, routing, orders, impact, comparison))
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

    @Test public void searchFiltersAndSnapshotComparisonHaveExplicitReadOnlyEndpoints() throws Exception {
        when(master.searchMaterials(any(),isNull())).thenReturn(row("status","SUCCESS","materials",java.util.Collections.emptyList(),"answer","查询结果"));
        mvc.perform(get("/admin/manufacturing/materials/search").param("materialName","控制器").param("materialStatus","2").param("pageSize","5"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("SUCCESS"));
        verify(master).searchMaterials(argThat(q -> "控制器".equals(q.materialName) && "2".equals(q.materialStatus) && q.pageSize==5),isNull());
        when(comparison.compare("WO-001",subject)).thenReturn(row("status","SUCCESS","hasChanges",false,"answer","相同"));
        mvc.perform(get("/admin/manufacturing/orders/compare-routing").param("workOrderCode","WO-001"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.hasChanges").value(false));
        verify(orders,never()).create(any(),any(),any(),any()); verify(orders,never()).transition(any(),any(),any());
    }

    @Test public void searchRejectsUnknownOrInvalidFieldsAndDeniedDomainsWithoutReadingData() throws Exception {
        mvc.perform(get("/admin/manufacturing/orders/search").param("pageSize","10000")).andExpect(status().isBadRequest());
        mvc.perform(get("/admin/manufacturing/materials/search").param("sql","SELECT 1")).andExpect(status().isBadRequest());
        when(subject.isPermitted("mes:assistant:workorder:read")).thenReturn(false);
        mvc.perform(get("/admin/manufacturing/orders/search")).andExpect(status().isForbidden());
        verifyZeroInteractions(master,orders);
    }
}
