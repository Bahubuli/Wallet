package com.jitendra.Wallet.config;

import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;

@Configuration
public class FlywayConfig {

    @Bean(name = "flywayDataSource")
    @ConfigurationProperties(prefix = "spring.flyway")
    public DataSource flywayDataSource() {
        return DataSourceBuilder.create().build();
    }

    @Bean(initMethod = "migrate")
    public Flyway flyway(@Qualifier("flywayDataSource") DataSource flywayDataSource) {
        return Flyway.configure()
                .dataSource(flywayDataSource)
                .baselineOnMigrate(true)
                .baselineVersion("1")
                .locations("classpath:db/migration")
                .load();
    }
}
