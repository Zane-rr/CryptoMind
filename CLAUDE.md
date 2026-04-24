# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

CryptoMind 是一个面向普通投资者的加密货币 AI 分析助手，基于 Spring Boot 3.4.5 + Spring AI + LangGraph4J 构建。

## Commands

```bash
# Build
./mvnw clean package -DskipTests

# Run
./mvnw spring-boot:run

# Run all tests
./mvnw test

# Run a single test class
./mvnw test -Dtest=CryptoMindApplicationTests

# Run a single test method
./mvnw test -Dtest=ClassName#methodName
```

## Architecture

### Tech Stack
- **Spring Boot 3.4.5** — web framework, JPA, Redis
- **Spring AI 1.0.0** — LLM integration via OpenAI-compatible API (DeepSeek)
- **LangGraph4J 1.5.14** — agentic workflow / graph orchestration
- **Ta4j 0.16** — technical indicator calculation (RSI, MACD, etc.)
- **Elasticsearch 8.10.4** — vector store for RAG
- **MySQL** — relational persistence (JPA, DDL auto=update)
- **Redis** — caching / alert state
- **OkHttp 4.12.0** — HTTP client for Binance & CryptoPanic APIs

### Package Structure

All code lives under `com.cryptomind`:

```
src/main/java/com/cryptomind/
├── CryptoMindApplication.java
├── adapter/
│   ├── BinanceAdapter.java          # Binance REST API 实现
│   ├── CryptoPanicAdapter.java      # CryptoPanic 新闻 API 实现
│   ├── ExchangeAdapter.java         # 交易所适配器接口
│   └── NewsAdapter.java             # 新闻适配器接口
├── config/
│   └── OkHttpConfig.java            # OkHttpClient Bean
├── controller/
│   ├── ChatController.java          # /api/chat 对话接口
│   └── MarketController.java        # /api/market 行情接口
├── dto/
│   ├── AnalysisCardDTO.java
│   ├── ChatMessageDTO.java
│   ├── ChatRequest.java
│   ├── ChatResponse.java
│   ├── ErrorResponse.java           # 全局错误响应
│   ├── HistoryResponse.java
│   ├── IndicatorResponse.java       # 技术指标结果
│   ├── NewsDTO.java                 # 新闻数据
│   ├── NewsSearchRequest.java       # 新闻检索请求
│   ├── NewsSearchResponse.java      # 新闻检索结果
│   ├── PositionData.java            # 持仓数据
│   └── PriceResponse.java           # 行情价格
├── entity/
│   ├── AnalysisRecord.java          # JPA：分析记录
│   ├── ChatMessage.java             # JPA：聊天消息
│   ├── ChatSession.java             # JPA：会话
│   ├── ExchangeBinding.java         # JPA：交易所绑定（预留）
│   └── NewsDocument.java            # ES @Document：新闻文档
├── exception/
│   ├── ExchangeApiException.java    # 交易所 API 异常
│   ├── GlobalExceptionHandler.java  # 全局异常处理器
│   └── NewsApiException.java        # 新闻 API 异常
├── repository/
│   ├── AnalysisRecordRepository.java
│   ├── ChatMessageRepository.java
│   ├── ChatSessionRepository.java
│   ├── ExchangeBindingRepository.java
│   └── NewsRepository.java          # ES Repository
├── service/
│   ├── IChatService.java
│   ├── IMarketService.java
│   ├── INewsService.java
│   └── impl/
│       ├── ChatServiceImpl.java
│       ├── MarketServiceImpl.java
│       └── NewsServiceImpl.java
├── agent/                           # LangGraph4J Agent 图（待实现）
└── tool/                            # Spring AI @Tool 工具函数（待实现）
```

| Package | Purpose |
|---|---|
| `adapter` | 外部 API 适配器（Binance、CryptoPanic），通过接口隔离，可扩展其他数据源 |
| `config` | Spring `@Configuration` Bean（OkHttp 等） |
| `controller` | REST 接口，仅 Chat 和 Market 对外暴露，News 无 Controller |
| `service` | 业务逻辑，Market/Chat/News 三个模块 |
| `repository` | MySQL JPA Repository + ES Repository |
| `entity` | JPA 实体（MySQL）和 ES `@Document`（NewsDocument） |
| `dto` | 请求/响应 DTO，命名区分 Request/Response |
| `exception` | 业务异常类 + 全局异常处理器 |
| `agent` | LangGraph4J Agent 图定义和节点实现（待实现） |
| `tool` | Spring AI `@Tool` 工具函数，供 LLM 调用（待实现） |

### AI / Agent Flow

The LLM is DeepSeek (accessed via the OpenAI-compatible API). Spring AI wires it as the `ChatClient`. LangGraph4J orchestrates multi-step agentic workflows: agent graphs are defined in the `agent` package, and each graph node can invoke `tool` functions (market data fetch, technical analysis, news retrieval) that are registered as Spring AI tools.

### Agent Roles
- **DataAgent** — fetches Binance price data, computes Ta4j indicators
- **NewsAgent** — retrieves news from ES (RAG), judges sentiment
- **AnalysisAgent** — synthesizes DataAgent + NewsAgent output into plain-language advice
- **RiskAgent** — checks simulated portfolio, adds position sizing advice
- **AlertAgent** — runs on scheduler, pushes DingTalk notifications on triggers

### External APIs

- **Binance REST API** — OHLCV price data, order book
- **CryptoPanic API** — crypto news feed for sentiment
- **DeepSeek API** — LLM chat completions (configured as OpenAI-compatible)
- **Embedding API** — separate endpoint/key configured via `EMBEDDING_API_*` env vars

### Required Environment Variables

| Variable | Default | Description |
|---|---|---|
| `DEEPSEEK_API_KEY` | — | DeepSeek API key (required) |
| `DEEPSEEK_API_MODEL` | `deepseek-chat` | Model name |
| `EMBEDDING_API_KEY` | — | Embedding service API key |
| `EMBEDDING_API_URL` | — | Embedding service base URL |
| `EMBEDDING_API_MODEL` | — | Embedding model name |
| `MYSQL_HOST/PORT` | `localhost:3306` | MySQL connection |
| `MYSQL_USERNAME/PASSWORD` | `root/123456` | MySQL credentials |
| `REDIS_HOST/PORT` | `localhost:6379` | Redis connection |
| `REDIS_PASSWORD` | `PaiSmart2025` | Redis password |
| `ES_HOST/PORT` | `localhost:9200` | Elasticsearch connection |
| `ES_USERNAME/PASSWORD` | `elastic/PaiSmart2025` | ES credentials |
| `SUPPORTED_SYMBOLS` | `BTCUSDT,ETHUSDT` | Monitored trading pairs |
| `ALERT_INTERVAL` | `300000` (5 min) | Price alert check interval (ms) |
| `DINGTALK_WEBHOOK_URL` | — | 钉钉机器人Webhook地址 |
| `CRYPTOPANIC_API_KEY` | — | CryptoPanic新闻API Key |

## Code Style & Conventions

### 注释规范
- 所有类必须有类级别注释，说明职责和所属模块
- 所有public方法必须有Javadoc注释，包含@param、@return、@throws
- 复杂业务逻辑必须有行内注释，说明"为什么"而不是"是什么"
- Agent节点方法必须注释清楚输入状态和输出状态
- TODO注释格式：// TODO: [描述] - 用于标记待实现功能

### 命名规范
- 类名：大驼峰，语义清晰，如BinanceAdapter、DataAgentNode
- 方法名：小驼峰，动词开头，如fetchMarketData、calculateRsi
- 常量：全大写下划线，如MAX_RETRY_COUNT
- Agent节点方法统一命名为execute()

### 代码规范
- 禁止魔法数字，全部提取为常量或配置项
- 所有外部API调用必须有try-catch和日志
- 日志使用@Slf4j，禁止System.out.println
- 敏感信息（API Key）禁止打印到日志

### 包结构规范
- Agent节点类放在agent包，命名以Node结尾，如DataAgentNode
- MCP工具类放在tool包，命名以Tool结尾，如MarketDataTool
- 配置类放在config包，命名以Config结尾
- DTO类区分请求和响应，如ChatRequest、ChatResponse

### 模块完成检查清单
每个模块代码生成完毕后，必须自检以下几点：
1. 方法执行顺序是否与设计文档一致
2. 有没有逻辑遗漏（如先存消息再取上下文这类顺序问题）
3. 所有TODO标记是否已注明原因
4. 异常处理是否完善，外部调用是否有try-catch
5. Redis和MySQL的数据一致性是否保证
6. 接口返回值是否与DTO设计一致

自检完成后输出：「模块自检完成，发现X个问题，已修复」

## Docs
See docs/ for API, architecture, and design details.
