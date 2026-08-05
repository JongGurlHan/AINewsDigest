package com.example.ainewsdigest;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * {@code @ConfigurationPropertiesScan}으로 {@code ainewsdigest.*} 프로퍼티 클래스를 한곳에서 등록한다.
 * 각 프로퍼티 클래스에는 {@code @ConfigurationProperties}만 붙이고
 * {@code @Component}나 {@code @EnableConfigurationProperties}를 쓰지 않는다 — 등록 방식이 도메인마다
 * 갈리면 어디를 봐야 바인딩되는지 알 수 없게 된다.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class AinewsdigestApplication {

	public static void main(String[] args) {
		SpringApplication.run(AinewsdigestApplication.class, args);
	}

}
