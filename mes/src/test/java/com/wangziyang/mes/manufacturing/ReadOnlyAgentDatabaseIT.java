package com.wangziyang.mes.manufacturing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangziyang.mes.assistant.agent.MultiAgentService;
import com.wangziyang.mes.assistant.agent.PythonAgentProperties;
import com.wangziyang.mes.assistant.controller.MultiAgentController;
import com.wangziyang.mes.assistant.service.MaterialRoutingService;
import org.apache.shiro.authc.UsernamePasswordToken;
import org.apache.shiro.mgt.SecurityManager;
import org.apache.shiro.subject.Subject;
import org.apache.shiro.util.ThreadContext;
import org.junit.*;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import java.net.ServerSocket;
import java.util.*;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Real Realm, MySQL and signed Python HTTP; MVC entry is simulated, CAPTCHA/browser are not tested here. */
@RunWith(SpringRunner.class)
@SpringBootTest
@Transactional(readOnly = true)
public class ReadOnlyAgentDatabaseIT {
    @Autowired private MultiAgentService agent;
    @Autowired private SecurityManager securityManager;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private Environment environment;
    @Autowired private ObjectMapper json;
    @Autowired private MasterDataQueryService master;
    @Autowired private MaterialRoutingService routing;
    @Autowired private WorkOrderService orders;
    @Autowired private OperationImpactService impact;
    @Autowired private RoutingComparisonService comparison;
    private Subject subject;
    private MockMvc mvc;

    @BeforeClass public static void explicitOptIn() {
        assumeTrue("Requires an explicitly started isolated no-model Python on 8002",Boolean.getBoolean("mes.readonly.agent.it.enabled"));
    }
    @Before public void localRealm() {
        assertTrue(environment.getProperty("spring.datasource.url","").startsWith("jdbc:mysql://127.0.0.1:3308/mes_study?"));
        assertEquals(Integer.valueOf(3308),jdbc.queryForObject("SELECT @@port",Integer.class));
        assertEquals("http://127.0.0.1:8002",environment.getProperty("MES_AGENT_BASE_URL"));
        subject=new Subject.Builder(securityManager).buildSubject(); ThreadContext.bind(subject);
        UsernamePasswordToken token=new UsernamePasswordToken(System.getProperty("mes.it.username","admin"),System.getProperty("mes.it.password","123"));
        try { subject.login(token); } finally { token.clear(); }
        assertTrue(subject.isAuthenticated());
        for(String role:Arrays.asList("material","operation","routing","workorder")) assertTrue(subject.isPermitted("mes:assistant:"+role+":read"));
        mvc=MockMvcBuilders.standaloneSetup(new MultiAgentController(agent)).build();
    }
    @After public void logout() { try { if(subject!=null) subject.logout(); } finally { ThreadContext.remove(); } }

    @Test public void naturalLanguageRunsTheDependentGraphAgainstActualExistingBusinessRecords() throws Exception {
        JsonNode data=ask("查询包含绝缘与低压功能测试工序的物料，以及目前处于已创建状态的相关生产工单。");
        assertTrue(data.path("agentExecuted").asBoolean()); assertEquals("operation",data.path("executedAgents").get(0).asText());
        assertEquals(4,data.path("executedAgents").size()); assertFalse(data.path("modelAttempted").asBoolean());
        assertFalse(data.path("modelConnected").asBoolean());
        assertEquals("SERVO-DEMO-CTRL-001",data.path("businessResults").path("material").path("materials").get(0).path("code").asText());
        assertEquals("WO-SERVO-DEMO-001",data.path("businessResults").path("workorder").path("orders").get(0).path("orderCode").asText());
        assertEquals("CREATED",data.path("businessResults").path("workorder").path("orders").get(0).path("state").asText());
    }
    @Test public void aRealAbsentOperationStopsTheGraphAndDoesNotInventAnAgingTest() throws Exception {
        JsonNode data=ask("查询包含老化测试工序的物料及相关工单");
        assertTrue(data.path("agentExecuted").asBoolean()); assertEquals(1,data.path("executedAgents").size());
        assertEquals("operation",data.path("executedAgents").get(0).asText());
        assertEquals("NO_OPERATION",data.path("businessResults").path("operation").path("status").asText());
        assertFalse(data.path("businessResults").path("workorder").has("orders"));
    }
    @Test public void anExistingOrderComparisonSurvivesTheRealSignedHttpContract() throws Exception {
        String before=jdbc.queryForObject("SELECT route_snapshot_json FROM mes_work_order WHERE order_code='WO-SERVO-DEMO-001'",String.class);
        JsonNode data=ask("对比工单 WO-SERVO-DEMO-001 使用的工艺和当前物料工艺的差异");
        assertTrue(data.path("agentExecuted").asBoolean());
        JsonNode compared=data.path("businessResults").path("workorder");
        assertTrue(compared.path("comparisonAvailable").asBoolean()); assertFalse(compared.path("hasChanges").asBoolean(true));
        assertEquals(6,compared.path("snapshotSteps").size());
        assertEquals(before,jdbc.queryForObject("SELECT route_snapshot_json FROM mes_work_order WHERE order_code='WO-SERVO-DEMO-001'",String.class));
    }
    @Test public void aRealClosedPythonPortPreservesDatabaseFactsWithAnHonestFailureFlag() throws Exception {
        int port; try(ServerSocket unused=new ServerSocket(0)) { port=unused.getLocalPort(); }
        MockEnvironment config=new MockEnvironment().withProperty("MES_AGENT_ENABLED","true")
                .withProperty("MES_AGENT_BASE_URL","http://127.0.0.1:"+port)
                .withProperty("MES_AGENT_SHARED_SECRET","r006-integration-test-only-shared-secret");
        MultiAgentService offline=new MultiAgentService(new PythonAgentProperties(config),json,master,routing,orders,impact,comparison);
        Map<String,Object> data=offline.chat("查询物料 SERVO-DEMO-CTRL-001",UUID.randomUUID().toString(),subject,null,null,null);
        assertEquals(false,data.get("agentExecuted")); assertEquals("AGENT_UNAVAILABLE",data.get("fallbackReason"));
        assertTrue(data.get("businessResults").toString().contains("SERVO-DEMO-CTRL-001"));
    }
    private JsonNode ask(String message) throws Exception {
        String response=mvc.perform(post("/admin/assistant/multi/chat").characterEncoding("UTF-8")
                .param("message",message).param("conversationId",UUID.randomUUID().toString()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(response).path("data");
    }
}
