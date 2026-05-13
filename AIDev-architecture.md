# AIDev 项目架构文档

> 个人 RAG 系统，用于学习笔记管理与 AI 问答。
> Java 后端提供 REST API，Python 脚本负责文档摄入。
> 基于 macOS 开发与运行。

---

## 术语表

| # | 术语 | 中文 / 说明 |
|---|------|-------------|
| ① | **RAG** | Retrieval-Augmented Generation，检索增强生成。先检索相关知识，再让 LLM 基于检索结果回答问题，减少幻觉 |
| ② | **LLM** | Large Language Model，大语言模型（如 DeepSeek、GPT）。理解并生成自然语言 |
| ③ | **Embedding** | 向量化。将文本映射为高维空间中的数值向量，语义相近的文本向量距离更近 |
| ④ | **Vector** | 向量。Embedding 输出的数值数组，本系统使用 768 维向量 |
| ⑤ | **Qdrant** | 向量数据库。专门存储和检索向量数据的服务，支持余弦相似度搜索 |
| ⑥ | **Collection** | Qdrant 中的集合，类似关系数据库中的表，一组向量的容器 |
| ⑦ | **Cosine 距离** | 余弦相似度。衡量两个向量方向的接近程度，值越接近 1 表示语义越相似 |
| ⑧ | **Meilisearch** | 轻量级全文搜索引擎，使用 BM25 算法进行关键词匹配搜索 |
| ⑨ | **BM25** | 全文检索排序算法。根据关键词在文档中出现的频率和稀有度计算相关性得分 |
| ⑩ | **RRF** | Reciprocal Rank Fusion，倒数排序融合。合并多路检索结果的排序算法，公式 `1/(k+rank)` |
| ⑪ | **Chunk** | 分片/块。将长文档切分成多个小片段分别索引，便于精确检索 |
| ⑫ | **Ollama** | 本地运行 LLM 和 Embedding 模型的工具，无需网络 API |
| ⑬ | **nomic-embed-text** | Ollama 上的开源文本 Embedding 模型，输出 768 维向量 |
| ⑭ | **Token** | 词元。LLM 处理文本的最小单位，中文约 1 字 ≈ 1-2 tokens，英文约 1 词 ≈ 1-2 tokens |
| ⑮ | **Context Window** | 上下文窗口。LLM 能接收的最大 token 数，DeepSeek 为 1M（约 100 万 tokens） |
| ⑯ | **SSE** | Server-Sent Events，服务端推送事件。HTTP 长连接方式逐字符/逐块推送数据 |
| ⑰ | **RestTemplate** | Spring 提供的 HTTP 请求客户端，用于调用外部 REST API |
| ⑱ | **MD5** | 哈希算法。将任意数据映射为固定长度指纹，用于判断文件是否变更 |
| ⑲ | **watchdog** | Python 文件系统监听库，实时监控目录中的文件创建/修改/删除 |
| ⑳ | **防抖 (debounce)** | 连续触发时只执行最后一次，本系统设为 2 秒，避免频繁保存导致重复索引 |

---

## 一、系统总览

```
┌─────────────────────────────────────────────────────────────┐
│                    用户 / 客户端                              │
│              (HTTP / cURL / 前端)                            │
└──────────────────┬──────────────────────────────────────────┘
                   │
                   ▼
┌───────────────────────────────────────────────────────────────┐
│              Java Spring Boot Backend (port 8081)              │
│                                                                │
│  ┌──────────────┐  ┌──────────────┐  ┌────────────────────┐   │
│  │ DeepSeekCtrl  │  │ KnowledgeCtrl│  │  AppConfig         │   │
│  │ /api/chat/**  │  │ /api/knowledge│  │  (RestTemplate) ⑰│   │
│  └───┬───┬───────┘  └──────┬───────┘  └────────────────────┘   │
│      │   │                 │                                    │
│      ▼   ▼                 ▼                                    │
│  ┌─────────────┐  ┌──────────────────┐                         │
│  │ DeepSeekSvc │  │  GeneralRagSvc ① │                         │
│  │ (LLM②调用)   │  │  (RAG编排)       │                         │
│  └─────────────┘  └────────┬─────────┘                         │
│                            │                                    │
│              ┌─────────────┼─────────────┐                      │
│              ▼             ▼             ▼                      │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐          │
│  │ VectorService│  │MeiliSearchSvc│  │  FileParser   │          │
│  │ (Qdrant⑤向量) │  │(BM25⑨全文)   │  │(docx/xlsx)   │          │
│  └──────┬───────┘  └──────┬───────┘  └──────────────┘          │
└─────────┼──────────────────┼────────────────────────────────────┘
          │                  │
          ▼                  ▼
┌─────────────────┐  ┌──────────────────────┐
│  Qdrant (16333) │  │ Meilisearch (7700) ⑧│
│  向量数据库⑤     │  │ 全文搜索引擎          │
│  collection⑥:   │  │ index: aiknowledge-doc│
│  aiknowledge-doc│  └──────────────────────┘
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│  Ollama (11434) │
│  nomic-embed-text⑬│
│  (embedding③模型)│
└─────────────────┘

┌──────────────────────────────────────────┐
│       Python Ingestion Pipeline          │
│  ingest.py → 分块⑪ → embedding③ → 写入  │
│            → Qdrant + Meilisearch       │
└──────────────────────────────────────────┘
```

---

## 二、项目结构

```
AIDev/                                    # Java + Python 混合项目（macOS）
├── pom.xml                                # Maven 配置 (Spring Boot 2.7.9, Java 11)
├── .gitignore                             # 忽略 .venv/ __pycache__/ .idea/ 等
├── AIDev-architecture.md                  # 本架构文档
├── README.md                              # 项目说明
├── src/main/java/com/deepseek/demo/
│   ├── DeepSeekApplication.java           # Spring Boot 入口
│   ├── config/
│   │   └── AppConfig.java                 # RestTemplate⑰ 连接池(32总/8路由) + 超时(5s/30s)
│   ├── controller/
│   │   ├── DeepSeekController.java        # 聊天 & 知识库 API
│   │   └── KnowledgeController.java       # 文档摄入 & 同步 API
│   ├── dto/
│   │   ├── DeepSeekChatRequest.java       # LLM② 请求体
│   │   ├── DeepSeekChatResponse.java      # LLM 响应体
│   │   └── Message.java                   # 消息体
│   └── service/
│       ├── DeepSeekService.java           # DeepSeek LLM 调用
│       ├── GeneralRagService.java         # 文档 RAG① 编排
│       ├── VectorService.java             # Qdrant⑤ 向量搜索 + 混合检索(RRF⑩)
│       ├── MeiliSearchService.java        # Meilisearch⑧ 全文搜索
│       └── FileParser.java                # docx/xlsx 文件解析
├── src/main/resources/
│   ├── application.yml                    # 本地配置（${DEEPSEEK_API_KEY}，不写真实 key）
│   └── application.yml.example            # 配置模板，供新开发者参考
├── src/test/java/com/deepseek/demo/
│   ├── controller/DeepSeekControllerTest.java
│   └── service/DeepSeekServiceTest.java
├── ingestion-pipeline/                    # Python 摄入管线
│   ├── ingest.py                          # 文档分块⑪ + embedding③ + 写入
│   └── requirements.txt                   # Python 依赖
└── scripts/                               # Python 辅助脚本
    ├── pywc.py                            # 简化版 wc 工具
    └── test_pywc.py                       # pywc 测试
```

---

## 三、核心组件

### 3.1 组件职责矩阵

| 组件 | 职责 | 端口 | 技术栈 |
|------|------|------|--------|
| DeepSeekController | 聊天、RAG①、知识库搜索的 HTTP 入口 | 8081 | Spring Boot |
| KnowledgeController | 文档摄入、Meilisearch⑧ 同步管理 | 8081 | Spring Boot |
| DeepSeekService | 调用 DeepSeek LLM② API（非流式 + 流式） | — | RestTemplate⑰ |
| GeneralRagService | RAG① 流程编排：检索 → 过滤 → 组装提示 → LLM② | — | — |
| VectorService | Qdrant⑤ 向量检索 + 混合检索（RRF⑩ 合并） | 16333 | Qdrant HTTP API |
| MeiliSearchService | Meilisearch⑧ BM25⑨ 全文检索 | 7700 | Meilisearch HTTP API |
| FileParser | docx/xlsx 文件文本提取 | — | Apache POI |
| ingest.py | 文件分块⑪、embedding③、双路写入 | — | Ollama⑫ Python SDK |

### 3.2 DeepSeekService — LLM 调用

```
DeepSeekService
├── chat(message)            → POST /v1/chat/completions  (非流式)
├── chatWithSystem(prompt, msg)  → 带系统提示词的对话
├── chat(request)            → 底层 HTTP 调用
└── chatStream(msg, callback) → SSE⑯ 流式响应 (RestTemplate.execute⑰)
```

- 请求地址：`${deepseek.base-url}/v1/chat/completions`
- 鉴权：`Authorization: Bearer ${deepseek.api-key}`
- 模型：`deepseek-chat`
- 流式模式：通过 `RestTemplate.execute` 直接读取 HTTP 响应流，逐行解析 `data: ` SSE⑯ 事件

### 3.3 VectorService — 向量检索与混合检索

#### 3.3.1 初始化

`@PostConstruct init()` 在启动时自动检测 Qdrant⑤ collection⑥ 是否存在，不存在则创建（768 维、Cosine⑦ 距离）。

#### 3.3.2 Embedding③

调用 Ollama⑫ `/api/embed` 接口，使用 `nomic-embed-text⑬` 模型生成 768 维向量④。

#### 3.3.3 混合检索流程

```
searchDocsWithFullContent(question, limit)
  │
  ├── searchHybrid(question, limit*2, docCollection, docVectorName)
  │     │
  │     ├── searchQdrant()           # Qdrant⑤ 向量④搜索
  │     │     └── 关键词加权排序        # VECTOR_WEIGHT=0.6
  │     │
  │     ├── meiliSearchService.search()  # Meilisearch⑧ BM25⑨ 全文
  │     │
  │     └── rrfMerge()               # RRF⑩ (k=60) 合并两路结果
  │
  └── 上下文扩展
        └── scrollWithRange()        # 按文件+chunk⑪范围获取相邻chunk
```

**关键词加权**：从 query 中提取中英文关键词，计算每个结果中关键词的命中比例，按 `0.6 * vector_score + 0.4 * keyword_score` 重排。

**RRF⑩ 合并**：对 Qdrant⑤ 和 Meilisearch⑧ 两路结果按 `1 / (k + rank)` 公式计算 RRF 得分，k=60，合并后按得分降序排列。这样即使某一路漏掉了某个结果，另一路也能补上。

**上下文扩展**：对命中的结果，按文件分组，取匹配 chunk⑪ 前后各 2 个 chunk 拼接到一起，提供更完整的上下文。

### 3.4 MeiliSearchService⑧ — 全文搜索

封装 Meilisearch REST API 的搜索调用：
- `search(index, query, limit)` → `POST /indexes/{index}/search`
- 返回结构与 VectorService 的 `parseSearchResults` 兼容
- 搜索失败时返回空列表（非致命降级）

### 3.5 GeneralRagService① — RAG 编排

```
ragChat(question, limit)
  ├── vectorService.searchDocsWithFullContent(question, limit)
  │     └── 内部：混合检索(RRF⑩合并) + 上下文扩展
  ├── filterAndTruncate()              # score ≥ 0.01 过滤 + 12000字符截断
  └── deepSeekService.chatWithSystem(prompt, question)
       └── prompt = 参考内容 + 用户问题
```

**质量保障**：
- RRF⑩ 得分阈值 0.01（约等于在一路检索中排名前 40）：低于此值的结果不进入 LLM②，减少噪声
- 上下文截断 12000 字符（约 6000 tokens⑭），控制送入 LLM② 的知识量
- 空上下文降级为纯 LLM 回答

### 3.6 FileParser — 文件解析

| 格式 | 解析方式 | 依赖 |
|------|----------|------|
| .docx | XWPFWordExtractor | poi-ooxml |
| .xlsx / .xls | XSSFWorkbook → 逐行逐列 | poi-ooxml |
| .txt / .md / .csv / .json / .xml / .yml / .properties / .html / .css | Files.readString | — |

### 3.7 ingest.py — 文档摄入管线

```
ingest.py <directory> [options]

流程（每次运行）:
  1. 扫描目录，收集所有支持的文件
  2. 读取文件内容 + 计算 MD5⑱ hash
  3. 从 Qdrant⑤ 读取已索引文件的 hash 列表
  4. 对比 diff：
     ├── hash 一致 → 跳过
     ├── 新增/修改 → 分块⑪ → embedding③ → 写 Qdrant → 写 Meilisearch⑧
     └── 已删除    → 从 Qdrant + Meilisearch 删除
```

**分片⑪ 策略**：

| 文件类型 | 分片方式 |
|----------|----------|
| .md / .markdown | 按 `##` 标题切分 |
| .txt / .docx / .pptx / .html | 按段落合并，滑动窗口 200~1500 字符 |
| .py / .js / .ts / .go / .java 等代码 | 按函数/类边界切分 |
| .png / .jpg / .gif / .webp | llava 视觉模型生成文字描述 |

**双路写入**：

```
分块⑪ → embedding③ → Qdrant⑤ (向量④)
   └→ 同时写入 → Meilisearch⑧ (全文)
```

向 Qdrant 写入向量④ 的同时写入 Meilisearch⑧，实现向量搜索 + 关键词搜索双路覆盖。

**监听模式**（`--watch`）：
- 启动时先增量同步
- 通过 watchdog⑲ 监听文件变更
- 2 秒防抖⑳，避免频繁重复索引
- 增删改自动同步

---

## 四、并发与性能优化

### 4.1 HTTP 连接池

RestTemplate 使用 Apache HttpClient 连接池，避免每次请求创建新连接：

| 参数 | 值 | 说明 |
|------|:--:|------|
| 最大总连接 | 32 | 支撑公司初期并发 |
| 单路由最大连接 | 8 | 每个目标服务（Ollama/Qdrant/Meilisearch）最多 8 个并发 |
| 连接超时 | 5s | 建立连接超时 |
| 读取超时 | 30s | 等待响应超时，Ollama embedding 可能较慢 |

### 4.2 Embedding 缓存

相同查询文本在 **5 分钟内** 不重复调用 Ollama，直接返回缓存向量：

```
用户A 搜索 "什么是微服务"  →  embedding调用 → 缓存
用户B 5秒后搜同样的问题 →  命中缓存，跳过 Ollama
```

- 缓存上限 1000 条，超限时惰性淘汰过期项
- 适用于高频重复查询场景（如团队多人搜索同一知识点）

---

## 五、API 接口

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/chat` | 直接对话（非流式） |
| POST | `/api/chat/system` | 带系统提示词对话 |
| POST | `/api/chat/stream` | 流式对话 SSE⑯ |
| POST | `/api/chat/stream/system` | 流式对话 SSE（带系统提示词） |
| POST | `/api/chat/knowledge` | 文档知识库问答（非流式） |
| POST | `/api/chat/knowledge/stream` | 文档知识库问答（流式） |
| POST | `/api/chat/knowledge/search` | 纯检索（不经 LLM②） |
| POST | `/api/knowledge/ingest/doc` | 文档摄入 |
| POST | `/api/knowledge/sync/meilisearch` | Qdrant⑤ → Meilisearch⑧ 全量同步 |

---

## 六、数据结构

### 6.1 Qdrant⑤ Payload

```json
{
  "text": "chunk⑪ 文本内容",
  "chunk_index": 0,
  "total_chunks": 5,
  "file_path": "/path/to/file.md",
  "file_name": "file.md"
}
```

### 6.2 Meilisearch⑧ Document

```json
{
  "id": "md5⑱(chunk_text)",
  "text": "chunk 文本内容",
  "file_path": "/path/to/file.md",
  "file_name": "file.md",
  "chunk_index": 0,
  "file_type": ".md",
  "file_hash": "md5(原文件)"
}
```

---

## 七、配置说明

> ⚠️ **安全警告**：`application.yml` 中的 `deepseek.api-key` 使用 `${DEEPSEEK_API_KEY}` 占位符，**禁止直接写入真实 key**。
> 真实 key 通过环境变量或 IDE Run Configuration 传入，防止误提交到 git 仓库。

```yaml
deepseek:
  api-key: ${DEEPSEEK_API_KEY}
  base-url: https://api.deepseek.com

qdrant:
  host: localhost
  port: 16333
  doc-collection: aiknowledge-doc    # collection⑥ 名称

ollama:
  host: localhost
  port: 11434
  model: nomic-embed-text⑬

meilisearch:
  host: localhost
  port: 7700

server:
  port: 8081
```

---

## 八、基础设施

| 组件 | 版本 | 启动方式 |
|------|------|----------|
| Qdrant⑤ | v1.18.0 | Docker (`qdrant/qdrant:v1.18.0`) |
| Meilisearch⑧ | 1.43.0 | Homebrew (`brew services start meilisearch`) |
| Ollama⑫ | 0.23.2 | 本地运行 |
| Embedding③ 模型 | nomic-embed-text⑬ | `ollama pull nomic-embed-text` |
| Java | 11 | Maven 管理 |
| Python | 3.11 | Homebrew，虚拟环境 `.venv`（`pip install -r requirements.txt`） |

---

## 九、关键设计决策

| 决策 | 选择 | 理由 |
|------|------|------|
| 向量数据库 | Qdrant⑤ | 轻量、Docker 一键部署、支持 named vectors |
| 全文搜索引擎 | Meilisearch⑧ | 比 ES 更轻量，32GB 内存可运行，BM25⑨ 算法 |
| Embedding③ | Ollama⑫ 本地 nomic-embed-text⑬ | 本地运行，无 API 费用，768 维 |
| LLM② | DeepSeek API | 性价比高，1M context window⑮ |
| 检索策略 | 向量④ + BM25⑨ 双路 + RRF⑩ 合并(k=60) | 语义+关键词互补，提高召回率 |
| 低分过滤阈值 | RRF 得分 ≥ 0.01 | RRF 得分非绝对值，阈值过低无意义，过高则丢失结果；0.01 ≈ 单路前 40 名 |
| 上下文截断 | 12000 字符 | 限制送入 LLM 的知识量，减少噪声 |
| 降级策略 | 组件异常时静默降级 | 不阻塞主流程 |

---

## 十、健壮性保障

| 场景 | 处理方式 |
|------|----------|
| Qdrant⑤ 不可用 | 启动时打警告延迟初始化 |
| Meilisearch⑧ 不可用 | 搜索返回空列表，不中断 |
| 低分结果 | RRF⑩ score < 0.01 过滤，不进 LLM②（等价于在一路检索中排名前 40 以上才保留） |
| 上下文超长 | 按 score 排序截断至 12000 字符 |
| Ollama⑫ embedding③ 失败 | 3 次重试，指数退避 |
| 文件读取异常 | 跳过该文件，不中断整体流程 |
