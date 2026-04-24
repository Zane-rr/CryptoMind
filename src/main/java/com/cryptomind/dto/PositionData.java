package com.cryptomind.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 持仓数据DTO，字段基于币安 account 接口的 balances 数组。
 * currentPrice 和 totalValue 由 Service 层调用 getPrice() 补充填充。
 * 模块：Market / Exchange
 */
@Data
public class PositionData {

    /** 资产名称，如 BTC、ETH */
    private String asset;

    /** 可用数量 */
    private BigDecimal free;

    /** 挂单锁定数量 */
    private BigDecimal locked;

    /** 实时单价（USDT计价），由Service层补充 */
    private BigDecimal currentPrice;

    /** 持仓总价值（= free * currentPrice），由Service层补充 */
    private BigDecimal totalValue;

    /** 账户类型，固定为现货账户 */
    private String accountType = "SPOT";

    /** 说明：理财/质押账户余额需单独查询 */
    private String note = "仅显示现货账户余额，理财/质押账户余额需单独查询";
}
