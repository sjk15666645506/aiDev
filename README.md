# AIDev

AI 学习项目。Java 后端 REST API + Agent 引擎 + Vue 3 前端。

## 功能

- **💬 聊天** — 直接对话 DeepSeek V4 Flash
- **📚 知识库 RAG** — 文档摄入 → 向量+全文检索 → LLM 问答
- **🤖 Agent** — ReAct 循环 + Tool Calling + 双重确认
- **🔍 股票搜索** — 输入代码或名称，模糊匹配 A 股 5400+ 只 + 常用美股

## 快速开始

### 1. 后端

```bash
export DEEPSEEK_API_KEY=sk-xxxxx

# 启动依赖服务
brew services start redis
brew services start meilisearch
docker run -d --name qdrant -p 16333:6333 qdrant/qdrant
ollama pull nomic-embed-text

# 启动 Spring Boot
mvn spring-boot:run
```

### 2. 前端

```bash
cd aiDev-vue
npm install
npm run dev
# 访问 http://localhost:5173
```

## 项目结构

```
├── src/main/java/com/deepseek/demo/    ← Java 后端主代码
├── ingestion-pipeline/                  ← 文档摄入工具（ingest.py）
├── aiDev-vue/                           ← Vue 3 前端
└── pom.xml
```

## 依赖服务

| 服务 | 用途 | 默认地址 |
|------|------|---------|
| DeepSeek API | LLM 问答 | https://api.deepseek.com |
| Qdrant | 向量数据库 | localhost:16333 |
| Ollama | 本地 embedding 模型 | localhost:11434 |
| Meilisearch | 全文检索引擎 | localhost:7700 |
| Redis | 会话/确认点存储 | localhost:6379 |

## API Key 配置

`application.yml` 中 `deepseek.api-key` 使用 `${DEEPSEEK_API_KEY}` 占位符，通过环境变量传入：

```bash
export DEEPSEEK_API_KEY=sk-xxxxx
```
