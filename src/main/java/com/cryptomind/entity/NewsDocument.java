package com.cryptomind.entity;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.DateFormat;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Elasticsearch 新闻文档实体，对应索引 crypto_news。
 * createIndex=false：索引由 NewsServiceImpl.buildEsIndex() 在启动时按需创建，
 * 确保 dense_vector 字段的 dims/similarity 参数可控。
 * 模块：News
 */
@Data
@Document(indexName = "crypto_news", createIndex = false)
public class NewsDocument {

    /** 新闻 url 的 MD5 摘要，作为 ES 文档ID，保证 saveAll() 幂等 upsert */
    @Id
    private String id;

    /** 新闻标题，支持全文检索 */
    @Field(type = FieldType.Text)
    private String title;

    /** 新闻摘要（Finnhub: summary） */
    @Field(type = FieldType.Text)
    private String summary;

    /** 原文链接 */
    @Field(type = FieldType.Keyword)
    private String url;

    /** 来源名称 */
    @Field(type = FieldType.Keyword)
    private String source;

    /** 相关币种代码列表，如 [BTC, ETH] */
    @Field(type = FieldType.Keyword)
    private List<String> currencies;

    /** 情绪标签：positive / negative / neutral */
    @Field(type = FieldType.Keyword)
    private String sentiment;

    /** 新闻发布时间 */
    @Field(type = FieldType.Date, format = DateFormat.date_hour_minute_second)
    private LocalDateTime publishedAt;

    /**
     * 标题向量，1536维，使用余弦相似度索引。
     * 索引映射由 buildEsIndex() 手动提交，此处不使用注解声明 dense_vector，
     * 避免 Spring Data ES 对 dims 参数支持不一致导致的映射错误。
     */
    private List<Float> titleVector;

    /** 入库时间 */
    @Field(type = FieldType.Date, format = DateFormat.date_hour_minute_second)
    private LocalDateTime createdAt;
}
