package com.paytm.seats;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class SeatReservationApplication {

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(SeatReservationApplication.class);
        app.addListeners(new DatabaseUrlListener());
        app.run(args);
    }
}
