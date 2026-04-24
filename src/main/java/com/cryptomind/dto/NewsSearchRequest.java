package com.cryptomind.dto;

import lombok.Data;

/**
 * 新闻语义检索请求DTO。
 * 模块：News
 */
@Data
public class NewsSearchRequest {

    /** 搜索关键词或语义描述，如"BTC利空消息" */
    private String query;

    /** 按币种过滤，可为空；如 BTC、ETH */
    private String symbol;

    /** 返回条数，默认5 */
    private Integer limit = 5;
}
