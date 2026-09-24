package com.eottabom.migration.model;

import java.nio.file.Path;

/**
 * 태스크 옵션을 모은 실행 요청.
 *
 * @param targetBoot 목표 Boot 단계. null 이면 최종 단계
 * @param targetJava "auto"(목표 Boot 가 지원하면 유지) | "latest"(목표 Boot 가 지원하는 최신 LTS) | "none"
 * | "17" | "21" | "25"
 * @param gate "compile" | "build" | "none"
 * @param allowDirty 작업 트리에 커밋되지 않은 변경이 있어도 시작한다
 * @param keepJavaHome 대상 프로젝트용 JDK 자동 선택을 끄고 현재 JAVA_HOME 을 쓴다
 * @param skipProjectRecipes 대상 프로젝트의 .rewrite/ 레시피를 붙이지 않는다
 */
public record MigrationRequest(Path projectDir, String targetBoot, String targetJava, String gate, boolean commit,
		boolean dryRun, boolean oneShot, boolean upstreamOnly, boolean allowDirty, boolean keepJavaHome,
		boolean skipProjectRecipes) {
}
