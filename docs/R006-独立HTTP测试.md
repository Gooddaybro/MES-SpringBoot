# R006：独立Java/Python HTTP联调复现

这是显式启用的学习环境验收，不是部署脚本。使用既有3308/mes_study、独立Python8002、真实Shiro Realm及真实业务SQL，不调用供应商、不更改现有8001服务、不新建RAG索引。MVC入口由测试模拟，不能将其描述为真实浏览器/验证码登录。

## 准备

仓库根目录运行；需要原`.local/application-idea.yml`私有学习配置、原SERVO-DEMO主数据及工单、学习账号已具备四项读取权限。未具备数据或权限时应停止并如实记录，不通过改保护条件、授予管理员或补造数据让测试通过。

先确认8002没有其他进程；以下Python只复制私有配置生成独立文件，原件保持不变，密码与共享密钥不会打印：

```powershell
if(Get-NetTCPConnection -State Listen -LocalPort 8002 -ErrorAction SilentlyContinue) {
    throw '8002已被占用，请核实后停止自己创建的测试服务，不能替换未知进程。'
}
uv sync --frozen --project agent
@'
from pathlib import Path
import secrets
import yaml

private = Path('.local')
config = yaml.safe_load((private / 'application-idea.yml').read_text(encoding='utf-8'))
assert config['spring']['datasource']['url'].startswith('jdbc:mysql://127.0.0.1:3308/mes_study?')
secret = secrets.token_urlsafe(48)
(private / 'r006-test-secret').write_text(secret, encoding='utf-8')
config['MES_AGENT_ENABLED'] = 'true'
config['MES_AGENT_BASE_URL'] = 'http://127.0.0.1:8002'
config['MES_AGENT_SHARED_SECRET'] = secret
(private / 'application-r006-agent-it.yml').write_text(yaml.safe_dump(config, allow_unicode=True), encoding='utf-8')
print('独立私有测试配置已生成；原配置未改，密钥未输出。')
'@ | uv run --frozen --project agent python -
```

## 启动测试服务

在另一个终端、同一仓库目录执行并保持运行：

```powershell
uv run --frozen --project agent python agent/tests/run_readonly_smoke_server.py --secret-file .local/r006-test-secret --port 8002
```

服务使用生产代码的create_app/MultiAgentWorkflow。Settings明确不读取原agent.env，聊天/向量Key置空；知识检索在该专用测试中不可调用，不是伪造业务事实的工具。

原终端检查：

```powershell
Invoke-RestMethod http://127.0.0.1:8002/health
```

应为status=ok、serviceAuthenticationConfigured=true、llmConfigured=false、embeddingConfigured=false。这只检查配置/健康，真实签名调用由下面测试验证。

## 执行

```powershell
$env:JAVA_HOME='D:/Program Files/Java/jdk1.8.0_202'
$env:Path=$env:JAVA_HOME+'/bin;'+$env:Path
mvn -f mes/pom.xml '-Dspring.profiles.active=local' '-Dspring.config.additional-location=file:D:/Documents/ChatGPT/manufacture/MES-SpringBoot/.local/application-r006-agent-it.yml' '-Dmes.readonly.agent.it.enabled=true' '-Dtest=ReadOnlyAgentDatabaseIT' test
```

替换自己的JDK/配置绝对路径，但保持测试保护的127.0.0.1:3308/mes_study与8002目标。账号默认是既有本地教学admin，不向生产账号外推；若本机已调整学习账号，使用本机私有测试属性，不将凭据提交Git。

4项测试：

1. 实际存在的绝缘与低压功能测试工序 → 唯一工序ID → 当前路线/物料 → CREATED历史工单 → 真正LangGraph执行并返回数据库事实。
2. 实际不存在的“老化测试” → NO_OPERATION → 仅工序子图执行，不调用下游。
3. 真实既有WO-SERVO-DEMO-001与当前路线对比 → 六工序一致，历史JSON不变。
4. 真实关闭端口连接失败 → agentExecuted=false/AGENT_UNAVAILABLE，保留真实数据库物料事实。

学习库只读事务回滚，测试不创建工单。运行其他Spring数据库测试时使用独立Maven命令，避免原Ehcache上下文冲突。

## 结束

在自己启动测试服务的终端按Ctrl+C，现有8001/9091不受影响。私有生成文件保持在忽略目录.local，可按自己的保留策略归档；不要上传密钥、完整日志或Cookie。

本轮记录见[验收报告](R006-能力盘点与第一批验收.md)与[脱敏JSON](evals/R006-readonly-20261102.json)。
