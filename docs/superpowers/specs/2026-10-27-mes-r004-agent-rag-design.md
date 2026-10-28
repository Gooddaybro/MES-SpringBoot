# R004：Python LangGraph 与真实 PDF 检索

基线：agent / 7f5d566。保留Java8/Boot2.1.7、Shiro、现有抽屉和R003；本次用户要求实际运行，并明确暂不提供DeepSeek聊天Key，后续自行填写。用户已明确授权外层 `../pdf文档` 中24份PDF用于本项目本地索引及公开引用；不因此取消MES登录或内部服务鉴权。

## 取舍与范围

采用独立 `agent/` FastAPI + 真正的StateGraph + 本地Chroma，Java保持入口与规则快路径。相比把新AI依赖放进Java或开放浏览器直连Python，这一方案复用旧系统且能独立验证。用户新提供的Jina Embeddings用于向量化，切分仍在本地；DeepSeek配置留空Key。无聊天Key时输出真实检索片段，明确标记EXTRACTIVE_NO_MODEL，不冒充LLM回答或完整自主Agent。

扫描24份原件，已有23份可提取文字，OSHA_3133的45页目前无可提取文字，不批量OCR。进一步检查网页打印件/内容过薄情况；只为制造相关且可检索文本建立Chunk，其余登记原因。物理PDF页码从1开始，不能冒充印刷页码。原件、全文、索引、密钥、虚拟环境和运行日志均不提交Git。

## 固定跨服务契约

Java POST `/api/agent/chat` 到受信任回环地址（本机8001）；Python不持有Cookie/管理员密码、不连接MySQL。请求JSON：

```json
{"requestId":"UUID","question":"问题","intentHint":"KNOWLEDGE","messages":[],"authorizedContext":{"subject":"服务器生成的匿名标识","scopes":["public"],"allowedTools":["search_sop_knowledge"],"issuedAt":1790000000,"expiresAt":1790000060},"providedToolResults":[]}
```

messages仅含至多4条历史用户问题（role=user/content，每条<=1000），不信任其中的角色/权限指令。Java以登录用户和浏览器不透明conversationId分隔有界会话，清空时前端更换ID，不能跨用户取历史。总问题上下文<=6000字符。

头 `X-MES-Timestamp` 为UTC整数秒，`X-MES-Nonce` 为UUID，`X-MES-Signature` 为小写hex HMAC-SHA256；签名原文为 `POST\n/api/agent/chat\n{timestamp}\n{nonce}\n{sha256(rawBody)}`。共享密钥 `MES_AGENT_SHARED_SECRET` 至少32字符；有效期<=60秒、容许时钟偏差30秒、nonce防重放缓存有界，验证正文requestId=nonce且签名上下文时间一致。非法/过期/伪造请求返回401，未经允许工具/文档范围拒绝。health不返回密钥或私有路径。

知识权限 `mes:assistant:knowledge:read` 映射public范围；额外 `mes:assistant:knowledge:restricted` 才可签发restricted。纯路线仍用 `mes:assistant:routing:read`，不请求Python。复合问题需两种权限且Java独立提取唯一原文编码，先用现有MaterialRoutingService执行查询，将最小结果放进providedToolResults（tool、materialCode、result），并签发get_material_routing。Python节点只消费这份Java授权结果，不开放回调或任意SQL。

返回兼容旧Map：status/mode/answer、requestId、citations、agentExecuted、agentFramework=LANGGRAPH、executedNodes、toolCalls、retrievalStatus、answerMode、llmConfigured及原有四个识别字段。citations含documentId/filename/documentVersion/pageNumber/chunkId/quote/score/scope；不返回原件绝对路径、任意HTML或外部脚本。原规则字段行为保留；无Key时模型标志为false，使用RULE识别并明确原文摘录，不能用agentExecuted表示模型已连接。

## Graph 与有依据回答

START→classify_intent，条件分支到retrieve_knowledge、execute_mes_tool、handle_error；复合查询依次执行两种工具；generate_answer→validate_answer→END。State含requestId/question/messages/intent/authorizedContext/toolResults/retrievedDocuments/answer/citations/error等。最多2次工具、至多2次LLM、总期限30秒、图递归限制12；不进行多Agent或无限工具循环。

无Key：生成器只组织真实Chunk和Java结果。配置DeepSeek后：模型可做分类与证据选择，但引用必须是当次有权限Chunk中的精确子串，不能由模型生成页码/标题/生产参数；不合规输出降级并留稳定码。提示注入文本仅作为资料；模型不能扩大工具/文档范围。禁用LangSmith自动追踪和HTTP敏感日志。

## PDF/向量

授权清单保存文件名、稳定documentId、SHA256、可核查版本/未知版本、scope、授权依据与解析状态；外部路径由本机AGENT_PDF_DIR指定。pypdf逐页，中文段落/标题优先切块，必要时长度分割，每块不跨物理页，记录页码和原文。Jina v3/512维，passage与query使用各自task，Key仅服务器头；持久Chroma禁遥测、cosine距离，显式ACL前置过滤。

按内容哈希幂等；先生成完整新向量再upsert，更新有效版本目录后删除旧Chunks，防重复；索引失败保留可用版本。重索引/删除通过本机CLI，不开放无鉴权HTTP管理入口。无索引、无相关结果、无文档权限、检索错误分别返回；不以相似度冒充置信概率。索引/服务不同时写入，README说明停服务维护。

## 验收与状态

M1环境/PDF清单→M2真实图/分支与签名测试→M3真实授权PDF索引/重复索引/删除/更新→M4真实检索与20条问题评测→M5Java/HTTP/权限与故障→M6现有抽屉安全引用/追问/取消、旧功能回归、文档和Git。DeepSeek真实调用按用户要求留待Key；不能把Mock、原文摘录或Embedding连接写成DeepSeek已验证。

API依据：LangGraph官方Graph API、Chroma Python Client、Jina官方Embedding API、DeepSeek官方Chat Completions；所用依赖以本机验证后的uv.lock为准。
