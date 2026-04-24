package com.cryptomind.repository;

import com.cryptomind.entity.NewsDocument;
import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;

/**
 * News模块 Elasticsearch Repository。
 * saveAll() 以文档 @Id（CryptoPanic news id）为键做 upsert，天然去重。
 * 模块：News
 */
public interface NewsRepository extends ElasticsearchRepository<NewsDocument, String> {
}
