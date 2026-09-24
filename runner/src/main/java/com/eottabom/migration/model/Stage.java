package com.eottabom.migration.model;

/**
 * 마이그레이션 한 단계. 단계마다 rewriteRun 과 게이트를 한 번씩 돈다.
 *
 * @param name Boot 단계는 "3.4", Java 단계는 "java21", Gradle 단계는 "gradle8.14"
 */
public record Stage(Kind kind, String name, String recipe) {

	public enum Kind {

		BOOT, JAVA, GRADLE

	}

	/** 리포트/패치 파일 이름 접두사. 예) 03-boot-3.4, 06-java25, 02-gradle8.14 */
	public String tag(int order) {
		return String.format("%02d-%s", order, (this.kind == Kind.BOOT) ? "boot-" + this.name : this.name);
	}

	/** known-issues.yml 의 stage 키. 예) 3.4, java21, gradle */
	public String issueKey() {
		return (this.kind == Kind.GRADLE) ? "gradle" : this.name;
	}
}
