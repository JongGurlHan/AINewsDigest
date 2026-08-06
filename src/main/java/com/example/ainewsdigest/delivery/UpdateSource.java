package com.example.ainewsdigest.delivery;

/**
 * 텔레그램 업데이트 수신 아웃바운드 포트. 웹훅이 아니라 롱폴링이다 (ADR-008) — 서버에서 나가는
 * 연결이라 도메인·인증서·인바운드 방화벽이 전부 불필요하고 로컬이 운영과 동일하게 동작한다.
 */
public interface UpdateSource {

	/**
	 * 롱폴링. offset 이상의 업데이트를 timeoutSeconds까지 기다렸다 반환한다. 예외를 던지지 않는다.
	 *
	 * <p><b>텍스트가 없는 업데이트(사진·스티커 등)는 결과에 담기지 않는다.</b> 이 서비스가 다루는 것은
	 * {@code /start}·{@code /stop}·{@code /help} 세 명령뿐이라 나머지는 해석할 대상이 없다. 다만 걸러진
	 * 업데이트의 {@code updateId}도 함께 사라지므로, 폴러(step 8)가 텍스트 업데이트만으로 offset을
	 * 전진시키면 걸러진 건이 확인되지 않은 채 남아 다음 폴링이 즉시 반환될 수 있다.
	 *
	 * @param offset         이 값 이상의 업데이트를 요청한다. 이보다 작은 업데이트는 텔레그램이 확인 처리한다
	 * @param timeoutSeconds 롱폴링 대기 시간. 설정된 {@code poll-timeout}이 상한이다
	 */
	PollResult getUpdates(long offset, int timeoutSeconds);
}
