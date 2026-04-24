package com.cryptomind.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

/**
 * 新闻检索结果DTO。
 * 模块：News
 */
@Data
@AllArgsConstructor
public class NewsSearchResponse {

    /** 检索命中的新闻列表 */
    private List<NewsDTO> news;

    /** 合并去重后的总命中数 */
    private Integer totalHits;
}
