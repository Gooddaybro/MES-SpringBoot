package com.wangziyang.mes.manufacturing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangziyang.mes.assistant.service.MaterialRoutingService;
import org.junit.Before;
import org.junit.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.*;

import static com.wangziyang.mes.manufacturing.MasterDataQueryServiceTest.row;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class WorkOrderServiceTest {
    private JdbcTemplate jdbc;
    private MaterialRoutingService routing;
    private WorkOrderService service;

    @Before public void setUp() {
        jdbc = mock(JdbcTemplate.class);
        routing = mock(MaterialRoutingService.class);
        service = new WorkOrderService(jdbc, routing, new ObjectMapper());
    }

    @Test public void invalidQuantityDoesNotQueryOrWrite() {
        for (String value : Arrays.asList("0", "-1", "0.0001", "1000000001")) {
            expect("INVALID_INPUT", () -> service.create("WO-001", "MAT-001", new BigDecimal(value), "learner"));
        }
        verifyZeroInteractions(jdbc, routing);
    }

    @Test public void invalidActionCannotReachSql() {
        expect("INVALID_INPUT", () -> service.transition("WO-001", "DELETE", "learner"));
        verifyZeroInteractions(jdbc, routing);
    }

    @Test public void invalidOrMissingRouteCannotCreateAnOrder() {
        when(jdbc.queryForList(anyString(), (Object[]) anyVararg())).thenReturn(Collections.emptyList());
        when(routing.query("MAT-001")).thenReturn(row("status", "NO_ROUTE", "answer", "未关联工艺路线"));
        expect("NO_ROUTE", () -> service.create("WO-001", "MAT-001", BigDecimal.ONE, "learner"));
        verify(jdbc, never()).update(anyString(), (Object[]) anyVararg());
    }

    @Test public void repeatedTransitionDoesNotChangeVersionOrWrite() {
        when(jdbc.queryForList(anyString(), (Object[]) anyVararg())).thenReturn(Collections.singletonList(order("IN_PROGRESS")));
        Map<String,Object> result = service.transition("WO-001", "START", "learner");
        assertEquals("SUCCESS", result.get("status"));
        assertEquals(Boolean.TRUE, result.get("duplicate"));
        verify(jdbc, never()).update(anyString(), (Object[]) anyVararg());
    }

    @Test public void completedAndCancelledOrdersCannotRestart() {
        for(String state : Arrays.asList("COMPLETED", "CANCELLED")) {
        when(jdbc.queryForList(anyString(), (Object[]) anyVararg())).thenReturn(Collections.singletonList(order(state)));
            expect("CONFLICT", () -> service.transition("WO-001", "START", "learner"));
        }
        verify(jdbc, never()).update(anyString(), (Object[]) anyVararg());
    }

    @Test public void duplicateCreateMustMatchTheStoredBusinessParameters() {
        when(jdbc.queryForList(anyString(), (Object[]) anyVararg())).thenReturn(Collections.singletonList(order("CREATED")));
        assertEquals(Boolean.TRUE, service.create("WO-001", "MAT-001", BigDecimal.ONE, "learner").get("duplicate"));
        assertEquals(Boolean.TRUE, service.create("WO-001", "mat-001", BigDecimal.ONE, "learner").get("duplicate"));
        expect("CONFLICT", () -> service.create("WO-001", "MAT-001", new BigDecimal("2"), "learner"));
        verifyZeroInteractions(routing);
        verify(jdbc, never()).update(anyString(), (Object[]) anyVararg());
    }

    @Test public void orderQueriesStayBounded() {
        List<Map<String,Object>> rows = new ArrayList<>();
        for(int i=0; i<21; i++) rows.add(order("CREATED"));
        when(jdbc.queryForList(anyString(), (Object[]) anyVararg())).thenReturn(rows);
        Map<String,Object> result = service.query(null, null);
        assertEquals(20, ((List<?>)result.get("orders")).size());
        assertEquals(Boolean.TRUE, result.get("truncated"));
    }

    private Map<String,Object> order(String state) {
        return row("id", "o1", "orderCode", "WO-001", "materialCode", "MAT-001", "plannedQuantity", BigDecimal.ONE,
                "state", state, "version", 1L, "routeSnapshotJson", "{\"steps\":[{\"code\":\"OP-10\"}]}");
    }

    private void expect(String status, Runnable action) {
        try { action.run(); fail("Expected " + status); }
        catch (BusinessException expected) { assertEquals(status, expected.getStatus()); }
    }
}
