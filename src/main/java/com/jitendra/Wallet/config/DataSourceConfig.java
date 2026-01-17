package com.jitendra.Wallet.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.cdimascio.dotenv.Dotenv;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
    public DataSource dataSource() throws Exception {
        Dotenv dotenv = Dotenv.configure().ignoreIfMissing().load();
        
        String postgresUser = dotenv.get("POSTGRES_USER", "postgres");
        String postgresPass = dotenv.get("POSTGRES_PASS", "admin");
        
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
