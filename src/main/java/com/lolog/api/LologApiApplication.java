package com.lolog.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

// LOLog 메인 API 서버. 스케줄 작업도 켠다.
@SpringBootApplication
@EnableScheduling
public class LologApiApplication {

	/**
	 * 스프링 애플리케이션을 시작한다.
	 *
	 * @param args 실행할 때 받은 명령줄 인자
	 */
	public static void main(String[] args) {
		SpringApplication.run(LologApiApplication.class, args);
	}

}
