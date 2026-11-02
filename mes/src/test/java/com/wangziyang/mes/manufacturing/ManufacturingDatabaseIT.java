package com.wangziyang.mes.manufacturing;

import com.wangziyang.mes.assistant.service.MaterialRoutingService;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** Real isolated-learning-DB integration. Transactional fixtures roll back; concurrency owns one exact temporary order. */
@RunWith(SpringRunner.class)
@SpringBootTest
public class ManufacturingDatabaseIT {
    private static final String MATERIAL = "SERVO-DEMO-CTRL-001";
    @Autowired private MasterDataQueryService master;
    @Autowired private MaterialRoutingService routing;
    @Autowired private WorkOrderService orders;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private Environment environment;

    @BeforeClass public static void explicitLocalOptIn() {
        assumeTrue("Enable explicitly with -Dmes.manufacturing.it.enabled=true", Boolean.getBoolean("mes.manufacturing.it.enabled"));
    }

    private void localOnly() {
        String url = environment.getProperty("spring.datasource.url", "");
        assertTrue("Test may only use the isolated local learning DB", url.startsWith("jdbc:mysql://127.0.0.1:3308/mes_study?"));
        assertEquals("mes_study", jdbc.queryForObject("SELECT DATABASE()", String.class));
        assertEquals(Integer.valueOf(3308), jdbc.queryForObject("SELECT @@port", Integer.class));
    }

    @Test @Transactional
    @SuppressWarnings("unchecked")
    public void masterQueriesMatchRealMaterialAndOrderedOperations() {
        localOnly();
        Map<String,Object> result = master.material(MATERIAL);
        assertEquals("SUCCESS", result.get("status"));
        assertEquals("sample-servo-mat-controller", ((Map<?,?>)result.get("material")).get("id"));
        List<Map<String,Object>> actual = (List<Map<String,Object>>)master.operations(null, MATERIAL).get("operations");
        assertEquals(6, actual.size());
        for(int i=0; i<actual.size(); i++) assertEquals(i+1, ((Number)actual.get(i).get("order")).intValue());
        String operationCode = String.valueOf(actual.get(2).get("code"));
        assertEquals(1, ((List<?>)master.operations(operationCode, MATERIAL).get("operations")).size());
    }

    @Test @Transactional
    @SuppressWarnings("unchecked")
    public void createFreezesTheRouteAndRepeatDoesNotRewriteIt() {
        localOnly();
        String code = freshCode();
        Map<String,Object> created = orders.create(code, MATERIAL, new BigDecimal("10.125"), "r005-integration");
        Map<String,Object> first = (Map<String,Object>)created.get("workOrder");
        assertEquals(Boolean.FALSE, created.get("duplicate"));
        Map<String,Object> snapshot = (Map<String,Object>)first.get("routeSnapshot");
        List<Map<String,Object>> steps = (List<Map<String,Object>>)snapshot.get("steps");
        String oldName = String.valueOf(steps.get(0).get("name"));
        jdbc.update("UPDATE sp_oper SET oper_desc=? WHERE id=?", "temporary integration change", steps.get(0).get("operationId"));
        assertNotEquals(oldName, jdbc.queryForObject("SELECT oper_desc FROM sp_oper WHERE id=?", String.class, steps.get(0).get("operationId")));
        Map<String,Object> queried = (Map<String,Object>)orders.query(code, null).get("workOrder");
        assertEquals(snapshot, queried.get("routeSnapshot"));
        Map<String,Object> repeated = orders.create(code, MATERIAL, new BigDecimal("10.125"), "r005-integration");
        assertEquals(Boolean.TRUE, repeated.get("duplicate"));
        assertEquals(first.get("id"), ((Map<?,?>)repeated.get("workOrder")).get("id"));
        assertEquals(Integer.valueOf(1), jdbc.queryForObject("SELECT COUNT(*) FROM mes_work_order WHERE order_code=?", Integer.class, code));
        expect("CONFLICT", () -> orders.create(code, MATERIAL, BigDecimal.ONE, "r005-integration"));
    }

    @Test @Transactional
    @SuppressWarnings("unchecked")
    public void lifecycleRejectsIllegalTransitionsAndRepeatedActionsAreIdempotent() {
        localOnly();
        String code = freshCode();
        orders.create(code, MATERIAL, BigDecimal.TEN, "r005-integration");
        expect("CONFLICT", () -> orders.transition(code, "COMPLETE", "r005-integration"));
        Map<String,Object> started = orders.transition(code, "START", "r005-integration");
        Map<?,?> order = (Map<?,?>)started.get("workOrder");
        assertEquals("IN_PROGRESS", order.get("state"));
        assertEquals(1L, ((Number)order.get("version")).longValue());
        Map<String,Object> repeated = orders.transition(code, "START", "r005-integration");
        assertEquals(Boolean.TRUE, repeated.get("duplicate"));
        assertEquals(1L, ((Number)((Map<?,?>)repeated.get("workOrder")).get("version")).longValue());
        expect("CONFLICT", () -> orders.transition(code, "CANCEL", "r005-integration"));
        order = (Map<?,?>)orders.transition(code, "COMPLETE", "r005-integration").get("workOrder");
        assertEquals("COMPLETED", order.get("state"));
        assertEquals(2L, ((Number)order.get("version")).longValue());
        assertNotNull(order.get("completedAt"));
        expect("CONFLICT", () -> orders.transition(code, "START", "r005-integration"));
    }

    @Test @Transactional
    public void missingRouteAndCancelledOrderDoNotInventExecution() {
        localOnly();
        String missing = freshCode();
        expect("NO_ROUTE", () -> orders.create(missing, "SERVO-DEMO-PCBA-001", BigDecimal.ONE, "r005-integration"));
        assertEquals(Integer.valueOf(0), jdbc.queryForObject("SELECT COUNT(*) FROM mes_work_order WHERE order_code=?", Integer.class, missing));
        String code = freshCode();
        orders.create(code, MATERIAL, BigDecimal.ONE, "r005-integration");
        orders.transition(code, "CANCEL", "r005-integration");
        expect("CONFLICT", () -> orders.transition(code, "START", "r005-integration"));
        expect("CONFLICT", () -> orders.transition(code, "COMPLETE", "r005-integration"));
    }

    @Test @Transactional
    public void caseInsensitiveMaterialLookupKeepsTheCanonicalIdentityInOrders() {
        localOnly();
        String code = freshCode();
        Map<String,Object> created = orders.create(code, MATERIAL.toLowerCase(Locale.ROOT), BigDecimal.ONE, "r005-integration");
        assertEquals(MATERIAL, ((Map<?,?>)created.get("workOrder")).get("materialCode"));
        assertEquals("SUCCESS", orders.query(code, MATERIAL).get("status"));
        assertEquals("SUCCESS", orders.query(code, MATERIAL.toLowerCase(Locale.ROOT)).get("status"));
        assertEquals(Boolean.TRUE, orders.create(code, MATERIAL, BigDecimal.ONE, "r005-integration").get("duplicate"));
    }

    @Test
    public void concurrentCreatesProduceExactlyOnePersistentOrder() throws Exception {
        localOnly();
        String code = freshCode();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Callable<Map<String,Object>> create = () -> { start.await(); return orders.create(code, MATERIAL, BigDecimal.TEN, "r005-integration"); };
            Future<Map<String,Object>> one = executor.submit(create), two = executor.submit(create);
            start.countDown();
            Map<String,Object> first = one.get(20, TimeUnit.SECONDS), second = two.get(20, TimeUnit.SECONDS);
            assertEquals("SUCCESS", first.get("status")); assertEquals("SUCCESS", second.get("status"));
            assertNotEquals(first.get("duplicate"), second.get("duplicate"));
            assertEquals(((Map<?,?>)first.get("workOrder")).get("id"), ((Map<?,?>)second.get("workOrder")).get("id"));
            assertEquals(Integer.valueOf(1), jdbc.queryForObject("SELECT COUNT(*) FROM mes_work_order WHERE order_code=?", Integer.class, code));
        } finally {
            executor.shutdownNow(); executor.awaitTermination(5, TimeUnit.SECONDS);
            jdbc.update("DELETE FROM mes_work_order WHERE order_code=? AND created_by='r005-integration'", code);
        }
    }

    private String freshCode() { return "R005-IT-" + UUID.randomUUID().toString(); }
    private void expect(String status, Runnable action) {
        try { action.run(); fail("Expected " + status); }
        catch (BusinessException expected) { assertEquals(status, expected.getStatus()); }
    }
}
