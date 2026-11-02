package com.wangziyang.mes.manufacturing;

import com.wangziyang.mes.assistant.service.MaterialRoutingService;
import freemarker.template.Template;
import org.apache.shiro.subject.Subject;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.view.freemarker.FreeMarkerConfigurer;
import org.mybatis.spring.SqlSessionTemplate;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.util.*;
import static com.wangziyang.mes.manufacturing.BusinessQueryTest.params;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** Only the existing 3308 learning DB; all new fixture rows are transactionally rolled back. */
@RunWith(SpringRunner.class)
@SpringBootTest
@Transactional
public class ReadOnlyBusinessDatabaseIT {
    @Autowired private MasterDataQueryService master;
    @Autowired private WorkOrderService orders;
    @Autowired private OperationImpactService impact;
    @Autowired private RoutingComparisonService comparison;
    @Autowired private MaterialRoutingService routing;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private Environment environment;
    @Autowired private FreeMarkerConfigurer freemarker;
    @Autowired private SqlSessionTemplate sessions;

    @BeforeClass public static void explicitLocalOptIn() {
        assumeTrue("Enable explicitly with -Dmes.readonly.it.enabled=true",Boolean.getBoolean("mes.readonly.it.enabled"));
    }
    private void localOnly() {
        assertTrue(environment.getProperty("spring.datasource.url","").startsWith("jdbc:mysql://127.0.0.1:3308/mes_study?"));
        assertEquals("mes_study",jdbc.queryForObject("SELECT DATABASE()",String.class));
        assertEquals(Integer.valueOf(3308),jdbc.queryForObject("SELECT @@port",Integer.class));
    }
    private Subject reader() {
        Subject subject=mock(Subject.class); when(subject.isAuthenticated()).thenReturn(true); when(subject.isPermitted(anyString())).thenReturn(true); return subject;
    }

    @Test @Transactional(readOnly = true)
    public void existingLearningMaterialsSupportFuzzyMultipleCandidatesAndRealEmptyResults() {
        localOnly();
        Map<String,Object> existing=master.material("SERVO-DEMO-CTRL-001"); assertEquals("SUCCESS",existing.get("status"));
        Map<String,Object> fuzzy=master.searchMaterials(BusinessQuery.from(params("materialName","教学样例","pageSize","2")),null);
        assertEquals("SUCCESS",fuzzy.get("status")); assertEquals(2,((List<?>)fuzzy.get("materials")).size());
        assertEquals(true,fuzzy.get("hasMore")); assertEquals(true,fuzzy.get("requiresSelection"));
        assertEquals("NO_MATERIAL",master.searchMaterials(BusinessQuery.from(params("materialCode","R006-MISSING-"+UUID.randomUUID())),null).get("status"));
        assertEquals("NO_OPERATION",impact.query(BusinessQuery.from(params("operationName","老化测试")),reader()).get("status"));
    }

    @Test @Transactional(readOnly = true)
    public void existingServoOperationLinksActualCurrentMaterialAndItsFrozenOrder() {
        localOnly();
        Map<String,Object> result=impact.query(BusinessQuery.from(params("operationCode","SERVO-DEMO-OP50","state","CREATED")),reader());
        assertEquals("SUCCESS",result.get("status")); assertEquals("sample-servo-op-50",result.get("operationId"));
        Map<?,?> facts=(Map<?,?>)result.get("businessResults");
        assertEquals("SUCCESS",((Map<?,?>)facts.get("routing")).get("status"));
        assertTrue(facts.get("material").toString().contains("SERVO-DEMO-CTRL-001"));
        assertTrue(facts.get("workorder").toString().contains("WO-SERVO-DEMO-001"));
    }

    @Test @Transactional(readOnly = true)
    public void realStoredSnapshotCanBeComparedWithoutChangingTheOrderOrItsVersion() {
        localOnly();
        Map<String,Object> before=jdbc.queryForMap("SELECT route_snapshot_json,version,state FROM mes_work_order WHERE order_code='WO-SERVO-DEMO-001'");
        Map<String,Object> compared=comparison.compare("WO-SERVO-DEMO-001",reader());
        assertEquals("SUCCESS",compared.get("status")); assertEquals(false,compared.get("hasChanges"));
        assertEquals(6,((List<?>)compared.get("snapshotSteps")).size());
        assertEquals(before,jdbc.queryForMap("SELECT route_snapshot_json,version,state FROM mes_work_order WHERE order_code='WO-SERVO-DEMO-001'"));
    }

    @Test
    public void ownedTransactionalFixtureShowsRealChangesAndHistoricalMembershipSurvivesRouteRemoval() {
        localOnly(); Fixture fixture=fixture();
        orders.create(fixture.order,fixture.material,BigDecimal.ONE,"r006-it");
        String frozen=jdbc.queryForObject("SELECT route_snapshot_json FROM mes_work_order WHERE order_code=?",String.class,fixture.order);
        assertEquals(false,comparison.compare(fixture.order,reader()).get("hasChanges"));
        jdbc.update("DELETE FROM sp_flow_oper_relation WHERE id=?",fixture.relation);
        String replacement=UUID.randomUUID().toString(); insertOperation(replacement,"R006-NEW-"+replacement,"R006事务测试包装");
        insertRelation(UUID.randomUUID().toString(),fixture.route,fixture.route,replacement,"R006-NEW-"+replacement,1);
        // JDBC fixture edits do not invalidate MyBatis's session cache in this outer test transaction.
        sessions.clearCache();
        Map<String,Object> compared=comparison.compare(fixture.order,reader());
        assertEquals(true,compared.get("hasChanges")); assertEquals(2,((List<?>)compared.get("changes")).size());
        Map<String,Object> associated=impact.query(BusinessQuery.from(params("operationCode",fixture.operationCode,"materialCode",fixture.material)),reader());
        assertEquals("SUCCESS",associated.get("status"));
        Map<?,?> facts=(Map<?,?>)associated.get("businessResults");
        assertEquals("NO_MATERIAL",((Map<?,?>)facts.get("material")).get("status"));
        assertEquals("NO_ROUTE",((Map<?,?>)facts.get("routing")).get("status"));
        assertEquals(1,((List<?>)((Map<?,?>)facts.get("workorder")).get("orders")).size());
        assertEquals(frozen,jdbc.queryForObject("SELECT route_snapshot_json FROM mes_work_order WHERE order_code=?",String.class,fixture.order));
        assertEquals(Long.valueOf(0),jdbc.queryForObject("SELECT version FROM mes_work_order WHERE order_code=?",Long.class,fixture.order));
    }

    @Test public void ambiguousAndUnlinkedFixtureOperationsDoNotInventCurrentAssociations() {
        localOnly(); String name="R006候选"+UUID.randomUUID();
        String a=UUID.randomUUID().toString(),b=UUID.randomUUID().toString();
        insertOperation(a,"R006-"+a,name); insertOperation(b,"R006-"+b,name);
        assertEquals("AMBIGUOUS",impact.query(BusinessQuery.from(params("operationName",name)),reader()).get("status"));
        Map<String,Object> single=impact.query(BusinessQuery.from(params("operationCode","R006-"+a)),reader());
        assertEquals("SUCCESS",single.get("status"));
        for(Object fact:((Map<?,?>)single.get("businessResults")).values()) assertTrue(fact instanceof Map);
        assertEquals("NO_ROUTE",((Map<?,?>)((Map<?,?>)single.get("businessResults")).get("routing")).get("status"));
    }

    @Test public void likeWildcardsAreLiteralAndInvalidRoutesStillBlockTheExistingCreateService() {
        localOnly(); Fixture fixture=fixture();
        jdbc.update("UPDATE sp_materile SET materiel_desc=? WHERE materiel=?","R006-50%_!",fixture.material);
        Map<String,Object> searched=master.searchMaterials(BusinessQuery.from(params("materialName","50%_!")),null);
        assertEquals(1,((List<?>)searched.get("materials")).size());
        jdbc.update("UPDATE sp_materile SET flow_id=NULL WHERE materiel=?",fixture.material);
        sessions.clearCache();
        try { orders.create(fixture.order,fixture.material,BigDecimal.ONE,"r006-it"); fail("No-route creation must fail"); }
        catch(BusinessException expected) { assertEquals("NO_ROUTE",expected.getStatus()); }
        assertEquals(Integer.valueOf(0),jdbc.queryForObject("SELECT COUNT(*) FROM mes_work_order WHERE order_code=?",Integer.class,fixture.order));
    }

    @Test @Transactional(readOnly = true)
    public void originalFreeMarkerWorkbenchRendersBothNewReadFormsAndExistingWriteForms() throws Exception {
        localOnly(); Template template=freemarker.getConfiguration().getTemplate("admin/assistant/manufacturing.ftl");
        StringWriter html=new StringWriter(); template.process(Collections.singletonMap("request",Collections.singletonMap("contextPath","")),html);
        assertTrue(html.toString().contains("/operation-impact")); assertTrue(html.toString().contains("/orders/compare-routing"));
        assertTrue(html.toString().contains("/orders/transition")); assertTrue(html.toString().contains("data-method=\"POST\""));
    }

    private Fixture fixture() {
        Fixture f=new Fixture(); f.route=UUID.randomUUID().toString(); f.operation=UUID.randomUUID().toString(); f.relation=UUID.randomUUID().toString();
        f.material="R006-M-"+UUID.randomUUID(); f.order="R006-IT-"+UUID.randomUUID(); f.operationCode="R006-O-"+UUID.randomUUID();
        jdbc.update("INSERT INTO sp_flow (id,flow,flow_desc,create_time,create_username,update_time,update_username) VALUES (?,?,?,NOW(),'r006-it',NOW(),'r006-it')",f.route,f.route,"R006事务测试路线");
        insertOperation(f.operation,f.operationCode,"R006事务测试老化"); insertRelation(f.relation,f.route,f.route,f.operation,f.operationCode,1);
        jdbc.update("INSERT INTO sp_materile (id,materiel,materiel_desc,unit,mat_type,flow_id,is_deleted,create_time,create_username,update_time,update_username) VALUES (?,?,?,'PCS','FG',?,'0',NOW(),'r006-it',NOW(),'r006-it')",UUID.randomUUID().toString(),f.material,"R006事务测试控制器",f.route);
        assertEquals("SUCCESS",routing.query(f.material).get("status")); return f;
    }
    private void insertOperation(String id,String code,String name) {
        jdbc.update("INSERT INTO sp_oper (id,oper,oper_desc,create_time,create_username,update_time,update_username) VALUES (?,?,?,NOW(),'r006-it',NOW(),'r006-it')",id,code,name);
    }
    private void insertRelation(String id,String routeId,String routeCode,String operationId,String operationCode,int order) {
        jdbc.update("INSERT INTO sp_flow_oper_relation (id,flow_id,flow,oper_id,oper,sort_num,create_time,create_username,update_time,update_username) VALUES (?,?,?,?,?,?,NOW(),'r006-it',NOW(),'r006-it')",id,routeId,routeCode,operationId,operationCode,order);
    }
    private static class Fixture { String route,operation,relation,material,order,operationCode; }
}
