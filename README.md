# CryptoMind

面向普通投资者的加密货币 AI 分析助手。基于 Spring Boot 3.4.5 + Spring AI + LangGraph4J 构建，集成实时行情、多源新闻、技术指标分析与智能对话，提供桌面客户端（Electron）和浏览器两种访问方式。

---

## 功能特性

- **智能对话**：接入 DeepSeek LLM，自动识别用户意图，分 QUICK / DEEP 两条链路处理
  - QUICK：轻量问答，需要新闻时自动预取并注入 Prompt
  - DEEP：LangGraph4J 多轮 Agent 编排，工具调用（行情 + 技术指标 + 新闻）
- **实时行情**：对接 Binance REST API，获取价格、24h 涨跌幅、OHLCV
- **技术指标**：基于 Ta4j 计算 RSI、MACD、布林带（1h K 线）
- **多源新闻 RAG**：
  - RSS 聚合（CoinDesk / CoinTelegraph / Decrypt）每 10 分钟自动拉取
  - Tavily 实时搜索兜底补充
  - 新闻向量化后存入 Elasticsearch，支持语义检索 + 关键词降级
- **价格预警**：自定义阈值规则，触发后通过钉钉 Webhook 推送通知
- **会话历史**：MySQL 持久化多轮对话，支持上下文管理
- **桌面客户端**：Electron 包装，支持普通窗口和悬浮侧边栏（Always-on-top）两种模式

---

## 技术栈

| 层次 | 技术 |
|---|---|
| 框架 | Spring Boot 3.4.5 |
| AI / Agent | Spring AI 1.0.0 + LangGraph4J 1.5.14 |
| LLM | DeepSeek（OpenAI 兼容接口） |
| 向量存储 | Elasticsearch 8.x（Spring Data ES） |
| 关系数据库 | MySQL 8（JPA / Hibernate） |
| 缓存 | Redis |
| 技术指标 | Ta4j 0.16 |
| HTTP 客户端 | OkHttp 4.12 |
| 桌面客户端 | Electron 31 |
| 外部数据源 | Binance REST、Tavily Search、RSS（CoinDesk / CoinTelegraph / Decrypt） |

---

## 项目结构

```
CryptoMind/
├── src/main/java/com/cryptomind/
│   ├── adapter/                  # 外部 API 适配器
│   │   ├── BinanceAdapter.java   # Binance 行情
│   │   ├── RssAdapter.java       # RSS 多源新闻（@Primary）
│   │   ├── TavilyAdapter.java    # Tavily 实时搜索
│   │   └── FinnhubAdapter.java   # Finnhub（保留，非主路）
│   ├── agent/                    # AI Agent 层
│   │   ├── CryptoAgentService.java  # 意图识别 + QUICK/DEEP 分流
│   │   ├── MarketTools.java         # Spring AI @Tool：行情/指标/新闻/持仓
│   │   ├── DebateGraph.java         # LangGraph4J 图定义
│   │   └── DebateState.java         # Agent 状态
│   ├── config/
│   │   ├── ChatClientConfig.java    # Spring AI ChatClient 配置
│   │   └── OkHttpConfig.java
│   ├── controller/
│   │   ├── ChatController.java      # /api/chat
│   │   ├── MarketController.java    # /api/market
│   │   ├── NewsController.java      # /api/news
│   │   └── AlertController.java     # /api/alert
│   ├── service/impl/
│   │   ├── ChatServiceImpl.java
│   │   ├── MarketServiceImpl.java
│   │   ├── NewsServiceImpl.java     # 新闻调度、ES 存储、向量检索
│   │   └── AlertServiceImpl.java
│   ├── entity/                   # JPA + ES 实体
│   ├── dto/                      # 请求 / 响应 DTO
│   └── exception/                # 全局异常处理
├── src/main/resources/
│   ├── application.properties
│   └── static/index.html         # 单页前端（SSE 流式对话）
├── electron/                     # 桌面客户端
│   ├── main.js
│   └── package.json
└── pom.xml
```

---

## 快速开始

### 前置依赖

- Java 17+
- Maven 3.8+
- MySQL 8
- Redis
- Elasticsearch 8.x（需开启向量字段支持）
- Node.js 18+（Electron 客户端，可选）

### 1. 配置环境变量

```bash
# LLM
export DEEPSEEK_API_KEY=sk-xxx
export DEEPSEEK_API_MODEL=deepseek-chat

# Embedding（独立服务或兼容 OpenAI 接口）
export EMBEDDING_API_KEY=xxx
export EMBEDDING_API_URL=https://your-embedding-api
export EMBEDDING_API_MODEL=text-embedding-3-small

# 数据库
export MYSQL_HOST=localhost
export MYSQL_PORT=3306
export MYSQL_USERNAME=root
export MYSQL_PASSWORD=your_password

# Redis
export REDIS_HOST=localhost
export REDIS_PORT=6379
export REDIS_PASSWORD=your_password

# Elasticsearch
export ES_HOST=localhost
export ES_PORT=9200
export ES_USERNAME=elastic
export ES_PASSWORD=your_password

# Binance（行情无需 Key，持仓需要）
export BINANCE_API_KEY=
export BINANCE_SECRET_KEY=

# 新闻
export TAVILY_API_KEY=tvly-xxx
export FINNHUB_API_KEY=xxx   # 可选

# 预警
export DINGTALK_WEBHOOK_URL=https://oapi.dingtalk.com/robot/send?access_token=xxx

# 监控标的 & 预警间隔
export SUPPORTED_SYMBOLS=BTCUSDT,ETHUSDT
export ALERT_INTERVAL=300000
```

### 2. 创建数据库

```sql
CREATE DATABASE cryptomind CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

> JPA `ddl-auto=update` 会自动建表，无需手动执行 DDL。

### 3. 启动后端

```bash
./mvnw spring-boot:run
```

浏览器访问 `http://localhost:8080` 即可使用 Web 界面。

### 4. 启动 Electron 桌面客户端（可选）

```bash
cd electron
npm install

# 普通窗口模式
npm start

# 悬浮侧边栏模式（右侧 Always-on-top）
npm run start:float
```

> Electron 会自动连接已启动的 Spring Boot 服务（localhost:8080）。

---

## 主要 API

| 接口 | 方法 | 说明 |
|---|---|---|
| `/api/chat/stream` | POST | SSE 流式对话（核心入口） |
| `/api/chat/history/{sessionId}` | GET | 获取会话历史 |
| `/api/market/price/{symbol}` | GET | 实时价格 |
| `/api/market/indicators/{symbol}` | GET | 技术指标 |
| `/api/news/latest` | GET | 最新新闻列表 |
| `/api/news/search` | POST | 新闻语义检索 |
| `/api/alert/rules` | GET/POST | 查询 / 创建预警规则 |

---

## 新闻数据流

```
启动时立即拉取
RSS (CoinDesk / CoinTelegraph / Decrypt)
        +
Tavily Search
        |
        v
  向量化（Embedding API）
        |
        v
  Elasticsearch（存储，无向量时也保存）
        |
   每 10 分钟定时重复

用户查询时：
  ES 向量检索（降级→关键词检索）
        +
  Tavily 实时补充
        |
        v
  注入 LLM Prompt
```

---

## Agent 工作流

```
用户输入
   |
   v
意图识别（LLM JSON）
   |
   +-- needsMarket=true / needsNews=true
   |
   +-- QUICK 路径（轻量）
   |      预取新闻数据 → 注入 Prompt → 流式回复
   |
   +-- DEEP 路径（多步推理）
          LangGraph4J 图
          MarketTools (@Tool)
          ├── getPrice()
          ├── getIndicators()
          ├── searchNews()
          └── getPortfolio()
          → 综合分析回复
```

---

## 开发命令

```bash
# 构建
./mvnw clean package -DskipTests

# 运行所有测试
./mvnw test

# 运行单个测试类
./mvnw test -Dtest=ClassName

# 运行单个测试方法
./mvnw test -Dtest=ClassName#methodName
```

---

## License

MIT
