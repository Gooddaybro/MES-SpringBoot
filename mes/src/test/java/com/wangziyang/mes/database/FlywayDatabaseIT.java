package com.wangziyang.mes.database;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** Opt-in: creates and removes ONLY a randomly named database on loopback MySQL. */
public class FlywayDatabaseIT {
    private Connection admin;
    private String database;
    private String url;
    private String user;
    private String password;

    @Before public void createTemporaryDatabase() throws Exception {
        assumeTrue("Enable with -Dmes.flyway.it=true", Boolean.getBoolean("mes.flyway.it"));
        int port = Integer.parseInt(System.getenv().getOrDefault("MES_FLYWAY_TEST_PORT", "3308"));
        assertTrue("Only local test ports are allowed", port == 3306 || port == 3308);
        user = System.getenv("MES_FLYWAY_TEST_USER");
        password = System.getenv("MES_FLYWAY_TEST_PASSWORD");
        assertNotNull("Set MES_FLYWAY_TEST_USER", user);
        assertNotNull("Set MES_FLYWAY_TEST_PASSWORD", password);
        String server = "jdbc:mysql://127.0.0.1:" + port + "/";
        String options = "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai&characterEncoding=UTF-8";
        admin = DriverManager.getConnection(server + options, user, password);
        database = "mes_flyway_it_" + UUID.randomUUID().toString().replace("-", "");
        admin.createStatement().execute("CREATE DATABASE `" + database + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        url = server + database + options;
    }

    @After public void removeOwnedTemporaryDatabase() throws Exception {
        if (admin != null) {
            try {
                if (database != null && database.matches("mes_flyway_it_[a-f0-9]{32}"))
                    admin.createStatement().execute("DROP DATABASE `" + database + "`");
            } finally { admin.close(); }
        }
    }

    private Flyway flyway(String... extraLocations) {
        List<String> locations = new ArrayList<>(Collections.singletonList("classpath:db/migration"));
        locations.addAll(Arrays.asList(extraLocations));
        return Flyway.configure().dataSource(url, user, password).locations(locations.toArray(new String[0]))
                .baselineOnMigrate(false).validateOnMigrate(true).cleanDisabled(true).load();
    }

    private String query(String sql) throws Exception {
        try (Connection c = DriverManager.getConnection(url, user, password);
             Statement statement = c.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            StringBuilder value = new StringBuilder();
            while (rows.next()) {
                for (int i = 1; i <= rows.getMetaData().getColumnCount(); i++) value.append(rows.getString(i)).append('|');
                value.append('\n');
            }
            return value.toString();
        }
    }

    @Test public void emptyDatabaseReceivesAllTablesAndOnlySystemData() throws Exception {
        assertEquals(4, flyway().migrate());
        assertEquals("20|\n", query("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()"));
        assertEquals("0|\n", query("SELECT COUNT(*) FROM sp_materile"));
        assertEquals("0|\n", query("SELECT COUNT(*) FROM sp_sys_user"));
        assertEquals("0|\n", query("SELECT COUNT(*) FROM mes_work_order"));
        assertEquals("7|\n", query("SELECT COUNT(*) FROM sp_sys_menu WHERE permission LIKE 'mes:assistant:%' AND type='2'"));
        assertEquals("0|\n", query("SELECT COUNT(*) FROM sp_sys_role_menu r JOIN sp_sys_menu m ON m.id=r.menu_id WHERE m.permission LIKE 'mes:assistant:%'"));
        assertEquals("5|\n", query("SELECT COUNT(*) FROM sp_sys_dict"));
    }

    @Test public void secondMigrationDoesNotChangeAnyRows() throws Exception {
        flyway().migrate();
        String before = snapshot();
        assertEquals(0, flyway().migrate());
        assertEquals(before, snapshot());
    }

    @Test public void newVersionRunsOnce() throws Exception {
        flyway().migrate();
        Path directory = Files.createTempDirectory("mes-flyway-next-");
        Path migration = directory.resolve("V99__test_only.sql");
        try {
            Files.write(migration, "CREATE TABLE flyway_test_marker (id INT PRIMARY KEY);\nINSERT INTO flyway_test_marker VALUES (1);".getBytes(StandardCharsets.UTF_8));
            assertEquals(1, flyway("filesystem:" + directory.toAbsolutePath()).migrate());
            assertEquals(0, flyway("filesystem:" + directory.toAbsolutePath()).migrate());
            assertEquals("1|\n", query("SELECT COUNT(*) FROM flyway_test_marker"));
        } finally { Files.deleteIfExists(migration); Files.deleteIfExists(directory); }
    }

    @Test public void manuallyImportedDatabaseIsRefusedUntilExplicitBaseline() throws Exception {
        try (Connection c = DriverManager.getConnection(url, user, password)) {
            ScriptUtils.executeSqlScript(c, new FileSystemResource("../scripts/sql/MySQL-sparchetype_20200330.sql"));
            // Simulate out-of-order, partially applied R005 on a previously imported database.
            ScriptUtils.executeSqlScript(c, new FileSystemResource("../scripts/sql/r005_work_order.sql"));
        }
        String before = query("SELECT id,materiel,materiel_desc,flow_id FROM sp_materile ORDER BY id")
                + query("SELECT id,username,password FROM sp_sys_user ORDER BY id");
        try { flyway().migrate(); fail("Unmanaged nonempty database must be refused"); }
        catch (FlywayException expected) { assertTrue(expected.getMessage().contains("baseline")); }
        Flyway takeover = Flyway.configure().dataSource(url,user,password).locations("classpath:db/migration")
                .baselineVersion("1").baselineOnMigrate(false).cleanDisabled(true).load();
        takeover.baseline();
        assertEquals(3, takeover.migrate());
        assertEquals(before, query("SELECT id,materiel,materiel_desc,flow_id FROM sp_materile ORDER BY id")
                + query("SELECT id,username,password FROM sp_sys_user ORDER BY id"));
        assertEquals("1|BASELINE|\n", query("SELECT version,type FROM flyway_schema_history WHERE type='BASELINE'"));
    }

    @Test public void failedDdlIsVisibleAndRequiresInspectionBeforeRepair() throws Exception {
        flyway().migrate();
        Path directory = Files.createTempDirectory("mes-flyway-fail-");
        Path migration = directory.resolve("V99__deliberate_failure.sql");
        try {
            Files.write(migration, ("CREATE TABLE flyway_partial_ddl (id INT);\n"
                    + "INSERT INTO no_such_mes_table VALUES (1);").getBytes(StandardCharsets.UTF_8));
            try { flyway("filesystem:"+directory.toAbsolutePath()).migrate(); fail("Expected SQL failure"); }
            catch (FlywayException expected) { assertTrue(expected.getMessage().contains("no_such_mes_table")); }
            assertEquals("0|\n", query("SELECT success FROM flyway_schema_history WHERE version='99'"));
            assertEquals("flyway_partial_ddl|\n", query("SHOW TABLES LIKE 'flyway_partial_ddl'"));
            try { flyway("filesystem:"+directory.toAbsolutePath()).migrate(); fail("Expected failed-history refusal"); }
            catch (FlywayException expected) { assertTrue(expected.getMessage().toLowerCase(Locale.ROOT).contains("failed")); }
        } finally { Files.deleteIfExists(migration); Files.deleteIfExists(directory); }
    }

    @Test public void editedSuccessfulMigrationIsRejected() throws Exception {
        flyway().migrate();
        Path directory = Files.createTempDirectory("mes-flyway-checksum-");
        Path migration = directory.resolve("V99__immutable.sql");
        try {
            Files.write(migration, "CREATE TABLE checksum_marker (id INT);".getBytes(StandardCharsets.UTF_8));
            flyway("filesystem:"+directory.toAbsolutePath()).migrate();
            Files.write(migration, "CREATE TABLE checksum_marker (id INT, changed INT);".getBytes(StandardCharsets.UTF_8));
            try { flyway("filesystem:"+directory.toAbsolutePath()).migrate(); fail("Expected checksum failure"); }
            catch (FlywayException expected) { assertTrue(expected.getMessage().toLowerCase(Locale.ROOT).contains("checksum")); }
        } finally { Files.deleteIfExists(migration); Files.deleteIfExists(directory); }
    }

    private String snapshot() throws Exception {
        StringBuilder result = new StringBuilder();
        try (Connection c = DriverManager.getConnection(url,user,password);
             Statement statement = c.createStatement(); ResultSet tables = statement.executeQuery("SHOW TABLES")) {
            while (tables.next()) result.append(query("SELECT * FROM `"+tables.getString(1)+"` ORDER BY 1"));
        }
        return result.toString();
    }
}
