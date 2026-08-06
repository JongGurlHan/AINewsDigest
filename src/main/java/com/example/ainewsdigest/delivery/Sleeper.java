package com.example.ainewsdigest.delivery;

import java.time.Duration;

/**
 * 대기를 주입 가능한 지점으로 뺀 것. 백오프 로직을 테스트하려면 실제로 기다리는 수밖에 없는 구조를
 * 만들지 않기 위해서다 — 테스트는 페이크로 <b>받은 값만</b> 기록해 검증한다.
 *
 * <p>구현은 인터럽트를 삼키되 <b>플래그는 반드시 복원한다.</b> 폴링 루프의 종료 조건이 그 플래그이므로,
 * 여기서 지워버리면 애플리케이션 종료 시 루프가 깨어나지 못한다.
 */
public interface Sleeper {

	void sleep(Duration duration);
}
