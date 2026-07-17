# yagent

`yagent` 是一个基于 Spring Boot 4、Java 21 和 AgentScope Java 2.0 构建的 Agent Demo 项目。当前项目里已经实现了一版 **多智能体经营分析助手**，用于演示 AgentScope 在真实业务场景中的核心运行流程，包括：

- 多智能体协作
- 会话状态记忆
- 流式输出
- Tool 调用
- 数据库分析
- SQL 透明展示

这版 Demo 的目标不是做复杂 BI 平台，而是提供一个**最小但可以直接运行**的分析型 Agent 项目，用来学习 AgentScope，也能作为后续继续扩展成可上线项目的基础。

## 项目能力

当前经营分析 Demo 固定为 3 Agent 协作：

- `Coordinator Agent`
  负责接收用户问题、组织分析流程、汇总最终结果
- `Metric Agent`
  负责识别用户问题中的指标、维度、时间范围和分析意图
- `SQL Analyst Agent`
  负责基于样例库 schema 生成 H2 只读 SQL、执行查询并整理分析素材

当前支持的分析范围：

- 销售额
- 订单数
- 客单价
- 商品排行
- 区域趋势 / 区域对比

当前内置的数据模型：

- `orders`
- `products`
- `customers`
- `regions`

项目启动时会自动加载 H2 内存数据库，并初始化样例数据，因此不需要额外安装数据库。

## 运行环境

建议环境：

- JDK 21 或以上
- Maven 3.9+
- 可用的 DashScope API Key

## 关键依赖

项目核心依赖包括：

- `spring-boot-starter-web`
- `spring-boot-starter-jdbc`
- `agentscope-core`
- `agentscope-harness`
- `agentscope-extensions-model-dashscope`
- `h2`

## 启动前准备

当前项目默认启用 `local` profile，配置文件位置是：

- [application.yml](D:/JavaProject/Aagent/yagent/src/main/resources/application.yml)
- [application-local.yml](D:/JavaProject/Aagent/yagent/src/main/resources/application-local.yml)

需要确认以下配置可用：

```yaml
spring:
  ai:
    dashscope:
      enabled: true
      api-key: your-api-key
```

说明：

- `enabled: true`
  这表示当前环境允许使用 DashScope 大模型。
- `api-key`
  这是 Agent 真正调用模型时需要使用的密钥。如果这里没有可用的 key，项目虽然能编译，但在真正发起分析问答时会失败。

如果你后续不想把 key 放在本地配置文件里，可以改成环境变量方式，然后同步调整配置读取方式。

## 启动方式

在项目根目录 `D:\JavaProject\Aagent\yagent` 下执行：

```bash
mvn spring-boot:run
```

这一步的作用：

- 让 Spring Boot 启动 Web 服务
- 自动创建 H2 内存数据库
- 自动执行 `schema.sql` 和 `data.sql`
- 初始化分析工具
- 初始化主控 Agent 和子 Agent

默认启动信息：

- 端口：`8123`
- Context Path：`/api`

如果启动成功，你通常会看到这些关键信息：

- Tomcat started on port `8123`
- H2 数据源初始化完成
- `HarnessAgent 'coordinator' built`

启动成功后，可以访问：

- 健康检查或其他现有接口
- 分析 Agent 相关接口

## 分析 Agent 接口

### 1. 查看样例库 schema

```http
GET /api/analysis/schema
```

示例：

```bash
curl "http://localhost:8123/api/analysis/schema"
```

这个接口会返回：

- 当前经营分析样例库的表结构说明
- 当前支持的指标和维度

这一步建议最先执行，因为它的作用是：

- 验证项目是否已经成功启动
- 验证 H2 样例库是否已经初始化
- 帮你快速看清当前 Demo 能分析哪些表和字段

### 2. 一次性分析问答

```http
GET /api/analysis/once?message=...&sessionId=...
```

示例：

```bash
curl "http://localhost:8123/api/analysis/once?message=近30天销售额趋势如何&sessionId=demo-001"
```

返回结果固定包含 3 个部分：

- `分析结论`
- `本次使用的 SQL`
- `关键分析步骤`

这一步适合你第一次验证核心链路，因为它最容易看清整个 Agent 流程是否跑通：

1. 你输入自然语言问题
2. 主控 Agent 接收问题
3. `metric-agent` 识别分析口径
4. `sql-agent` 生成 SQL 并查询 H2
5. 主控 Agent 汇总结果并返回最终答案

如果这个接口能返回完整三段内容，就说明当前经营分析 Demo 的主链路已经跑通。

### 3. 流式分析问答

```http
GET /api/analysis/stream?message=...&sessionId=...
```

示例：

```bash
curl "http://localhost:8123/api/analysis/stream?message=本月Top5商品销售额是多少&sessionId=demo-002"
```

这个接口会持续输出文本片段，适合接前端聊天窗口或调试流式过程。

这一步的重点不是“结果内容”，而是看执行过程：

- 是否能边生成边返回文本
- 是否能看到子 Agent 协作标识
- 是否能看到 SQL 生成后的阶段性输出

如果你后面要接前端页面，通常优先接这个流式接口。

## 会话记忆

分析 Agent 支持基于 `sessionId` 的多轮追问。

例如：

1. 第一轮：

```text
近30天销售额趋势如何
```

2. 第二轮：

```text
华东地区呢
```

3. 第三轮：

```text
把刚才结果总结成3条
```

只要 `sessionId` 相同，Agent 会继承上一轮的分析上下文。  
如果更换 `sessionId`，则会开启一段新的独立会话。

这里可以这样理解：

- `sessionId = demo-001`
  表示“这一组问题属于同一段对话”
- 同一个 `sessionId`
  表示 Agent 会记住你上一轮问了什么、分析口径是什么
- 换一个新的 `sessionId`
  表示重新开一段新的对话，不继承旧内容

所以你在调试时，如果发现第二轮追问“没接上上一轮”，先检查是不是把 `sessionId` 换掉了。

## 样例数据初始化

当前 H2 内存数据库的建表和数据初始化文件位于：

- [schema.sql](D:/JavaProject/Aagent/yagent/src/main/resources/schema.sql)
- [data.sql](D:/JavaProject/Aagent/yagent/src/main/resources/data.sql)

项目启动时会自动执行这两个文件。

这两个文件的分工是：

- `schema.sql`
  负责建表，也就是定义数据库结构
- `data.sql`
  负责插入样例数据，也就是让 Demo 启动后立刻有数据可分析

因为当前使用的是 H2 内存数据库，所以每次重启项目，数据都会重新按这两个文件初始化。

## 主要代码位置

分析 Agent 的核心代码主要在以下位置：

- [AnalyticsAgentConfig.java](D:/JavaProject/Aagent/yagent/src/main/java/com/ex/yagent/config/AnalyticsAgentConfig.java)
  负责主控 Agent 配置
- [AnalyticsTools.java](D:/JavaProject/Aagent/yagent/src/main/java/com/ex/yagent/analysis/AnalyticsTools.java)
  负责 schema 查询、指标说明、只读 SQL 执行
- [AnalysisController.java](D:/JavaProject/Aagent/yagent/src/main/java/com/ex/yagent/controller/AnalysisController.java)
  负责 HTTP 接口
- [.agentscope/analytics-workspace/subagents](</D:/JavaProject/Aagent/yagent/.agentscope/analytics-workspace/subagents>)
  负责子 Agent 声明

如果你想按“理解流程”的顺序阅读代码，建议这样看：

1. 先看 [AnalysisController.java](D:/JavaProject/Aagent/yagent/src/main/java/com/ex/yagent/controller/AnalysisController.java)
   这里是 HTTP 入口，最容易看清外部请求是怎么进入 Agent 的
2. 再看 [AnalyticsAgentConfig.java](D:/JavaProject/Aagent/yagent/src/main/java/com/ex/yagent/config/AnalyticsAgentConfig.java)
   这里能看到主控 Agent、workspace、状态存储、系统提示词这些核心配置
3. 再看 [AnalyticsTools.java](D:/JavaProject/Aagent/yagent/src/main/java/com/ex/yagent/analysis/AnalyticsTools.java)
   这里是 Agent 真正能调用的数据分析工具
4. 最后看 [.agentscope/analytics-workspace/subagents](</D:/JavaProject/Aagent/yagent/.agentscope/analytics-workspace/subagents>)
   这里能看到 `metric-agent` 和 `sql-agent` 的职责定义

## 测试方式

编译：

```bash
mvn -DskipTests compile
```

这一步只验证一件事：

- 当前代码能不能正常编译通过

运行分析工具层测试：

```bash
mvn "-Dtest=AnalyticsToolsTest" test
```

这个测试会验证：

- H2 样例库是否正常初始化
- 只读 SQL 是否可执行
- 危险 SQL 是否被拒绝
- 空结果是否返回明确提示

建议你调试时至少按下面顺序执行一次：

1. `mvn -DskipTests compile`
   先确认代码没编译错误
2. `mvn "-Dtest=AnalyticsToolsTest" test`
   再确认数据层和只读 SQL 工具没问题
3. `mvn spring-boot:run`
   最后启动整个项目做联调
4. 访问 `/api/analysis/schema`
   确认项目和样例库已就绪
5. 访问 `/api/analysis/once`
   确认多智能体分析主链路已跑通

## 当前限制

当前版本是第一版最小可运行 Demo，限制如下：

- 只支持固定的经营分析样例 schema
- 只支持 H2 样例库，不支持任意外部业务数据库
- 不生成图表
- 不提供前端页面
- 不做权限分级和多租户隔离
- 只展示文本分析结果

## 后续可扩展方向

后续可以继续扩展为更完整的项目，例如：

- 接入 MySQL / PostgreSQL 等真实业务库
- 加入图表生成
- 接入上传文件或数据源配置
- 增加利润、客户复购、渠道转化等更复杂指标
- 增加前端页面
- 增加更细粒度的子 Agent 协作
