package com.procurepal_services.stock_bridge_api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class StockBridgeApiApplication {

	public static void main(String[] args) {
		SpringApplication.run(StockBridgeApiApplication.class, args);
	}

}
