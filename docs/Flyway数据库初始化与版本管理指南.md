# Flyway 数据库初始化与版本管理指南

适用：`agent` 分支、JDK 8、Spring Boot 2.1.7.RELEASE、MySQL **8.0.16 及以上的 8.0.x**。本次本机验证版本为 8.0.46。没有升级 JDK、Boot、ORM、Shiro，没有改写业务 Java/Python/前端；新增一个独立 Java 基线维护入口。

## 1. 使用方式与边界

Flyway 在 Spring Boot 启动期间检查 `flyway_schema_history`，按版本执行尚未成功的 SQL。成功版本保存 checksum，以后重启跳过；已执行文件被修改时校验失败。迁移失败会阻止启动。

**Flyway 创建表，连接目标数据库必须预先存在。** 项目不在应用启动时连接 MySQL 系统库建库，不自动连接远程服务器。空库使用 V1–V4；已有库必须先核验、备份，再显式 baseline。不要启用 `baseline-on-migrate: true`。

- 默认 `dev`：`127.0.0.1:3306/mes_study`，启用自动迁移。
- 公共配置：Flyway 默认关闭，`clean-disabled: true`、`validate-on-migrate: true`、`baseline-on-migrate: false`，关闭 Spring JDBC 的另一套初始化机制。
- `pro`：明确关闭自动迁移；URL、账号、密码分别由 `MES_PRO_DB_URL`、`MES_PRO_DB_USERNAME`、`MES_PRO_DB_PASSWORD` 提供，已移除历史硬编码远程地址。生产发布须在备份、验证及明确授权后单独安排。
- 历史 `local` 启动器仍使用独立的 3308 学习库，默认不自动接管旧库；新库通过 `-EnableFlyway` 显式启用。启动器已取消导入含 DROP 的全量快照。

依赖选用 Boot 原生管理的 **Flyway 5.2.4 / Connector/J 8.0.17**。这保留了 Boot 2.1.7 的自动配置 API 和 Java 8 兼容性，没有引入要求较高 JDK 的新版 Flyway。参见 [Boot 2.1.7 数据库初始化文档](https://docs.spring.io/spring-boot/docs/2.1.7.RELEASE/reference/html/howto-database-initialization.html)、[Boot 依赖版本](https://docs.spring.io/spring-boot/docs/2.1.7.RELEASE/reference/html/appendix-dependency-versions.html)、[Flyway 5.2.4 MySQL 实现](https://github.com/flyway/flyway/blob/flyway-5.2.4/flyway-core/src/main/java/org/flywaydb/core/internal/database/mysql/MySQLDatabase.java)。本项目实测兼容范围不外推为整个旧技术栈的当前长期支持承诺。

## 2. 原始 SQL 审查清单

13 个原始文件全部保留，均不在 Flyway 扫描路径内。全量快照与单表脚本不是可以顺序叠加的增量；两个快照内部未发现重复主键 INSERT，但跨文件重复表及主键很多。反复导入快照会先删表，反复导入原始 INSERT 也可能主键冲突。

| 原文件（scripts/sql） | 结构与数据、顺序和风险 | 本次归类 |
|---|---|---|
| `MySQL-sparchetype_20200326.sql` | 16 表；工厂、车间、线体、物料、路线/关系/工序、7 张系统表、通用主数据两表。16 个 DROP TABLE，关闭/恢复 FK 检查；混有历史账号和演示业务数据；字典索引指向 type。 | 历史保留，不执行；不与 0330 连用。0330 中仍有的结构已合入 V1。 |
| `MySQL-sparchetype_20200330.sql` | 17 表，在 0326 上增加 sp_bom；字典同名索引改为 name，其他基础 DDL 一致；19 菜单、5 字典、6 个通用表配置及 16 个字段配置。业务样例数量变化，另有 4 个历史用户、3 个角色。17 个 DROP TABLE；无 sp_bom_item。 | V1 提取 17 张 DDL、必要菜单/字典/通用配置、唯一 admin 角色及其导航关联；不带原始用户、账号密码、个人角色或业务记录。原文件仅历史保留。 |
| `sp_flow.sql` | 单表 sp_flow，含 DROP，无 INSERT。DDL 与 0330 一致。路线必须先于业务关联数据。 | 已合入 V1；原文件不自动执行。 |
| `sp_flow_oper_relation.sql` | 单表 sp_flow_oper_relation，含 DROP，无 INSERT。DDL 与 0330 一致；数据依赖路线、工序，sort_num 决定顺序。 | 已合入 V1；原文件不自动执行。 |
| `sp_oper.sql` | 单表 sp_oper，含 DROP，无 INSERT。DDL 与 0330 一致。 | 已合入 V1；原文件不自动执行。 |
| `sp_table_manager.sql` | 单表通用表配置，含 DROP，无 INSERT；与 0330 仅注释/空白差异。 | 结构及 0330 必需配置合入 V1；原文件历史保留。 |
| `sp_table_manager_item.sql` | 单表通用字段配置，含 DROP，无 INSERT；与 0330 仅注释/空白差异；配置数据依赖上一个文件。 | 同上，合入 V1。 |
| `mes_assistant_permission.sql` | R002/R003 路线只读按钮及显式角色关联；依赖菜单、角色、角色菜单表；NOT EXISTS 防重复；未指定有效角色不改变数据。 | 权限**登记**合入 V3；授权逻辑保留为显式操作，禁止迁移默认扩权。 |
| `mes_assistant_knowledge_permission.sql` | R004 公开、受限知识两种按钮，分别指定角色；DML 事务与 NOT EXISTS；受限权限独立。 | 登记合入 V3；本地 grant-role 仅授公开知识，受限权限必须另行审阅授权。 |
| `r005_multi_agent_permission.sql` | R005 四业务读权限与工单写权限；路线权限与旧脚本重叠；只在 mes_study/3308 且选择角色时授权。 | 去重登记合入 V3；本地授权命令复用原脚本并显式选角色。 |
| `r005_work_order.sql` | R005 新建 mes_work_order；IF NOT EXISTS，无业务 INSERT；唯一工单号、物料索引、3 个 CHECK。IF NOT EXISTS 不验证已有表是否正确。 | 独立 V4；已有表必须由核验工具比较字段/索引/CHECK 后接管。 |
| `r005_work_order_sample.sql` | 教学工单 WO-SERVO-DEMO-001，依赖伺服样例的完整 6 工序路线及 V4；显式确认和冲突检查，重复不改状态/冻结快照。 | 仅独立 dev-seed；不在任何启动扫描目录。 |
| `servo_controller_sample.sql` | 6 物料、1 路线、6 工序、6 关联；来源用户名 servo-sample-seed、ID sample-servo-*、编码 SERVO-DEMO-*。只 DROP TEMPORARY TABLE，不删业务表；冲突时整组拒绝。依赖 V1。 | 仅独立 dev-seed；先于教学工单执行。 |

所有历史 DDL 使用 MySQL 8 的 `utf8mb4_0900_ai_ci`，不能原样用于 5.7。工单 CHECK 在 8.0.16 前不完整强制执行，因此维护工具拒绝更老版本；正式兼容验证只在 8.0.46 完成。历史脚本没有 TRUNCATE 或 DELETE；危险点是快照/单表的 DROP 和不可叠加的全量数据。

正常运行需要：表结构、导航菜单、字典、通用维护元数据、管理员角色及显式初始化的首个用户。工厂/车间/线体、物料、路线、工序和教学工单均是业务数据，空库可无记录。助手权限登记不等于自动授予任何用户。

**额外发现：** 原快照缺少 Java 实体 `SpBomItem` 所用的 `sp_bom_item`；`SpBom.materielCode` 对应的 `materiel_code` 也不存在，历史只有 `materiel_id`。V2 增量补齐，保留原字段及全部值；不猜测旧 ID 与编码的含义，不自动回填。BOM 业务流程/旧数据映射仍需单独验收。

## 3. 正式迁移版本

位置：`mes/src/main/resources/db/migration/`。

| 版本 | 文件 | 内容 |
|---|---|---|
| 1 | `V1__mes_baseline.sql` | 17 张基础表；47 条必要系统种子（19 菜单、5 字典、22 通用维护配置、1 管理角色），再建立管理员角色导航；没有历史用户、密码或演示业务数据。 |
| 2 | `V2__complete_bom_schema.sql` | 新建 sp_bom_item，补 materiel_code，保留 materiel_id；对经过核验的手工已有对象允许跳过创建。 |
| 3 | `V3__assistant_permission_catalog.sql` | 去重登记 7 项助手能力，包括独立受限知识；保留已有权限 ID/角色关联；不自动授权。 |
| 4 | `V4__r005_work_order.sql` | R005 工单表、索引、3 个 CHECK；不导入教学工单。 |

V4 完成后为 **19 张应用表 + flyway_schema_history，共 20 张表**。

## 4. 新电脑 / IDEA 首次启动

以下命令在项目根目录 `MES-SpringBoot` 执行。安装 JDK 8、Maven、MySQL 8.0.16+；维护命令另需 Python 3（仅标准库）和 MySQL 8 的 mysql 客户端。IDEA 的 Project SDK、运行 JRE 及 Maven Runner 均设为 JDK 8。

1. 先确认 MySQL 连接为 `127.0.0.1:3306`。用本地有建库权限的账号显式执行：

```powershell
mysql --no-defaults --host=127.0.0.1 --port=3306 --user=root -p --execute="SOURCE scripts/db/create-local-database.sql"
```

2. IDEA 编辑 MES 的 Spring Boot 运行配置：Active profiles=`dev`，环境变量 `MES_DB_USERNAME`（省略时 root）、`MES_DB_PASSWORD`（必填）。真实密码只留在本机私有设置，不存入共享 `.run`、YAML 或 Git。Redis 保持既有本机配置；采用 Ehcache 时不依赖 Redis 完成 Shiro 登录。
3. 启动 `SparchetypeApplication`。首次应看到 V1–V4 执行完成及应用启动；第二次应显示 schema 已是最新。不要手工导入任何历史快照。
4. 第一次创建自己的管理员。若 mysql 已加入 PATH，可在 PowerShell 7 临时设置环境，或使用受忽略的 MySQL client 配置：

```powershell
$env:MES_DB_USERNAME = 'root'
$env:MES_DB_PASSWORD = Read-Host '本机数据库密码' -MaskInput
$env:MES_BOOTSTRAP_PASSWORD = Read-Host '自选 MES 管理员密码（至少12字符）' -MaskInput
python scripts/db/mes_db.py init-admin
Remove-Item Env:MES_BOOTSTRAP_PASSWORD
```

管理员用户名为 `admin`，密码为你刚设置的值。这个命令仅接受成功迁移到 V4 且**没有任何用户**的本地库；不会重置或覆盖已有账号。哈希沿用项目现有 Shiro MD5 + 用户名盐 + 3 次迭代，以免改变原鉴权行为；没有迁移为另一套密码算法。

5. 打开 `http://127.0.0.1:9090/login-ui`，填写你自己的密码及页面验证码。新建空库的查询可以正常返回“没有数据”。需要教学业务及助手能力时再执行第 6 节命令。

若 mysql 没有加入 PATH，每条维护命令增加 `--mysql '你的MySQL目录/bin/mysql.exe'`。也可使用 `--client-config '绝对路径/client.ini'`；文件至少包含 `[client]` 下 `user`、`password`，不要提交此文件。工具强制覆盖 host/port/protocol，不采用配置里的远程目标。

## 5. 已有数据库安全接管（包括只导入一部分 R004/R005）

应用启动和 baseline 是两件事。第一次接入旧库时 `baseline-on-migrate=false` 会拒绝无历史表的非空库；这是保护，不应改成 true 绕过。

先停业务写入。对目标做只读核验（参数默认 3306/mes_study）：

```powershell
python scripts/db/mes_db.py inspect
# 当前仓库原来的隔离学习库在 3308，使用：
python scripts/db/mes_db.py inspect --mysql 'D:/Program Files/mysql-8.0.46-winx64/bin/mysql.exe' --client-config '.local/mysql-root.ini' --port 3308
```

工具先检查目标库是否存在、实际服务版本/端口，再核对 `schema-contract.json` 中的字段类型/NULL/default/列及表默认 collation、索引、InnoDB、CHECK 是否强制执行、外键、必需字典/导航/通用元数据，以及权限重复、类型及预留 ID/code/name 冲突。预留值使用数据库自身的大小写/重音比较规则检查，同时拒绝 Shiro 无法识别的大小写不同权限值。输出只有结构状态，不输出用户密码或业务行。它不是 MySQL 所有对象和业务数据质量的通用审计器，额外业务表会列出供人工审阅。

状态与处理：

- `DATABASE_MISSING`：执行独立建库 SQL；不 baseline。
- `EMPTY`：直接启动 dev；不 baseline，否则 V1 会被跳过。
- `UNMANAGED` 且 blockers 为空：可以在备份副本验证后，以 **baseline=1** 接管。无论旧库有无 R005 工单，V2–V4 都会核对已存在对象后的兼容路径，补缺表/缺列/缺权限登记；不会重新执行 V1。
- blockers 非空：停止接管。缺基础表/字段、错类型/索引、缺系统元数据、权限冲突、已有工单表不匹配等必须逐项修正，先在副本试验。不要提高 baselineVersion 掩盖缺失迁移。
- `MANAGED`：使用正常 Flyway validate/migrate；不得再次 baseline。

本次真实 3308 学习库为：18 张应用表、已有工单表、6/7 项助手权限、没有 BOM 明细表及 materiel_code、没有历史表。只读核验无 blocker，推荐 baseline 1，再由 V2–V4 补齐。本次**没有对真实学习库执行 baseline/迁移/样例写入**；已在临时副本验证该路径。

具体接管步骤：

1. 停止应用，备份精确目标，确认备份可读、表结构及关键数据完整，并在隔离库试还原。PowerShell 用 mysqldump 的 result-file 参数，避免旧版 PowerShell 重定向破坏编码：

```powershell
mysqldump --no-defaults --host=127.0.0.1 --port=3306 --user=root -p --single-transaction --routines --events --triggers --hex-blob --set-gtid-purged=OFF --result-file="D:/private-backups/mes_study-before-flyway.sql" mes_study
```

2. 运行 inspect，审阅报告和备份，必要时逐表比对。不要备份到可提交目录。
3. 用 JDK8 先打包当前代码：`mvn -f mes/pom.xml '-DskipTests' package`。此命令只打包，不启动应用、不执行数据库迁移。**确认上述步骤后**，显式执行接管命令（工具会重新核验目标、要求非空 `.sql` 备份、记录备份 SHA256，再调用真正的 Flyway baseline API；不手工伪造 history）：

```powershell
python scripts/db/mes_db.py baseline --backup-file 'D:/private-backups/mes_study-before-flyway.sql' --confirm BASELINE_REVIEWED
```

使用私有 client 配置/3308 时，baseline 也必须带与 inspect 完全相同的 `--mysql`、`--client-config`、`--port`。设置 JAVA_HOME 为 JDK8。工具从已构建 jar 提取固定版本 Flyway/驱动和维护入口，以最小 classpath 直接运行 Java API，固定目标 schema、history 表和 baseline=1；不读取 Maven Flyway 插件、flyway.conf 或继承 FLYWAY_*/Java 选项覆盖。备份文件存在性和哈希不能代替人工确认“它确实属于这个目标且已试还原”。

4. 正常启动 dev，V1 被 baseline 标记为已有，不重新导入；只执行 V2–V4。若用历史 local 启动器：停止旧应用后 `scripts/local/Start-Local.ps1 -EnableFlyway -Rebuild`，连接仍为该启动器专用 3308。
5. 比对原用户/角色/授权/物料/路线/工单数量及关键记录；查询 history 并重新登录。旧库已有账号无需也不能执行 init-admin。

0326 的字典索引与 0330 不同，且没有 sp_bom，会被核验拒绝。可以从 V1 中提取**缺失表的 CREATE 部分**到独立、已审阅的补齐 SQL；索引变更须单独核对线上查询/数据后处理；缺配置只补缺项。禁止再次导入整个快照或对含业务数据的表 DROP 重建。严重部分结构/自定义列冲突没有通用自动修复方案，本次优先拒绝并保留数据。

## 6. 本地测试数据与角色授权

正式目录不含任何测试 seed；不同环境不会因启动或重启加载演示业务。明确批准使用教学数据后，独立执行：

```powershell
python scripts/db/mes_db.py dev-seed --confirm LOCAL_LEARNING_ONLY
```

命令先核验已管理的结构，按顺序复用原伺服样例和工单样例 SQL，保留来源 ID、编码、创建人、描述及工单工艺快照；仅在本次显式会话内将原 3308 防护条件绑定到已核验的本机目标。因此同一命令也可用于目标 3306；原脚本原文不变。

重复导入不会重置工单状态、快照或旧记录；相同 ID/业务编码但内容不同会报错。两个 seed 各有自己的事务；若工单部分失败，已成功的伺服组不会整体回滚，修正问题后可以安全重跑。不要并行运行种子或同时修改这些业务编码。

助手能力需选定角色，独立明确授权：

```powershell
python scripts/db/mes_db.py grant-role --role-id 1185025876737396738 --confirm LOCAL_LEARNING_ONLY
```

上述是 V1 管理员角色 ID，也可改为你批准的已有启用角色。命令授予路线/公开知识/物料/工序/工单读/工单写这 6 项，**不授受限知识**。授权后重新登录以更新 Shiro 缓存。生产角色请经正常权限管理与审阅流程配置；不要把本地教学授权当作生产默认策略。

## 7. R006 / R007 及以后新增 SQL

Flyway 版本与业务阶段编号独立。当前最大为 V4，因此 R006 的第一个迁移可以是 `V5__r006_add_work_order_priority.sql`，R007 的后续可以是 V6，不能照着业务阶段号随意跳过/覆盖文件。

```sql
-- Example for the NEXT version only; this example is not automatically loaded.
ALTER TABLE mes_work_order ADD COLUMN priority INT NOT NULL DEFAULT 0;
```

一个迁移版本处理一个可核验目标；每条语句独立占行并以分号结尾（5.2.4 不支持把多条语句写在同一行的本项目测试用法）。UTF-8、`V数字__英文描述.sql`，双下划线。先从空库全链验证，再对代表性的旧库副本升级、检查重启及失败行为。

新增权限先登记按钮，再显式给批准角色授权；迁移中禁止一律给所有角色赋权。新增业务表/字段/索引时考虑旧数据、默认值、NOT NULL、索引长度、约束及执行锁；需要清洗/回填先备份和审阅，不以 DROP/TRUNCATE 或无范围 DELETE 解决冲突。

成功版本永不随意改写、改名或删除；修复用新版本。不要靠 `repair` 接受被篡改的 checksum。`schema-contract.json` 是本次接管 V1–V4 的结构核验基准；新增迁移若改变已核验结构，需要同步扩展核验规则/版本语义及测试，不能仅更新 JSON 掩盖旧库差异。普通新增迁移执行本身只需增加版本文件，仍须经过验证后发布。

## 8. 迁移成功检查与 history

```sql
SELECT installed_rank,version,description,type,script,checksum,success
FROM flyway_schema_history ORDER BY installed_rank;
SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE();
SELECT COUNT(*) FROM sp_sys_user;
SELECT COUNT(*) FROM sp_materile;
SELECT COUNT(*) FROM mes_work_order;
```

全新库应有 V1–V4 四条成功 SQL 记录；旧库接管应有 version=1/type=BASELINE 加 V2–V4 成功记录。BASELINE 不是重新执行 V1，history 也不是业务数据备份。第二次启动应无新增迁移行、业务数量不变。

## 9. 常见错误与 MySQL 失败恢复

| 现象 | 检查与处理 |
|---|---|
| Unknown database / `DATABASE_MISSING` | 连接指定本机端口显式建库，Flyway 不负责创建连接目标。 |
| MES_DB_PASSWORD 未配置 / Access denied | 配置当前 Java 进程的环境变量或本机私有 client 文件；不同端口账号体系不一定相同，不要猜测远程口令。 |
| Non-empty schema but no schema history | 执行第 5 节核验/备份/baseline，不能直接打开 baseline-on-migrate。 |
| Duplicate table/column 或核验 blocker | 旧库可能部分手工执行。比对具体对象；已有 IF NOT EXISTS 不代表结构正确。保留数据，在副本修正后重新核验。 |
| Permission ID/code/name 冲突 | 不删除旧权限；确认语义、角色引用，明确合并方案。V3 对已存在同权限保留原 ID，但不能自动判断不同权限同 ID 的真实含义。 |
| Checksum mismatch | 还原已成功版本的原文，修正另写新版本；不要随意 repair。 |
| Failed migration / success=0 | 停止写入，保留日志、备份和 history，核对失败 SQL 之前的 DDL/数据残留，按下述流程恢复。 |
| 工单 CHECK 缺失/未强制执行 | 检查 MySQL 版本及 SHOW CREATE TABLE；维护工具拒绝低于 8.0.16。 |

MySQL CREATE/ALTER 经常隐式提交，Flyway 不能保证整个迁移自动回滚。实际失败测试中：先 CREATE TABLE 后故意 INSERT 不存在表，应用启动失败，表仍存在，history 留 success=0；再次启动明确拒绝。

恢复顺序：①停止应用及业务写入；②备份当前部分状态，记录 `SHOW CREATE TABLE` 和失败行；③选择恢复原备份，或经审阅把残留结构/数据修复到可继续的准确状态；④确认不会重复写业务数据后，才在这个明确目标上考虑 Flyway `repair` 清理失败历史并重试，或采用已设计的前向修复流程。**repair 只调整 history，不会撤销残留表/列/数据。** 本项目不自动执行 repair/clean，不提供自动删表回滚。

## 10. 可重复执行的测试

```powershell
python -m unittest discover -s scripts/tests -p test_mes_db.py
python scripts/tests/flyway_smoke.py --mysql '你的MySQL目录/bin/mysql.exe' --client-config '本机私有root-client.ini' --java '你的JDK8目录/bin/java.exe' --port 3308
```

smoke 命令需要能创建/删除临时库的本机账号。它只创建和删除随机 `mes_flyway_it_<32位UUID>` 库，强制回环地址及 3306/3308，不启动或调用外部 AI 服务。检查默认完整 package、真实 jar 两次启动逐表哈希、新版本/重复、显式管理员和样例、真实 Realm/业务查询、故意失败及再次拒绝、备份与部分旧库 baseline；日志和备份存于被忽略的 `.local/flyway-smoke-*/`。测试不使用真实学习库作为写入目标。

完整 smoke 的 R005 服务验收需要项目已有的 `MasterDataQueryService`、`WorkOrderService` 源码。本次保留但不混入提交的 R005 Java/Python/前端改动仍在当前工作区；仅检出 Flyway 提交的机器可独立编译、运行迁移及下一段 FlywayDatabaseIT，待 R005 业务实现另行提交后再运行完整四业务 smoke。数据库表结构本身不依赖这些未提交类。

另有 `FlywayDatabaseIT` 的 6 项数据库测试，显式启用并通过环境传入本机测试账号：

```powershell
# 密码由当前进程的私有环境传入，不放进命令行或 Git。
$env:MES_FLYWAY_TEST_PORT = '3308'
$env:MES_FLYWAY_TEST_USER = 'root'
$env:MES_FLYWAY_TEST_PASSWORD = Read-Host '本机隔离实例密码' -MaskInput
mvn -f mes/pom.xml '-Dmes.flyway.it=true' '-Dtest=FlywayDatabaseIT' test
```

具体本次成功、失败、未验证范围见 [验证结果](evals/Flyway-20261030.json) 及 [改造日志](MES_REFACTOR_JOURNAL.md)。3306 本机服务已运行，但当前可用私有配置仅指向 3308，用户同意按已找到的配置执行；因此不声称已验收真实 3306 的凭据/旧数据，或已在真实学习库完成接管。Redis、真实 AI 模型、生产部署、BOM 全流程和多电脑实际部署不在本次已验证范围。
