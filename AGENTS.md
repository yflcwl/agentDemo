# AGENTS.md

This file provides guidance to Codex (Codex.ai/code) when working with code in this repository.

## 项目概述

yagent — 基于 Spring Boot 3.5.15 / Java 21 的 AI Agent 服务，集成阿里云百炼灵积（DashScope）大模型平台。项目同时演示了三种调用大模型的方式：原生 HTTP、DashScope SDK、Spring AI 框架。

## 构建与运行

```bash
# 编译
mvn compile -f pom.xml

# 运行测试
mvn test -f pom.xml

# 启动应用（默认 active profile: local，端口 8123，context-path: /api）
mvn spring-boot:run -f pom.xml

# 打包
mvn package -f pom.xml
```

## 核心架构

```
src/main/java/com/ex/yagent/
├── YagentApplication.java          # Spring Boot 入口
├── controller/
│   └── HealthController.java       # 健康检查 REST 端点
└── demo/invoke/                    # 三种 AI 调用模式示例
    ├── TestApiKey.java             # API Key 常量（需填入真实 key）
    ├── HttpAiInvoke.java           # 方式一：原生 HTTP（Hutool HttpRequest）
    ├── SdkAiInvoke.java            # 方式二：DashScope Java SDK
    └── SpringAiInvoke.java         # 方式三：Spring AI 框架（ChatModel 注入）
```

- **包扫描范围**：`com.ex.yagent`，controller 包在 `springdoc` 中单独配置用于 API 文档扫描。
- **启动时行为**：`SpringAiInvoke` 实现了 `CommandLineRunner`，应用启动后自动执行一次 Spring AI 调用。
- **配置**：`application.yml` 设置公共参数（端口、context-path、Knife4j/Swagger），`application-local.yml` 存放 `spring.ai.dashscope.api-key` 等敏感配置（已在 `.gitignore` 中排除）。

## 关键依赖

| 依赖 | 用途 |
|---|---|
| `dashscope-sdk-java` (2.22.4) | 阿里云百炼灵积 SDK |
| `spring-ai-alibaba-studio` (1.1.2.0) | Spring AI 阿里云适配层，提供 `ChatModel` bean |
| `knife4j-openapi3-jakarta-spring-boot-starter` (4.4.0) | API 文档（Knife4j + Swagger UI） |
| `hutool-all` (5.8.23) | 通用工具库 |
| `lombok` (1.18.36) | 编译期代码生成 |

## API 文档

启动后访问：
- Swagger UI：`http://localhost:8123/api/swagger-ui.html`
- OpenAPI 3 JSON：`http://localhost:8123/api/v3/api-docs`

## 注意事项

- `application-local.yml` 包含 API Key 等敏感配置，已加入 `.gitignore`，不要提交到版本库。
- `SdkAiInvoke` 中使用 `@Value` 注入 `static` 字段是无效的（Spring 不支持静态字段注入），实际运行时会拿到 null——代码处于演示阶段，需要重构为实例注入或从环境变量读取。
- `TestApiKey.API_KEY` 为空字符串常量，真正使用时需填入实际 key，或改用环境变量/配置文件读取。
