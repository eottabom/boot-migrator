package com.eottabom.migration.exec;

import java.util.Set;

/**
 * 게이트 결과.
 *
 * @param build build 게이트 결과 (테스트 실패는 failedTests 로 따로 센다)
 * @param failedTests build 게이트에서 실패한 테스트 수
 * @param newFailedTasks 원본에서는 실패하지 않던 태스크 중 이 단계에서 실패한 것 (원인을 모르는 실패도 포함)
 */
record GateResult(boolean compileOk, Outcome build, int failedTests, Set<String> newFailedTasks) {

	static final GateResult SKIPPED = new GateResult(true, Outcome.SKIPPED, 0, Set.of());

	boolean buildBlocking() {
		return !this.newFailedTasks.isEmpty();
	}

	boolean passed() {
		return this.compileOk && this.failedTests == 0 && !buildBlocking();
	}

	String describe() {
		if (!this.compileOk) {
			return "컴파일 실패";
		}
		String build = "빌드 실패 (새로 실패한 태스크 " + String.join(", ", this.newFailedTasks) + ")";
		if (this.failedTests > 0 && buildBlocking()) {
			return "테스트 " + this.failedTests + "개 실패, " + build;
		}
		if (this.failedTests > 0) {
			return "테스트 " + this.failedTests + "개 실패";
		}
		return build;
	}
}
