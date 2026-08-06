package com.example.ainewsdigest.collect;

import java.time.Instant;

/**
 * 후보 기사 수집 아웃바운드 포트. 서비스 계층은 구현체({@code HackerNewsClient} 등)를 직접 참조하지 않는다.
 * 테스트는 인메모리 페이크로 대체할 수 있어야 한다.
 */
public interface NewsSource {

	/** 로그·장애 보고에 쓰는 소스 이름. */
	String name();

	/**
	 * {@code since} 이후 게시된 후보를 반환한다.
	 *
	 * <p><b>예외를 던지지 않는다.</b> 소스 하나가 죽어도 나머지로 다이제스트가 성립하므로,
	 * 실패는 {@link FetchResult#failed()}로 표현한다 (ADR-016).
	 */
	FetchResult fetch(Instant since);
}
