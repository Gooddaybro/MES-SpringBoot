"""Local-only maintenance using Python 3 stdlib and the MySQL 8 command-line client."""
import argparse
import configparser
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[2]
CONTRACT = Path(__file__).with_name("schema-contract.json")


def validate_target(port, database):
    if port not in (3306, 3308) or not re.fullmatch(r"mes_study|mes_flyway_it_[a-f0-9]{32}", database):
        raise ValueError("Only loopback ports 3306/3308 and mes_study/owned temporary test names are allowed")


def contract_errors(actual, expected):
    errors = []
    for table, wanted in expected.items():
        found = actual.get(table)
        if not found:
            errors.append(f"Missing table: {table}")
            continue
        if found["engine"] != wanted["engine"]:
            errors.append(f"Wrong engine: {table}")
        if found.get("collation") != wanted.get("collation"):
            errors.append(f"Wrong table default collation: {table}")
        for column in set(found["columns"]) | set(wanted["columns"]):
            if found["columns"].get(column) != wanted["columns"].get(column):
                errors.append(f"Column definition differs: {table}.{column}")
        for index in wanted["indexes"]:
            if index not in found["indexes"]:
                errors.append(f"Missing/different index: {table}.{index[0]}")
        for index in found["indexes"]:
            if index[1] == "0" and index not in wanted["indexes"]:
                errors.append(f"Unexpected unique index: {table}.{index[0]}")
        if found.get("checks", {}) != wanted.get("checks", {}):
            errors.append(f"CHECK constraints differ or are not enforced: {table}")
        if found.get("foreign_keys", []) != wanted.get("foreign_keys", []):
            errors.append(f"Foreign keys differ: {table}")
    return sorted(set(errors))


def sql_text(value):
    return "CONVERT(0x" + value.encode("utf-8").hex() + " USING utf8mb4)"


class LocalDatabase:
    def __init__(self, args):
        validate_target(args.port, args.database)
        self.args = args
        self.command = [str(Path(args.mysql).resolve()) if Path(args.mysql).is_file() else args.mysql]
        if args.client_config:
            self.command.append("--defaults-file=" + str(Path(args.client_config).resolve(strict=True)))
        else:
            self.command.append("--no-defaults")
            self.command.append("--user=" + os.environ.get("MES_DB_USERNAME", "root"))
        self.command += ["--host=127.0.0.1", f"--port={args.port}", "--protocol=tcp",
                         "--connect-timeout=5", "--default-character-set=utf8mb4"]

    def run(self, sql, xml=False, select_database=True):
        command = self.command + (["--database=" + self.args.database] if select_database else [])
        command += ["--xml"] if xml else ["--batch", "--raw"]
        env = dict(os.environ)
        env.pop("MYSQL_PWD", None)
        if not self.args.client_config and "MES_DB_PASSWORD" in env:
            env["MYSQL_PWD"] = env["MES_DB_PASSWORD"]
        result = subprocess.run(command, input=sql, encoding="utf-8", capture_output=True, env=env, check=False)
        if result.returncode:
            # Do not echo SQL, credentials, client configuration or application rows.
            codes = re.findall(r"ERROR \d+ \([A-Z0-9]+\)", result.stderr)
            raise ValueError("MySQL command failed " + ", ".join(codes) + "; inspect the target/credentials and SQL locally")
        return result.stdout

    def rows(self, sql, select_database=True):
        root = ET.fromstring(self.run(sql, xml=True, select_database=select_database))
        return [{field.attrib["name"]: None if any(k.endswith("nil") and v == "true" for k, v in field.attrib.items())
                 else field.text or "" for field in row} for row in root.findall("row")]


def schema_signature(db):
    tables = {r["t"]: {"engine": r["e"], "collation": r["co"], "columns": {}, "indexes": [], "checks": {}, "foreign_keys": []}
              for r in db.rows("SELECT table_name t,engine e,table_collation co FROM information_schema.tables WHERE table_schema=DATABASE()")
              if r["t"] != "flyway_schema_history"}
    for row in db.rows("SELECT table_name t,column_name n,column_type ty,is_nullable nu,column_default d,collation_name co,extra ex "
                       "FROM information_schema.columns WHERE table_schema=DATABASE() ORDER BY table_name,ordinal_position"):
        if row["t"] in tables:
            ty = re.sub(r"\b(tinyint|smallint|mediumint|int|bigint)\(\d+\)", r"\1", row["ty"])
            tables[row["t"]]["columns"][row["n"]] = [ty, row["nu"], row["d"], row["co"], row["ex"]]
    for row in db.rows("SELECT table_name t,index_name n,non_unique u,seq_in_index s,column_name c,sub_part p,index_type ty "
                       "FROM information_schema.statistics WHERE table_schema=DATABASE() ORDER BY table_name,index_name,seq_in_index"):
        if row["t"] in tables:
            tables[row["t"]]["indexes"].append([row[k] for k in ("n", "u", "s", "c", "p", "ty")])
    for row in db.rows("SELECT t.table_name t,t.constraint_name n,c.check_clause c,t.enforced e "
                       "FROM information_schema.table_constraints t JOIN information_schema.check_constraints c "
                       "ON c.constraint_schema=t.constraint_schema AND c.constraint_name=t.constraint_name "
                       "WHERE t.table_schema=DATABASE() AND t.constraint_type='CHECK'"):
        if row["t"] in tables:
            tables[row["t"]]["checks"][row["n"]] = row["c"] if row["e"] == "YES" else "NOT ENFORCED"
    for row in db.rows("SELECT table_name t,constraint_name n FROM information_schema.table_constraints "
                       "WHERE table_schema=DATABASE() AND constraint_type='FOREIGN KEY' ORDER BY constraint_name"):
        if row["t"] in tables:
            tables[row["t"]]["foreign_keys"].append(row["n"])
    return tables


def inspect(db):
    target = db.rows("SELECT VERSION() v,@@port p", select_database=False)[0]
    if target["p"] != str(db.args.port) or not re.match(r"8\.0\.(\d+)", target["v"]) or int(target["v"].split(".")[2].split("-")[0]) < 16:
        raise ValueError("Expected MySQL 8.0.16+ at the explicitly selected local port")
    report = {"host": "127.0.0.1", "port": db.args.port, "database": db.args.database,
              "mysql": target["v"], "writes": False, "blockers": []}
    if not db.rows("SELECT schema_name FROM information_schema.schemata WHERE schema_name=" + sql_text(db.args.database), False):
        return dict(report, state="DATABASE_MISSING", next="Create the database with scripts/db/create-local-database.sql")
    tables = db.rows("SHOW TABLES")
    if not tables:
        return dict(report, state="EMPTY", next="Start dev with Flyway enabled; do not baseline an empty database")
    contract = json.loads(CONTRACT.read_text(encoding="utf-8"))
    actual = schema_signature(db)
    expected = json.loads(json.dumps(contract["tables"]))
    for name in set(expected) - set(contract["baseline_tables"]):
        if name not in actual:
            del expected[name]
    if "materiel_code" not in actual.get("sp_bom", {}).get("columns", {}):
        expected["sp_bom"]["columns"].pop("materiel_code")
    errors = contract_errors(actual, expected)
    if not errors:
        for table, check in contract["system_data"].items():
            columns = check["columns"]
            rows = db.rows("SELECT " + ",".join("`" + c + "`" for c in columns) + " FROM `" + table + "`")
            observed = {tuple(r[c] for c in columns) for r in rows}
            for row in check["required"]:
                if tuple(row) not in observed:
                    errors.append(f"Required system configuration missing/changed: {table} key={row[0]}")
        present_capabilities = 0
        for cap in contract["capabilities"]:
            # Let MySQL use the audited column collation, including case/accent rules.
            matches = db.rows("SELECT type,permission FROM sp_sys_menu WHERE permission=" + sql_text(cap["permission"]))
            present_capabilities += bool(matches)
            if matches and (len(matches) != 1 or matches[0]["type"] != "2" or matches[0]["permission"] != cap["permission"]):
                errors.append("Ambiguous/noncanonical/non-button capability: " + cap["permission"])
            collisions = db.rows("SELECT COUNT(*) n FROM sp_sys_menu WHERE id=" + sql_text(cap["id"])
                                 + " OR code=" + sql_text(cap["code"]) + " OR name=" + sql_text(cap["name"]))
            if not matches and collisions[0]["n"] != "0":
                errors.append("Reserved capability ID/code/name collision: " + cap["permission"])
        report["assistant_capabilities"] = present_capabilities
    history = []
    has_history = any("flyway_schema_history" in row.values() for row in tables)
    if has_history:
        history = db.rows("SELECT version,type,success FROM flyway_schema_history ORDER BY installed_rank")
        if any(r["success"] != "1" for r in history):
            errors.append("Failed Flyway history entry; inspect residual DDL before repair")
    report.update(state="MANAGED" if has_history else "UNMANAGED", history=history,
                  table_count=len(actual), bom_complete="sp_bom_item" in actual and "materiel_code" in actual.get("sp_bom", {}).get("columns", {}),
                  work_order_present="mes_work_order" in actual, blockers=errors,
                  extra_tables=sorted(set(actual) - set(contract["tables"])))
    report["next"] = "Resolve blockers on a backup copy; do not baseline" if errors else (
        "Use normal Flyway validation/startup" if history else "After backup/review, explicit baseline 1; V2-V4 safely fill audited partial stages")
    return report


def baseline(db):
    report = inspect(db)
    if report["state"] != "UNMANAGED" or report["blockers"]:
        raise ValueError("Baseline refused: database must be audited, nonempty, unmanaged and free of blockers")
    backup = Path(db.args.backup_file).resolve(strict=True)
    if backup.stat().st_size < 100 or backup.suffix.lower() != ".sql":
        raise ValueError("Provide a reviewed nonempty SQL backup of this exact target")
    if db.args.confirm != "BASELINE_REVIEWED":
        raise ValueError("Explicit --confirm BASELINE_REVIEWED required after reviewing the backup and inspect report")
    env = {key: value for key, value in os.environ.items()
           if not key.upper().startswith(("FLYWAY_", "LOADER_"))
           and key.upper() not in {"JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "MAVEN_OPTS", "MAVEN_ARGS"}}
    user, password = env.get("MES_DB_USERNAME", "root"), env.get("MES_DB_PASSWORD")
    if db.args.client_config:
        config = configparser.ConfigParser(interpolation=None)
        config.read(db.args.client_config, encoding="utf-8-sig")
        user = config.get("client", "user")
        password = config.get("client", "password").strip('"\'')
    if password is None:
        raise ValueError("MES_DB_PASSWORD or a [client] user/password configuration is required")
    env.update(MES_FLYWAY_DB_URL=f"jdbc:mysql://127.0.0.1:{db.args.port}/{db.args.database}?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai",
               MES_FLYWAY_DB_USER=user, MES_FLYWAY_DB_PASSWORD=password, MES_FLYWAY_APPROVED="BASELINE_REVIEWED")
    jar = ROOT / "mes/target/mes-1.0.0.jar"
    source = ROOT / "mes/src/main/java/com/wangziyang/mes/database/LocalFlywayBaseline.java"
    if not jar.is_file() or jar.stat().st_mtime < source.stat().st_mtime:
        raise ValueError("Build the current jar with JDK8: mvn -f mes/pom.xml -DskipTests package")
    runner = (ROOT / ".local/flyway-baseline").resolve()
    if ROOT not in runner.parents:
        raise ValueError("Baseline runtime must remain inside this checkout")
    runner.mkdir(parents=True, exist_ok=True)
    class_name = "com/wangziyang/mes/database/LocalFlywayBaseline.class"
    class_file = runner / class_name
    class_file.parent.mkdir(parents=True, exist_ok=True)
    # Extract only three fixed paths; avoid Spring/Maven launchers and their ambient configuration.
    with zipfile.ZipFile(jar) as archive:
        class_file.write_bytes(archive.read("BOOT-INF/classes/" + class_name))
        libraries = ["flyway-core-5.2.4.jar", "mysql-connector-java-8.0.17.jar"]
        for name in libraries:
            (runner / name).write_bytes(archive.read("BOOT-INF/lib/" + name))
    java = Path(env["JAVA_HOME"]) / "bin" / ("java.exe" if os.name == "nt" else "java") if env.get("JAVA_HOME") else shutil.which("java")
    if not java:
        raise ValueError("Set JAVA_HOME to JDK8")
    print("Backup SHA256:", hashlib.sha256(backup.read_bytes()).hexdigest(), flush=True)
    classpath = os.pathsep.join(str(path) for path in [runner, *(runner / name for name in libraries)])
    subprocess.run([str(java), "-Dfile.encoding=UTF-8", "-cp", classpath, "com.wangziyang.mes.database.LocalFlywayBaseline"], env=env, check=True)


def init_admin(db):
    report = inspect(db)
    if report["state"] != "MANAGED" or report["blockers"] or not any(r["version"] == "4" and r["success"] == "1" for r in report["history"]):
        raise ValueError("Initialize only a successfully migrated V4 database")
    if db.rows("SELECT COUNT(*) n FROM sp_sys_user")[0]["n"] != "0":
        raise ValueError("Existing users preserved: this command only initializes a database with no users")
    if db.rows("SELECT COUNT(*) n FROM sp_sys_role WHERE code='admin' AND is_deleted='0'")[0]["n"] != "1":
        raise ValueError("Exactly one enabled admin role is required before creating the first user")
    password = os.environ.get("MES_BOOTSTRAP_PASSWORD", "")
    if len(password) < 12:
        raise ValueError("Set MES_BOOTSTRAP_PASSWORD to your own password of at least 12 characters")
    value = hashlib.md5(b"admin" + password.encode("utf-8")).digest()
    for _ in range(2):
        value = hashlib.md5(value).digest()
    db.run("START TRANSACTION;\n"
           "INSERT INTO sp_sys_user (id,name,username,password,mobile,sex,is_deleted,create_time,create_username,update_time,update_username) "
           "SELECT 'flyway-bootstrap-admin','Administrator','admin'," + sql_text(value.hex()) + ", '', '2','0',NOW(),'local-bootstrap',NOW(),'local-bootstrap' "
           "FROM DUAL WHERE NOT EXISTS(SELECT 1 FROM sp_sys_user);\n"
           "INSERT INTO sp_sys_user_role (id,user_id,role_id,create_time,create_username,update_time,update_username) "
           "SELECT 'flyway-bootstrap-admin-role',u.id,r.id,NOW(),'local-bootstrap',NOW(),'local-bootstrap' "
           "FROM sp_sys_user u JOIN sp_sys_role r ON r.code='admin' AND r.is_deleted='0' "
           "WHERE u.id='flyway-bootstrap-admin' AND NOT EXISTS(SELECT 1 FROM sp_sys_user_role x WHERE x.user_id=u.id AND x.role_id=r.id);\nCOMMIT;")
    print("Created local admin using your password; existing users were not modified")


def local_script(db, filename, variables):
    text = (ROOT / "scripts/sql" / filename).read_text(encoding="utf-8-sig")
    guard = "DATABASE()='mes_study' AND @@port=3308"
    if guard in text:
        text = text.replace(guard, f"DATABASE()='{db.args.database}' AND @@port={db.args.port}")
    output = db.run(variables + "\n" + text)
    if "READY:" not in output:
        raise ValueError("Local initialization refused by source SQL conflict/prerequisite checks: " + filename)
    print("READY source:", "scripts/sql/" + filename)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=["inspect", "baseline", "init-admin", "dev-seed", "grant-role"])
    parser.add_argument("--mysql", default="mysql")
    parser.add_argument("--client-config")
    parser.add_argument("--port", type=int, default=3306)
    parser.add_argument("--database", default="mes_study")
    parser.add_argument("--backup-file")
    parser.add_argument("--confirm")
    parser.add_argument("--role-id")
    args = parser.parse_args()
    db = LocalDatabase(args)
    if args.command == "inspect":
        report = inspect(db)
        print(json.dumps(report, ensure_ascii=False, indent=2))
        return 2 if report["blockers"] else 0
    if args.command == "baseline":
        if not args.backup_file:
            raise ValueError("--backup-file is required")
        baseline(db)
    elif args.command == "init-admin":
        init_admin(db)
    else:
        if args.confirm != "LOCAL_LEARNING_ONLY":
            raise ValueError("Explicit --confirm LOCAL_LEARNING_ONLY is required")
        report = inspect(db)
        if report["state"] != "MANAGED" or report["blockers"] or not report["bom_complete"] or not report["work_order_present"]:
            raise ValueError("Use a fully migrated, validated local database")
        if args.command == "dev-seed":
            local_script(db, "servo_controller_sample.sql", "SET @mes_servo_sample_confirm='LOCAL_LEARNING_ONLY';")
            local_script(db, "r005_work_order_sample.sql", "SET @r005_sample_confirm='LOCAL_LEARNING_ONLY';")
        else:
            if not args.role_id or not re.fullmatch(r"[A-Za-z0-9_-]{1,64}", args.role_id):
                raise ValueError("Select an existing enabled --role-id explicitly")
            role = sql_text(args.role_id)
            local_script(db, "mes_assistant_permission.sql", "SET @assistant_role_id=" + role + ";")
            local_script(db, "mes_assistant_knowledge_permission.sql", "SET @assistant_knowledge_role_id=" + role + "; SET @assistant_restricted_role_id=NULL;")
            local_script(db, "r005_multi_agent_permission.sql", "SET @r005_role_id=" + role + ";")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (ValueError, OSError, subprocess.CalledProcessError, ET.ParseError, configparser.Error, zipfile.BadZipFile, KeyError) as error:
        print("ERROR:", error, file=sys.stderr)
        sys.exit(2)
