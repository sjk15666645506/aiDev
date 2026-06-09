# AIDev 项目架构文档

> RAG 系统 + 交易分析 Agent + 大盘分析 + ETF 分析。
> Java 后端提供 REST API + Agent 引擎，Python 脚本负责文档摄入 + 金融数据采集（含 ETF + K 线）。
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
| ㉘ | **Market Data** | 全量 A 股日频数据。每日采集 ~5400 只股票 OHLC + 大盘指数 + 涨跌统计 + 行业板块 |
| ㉙ | **ETF** | Exchange Traded Fund，交易型开放式指数基金。本系统采集 A 股 ETF 约 1000 只 |
| ㉚ | **NAV** | Net Asset Value，基金净值。ETF 的内在价值，由基金持仓市值计算得出 |
| ㉛ | **Premium Rate (溢价率)** | ETF 市场价相对净值的偏离程度，`(市价 - NAV) / NAV × 100%`。正值=溢价（买贵了），负值=折价 |
| ㉜ | **K 线 (K-Line)** | 蜡烛图。每个时间单位的 OHLC（开盘/最高/最低/收盘价）。本系统存储日 K 线，每只股票保留 250 个交易日 |

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
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐  ┌────────────┐ │
│  │ DeepSeekCtrl  │  │KnowledgeCtrl │  │ TradingCtrl  │  │ AgentCtrl  │ │
│  │ /api/chat/**  │  │/api/knowledge│  │ /api/trading │  │ /api/agent │ │
│  └───┬───┬───────┘  └──────┬───────┘  └──────┬───────┘  └──────┬─────┘ │
│      │   │                 │                  │                │       │
│      ▼   ▼                 ▼                  ▼                ▼       │
│  ┌─────────────┐  ┌──────────────────┐  ┌──────────────────────────┐  │
│  │ LangChain4j │  │  AgentSvc ㉒      │  │  TradingAnalysisSvc      │  │
│  │ LlmSvc      │  │  (ReAct 循环㉓)    │  │  (个股分析编排)           │  │
│  │ (DS V4)     │  └────────┬─────────┘  ├──────────────────────────┤  │
│  └─────────────┘           │              │  MarketAnalysisSvc       │  │
│                            │              │  (大盘分析)              │  │
│  ┌────────────────────────────────────┐  ├──────────────────────────┤  │
│  │          Agent 模块组件             │  │  MarketDataSvc            │  │
│  │  ┌──────────────┐  ┌────────────┐  │  │  (读取全市场数据)         │  │
│  │  │ ToolRegistry │  │Conversation│  │  ├──────────────────────────┤  │
│  │  │ (@Tool扫描)   │  │Store(Redis)│  │  │  StockDataSvc             │  │
│  │  └──────────────┘  └────────────┘  │  │  (调 yfinance_data.py)    │  │
│  │  ┌──────────────┐  ┌────────────┐  │  ├──────────────────────────┤  │
│  │  │ Confirmation │  │  Tools     │  │  │  EtfAnalysisSvc㉙          │  │
│  │  │ Store(Redis) │  │(Task/Fin)  │  │  │  (ETF 分析编排)           │  │
│  │  └──────────────┘  └────────────┘  │  ├──────────────────────────┤  │
│  └────────────────────────────────────┘  │  EtfDataSvc               │  │
│                                          │  (ETF 行情/净值/溢价率㉛)  │  │
│  ┌─────────────┐  ┌──────────────────┐  └──────────────────────────┘  │
│  │GeneralRagSvc①│  │  (RAG编排)        │                               │
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

┌─────────────────────────────────────────────────────────────────────┐
│              Python 数据管线（含 ETF + K 线）                        │
│                                                                     │
│  ingestion-pipeline/                                                │
│  ├── ingest.py                  文档摄入 → Qdrant + Meilisearch     │
│  ├── yfinance_data.py           金融数据获取（Sina㉖ + 东财㉗ + ETF）│
│  ├── batch_collect.py           ㉘ 全量 A 股 + ETF 日频采集         │
│  ├── DAILY_OPS.md               每日操作手册                        │
│  └── market_data/               日频数据存储                        │
│      ├── meta/                                                     │
│      │   ├── stocks_list.json      A 股清单 (~5488 只)             │
│      │   ├── etf_list.json         ETF 清单 (~1000 只) ㉙          │
│      │   ├── industry_map.json     行业映射 (并发 F10 获取)        │
│      │   └── history_index.json    采集历史日期索引                 │
│      ├── kline/                   日 K 线数据 (6451 只) ㉜         │
│      │   ├── 000001.json           个股 K 线 (250 个交易日)        │
│      │   └── ...                                                   │
│      └── YYYYMMDD/               每日数据                          │
│          ├── snapshot.json.gz      全市场快照（gzip ~170KB）       │
│          ├── etf_snapshot.json.gz  ETF 快照+净值（gzip ~30KB）㉙   │
│          ├── indices.json          大盘指数 (7个)                   │
│          ├── market_stats.json     涨跌统计                         │
│          ├── etf_stats.json        ETF 统计（净值/溢价率/TOP）㉛    │
│          ├── sectors.json          行业板块 (109个板块)             │
│          ├── top_stocks.json       TOP 榜单                        │
│          └── training.json         汇总训练样本                     │
└─────────────────────────────────────────────────────────────────────┘
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
│   │   ├── TradingController.java         # 交易分析 API（/api/trading/**）
│   │   ├── EtfController.java             # ETF 分析 API（/api/etf/**）㉙
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
│   │   ├── StockDataService.java           # 股票数据服务（调 Python yfinance_data.py）
│   │   ├── TradingAnalysisService.java     # 个股交易分析（数据→DS V4 Flash→分析报告）
│   │   ├── MarketDataService.java          # 全市场数据读取（读 batch_collect 采集数据）
│   │   ├── MarketAnalysisService.java      # 大盘分析（全市场数据→DS V4 Flash→盘面研判）
│   │   ├── EtfDataService.java             # ETF 数据服务（行情/净值㉚/溢价率㉛）
│   │   ├── EtfAnalysisService.java         # ETF 交易分析（净值→溢价率→LLM 分析报告）
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
│   │       ├── FinanceTools.java          # 金融计算工具集
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
│   ├── yfinance_data.py                   # 金融数据获取（Sina㉖ + 东财㉗ + yfinance + ETF 净值和溢价率㉛）
│   ├── batch_collect.py                   # ㉘ 全量 A 股 + ETF 日频采集（含 K 线㉜）
│   ├── DAILY_OPS.md                       # 每日操作说明
│   ├── market_data/                       # 日频全市场数据
│   │   ├── meta/                          # 元数据（股票清单/ETF清单/行业映射/历史索引）
│   │   ├── kline/                         # 每日 K 线数据（6451 只股票，东方财富+Sina 源）
│   │   └── YYYYMMDD/                      # 每日快照 + ETF + 统计
│   ├── requirements.txt                   # Python 依赖
│   ├── pywc.py                            # 简化版 wc 工具
│   └── test_pywc.py                       # pywc 测试
└── aiDev-vue/                             # Vue 3 前端应用
    ├── src/
    │   ├── api/index.ts                   # API 封装（chat / trading / market / agent）
    │   ├── pages/
    │   │   ├── TradingPage.vue            # 个股交易分析页面
    │   │   ├── MarketPage.vue             # 大盘分析页面
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
| TradingController | 个股分析、大盘分析、搜索、行情接口 | 8081 | Spring Boot |
| EtfController | ETF 分析、行情、搜索、统计接口 | 8081 | Spring Boot |
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
| TradingAnalysisService | 个股交易分析编排 | — | LangChain4j② |
| MarketDataService | 读取 batch_collect 采集的全市场数据 | — | Jackson |
| MarketAnalysisService | 大盘分析编排（数据→DS V4→盘面研判） | — | LangChain4j② |
| StockDataService | 个股数据获取（通过 ProcessBuilder 调用 Python） | — | ProcessBuilder + Jackson |
| EtfDataService | ETF 数据获取（行情/净值㉚/溢价率㉛），通过 yfinance_data.py etf-full | — | ProcessBuilder + Jackson |
| EtfAnalysisService | ETF 交易分析编排（数据→DS V4→分析报告） | — | LangChain4j② |
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
- 所有消费者（聊天、Agent、交易分析、大盘分析）共用此单一 LLM 服务

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
| POST | `/api/trading/analyze` | **个股交易分析**：输入 `symbol` + 可选 `request`，返回报价 + 技术指标 + DS V4 Flash 分析 |
| POST | `/api/trading/batch` | 批量分析多个股票 |
| GET | `/api/trading/quote/{symbol}` | 查询实时报价 |
| GET | `/api/trading/indicators/{symbol}` | 查询技术指标（不调 LLM） |
| GET | `/api/trading/search?q=` | 搜索股票（代码或名称），返回匹配列表 |
| POST | `/api/trading/market-analysis` | **大盘分析**：基于每日全量数据，DS V4 Flash 输出盘面情绪、关注板块、个股 |
| POST | `/api/etf/analyze` | **ETF 交易分析**：输入 ETF 代码 + 可选 request，返回行情+净值+溢价率㉛+DS V4 分析 |
| POST | `/api/etf/batch` | 批量分析多个 ETF |
| GET | `/api/etf/quote/{symbol}` | 查询 ETF 实时报价（含净值、溢价率） |
| GET | `/api/etf/indicators/{symbol}` | 查询 ETF 技术指标（不调 LLM） |
| GET | `/api/etf/search?q=` | 搜索 ETF（代码或名称） |
| GET | `/api/etf/stats` | 获取今日 ETF 市场统计（需先运行 batch_collect.py） |

---

## 五、交易分析系统

### 5.1 个股分析流程

```
POST /api/trading/analyze { symbol: "600519" }
        │
        ▼
┌─ TradingAnalysisService ──────────────────────────────────┐
│                                                           │
│ ① StockDataService（Python ProcessBuilder）                │
│    ├── 实时行情  ← Sina Finance㉖ / 东方财富 / yfinance      │
│    ├── 历史 K 线 ← 东方财富 / Sina                           │
│    ├── 技术指标  ← 本地计算（MA/RSI/MACD/波动率/支撑阻力）    │
│    └── 基本面    ← 东财数据中心（F10 财报，仅A股）            │
│                                                           │
│ ② MarketDataService（大盘背景注入）                        │
│    ├── 大盘指数（7 个）                                     │
│    ├── 全市场涨跌比、涨停跌停统计                              │
│    ├── 个股所属行业板块表现                                    │
│    ├── 个股 vs 板块相对强度                                    │
│    └── 个股是否在今日 TOP 榜单中                               │
│                                                           │
│ ③ 构建结构化提示词 → 调用 DeepSeek V4 Flash                │
│    └── 输出: 大盘背景→趋势研判→技术位→交易建议→基本面→风险    │
│                                                           │
│ ④ 返回 JSON（报价 + 指标 + 大盘上下文 + LLM 分析报告）      │
│                                                           │
└───────────────────────────────────────────────────────────┘
```

### 5.2 大盘分析流程

```
POST /api/trading/market-analysis
        │
        ▼
┌─ MarketAnalysisService ───────────────────────────────────┐
│                                                           │
│ ① 读取当日全市场数据（training.json）                      │
│    ├── 7 大指数                                            │
│    ├── 涨跌家数、涨跌比、涨停跌停、涨跌分布                     │
│    ├── 行业板块排行（109 个板块，涨幅前20 + 跌幅前20）         │
│    └── TOP 个股榜单（涨幅/跌幅/成交额各 TOP10）               │
│                                                           │
│ ② 构建结构化 Prompt → 调用 DeepSeek V4 Flash              │
│    └── 输出: 盘面综述→板块关注→个股关注→后市研判             │
│                                                           │
│ ③ 返回 JSON（指数+统计数据+TOP榜单+LLM分析报告）            │
│                                                           │
└───────────────────────────────────────────────────────────┘
```

### 5.3 ETF 分析流程

```
POST /api/etf/analyze { symbol: "510050" }
        │
        ▼
┌─ EtfAnalysisService ─────────────────────────────────────┐
│                                                           │
│ ① EtfDataService（Python yfinance_data.py etf-full）      │
│    ├── 实时行情  ← Sina / 东方财富                        │
│    ├── 历史 K 线 ← 东方财富 / Sina                         │
│    ├── 基金净值  ← 东方财富基金估值接口 (NAV) ㉚            │
│    ├── 溢价率㉛   ← 本地计算 (市价 - NAV) / NAV            │
│    ├── 技术指标  ← 本地计算 (MA/RSI/MACD/波动率)            │
│    └── 跟踪指数  ← ETF 跟踪的指数信息                       │
│                                                           │
│ ② MarketDataService（大盘背景注入）                        │
│    ├── 大盘指数（7 个）                                    │
│    └── 全市场涨跌统计                                      │
│                                                           │
│ ③ 构建 ETF 专家提示词 → 调用 DeepSeek V4 Flash            │
│    └── 输出: 净值分析→溢价率㉛判断→趋势研判→套利建议→风险   │
│                                                           │
│ ④ 返回 JSON（行情 + NAV + 溢价率 + 大盘背景 + LLM 分析）  │
│                                                           │
└───────────────────────────────────────────────────────────┘
```

**与个股分析的区别**：
1. 提示词包含 ETF 特有的净值/溢价率/跟踪误差分析
2. 分析维度侧重折溢价套利、流动性、跟踪偏差
3. 无基本面分析（ETF 无 EPS/BPS），替代为跟踪指数和基金规模

### 5.4 数据采集（batch_collect.py）

每个交易日 15:30 定时执行。支持命令行选项：
- `python3 batch_collect.py` — 全量采集（含个股 + ETF）
- `python3 batch_collect.py --no-etf` — 仅采集股票
- `python3 batch_collect.py --status` — 查看采集状态
- `python3 batch_collect.py --update-list` — 刷新股票+ETF清单

#### 个股 + 指数采集

| 步骤 | 耗时 | 数据量 |
|------|------|--------|
| ① 加载股票清单（缓存） | ~0s | ~5488 只 |
| ② 获取大盘指数（7个） | ~1s | 上证/深证/创业/科创/300/50/500 |
| ③ 行业映射（F10 并发 50 线程） | ~10s | ~5300 只含行业 (覆盖率 97%+) |
| ④ 全市场个股快照（Sina 批量 7批） | ~8s | ~5300 只 OHLC + 成交量 |
| ⑤ 涨跌统计 + 板块聚合 | ~1s | 涨跌分布 + 109 个板块排行 |
| ⑥ 存储（gzip + JSON） | ~0.3s | ~170KB/天 |
| **个股小计** | **~20s** | |

#### ETF 采集（--no-etf 时跳过）

| 步骤 | 耗时 | 数据量 |
|------|------|--------|
| ① 发现 ETF 代码（首次 / 30天刷新） | ~20s | ~1000 只 |
| ② ETF 批量快照（Sina 2批） | ~2s | ~1000 只 OHLC + 成交量 |
| ③ 基金净值获取（东方财富） | ~3s | 逐只获取 NAV ㉚ |
| ④ 溢价率计算 ㉛ | ~0.1s | 市价 vs NAV 偏离度 |
| ⑤ ETF 统计聚合 | ~0.2s | 净值排行 + 溢价率排行 + TOP ETF |
| ⑥ 存储（gzip + JSON） | ~0.1s | ~30KB/天 |
| **ETF 小计** | **~25s（首次）/ ~5s（缓存）** | |

#### K 线采集

每日采集所有个股和 ETF 的历史 K 线数据，存储于 `market_data/kline/`：

| 项目 | 说明 |
|------|------|
| 覆盖范围 | 6451 只个股 + ETF |
| 时间跨度 | 每个标的 250 个交易日 |
| 数据源 | 东方财富优先（K线API）→ Sina 降级 |
| 数据字段 | 日期、开盘、最高、最低、收盘、成交量、成交额 |
| 存储格式 | 每只股票一个 JSON 文件 |
| 存储大小 | ~2MB/天（全部 6451 只） |
| 首次采集 | ~60 秒（东方财富，带速率限制） |
| 后续增量 | 仅更新新数据，~5 秒 |

行业映射通过**东方财富 F10 公司概况接口**并发获取（50 线程 ThreadPoolExecutor），首次运行约 10 秒完成 5400 只股票的行业分类，后续运行只补充新股票。

### 5.4 前端页面

| 页面 | 路由 | 功能 |
|------|------|------|
| 📈 交易分析 | `/trading` | 输入股票代码/名称搜索，查看实时报价 + 技术指标 + LLM 分析报告 |
| 📊 大盘分析 | `/market` | 点击分析今日大盘，展示指数、统计、TOP 榜单、LLM 盘面研判 |
| 📈 ETF 分析 | `/etf` | ETF 实时报价 + 净值㉚ + 溢价率㉛ + LLM 分析报告 |
| 其他 | /chat, /agent, /knowledge-qa, ... | 通用聊天、Agent、知识库等功能 |

前端使用 Vue 3 + TypeScript + Vite，通过 `/api` 代理到 Java 后端 (localhost:8081)。分析结果通过 `localStorage` 持久化，页面刷新/切换不丢失。

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
| **交易分析 LLM** | **DeepSeek V4 Flash（统一 LLM 架构）** | **删除本地 Qwen2.5，简化架构。数据拉取 + API 推理，不存训练数据** |
| **大盘分析** | **MarketDataService 读取 batch_collect 数据 → MarketAnalysisService 调用 DS V4 Flash** | **复用全市场采集数据，LLM 自主研判** |
| **ETF 分析** | **EtfDataService 调 yfinance_data.py etf-full → EtfAnalysisService 调用 DS V4 Flash** | **独立于个股分析的专用流程，聚焦净值㉚、溢价率㉛、跟踪误差** |
| ETF 数据源 | Sina（批量行情）+ 东方财富（基金净值）+ 同花顺（ETF 清单） | 行情用 Sina 批量，净值逐只查询东财 |
| K 线数据源 | 东方财富 K 线 API（优先）→ Sina K 线（降级） | 日 K 线 250 个交易日，东方财富数据更完整 |
| K 线存储 | 逐只股票 JSON 文件存入 `market_data/kline/` | 按代码独立文件，便于 Java 按需读取 |
| 金融数据源 | Sina Finance ㉖（批量不限流）+ 东方财富 ㉗（F10 并发 50 线程） | 批量用新浪，行业映射用东财 F10 并发 |
| 数据采集 | ProcessBuilder 调 Python 脚本 | Java 编排流程、Python 执行数据获取 |
| 股票搜索 | 本地 stocks_list.json + 30 只常用美股硬编码 | A 股按代码/名称模糊匹配，美股覆盖主流 |

---

## 九、健壮性保障

| 场景 | 处理方式 |
|------|----------|
| Qdrant⑤ 不可用 | 启动时打警告延迟初始化 |
| Meilisearch⑧ 不可用 | 搜索返回空列表，不中断 |
| Redis 连接断开 | ConversationStore / ConfirmationStore 自动切换 LocalCache |
| DeepSeek API 网络错误 | RestTemplate⑰ 5s 连接超时，重试 1 次 |
| DeepSeek API 限流 (429) | 等待 2s 后重试，最多 2 次 |
| Python 子进程失败 | StockDataService 返回 `error` 字段，TradingAnalysisService 静默降级 |
| 股票数据获取失败 | 返回 `llmError`/`error` 字段，前端展示降级提示 |
| 行业映射获取失败 | 增量补充，逐次累计，非致命 |
| ETF 净值获取失败 | 返回行情数据 + `navError` 字段，LLM 分析跳过净值/溢价率维度 |
| ETF 批量采集跳过 | 支持 `--no-etf` 参数，个股采集不受影响 |
| K 线获取失败（东方财富） | 自动降级到 Sina K 线接口，数据完整度降低但不断服 |
| K 线数据不存在（新上市股票） | Java 端读取时自动跳过，TradingAnalysisService 静默降级 |
