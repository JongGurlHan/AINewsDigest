package com.example.ainewsdigest.delivery;

import java.util.List;

/**
 * 롱폴링 한 사이클의 결과.
 *
 * <p><b>실패를 빈 리스트로 표현하지 않는다 (ADR-016).</b> 롱폴링에서 "타임아웃까지 기다렸는데 업데이트가
 * 없었다"는 가장 흔한 <b>정상</b> 응답이고 그것도 빈 리스트다. 둘을 같은 값으로 만들면 폴러(step 8)가
 * 실패를 감지할 수 없어 백오프를 걸 방법이 아예 없어진다 — 정상 무응답마다 5초씩 자거나, 네트워크가
 * 끊긴 채로 초당 수십 번 재시도하게 된다.
 */
public sealed interface PollResult {

	/** 정상 응답. <b>0건이어도 실패가 아니다.</b> */
	record Updates(List<TelegramUpdate> updates) implements PollResult {

		public Updates {
			updates = List.copyOf(updates);
		}
	}

	/**
	 * 폴링 자체가 실패했다. 폴러는 백오프를 건다.
	 *
	 * <p>{@code reason}은 관리자 알림으로 발송될 수 있으므로 구현체가 봇 토큰을 마스킹한 문자열만 담는다.
	 */
	record Failure(String reason) implements PollResult {
	}
}
