# aiDev

AI 学习项目 —— 基于 DeepSeek + RAG 的知识库问答系统。

> 本项目基于 macOS 开发与运行。

## 项目结构

```
├── src/main/java/com/deepseek/demo/    ← Java 后端主代码
├── ingestion-pipeline/                  ← Python 文档向量化脚本
│   ├── ingest.py                        （将文档导入 Qdrant）
│   └── requirements.txt
├── scripts/                             ← 辅助脚本
│   ├── pywc.py
│   └── test_pywc.py
└── pom.xml
```

## 注意事项

### 1. API Key 配置

`src/main/resources/application.yml` 中 `deepseek.api-key` 使用 `${DEEPSEEK_API_KEY}` 占位符，**不要直接写入真实 key**。

通过环境变量或 IDE Run Configuration 传入：

```bash
export DEEPSEEK_API_KEY=sk-xxxxx
```

### 2. 依赖的服务

| 服务 | 用途 | 默认地址 |
|------|------|---------|
| DeepSeek API | LLM 问答 | https://api.deepseek.com |
| Qdrant | 向量数据库 | localhost:16333 |
| Ollama | 本地 embedding 模型 | localhost:11434 |
| Meilisearch | 全文检索引擎 | localhost:7700 |

启动项目前确保以上服务可用。

### 3. 文档向量化（ingestion-pipeline）

Python 脚本，需要先安装依赖：

```bash
cd ingestion-pipeline
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
```

### 4. Python 脚本

`scripts/` 和 `ingestion-pipeline/` 下的 `.venv/`、`__pycache__/` 已加入 `.gitignore`，不会提交到仓库。
