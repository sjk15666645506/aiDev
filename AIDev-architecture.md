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
| ㉑ | **Redis** | 内存键值数据库。本系统用于存储会话状态和确认点，String 类型 + JSON，TTL 自动过期 |
| ㉒ | **Agent** | 智能体。本系统的 ReAct 引擎，LLM 自主规划操作、调用工具、迭代执行直至完成任务 |
| ㉓ | **ReAct** | Reasoning + Acting 循环。LLM 交替进行推理决策和工具调用，每一步基于上一步结果继续 |
| ㉔ | **Tool Calling** | LLM 调用预定义 API 的机制。DeepSeek 原生支持 function calling，返回 tool_calls |
| ㉕ | **Confirmation** | 确认机制。操作计划确认 + 写操作二次确认，HITL（Human-in-the-Loop）保障安全 |

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
│  │ LangChain4j │  │  AgentSvc ㉒      │                         │
│  │ LlmSvc       │  │  (ReAct 循环㉓)   │                         │
│  │ (LLM②调用)   │  │                   │                         │
│  └─────────────┘  └────────┬─────────┘                         │
│                            │                                    │
│  ┌──────────────────────────────────────────────────────┐       │
│  │               Agent 模块组件                          │       │
│  │  ┌──────────────┐  ┌──────────────────┐              │       │
│  │  │ ToolRegistry │  │ ConversationStore│              │       │
│  │  │ (@Tool扫描)   │  │ (Redis㉑ 会话存储) │              │       │
│  │  └──────────────┘  └──────────────────┘              │       │
│  │  ┌──────────────┐  ┌──────────────────┐              │       │
│  │  │ Confirmation │  │  Example Tools    │              │       │
│  │  │ Store(Redis) │  │  (Task/External) │              │       │
│  │  └──────────────┘  └──────────────────┘              │       │
│  └──────────────────────────────────────────────────────┘       │
│                                                                  │
│  ┌─────────────┐  ┌──────────────────┐                         │
│  │GeneralRagSvc①│  │  (RAG编排)        │                         │
│  └────────┬─────┘  └──────────────────┘                         │
│           │                                                     │
│  ┌────────┼────────┐                                            │
│  ▼        ▼        ▼                                            │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐          │
│  │ VectorService│  │MeiliSearchSvc│  │  FileParser   │          │
│  │ (Qdrant⑤向量) │  │(BM25⑨全文)   │  │(docx/xlsx)   │          │
│  └──────┬───────┘  └──────┬───────┘  └──────────────┘          │
└─────────┼──────────────────┼────────────────────────────────────┘
          │                  │
          ▼                  ▼
┌─────────────────┐  ┌──────────────────────┐  ┌─────────────────┐
│  Qdrant (16333) │  │ Meilisearch (7700) ⑧│  │  Redis (6379) ㉑│
│  向量数据库⑤     │  │ 全文搜索引擎          │  │  会话/确认点存储  │
│  collection⑥:   │  │ index: aiknowledge-doc│  │  TTL 自动过期   │
│  aiknowledge-doc│  └──────────────────────┘  └─────────────────┘
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
│   │   ├── AppConfig.java                 # RestTemplate⑰ 连接池(32总/8路由) + 超时(5s/30s)
│   │   └── RedisConfig.java               # Redis㉑ 配置 Bean
│   ├── annotation/                        # Agent㉒ 注解层
│   │   ├── Tool.java                      # @Tool 注解
│   │   ├── ToolDomain.java                # @ToolDomain 领域枚举
│   │   ├── ToolParam.java                 # @ToolParam 注解
│   │   └── ActionType.java                # 枚举 READ / WRITE㉕
│   ├── controller/
│   │   ├── DeepSeekController.java        # 聊天 & 知识库 API
│   │   ├── KnowledgeController.java       # 文档摄入 & 同步 API
│   │   ├── AgentController.java           # Agent㉒ 对话 & 确认 API
│   │   └── GlobalExceptionHandler.java    # 全局异常处理（@RestControllerAdvice）
│   ├── dto/
│   │   ├── AgentResponse.java             # Agent 统一响应
│   │   └── ConfirmationPoint.java         # 确认点㉕ DTO
│   ├── filter/
│   │   └── TraceFilter.java               # 全链路 traceId 注入（MDC + X-Trace-Id 响应头）
│   ├── service/
│   │   ├── ILlmService.java               # LLM 调用接口（LangChain4j 原生类型）
│   │   ├── LangChain4jLlmService.java      # LLM 调用实现（OpenAiChatModel 适配 DeepSeek API）
│   │   ├── IVectorSearchService.java       # 向量检索接口（VectorService 实现）
│   │   ├── IToolRegistry.java              # 工具注册接口（ToolRegistry 实现）
│   │   ├── GeneralRagService.java         # 文档 RAG① 编排
│   │   ├── VectorService.java             # 混合检索(RRF⑩) + 上下文扩展（844→260行）
│   │   ├── QdrantClient.java              # Qdrant⑤ HTTP 通信（从 VectorService 提取）
│   │   ├── EmbeddingClient.java           # Embedding③ + 缓存（从 VectorService 提取）
│   │   ├── MeiliSearchService.java        # Meilisearch⑧ 全文搜索
│   │   ├── FileParser.java                # docx/xlsx 文件解析
│   │   ├── AgentService.java              # Agent㉒ 编排 + 确认回调（plan状态生命周期管理）
│   │   ├── ReActEngine.java               # ReAct㉓ 循环核心 + autoMatchTool + 确认点创建
│   │   ├── LlmContext.java                # ThreadLocal 传递 LLM 请求上下文（conversationId）
│   │   ├── AgentFallback.java             # 兜底回复生成（无工具匹配/异常时）
│   │   ├── DomainRouter.java              # Layer1: 意图→领域分类
│   │   ├── ToolRegistry.java              # 工具注册中心（注解扫描/反射执行）
│   │   ├── ToolMeta.java                  # 工具元数据模型
│   │   ├── ToolRetriever.java             # Layer2: 语义+频率工具召回
│   │   ├── ToolVectorStore.java           # 工具 Embedding 内存向量存储
│   │   ├── CapabilityGuard.java           # Layer3: 执行前能力关键词校验
│   │   ├── CapabilityKeywords.java        # 域→关键词映射（11 组中英文）
│   │   ├── FrequencyTracker.java          # 工具调用频率追踪（时间衰减）
│   │   ├── SubAgent.java                  # 子 Agent 执行器（多 Agent 协作）
│   │   └── tools/
│   │       ├── TaskTools.java             # 任务管理工具集
│   │       ├── ExternalTools.java         # 外部服务工具集
│   │       ├── FinanceTools.java          # 金融计算工具集（buyStock/sellStock 使用 Integer 参数避免 JSON 类型不匹配）
│   │       └── MultiAgentTools.java       # 多 Agent 委派工具集
│   ├── store/                             # 持久化层（Redis + 本地缓存降级）
│   │   ├── IConversationStore.java        # 会话存储接口
│   │   ├── ConversationStore.java         # 会话上下文存储（Redis, TTL 30min, 分conversationId锁）
│   │   ├── ConfirmationStore.java         # 确认点存储（Redis, TTL 5min）
│   │   └── LocalCache.java                # 本地缓存（TTL + 容量上限逐出）
│   └── util/
│       └── ChatMessageJsonUtil.java        # ChatMessage ↔ JSON 序列化（标准 OpenAI 消息格式）
│       └── StringUtils.java               # 公共字符串工具（truncate 等）
├── src/main/resources/
│   ├── application.yml                    # 本地配置（${DEEPSEEK_API_KEY}，不写真实 key）
│   └── application.yml.example            # 配置模板，供新开发者参考
├── src/test/java/com/deepseek/demo/
│   ├── controller/
│   │   ├── DeepSeekControllerTest.java
│   │   └── AgentControllerTest.java
│   ├── dto/
│   │   └── AgentResponseTest.java
│   ├── service/
│   │   ├── AgentServiceTest.java
│   │   └── ToolRegistryTest.java
│   └── store/
│       ├── ConversationStoreTest.java
│       └── ConfirmationStoreTest.java
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
| AgentController | Agent㉒ 对话 & 确认回调 HTTP 入口 | 8081 | Spring Boot |
| GlobalExceptionHandler | 全局异常 → JSON（@RestControllerAdvice） | — | Spring Boot |
| TraceFilter | 全链路 traceId 注入（MDC + X-Trace-Id 响应头） | — | OncePerRequestFilter |
| LangChain4jLlmService | 调用 DeepSeek LLM② API（非流式 + 流式），基于 OpenAiChatModel 适配，实现 ILlmService | — | LangChain4j 0.33.x |
| GeneralRagService | RAG① 流程编排：检索 → 过滤 → 组装提示 → LLM② | — | — |
| VectorService | 混合检索（RRF⑩ 合并）+ 上下文扩展，实现 IVectorSearchService | 16333 | Qdrant + Meilisearch |
| QdrantClient | Qdrant⑤ HTTP 通信（collection/search/scroll/upsert） | 16333 | Qdrant REST API |
| EmbeddingClient | Embedding③ + 5min 缓存（从 VectorService 提取） | 11434 | Ollama |
| MeiliSearchService | Meilisearch⑧ BM25⑨ 全文检索 | 7700 | Meilisearch HTTP API |
| FileParser | docx/xlsx 文件文本提取 | — | Apache POI |
| ingest.py | 文件分块⑪、embedding③、双路写入 | — | Ollama⑫ Python SDK |
| LlmContext | ThreadLocal 上下文（conversationId 透传到 LLM 调用链路） | — | ThreadLocal |
| AgentService | ReAct㉓ 编排 + 确认回调㉕ + plan 状态生命周期管理 | — | DeepSeek API㉔ |
| ReActEngine | ReAct㉓ 循环核心：agentLoop + autoMatchTool（末位消息匹配）+ 确认点创建 | — | DeepSeek API |
| ToolRegistry | @Tool 注解扫描、JSON Schema 生成、反射调用，实现 IToolRegistry | — | Spring Bean |
| DomainRouter | Layer1: 意图→领域分类，轻量 LLM 调用 | — | DeepSeek API |
| ToolRetriever | Layer2: 领域内语义+频率工具召回 | — | EmbeddingClient + ToolVectorStore |
| CapabilityGuard | Layer3: 执行前能力关键词校验 | — | 规则引擎 |
| ToolVectorStore | 工具 Embedding 内存向量存储（余弦距离） | — | ConcurrentHashMap |
| FrequencyTracker | 工具调用频率追踪（时间衰减） | — | ConcurrentHashMap |
| ConversationStore | 会话上下文 Redis㉑ 存储（降级切 LocalCache，分 conversationId 锁），实现 IConversationStore | 6379 | Redis + Jackson |
| ConfirmationStore | 确认点 Redis㉑ 存储（降级切 LocalCache） | 6379 | Redis + Jackson |
| LocalCache | 本地缓存：TTL 过期 + 容量上限逐出 | — | ConcurrentHashMap + ScheduledExecutor |

### 3.2 核心接口抽象

Batch 3 引入 4 个核心接口，所有消费者面向接口编程：

| 接口 | 实现类 | 包 | 核心方法数 |
|------|--------|-----|------------|
| `ILlmService` | `LangChain4jLlmService` | service | 5 |
| `IVectorSearchService` | `VectorService` | service | 6 + 1 静态方法 |
| `IToolRegistry` | `ToolRegistry` | service | 7 |
| `IConversationStore` | `ConversationStore` | store | 12 |

`IVectorSearchService` 还承载静态工具方法 `truncateContexts(List, int)` 和常量 `MAX_CONTEXT_CHARS`。

### 3.3 LangChain4jLlmService — LLM 调用

基于 LangChain4j 0.33.x `OpenAiChatModel` 适配 DeepSeek API（OpenAI 兼容），实现 `ILlmService` 接口。替代旧版 `DeepSeekService`（基于 RestTemplate + 自定义 DTO）。

```
LangChain4jLlmService
├── chat(message)            → chatModel.generate(userMessage)
├── chatWithSystem(prompt, msg)  → chatModel.generate(system + user)
├── chat(messages)           → chatModel.generate(List<ChatMessage>)
├── chatWithTools(messages, tools) → chatModel.generate(messages, tools)
└── chatStream(msg, callback) → StreamingChatLanguageModel.generate()
```

- 请求地址：`${deepseek.base-url}/v1`（OpenAiChatModel 自动拼接 `/chat/completions`）
- 鉴权：`Authorization: Bearer ${deepseek.api-key}`
- 模型：`deepseek-v4-flash`（通过 `openai.chat.model` 配置）
- 接口全部使用 **LangChain4j 原生类型**：`ChatMessage`（`SystemMessage` / `UserMessage` / `AiMessage` / `ToolExecutionResultMessage`）、`Response<AiMessage>`、`ToolSpecification`
- 已删除旧版自建 DTO：`DeepSeekChatRequest`、`DeepSeekChatResponse`、`Message`、`ToolCall`、`FunctionCall`
- 流式模式：通过 `StreamingChatLanguageModel.generate()` + `StreamingResponseHandler` 回调

### 3.4 VectorService — 向量检索与混合检索

实现 `IVectorSearchService` 接口。Batch 2 将 Qdrant HTTP 通信提取为 `QdrantClient`、Embedding + 缓存提取为 `EmbeddingClient`，VectorService 自身专注于混合检索编排（844→260 行）。

#### 3.4.1 初始化

启动时委托 `QdrantClient.init()` 检测 collection 并自动创建（768 维、Cosine⑦ 距离）；`createCollection` 改为直接 PUT + 忽略 409（collection 已存在）。

#### 3.4.2 Embedding③

委托 `EmbeddingClient.embed(text)` → Ollama⑫ `/api/embed`，nomic-embed-text⑬ 768 维向量④，含 5 分钟 LRU 缓存。

#### 3.4.3 混合检索流程

```
searchDocsWithFullContent(question, limit)
  │
  ├── searchHybrid(question, limit*2, docCollection, docVectorName)
  │     │
  │     ├── embeddingClient.embed(question)    # Embedding③ (5min 缓存)
  │     ├── qdrantClient.search()             # Qdrant⑤ 向量④搜索
  │     │     └── 关键词加权排序                # VECTOR_WEIGHT=0.6
  │     │
  │     ├── meiliSearchService.search()       # Meilisearch⑧ BM25⑨ 全文
  │     │
  │     └── rrfMerge()               # RRF⑩ (k=60) 合并两路结果
  │
  └── 上下文扩展
        └── qdrantClient.scrollWithRange()    # 按文件+chunk⑪范围获取相邻chunk
```

**关键词加权**：从 query 中提取中英文关键词，计算每个结果中关键词的命中比例，按 `0.6 * vector_score + 0.4 * keyword_score` 重排。

**RRF⑩ 合并**：对 Qdrant⑤ 和 Meilisearch⑧ 两路结果按 `1 / (k + rank)` 公式计算 RRF 得分，k=60，合并后按得分降序排列。这样即使某一路漏掉了某个结果，另一路也能补上。

**上下文扩展**：对命中的结果，按文件分组，取匹配 chunk⑪ 前后各 2 个 chunk 拼接到一起，提供更完整的上下文。

### 3.5 MeiliSearchService⑧ — 全文搜索

封装 Meilisearch REST API 的搜索调用：
- `search(index, query, limit)` → `POST /indexes/{index}/search`
- 返回结构与 VectorService 的 `parseSearchResults` 兼容
- 搜索失败时返回空列表（非致命降级）

### 3.6 GeneralRagService① — RAG 编排

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

### 3.7 FileParser — 文件解析

| 格式 | 解析方式 | 依赖 |
|------|----------|------|
| .docx | XWPFWordExtractor | poi-ooxml |
| .xlsx / .xls | XSSFWorkbook → 逐行逐列 | poi-ooxml |
| .txt / .md / .csv / .json / .xml / .yml / .properties / .html / .css | Files.readString | — |

### 3.8 AgentService㉒ — ReAct㉓ 引擎

核心循环：三层路由引擎前置过滤工具，LLM 交替进行推理和工具调用㉔，直至生成最终回答或达到最大轮次。

#### 3.8.1 Chat 入口流程

```
AgentService.chat(conversationId, userMessage)
   │
   ▼
┌─ Layer 1: 领域路由 ──────────────────┐
│  DomainRouter.classify()             │
│  LLM 判断用户意图 → 选择一个 ToolDomain │
│  （任务管理 / 代码仓库 / CI_CD / ……）    │
└──────────────────────────────────────┘
   │
   ▼
┌─ Layer 2: 工具召回 ──────────────────┐
│  ToolRetriever.retrieve(domain, topK)│
│  语义向量检索 + 频率衰减补全           │
│  → 取 topK 工具 Schema 传给 LLM      │
└──────────────────────────────────────┘
   │
   ▼
① RAG 检索知识库 → 拼入 system prompt
   │
   ▼
② 获取/初始化 messages list
   │
   ├─ 首次消息: [system(含知识库)]
   │   planConfirmed=false, approvedPlan=null
   │
   └─ 后续消息: [+ 历史对话]
       planConfirmed 保持不变（首次确认后=true）
       approvedPlan=null（清除旧计划）
   │
   ▼
③ 追加 UserMessage → ReAct㉓ 循环
```

#### 3.8.2 ReAct㉓ 循环（agentLoop）

```
ReAct 循环 (max 10 轮)
   │
   ├─ 调用 LLM（带 ToolSpecification 列表）
   │
   ├─ 无 toolExecutionRequests
   │    │
   │    ├─ 自动匹配（autoMatchTool，仅第 1 轮）
   │    │    └─ 匹配成功:
   │    │         ├─ 工具刚执行过? → 跳过，返回 LLM 文本
   │    │         ├─ planConfirmed? → 执行（READ/WRITE 共同决定）
   │    │         └─ !planConfirmed → 生成计划确认点
   │    │
   │    └─ 匹配失败 → 返回最终回答 ✅
   │
   └─ 有 toolExecutionRequests
         │
         ├─ planConfirmed = false
         │   → 生成操作计划确认点㉕（READ+WRITE 均需确认）
         │     存 checkpoint 后 return，等用户确认
         │
         └─ planConfirmed = true → 逐个执行
              │
              ├─ approvedPlan != null → 过滤不在计划内的工具
              │  （首次确认后有效，后续消息已清空）
              │
              ├─ Layer 3: CapabilityGuard.validate()
              │  用户消息 vs 工具能力关键词 → 拒绝则回送 LLM
              │
              ├─ FrequencyTracker 记录调用
              │
              ├─ READ 工具 → 直接执行
              │
              └─ WRITE 工具
                   ├─ 白名单 → 直接执行
                   └─ 非白名单 → 生成二次确认点㉕
                             等用户确认后 handleExecConfirm 执行
```

**双重确认机制㉕**（按对话生命周期不同行为）：

| 阶段 | READ 工具 | WRITE 工具 |
|------|-----------|-----------|
| 首次消息（plan 未确认） | 需要 plan 确认 | 需要 plan 确认 |
| 首次消息（plan 已确认） | 直接执行 | 需要 exec 二次确认 |
| 后续消息 | 直接执行 | 需要 exec 二次确认 |

- **确认点 #1（操作计划确认）**：LLM 返回 ToolExecutionRequest 且 `planConfirmed=false` 时触发，用户确认后开始逐项执行
- **确认点 #2（写操作二次确认）**：非白名单 WRITE 操作逐项确认，防止误写
- plan 状态跨消息管理：首次确认后 `planConfirmed=true` 持久化，下条新消息自动继承
- **状态清理**：新消息追加时清除旧 `approvedPlan`，但保留 `planConfirmed=true`

#### 3.8.3 autoMatchTool — 自动工具匹配

当 LLM 未调用工具且本应为某个工具触发时，autoMatchTool 作为兜底机制：

```
autoMatchTool(messages, toolSchemas)
   │
   ├─ 从后向前查找最后一个 UserMessage（非首个）
   ├─ 遍历关键词规则表匹配
   │    例: "买入" → buy_stock, "净值" → query_stock_nav
   └─ 返回匹配到的 ToolExecutionRequest
```

**保护措施**：
- 匹配前检查该工具结果是否已在对话中（`ToolExecutionResultMessage`）→ 跳过，直接返回 LLM 文本
- 仅在第 1 轮迭代 (`i == 0`) 触发，避免循环自动匹配

#### 3.8.4 SubAgent — 子 Agent 执行器

被 `MultiAgentTools.delegateTask` 调用，在主 Agent 的 ReAct 循环内独立执行子任务：

```
主 Agent 识别到需要其他领域专家处理
  → 调用 delegate_task 工具
    → SubAgent.execute(task, domain)
      → 限定于目标领域的工具列表（如 FINANCE 只有金融工具）
      → mini ReAct 循环（最多 3 轮，无确认流程）
      → 返回最终结果给主 Agent
```

特点：无 Redis 持久化、无确认流程、工具域隔离——子 Agent 只管执行并返回，结果由主 Agent 汇总统筹。

#### 3.8.5 AgentFallback — 兜底回复

当 Agent 链路异常时（无匹配工具、工具调用失败、LLM 异常、满 10 轮），生成用户友好的中文兜底消息，避免将技术异常暴露给用户。

**类型现代化（Phase 3）**：

- 全部使用 **LangChain4j 0.33.x 原生类型**：`ChatMessage`（`SystemMessage` / `UserMessage` / `AiMessage` / `ToolExecutionResultMessage`）、`Response<AiMessage>`、`ToolExecutionRequest`、`ToolSpecification`
- 已删除旧版自建 DTO：`DeepSeekChatRequest`、`DeepSeekChatResponse`、`Message`、`ToolCall`、`FunctionCall`、`DeepSeekResponseNormalizer`
- `ConversationStore` 消息通过 `ChatMessageJsonUtil` 序列化（标准 OpenAI 消息格式，Jackson 手动 toMap/fromMap）
- `ConfirmationStore` pending requests 通过 `List<Map<String,String>>` 序列化（`ToolExecutionRequest` 为 LC4j 不可变类，不可直接 Jackson 序列化）
- `LlmContext` 通过 ThreadLocal 在 LLM 调用链路中传递当前 conversationId，替代方法参数传递

### 3.9 ToolRegistry — 工具注册中心

```
启动时：
  @PostConstruct → 扫描所有 Bean → 收集 @Tool 注解方法
  → 注册到 Map<String, ToolMeta> → 可生成 LangChain4j ToolSpecification 列表

运行时：
  execute(ToolExecutionRequest) → 反射调用对应方法 + 10s 超时保护
  isAutoConfirm(toolName) → 判断是否在白名单中（跳过二次确认㉕）
```

### 3.10 ConversationStore — 会话存储

- 存储位置：Redis㉑ `conversation:{conversationId}`（String 类型 + Jackson JSON）
- 存储内容：消息历史（ChatMessage 列表通过 `ChatMessageJsonUtil` 序列化为 JSON 字符串）、checkpoint 轮次、planConfirmed 标志、已批准的操作计划
- 过期策略：Redis TTL 30 分钟，无访问自动过期
- **降级策略**：Redis 不可用时自动切换 `LocalCache`（1000 条容量上限 / 30 分钟 TTL / 超限随机逐出），Redis 恢复后静默切回

### 3.11 ConfirmationStore — 确认点存储

- 存储位置：Redis㉑ `confirmation:{confirmationId}`（String 类型 + Jackson JSON）
- 两种类型：plan（操作计划确认，planToolCalls 存为 `List<Map>`）、exec（写操作二次确认，pendingRequests 通过 `List<Map<String,String>>` 序列化）
- 过期策略：Redis TTL 5 分钟
- **降级策略**：Redis 不可用时自动切换 `LocalCache`（500 条容量上限 / 5 分钟 TTL / 超限随机逐出），Redis 恢复后静默切回

### 3.12 ingest.py — 文档摄入管线

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
| POST | `/api/agent/chat` | Agent㉒ 对话入口（非流式），传入 `conversation_id` 继续已有会话 |
| POST | `/api/agent/confirm` | Agent 确认回调㉕（确认/拒绝/反馈），支持 `conversation_id` + `confirmation_id` + `confirm` + `feedback` |

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

spring:
  redis:                              # Agent㉒ 会话 & 确认点存储
    host: localhost
    port: 6379
    timeout: 2000

server:
  port: 8081

tool:
  whitelist: ${TOOL_WHITELIST:send_notification,update_task_status,feishu_send_message}

store:
  conversation:
    local-cache:
      max-capacity: 1000        # Redis 降级本地缓存上限
      ttl-minutes: 30            # 降级缓存条目 TTL
  confirmation:
    local-cache:
      max-capacity: 500          # 确认点本地缓存上限
      ttl-minutes: 5             # 确认点缓存 TTL（与 Redis 一致）
```

---

## 八、基础设施

| 组件 | 版本 | 启动方式 |
|------|------|----------|
| Qdrant⑤ | v1.18.0 | Docker (`qdrant/qdrant:v1.18.0`) |
| Meilisearch⑧ | 1.43.0 | Homebrew (`brew services start meilisearch`) |
| Redis㉑ | 7.x | Homebrew (`brew services start redis`) |
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
| **Agent㉒ 模式** | **ReAct㉓ 循环 + HITL㉕ 双重确认** | **LLM 自主规划执行，关键写操作人工兜底** |
| **会话持久化** | **Redis㉑ String + JSON** | **比内存方案更可靠，TTL 自动过期无需定时清理；Jackson 手动序列化避免 JDK 序列化兼容问题** |
| **二次确认㉕ 策略** | **WRITE + 非白名单 → 确认点** | **白名单（飞书等可信操作）自动执行，非白名单写操作逐项确认，平衡效率与安全** |
| **路由架构** | **三层路由：DomainRouter → ToolRetriever → CapabilityGuard** | **逐层过滤工具空间，减少 LLM 误调用、提高准确率** |
| **领域分类** | **LLM 轻量调用（DeepSeek + 简短系统提示）** | **比关键词匹配更准确理解用户意图，比完整 ReAct 更轻量（单次调用）** |
| **工具召回** | **语义 Embedding（nomic-embed-text）+ 频率衰减补全** | **语义检索找到功能匹配的工具，频率补全兜底冷启动和 Embedding 失败** |
| **能力校验** | **关键词规则引擎（CapabilityKeywords 中英文 11 组映射）** | **极低延迟（纯内存匹配），拒绝明显不匹配的调用，减少 LLM 幻觉执行** |
| **LLM 调用框架** | **LangChain4j 0.33.x OpenAiChatModel** | **替代 RestTemplate + 自定义 DTO，原生支持 tool calling、streaming、多态消息类型，减少自建代码和维护成本** |
| DeepSeek V4 `reasoning_content` 轮播 | LangChain4j OpenAiChatModel 内部透传非标准字段 | V4 thinking mode 强制要求回传此字段，否则 HTTP 400 |
| **Plan 生命周期** | **planConfirmed 持久化跨消息，approvedPlan 每新消息清空** | **首次确认后后续 READ 免确认；旧计划不污染新请求，WRITE 仍走 exec 确认** |
| **autoMatchTool 末位策略** | **从后向前查找最后一个 UserMessage + 跳过已执行工具** | **避免匹配历史旧消息导致误触发；工具执行完毕后直接返回 LLM 文本** |
| **工具参数类型匹配** | **@ToolParam number 对应 Java Integer，而非 String** | **LLM 返回 JSON number 类型，反射调用时 Integer 自动匹配，避免 argument type mismatch** |
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
| **Redis 连接断开** | **ConversationStore / ConfirmationStore 自动切换 LocalCache（本地内存），Redis 恢复后静默切回** |
| **本地缓存超限** | **LocalCache 随机逐出旧条目 + 定时清理过期条目（基于 createdAt），防止 OOM** |
| **DeepSeek API 网络错误** | **RestTemplate⑰ 5s 连接超时，捕获 `ResourceAccessException`，重试 1 次** |
| **DeepSeek API 限流 (429)** | **等待 2s 后重试，最多 2 次，仍失败返回"请求过于频繁"** |
| **DeepSeek API 鉴权失败 (401)** | **不重试，记录错误日志，返回"API 认证失败"** |
| **Tool㉔ 执行异常** | **异常信息以 tool role 回送 LLM，由 LLM 决定重试或告知用户** |
| **Tool 超时** | **单次执行 10s 超时保护，超时信息回送 LLM** |
| **ReAct㉓ 满 10 轮** | **返回已有结果 + 提示"任务可能未完全执行"** |
| **确认点过期 (TTL 5min)** | **Redis㉑ 自动过期，返回"确认已过期，请重新提问"** |
| **确认点重复消费** | **consumed 标志去重，返回"该操作已处理"** |
| **Tool 不在已批准计划中** | **跳过该调用，追加 system 提示** |
| **无工具匹配用户意图** | **AgentFallback.noSuitableTool()，返回"没有找到能处理该请求的工具"** |
| **LLM API 异常** | **AgentFallback.apiUnavailable()，返回"大脑暂时离线，请稍后再试"** |
| **Tool 调用返回异常** | **AgentFallback.toolExecutionFailed(name, detail)，异常回送 LLM 决定重试或告知用户** |
| **CapabilityGuard 校验不通过** | **拒绝执行，错误回送 LLM，由 LLM 修正调用或改用其他方式** |
| **DeepSeek V4 missing `reasoning_content`** | **LangChain4j OpenAiChatModel 内部透传非标准字段，确保 reasoning_content 在请求中保留并回传** |
| **DeepSeek V4 orphaned `assistant(tool_calls)`** | **400 "must be followed by tool messages" → AgentService.removeLastAssistantMessage() 注入新消息前移除 orphaned AiMessage** |
