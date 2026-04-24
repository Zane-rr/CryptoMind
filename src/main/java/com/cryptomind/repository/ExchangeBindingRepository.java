package com.cryptomind.repository;

import com.cryptomind.entity.ExchangeBinding;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * ExchangeBinding 数据访问层。
 * 模块：Market / Exchange
 */
public interface ExchangeBindingRepository extends JpaRepository<ExchangeBinding, Long> {

    /**
     * 根据交易所名称查询绑定记录。
     *
     * @param platform 交易所名称，如 binance
     * @return 绑定记录，不存在则为空
     */
    Optional<ExchangeBinding> findByPlatform(String platform);
}
