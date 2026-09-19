# RagAgent

基于 **Spring Boot** 的流式大模型对话应用：浏览器页面通过 SSE 调用后端，后端经 `WebClient` 请求本地 LM Studio（OpenAI 兼容接口），逐字返回大模型回复。

## 技术栈

| 技术 | 版本 | 用途 |
| --- | --- | --- |
| Java | 17 | 运行环境 |
| Spring Boot | 3.2.0 | Web / SSE / 自动配置 |
| spring-boot-starter-web | 3.2.0 | REST 接口、`SseEmitter`、静态资源托管 |
| spring-boot-starter-webflux | 3.2.0 | `WebClient` 调用大模型（容器仍为 Tomcat） |
| Maven | 3.6+ | 构建（也可用 IDEA 内置 Maven） |
| LM Studio | 任意 | 本地 OpenAI 兼容推理服务，默认端口 1234 |

## 数据流

```
浏览器 index.html
   │  POST /chat  {"question": "..."}
   ▼
ChatController → ChatService（组装 OpenAI 请求，stream=true）
   │  WebClient.post()
   ▼
LM Studio  /v1/chat/completions
   │  SSE 数据块（Flux<String>）
   ▼
emitter.send() 逐块转发 → 浏览器流式渲染
```

## 项目结构

```
src/main/java/com/example/rag/
├── RagApplication.java          # 启动类
├── config/
│   ├── WebClientConfig.java     # WebClient Bean
│   └── LlmProperties.java       # llm.* 配置绑定
├── controller/
│   └── ChatController.java      # POST /chat
├── service/
│   └── ChatService.java         # 调用大模型并转发 SSE
└── model/
    └── ChatRequest.java         # 请求体 {question}
src/main/resources/
├── application.yaml             # llm 配置
└── static/index.html            # 前端页面（自动托管）
```

## 快速开始

1. **启动 LM Studio**：加载对话模型（如 DeepSeek-R1），在 Local Server 页启动服务，确认监听 `127.0.0.1:1234`。
2. **启动后端**：
   - IDEA：运行 `RagApplication.java`；
   - 命令行：`mvn spring-boot:run`。
3. **打开页面**：浏览器访问 `http://localhost:8080/`，Enter 发送（Shift+Enter 换行）。

## 配置

`src/main/resources/application.yaml`：

```yaml
llm:
  base-url: http://127.0.0.1:1234/v1   # OpenAI 兼容接口地址
  api-key: "dummy"                       # 本地不校验，占位即可
  model-name: deepseek-r1                # 与 LM Studio 加载模型一致
```

可加 `server.port` 修改默认端口 8080。改用在线 API 或 Ollama 时，只需替换上面三个值。

## 接口

`POST /chat`，`Content-Type: application/json`

请求：`{"question": "你好"}`

响应：`text/event-stream`，转发 OpenAI SSE 块，`delta` 中：
- `content`：正式回答增量；
- `reasoning_content`：推理模型思考过程；
- `finish_reason`：结束标志（`stop`）。

curl：

```bash
curl -N -X POST http://localhost:8080/chat -H "Content-Type: application/json" -d '{"question":"你好"}'
```

## 备注

- 无数据库依赖，无需启动 PostgreSQL。
- 常见问题：发送失败多为 LM Studio 未启动 / 模型名不一致；8080 被占用可用 `Get-NetTCPConnection -LocalPort 8080` 查进程后结束，或改 `server.port`。

## 命令

```bash
mvn compile                 # 编译
mvn spring-boot:run         # 运行
mvn clean package -DskipTests  # 打包
```
