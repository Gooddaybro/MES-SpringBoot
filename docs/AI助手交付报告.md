# AI助手交付报告

> 本页保留 **R002（2026-10-26）历史交付记录**，并在文末新增 **R003交付章节**。R002部分“未接入模型”等描述仅指当时状态；当前R003是Java8内“模型辅助的受控意图识别 + Java工具执行”，真实模型服务仍待验证。具体操作以 [运行指南](AI助手接入与运行指南.md) 为准，专项证据见 [R003模型适配与评测报告](R003模型适配与评测报告.md)，伺服样例见 [独立主数据说明](伺服控制器样例主数据.md)。

日期：2026-10-26。分支：`agent`。改造起点：`3d4433c`。本轮提交名称：`feat: 整合MES助手抽屉与真实工艺查询闭环`；最终SHA及远程推送核对结果见交付回复或运行 `git log -1`、`git ls-remote --heads origin agent`。

## 开发结果

现有Java8 / Boot2.1.7 / Layui / FreeMarker工程内实现了公共抽屉与按物料编码查询工艺路线的真实数据库闭环。Java负责身份、专用权限、校验、查询和异常；规则意图识别调用独立只读工具，未增加模型或Python依赖。所有写入意图明确拒绝，本轮没有报工/采购/整改/报修的虚构成功按钮。

已验证默认61项Java测试、显式MySQL集成测试1项、前端10项行为测试、带空格启动参数探针；Maven打包与新jar启动成功。实际浏览器完成验证码登录、6道工序查询、复制、清空、查询依据和物料iframe切换。真实MySQL停机时显示不可用，恢复后可重试。详细命令、结果和恢复方法见[运行指南](AI助手接入与运行指南.md)。

## 修改与新增文件

| 文件（仓库相对路径） | 目的 |
| --- | --- |
| `.gitignore` | 排除运行数据、构建产物、私有环境配置 |
| `docs/AI助手接入与运行指南.md` | 从零启动、真实接口/表结构、模型边界、P2–P4实施与验收 |
| `docs/AI助手交付报告.md` | 本轮范围、文件清单、验收与未完成边界 |
| `docs/LOCAL-RUN.md` | 将旧环境说明链接到当前运行指南 |
| `docs/MES_REFACTOR_JOURNAL.md` | 更新业务目标、保留历史并追加R002真实验证记录 |
| `docs/superpowers/specs/2026-10-26-mes-assistant-design.md` | 两版审查、兼容性和接口设计 |
| `docs/superpowers/plans/2026-10-26-mes-assistant.md` | 实施与验证任务记录 |
| `mes/src/main/java/com/wangziyang/mes/assistant/AssistantResponses.java` | 编码校验、统一依据字段 |
| `mes/src/main/java/com/wangziyang/mes/assistant/controller/MesAssistantController.java` | 登录/专用权限、两接口、错误与审计 |
| `mes/src/main/java/com/wangziyang/mes/assistant/service/MesAssistantService.java` | 规则意图、参数澄清、拒绝写入和未接入能力 |
| `mes/src/main/java/com/wangziyang/mes/assistant/service/MaterialRoutingService.java` | 精确有效物料查询、排序、批量工序与记录依据 |
| `mes/src/main/java/com/wangziyang/mes/basedata/entity/SpMaterile.java` | 修正状态注释为实际0/1/2规则 |
| `mes/src/main/java/com/wangziyang/mes/system/config/shiro/SpLoginFormFilter.java` | 助手未登录请求返回401 JSON，其他旧路径行为不变 |
| `mes/src/main/java/com/wangziyang/mes/system/service/impl/SysMenuServiceImpl.java` | 初始/搜索导航排除按钮，授权仍保留按钮 |
| `mes/src/main/resources/application-dev.yml` | 旧数据库口令改为服务端环境变量 |
| `mes/src/main/resources/application-pro.yml` | 旧数据库口令改为服务端环境变量 |
| `mes/src/main/resources/templates/admin/index.ftl` | 一次性挂载助手，使用已验证的模板根路径 |
| `mes/src/main/resources/templates/admin/assistant/widget.ftl` | 抽屉结构、输入及状态区域 |
| `mes/src/main/resources/static/css/mes-assistant.css` | 隔离样式、可读表格和窄屏布局 |
| `mes/src/main/resources/static/js/mes-assistant.js` | 安全文本渲染、AJAX、复制、键盘、清空及竞态 |
| `mes/src/test/java/com/wangziyang/mes/assistant/MaterialRoutingServiceTest.java` | 路线数据、状态/断链/排序及批量查询回归 |
| `mes/src/test/java/com/wangziyang/mes/assistant/MesAssistantServiceTest.java` | 意图、完整编码、歧义和写入拒绝回归 |
| `mes/src/test/java/com/wangziyang/mes/assistant/MesAssistantControllerTest.java` | 权限、输入、HTTP状态、错误脱敏与依据 |
| `mes/src/test/java/com/wangziyang/mes/assistant/SpLoginFormFilterTest.java` | 真实过滤器的助手401及原路径行为 |
| `mes/src/test/java/com/wangziyang/mes/assistant/SysMenuNavigationTest.java` | 初始/搜索导航与角色授权边界 |
| `mes/src/test/java/com/wangziyang/mes/assistant/MesAssistantDatabaseIT.java` | 显式本地库集成测试、真实Realm/SQL/模板、只读快照 |
| `scripts/local/Start-Local.ps1` | -Rebuild、旧jar进程处理、带空格启动参数 |
| `scripts/local/Stop-Local.ps1` | MySQL程序路径可配置，支持换机器 |
| `scripts/local/application-local.yml` | 有界连接、池等待与查询超时 |
| `scripts/local/application-business.example.yml` | 私有业务数据源配置示例 |
| `scripts/sql/mes_assistant_permission.sql` | 明确选择角色后幂等注册专用查询权限 |
| `scripts/tests/mes-assistant.test.cjs` | 10项客户端行为回归 |
| `scripts/tests/start-local-arguments.ps1` | 实际进程参数空格路径回归 |

没有整体合并参考分支、切换分支或创建新项目，也没有提交无关的已有修改；初始工作区干净。运行口令、私有配置、数据库、日志、jar和截图均不在本轮提交范围内。

## 当前运行方式

1. 进入本仓库，准备JDK8、Maven、MySQL8；本机执行 `scripts/local/Start-Local.ps1 -Rebuild`，换机器指定JDK/MySQL目录。
2. 打开 `http://127.0.0.1:9090/login-ui`，原本地样例账号admin/123，填写验证码；真实业务系统使用实际账号。
3. 为明确选定角色授予 `mes:assistant:routing:read` 后重新登录；本地原样例角色已通过显式SQL授权，其他机器需自行审核并执行。
4. 在首页右下角打开助手。当前样例编码100112可返回6道既有工序；它是学习数据，不代表伺服电机控制器的实际工艺。
5. 结构化工具：GET `/admin/assistant/material-routing?materialCode=...`；聊天：POST `/admin/assistant/chat`，表单message。

链路：抽屉 → Controller鉴权 → 规则意图 → MaterialRoutingService → 原Service/Mapper → MySQL → 带依据结果。模型不能绕过Java工具边界。

## 未完成与下一步

| 项目 | 状态与下一步 |
| --- | --- |
| 原工厂业务库 | 待接入；目前是独立mes_study样例库。按指南核对表/状态/主数据后用私有配置接入，不导入旧SQL覆盖工厂库 |
| 真实模型 | 待接入；无实际模型请求，填写环境变量不会自动生效。下一轮先实现受控意图适配器与故障测试 |
| P2 | 待接入；先修BOM字段映射/缺失子表，再补库存、工单与报工事务，最后接统一预览确认 |
| P3 | 待接入；先具备真实事实表和审核指标，再封装白名单查询，不开放任意SQL |
| P4 | 待接入；先准备授权真实文档、切分/Embedding/存储与检索验证，再做引用回答 |
| 原系统遗留问题 | 旧依赖、口令散列、原接口权限粒度需专项治理；窄屏原“选择模块”下拉为空，当前可通过左上侧栏进入物料菜单，本轮未改原spLayui该逻辑 |

数据库停机恢复、通用错误与查询超时测试不代表已验证模型服务故障；本轮没有可验证的真实模型/Python/RAG服务。

## R003 交付：Java8受控模型意图识别与安全降级

代码提交：[`9ae01c53fe8a3c1bd07bbdcb4cf4ccbd3d4ec80c`](https://github.com/Gooddaybro/MES-SpringBoot/commit/9ae01c53fe8a3c1bd07bbdcb4cf4ccbd3d4ec80c)，`feat: 接入MES模型意图适配与安全降级`，2026-10-27 11:32:00 +08:00；基线为R002 `781ed2d31e47bf50879e7ad07af40831683ba57b`。本次文档同步从干净的agent开始，核对代码、归档、测试记录和本机学习库；不只依赖实施计划。代码已提交、自动化通过和真实模型成功是不同状态。

### 已实施

| 代码证据（真实路径） | 实现范围 |
| --- | --- |
| `mes/src/main/java/com/wangziyang/mes/assistant/agent/LlmProperties.java` | 服务端MES_LLM_BASE_URL/MODEL/API_KEY，默认连接3秒/读取15秒，可选1–60000ms；端点/格式校验，未配置不阻止规则功能 |
| `mes/src/main/java/com/wangziyang/mes/assistant/agent/LlmIntentClient.java` | Java8 RestTemplate/Jackson，单次非流式Chat Completions；响应限长/严格JSON/意图白名单，稳定故障码，禁重定向和自动重试 |
| `mes/src/main/java/com/wangziyang/mes/assistant/agent/LlmIntentResult.java`、`MaterialCodeCandidates.java` | 不可变结果与独立完整原文候选；编码逐字/区分大小写，不接受模型猜测、子串、全角替换或非法字符裁剪 |
| `mes/src/main/java/com/wangziyang/mes/assistant/service/MesAssistantService.java` | 规则优先，只有规则未解决且唯一合法候选时请求模型；QUERY_MATERIAL_ROUTING/UNSUPPORTED白名单；失败澄清；事实复用原MaterialRoutingService |
| `mes/src/main/java/com/wangziyang/mes/assistant/controller/MesAssistantController.java`、`AssistantResponses.java` | 先Shiro登录与mes:assistant:routing:read，输入校验、HTTP业务状态、识别元数据/安全日志；保留既有只读数据库查询和真实依据 |
| `mes/src/main/resources/templates/admin/index.ftl`、`admin/assistant/widget.ftl`、`static/js/mes-assistant.js` | 原抽屉展示本次来源/失败码和复制，150秒等待预算，说明问题外发边界，不显示Key或虚构模型连接 |
| `mes/src/test/resources/assistant/intent-eval.jsonl`、测试包AssistantIntentEvaluationTest/legacy/LegacyR002Rules | 30条评测和原规则冻结；输出本机测量，未发布真实模型准确率 |
| `scripts/sql/servo_controller_sample.sql`、`scripts/tests/servo-controller-seed.ps1` | B0独立样例文件：6物料/1路线/6工序/6关联，显式学习库启用、幂等和冲突保护；与R003同属9ae01c5，未伪造独立B0提交 |

完整29文件及角色见 [R003专项报告](R003模型适配与评测报告.md) 和 `git show --name-status 9ae01c5`。没有升级Java/Boot或增加Python服务。

调用链为：浏览器→Shiro/Controller登录与专用权限→Java输入/安全规则→明确规则查询直接执行，或唯一原文编码的未解决问句请求模型→Java再校验→原只读Service/Mapper/MySQL→数据库事实+识别元数据。SQL、工艺事实、业务写入和授权决定均不交给模型；只外发本次问题与固定指令，问题本身可能敏感。

状态语义：

- 规则命中/规则拒绝/GET：RULE、false、false、null，零模型请求。
- 未配置需补位：RULE_FALLBACK、false、false、MODEL_NOT_CONFIGURED；已尝试后HTTP/网络/结构/原文校验失败：RULE_FALLBACK、true、false、相应原因码。
- 合规模型识别：MODEL、true、true、null；合规UNSUPPORTED是成功的范围外分类、不调用工具，未知意图则降级；合规主动澄清也不调用工具。
- 上述四值依次为recognitionSource/modelAttempted/modelConnected/fallbackReason。模型识别通过后DB失败仍保留其来源；modelConnected不证明业务成功，更不自动等于真实供应商已经全面验证。

### 已验证

| 实际记录 | 结果 | 覆盖边界 |
| --- | --- | --- |
| R003原生JDK1.8.0_202 / Maven3.6.3 package，2026-10-27 11:22:56结束 | 112项，0失败/错误/跳过，BUILD SUCCESS | 包含30客户端、16混合调度、2评测及原功能/权限回归；不是额外加30项 |
| R003显式MesAssistantDatabaseIT，11:25:44结束 | 1项通过 | 本机127.0.0.1:3308/mes_study，真实Realm、Mapper/SQL/模板、只读事务及四表快照；不算生产或验证码验收 |
| R003浏览器/启动记录 | 最终jar启动、验证码登录、SERVO六工序、未配置降级显示通过 | 仅本机学习环境；截图/运行日志在忽略的.local中，不随Git跨电脑复制 |
| R003主数据和启动参数探针 | 种子无启用不变、重复一致、冲突拒绝、原记录保护；空格路径通过 | 无生产库写入；文档同步本次仅只读复核6/1/6/6数量 |
| 本次文档同步重跑（11:42:11结束） | 客户端30+评测2=32项，0失败/错误/跳过 | 回环HttpServer/假Key或故障注入，不调用真实模型；评测决策计数与原归档一致 |
| 本次Node重跑 | 11项通过 | 实际前端脚本的文本/复制/IME/竞态/元数据；不替代重新做浏览器布局检查 |
| 本次文档检查 | 仅三份既有文档，git diff --check通过 | R002历史正文比对未变，原链接保留，围栏/本地链接和敏感信息检查通过 |

HTTP替身覆盖正常/401/429/5xx、空错误体、连接拒绝/读取超时和确定性连接超时注入、非法/重复/缺字段/错类型/未知意图/空/超长响应、重定向与断开后不重发。服务回归拒绝不在原话的编码、大小写/分隔符变化、子串、多码、常用词编码和控制字符；规则查询在各类模型故障后仍可用。未登录/无权限时不调用模型或业务工具。

本次文档任务没有重跑完整package、真实数据库IT、浏览器登录/部署，也没有修改业务代码。112+1是已经核实的R003验收记录，32+11是本次实际重跑。可复现命令和本地报告位置见运行指南第10节。

### 评测结果

输入 `mes/src/test/resources/assistant/intent-eval.jsonl` 共30条，来自学习样例、SERVO教学样例和明确合成输入。原规则冻结自781ed2d；R003侧在模型禁用时执行规则优先逻辑，工具模拟。E13/E26错码夹具是mock验证，不能当真实模型回答。

| 指标（R003归档） | R002规则基线 | R003规则优先、模型禁用 | 规则+真实模型 |
| --- | --- | --- | --- |
| 意图/可执行决策准确率 | 22/30，73.33% | 24/30，80.00% | 未执行，不推测 |
| 可执行编码准确率 | 6/12，50.00% | 6/12，50.00% | 未执行 |
| 错误工具调用数 | 1 | 0 | 未执行 |
| 澄清率 | 6/30，20.00% | 10/30，33.33% | 未执行 |
| 实际模型调用比例 | 0/30，0% | 0/30，0%（6条是补位候选） | 未执行 |
| 平均本机函数耗时 | 0.459907ms | 0.245840ms | 未执行 |

证据：[归档JSON](evals/R003-rules-offline.json)。编码分母为12条应执行查询的带码样例；无模型时六条同义句澄清，不计识别/编码正确。错误调用从1到0来自Java拒绝E17指令覆盖，不是未经测量的模型收益。耗时仅30次函数/模拟工具开销，不是HTTP/DB/模型端到端延迟，不能据此宣称稳定提速。

本次重跑计数逐项一致，均值为0.383370ms/0.225513ms；保留R003历史快照不改写。真实混合准确率、调用比例、错误调用数、延迟及成本全部待实测，不能把当前80%写成“模型加持后的准确率”。

### 待验证

- 本次进程MES_LLM_BASE_URL/MODEL/API_KEY三项均未配置，没有可核对真实供应商请求/成功响应记录；没有扫描或复用其他应用的Key。存在配置、Mock通过或单次modelConnected都不构成全面真实连接验收。
- 实际供应商模型对严格JSON/finish_reason、鉴权、限流、TLS/代理、最大超时和真实评测集的兼容性；按指南8.2先做最小真实验收，再记录实际结果。
- 真实工厂数据库/主数据、部署网络、生产负载、工厂/租户数据范围与整体安全治理；本机学习库6/1/6/6不是工厂生产数据。
- 网络读取仅空闲超时、不是整个请求总期限；前端150秒为配置预算留余量，不能保证所有持续慢速传输都按总期限结束。旧Boot/Shiro及遗留认证风险不因新增助手而消失。

### 尚未实现

完整多步Agent工具循环、LangGraph/Python独立服务、授权PDF导入/页码切块/Embedding/检索/RAG均未实现；未取得本项目授权PDF，不进行正式入库。当前只读工具与原BOM实体/控制器不能证明齐套、库存、工单状态机、报工预览/确认或受控统计已完成。`preview_work_report`未加入当前意图白名单，没有实际报工写入、确认事务或跨服务短期签名上下文。

### 后续各子阶段进入条件

| 子项 | 进入条件 | 状态/独立验收 |
| --- | --- | --- |
| A R003 | 保留Java8、规则/权限/原文边界可靠 | 已实施，自动化/学习环境已验证，真实模型验证计划中；评测可复跑、实际验证状态明确 |
| B0 样例主数据 | 四表字段已审、明确隔离学习库与教学数据 | 已验证（本机），独立SQL/日志/回滚；跨电脑另验 |
| B1 BOM/齐套 | 先核对SpBom映射与BOM头/明细/物料关系；独立迁移；真实齐套还需B2可用量依据 | 计划中，覆盖缺料/不足/重复，SQL一致性 |
| B2 库存 | 用户先定库存/单位/可用/预留/范围规则 | 计划中，再设计独立查询/迁移/测试，不采用演示库存 |
| B3 工单 | 用户先用自己的话写一页状态/转换/顺序/不能越站规则并审阅 | 计划中，先边界测试，再编码，Agent不代定全部规则 |
| B4 报工 | B3与数量规则可靠，权限和预览/确认契约已审 | 计划中，模型仅提意图；Java预览→用户独立明确确认→Java事务/幂等写入 |
| B5 统计 | 有授权可信事实和已审指标/维度/筛选/范围 | 计划中，白名单参数化查询，禁任意SQL，逐指标留验收 |
| C P4 Python/RAG | 同时满足R003稳定且Java权限/契约可靠，以及真实SOP/质检PDF明确授权 | 计划中，可独立启动，不等BOM/库存/工单全部完成 |

每项分别记录目标、代码/SQL范围、测试、实际Commit及代码/数据回滚。C的Python负责PDF解析、保留页码切块、Embedding、检索和LangGraph编排；Java继续负责身份、业务权限、结构化查询和生产写入。未来受控HTTP需requestId、用户数据范围与短期签名上下文并防伪造/越权；当前traceId不是已实现的签名机制。无授权文档不正式入库。提前练习LangGraph仅可作为用户选择的仓库外独立教学Demo，用模拟Java工具，不计主线完成。

详细路线和边界见 [运行指南第9节](AI助手接入与运行指南.md#9-第八步p2p4继续开发)，独立日志及回滚见 [MES_REFACTOR_JOURNAL](MES_REFACTOR_JOURNAL.md)。本次只同步三份既有文档；实际文档提交成功后在交付回复/文件Git历史核对SHA，不预填未产生的Commit。
