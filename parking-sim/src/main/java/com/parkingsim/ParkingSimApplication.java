package com.parkingsim;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** 포항 도심 공영주차장 탐색·혼잡 시뮬레이터 MCP 서버. */
@SpringBootApplication
public class ParkingSimApplication {

    public static void main(String[] args) {
        SpringApplication.run(ParkingSimApplication.class, args);
    }
}
