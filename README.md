## 目录结构

```text
Epic/
├── pom.xml                         # Maven 配置
├── README.md
├── docs/                           # 项目文档
└── src/
    ├── main/
    │   ├── java/com/gridcharge/
    │   │   ├── GridChargeApplication.java  # 启动类
    │   │   ├── config/             # 配置
    │   │   ├── controller/         # 管理端与用户端接口
    │   │   ├── dto/                # 数据传输对象
    │   │   ├── entity/             # 数据库实体
    │   │   ├── exception/          # 业务异常
    │   │   ├── mapper/             # 数据访问接口
    │   │   ├── mq/                 # 消息生产与消费
    │   │   ├── service/            # 业务接口及实现
    │   │   ├── task/               # 后台任务
    │   │   ├── utils/              # 工具与拦截器
    │   │   └── vo/                 # 响应对象
    │   └── resources/
    │       ├── application.yml     # 应用配置
    │       ├── db/                 # 数据库初始化与迁移脚本
    │       ├── mapper/             # MyBatis XML
    │       └── lua/                # Lua 相关资源
    └── test/java/com/gridcharge/   # 测试
```

## 启动

1. 安装 Java 17、Maven 3.9+、MySQL 5.7.19+、Redis 6.2+ 和 RocketMQ，启动 MySQL、Redis 以及 RocketMQ 的 NameServer 和 Broker。默认连接本机 MySQL `3306`、Redis `6379`、RocketMQ NameServer `9876`。

2. 克隆项目：

   ```powershell
   git clone https://github.com/Voricphy/Epic.git
   cd Epic
   ```

3. 在 MySQL 客户端依次执行以下脚本，将路径替换为本机项目路径：

   ```sql
   SOURCE D:/Epic/src/main/resources/db/schema.sql;
   SOURCE D:/Epic/src/main/resources/db/data.sql;
   ```

4. 在 PowerShell 中设置环境变量，替换为自己的配置：

   ```powershell
   $env:MYSQL_PASSWORD = "你的数据库密码"
   $env:REDIS_PASSWORD = "你的Redis密码"
   $env:ADMIN_PASSWORD = "你设置的管理员密码"
   $env:SMTP_USERNAME = "你的QQ邮箱地址"
   $env:SMTP_PASSWORD = "你的QQ邮箱SMTP授权码"
   ```

   Redis 未设置密码时，将 `REDIS_PASSWORD` 留空；无需发信时可省略 SMTP 配置。其他连接配置见 `src/main/resources/application.yml`。

5. 确认 `JAVA_HOME` 指向 Java 17，构建并启动：

   ```powershell
   mvn clean package
   java -jar target/grid-charge-1.0.0.jar
   ```

   也可在 IntelliJ IDEA 中打开 `pom.xml`，在运行配置中设置上述环境变量，运行 `com.gridcharge.GridChargeApplication`。

启动后访问接口文档：<http://localhost:8080/swagger-ui/index.html>。
