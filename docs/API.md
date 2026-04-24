# API Endpoints

## Chat
- POST /api/chat/ask — SSE流式返回，入参含sessionId（空则创建新会话并返回新sessionId）
- GET /api/chat/{sessionId}/history — 获取会话历史
- DELETE /api/chat/{sessionId}/history — 清空会话历史

## Market
- GET /api/market/price/{symbol} — 查询当前价格
- GET /api/market/indicators/{symbol} — 查询技术指标

## Exchange
- POST /api/exchange/{platform}/bind — 绑定交易所API Key（AES加密），platform当前支持binance
- GET /api/exchange/{platform}/portfolio — 获取持仓和自选
- DELETE /api/exchange/{platform}/bind — 解绑

## Alert
- POST /api/alert/config — 配置预警规则
- GET /api/alert/config — 获取当前预警配置
- GET /api/alert/history — 获取历史预警记录