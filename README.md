# Epic · 充电站预约后端学习骨架

> 2026-10-25：已接入管理员密码登录、`/admin/**` 权限校验及站点增删改查。
> 管理员密码通过环境变量 `ADMIN_PASSWORD` 设置，未配置时无法登录；接口示例与权限规则见 [管理端接口](docs/管理端接口.md)。
> 下方早期骨架说明中的待实现清单未完全同步当前进度，以代码和专项文档为准。
> 2026-10-26：管理端取消时段已增加持久化邮件通知及后台重试，用户端业务保持不变。
> 已有数据库先执行 `src/main/resources/db/migrations/20261025_admin_cancellation_mail.sql`；配置及行为见管理端接口文档。
> 用户端取消预约已实现：`POST /user/reservations/{id}/cancel`，规则与示例见 [用户端取消预约](docs/用户端取消预约.md)。
> 两端登出已实现：`POST /user/auth/logout`、`POST /admin/auth/logout`，携带 `authorization` 请求头，无需请求体，仅退出当前 token 的会话。
> 预约订单 ID 已改用雪花算法。多实例需设置不同的 `SNOWFLAKE_WORKER_ID` / `SNOWFLAKE_DATACENTER_ID` 组合（各 0～31）；接口返回的预约 `id` 为字符串，前端需原样保留。
> 站点详情已启用布隆过滤器与短期空值缓存，保留逻辑过期及互斥回源；配置和数据同步规则见 [站点缓存](docs/业务开发文档.md#第二阶段站点缓存与分布式锁)。

本项目由 `D:/GridCharge` 裁剪而来，输出到 `D:/Epic`。原项目不变。
采用单模块 Maven 目录结构，包名前缀保留 `com.gridcharge`，所有源码统一放在根目录 `src` 下。

邮箱验证码 Controller 已接入 Service，`sendCode` 方法仍待实现；用户端站点详情接口已接入数据库查询，缓存留待手写，暂不记录访问或 UV。其他尚未接线的 Controller 方法仍返回 `null`。
通用数据库 CRUD 已保留在 ServiceImpl/Mapper，作为现成底座，不再作为学习任务；学习重点是 Redis 业务和相关预约编排。

## 已保留与已留空

| 保留 | 待你实现 |
| --- | --- |
| Maven 单模块、Spring Boot 配置、MyBatis XML | Controller 中调用 Service 并返回 Result |
| Entity、DTO、VO、参数校验、统一异常/返回 | 邮箱验证码、登录、Token、鉴权 |
| 站点 CRUD、分页、时段数据库操作 | 缓存优化、分布式锁、GEO |
| 用户/预约查询、数据库关注增删查 | 预约提交/取消/事务、Lua、Stream、恢复 |
| 五表 SQL、条件更新、索引/唯一约束 | Set 关注同步、Feed、活跃/UV/排行 |
| Redis/Redisson 依赖、可选连接配置、Key 常量 | Redis 工具、Lua 脚本、后台任务 |

按 Redis 能力分阶段开发，请看 [业务开发文档](docs/业务开发文档.md)；简要清单见 [学习任务](docs/learning-tasks.md)。尚未实现的 Service TODO 方法抛出 `UnsupportedOperationException`。

## 目录结构

```text
Epic/
├── .idea/                         # IDEA 配置
├── .gitignore
├── pom.xml                        # 单模块 Maven 配置
├── README.md
├── docs/                          # 学习笔记与业务文档
└── src/
    ├── main/
    │   ├── java/com/gridcharge/
    │   │   ├── GridChargeApplication.java
    │   │   ├── config/            # Spring 配置、全局异常处理
    │   │   ├── controller/        # admin / user 接口
    │   │   ├── dto/               # 请求 DTO、UserDTO、Result、PageResult
    │   │   ├── entity/            # 数据库实体
    │   │   ├── exception/         # 业务异常
    │   │   ├── mapper/            # MyBatis 接口
    │   │   ├── service/
    │   │   │   └── impl/          # 业务实现
    │   │   ├── task/              # 消费者与恢复任务
    │   │   ├── utils/             # 工具、拦截器、Redis 常量
    │   │   └── vo/                # 接口响应数据
    │   └── resources/
    │       ├── application.yml
    │       ├── db/                # schema.sql / data.sql
    │       ├── mapper/            # MyBatis XML
    │       └── lua/               # Lua 脚本学习入口
    └── test/java/com/gridcharge/
        └── ScaffoldContextTest.java
```

在 IDEA 中重新加载根目录 `pom.xml` 即可同步单模块结构。数据库脚本仍需手动执行，应用不会自动初始化数据库。

## 启动

Java 17、Maven 3.9+。在 IDEA 打开根 pom.xml，运行 `com.gridcharge.GridChargeApplication`。

```powershell
cd D:\Epic
mvn clean package
java -jar target/grid-charge-1.0.0.jar
```

也可以复用原项目已经下载的依赖（PowerShell 的 -D 参数整体加引号）：

```powershell
mvn '-Dmaven.repo.local=D:\GridCharge\.m2' clean package
```

Swagger：<http://localhost:8080/swagger-ui/index.html>；OpenAPI：<http://localhost:8080/v3/api-docs>。
接口占位返回空响应是正常现象，并不表示业务已实现。

登录拦截器已在 `WebMvcConfiguration.addInterceptors` 中注册并直接生效，不设登录开关。Redisson 客户端随应用启动创建；管理端取消邮件任务默认定时执行，原预约恢复等学习任务仍未注册。不再进行强制依赖版本检查。
启动应用前需要确保 Redis 可连接；Redisson 的地址配置在 `RedisConfiguration` 中，密码读取 `application.yml` 的 `spring.data.redis.password`，由环境变量 `REDIS_PASSWORD` 提供。
真正调用 CRUD 前须初始化数据库和配置连接；恢复 Redis 业务时仍应使用 Redis 6.2+。

## QQ 邮箱验证码

所有配置集中在 `src/main/resources/application.yml`，不再区分 dev/prod。
默认仅监听 `127.0.0.1`；可用 `SERVER_ADDRESS` 覆盖。设置 `API_DOCS_ENABLED=false` 可关闭 Swagger 和 OpenAPI。

QQ 邮箱网页端进入「设置 → 账号与安全 → 安全设置」，开启 POP3/IMAP/SMTP 服务并生成授权码，参见 [QQ 邮箱官方说明](https://help.mail.qq.com/detail/106/985)。
在 IDEA 应用运行配置的环境变量中添加：

```text
SMTP_USERNAME=你的QQ邮箱地址
SMTP_PASSWORD=你的SMTP授权码
```

默认使用 `smtp.qq.com:465` 和 SSL。授权码不要填成 QQ 登录密码，也不要提交到源码。
邮件连接配置已就绪，`sendCode` 和 `login` 的业务代码仍待实现。
建议验证码保存为 Redis String，有效期 5 分钟，同一邮箱冷却 60 秒；真实发信时接口仅返回发送状态，不回显验证码。
实现后可启动 Redis 并调用 `POST /user/auth/code`，JSON 请求体示例：`{"email":"收件人邮箱"}`。
SMTP 接受邮件不等于已进入收件箱；登录校验需与发送逻辑使用相同的邮箱规范化规则和 Redis Key。

## 数据库

为避免与原项目混用，默认库名改为 **epic_learning**，SQL 与配置已同步。
MySQL 5.7.19+ 下执行：

```sql
SOURCE D:/Epic/src/main/resources/db/schema.sql;
SOURCE D:/Epic/src/main/resources/db/data.sql;
```

环境变量：`MYSQL_HOST / MYSQL_PORT / MYSQL_DATABASE / MYSQL_USERNAME / MYSQL_PASSWORD`；
`REDIS_HOST / REDIS_PORT / REDIS_USERNAME / REDIS_PASSWORD / REDIS_DATABASE`。
默认端口 3306 / 6379，密码不写死。原五张表和模拟数据保留，应用不自动初始化数据库。
启动前请配置 `MYSQL_PASSWORD`、`REDIS_PASSWORD` 和 `ADMIN_PASSWORD`；发信时还需配置 `SMTP_USERNAME`、`SMTP_PASSWORD`。未配置管理员密码时拒绝管理员登录，请勿将真实凭据提交到仓库。

## 学习顺序

从 Redis 验证码与登录态开始，再做缓存/GEO、INCR/Set、Lua/Stream、取消补偿、关注 Feed 和统计。
通用 CRUD 直接复用；Controller 接线随功能完成，不单列为学习任务。
源码中的 TODO 仅标出目标，不包含 Redis 解题实现。
`WebMvcConfiguration.addInterceptors` 已注册刷新拦截器和登录拦截器，并配置执行顺序与放行路径；登录态 TTL 统一在 `RedisKeyConstant` 中设置。Redisson 无额外启用开关；后台任务实现完成后自行注册 Bean/调度。

这是本地学习工程，登录拦截器已注册，仍有业务待实现。原项目的业务测试、压测/冒烟脚本和完成版设计文档不复制过来。
验证结果见 [验证记录](docs/verification.md)。
