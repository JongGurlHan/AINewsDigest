package com.example.ainewsdigest.curation;

/**
 * 선별·요약이 성립하지 못했을 때 던진다. 수집·발송의 실패는 결과 타입으로 표현하지만 이쪽은 예외다 —
 * 채점이나 요약이 없으면 그날 다이제스트 자체가 만들어지지 않으므로, 스케줄러까지 올려보내
 * 재시도(07:15)와 관리자 알림을 결정하게 해야 한다 (ADR-016).
 */
public class CurationException extends RuntimeException {

	public CurationException(String message) {
		super(message);
	}

	public CurationException(String message, Throwable cause) {
		super(message, cause);
	}
}
