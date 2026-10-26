# MES 学习框架：先理解生产主线，再沿项目补细节

更新：2026-10-21。目标：围绕当前开源二次开发项目，逐步具备 MES Java 后端的业务分析、独立实现和项目讲解能力。本文是学习路线，不代表已经掌握或已经完成开发。

## 一句话定位

MES 关注车间生产如何执行、执行到了哪里、产生了什么结果。它连接企业计划与现场执行；ISA-95 将制造运营管理放在第 3 层，将包含 ERP 的业务计划与物流放在第 4 层。[ISA 官方说明](https://www.isa.org/standards-and-publications/isa-standards/isa-95-standard)

因此，学习核心可以先抓住五个问题：**做什么、按什么做、这次做多少、实际做了多少、结果是否合格且可追查。** SAP 的 MES 概述也将生产监控、数据记录、质量与产品追踪列为重要能力。[SAP MES 概述](https://www.sap.com/uk/resources/what-is-mes)

## 我们采用的业务骨架

以下是为当前项目裁剪的教学主线，面向简化的离散生产场景；它不是行业统一流程，也不等同于当前项目已有功能。

| 环节 | 要回答的问题 | 关键对象与结果 |
|---|---|---|
| 基础定义与资源 | 做什么、如何做、在哪里做？ | 物料、BOM、工序、工艺路线、人员与设备等 |
| 工单计划 | 本次生产什么、多少、何时完成？ | 工单、计划数量、所用路线、状态 |
| 执行与报工 | 谁在何时完成哪一步、完成多少？ | 开工/完工记录、工序记录、合格和不良数量 |
| 质量处置 | 检查什么、不合格如何处理？ | 检验结果、不良原因、返工或报废记录 |
| 完工与库存协同 | 生产结果如何交接、如何追查？ | 完工结果、入库或回传记录、历史记录 |

质量检查可以嵌入各工序。库存也可以由 ERP/WMS 等系统协同管理，具体边界需按业务确定；学习时先实现简化记录，再考虑接口。SAP 的官方示例包含工序中检验、报工和报废数量、消耗与收货信息回传 ERP，以及仓储系统协同，可用来理解这类边界。[SAP 执行与协同示例](https://learning.sap.com/courses/discovering-sap-digital-manufacturing/exploring-the-integration-of-sap-digital-manufacturing-with-sap-s-4hana_b2ffd41c-64b9-4f73-a802-8528562e2cb7)

先区分四个对象：**物料是被生产或消耗的对象；工序是一项工作；路线组织工序及其顺序；工单是一次具体生产任务。** 同一种物料可以多次生产，不应把物料档案当成一次工单。

## 当前项目位于哪里

[README](D:/Documents/ChatGPT/manufacture/MES-SpringBoot/README.md) 明确含“开发规划”。完成程度应以代码与运行结果为依据。

| 状态 | 当前能确认的内容 | 证据与边界 |
|---|---|---|
| 已实现，物料流程已验证 | 物料维护、查询并关联已有路线 | `POST /basedata/materile/add-or-update`；`sp_materile`、`sp_flow`；[保存入口](D:/Documents/ChatGPT/manufacture/MES-SpringBoot/mes/src/main/java/com/wangziyang/mes/basedata/controller/SpMaterileController.java:114)先查路线、补名称再保存物料 |
| 已有路线保存实现 | 用工序顺序构成路线 | `POST /basedata/flow/process/add-or-update`；`sp_oper`、`sp_flow_oper_relation`、`sp_flow`；[事务方法](D:/Documents/ChatGPT/manufacture/MES-SpringBoot/mes/src/main/java/com/wangziyang/mes/technology/service/impl/SpFlowOperRelationServiceImpl.java:95)保存路线头与工序关系；本轮尚未端到端验证路线修改 |
| 部分实现 | BOM 页面、实体和查询等代码 | [保存按钮](D:/Documents/ChatGPT/manufacture/MES-SpringBoot/mes/src/main/resources/templates/technology/bom/addOrUpdate.ftl:181)直接提示暂时无法保存；不能当作可用 BOM 闭环 |
| 演示功能 | 智慧大屏 | [看板脚本](D:/Documents/ChatGPT/manufacture/MES-SpringBoot/mes/src/main/resources/static/js/mes/digitization/js.js:12)使用随机数与固定数组生成演示数据，不能据此认定有真实报工或库存统计链路 |
| 后续待开发 | 工单、报工、质检、库存协同 | 当前代码及已导入的 17 张表未形成这些完整链路；[SQL 中工单菜单](D:/Documents/ChatGPT/manufacture/MES-SpringBoot/scripts/sql/MySQL-sparchetype_20200330.sql:317)仍为占位。新增时需逐条设计和验证 |

项目另有用户、角色和菜单等系统管理能力。我们先沿物料与路线进入业务；演示“车轴流程”只用于练习，不作为真实机械加工工艺标准。

## 学习阶段与面试解释能力

下表是学习安排，不是岗位要求统计。每次只推进一个小任务：演示、独立操作、反馈纠错、脱离原代码复现。

| 阶段 | 可验证产出 | 对应 Java 知识 | 能解释的问题 |
|---|---|---|---|
| 1. 理解对象，操作现有流程 | 自己建立并查到一条物料，解释所关联的路线与数据变化 | 实体、主键、表关联、SQL 查询 | 物料、路线与工单为什么分开？ |
| 2. 追踪现有代码 | 从一次保存定位请求、方法与 SQL，并画自己的调用草图 | Spring MVC 参数绑定、依赖注入、MyBatis-Plus、事务 | 这次请求经过哪些层，事务保护哪些操作？ |
| 3. 自建简化工单 | 自己设计并实现创建、开工、完工；验证非法状态转换 | 分层、DTO、校验、状态建模 | 已完工任务为何不能再次开工，规则放在哪里？ |
| 4. 补充执行与报工 | 保存工序与数量记录，验证超量及重复提交 | 数据库事务、幂等、并发一致性 | 重复点击报工如何避免数量重复累加？ |
| 5. 补质量和结果追踪 | 查到某次工单的生产与检验历史，再设计库存交接边界 | 关联查询、业务约束、接口设计、异常处理 | 不合格品怎样处置，完工与入库有什么关系？ |

技术执行链先记住：页面提交 → Controller → Service → Mapper → MySQL → 返回结果。原项目部分业务就在 Controller，例如物料关联路线；阅读时追真实调用，不假设所有 Service 都有自定义实现。

面试准备围绕每条自己完成的流程，保留业务草图、表关系、关键规则、验证证据与个人贡献。能独立演示、定位错误并解释设计后，再将对应能力写入简历。

## 第一轮只完成认知任务

用现有示例物料 `MAT-DEMO-001` 及其关联路线思考一个场景：**本周生产 20 件该物料，下周再生产 50 件。** 用物料、工序、工艺路线、工单四个对象，说清各自记录什么，以及第二次生产时哪些定义可以复用、哪些任务数据需要新建。先给出自己的判断。

这一轮验收：能解释这四个对象的分工和关系。随后才进入页面实操与代码定位。演示由 AI 完成，只能证明流程可运行；独立操作和解释才作为学习掌握的证据。
