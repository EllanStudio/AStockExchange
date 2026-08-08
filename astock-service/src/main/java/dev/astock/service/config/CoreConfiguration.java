package dev.astock.service.config;

import dev.astock.domain.quote.QuoteQualityGate;
import dev.astock.domain.quote.QuoteQualityPolicy;
import dev.astock.domain.rule.TradingSessionPolicy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.Clock;

@Configuration
public class CoreConfiguration {
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    QuoteQualityGate quoteQualityGate(AStockProperties properties, Clock clock) {
        var data = properties.data();
        return new QuoteQualityGate(new QuoteQualityPolicy(
                data.displayStaleAfter(), data.executionStaleAfter(),
                data.sourceDivergenceBps(), 10, 20
        ), clock);
    }

    @Bean
    TradingSessionPolicy tradingSessionPolicy() {
        return new TradingSessionPolicy();
    }

    @Bean
    PlatformTransactionManager transactionManager(DataSource dataSource) {
        var manager = new JdbcTransactionManager(dataSource);
        manager.setDefaultTimeout(10);
        return manager;
    }

    @Bean
    TransactionTemplate transactionTemplate(PlatformTransactionManager manager) {
        var template = new TransactionTemplate(manager);
        template.setIsolationLevelName("ISOLATION_READ_COMMITTED");
        template.setTimeout(10);
        return template;
    }
}
