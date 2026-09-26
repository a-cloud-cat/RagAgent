# RagAgent

基于 **Spring Boot** 的 RAG（检索增强生成）流式对话应用：上传 `.txt` / `.pdf` / `.html` 文档后自动解析为纯文本、切块、向量化并存入 pgvector；提问时先检索最相似的知识分块拼入提示词，再经 `WebClient` 调用大模型（OpenAI 兼容接口），通过 SSE 逐字返回回复。

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
| Apache Tika | 3.2.2 | PDF/HTML 等文档解析为纯文本 |
| JUnit 5 / AssertJ / MockWebServer | Boot 3.2.0 管理 | 单元测试（test scope，不进生产包） |
| springdoc-openapi | 2.3.0 | 接口文档，启动后访问 `/swagger-ui.html` |
| Maven | 3.6+ | 构建（也可用 IDEA 内置 Maven） |
| LM Studio | 任意 | 本地 OpenAI 兼容推理服务，默认端口 1234（local profile） |

## 数据流

**文档入库：**

```
POST /documents（multipart，.txt/.pdf/.html/.htm）
   ▼
txt 按 UTF-8 直读；PDF/HTML 经 DocumentParser（Apache Tika）提取纯文本
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
│   ├── DocumentController.java  # POST /documents（上传文档入库）
│   └── GlobalExceptionHandler.java  # 统一异常：400 / 500
├── eval/
│   ├── EvalCase.java            # 评测用例模型（question/document/expectedPhrase）
│   ├── EvalReport.java          # 评测报告（Hit@K/相似度/耗时 + 每题明细）
│   ├── EvalService.java         # 加载用例 → 逐题检索 → 判定命中 → 汇总指标
│   └── EvalController.java      # POST /eval/retrieval
├── embedding/
│   ├── EmbeddingClient.java         # 向量化接口
│   ├── OpenAiEmbeddingClient.java   # 百炼实现（维度/条数校验、分批）
│   └── dto/                         # Embedding 请求/响应 DTO
├── parser/
│   ├── DocumentParser.java       # 文档解析接口：二进制流 → 纯文本
│   └── TikaDocumentParser.java   # Tika 实现（PDF/HTML，异常包为 IllegalStateException）
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
├── eval/
│   └── retrieval-cases.json    # 检索评测集（问题 → 文档名 + 期望短语）
└── static/index.html           # 前端页面（自动托管）
src/test/
├── java/.../service/TextChunkerTest.java        # 切块边界（空/短/临界/超长/重叠）
├── java/.../embedding/OpenAiEmbeddingClientTest.java  # MockWebServer 桩：请求体与条数/维度校验
├── java/.../parser/TikaDocumentParserTest.java  # HTML 夹具：正文提取与标签剥离
└── resources/parser/sample.html                 # 解析器测试夹具
docker-compose.yml              # 本地 pgvector 容器
docs/eval-baseline.md           # 检索评测基线（Hit@K 数字与复跑口径）
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

5. **入库文档**：通过 Swagger UI（`http://localhost:8080/swagger-ui.html`）或 curl 上传文档，支持 UTF-8 编码的 `.txt` 以及 `.pdf` / `.html` / `.htm`：

   ```bash
   curl -F "file=@你的文档.txt"  http://localhost:8080/documents
   curl -F "file=@你的文档.pdf"  http://localhost:8080/documents
   curl -F "file=@你的文档.html" http://localhost:8080/documents
   ```

6. **开始提问**：浏览器访问 `http://localhost:8080/`，Enter 发送（Shift+Enter 换行）。

7. **跑检索评测**（可选）：对 `src/main/resources/eval/retrieval-cases.json` 中的问题批量跑向量化 + topK 检索，返回 Hit@K 报告：

   ```bash
   curl -X POST http://localhost:8080/eval/retrieval
   ```

   基线数字与解读见 `docs/eval-baseline.md`，调整切块/topK/模型后重跑对比。

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

支持 `.txt`（UTF-8 直读）、`.pdf` / `.html` / `.htm`（Tika 提取纯文本）。成功返回：`{"id":1,"name":"xxx.pdf","status":"INGESTED"}`；空文件/不支持的后缀返回 400。

```bash
curl -F "file=@你的文档.pdf" http://localhost:8080/documents
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

**`POST /eval/retrieval`**，无请求体

离线检索评测：逐题复用主链路（向量化 → topK 检索），以「文档名精确相等 + 分块文本包含期望短语（忽略大小写与空白）」判定命中。返回汇总指标（`topK / total / hits / hitRate / avgScore / avgLatencyMs / chunkSize / embeddingModel`）及每题 `hit / hitRank / retrieved` 明细。每次调用都会真实请求百炼向量化接口。

```bash
curl -X POST http://localhost:8080/eval/retrieval
```

## 备注

- 依赖 PostgreSQL + pgvector，启动前请确认容器已运行且已执行 `schema_pg.sql`。
- 向量维度需三处一致：百炼请求参数、`rag.embedding.dimension`、库中 `vector(1536)`；客户端对返回条数与维度都做了校验，不一致会快速失败。
- 常见问题：发送失败多为模型服务未启动 / 模型名不一致 / API key 未注入；8080 被占用可用 `Get-NetTCPConnection -LocalPort 8080` 查进程后结束，或改 `server.port`。

## 命令

```bash
docker compose up -d              # 启动 pgvector
mvn compile                       # 编译
mvn test                          # 运行单元测试（离线、无需 Docker，共 11 个用例）
mvn spring-boot:run               # 运行
mvn clean package -DskipTests     # 打包
```
