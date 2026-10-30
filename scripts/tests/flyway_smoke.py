"""Opt-in MySQL/JDK8 smoke test. Owns only random mes_flyway_it_* databases.

All logs/backups remain in ignored .local/. Never launches the Python AI service.
"""
import argparse
import configparser
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import secrets
import shutil
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("mes_db", ROOT / "scripts/db/mes_db.py")
db_tools = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(db_tools)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mysql", required=True)
    parser.add_argument("--client-config", required=True)
    parser.add_argument("--java", required=True, help="JDK 8 java.exe")
    parser.add_argument("--port", type=int, default=3308, choices=[3306, 3308])
    args = parser.parse_args()
    args.client_config = str(Path(args.client_config).resolve(strict=True))
    args.database = "mes_flyway_it_" + uuid.uuid4().hex
    db = db_tools.LocalDatabase(args)
    owned = [args.database]
    runtime = ROOT / ".local" / ("flyway-smoke-" + uuid.uuid4().hex)
    runtime.mkdir(parents=True)
    config = configparser.ConfigParser(interpolation=None)
    config.read(args.client_config, encoding="utf-8-sig")
    env = dict(os.environ)
    env.update(JAVA_HOME=str(Path(args.java).resolve().parents[1]),
               SPRING_PROFILES_ACTIVE="dev", SPRING_DATASOURCE_USERNAME=config.get("client", "user"),
               SPRING_DATASOURCE_PASSWORD=config.get("client", "password").strip('"\''),
               MES_BOOTSTRAP_PASSWORD=secrets.token_hex(16), MES_AGENT_ENABLED="false", MES_LLM_ENABLED="false")
    env["MES_DB_PASSWORD"] = env["SPRING_DATASOURCE_PASSWORD"]
    report = {"jdk": "8", "mysql_port": args.port, "checks": [], "remote_connections": False}
    process = None
    log_handle = None
    maven = shutil.which("mvn")
    assert maven, "Maven is required"

    def passed(name):
        report["checks"].append(name)
        print("PASS:", name, flush=True)

    def set_target():
        env["SPRING_DATASOURCE_URL"] = f"jdbc:mysql://127.0.0.1:{args.port}/{args.database}?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai&characterEncoding=UTF-8"

    def command(command_args, log):
        with (runtime / log).open("wb") as output:
            result = subprocess.run(command_args, cwd=ROOT, env=env, stdout=output, stderr=subprocess.STDOUT, check=False)
        assert result.returncode == 0, f"Command failed; inspect {runtime / log}"

    def stop():
        nonlocal process, log_handle
        if process is not None:
            if process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=10)
            process = None
        if log_handle:
            log_handle.close()
            log_handle = None

    def start(label, locations=None, expect_failure=False):
        nonlocal process, log_handle
        stop()
        set_target()
        with socket.socket() as listener:
            listener.bind(("127.0.0.1", 0))
            web_port = listener.getsockname()[1]
        options = [str(Path(args.java).resolve()), "-Dfile.encoding=UTF-8", "-Xmx384m", "-jar",
                   str(ROOT / "mes/target/mes-1.0.0.jar"), "--server.address=127.0.0.1", f"--server.port={web_port}",
                   "--spring.flyway.enabled=true", "--spring.datasource.druid.initial-size=1",
                   "--logging.config=" + (ROOT / "scripts/local/logback-local.xml").as_uri()]
        if locations:
            options.append("--spring.flyway.locations=classpath:db/migration,filesystem:" + str(locations))
        log_handle = (runtime / (label + ".log")).open("wb")
        process = subprocess.Popen(options, cwd=ROOT, env=env, stdout=log_handle, stderr=subprocess.STDOUT,
                                   creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)
        deadline = time.monotonic() + 60
        while time.monotonic() < deadline:
            if process.poll() is not None:
                assert expect_failure and process.returncode != 0, f"Unexpected startup exit; inspect {label}.log"
                return
            try:
                with urllib.request.urlopen(f"http://127.0.0.1:{web_port}/login-ui", timeout=1) as response:
                    if response.status == 200:
                        assert not expect_failure, "Failed migration must prevent web startup"
                        return web_port
            except (urllib.error.URLError, TimeoutError, ConnectionError):
                pass
            time.sleep(0.2)
        raise AssertionError("Startup timeout: " + label)

    def rows_hash(table):
        return hashlib.sha256(db.run("SELECT * FROM `" + table + "` ORDER BY 1", xml=True).encode()).hexdigest()

    def maintenance(action, *extra, expected_code=0):
        result = subprocess.run([sys.executable, str(ROOT / "scripts/db/mes_db.py"), action, "--mysql", args.mysql,
                                 "--client-config", args.client_config, "--port", str(args.port), "--database", args.database,
                                 *extra], env=env, cwd=ROOT, capture_output=True, encoding="utf-8", errors="replace", check=False)
        (runtime / (action + ".log")).write_text(result.stdout + result.stderr, encoding="utf-8")
        assert result.returncode == expected_code, f"{action} failed; inspect private runtime logs"

    try:
        db.run("CREATE DATABASE `" + args.database + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci", select_database=False)
        set_target()
        # The default context test connects only to this empty temporary DB; migration is exercised by the real jar below.
        env["SPRING_FLYWAY_ENABLED"] = "false"
        command([maven, "-f", "mes/pom.xml", "package"], "package.log")
        env.pop("SPRING_FLYWAY_ENABLED")
        passed("JDK8 full Maven package including the existing context test")
        web_port = start("01-empty")
        for resource in ("/lib/jquery/jquery.min.js", "/css/start.css"):
            with urllib.request.urlopen(f"http://127.0.0.1:{web_port}" + resource, timeout=5) as response:
                assert response.status == 200 and len(response.read()) > 100, "Missing login resource: " + resource
                assert "text/html" not in response.headers.get("Content-Type", ""), "Login resource redirected to HTML"
        assert len(db.rows("SHOW TABLES")) == 20
        assert len(db.rows("SELECT version FROM flyway_schema_history WHERE success=1")) == 4
        assert db.rows("SELECT COUNT(*) n FROM sp_sys_user")[0]["n"] == "0"
        assert db.rows("SELECT COUNT(*) n FROM sp_materile")[0]["n"] == "0"
        before = {next(iter(row.values())): rows_hash(next(iter(row.values()))) for row in db.rows("SHOW TABLES")}
        passed("Empty database: real Spring Boot startup applied V1-V4; login JS/CSS available; no sample users/business data")
        start("02-repeat")
        assert before == {name: rows_hash(name) for name in before}
        stop()
        passed("Second real Spring Boot startup: history and every table row unchanged")
        maintenance("init-admin")
        maintenance("init-admin", expected_code=2)
        maintenance("grant-role", "--role-id", "1185025876737396738", "--confirm", "LOCAL_LEARNING_ONLY")
        maintenance("dev-seed", "--confirm", "LOCAL_LEARNING_ONLY")
        seed_before = {name: rows_hash(name) for name in ("sp_materile", "sp_flow", "sp_oper", "sp_flow_oper_relation", "mes_work_order")}
        maintenance("dev-seed", "--confirm", "LOCAL_LEARNING_ONLY")
        assert seed_before == {name: rows_hash(name) for name in seed_before}
        passed("First-user bootstrap refuses overwrite; explicit grants and repeated teaching seed preserve rows")
        command([maven, "-f", "mes/pom.xml", "-Dmes.flyway.business.it=true", "-Dtest=FlywayBusinessDatabaseIT", "test"], "business-it.log")
        passed("Real Shiro login, users/menus, six capabilities, restricted denial, material/route/work-order services")
        directory = runtime / "migrations"
        directory.mkdir()
        (directory / "V99__startup_probe.sql").write_text("CREATE TABLE startup_probe (id INT PRIMARY KEY);\nINSERT INTO startup_probe VALUES (1);\n", encoding="utf-8")
        start("03-new-version", directory)
        assert db.rows("SELECT COUNT(*) n FROM startup_probe")[0]["n"] == "1"
        start("04-new-repeat", directory)
        assert db.rows("SELECT COUNT(*) n FROM startup_probe")[0]["n"] == "1"
        assert len(db.rows("SELECT version FROM flyway_schema_history WHERE success=1")) == 5
        passed("New filesystem V99 executes exactly once across real restarts")
        (directory / "V100__intentional_failure.sql").write_text("CREATE TABLE partial_ddl (id INT);\nINSERT INTO missing_test_table VALUES (1);\n", encoding="utf-8")
        start("05-failure", directory, expect_failure=True)
        assert db.rows("SELECT success FROM flyway_schema_history WHERE version='100'")[0]["success"] == "0"
        assert db.rows("SHOW TABLES LIKE 'partial_ddl'")
        start("06-failure-restart", directory, expect_failure=True)
        stop()
        passed("Failed migration blocks Spring Boot; partial DDL/failed history persist; retry is refused")
        args.database = "mes_flyway_it_" + uuid.uuid4().hex
        owned.append(args.database)
        set_target()
        db.run("CREATE DATABASE `" + args.database + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci", select_database=False)
        db.run((ROOT / "scripts/sql/MySQL-sparchetype_20200330.sql").read_text(encoding="utf-8-sig"))
        db.run((ROOT / "scripts/sql/r005_work_order.sql").read_text(encoding="utf-8-sig"))
        legacy = {name: db.rows("SELECT * FROM `" + name + "` ORDER BY id") for name in db_tools.schema_signature(db)}
        backup = runtime / "legacy-backup.sql"
        dump = str(Path(args.mysql).with_name("mysqldump.exe" if os.name == "nt" else "mysqldump"))
        with backup.open("wb") as output:
            subprocess.run([dump, "--defaults-file=" + args.client_config, "--host=127.0.0.1", f"--port={args.port}",
                            "--protocol=tcp", "--single-transaction", "--set-gtid-purged=OFF", args.database],
                           stdout=output, stderr=subprocess.PIPE, check=True)
        db.run("ALTER TABLE sp_bom DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci")
        maintenance("inspect", expected_code=2)
        maintenance("baseline", "--backup-file", str(backup), "--confirm", "BASELINE_REVIEWED", expected_code=2)
        assert not db.rows("SHOW TABLES LIKE 'flyway_schema_history'")
        db.run("ALTER TABLE sp_bom DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci")
        db.run("INSERT INTO sp_sys_menu (id,code,name,url,parent_id,grade,sort_num,type,permission,create_time,create_username,update_time,update_username) "
               "VALUES ('preflight-case-conflict','mésmaterialread','Preflight collision','#','0','1',9999,'2','legacy:material:read',NOW(),'smoke',NOW(),'smoke')")
        maintenance("inspect", expected_code=2)
        maintenance("baseline", "--backup-file", str(backup), "--confirm", "BASELINE_REVIEWED", expected_code=2)
        db.run("UPDATE sp_sys_menu SET code='preflightLegacyCode',permission='MES:ASSISTANT:MATERIAL:READ' WHERE id='preflight-case-conflict'")
        maintenance("inspect", expected_code=2)
        db.run("DELETE FROM sp_sys_menu WHERE id='preflight-case-conflict'")
        db.run("ALTER TABLE mes_work_order DROP INDEX uk_mes_work_order_code")
        maintenance("inspect", expected_code=2)
        maintenance("baseline", "--backup-file", str(backup), "--confirm", "BASELINE_REVIEWED", expected_code=2)
        assert not db.rows("SHOW TABLES LIKE 'flyway_schema_history'")
        db.run("ALTER TABLE mes_work_order ADD UNIQUE KEY uk_mes_work_order_code (order_code)")
        trap = "mes_flyway_it_" + uuid.uuid4().hex
        owned.append(trap)
        db.run("CREATE DATABASE `" + trap + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci", select_database=False)
        trap_url = f"jdbc:mysql://127.0.0.1:{args.port}/{trap}?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai"
        ambient_config = runtime / "flyway.conf"
        ambient_config.write_text("flyway.url=" + trap_url + "\nflyway.schemas=" + trap + "\nflyway.baselineVersion=4\nflyway.initSql=CREATE TABLE ambient_init (id INT)\n", encoding="utf-8")
        env.update(FLYWAY_URL=trap_url, FLYWAY_SCHEMAS=trap, FLYWAY_BASELINE_VERSION="4", FLYWAY_CONFIG_FILES=str(ambient_config),
                   JAVA_TOOL_OPTIONS="-Dflyway.url=" + trap_url, MAVEN_OPTS="-Dflyway.baselineVersion=4")
        maintenance("baseline", "--backup-file", str(backup), "--confirm", "BASELINE_REVIEWED")
        for key in ("FLYWAY_URL", "FLYWAY_SCHEMAS", "FLYWAY_BASELINE_VERSION", "FLYWAY_CONFIG_FILES", "JAVA_TOOL_OPTIONS", "MAVEN_OPTS"):
            env.pop(key)
        assert not db.rows("SELECT table_name FROM information_schema.tables WHERE table_schema=" + db_tools.sql_text(trap))
        assert db.rows("SELECT version FROM flyway_schema_history")[0]["version"] == "1"
        passed("Case/accent and noncanonical permission conflicts refused; ambient Flyway/Maven/Java overrides cannot change baseline target/version")
        start("07-legacy-takeover")
        for table, rows in legacy.items():
            actual = {row["id"]: row for row in db.rows("SELECT * FROM `" + table + "` ORDER BY id")}
            for row in rows:
                assert all(actual[row["id"]][key] == value for key, value in row.items()), "Legacy row changed: " + table
        assert db.rows("SELECT type FROM flyway_schema_history WHERE version='1'")[0]["type"] == "BASELINE"
        assert not db_tools.inspect(db)["blockers"]
        passed("Backup + audited baseline CLI: rejects wrong schema; partial R005 takeover preserves every original row")
        report["status"] = "PASS"
    finally:
        stop()
        for name in owned:
            db_tools.validate_target(args.port, name)
            assert name.startswith("mes_flyway_it_")
            db.run("DROP DATABASE IF EXISTS `" + name + "`", select_database=False)
        (runtime / "results.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print("Private evidence:", runtime, flush=True)


if __name__ == "__main__":
    main()
