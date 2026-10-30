package com.wangziyang.mes.database;

import com.wangziyang.mes.assistant.service.MaterialRoutingService;
import com.wangziyang.mes.system.service.ISysMenuService;
import com.wangziyang.mes.system.service.ISysUserService;
import org.apache.shiro.authc.UsernamePasswordToken;
import org.apache.shiro.mgt.SecurityManager;
import org.apache.shiro.subject.Subject;
import org.apache.shiro.util.ThreadContext;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** Real Realm/services against a disposable database created by flyway_smoke.py. */
@RunWith(SpringRunner.class)
@SpringBootTest
public class FlywayBusinessDatabaseIT {
    @Autowired private Environment environment;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private SecurityManager securityManager;
    @Autowired private ISysUserService users;
    @Autowired private ISysMenuService menus;
    @Autowired private ApplicationContext context;
    @Autowired private MaterialRoutingService routing;

    @BeforeClass public static void explicitOptIn() {
        assumeTrue("Run with the disposable-db smoke harness", Boolean.getBoolean("mes.flyway.business.it"));
    }

    @Test public void migratedDatabaseSupportsRealLoginPermissionsNavigationAndBusinessQueries() throws Exception {
        String url = environment.getProperty("spring.datasource.url", "");
        assertTrue(url.matches("jdbc:mysql://127\\.0\\.0\\.1:(3306|3308)/mes_flyway_it_[a-f0-9]{32}\\?.*"));
        assertNotNull(users.getUserAndRoleAndMenuByUsername("admin"));
        Subject subject = new Subject.Builder(securityManager).buildSubject();
        ThreadContext.bind(subject);
        try {
            subject.login(new UsernamePasswordToken("admin", System.getenv("MES_BOOTSTRAP_PASSWORD")));
            assertTrue(subject.isAuthenticated());
            for (String permission : new String[]{"routing:read", "knowledge:read", "material:read", "operation:read", "workorder:read", "workorder:write"})
                assertTrue(permission, subject.isPermitted("mes:assistant:" + permission));
            assertFalse(subject.isPermitted("mes:assistant:knowledge:restricted"));
            assertFalse(menus.listIndexMenuTree().isEmpty());
            assertEquals(25, menus.listByRoleId("1185025876737396738").size());
            // R005 services may still be separate local work; keep this opt-in test independently compilable.
            Map<String,Object> material = ReflectionTestUtils.invokeMethod(context.getBean("masterDataQueryService"),
                    "material", "SERVO-DEMO-CTRL-001");
            assertEquals("SUCCESS", material.get("status"));
            Map<String,Object> route = routing.query("SERVO-DEMO-CTRL-001");
            assertEquals("SUCCESS", route.get("status"));
            assertEquals(6, ((List<?>) route.get("steps")).size());
            Map<String,Object> workOrder = ReflectionTestUtils.invokeMethod(context.getBean("workOrderService"),
                    "query", "WO-SERVO-DEMO-001", null);
            assertEquals("SUCCESS", workOrder.get("status"));
            assertEquals(Integer.valueOf(1), jdbc.queryForObject("SELECT COUNT(*) FROM mes_work_order", Integer.class));
        } finally { subject.logout(); ThreadContext.unbindSubject(); ThreadContext.unbindSecurityManager(); }
    }
}
