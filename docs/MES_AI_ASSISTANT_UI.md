# MES 前端智能助手：界面与交互设计

> 版本：R002 / 2026-10-25
> 状态：已提交代码到 feature/mes-assistant-chat-ui 分支；尚未在用户本地启动和验收。
> 当前功能：规则式工艺路线查询（不是已经接通的 LangGraph Agent）。

## 1. 界面结构

入口位于 MES 后台首页右下角：一个蓝色「智能助手」浮动按钮。点击后在右侧弹出聊天面板，关闭后不影响原先导航、业务标签页和菜单。

聊天面板布局：

    ┌─────────────────────────────────────────┐
    │ MES 智能助手                     [关闭]  │
    │ 工艺查询版 · 暂未接入大模型              │
    ├─────────────────────────────────────────┤
    │ 助手：我可以查询 MES 的真实工艺路线     │
    │                                         │
    │          用户：物料编码为 X 的工序？    │
    │                                         │
    │ 助手：从数据库查到 N 道工序             │
    │    1. [工序名称]                         │
    │    2. [工序名称]                         │
    │    来源：物料→路线→工序                 │
    │                                         │
    ├─────────────────────────────────────────┤
    │ [我能做什么?] [工艺路线与工单] [怎样查询] │
    │ [输入问题……]                    [发送]   │
    │ Enter 发送 / Shift+Enter 换行 / 只读    │
    └─────────────────────────────────────────┘

手机屏幕自动改为近全屏的弹层。所有来自用户或数据库的消息均作为文本节点插入，避免 HTML 注入。

## 2. 当前交互流程

1. 用户登录后台 /admin/index，点击右下角助手。
2. 用户输入：物料编码为 X 的工序有哪些？X 必须是实际存在的数据库物料编码。
3. 前端 POST /admin/assistant/chat，form 参数为 message。接口由 Shiro 的 /admin/** 登录规则保护。
4. 后端读取编码：sp_materile.materiel → flow_id → sp_flow.id → sp_flow_oper_relation.flow_id（按 sort_num 升序）→ sp_oper。
5. 前端显示工序、错误提示、数据来源。无此物料、无关联路线、无工序都明确提示，不会虚构结果。
6. 如果询问其他功能，如设备预测、质检异常分析：明确说明未接入，绝不假装已有数据。

## 3. 分层设计与下一步的 Agent

当前链路：

    浏览器聊天框 → POST /admin/assistant/chat → Java 规则识别 → Java 只读工艺查询 → MySQL

未来链路（尚未实现）：

    浏览器聊天框 → Java 鉴权网关 → Python LangGraph（带 Tool Calling）
                   → Java 工艺 / 工单 / 质检查询接口 → MySQL
                   → LLM 用查到的真实数据生成回答

划分原则：Java 做权限、事务与结构化查询；LangGraph 做自然语言意图理解和受约束的工具编排；RAG 用于查询 SOP 或维修手册。生产数据不允许由模型生成或任意修改。

## 4. 验收检查表

- [ ] 登录后，右下角显示按钮，能够打开与关闭；页面标签切换不遮挡关键功能。
- [ ] 桌面和手机布局无溢出，Esc 可以关闭，Enter 能发送，Shift+Enter 能换行。
- [ ] 用户输入和数据库字段不会被当 HTML 渲染。
- [ ] 有真实物料及工序时，按 sort_num 依序展示。
- [ ] 不存在编码、未绑路线、无工序能区分反馈。
- [ ] 网络异常、后端不可用、未登录提示明确。
- [ ] 对比接口查询与数据库内容一致；关注旧数据库状态字段语义。
- [ ] 确认该接口的细粒度操作权限、日志和 CSRF 风险，再考虑生产环境。
- [ ] JDK8/Maven 本地构建与测试通过；当前未运行。

## 5. 涉及文件

- 页面入口：mes/src/main/resources/templates/admin/index.ftl
- UI 组件：mes/src/main/resources/templates/admin/assistant/widget.ftl
- 样式：mes/src/main/resources/static/css/mes-assistant.css
- 交互脚本：mes/src/main/resources/static/js/mes-assistant.js
- API：mes/src/main/java/com/wangziyang/mes/assistant/controller/MesAssistantController.java
- 查询服务：mes/src/main/java/com/wangziyang/mes/assistant/service/MesAssistantService.java
- 变更日志：docs/MES_REFACTOR_JOURNAL.md

## 6. 待解决事项

- 没有配置 LLM、LangGraph、API Key，通用 AI 对话未接入。本版本只支持限定范围的工艺查询。
- 现有物料状态字段（is_deleted）含义应与用户的真实数据库数据核对，确定是否只允许正常状态物料查询。
- 生产时如涉及不同车间的数据隔离，必须在 Java 查询入口按身份授权；当前只有站内登录保护。
- 工艺查询当前按 ID 获取各工序详情，后续可优化批量查询，避免 N+1。
- 本轮仅经 GitHub 代码审阅，尚未本地运行浏览器验证。
