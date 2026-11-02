# MES R006 第一批 Implementation Plan

> **For agentic workers:** 按本文件顺序在当前会话执行；用户要求保持现有 agent 分支。复用 writing-plans、test-driven-development、verification-before-completion 的检查方式。

**Goal:** 在原四业务 Agent 上补齐名称搜索、工序关联只读查询及可靠历史快照对比。

**Architecture:** Java校验权限、过滤参数并查询确定性关系；原HMAC契约传递事实，LangGraph先确认工序再组织下游。保留原Service事务与写入入口。

**Tech Stack:** JDK8 / Spring Boot2.1.7 / MySQL8 / Shiro / FastAPI / LangGraph / Layui + FreeMarker。

## 文件级任务与检查点

### 1. 基线与盘点

- [x] 检查内层仓库 `agent`、提交75f9b49、已有6个脏文件并在忽略目录`.local/r006-before/`保存原件。
- [x] 运行原Java182/Python135/Node20测试；分别核实3307和3308数据，不执行初始化/迁移/种子。
- [x] 新增 `docs/R006-能力盘点与第一批验收.md`，记录Controller/Service/Mapper/表与完整能力表、两个数据库边界和后续依赖。

### 2. 有界搜索与关联

Create `mes/src/main/java/com/wangziyang/mes/manufacturing/BusinessQuery.java`：受控参数、中文名称、日期及页码验证；LIKE文字转义。Modify同目录 `MasterDataQueryService.java`、`WorkOrderService.java`，增加搜索方法，不改原create/transition。

- [x] 先新增 `BusinessQueryTest.java`、`BusinessSearchServiceTest.java`、`OperationImpactServiceTest.java`，断言分页、特殊字符、歧义、空关联和快照匹配；以同名 `-Dtest` 运行确认缺失能力。
- [x] 实现 `searchMaterials(BusinessQuery,String)`、`searchOperations(BusinessQuery)`、`searchRoutings(BusinessQuery,String)`、`WorkOrderService.search(BusinessQuery,String)`；最后参数为服务端已确定的operationId或null。
- [x] 新增 `OperationImpactService.java`：先鉴权，再强制工序首屏解析；仅唯一候选时沿真实ID查询；分别返回四模块事实和`parameters.operationId`。
- [x] 专项测试通过；输入页码范围1..1000、pageSize1..50，日期为本地时间起点包含/终点排除，不支持的参数拒绝。

查询片段（绑定参数）：

```sql
AND m.materiel_desc LIKE ? ESCAPE '!'
AND EXISTS (SELECT 1 FROM sp_flow_oper_relation r JOIN sp_flow f ON f.id=r.flow_id
            WHERE r.flow_id=m.flow_id AND r.oper_id=?)
AND JSON_CONTAINS(route_snapshot_json,JSON_OBJECT('operationId',?),'$.steps')=1
ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?
```

### 3. 快照对比

Create `RoutingComparisonService.java`、`RoutingComparisonServiceTest.java`。复用原orders.query/routing.query，不修改物料、工单或快照。

- [x] 先测试相同、增删、重排、名称/编码变动、重复工序、缺失快照、当前无路线、无权限；运行确认失败。
- [x] 服务读取快照，按operationId+出现次数匹配，仅比较历史确实保存的字段；输出`changes`、`snapshotSteps`、`currentSteps`、`potentialProductionImpact`、`limitations`。
- [x] 不可靠结构返回`SNAPSHOT_UNRELIABLE`，没有当前有效路线返回原业务错误，不能解释为无差异；同时核对当前/历史物料ID，阻止同码替代记录。

### 4. Java工具入口与LangGraph

Modify `ManufacturingController.java`、`assistant/agent/MultiAgentService.java`、`assistant/controller/MultiAgentController.java`；Create `assistant/agent/BusinessQuestion.java`。Modify `agent/app/multi_schemas.py`、`agent/app/multi_graph.py`。

- [x] 增加Java controller/service失败测试及 `agent/tests/test_operation_chain.py`，确认无权限、名称冲突、伪造关联、歧义停止和超时不丢事实。
- [x] 工作台新增 `/materials/search`、`/operations/search`、`/routings/search`、`/orders/search`、`/operation-impact`、`/orders/compare-routing`；旧端点保持兼容。
- [x] 聊天增加可选名称/状态/日期/分页字段；固定句式只提取原文，不生成代码/SQL。差异查询要求工单和路线权限；跨模块要求四项权限。
- [x] 签名JSON新增可选`queryMode=operation_chain`；旧请求无该字段保持行为。图增加先解析后分派的条件边；不能匹配operationId的下游事实拒绝。
- [x] 运行Java专项测试、`uv run --frozen --project agent pytest agent/tests -q`、Ruff；原签名/权限/超时测试继续通过。独立审查发现并回归修复条件丢失、否定/时间及物料身份问题。

### 5. 原界面增量与集成验收

Modify `mes/src/main/resources/templates/admin/assistant/manufacturing.ftl`、`widget.ftl`、`static/js/mes-manufacturing.js`、`static/js/mes-assistant.js` 与 `scripts/tests/mes-assistant.test.cjs`、`mes-manufacturing.test.cjs`；Create `mes/src/test/java/com/wangziyang/mes/manufacturing/ReadOnlyBusinessDatabaseIT.java`。

- [x] 先增加名称条件、只读表单、差异结果/XSS行为测试，再改界面；保留显式创建/状态提交。
- [x] 新IT仅允许3308/mes_study并显式启用，使用已有SERVO-DEMO主数据；新增/变化夹具只在测试事务内回滚，不改3307业务数据。
- [x] JDK8完整package、Python全量/Ruff、Node全量与语法检查通过，再运行只读真实数据及回滚IT。命令采用既有私有local配置，不输出凭据。
- [x] 为验证真实签名传输，再新增 `ReadOnlyAgentDatabaseIT.java`（真实Realm/MySQL/HTTP，MVC模拟入口）及 `agent/tests/run_readonly_smoke_server.py`（无Key、无PDF索引的独立8002服务）；4项通过。未将该测试称为真实浏览器/HITL验收。

```powershell
mvn -f mes/pom.xml '-Dspring.profiles.active=local' '-Dspring.config.additional-location=file:D:/Documents/ChatGPT/manufacture/MES-SpringBoot/.local/application-idea.yml' package
mvn -f mes/pom.xml '-Dspring.profiles.active=local' '-Dspring.config.additional-location=file:D:/Documents/ChatGPT/manufacture/MES-SpringBoot/.local/application-idea.yml' '-Dmes.readonly.it.enabled=true' '-Dtest=ReadOnlyBusinessDatabaseIT' test
uv run --frozen --project agent pytest agent/tests -q
Push-Location agent
uv run --frozen ruff check app tests
Pop-Location
node --test scripts/tests/mes-assistant.test.cjs scripts/tests/mes-manufacturing.test.cjs
```

### 6. 文档与Git交付

- [x] 更新能力/接口/图/操作/启动/测试/未完事项文档；`MES_REFACTOR_JOURNAL.md`只追加本轮条目，保护原未提交内容。
- [x] 检查前后数据指纹、`git diff --check`和密钥边界。区分单元、集成、数据库学习环境、生产尚未验证。
交付动作：精确暂存本轮文件；日志使用HEAD+本轮追加内容生成索引，原脏文档/配置不随本轮提交。中文 `feat: 增强MES受控关联查询与工艺快照对比`，正常推送origin/agent并核对远端SHA，不强推。实际结果以Git记录和交付回复为准。

## 自检

本计划仅覆盖用户第一批优先功能；智能建单HITL、批量执行、生产SOP精确联动留作第二/三批。已确认原工单快照可复用，但没有参数/发布版本字段；没有行级工厂授权字段，均不得伪称支持。
