package com.jitendra.Wallet.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ClassPathResource;

import javax.sql.DataSource;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Collectors;

@Configuration
public class DataSourceConfig {

    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }

    @Bean
    @Primary
    public DataSource dataSource() throws Exception {
        // Read from system properties set once in WalletApplication.main()
        // (no duplicate Dotenv loading — BUG-04 fix)
        String postgresUser = System.getProperty("POSTGRES_USER", "postgres");
        String postgresPass = System.getProperty("POSTGRES_PASS", "admin");

        // Use env-var-driven URLs instead of hardcoded ones (BUG-03 fix)
        String url1 = System.getProperty("POSTGRES_DB1_URL", "jdbc:postgresql://localhost:5432/shardwallet1");
        String url2 = System.getProperty("POSTGRES_DB2_URL", "jdbc:postgresql://localhost:5432/shardwallet2");

        System.out.println("Running Flyway migrations directly on physical databases...");
        String[] urls = { url1, url2 };
        for (String url : urls) {
            Flyway.configure()
                    .dataSource(url, postgresUser, postgresPass)
                    .baselineOnMigrate(true)
                    .baselineVersion("1")
                    .locations("classpath:db/migration")
                    .load()
                    .migrate();
        }

        System.out.println("Loading ShardingSphere with user: " + postgresUser);

        ClassPathResource resource = new ClassPathResource("sharding.yml");
        String yamlContent;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            yamlContent = reader.lines().collect(Collectors.joining("\n"));
        }

        yamlContent = yamlContent.replace("${POSTGRES_USER}", postgresUser);
        yamlContent = yamlContent.replace("${POSTGRES_PASS}", postgresPass);

        Path tempYaml = Files.createTempFile("sharding-processed", ".yml");
        Files.write(tempYaml, yamlContent.getBytes(StandardCharsets.UTF_8));
        tempYaml.toFile().deleteOnExit(); // DEVOPS-05 fix: clean up temp file

        return org.apache.shardingsphere.driver.api.yaml.YamlShardingSphereDataSourceFactory
                .createDataSource(tempYaml.toFile());
    }
}
