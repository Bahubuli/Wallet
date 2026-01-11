package com.jitendra.Wallet;

import io.github.cdimascio.dotenv.Dotenv;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class WalletApplication {

	public static void main(String[] args) {
		// Load .env file - ignoreIfMissing prevents crash if .env doesn't exist
		Dotenv dotenv = Dotenv.configure()
				.ignoreIfMissing()
				.load();

		// Set system properties from .env (or use defaults)
		String postgresUser = dotenv.get("POSTGRES_USER", "postgres");
		String postgresPass = dotenv.get("POSTGRES_PASS", "admin");
		String postgresDb1 = dotenv.get("POSTGRES_DB1_URL", "jdbc:postgresql://localhost:5432/wallet1");
		String postgresDb2 = dotenv.get("POSTGRES_DB2_URL", "jdbc:postgresql://localhost:5432/wallet2");

		System.setProperty("POSTGRES_USER", postgresUser);
		System.setProperty("POSTGRES_PASS", postgresPass);
		System.setProperty("POSTGRES_DB1_URL", postgresDb1);
		System.setProperty("POSTGRES_DB2_URL", postgresDb2);

		System.out.println("Using POSTGRES_USER: " + postgresUser);

		SpringApplication.run(WalletApplication.class, args);
	}

}
