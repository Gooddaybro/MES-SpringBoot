package com.wangziyang.mes.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.shiro.authc.UsernamePasswordToken;
import org.apache.shiro.mgt.SecurityManager;
import org.apache.shiro.subject.Subject;
import org.apache.shiro.util.ThreadContext;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.mock.web.MockFilterConfig;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import javax.servlet.http.Cookie;
import javax.servlet.ServletContext;
import javax.sql.DataSource;
import java.sql.Connection;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Opt-in local SQL / mapper / service / secured HTTP integration evidence.
 * Authentication uses the real realm in this test fixture; it does not verify CAPTCHA or page login.
 */
@RunWith(SpringRunner.class)
@SpringBootTest
@AutoConfigureMockMvc
public class MesAssistantDatabaseIT {
    @Autowired
    private MockMvc mvc;
    @Autowired
    private SecurityManager securityManager;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private DataSource dataSource;
    @Autowired
    private Environment environment;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private ServletContext servletContext;
    @Autowired
    @Qualifier("webStatFilterRegistrationBean")
    private FilterRegistrationBean<?> druidWebFilter;

    @BeforeClass
    public static void requireExplicitOptIn() {
        assumeTrue("Enable this local-only fixture with -Dmes.it.enabled=true", Boolean.getBoolean("mes.it.enabled"));
    }

    @Test
    @Transactional(readOnly = true)
    public void realRealmFixtureAuthenticationAndReadOnlyRoutingMatchLocalSql() throws Exception {
        assertLocalDatabase(environment.getProperty("spring.datasource.url"));
        Connection connection = DataSourceUtils.getConnection(dataSource);
        assertLocalDatabase(connection.getMetaData().getURL());
        assertTrue("The database fixture must use a read-only JDBC transaction", connection.isReadOnly());
        assertEquals("The database must enforce read-only transactions", Integer.valueOf(1),
                jdbc.queryForObject("SELECT @@transaction_read_only", Integer.class));
        // MockMvc does not run servlet-container initialization for the registered Druid statistics filter.
        MockFilterConfig filterConfig = new MockFilterConfig(servletContext);
        druidWebFilter.getInitParameters().forEach(filterConfig::addInitParameter);
        druidWebFilter.getFilter().init(filterConfig);

        Map<String, List<Map<String, Object>>> before = businessSnapshot();
        Map<String, Object> material = existingCompleteMaterial();
        String code = (String) material.get("material_code");
        List<Map<String, Object>> expectedSteps = jdbc.queryForList(
                "SELECT r.id AS relationId, r.oper_id AS operationId, r.sort_num AS `order`, "
                        + "o.oper AS code, o.oper_desc AS name "
                        + "FROM sp_flow_oper_relation r JOIN sp_oper o ON o.id = r.oper_id "
                        + "WHERE r.flow_id = ? ORDER BY r.sort_num, r.id", material.get("routing_id"));
        assertFalse("A complete existing route must contain actual operations", expectedSteps.isEmpty());
        assertNotNull("Full-context MockMvc must be injected", mvc);

        Subject subject = new Subject.Builder(securityManager).buildSubject();
        UsernamePasswordToken token = new UsernamePasswordToken(
                credential("mes.it.username", "MES_IT_USERNAME", "admin"),
                credential("mes.it.password", "MES_IT_PASSWORD", "123").toCharArray());
        ThreadContext.bind(subject);
        try {
            // These credentials are the existing local sample account, never a production authentication bypass.
            subject.login(token);
            assertTrue(subject.isAuthenticated());
            assertTrue("The local role must already have its explicitly approved routing permission",
                    subject.isPermitted("mes:assistant:routing:read"));
            Cookie session = new Cookie("JSESSIONID", subject.getSession().getId().toString());

            assertRenderedPagesAndNavigation(session);

            MvcResult tool = mvc.perform(get("/admin/assistant/material-routing")
                    .cookie(session).param("materialCode", code))
                    .andExpect(status().isOk()).andReturn();
            assertRoutingMatchesSql(tool, material, expectedSteps);

            MvcResult chat = mvc.perform(post("/admin/assistant/chat").cookie(session)
                    .characterEncoding("UTF-8").param("message", "查" + code + "的工艺路线"))
                    .andExpect(status().isOk()).andReturn();
            assertRoutingMatchesSql(chat, material, expectedSteps);

            String missingCode = "IT-MISSING-" + UUID.randomUUID().toString();
            mvc.perform(get("/admin/assistant/material-routing").cookie(session).param("materialCode", missingCode))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0))
                    .andExpect(jsonPath("$.data.status").value("NO_MATERIAL"))
                    .andExpect(jsonPath("$.data.material").doesNotExist());
            mvc.perform(get("/admin/assistant/material-routing").cookie(session).param("materialCode", "invalid@code"))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(1))
                    .andExpect(jsonPath("$.data.status").value("INVALID_INPUT"));

            assertEquals("The read-only assistant must leave all local business rows unchanged", before, businessSnapshot());
        } finally {
            token.clear();
            try {
                subject.logout();
            } finally {
                ThreadContext.remove();
                druidWebFilter.getFilter().destroy();
            }
        }
    }

    private Map<String, Object> existingCompleteMaterial() {
        List<Map<String, Object>> found = jdbc.queryForList(
                "SELECT m.id AS material_id, m.materiel AS material_code, m.materiel_desc AS material_name, "
                        + "m.is_deleted AS material_status, f.id AS routing_id, f.flow AS routing_code, f.flow_desc AS routing_name "
                        + "FROM sp_materile m JOIN sp_flow f ON f.id = m.flow_id "
                        + "JOIN sp_flow_oper_relation r ON r.flow_id = f.id LEFT JOIN sp_oper o ON o.id = r.oper_id "
                        + "WHERE m.is_deleted = '0' AND m.materiel REGEXP '^[A-Za-z0-9][A-Za-z0-9._/-]{0,254}$' "
                        + "AND NOT EXISTS (SELECT 1 FROM sp_materile duplicate_material "
                        + "WHERE duplicate_material.materiel = m.materiel AND duplicate_material.is_deleted = '0' "
                        + "AND duplicate_material.id <> m.id) "
                        + "GROUP BY m.id, f.id "
                        + "HAVING COUNT(*) = COUNT(o.id) AND COUNT(*) = COUNT(DISTINCT r.sort_num) AND MIN(r.sort_num) > 0 "
                        + "ORDER BY m.id LIMIT 1");
        assertFalse("The local fixture needs an existing normal material with a complete route", found.isEmpty());
        return found.get(0);
    }

    private void assertRenderedPagesAndNavigation(Cookie session) throws Exception {
        MvcResult index = mvc.perform(get("/admin/index").cookie(session))
                .andExpect(status().isOk()).andReturn();
        String html = index.getResponse().getContentAsString();
        assertTrue(html.contains("id=\"mes-ai-widget\""));
        assertTrue(html.contains("/js/mes-assistant.js"));
        assertTrue(html.contains("/css/mes-assistant.css"));
        assertFalse(html.contains("FreeMarker template error"));
        for (String path : new String[]{"/basedata/materile/list-ui", "/basedata/flow/process/list-ui"}) {
            MvcResult page = mvc.perform(get(path).cookie(session)).andExpect(status().isOk()).andReturn();
            assertTrue("Existing business pages must render HTML", page.getResponse().getContentAsString().contains("<html"));
        }
        for (String path : new String[]{"/admin/list/index/menu/tree", "/admin/list/index/menu/search/tree/助手"}) {
            MvcResult response = mvc.perform(get(path).cookie(session).characterEncoding("UTF-8"))
                    .andExpect(status().isOk()).andReturn();
            JsonNode result = json.readTree(response.getResponse().getContentAsString());
            assertEquals(0, result.path("code").asInt());
            JsonNode menuInfo = result.path("data").path("menuInfo");
            assertTrue(menuInfo.isObject());
            if (path.equals("/admin/list/index/menu/tree")) {
                assertTrue("Existing navigation modules must remain available", menuInfo.size() > 0);
            }
            assertNoButtonNavigation(menuInfo);
        }
    }

    private void assertNoButtonNavigation(JsonNode node) {
        assertNotNull(node);
        if (node.isObject()) {
            assertNotEquals("2", node.path("type").asText());
            assertNotEquals("mes:assistant:routing:read", node.path("permission").asText());
        }
        for (JsonNode child : node) {
            if (child.isContainerNode()) {
                assertNoButtonNavigation(child);
            }
        }
    }

    private void assertRoutingMatchesSql(MvcResult response, Map<String, Object> material,
                                         List<Map<String, Object>> expectedSteps) throws Exception {
        JsonNode envelope = json.readTree(response.getResponse().getContentAsString());
        assertEquals(0, envelope.path("code").asInt());
        JsonNode data = envelope.path("data");
        assertEquals("SUCCESS", data.path("status").asText());
        assertEquals("database", data.path("mode").asText());
        assertFalse(data.path("modelConnected").asBoolean(true));
        assertEquals("get_material_routing", data.path("tool").asText());
        assertEquals("sp_materile → sp_flow → sp_flow_oper_relation → sp_oper", data.path("source").asText());
        assertNotNull(Instant.parse(data.path("queriedAt").asText()));
        assertNotNull(UUID.fromString(data.path("traceId").asText()));
        assertTrue(data.path("elapsedMs").asLong(-1) >= 0);
        ObjectNode expectedMaterial = json.createObjectNode()
                .put("id", (String) material.get("material_id"))
                .put("code", (String) material.get("material_code"))
                .put("name", (String) material.get("material_name"))
                .put("status", (String) material.get("material_status"));
        ObjectNode expectedRouting = json.createObjectNode()
                .put("id", (String) material.get("routing_id"))
                .put("code", (String) material.get("routing_code"))
                .put("name", (String) material.get("routing_name"));
        assertEquals(expectedMaterial, data.path("material"));
        assertEquals(expectedRouting, data.path("routing"));
        assertEquals(json.valueToTree(expectedSteps), data.path("steps"));
    }

    private Map<String, List<Map<String, Object>>> businessSnapshot() {
        Map<String, List<Map<String, Object>>> result = new LinkedHashMap<>();
        for (String table : new String[]{"sp_materile", "sp_flow", "sp_flow_oper_relation", "sp_oper"}) {
            result.put(table, jdbc.queryForList("SELECT * FROM " + table + " ORDER BY id"));
        }
        return result;
    }

    private void assertLocalDatabase(String url) {
        assertTrue("This opt-in test is restricted to 127.0.0.1:3308/mes_study",
                url != null && url.matches("^jdbc:mysql://127\\.0\\.0\\.1:3308/mes_study(?:\\?.*)?$"));
    }

    private String credential(String property, String variable, String localSampleDefault) {
        String value = System.getProperty(property);
        if (value == null) {
            value = System.getenv(variable);
        }
        return value == null ? localSampleDefault : value;
    }
}
