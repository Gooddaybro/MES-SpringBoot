# MES 助手 Implementation Plan

> 按 subagent-driven-development / executing-plans 的检查与复核流程，在当前 agent 分支实施；用户已授权持续执行与最终提交推送。

**Goal:** 交付可运行的全局助手抽屉和按物料编码查询真实数据库的只读闭环，以及可复现中文教程。

**Architecture:** 保留Layui/FreeMarker及Java8单体；Controller负责身份/专用权限/异常，规则意图服务调用独立工艺工具，工具复用现有Service/Mapper。模型和Python服务保持待接入。

**Tech Stack:** Java8 / Boot2.1.7 / MyBatis-Plus3.1.2 / Shiro1.4 / JUnit4+Mockito / 原生DOM+jQuery。

## 任务1：后端工具、接口与权限

- [x] 在 `mes/src/test/java/com/wangziyang/mes/assistant/` 添加失败测试：缺少/多编码、写入拒绝、有效物料过滤、重码、无路线/工序、断链/排序、批量工序、权限及异常。
- [x] 用JDK8执行 `mvn -f mes/pom.xml -Dtest=MesAssistantServiceTest,MaterialRoutingServiceTest,MesAssistantControllerTest test`，确认新增能力尚不存在导致失败。
- [x] 新建 `assistant/service/MesAssistantService.java`、`MaterialRoutingService.java`、`assistant/controller/MesAssistantController.java`，实现设计协议；不修改原业务写接口。
- [x] 新建 `scripts/sql/mes_assistant_permission.sql`，必须显式选择已有角色，幂等注册专用权限；无角色不授权。
- [x] 同命令验证通过，按规格与代码质量分别复核。

## 任务2：公共抽屉与前端回归

- [x] 新建 `scripts/tests/mes-assistant.test.cjs`，用Node自带断言和轻量DOM替身验证实际事件/请求/渲染行为；先运行 `node scripts/tests/mes-assistant.test.cjs` 观察缺少脚本失败。
- [x] 新建 `templates/admin/assistant/widget.ftl`、`static/css/mes-assistant.css`、`static/js/mes-assistant.js`；公共 `templates/admin/index.ftl` 在body结尾挂载一次。
- [x] 覆盖开关/键盘/组合输入、纯文本渲染、复制、网络/401/403/超时、清空取消与迟到响应、重复加载不重复绑定。
- [x] 重跑Node测试；浏览器实际验收后将可复现操作写入指南。

## 任务3：配置与文档

- [x] 将 `.local/`、构建产物和本地密钥配置写入共享 `.gitignore`；修正物料状态注释。
- [x] 本地启动脚本支持显式重建，避免运行旧jar；数据库连接有有界超时，保留现有数据。
- [x] 新建 `docs/AI助手接入与运行指南.md`，整合LOCAL-RUN、历史改造日志和两个原型审查；更新旧文档入口并标记旧路线已被本轮取代。
- [x] 逐项列出现状、真实文件/字段/API、从零启动命令、配置与排错、模型待接入边界、P2–P4缺口/文件/顺序/验收。

## 任务4：运行验收、交付与推送

- [x] JDK8运行单元测试、原有上下文测试和 `mvn -f mes/pom.xml package`；所有命令使用local profile及私有环境变量，日志不含密钥。
- [x] 重启新jar，真实登录并验证两接口、数据依据、原菜单/物料/路线页面；查询对照现有mes_study样例，不写入虚构生产数据。
- [x] 验证401/403/400、成功/无数据、数据库不可用；记录无法验证的模型和真实工厂场景。
- [x] 更新计划复选框和指南的实际验证结果，独立规格/质量复核后修正问题。
- 发布步骤：`git diff --check`、指定文件暂存、敏感信息检查、以 `feat: 中文功能描述` 提交。普通 `git push -u origin agent`，用 `git ls-remote --heads origin agent` 核对SHA，不强推；执行结果由交付回复记录，避免在待发布文件中预写未来结果。

运行时秘密仅保存在被忽略的 `.local/`。初始无用户未提交修改；后续仅暂存本计划涉及的代码和文档。
