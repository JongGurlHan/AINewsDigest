package com.example.ainewsdigest.global;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;

/**
 * {@code @Scheduled}를 켜고, 시각 의존 로직이 주입받을 {@link Clock}을 등록한다.
 *
 * <p>{@code @EnableScheduling}은 {@link DigestScheduler}와 달리 조건부가 아니다. 스케줄러 빈이 없으면
 * 등록될 작업도 없어 무해하고, 이 설정까지 함께 꺼버리면 테스트가 {@link Clock}을 잃는다.
 *
 * <p><b>{@code Clock}을 빈으로 두는 이유.</b> {@code Instant.now()}·{@code LocalDate.now()}를 코드에서
 * 직접 부르면 "07:00 KST에 어느 날짜를 보는가"를 테스트할 방법이 없어진다. 이 프로젝트에서 그 값이
 * 틀리면 생성과 발송이 서로 다른 날짜를 보고 매일 조용히 어긋난다.
 *
 * <p>시스템 클럭에 서울 존을 심어 둔다. {@link DigestScheduler}가 어차피 {@code withZone}으로 한 번 더
 * 변환하지만, 다른 곳에서 {@code LocalDate.now(clock)}을 그냥 부르더라도 서버 기본 타임존(UTC)이
 * 새어 들어오지 않게 하는 방어선이다.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class SchedulingConfig {

	@Bean
	Clock clock() {
		return Clock.system(DigestScheduler.SEOUL);
	}
}
