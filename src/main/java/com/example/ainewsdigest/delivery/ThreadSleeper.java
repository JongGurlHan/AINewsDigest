package com.example.ainewsdigest.delivery;

import org.springframework.stereotype.Component;

import java.time.Duration;

/** 운영용 {@link Sleeper}. */
@Component
public class ThreadSleeper implements Sleeper {

	@Override
	public void sleep(Duration duration) {
		try {
			Thread.sleep(duration.toMillis());
		}
		catch (InterruptedException ex) {
			// 인터럽트를 삼키지 않는다. 폴링 루프가 이 플래그를 보고 종료한다.
			Thread.currentThread().interrupt();
		}
	}
}
