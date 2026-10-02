package com.tarun.seat_reserve_service_project;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class SeatReserveServiceProjectApplication {

	public static void main(String[] args) {
		SpringApplication.run(SeatReserveServiceProjectApplication.class, args);
	}

}
