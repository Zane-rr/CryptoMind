# News模块设计文档

## 模块职责
从CryptoPanic拉取加密货币新闻，向量化存入Elasticsearch，
为NewsAgent提供RAG检索能力，支持语义搜索和关键词搜索混合检索。

## 数据流向
CryptoPanic API（定时拉取）
↓
新闻文本向量化（阿里云Embedding）
↓
存入Elasticsearch（向量+原文）
↓
NewsAgent检索（语义+关键词混合）
↓
返回相关新闻摘要给AnalysisAgent

## 数据库设计

### Elasticsearch Index: crypto_news
| 字段 | 类型 | 说明 |
|------|------|------|
| id | keyword | CryptoPanic新闻ID |
| title | text | 新闻标题 |
| url | keyword | 原文链接 |
| source | keyword | 来源（如coindesk） |
| currencies | keyword[] | 相关币种（如[BTC, ETH]） |
| sentiment | keyword | 情绪：positive/negative/neutral |
| publishedAt | date | 发布时间 |
| titleVector | dense_vector | 标题向量（1536维） |
| createdAt | date | 入库时间 |

## Redis缓存设计
| Key | Value | 过期时间 | 说明 |
|-----|-------|---------|------|
| news:last_fetch | timestamp | 永久 | 上次拉取时间，防重复 |

## DTO设计

**NewsDTO:**
- id: String
- title: String
- url: String
- source: String
- currencies: List<String>
- sentiment: String
- publishedAt: LocalDateTime

**NewsSearchRequest:**
- query: String（搜索关键词或语义描述）
- symbol: String（可为空，按币种过滤）
- limit: Integer（默认5）

**NewsSearchResponse:**
- news: List<NewsDTO>
- totalHits: Integer

## 适配器层设计

### NewsAdapter接口（adapter/包）
```java
public interface NewsAdapter {
    // 拉取最新新闻，limit最多50条
    List<NewsDTO> fetchLatestNews(int limit) throws NewsApiException;
    
    // 按币种拉取新闻
    List<NewsDTO> fetchNewsByCurrency(String currency, int limit) throws NewsApiException;
}
```

### CryptoPanicAdapter实现
- baseUrl: https://cryptopanic.com/api/v1
- 需要API Key（免费注册获取）
- 每次拉取最新50条
- 返回字段：id, title, url, source, currencies, votes

## 异常类
**NewsApiException（exception/包）:**
- 继承RuntimeException
- 新闻API调用失败时抛出

## Service方法设计
| 方法 | 说明 |
|------|------|
| fetchAndStore() | 定时任务，每30分钟拉取新闻，向量化存ES |
| searchNews(request) | 混合检索：语义向量+关键词，返回最相关的5条 |
| vectorize(text) | 调阿里云Embedding API，返回向量 |
| buildEsIndex() | 启动时检查ES索引是否存在，不存在则创建 |

## 接口设计
News模块不对外暴露HTTP接口
仅供NewsAgent内部调用

## 定时任务
每30分钟执行fetchAndStore()：
1. 调CryptoPanic API拉取最新50条新闻
2. 过滤掉已存在的（按id去重）
3. 调阿里云Embedding向量化标题
4. 批量写入Elasticsearch

## RAG检索策略
用户问"BTC最近有什么利空消息？"时：
Step1: 向量化查询文本
Step2: ES向量相似度搜索（语义）
Step3: ES关键词搜索（BTC相关）
Step4: 两个结果合并去重，按相关度排序
Step5: 返回前5条最相关新闻

## 注意事项
- CryptoPanic免费版每天请求限制，定时30分钟拉取够用
- 向量化只对标题做，不对全文，节省token
- ES索引启动时自动创建，不需要手动建
- 新闻只保留最近7天，定时清理过期数据


## 工作流程
用户：「BTC现在能买吗？」sessionId=已有
↓
ChatController.ask()
- 创建 SseEmitter（建立SSE连接）
- 异步执行 ChatService.ask()
  ↓
  ChatService.ask()
1. getOrCreateSession(sessionId)
    - sessionId已有 → 直接返回
      ↓
2. getContextMessages(sessionId)
    - 查 Redis → 有则返回
    - 查 MySQL → 返回历史消息列表
      ↓
3. AgentOrchestrator.run(question, context)
   ↓
   RouterNode（意图识别）
- 调 DeepSeek 判断意图
- 标准分析 → 路由到 DataAgent + NewsAgent
  ↓
  并行执行：
  ┌─ DataAgent
  │   - MarketDataTool.getPrice(symbol)
  │       └── MarketService.getPrice()
  │           └── BinanceAdapter → 币安API
  │   - MarketDataTool.getIndicators(symbol)
  │       └── MarketService.getIndicators()
  │           └── Ta4j计算 MACD/RSI/布林带
  │
  └─ NewsAgent
    - NewsTool.searchNews(symbol)
      └── NewsService.searchNews()
      ├── vectorize(query) → 阿里云Embedding
      ├── vectorSearch() → ES余弦相似度
      └── keywordSearch() → ES关键词match
      ↓
      AnalysisAgent
- 综合 DataAgent + NewsAgent 结果
- 调 DeepSeek 生成结构化分析
- 输出 AnalysisCardDTO
  ├── suggestion（建议）
  ├── confidence（置信度）
  ├── technicalSummary（技术面）
  ├── newsSummary（新闻面）
  └── riskAdvice（风险提示）
  ↓
  ChatService（续）
4. saveMessage(sessionId, "user", question)
    - 存 MySQL chat_message
    - 存 Redis（key: chat:session:xxxx）
      ↓
5. saveMessage(sessionId, "assistant", response)
    - 存 MySQL chat_message
    - 更新 Redis 上下文
      ↓
6. saveAnalysisRecord(sessionId, AnalysisCardDTO)
    - 存 MySQL analysis_record
      ↓
      ChatController
- SSE流式推送分析结果给前端
- emitter.complete()