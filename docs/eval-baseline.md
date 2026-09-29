# 检索评测基线（Hit@K Baseline）

> 评测入口：`POST /eval/retrieval`（实时复用主链路：百炼向量化 → pgvector 余弦检索 → Rerank 精排）
> 评测集：`src/main/resources/eval/retrieval-cases.json`（8 题，锚定「文档名 + 期望短语」）

## 一、评测环境参数

| 参数 | 值 |
| --- | --- |
| 切块策略 | 固定窗口 300 字 / 重叠 50 字（`TextChunker`） |
| topK | 3（`rag.retrieval.top-k`） |
| candidate-limit | 20（`rag.retrieval.candidate-limit`） |
| 向量模型 | 百炼 `text-embedding-v4`，1536 维 |
| 向量库 | PostgreSQL + pgvector，余弦距离（`<=>`） |
| Rerank 模型 | 百炼 `qwen3-rerank` |
| 活跃语料 | `spring-boot-notes.html`（6 块）+ `测试演示文稿.pdf`（2 块，作为干扰语料），共 8 块 |

评测前已软删除（`deleted=1`）早期测试重复上传的 `spring-boot-notes.txt`（与 HTML 版内容重复，会互相挤占 topK 导致结果失真）。

## 二、基线结果

### Rerank 关闭（NoopRerankClient，2026-09-26）

| 指标 | 值 |
| --- | --- |
| **Hit@3** | **8 / 8 = 1.000** |
| MRR | 0.813（5 题第 1、3 题第 2） |
| 平均首条相似度 | 0.667 |
| 平均耗时 | 180.7 ms |

### Rerank 开启（qwen3-rerank，2026-09-29）

| 指标 | 值 |
| --- | --- |
| **Hit@3** | **8 / 8 = 1.000**（不回退） |
| **MRR** | **0.938**（7 题第 1、1 题第 2，+0.125） |
| 平均首条 rerank 分 | 0.899 |
| 平均耗时 | 414.7 ms（+234 ms，rerank API 开销） |

## 三、逐题明细

| # | 问题 | Noop 名次 | Rerank 名次 | 变化 |
| --- | --- | --- | --- | --- |
| 1 | Spring Boot 最核心的设计理念是什么？ | 1 | 1 | — |
| 2 | 对象不自己 new 依赖、交给容器管理，这种思想叫什么？ | 1 | 1 | — |
| 3 | 数据访问层组件应该标注哪个构造型注解？ | 2 | **2** | — |
| 4 | RagAgent 配置检索条数的配置键叫什么？ | 1 | 1 | — |
| 5 | RagAgent 文档解析功能的验收暗号是什么？ | 1 | 1 | — |
| 6 | spring-boot-starter-web 默认引入哪个内嵌 Web 容器？ | 2 | **1** | ↑ |
| 7 | RagAgent 用什么 HTTP 客户端流式调用大模型？ | 1 | 1 | — |
| 8 | Spring Boot 自动配置类的清单文件名是什么？ | 2 | **1** | ↑ |

## 四、解读

- 语料仅 8 个分块，**8/8 不代表生产水平**：小语料下答案几乎必然落在 top3；该基线的价值是固定一把尺子，供后续改动重跑对比。
- Rerank 将 2 道题从第 2 提升到第 1，MRR 从 0.813 提升到 0.938，Hit@3 不回退——验证了精排机制的正确性。
- 第 3 题（`@Repository`）仍排第 2，说明 rerank 也不能完全解决精确术语排序问题——这是未来"关键词多路融合"的触发信号。
- avgScore 不可跨模式比较：Noop 的 score 是余弦相似度（0~1），Rerank 的 score 是 rerank 模型打分（0~1 但量纲不同）。
- 重跑方式：应用启动后 `POST /eval/retrieval`，对比本表的 Hit@3、MRR、平均耗时；指标下滑即回退或分析。
