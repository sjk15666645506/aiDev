# AIDev 项目架构文档

> RAG 系统 + Agent 引擎。
> Java 后端提供 REST API + Agent 引擎，Python 脚本负责文档摄入。
> LLM 统一使用 **DeepSeek V4 Flash**（云端 API），无本地模型。
> 基于 macOS (M5) 开发与运行。

---

## 术语表

| # | 术语 | 中文 / 说明 |
|---|------|-------------|
| ① | **RAG** | Retrieval-Augmented Generation，检索增强生成。先检索相关知识，再让 LLM 基于检索结果回答问题，减少幻觉 |
| ② | **LLM** | Large Language Model，大语言模型（DeepSeek V4 Flash）。理解并生成自然语言 |
| ③ | **Embedding** | 向量化。将文本映射为高维空间中的数值向量，语义相近的文本向量距离更近 |
| ④ | **Vector** | 向量。Embedding 输出的数值数组，本系统使用 768 维向量 |
| ⑤ | **Qdrant** | 向量数据库。专门存储和检索向量数据的服务，支持余弦相似度搜索 |
| ⑥ | **Collection** | Qdrant 中的集合，类似关系数据库中的表，一组向量的容器 |
| ⑦ | **Cosine 距离** | 余弦相似度。衡量两个向量方向的接近程度，值越接近 1 表示语义越相似 |
| ⑧ | **Meilisearch** | 轻量级全文搜索引擎，使用 BM25 算法进行关键词匹配搜索 |
| ⑨ | **BM25** | 全文检索排序算法。根据关键词在文档中出现的频率和稀有度计算相关性得分 |
| ⑩ | **RRF** | Reciprocal Rank Fusion，倒数排序融合。合并多路检索结果的排序算法，公式 `1/(k+rank)` |
| ⑪ | **Chunk** | 分片/块。将长文档切分成多个小片段分别索引，便于精确检索 |
| ⑫ | **Ollama** | 本地运行 Embedding 模型的工具，仅用于 nomic-embed-text |
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
| ㉖ | **Sina Finance API** | 新浪财经行情接口。批量查询 A 股 800 只/次，无限制，数据源稳定 |
| ㉗ | **East Money API** | 东方财富行情/基本面接口。F10 公司概况接口（并发 50 线程），用于批量获取行业映射 |

---

## 一、系统总览

```
┌──────────────────────────────────────────────────────────────────────┐
│                          用户 / 客户端                                 │
│                    (HTTP / cURL / Vue 前端)                          │
└────────────────────┬───────────────────────────────────────────────┘
                     │
                     ▼
┌────────────────────────────────────────────────────────────────────────┐
│                  Java Spring Boot Backend (port 8081)                  │
│                                                                        │
│  ┌──────────────┐  ┌──────────────┐  ┌────────────┐ │
│  │ DeepSeekCtrl  │  │KnowledgeCtrl │  │ AgentCtrl  │ │
│  │ /api/chat/**  │  │/api/knowledge│  │ /api/agent │ │
│  └───┬───┬───────┘  └──────┬───────┘  └──────┬─────┘ │
│      │   │                 │                  │       │
│      ▼   ▼                 ▼                  ▼       │
│  ┌─────────────┐  ┌──────────────────┐                 │
│  │ LangChain4j │  │  AgentSvc ㉒      │                 │
│  │ LlmSvc      │  │  (ReAct 循环㉓)    │                 │
│  │ (DS V4)     │  └────────┬─────────┘                 │
│  └─────────────┘           │                          │
│                            │                           │
│  ┌────────────────────────────────────┐                 │
│  │          Agent 模块组件             │                 │
│  │  ┌──────────────┐  ┌────────────┐  │                 │
│  │  │ ToolRegistry │  │Conversation│  │                 │
│  │  │ (@Tool扫描)   │  │Store(Redis)│  │                 │
│  │  └──────────────┘  └────────────┘  │                 │
│  │  ┌──────────────┐  ┌────────────┐  │                 │
│  │  │ Confirmation │  │  Tools     │  │                 │
│  │  │ Store(Redis) │  │(Task)      │  │                 │
│  │  └──────────────┘  └────────────┘  │                 │
│  └────────────────────────────────────┘                  │
│                                                          │
│  ┌─────────────┐  ┌──────────────────┐                   │
│  │GeneralRagSvc①│  │  (RAG编排)        │                   │
│  └────────┬─────┘  └──────────────────┘                               │
│           │                                                           │
│  ┌────────┼────────┐                                                  │
│  ▼        ▼        ▼                                                  │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐                │
│  │ VectorService│  │MeiliSearchSvc│  │  FileParser   │                │
│  │ (Qdrant⑤向量) │  │(BM25⑨全文)   │  │(docx/xlsx)   │                │
│  └──────┬───────┘  └──────┬───────┘  └──────────────┘                │
└─────────┼──────────────────┼──────────────────────────────────────────┘
          │                  │
          ▼                  ▼
┌─────────────────┐  ┌──────────────────────┐  ┌─────────────────┐
│  Qdrant (16333) │  │ Meilisearch (7700) ⑧│  │  Redis (6379) ㉑│
│  向量数据库⑤     │  │  全文搜索引擎          │  │  会话/确认点存储  │
│  collection⑥:   │  │ index: aiknowledge-doc│  │  TTL 自动过期   │
│  aiknowledge-doc│  └──────────────────────┘  └─────────────────┘
└────────┬────────┘
          │
          ▼
┌────────────────────────────────────┐
│  Ollama (11434) ⑫                  │
│  └── nomic-embed-text⑬ (embedding) │
└────────────────────────────────────┘

┌────────────────────────────────────┐
│  Ingestion Pipeline                │
│                                   │
│  ingestion-pipeline/               │
│  └── ingest.py   文档摄入 → Qdrant │
└────────────────────────────────────┘
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
│   │   ├── LangChain4jLlmService.java      # 唯一 LLM 实现（OpenAiChatModel 适配 DeepSeek API）
│   │   ├── IVectorSearchService.java       # 向量检索接口（VectorService 实现）
│   │   ├── IToolRegistry.java              # 工具注册接口（ToolRegistry 实现）
│   │   ├── GeneralRagService.java         # 文档 RAG① 编排
│   │   ├── VectorService.java             # 混合检索(RRF⑩) + 上下文扩展
│   │   ├── QdrantClient.java              # Qdrant⑤ HTTP 通信
│   │   ├── EmbeddingClient.java           # Embedding③ + 缓存
│   │   ├── MeiliSearchService.java        # Meilisearch⑧ 全文搜索
│   │   ├── FileParser.java                # docx/xlsx 文件解析
│   │   ├── AgentService.java              # Agent㉒ 编排 + 确认回调
│   │   ├── ReActEngine.java               # ReAct㉓ 循环核心 + autoMatchTool + 确认点创建
│   │   ├── LlmContext.java                # ThreadLocal 传递 LLM 请求上下文
│   │   ├── AgentFallback.java             # 兜底回复生成
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
│   │       └── MultiAgentTools.java       # 多 Agent 委派工具集
│   ├── store/                             # 持久化层（Redis + 本地缓存降级）
│   │   ├── IConversationStore.java        # 会话存储接口
│   │   ├── ConversationStore.java         # 会话上下文存储（Redis, TTL 30min）
│   │   ├── ConfirmationStore.java         # 确认点存储（Redis, TTL 5min）
│   │   └── LocalCache.java                # 本地缓存（TTL + 容量上限逐出）
│   └── util/
│       ├── ChatMessageJsonUtil.java        # ChatMessage ↔ JSON 序列化
│       └── StringUtils.java               # 公共字符串工具
├── src/main/resources/
│   ├── application.yml                    # 本地配置（${DEEPSEEK_API_KEY}）
│   └── application.yml.example            # 配置模板
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
├── ingestion-pipeline/                    # Python 数据管线
│   ├── ingest.py                          # 文档分块⑪ + embedding③ + 写入
│   └── requirements.txt                   # Python 依赖
├── scripts/                               # 工具脚本
│   ├── pywc.py                            # 简化版 wc 工具
│   └── test_pywc.py                       # pywc 测试
└── aiDev-vue/                             # Vue 3 前端应用
    ├── src/
    │   ├── api/index.ts                   # API 封装
    │   ├── pages/
    │   │   ├── ChatPage.vue               # 聊天页面
    │   │   ├── AgentPage.vue              # Agent 对话页面
    │   │   ├── KnowledgeQAPage.vue        # 知识库问答页面
    │   │   ├── KnowledgeSearchPage.vue    # 知识库搜索页面
    │   │   └── DocumentPage.vue           # 文档管理页面
    │   ├── router/index.ts                # 路由配置
    │   ├── layouts/MainLayout.vue         # 主导航布局
    │   └── App.vue                        # Vue 根组件
    ├── vite.config.ts                     # Vite 配置（代理 /api → localhost:8081）
    └── package.json
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
| LangChain4jLlmService | DeepSeek V4 Flash 调用（非流式 + 流式 + Tool Calling） | — | LangChain4j 0.33.x |
| GeneralRagService | RAG① 流程编排：检索 → 过滤 → 组装提示 → LLM② | — | — |
| VectorService | 混合检索（RRF⑩ 合并）+ 上下文扩展 | 16333 | Qdrant + Meilisearch |
| QdrantClient | Qdrant⑤ HTTP 通信 | 16333 | Qdrant REST API |
| EmbeddingClient | Embedding③ + 5min 缓存 | 11434 | Ollama nomic-embed-text |
| MeiliSearchService | Meilisearch⑧ BM25⑨ 全文检索 | 7700 | Meilisearch HTTP API |
| FileParser | docx/xlsx 文件文本提取 | — | Apache POI |
| ingest.py | 文件分块⑪、embedding③、双路写入 | — | Ollama⑫ Python SDK |
| AgentService | ReAct㉓ 编排 + 确认回调㉕ | — | DeepSeek API㉔ |
| ReActEngine | ReAct㉓ 循环 + autoMatchTool + 确认点创建 | — | DeepSeek API |
| ToolRegistry | @Tool 注解扫描、JSON Schema 生成、反射调用 | — | Spring Bean |
| DomainRouter | Layer1: 意图→领域分类 | — | DeepSeek API |
| ToolRetriever | Layer2: 领域内语义+频率工具召回 | — | EmbeddingClient |
| CapabilityGuard | Layer3: 执行前能力关键词校验 | — | 规则引擎 |
| ConversationStore | 会话上下文 Redis㉑ 存储（降级切 LocalCache） | 6379 | Redis + Jackson |
| ConfirmationStore | 确认点 Redis㉑ 存储（降级切 LocalCache） | 6379 | Redis + Jackson |
| LocalCache | 本地缓存：TTL 过期 + 容量上限逐出 | — | ConcurrentHashMap |

### 3.2 核心接口抽象

| 接口 | 实现类 | 核心方法数 |
|------|--------|-----------|
| `ILlmService` | `LangChain4jLlmService` | 5 |
| `IVectorSearchService` | `VectorService` | 6 + 1 静态方法 |
| `IToolRegistry` | `ToolRegistry` | 7 |
| `IConversationStore` | `ConversationStore` | 12 |

### 3.3 LangChain4jLlmService — LLM 调用（唯一 LLM 服务）

基于 LangChain4j 0.33.x `OpenAiChatModel` 适配 DeepSeek API（OpenAI 兼容）。

```
LangChain4jLlmService
├── chat(message)            → chatModel.generate(userMessage)
├── chatWithSystem(prompt, msg)  → chatModel.generate(system + user)
├── chat(messages)           → chatModel.generate(List<ChatMessage>)
├── chatWithTools(messages, tools) → chatModel.generate(messages, tools)
└── chatStream(msg, callback) → StreamingChatLanguageModel.generate()
```

- 模型：`deepseek-v4-flash`
- 所有消费者（聊天、Agent、知识库问答）共用此单一 LLM 服务

### 3.4 ~ 3.12 （Agent 引擎、向量检索、文档摄入等组件保持不变，详见原文档）

---

## 四、API 接口

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
| POST | `/api/agent/chat` | Agent㉒ 对话入口 |
| POST | `/api/agent/confirm` | Agent 确认回调㉕ |

---

---

## 六、配置说明

> ⚠️ **安全警告**：`application.yml` 中的 `deepseek.api-key` 使用 `${DEEPSEEK_API_KEY}` 占位符，**禁止直接写入真实 key**。

```yaml
deepseek:
  api-key: ${DEEPSEEK_API_KEY}
  base-url: https://api.deepseek.com
  model: deepseek-v4-flash

qdrant:
  host: localhost
  port: 16333
  doc-collection: aiknowledge-doc

ollama:
  host: localhost
  port: 11434
  model: nomic-embed-text

meilisearch:
  host: localhost
  port: 7700

spring:
  redis:
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
      max-capacity: 1000
      ttl-minutes: 30
  confirmation:
    local-cache:
      max-capacity: 500
      ttl-minutes: 5
```

---

## 七、基础设施

| 组件 | 版本 | 启动方式 |
|------|------|----------|
| Qdrant⑤ | v1.18.0 | Docker (`qdrant/qdrant:v1.18.0`) |
| Meilisearch⑧ | 1.43.0 | Homebrew (`brew services start meilisearch`) |
| Redis㉑ | 7.x | Homebrew (`brew services start redis`) |
| Ollama⑫ | 0.23.2 | `brew services start ollama`（仅 nomic-embed-text） |
| nomic-embed-text⑬ | 137M | `ollama pull nomic-embed-text` |
| Java | 11 | Maven 管理 |
| Python | 3.11 | Homebrew |
| Node.js | 20+ | Homebrew（前端构建） |

---

## 八、关键设计决策

| 决策 | 选择 | 理由 |
|------|------|------|
| 向量数据库 | Qdrant⑤ | 轻量、Docker 一键部署 |
| 全文搜索引擎 | Meilisearch⑧ | 比 ES 轻量，BM25⑨ 算法 |
| Embedding③ | Ollama⑫ 本地 nomic-embed-text⑬ | 无 API 费用，768 维 |
| LLM② | DeepSeek V4 Flash（云端 API） | 唯一 LLM，性价比高，1M context window⑮ |
| 检索策略 | 向量④ + BM25⑨ 双路 + RRF⑩ 合并(k=60) | 语义+关键词互补 |
| **Agent㉒ 模式** | **ReAct㉓ 循环 + HITL㉕ 双重确认** | **自动规划 + 人工兜底** |
| **路由架构** | **三层路由：DomainRouter → ToolRetriever → CapabilityGuard** | **逐层过滤工具空间** |
| **LLM 调用框架** | **LangChain4j 0.33.x OpenAiChatModel** | **原生 tool calling、streaming** |
| 数据采集 | Python 脚本（ProcessBuilder 调用） | Java 编排流程、Python 执行 |

---

## 九、健壮性保障

| 场景 | 处理方式 |
|------|----------|
| Qdrant⑤ 不可用 | 启动时打警告延迟初始化 |
| Meilisearch⑧ 不可用 | 搜索返回空列表，不中断 |
| Redis 连接断开 | ConversationStore / ConfirmationStore 自动切换 LocalCache |
| DeepSeek API 网络错误 | RestTemplate⑰ 5s 连接超时，重试 1 次 |
| DeepSeek API 限流 (429) | 等待 2s 后重试，最多 2 次 |
