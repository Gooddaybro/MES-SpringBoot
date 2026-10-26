# 本地启动与运行验证

> 2026-10-26：助手接入、专用权限、完整构建测试与其他机器启动请看 [AI助手接入与运行指南](AI助手接入与运行指南.md)。代码变化后使用 `Start-Local.ps1 -Rebuild` 重新构建；下文验证记录为早期环境基线。

配置日期：2026-10-20。项目来自 https://github.com/wangziyang6/MES-SpringBoot ，基线提交 `64e84cac6b4fa2f95f687422d97e2b820de3755f`。原分支为 master，原 Java 业务代码、POM 与 SQL 文件未修改。

## 启动

在 PowerShell 中执行：

```powershell
Set-Location 'D:\Documents\ChatGPT\manufacture\MES-SpringBoot'
.\scripts\local\Start-Local.ps1
```

打开 http://127.0.0.1:9090/login-ui 。学习用原始样例账号：`admin`，密码：`123`，填写页面图片验证码。仅绑定本机地址。

脚本使用本机已安装的 JDK 8 和 MySQL 8 程序，第一次创建独立数据库目录、导入原项目 SQL、下载 Redis Windows 便携开发版本，并在没有 jar 时构建项目。之后启动复用已有数据，不会重新导入 SQL。

默认程序路径：

- JDK：`D:\Program Files\Java\jdk1.8.0_202`
- MySQL：`D:\Program Files\mysql-8.0.46-winx64`

换机器时用脚本的 `-JdkHome` 和 `-MySqlHome` 参数指定对应安装目录；还需要 Git、Maven 和首次下载时的网络。Redis 开发版来源：https://github.com/tporadowski/redis/releases/tag/v5.0.14.1 。此配置用于复现旧项目的本地学习环境，不是生产部署方案。

## 服务与文件

在 IDEA 中启动时，先将 `mes/pom.xml` 添加为 Maven 项目，再从右上角运行配置选择 **MES-IDEA-Local**，点击绿色运行按钮。该配置已经指定 JDK 8、local profile 和私有的 `.local/application-idea.yml`。IDEA 实例使用端口 **9091**，访问 http://127.0.0.1:9091/login-ui ，与脚本后台实例的 9090 区分。本机 MySQL 3308 和 Redis 6380 需保持运行。IDEA 的实际启动结果以 Run 控制台和该地址为准。

`.local/application-idea.yml` 含本地数据库密码，已忽略，不应提交；共享的 `.run/MES-IDEA-Local.run.xml` 不含密码。若将来重新生成数据库密码，需要同步更新私有 IDEA 配置。

| 服务 | 地址 | 数据或配置 |
| --- | --- | --- |
| Spring Boot | 127.0.0.1:9090 | `scripts/local/application-local.yml`，local profile |
| 独立 MySQL 8 | 127.0.0.1:3308 | `.local/mysql-data`，数据库 `mes_study` |
| Redis | 127.0.0.1:6380 | `.local/redis` |
| 日志 | 本地文件 | `.local/app.log`、`.local/app-error.log`、`.local/mysql.log`、`.local/redis.log` |

数据库密码随机生成在 `.local/settings.json`；MySQL 命令行连接配置为 `.local/mysql-client.ini`。该目录和构建目录通过本仓库 `.git/info/exclude` 忽略，不要上传其中的数据、密码和会话文件。原本运行的 MySQL57/MySQL80 服务及其数据库没有改动。

外部 local profile 覆盖上游的远程数据库、Redis 地址。日志使用本地 console 配置，不连接原 Logstash 地址。运行不依赖 Docker Desktop。

登录页的资源链接是 `/lib/...`、`/js/...`、`/css/...`，因此本地配置将 `spring.mvc.static-path-pattern` 设为 `/**`。`resources/static` 是磁盘上的资源目录名，不代表 URL 必须带 `/static` 前缀；将映射设成 `/static/**` 会使页面的脚本和样式返回 404，验证码也无法由脚本加载。修改本地 YAML 后需要在 IDEA 停止并重新运行，再刷新网页。

停止这一组学习进程：

```powershell
.\scripts\local\Stop-Local.ps1
```

停止后数据仍保留。不要删除 `.local/mysql-data` 或重新执行 SQL 来“修复启动”；先查看日志。

## 修改代码后编译

```powershell
$env:JAVA_HOME = 'D:\Program Files\Java\jdk1.8.0_202'
$env:Path = $env:JAVA_HOME + '\bin;' + $env:Path
.\scripts\local\Stop-Local.ps1
mvn -f mes/pom.xml -DskipTests package
.\scripts\local\Start-Local.ps1
```

上面 `-DskipTests` 是编译命令，不表示测试已经通过。当前项目的自带测试只有一个 Spring 上下文启动测试；已针对本地 profile 单独运行并通过，不能由此断言所有业务都正确。

## 已执行的验证

- Maven 使用 JDK 8 完成 jar 打包，退出码 0。
- 原始 SQL 导入独立库，共 17 张表；Redis ping 返回 PONG。
- 登录页、管理首页、物料列表和新增页面 HTTP 200。
- 通过真实图片验证码和原账号登录，返回 `code=0`，没有关闭认证。
- 调用真实接口新增 `MAT-DEMO-001`（教学示例车轴），关联已有“车轴流程”。
- 分页接口查回唯一记录，`flowDesc=车轴流程`、`createUsername=admin`，并通过 SQL 确认持久化。
- 原项目 contextLoads 测试：1 个测试，0 failures，0 errors，0 skipped。
- 完整停止再启动验证通过，登录页重新返回 HTTP 200，Redis 仍返回 PONG，数据库中的 MAT-DEMO-001 保留；原 MySQL57/MySQL80 服务继续运行。

接口验证记录保存在 `.local/verification.json`。本轮只验证物料与路线关联，不代表 README 中所有规划功能都已完成或可用。
