# RagAgent

基于 **Spring Boot** 的 RAG（检索增强生成）流式对话应用：上传文本文档后自动切块、向量化并存入 pgvector；提问时先检索最相似的知识分块拼入提示词，再经 `WebClient` 调用大模型（OpenAI 兼容接口），通过 SSE 逐字返回回复。

## 技术栈

| 技术 | 版本 | 用途 |
| --- | --- | --- |
| Java | 17 | 运行环境 |
| Spring Boot | 3.2.0 | Web / SSE / 自动配置 |
| spring-boot-starter-web | 3.2.0 | REST 接口、`SseEmitter`、静态资源托管 |
| spring-boot-starter-webflux | 3.2.0 | `WebClient` 调用大模型 / 向量化接口（容器为 Tomcat） |
| spring-boot-starter-jdbc | 3.2.0 | JdbcTemplate + Hikari 连接池访问 PostgreSQL |
| PostgreSQL + pgvector | pg17 | 文档/分块存储与向量余弦相似度检索（HNSW 索引） |
| 阿里云百炼 text-embedding-v4 | - | 文本向量化（1536 维，OpenAI 兼容协议） |
| springdoc-openapi | 2.3.0 | 接口文档，启动后访问 `/swagger-ui.html` |
| Maven | 3.6+ | 构建（也可用 IDEA 内置 Maven） |
| LM Studio | 任意 | 本地 OpenAI 兼容推理服务，默认端口 1234（local profile） |

## 数据流

**文档入库：**

```
POST /documents（multipart，.txt）
   ▼
IngestionService：建文档记录(PENDING)
   ▼
TextChunker 滑动窗口切块（300 字符/块，重叠 50）
   ▼
OpenAiEmbeddingClient → 百炼 /compatible-mode/v1/embeddings（16 条分批，1536 维）
   ▼
ChunkRepository 批量写入 t_chunk（embedding::vector）→ 文档置 INGESTED（失败置 FAILED）
```

**RAG 对话：**

```
浏览器 index.html
   │  POST /chat  {"question": "..."}
   ▼
ChatService：问题向量化 → pgvector 余弦检索 topK 分块 → 拼入 system 提示词
   │  WebClient.post()（stream=true；无召回时走普通对话）
   ▼
大模型 /v1/chat/completions（云端 DeepSeek 或本地 LM Studio）
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
│   ├── LlmProperties.java       # llm.* 配置绑定
│   ├── EmbeddingProperties.java # rag.embedding.* 配置绑定
│   └── RetrievalProperties.java # rag.retrieval.* 配置绑定
├── controller/
│   ├── ChatController.java      # POST /chat（SSE）
│   ├── DocumentController.java  # POST /documents（上传 .txt 入库）
│   └── GlobalExceptionHandler.java  # 统一异常：400 / 500
├── embedding/
│   ├── EmbeddingClient.java         # 向量化接口
│   ├── OpenAiEmbeddingClient.java   # 百炼实现（维度/条数校验、分批）
│   └── dto/                         # Embedding 请求/响应 DTO
├── service/
│   ├── ChatService.java        # 检索增强 + 调用大模型并转发 SSE
│   ├── IngestionService.java   # 入库编排：切块 → 向量化 → 落库
│   └── TextChunker.java        # 滑动窗口文本切块
├── repository/
│   ├── DocumentRepository.java # t_document 数据访问
│   └── ChunkRepository.java    # t_chunk 批量写入与向量检索（<=> 操作符）
└── model/
    ├── ChatRequest.java        # /chat 请求体 {question}
    ├── Document.java
    └── Chunk.java
src/main/resources/
├── application.yaml            # 公共配置：数据源、profile、embedding/retrieval
├── application-cloud.yaml      # 云端 DeepSeek
├── application-local.yaml      # 本地 LM Studio
├── database/
│   ├── schema_pg.sql           # 建表 + pgvector 扩展 + HNSW 索引
│   └── init_data_pg.sql        # 可选种子数据（默认无需预置）
└── static/index.html           # 前端页面（自动托管）
docker-compose.yml              # 本地 pgvector 容器
```

## 快速开始

1. **启动数据库**：`docker compose up -d`（pgvector/pg17，端口 5432，库名 rag_db），首次使用执行建表脚本：

   ```bash
   docker exec -i rag_pgvector-pgvector-1 psql -U postgres -d rag_db < src/main/resources/database/schema_pg.sql
   ```

2. **配置密钥**（PowerShell，只需执行一次）：

   ```powershell
   setx DASHSCOPE_API_KEY "你的百炼key"   # 向量化（cloud/local 都需要）
   setx DEEPSEEK_API_KEY "你的DeepSeek key" # 仅 cloud profile 需要
   ```

3. **准备对话模型**（二选一）：
   - cloud（默认）：直接使用 DeepSeek 官方 API，无需本地模型；
   - local：启动 LM Studio 并加载对话模型，确认监听 `127.0.0.1:1234`，再把 `application.yaml` 中 `spring.profiles.active` 改为 `local`。

4. **启动后端**：IDEA 运行 `RagApplication.java`，或 `mvn spring-boot:run`。

5. **入库文档**：通过 Swagger UI（`http://localhost:8080/swagger-ui.html`）或 curl 上传 UTF-8 编码的 `.txt`：

   ```bash
   curl -F "file=@你的文档.txt" http://localhost:8080/documents
   ```

6. **开始提问**：浏览器访问 `http://localhost:8080/`，Enter 发送（Shift+Enter 换行）。

## 配置

`src/main/resources/application.yaml`：

```yaml
spring:
  profiles:
    active: cloud                 # cloud=DeepSeek 云端；local=LM Studio
  datasource:
    url: jdbc:postgresql://localhost:5432/rag_db
    username: postgres
    password: "123456"

rag:
  embedding:
    base-url: https://dashscope.aliyuncs.com
    api-key: ${DASHSCOPE_API_KEY:}
    model: text-embedding-v4
    dimension: 1536       # 必须与库中 vector(1536) 一致；v4 默认 1024，不显式传会维度不符
  retrieval:
    top-k: 3              # 每次提问召回的最相似分块数
```

对话模型按 profile 区分（见 `application-cloud.yaml` / `application-local.yaml`），切换方式：改配置文件，或在 IDEA 运行配置的 Active profiles 填 `local`/`cloud` 临时覆盖。

## 接口

**`POST /documents`**，`Content-Type: multipart/form-data`，文件参数名 `file`

仅支持 UTF-8 的 `.txt`。成功返回：`{"id":1,"name":"xxx.txt","status":"INGESTED"}`；空文件/非 txt 返回 400。

```bash
curl -F "file=@你的文档.txt" http://localhost:8080/documents
```

**`POST /chat`**，`Content-Type: application/json`

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

- 依赖 PostgreSQL + pgvector，启动前请确认容器已运行且已执行 `schema_pg.sql`。
- 向量维度需三处一致：百炼请求参数、`rag.embedding.dimension`、库中 `vector(1536)`；客户端对返回条数与维度都做了校验，不一致会快速失败。
- 常见问题：发送失败多为模型服务未启动 / 模型名不一致 / API key 未注入；8080 被占用可用 `Get-NetTCPConnection -LocalPort 8080` 查进程后结束，或改 `server.port`。

## 命令

```bash
docker compose up -d              # 启动 pgvector
mvn compile                       # 编译
mvn spring-boot:run               # 运行
mvn clean package -DskipTests     # 打包
```
