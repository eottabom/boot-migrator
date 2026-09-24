package com.eottabom.migration.model;

import java.nio.file.Path;

/**
 * 태스크 옵션을 모은 실행 요청.
 *
 * @param targetBoot null 이면 최종 단계
 * @param targetJava auto(목표 Boot 가 지원하면 유지) | latest(지원하는 최신 LTS) | none | 17 | 21 | 25
 * @param gate compile | build | none
 */
public record MigrationRequest(Path projectDir, String targetBoot, String targetJava, String gate, boolean commit,
		boolean dryRun, boolean oneShot, boolean upstreamOnly, boolean allowDirty, boolean keepJavaHome,
		boolean skipProjectRecipes) {
}
