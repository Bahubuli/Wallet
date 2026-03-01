package com.jitendra.Wallet.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.cdimascio.dotenv.Dotenv;
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
        Dotenv dotenv = Dotenv.configure().ignoreIfMissing().load();

        String postgresUser = dotenv.get("POSTGRES_USER", "postgres");
        String postgresPass = dotenv.get("POSTGRES_PASS", "admin");

        System.out.println("Running Flyway migrations directly on physical databases...");
        String[] urls = { "jdbc:postgresql://localhost:5432/shardwallet1",
                "jdbc:postgresql://localhost:5432/shardwallet2" };
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

        return org.apache.shardingsphere.driver.api.yaml.YamlShardingSphereDataSourceFactory
                .createDataSource(tempYaml.toFile());
    }
}
