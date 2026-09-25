package com.eottabom.migration.exec;

/**
 * 게이트 단계(컴파일, 빌드) 하나의 결과. 리포트 JSON 에는 {@link #json()} 값으로 쓴다.
 */
enum Outcome {

	PASSED("ok"), FAILED("fail"), SKIPPED("skip");

	private final String json;

	Outcome(String json) {
		this.json = json;
	}

	static Outcome of(boolean passed) {
		return passed ? PASSED : FAILED;
	}

	String json() {
		return this.json;
	}

}
