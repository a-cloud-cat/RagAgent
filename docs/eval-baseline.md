# 检索评测基线（Hit@K Baseline）

> 记录日期：2026-09-26
> 评测入口：`POST /eval/retrieval`（实时复用主链路：百炼向量化 → pgvector 余弦检索）
> 评测集：`src/main/resources/eval/retrieval-cases.json`（8 题，锚定「文档名 + 期望短语」）

## 一、评测环境参数

| 参数 | 值 |
| --- | --- |
| 切块策略 | 固定窗口 300 字 / 重叠 50 字（`TextChunker`） |
| topK | 3（`rag.retrieval.top-k`） |
| 向量模型 | 百炼 `text-embedding-v4`，1536 维 |
| 向量库 | PostgreSQL + pgvector，余弦距离（`<=>`） |
| 活跃语料 | `spring-boot-notes.html`（6 块）+ `测试演示文稿.pdf`（2 块，作为干扰语料），共 8 块 |

评测前已软删除（`deleted=1`）早期测试重复上传的 `spring-boot-notes.txt`（与 HTML 版内容重复，会互相挤占 topK 导致结果失真）。

## 二、基线结果

| 指标 | 值 |
| --- | --- |
| **Hit@3** | **8 / 8 = 1.000** |
| MRR（据 hitRank 补算） | 0.813（5 题第 1、3 题第 2） |
| 平均首条相似度 | 0.667 |
| 平均耗时（向量化 + 检索） | 180.7 ms |

## 三、逐题明细

| # | 问题 | 命中 | 名次 |
| --- | --- | --- | --- |
| 1 | Spring Boot 最核心的设计理念是什么？ | ✅ | 1 |
| 2 | 对象不自己 new 依赖、交给容器管理，这种思想叫什么？ | ✅ | 1 |
| 3 | 数据访问层组件应该标注哪个构造型注解？ | ✅ | 2 |
| 4 | RagAgent 配置检索条数的配置键叫什么？ | ✅ | 1 |
| 5 | RagAgent 文档解析功能的验收暗号是什么？ | ✅ | 1 |
| 6 | spring-boot-starter-web 默认引入哪个内嵌 Web 容器？ | ✅ | 2 |
| 7 | RagAgent 用什么 HTTP 客户端流式调用大模型？ | ✅ | 1 |
| 8 | Spring Boot 自动配置类的清单文件名是什么？ | ✅ | 2 |

## 四、解读与后续对比口径

- 语料仅 8 个分块，**8/8 不代表生产水平**：小语料下答案几乎必然落在 top3；该基线的价值是固定一把尺子，供后续改动重跑对比。
- 3 道精确术语题（`@Repository` / `Tomcat` / `AutoConfiguration.imports`）答案均排在第 2 位而非第 1，语义召回到位但排序不优——是后续引入 **Rerank 精排**的触发信号。
- 重跑方式：应用启动后 `POST /eval/retrieval`，对比本表的 Hit@3、MRR、平均相似度与平均耗时；指标下滑即回退或分析。
