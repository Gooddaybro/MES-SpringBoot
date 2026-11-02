package com.wangziyang.mes.manufacturing;

import org.apache.shiro.subject.Subject;
import org.junit.Before;
import org.junit.Test;
import java.util.*;
import static com.wangziyang.mes.manufacturing.BusinessQueryTest.params;
import static com.wangziyang.mes.manufacturing.MasterDataQueryServiceTest.row;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class OperationImpactServiceTest {
    private MasterDataQueryService master;
    private WorkOrderService orders;
    private Subject subject;
    private OperationImpactService service;
    @Before public void setUp() {
        master=mock(MasterDataQueryService.class); orders=mock(WorkOrderService.class); subject=mock(Subject.class);
        when(subject.isAuthenticated()).thenReturn(true); when(subject.isPermitted(anyString())).thenReturn(true);
        service=new OperationImpactService(master,orders);
    }

    @Test public void allFourPermissionsAreResolvedBeforeAnyQuery() {
        when(subject.isPermitted("mes:assistant:routing:read")).thenReturn(false);
        assertEquals("FORBIDDEN",service.query(BusinessQuery.from(params("operationName","老化测试")),subject).get("status"));
        verifyZeroInteractions(master,orders);
    }

    @Test public void ambiguousOperationStopsAllDependentQueriesEvenWhenRequestedPageIsLater() {
        when(master.searchOperations(any())).thenReturn(row("status","SUCCESS","operations",Arrays.asList(row("id","a","code","O-1"),row("id","b","code","O-2")),"hasMore",false));
        Map<String,Object> result=service.query(BusinessQuery.from(params("operationName","老化","page","3")),subject);
        assertEquals("AMBIGUOUS",result.get("status")); assertFalse(result.containsKey("operationId"));
        verify(master).searchOperations(argThat(q -> q.page==1 && q.pageSize==2));
        verify(master,never()).searchMaterials(any(),any()); verify(master,never()).searchRoutings(any(),any()); verifyZeroInteractions(orders);
    }

    @Test public void uniqueOperationUsesItsActualIdForEveryAssociationAndPreservesEmptyResults() {
        when(master.searchOperations(any())).thenReturn(row("status","SUCCESS","operations",Collections.singletonList(row("id","actual-op","code","AGE-1","name","老化测试")),"hasMore",false));
        when(master.searchMaterials(any(),eq("actual-op"))).thenReturn(row("status","NO_MATERIAL","materials",Collections.emptyList()));
        when(master.searchRoutings(any(),eq("actual-op"))).thenReturn(row("status","NO_ROUTE","routings",Collections.emptyList()));
        when(orders.search(any(),eq("actual-op"))).thenReturn(row("status","NO_WORK_ORDER","orders",Collections.emptyList()));
        Map<String,Object> result=service.query(BusinessQuery.from(params("operationName","老化测试","state","CREATED")),subject);
        assertEquals("SUCCESS",result.get("status")); assertEquals("actual-op",result.get("operationId"));
        Map<?,?> facts=(Map<?,?>)result.get("businessResults"); assertEquals(4,facts.size());
        assertEquals("NO_WORK_ORDER",((Map<?,?>)facts.get("workorder")).get("status"));
        verify(orders).search(argThat(q -> "CREATED".equals(q.state)),eq("actual-op"));
    }

    @Test public void missingOperationReturnsNoOperationWithoutInventingRelations() {
        when(master.searchOperations(any())).thenReturn(row("status","NO_OPERATION","operations",Collections.emptyList()));
        assertEquals("NO_OPERATION",service.query(BusinessQuery.from(params("operationName","不存在")),subject).get("status"));
        verify(master,never()).searchMaterials(any(),any()); verifyZeroInteractions(orders);
    }

    @Test public void materialCodeDoesNotRestrictHistoricalOperationResolutionToTodaysRoute() {
        when(master.searchOperations(any())).thenReturn(row("status","NO_OPERATION","operations",Collections.emptyList()));
        service.query(BusinessQuery.from(params("operationCode","OP-1","materialCode","MAT-1")),subject);
        verify(master).searchOperations(argThat(q -> q.materialCode==null && "OP-1".equals(q.operationCode)));
    }

    @Test public void materialNamesCannotSilentlyBroadenTheAssociatedOrderSet() {
        assertEquals("NEEDS_INPUT",service.query(BusinessQuery.from(params("operationName","老化","materialName","控制器")),subject).get("status"));
        verifyZeroInteractions(master,orders);
    }
}
