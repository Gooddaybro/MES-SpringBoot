package com.wangziyang.mes.manufacturing;

import com.wangziyang.mes.assistant.service.MaterialRoutingService;
import org.junit.Before;
import org.junit.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.*;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class MasterDataQueryServiceTest {
    private JdbcTemplate jdbc;
    private MaterialRoutingService routing;
    private MasterDataQueryService service;

    @Before public void setUp() {
        jdbc = mock(JdbcTemplate.class);
        routing = mock(MaterialRoutingService.class);
        service = new MasterDataQueryService(jdbc, routing);
    }

    @Test public void missingMaterialAndDuplicateCodesAreDistinguished() {
        when(jdbc.queryForList(anyString(), (Object[]) anyVararg())).thenReturn(Collections.emptyList());
        assertEquals("NO_MATERIAL", service.material("MAT-001").get("status"));
        when(jdbc.queryForList(anyString(), (Object[]) anyVararg())).thenReturn(Arrays.asList(row("id", "a"), row("id", "b")));
        assertEquals("AMBIGUOUS", service.material("MAT-001").get("status"));
    }

    @Test public void materialLookupKeepsDatabaseFactsAndBoundsLists() {
        Map<String,Object> material = row("id", "m1", "code", "MAT-001", "name", "控制器", "unit", "PCS");
        when(jdbc.queryForList(anyString(), (Object[]) anyVararg())).thenReturn(Collections.singletonList(material));
        Map<String,Object> result = service.material("MAT-001");
        assertEquals("SUCCESS", result.get("status"));
        assertEquals(material, result.get("material"));
        List<Map<String,Object>> many = new ArrayList<>();
        for (int i=0; i<21; i++) many.add(row("code", "MAT-"+i));
        when(jdbc.queryForList(anyString(), (Object[]) anyVararg())).thenReturn(many);
        result = service.material(null);
        assertEquals(20, ((List<?>)result.get("materials")).size());
        assertEquals(Boolean.TRUE, result.get("truncated"));
    }

    @Test public void invalidCodesDoNotReachTheDatabase() {
        try { service.material("x' OR 1=1"); fail("Invalid code must fail"); }
        catch (BusinessException expected) { assertEquals("INVALID_INPUT", expected.getStatus()); }
        verifyZeroInteractions(jdbc, routing);
    }

    @Test public void materialScopedOperationsKeepTheAuthoritativeRouteOrder() {
        Map<String,Object> first = row("code", "OP-10", "name", "装配", "order", 1);
        Map<String,Object> second = row("code", "OP-20", "name", "测试", "order", 2);
        when(routing.query("MAT-001")).thenReturn(row("status", "SUCCESS", "steps", Arrays.asList(first, second)));
        Map<String,Object> result = service.operations(null, "MAT-001");
        assertEquals(Arrays.asList(first, second), result.get("operations"));
        result = service.operations("OP-20", "MAT-001");
        assertEquals(Collections.singletonList(second), result.get("operations"));
        assertEquals(Collections.singletonList(second), service.operations("op-20", "MAT-001").get("operations"));
        verifyZeroInteractions(jdbc);
    }

    @Test public void routeFailuresCannotBecomeSuccessfulOperationLists() {
        when(routing.query("MAT-001")).thenReturn(row("status", "ROUTE_INCOMPLETE", "answer", "路线不完整"));
        assertEquals("ROUTE_INCOMPLETE", service.operations(null, "MAT-001").get("status"));
        verifyZeroInteractions(jdbc);
    }

    @Test public void operationDefinitionsAreBoundedAndNotExecutionRecords() {
        List<Map<String,Object>> rows = new ArrayList<>();
        for(int i=0; i<51; i++) rows.add(row("code", "OP-"+i));
        when(jdbc.queryForList(anyString(), (Object[]) anyVararg())).thenReturn(rows);
        Map<String,Object> result = service.operations(null, null);
        assertEquals(50, ((List<?>)result.get("operations")).size());
        assertEquals(Boolean.TRUE, result.get("truncated"));
    }

    static Map<String,Object> row(Object... pairs) {
        Map<String,Object> value = new LinkedHashMap<>();
        for(int i=0; i<pairs.length; i+=2) value.put((String)pairs[i], pairs[i+1]);
        return value;
    }
}
