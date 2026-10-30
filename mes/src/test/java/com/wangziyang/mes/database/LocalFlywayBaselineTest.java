package com.wangziyang.mes.database;

import org.junit.Test;
import static org.junit.Assert.*;

public class LocalFlywayBaselineTest {
    private static final String OPTIONS = "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai";

    @Test public void refusesEveryUnapprovedEndpointBeforeOpeningAConnection() {
        for (String url : new String[]{"jdbc:mysql://example.com:3306/mes_study" + OPTIONS,
                "jdbc:mysql://127.0.0.1:3306/production" + OPTIONS,
                "jdbc:mysql://127.0.0.1:3307/mes_study" + OPTIONS,
                "jdbc:mysql://127.0.0.1:3306/mes_study" + OPTIONS + "&sessionVariables=sql_mode=''"}) {
            try { LocalFlywayBaseline.validateTarget(url); fail("Must refuse an unapproved target"); }
            catch (IllegalArgumentException expected) { /* No JDBC connection is attempted. */ }
        }
    }

    @Test public void acceptsOnlyTheExactConstructedLocalUrl() {
        assertEquals("mes_study", LocalFlywayBaseline.validateTarget("jdbc:mysql://127.0.0.1:3306/mes_study" + OPTIONS));
        String temporary = "mes_flyway_it_0123456789abcdef0123456789abcdef";
        assertEquals(temporary, LocalFlywayBaseline.validateTarget("jdbc:mysql://127.0.0.1:3308/" + temporary + OPTIONS));
    }
}
