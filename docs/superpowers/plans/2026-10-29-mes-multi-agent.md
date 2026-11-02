# R005 Four-business Multi-agent Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans task-by-task. Independent ownership follows dispatching-parallel-agents; integration/shared mutations are sequential.

**Goal:** Preserve single Agent and add material/operation/routing/workorder Agents, explicitly confirmed by the user.

**Architecture:** Existing Java/Shiro owns authorization/master data and new transactional work orders. A signed Python endpoint coordinates four isolated LangGraph subgraphs; original single contracts and dirty R004-M7 fixes stay intact.

**Tech Stack:** Java8/Spring Boot/JdbcTemplate/MySQL8/Shiro; Python3.11/FastAPI/LangGraph/httpx; existing jQuery.

## Task 1 — Baseline/contracts
- [x] Inspect source, dirty worktree, scripts/SQL and confirm exact four domains.
- [x] Python tests inside agent: 57 passed. Repo-root invocation missed its asyncio configuration; correct directory fixes it.
- [x] JDK8 Maven test with existing private local YAML/profile: exit0. Node14 passed. Default dev DB profile is not the local fixture.
- [x] Rewrite scope around material/operation/routing/workorder; no BOM/inventory/report feature.

## Task 2 — Real Java services (main worker)
Files: new manufacturing/{BusinessException,ManufacturingSupport,MasterDataQueryService,WorkOrderService,ManufacturingController}.java; tests/manufacturing/; scripts/sql/r005_work_order.sql, permissions, teaching seed.
- [x] Tests first, bounded real material/operation queries; route-scoped operations through MaterialRoutingService.
- [x] Persistent work-order create/query/transitions with frozen route, unique code, transaction/locking and repeat protection.
- [x] Authenticated workbench /admin/manufacturing; GET material/operations/routing/orders; POST orders and orders/transition with Shiro per domain.
- [x] Apply additive local migration/seed and test repeat preserves existing records.
Public contracts: MasterDataQueryService.material(String materialCode), operations(String operationCode,String materialCode); MaterialRoutingService.query(String); WorkOrderService.query(String workOrderCode,String materialCode), create(String orderCode,String materialCode,BigDecimal quantity,String actor), transition(String orderCode,String action,String actor); BusinessException.getStatus(). Null identifiers allow bounded material/operation/order lists; routing requires materialCode.

## Task 3 — Python multi Agent (independent worker)
Own new agent/app/multi_*.py, agent/tests/test_multi_*.py and minimal additive main.py/security.py. Single graph/schema untouched.
- [x] Fail tests for four actual compiled subgraphs, role context/tool isolation and grounded evidence first.
- [x] Strict new route and schema, shared path-bound HMAC/replay, separate role prompts/contexts/tools, bounded model interaction.
- [x] Overall30s timeout/partial errors/noKey/cleanup; run uv run --frozen pytest -q and ruff check app tests inside agent.

## Task 4 — Java bridge/UI (independent worker)
Own new MultiAgentService/MultiAgentController/tests; drawer widget/JS/CSS/Node tests; manufacturing.ftl/mes-manufacturing.js.
- [x] Tests first for signed bridge/response validation/permissions and mode switch.
- [x] Deterministic domain selection, server-derived ACL, safe HTTP, strict responses and authoritative Java businessResults.
- [x] Default single; switch aborts and rotates conversation UUID; render actual handoffs/tools as text; optional material/operation/order context.
- [x] Workbench real queries and explicit work-order create/START/COMPLETE/CANCEL forms; no model writing.
- [x] Targeted Maven runs coordinated because mes/target is shared; Node independently.

## Task 5 — Integration/delivery
- [x] Spec review then code-quality review; fix findings and rerun affected checks.
- [x] Full local-profile Maven, explicit DB integration, Python/ruff, Node, migration/seed idempotence.
- [x] Rebuild/restart managed local services; smoke original single and new four-role request with real teaching master/order data.
- [x] Update README/run guide/journal with actual evidence and usage; no push/production deployment.