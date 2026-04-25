Alert模块开发总结
模块定位
独立的后台定时预警模块，不影响现有任何代码，新增文件即可。
核心功能
每5分钟定时检查用户关注的币种，触发预警规则后推送钉钉通知（可选加LLM生成预警文案）。
技术栈
复用现有：IMarketService（拉价格+指标）、ChatClient（可选LLM文案）
预警规则

价格24h涨跌幅 > 5% 或 < -5%
RSI < 30（超卖）
RSI > 70（超买）

需要新增的文件

entity/AlertRule.java — 预警规则实体（MySQL）
entity/AlertHistory.java — 推送历史实体（MySQL）
repository/AlertRuleRepository.java
repository/AlertHistoryRepository.java
service/IAlertService.java
service/impl/AlertServiceImpl.java — 核心定时任务
controller/AlertController.java — 配置接口

接口

POST /api/alert/config — 新增预警规则（symbol + 规则类型）
GET /api/alert/config — 查询当前规则
DELETE /api/alert/config/{id} — 删除规则
GET /api/alert/history — 查询推送历史

配置项（application-local.properties已有）
propertiescrypto.alert.check-interval=300000
crypto.supported.symbols=BTCUSDT,ETHUSDT
钉钉推送
Webhook方式，Markdown卡片格式，需要新增环境变量：
propertiesdingtalk.webhook.url=https://oapi.dingtalk.com/robot/send?access_token=xxx
可选升级
触发预警后加一次LLM调用，生成一句话预警分析文案随通知一起推送。