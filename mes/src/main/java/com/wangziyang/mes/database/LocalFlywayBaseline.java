package com.wangziyang.mes.database;

import org.flywaydb.core.Flyway;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Explicit maintenance entry point, never a Spring bean or startup hook. */
public final class LocalFlywayBaseline {
    private LocalFlywayBaseline() { }

    static String validateTarget(String url) {
        Matcher match = Pattern.compile("jdbc:mysql://127\\.0\\.0\\.1:(3306|3308)/(mes_study|mes_flyway_it_[a-f0-9]{32})"
                + "\\?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai").matcher(url == null ? "" : url);
        if (!match.matches()) throw new IllegalArgumentException("Refusing an unapproved local baseline target");
        return match.group(2);
    }

    public static void main(String[] args) {
        if (!"BASELINE_REVIEWED".equals(System.getenv("MES_FLYWAY_APPROVED")))
            throw new IllegalArgumentException("Run the reviewed baseline command in scripts/db/mes_db.py");
        String url = System.getenv("MES_FLYWAY_DB_URL");
        String schema = validateTarget(url);
        // The Java API does not merge flyway.conf, FLYWAY_* or Maven/system property overrides.
        Flyway.configure().dataSource(url, System.getenv("MES_FLYWAY_DB_USER"), System.getenv("MES_FLYWAY_DB_PASSWORD"))
                .schemas(schema).table("flyway_schema_history").baselineVersion("1")
                .baselineOnMigrate(false).cleanDisabled(true).load().baseline();
    }
}
