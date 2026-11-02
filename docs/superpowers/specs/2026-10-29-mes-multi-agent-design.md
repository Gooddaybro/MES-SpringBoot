# R005：物料、工序、工艺路线、工单多 Agent

用户已明确四项业务是**物料、工序、工艺路线、工单**。保留现阶段单 Agent，新增可切换多 Agent 版本。既有未提交 R004-M7 修复保留；在当前 agent 分支增量实现，不重置工作区、不推送、不改生产库。

## 设计与范围

采用协调器加四个独立业务 Agent，各有独立上下文、角色提示、工具白名单和编译后的 LangGraph 子图。保留 Java `/admin/assistant/chat`、Python `/api/agent/chat` 及契约；新增 Java `/admin/assistant/multi/chat`、Python `/api/multi-agent/chat`。抽屉默认单 Agent，可切换多 Agent，展示实际交接、工具及各 Agent 状态。

复用现有 Python 进程，避免四个独立部署；仅在原 Agent 增加工具又不满足本次要求。协调器受控路由，子 Agent 独立使用模型及业务工具，不开放 SQL 或无限循环。

前三项复用原主数据表/查询服务，不复制数据。新增真实持久化工单、工艺快照及显式创建/状态转换入口。本轮不新增 BOM、库存或报工。

## 业务规则

- 物料：按唯一正常编码查询，或最多列出20条。重复编码返回 AMBIGUOUS，不选第一条。
- 工序：按编码查询或最多列出50条；提供物料时只取其真实路线中的工序及顺序。工序定义不冒充执行记录。
- 工艺路线：复用 MaterialRoutingService.query，必须提供物料编码，保留缺路线、重复顺序等边界。
- 工单：数量正数、最多3位小数、工单号唯一。创建时冻结物料/工艺/有序工序快照。状态 CREATED→IN_PROGRESS→COMPLETED，CREATED→CANCELLED。仅登录后的显式表单可创建/转换。同号同物料同数量重复创建返回已有记录，冲突参数拒绝；相同转换重复提交不更新版本。完成仅表示人工确认状态，不虚构报工、库存或质检。

新增业务工作台：查询前三项，创建/查询/开始/完成/取消工单。已有主数据维护页保留。所有事实由 Java 读取，Python 不接数据库、不持有用户Cookie或DB凭据，没有业务写入工具。

## 新调用契约

Java 接受 message/conversationId，可选 materialCode/operationCode/workOrderCode。按关键词选业务；“全部/四个业务/综合”选择全部。缺必要编号返回 NEEDS_INPUT，不猜编号。每项先检查 mes:assistant:{material|operation|routing|workorder}:read；工单写入另查 mes:assistant:workorder:write。

Python 独立严格请求：requestId、question(<=1000)、messages(<=4)、requestedAgents(1–4个唯一 material/operation/routing/workorder)、authorizedContext(subject/scopes/allowedTools/issuedAt/expiresAt)、providedToolResults(<=4)。工具结果为 {tool,parameters,result}；四工具 get_material/get_operations/get_material_routing/get_work_order。角色只接收自己授权的工具结果，不放宽原单 Agent schema。

沿用路径绑定 HMAC：POST\n{path}\n{timestamp}\n{nonce}\n{sha256(rawBody)}，nonce=requestId，<=60秒授权，跨入口防重放，正文<=64KiB。

返回 requestId/status/mode=multi-agent/answer/agentFramework=LANGGRAPH_MULTI_AGENT/agentExecuted/executedAgents/agentResults/handoffs/modelAttempted/modelConnected/fallbackReason/elapsedMs，可含llmConfigured。agentResults项 {agentId,status,answer,toolCalls,executedNodes,modelAttempted,modelConnected,fallbackReason}；toolCalls项 {name,status,elapsedMs}；handoffs项 {from,to,status}。Java校验编号、角色/工具白名单、字段界限，并附加自己的businessResults，模型不能覆盖事实。

每个子 Agent 用自己的图、角色提示、私有模型上下文和唯一事实工具，模型交互有界，输出证据必须匹配本次授权事实。无Key/模型错误明确标记规则事实与降级原因。协调器只分派已授权业务，部分失败保留其他结果，总期限30秒，轨迹体现实际子图。

## 数据与验证

仅新增工单迁移及独立教学种子，不改旧表/原记录。权限按明确选定的本机学习角色增加，不授予restricted PDF权限。迁移和种子仅应用于明确本机mes_study/3308，重复执行不覆盖已有数据。

覆盖原单Agent回归、新签名/权限/重放、四个真实子图/上下文隔离、模型HTTP替身/无Key/超时/部分故障、真实MySQL主数据与工单快照/状态/幂等/并发，以及前端切换/清空竞态/文本安全/工单表单。真实模型烟测只说明测到的连接和场景。