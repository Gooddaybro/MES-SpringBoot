package com.wangziyang.mes.manufacturing;

import com.wangziyang.mes.assistant.service.MaterialRoutingService;
import org.apache.shiro.subject.Subject;
import org.junit.Before;
import org.junit.Test;
import java.util.*;
import static com.wangziyang.mes.manufacturing.MasterDataQueryServiceTest.row;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class RoutingComparisonServiceTest {
    private WorkOrderService orders;
    private MaterialRoutingService routing;
    private Subject subject;
    private RoutingComparisonService service;
    private Map<String,Object> order;
    private Map<String,Object> snapshot;
    private Map<String,Object> current;

    @Before public void setUp() {
        orders=mock(WorkOrderService.class); routing=mock(MaterialRoutingService.class); subject=mock(Subject.class);
        when(subject.isAuthenticated()).thenReturn(true); when(subject.isPermitted(anyString())).thenReturn(true);
        service=new RoutingComparisonService(orders,routing);
        snapshot=row("material",row("id","m1","code","MAT-1"),"routing",row("id","r1","code","R-1"),
                "steps",new ArrayList<>(Arrays.asList(step("a",1,"O-A","装配"),step("b",2,"O-B","老化测试"))));
        current=row("status","SUCCESS","material",row("id","m1","code","MAT-1"),"routing",row("id","r1","code","R-1"),
                "steps",new ArrayList<>(Arrays.asList(step("a",1,"O-A","装配"),step("b",2,"O-B","老化测试"))));
        order=row("orderCode","WO-1","materialCode","MAT-1","state","CREATED","routeSnapshot",snapshot);
        when(orders.query("WO-1",null)).thenReturn(row("status","SUCCESS","workOrder",order));
        when(routing.query("MAT-1")).thenReturn(current);
    }

    @Test public void identicalStepsHaveNoConfirmedDifferenceButMissingParametersAreExplicit() {
        Map<String,Object> result=service.compare("WO-1",subject);
        assertEquals("SUCCESS",result.get("status")); assertEquals(false,result.get("hasChanges"));
        assertEquals(false,result.get("potentialProductionImpact")); assertEquals(Collections.emptyList(),result.get("changes"));
        assertTrue(result.get("limitations").toString().contains("参数"));
        assertTrue(result.get("limitations").toString().contains("发布"));
        assertEquals("CURRENT_ASSIGNED_ROUTE",result.get("comparisonBasis"));
        verify(orders,never()).create(any(),any(),any(),any()); verify(orders,never()).transition(any(),any(),any());
    }

    @Test public void additionsRemovalsReordersAndAvailableFieldsAreComparedByRealIdentity() {
        current.put("steps",Arrays.asList(step("b",1,"O-B2","老化测试新名称"),step("c",2,"O-C","包装")));
        Map<String,Object> result=service.compare("WO-1",subject);
        Set<String> types=types(result);
        assertEquals(new HashSet<>(Arrays.asList("REMOVED","ADDED","ORDER_CHANGED","CODE_CHANGED","NAME_CHANGED")),types);
        assertEquals(true,result.get("hasChanges")); assertEquals(true,result.get("potentialProductionImpact"));
        assertEquals(true,result.get("requiresProcessReview"));
        assertEquals("装配",((Map<?,?>)((List<?>)snapshot.get("steps")).get(0)).get("name"));
        assertEquals("CREATED",result.get("workOrderState")); assertEquals("WO-1",result.get("workOrderCode"));
    }

    @Test public void aNameChangeIsNotPresentedAsProvenProductionDamage() {
        current.put("steps",Arrays.asList(step("a",1,"O-A","装配名称修订"),step("b",2,"O-B","老化测试")));
        Map<String,Object> result=service.compare("WO-1",subject);
        assertEquals(Collections.singleton("NAME_CHANGED"),types(result));
        assertEquals(false,result.get("potentialProductionImpact")); assertEquals(true,result.get("requiresProcessReview"));
    }

    @Test public void repeatedOperationOccurrencesAreRetainedInsteadOfOverwrittenByAMap() {
        snapshot.put("steps",Arrays.asList(step("a",1,"O-A","装配"),step("a",2,"O-A","装配")));
        current.put("steps",Arrays.asList(step("a",1,"O-A","装配"),step("a",2,"O-A","装配")));
        assertEquals(false,service.compare("WO-1",subject).get("hasChanges"));
        current.put("steps",Collections.singletonList(step("a",1,"O-A","装配")));
        assertEquals(Collections.singleton("REMOVED"),types(service.compare("WO-1",subject)));
    }

    @Test public void changingAssignedRouteIsReportedEvenIfTheStepsMatch() {
        current.put("routing",row("id","r2","code","R-2"));
        assertTrue(types(service.compare("WO-1",subject)).contains("ROUTING_CHANGED"));
    }

    @Test public void absentOrBrokenHistoricalSnapshotIsNotRebuiltFromCurrentMasterData() {
        for (Object broken : Arrays.asList(null,Collections.emptyMap(),row("steps",Collections.emptyList()),
                row("material",row("code","OTHER"),"routing",row("id","r1","code","R-1"),"steps",snapshot.get("steps")))) {
            order.put("routeSnapshot",broken);
            assertEquals("SNAPSHOT_UNRELIABLE",service.compare("WO-1",subject).get("status"));
        }
        verifyZeroInteractions(routing);
    }

    @Test public void missingIdsAndInvalidOrDuplicateSequenceAreNotInterpretedAsRealChanges() {
        for (List<Map<String,Object>> broken : Arrays.asList(Collections.singletonList(step(null,1,"O-A","装配")),
                Collections.singletonList(step("a",0,"O-A","装配")),
                Arrays.asList(step("a",1,"O-A","装配"),step("b",1,"O-B","测试")))) {
            snapshot.put("steps",broken);
            assertEquals("SNAPSHOT_UNRELIABLE",service.compare("WO-1",subject).get("status"));
        }
        verifyZeroInteractions(routing);
    }

    @Test public void noCurrentRoutePreservesHistoryAndReportsAnUnavailableComparison() {
        when(routing.query("MAT-1")).thenReturn(row("status","NO_ROUTE","answer","当前物料无路线"));
        Map<String,Object> result=service.compare("WO-1",subject);
        assertEquals("NO_ROUTE",result.get("status")); assertEquals(false,result.get("comparisonAvailable"));
        assertEquals(snapshot.get("steps"),result.get("snapshotSteps")); assertFalse(result.containsKey("hasChanges"));
    }

    @Test public void authenticationAndBothReadPermissionsPrecedeAnyBusinessAccess() {
        when(subject.isAuthenticated()).thenReturn(false);
        assertEquals("AUTH_REQUIRED",service.compare("WO-1",subject).get("status"));
        when(subject.isAuthenticated()).thenReturn(true); when(subject.isPermitted("mes:assistant:routing:read")).thenReturn(false);
        assertEquals("FORBIDDEN",service.compare("WO-1",subject).get("status"));
        verifyZeroInteractions(orders,routing);
    }

    @Test public void missingOrderDoesNotQueryCurrentMaterial() {
        when(orders.query("WO-1",null)).thenReturn(row("status","NO_WORK_ORDER","answer","没有此工单"));
        assertEquals("NO_WORK_ORDER",service.compare("WO-1",subject).get("status")); verifyZeroInteractions(routing);
    }

    @Test public void reusingTheSameCodeForAnotherMaterialCannotProduceAFalseIdenticalComparison() {
        current.put("material",row("id","replacement-material","code","MAT-1"));
        Map<String,Object> result=service.compare("WO-1",subject);
        assertEquals("MATERIAL_IDENTITY_CHANGED",result.get("status")); assertEquals(false,result.get("comparisonAvailable"));
        assertFalse(result.containsKey("hasChanges"));
    }

    private static Map<String,Object> step(String id,int sequence,String code,String name) {
        return row("operationId",id,"order",sequence,"code",code,"name",name);
    }
    private static Set<String> types(Map<String,Object> result) {
        Set<String> resultTypes=new HashSet<>();
        for(Object item:(List<?>)result.get("changes")) resultTypes.add(String.valueOf(((Map<?,?>)item).get("type")));
        return resultTypes;
    }
}
