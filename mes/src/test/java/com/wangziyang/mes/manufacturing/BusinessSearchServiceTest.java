package com.wangziyang.mes.manufacturing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangziyang.mes.assistant.service.MaterialRoutingService;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import java.sql.Timestamp;
import java.util.*;
import static com.wangziyang.mes.manufacturing.BusinessQueryTest.params;
import static com.wangziyang.mes.manufacturing.MasterDataQueryServiceTest.row;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class BusinessSearchServiceTest {
    private JdbcTemplate jdbc;
    private MaterialRoutingService routing;
    private MasterDataQueryService master;
    private WorkOrderService orders;
    @Before public void setUp() {
        jdbc=mock(JdbcTemplate.class); routing=mock(MaterialRoutingService.class);
        master=new MasterDataQueryService(jdbc,routing); orders=new WorkOrderService(jdbc,routing,new ObjectMapper());
        when(jdbc.queryForList(anyString(), (Object[])anyVararg())).thenReturn(Collections.emptyList());
    }

    @Test public void materialNameAndStatusAreBoundParametersAndPaginationDoesNotChooseACandidate() {
        List<Map<String,Object>> rows = Arrays.asList(row("id","a","code","M-1"),row("id","b","code","M-2"),row("id","c","code","M-3"));
        when(jdbc.queryForList(anyString(), (Object[])anyVararg())).thenReturn(rows);
        Map<String,Object> result = master.searchMaterials(BusinessQuery.from(params("materialName","50%控制器_","materialStatus","2","page","2","pageSize","2")),null);
        assertEquals("SUCCESS",result.get("status")); assertEquals(2,((List<?>)result.get("materials")).size());
        assertEquals(true,result.get("hasMore")); assertEquals(2,result.get("page")); assertFalse(result.containsKey("material"));
        ArgumentCaptor<String> sql=ArgumentCaptor.forClass(String.class); ArgumentCaptor<Object> args=ArgumentCaptor.forClass(Object.class);
        verify(jdbc).queryForList(sql.capture(),args.capture());
        assertTrue(sql.getValue().contains("LIKE ? ESCAPE '!'") && sql.getValue().contains("LIMIT ? OFFSET ?"));
        assertFalse(sql.getValue().contains("50%控制器"));
        assertEquals(Arrays.asList("2","%50!%控制器!_%",3,2),args.getAllValues());
    }

    @Test public void operationAndRoutingSearchKeepEmptyAssociationsEmpty() {
        Map<String,Object> operations=master.searchOperations(BusinessQuery.from(params("operationName","老化测试")));
        assertEquals("NO_OPERATION",operations.get("status")); assertEquals(Collections.emptyList(),operations.get("operations"));
        Map<String,Object> routes=master.searchRoutings(BusinessQuery.from(Collections.emptyMap()),"op-id");
        assertEquals("NO_ROUTE",routes.get("status")); assertEquals(Collections.emptyList(),routes.get("routings"));
        verifyZeroInteractions(routing);
    }

    @Test public void reverseMaterialsUseActualRouteAndOperationIdsWithoutDuplicateJoinRows() {
        master.searchMaterials(BusinessQuery.from(Collections.emptyMap()),"op-id");
        ArgumentCaptor<String> sql=ArgumentCaptor.forClass(String.class); ArgumentCaptor<Object> args=ArgumentCaptor.forClass(Object.class);
        verify(jdbc).queryForList(sql.capture(),args.capture());
        assertTrue(sql.getValue().contains("EXISTS") && sql.getValue().contains("r.oper_id=?") && sql.getValue().contains("m.flow_id"));
        assertTrue(args.getAllValues().contains("op-id"));
    }

    @Test public void orderFiltersUseTheFrozenSnapshotAndNotTodaysMasterRoute() {
        Map<String,Object> result=orders.search(BusinessQuery.from(params("materialCode","M-1","state","CREATED","createdFrom","2026-11-01","createdTo","2026-11-03")),"op-id");
        assertEquals("NO_WORK_ORDER",result.get("status")); assertEquals(Collections.emptyList(),result.get("orders"));
        ArgumentCaptor<String> sql=ArgumentCaptor.forClass(String.class); ArgumentCaptor<Object> args=ArgumentCaptor.forClass(Object.class);
        verify(jdbc).queryForList(sql.capture(),args.capture());
        assertTrue(sql.getValue().contains("JSON_CONTAINS(route_snapshot_json,JSON_OBJECT('operationId',?),'$.steps')"));
        assertFalse(sql.getValue().contains("sp_flow"));
        assertTrue(sql.getValue().contains("created_at>=?") && sql.getValue().contains("created_at<?"));
        assertTrue(args.getAllValues().contains(Timestamp.valueOf("2026-11-01 00:00:00")));
        verifyZeroInteractions(routing);
    }
}
