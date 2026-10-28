# MES R004 Agent / PDF RAG Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** 保持Java8旧MES，新增可运行Python图与真实PDF索引/检索，安全接入原抽屉；不使用真实DeepSeek聊天Key。

**Architecture:** Java登录/权限/原文规则→签名FastAPI→有限StateGraph→Jina向量/Chroma与Java预执行工具结果→有来源响应。用户授权24PDF本地入库与公开引用，扫描件不批量OCR。

**Tech Stack:** Python3.11独立venv、FastAPI/Pydantic、LangChain/LangGraph、pypdf、Chroma、httpx、Jina Embeddings；Java8 RestTemplate/Jackson/Shiro；原Layui/FreeMarker/JS。

---

## M1 / Python环境与PDF清单

- [x] 复核agent/7f5d566、工作区、源码及原验收；建立独立agent/.venv，不修改其他应用Python环境。
- [x] 新建agent/pyproject.toml并uv lock/sync；新增.gitignore规则，私有配置位于.local/agent.env，真实Key不进Git/日志。
- [x] 依据已读取的24份原文/元数据生成清单与docs/R004-PDF清单.md，记录大小/页数/中文/提取/扫描/类型/版本/授权/范围，不把文件名当真实内容。

## M2 / FastAPI、签名与Graph（先红后绿）

- [x] tests/test_security.py先测HMAC/过期/重放/正文篡改/上下文范围，运行pytest确认失败，再实现app/config.py、schemas.py、security.py和main.py。
- [x] tests/test_graph.py先测真实StateGraph节点序列、条件路由/复合两工具、无Key原文、权限/无资料/超时、引用原文一致，再实现graph.py、llm.py、tools/mes_tools.py。
- [x] GET/health与POST/api/agent/chat可真实启动；脚本绑定127.0.0.1:8001，分开DeepSeek聊天配置与Jina向量配置；禁敏感追踪。

## M3 / 真实PDF与Chroma（先红后绿）

- [x] tests/test_rag.py先覆盖逐页提取、中文/英文切分边界、幂等/版本更新/删除/ACL和无结果，再实现rag/{loader,splitter,embeddings,vector_store,retriever}.py、scripts/{inventory,ingest}.py。
- [x] 使用pypdf真实解析；Jina只做向量，Chroma本地存储；真实数据与测试内存Embedding分开标记，后者不用于真实评测。
- [x] 已授权制造文本正式索引；45页扫描件登记未索引且不OCR；重复运行数量/哈希一致，验证删除/更新仅限测试临时库。

## M5 / Java安全接入（独立文件任务，先红后绿）

- [x] 新增assistant/agent/PythonAgentProperties.java、PythonAgentService.java，按spec固定JSON/HMAC契约；MES_AGENT_ENABLED/BASE_URL/SHARED_SECRET，3秒连接35秒读取，不跟随重定向/不转交Cookie。
- [x] 扩充Controller注入/聊天路由/知识权限，保持旧二参测试构造和GET路线。Java先校验原文复合参数并用原只读服务取最小结果，不提供匿名回调。
- [x] 新增PythonAgentServiceTest本地HTTP替身、Controller知识权限测试，验证请求签名/上下文、空配置/故障、路线不调用Python、错误响应/引用限长、日志不泄密。
- [x] 增量改Start-Local读取受限MES_AGENT_*私有配置；独立知识权限SQL显式角色启用。父任务实际JDK8全量回归/显式SQL IT。

## M4 / 真实检索评测

- [x] 从真实可读页编写agent/evals/rag-eval.jsonl（20条），记录期望documentId/物理页/答案片段或拒答；不假造控制器手册。
- [x] scripts/evaluate.py真实Jina/Chroma检索，统计命中、页码、引用原文、拒答与耗时，归档脱敏JSON和报告；无聊天Key时明确不测LLM总结正确性。

## M6 / 前端、联调、交付

- [x] Node测试先覆盖citation纯文本、识别/检索/无Key状态、conversationId清空更新和旧竞态，再改mes-assistant.js/widget.ftl/css；只沿用原AJAX契约。
- [x] Start-Agent/Stop-Agent回环进程脚本与agent/README/.env.example；签名真实Java→Python→索引查询、复合Java查询和原规则查询；故障不影响旧功能。
- [x] 浏览器验证最终jar的真实引用/页码/追问/清空；更新三份主文档并追加独立R004日志，保留R000–R003与原链接。
- [x] 规范/质量两阶段审查，Python/Java/Node测试，diff与敏感信息检查；仅相关源码/锁文件/脱敏证据提交并推送agent；真实DeepSeek留待用户填Key。
