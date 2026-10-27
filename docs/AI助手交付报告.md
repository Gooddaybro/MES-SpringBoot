# AI助手交付报告

> 本页保留 **R002（2026-10-26）历史交付记录**。当前 R003 已实现 Java 8 模型意图适配器和安全降级，实际配置与验收见 [R003模型适配与评测报告](R003模型适配与评测报告.md)；伺服样例见 [独立主数据说明](伺服控制器样例主数据.md)。下文“未接入模型”等描述仅指 R002 当时状态。真实模型凭据和供应商实测仍待补齐。

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
