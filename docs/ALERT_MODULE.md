# Alert 模块开发文档

## 模块定位

独立后台定时预警模块，不影响现有任何代码，新增文件即可接入。
每 5 分钟自动检查监控币种，触发规则后通过钉钉机器人推送 Markdown 卡片通知（含 LLM 生成的一句话研判文案）。

---

## 监控来源（两条路径）

| 路径 | 来源 | 配置方式 |
|---|---|---|
| 持仓路径 | `IMarketService.getPortfolio()` 返回的所有非稳定币持仓 | 自动，随持仓变化 |
| 自选路径 | `AlertRule` 表中用户手动添加的 symbol | 通过 API 管理 |

两条路径的 symbol 合并去重后统一进行规则检查。

---

## 预警规则

每个 symbol 每次检查都跑全部四条规则：

| 规则类型 | 触发条件 | Redis 冷却时间 |
|---|---|---|
| `PRICE_UP` | 24h 涨幅 > +5% | 30 分钟 |
| `PRICE_DOWN` | 24h 跌幅 < -5% | 30 分钟 |
| `RSI_OVERBOUGHT` | RSI(1h) > 70 | 2 小时 |
| `RSI_OVERSOLD` | RSI(1h) < 30 | 2 小时 |

冷却 key 格式：`alert:cooldown:{symbol}:{ruleType}`，TTL 到期自动清除。
Redis 重启兜底：触发前同时检查 `AlertHistory` 表中最近一条同 symbol+ruleType 记录的时间。

---

## 调度流程

```
@Scheduled(fixedRate = 300000)
checkAlerts()
  ├── 持仓路径：getPortfolio() → 过滤稳定币 → symbol 列表
  ├── 自选路径：AlertRuleRepository.findAll() → symbol 列表
  └── 合并去重 → for each symbol:
        ├── getPrice(symbol)
        ├── getIndicators(symbol, "1h")
        ├── 检查四条规则
        └── 触发时：
              check Redis cooldown key
              → 未冷却：generateLLMCopy(symbol, ruleType, price, rsi)
              → pushDingTalk(markdownContent)
              → save AlertHistory
              → set Redis cooldown TTL
```

---

## 钉钉通知格式（Markdown）

```
⚠️ BTC RSI超买提醒
触发：RSI 72.3（阈值 > 70）
价格：96,500 USDT  |  24h涨幅：+8.6%
时间：04/26 14:35

📊 AI研判：BTC短期动能强劲，RSI已进入超买区间，
历史上此位置多有回调。建议持仓者适当减仓锁利，
止盈参考 98,000，跌破 94,000 注意止损。
```

LLM 调用：非流式，使用现有 `ChatClient`，只生成 2-3 句话研判文案。

---

## 新增文件

```
src/main/java/com/cryptomind/
├── entity/
│   ├── AlertRule.java          # 自选监控规则（symbol, createdAt）
│   └── AlertHistory.java       # 推送历史（symbol, ruleType, price, rsiValue, llmCopy, triggeredAt）
├── repository/
│   ├── AlertRuleRepository.java
│   └── AlertHistoryRepository.java
├── service/
│   ├── IAlertService.java
│   └── impl/AlertServiceImpl.java   # 定时任务核心
└── controller/
    └── AlertController.java         # Watchlist CRUD
```

---

## API 接口

### 自选监控管理

| 方法 | 路径 | 说明 |
|---|---|---|
| `POST` | `/api/alert/watchlist` | 添加自选监控，body: `{"symbol":"SOLUSDT"}` |
| `GET` | `/api/alert/watchlist` | 查询所有自选规则 |
| `DELETE` | `/api/alert/watchlist/{id}` | 删除指定规则 |

---

## 前端

在 `index.html` 左侧 Portfolio 栏下方新增 **WATCHLIST** 小区块：

- 输入框 + 添加按钮，调用 `POST /api/alert/watchlist`
- 已添加列表，每条带删除按钮，调用 `DELETE /api/alert/watchlist/{id}`
- 不展示历史推送记录（历史通过钉钉查看）

---

## 环境变量

| 变量 | 说明 |
|---|---|
| `DINGTALK_WEBHOOK_URL` | 钉钉机器人 Webhook 地址 |
| `ALERT_INTERVAL` | 检查间隔毫秒数，默认 `300000`（5分钟）|
| `SUPPORTED_SYMBOLS` | 系统支持的币种（持仓路径自动读取，不影响自选路径）|

---

## 稳定币过滤列表

持仓路径跳过以下 symbol：`USDT, BUSD, USDC, DAI, TUSD, FDUSD`
