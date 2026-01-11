package com.jitendra.Wallet;

import io.github.cdimascio.dotenv.Dotenv;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class WalletApplication {

	public static void main(String[] args) {
		// Load .env file
		Dotenv dotenv = Dotenv.configure().load();

		// Set environment variables
		System.setProperty("POSTGRES_DB1_URL", dotenv.get("POSTGRES_DB1_URL"));
		System.setProperty("POSTGRES_DB2_URL", dotenv.get("POSTGRES_DB2_URL"));
		System.setProperty("POSTGRES_USER", dotenv.get("POSTGRES_USER"));
		System.setProperty("POSTGRES_PASS", dotenv.get("POSTGRES_PASS"));

		SpringApplication.run(WalletApplication.class, args);
	}

}
