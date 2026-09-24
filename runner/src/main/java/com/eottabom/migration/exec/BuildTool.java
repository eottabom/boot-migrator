package com.eottabom.migration.exec;

import java.nio.file.Path;
import java.util.List;

/**
 * 대상 프로젝트의 빌드 실행. 실제로는 {@link TargetGradle} 이 대상 프로젝트의 gradlew 를 띄우고, 러너 통합 테스트는 가짜 구현으로
 * 실패 → 수정 → 재개 흐름을 재현한다.
 */
public interface BuildTool {

	/** 대상 빌드를 띄우는 JDK (null 이면 현재 JAVA_HOME) */
	String javaHome();

	/** 출력은 log 파일로 보낸다. 종료 코드가 0 이면 true */
	boolean run(Path log, List<String> args);

	/** 결과만 필요하고 로그는 남기지 않는 실행 */
	boolean runQuietly(List<String> args);

	/**
	 * rewriteRun / rewriteDryRun. configFile 은 선언형 레시피 파일
	 * (.rewrite/rewrite.generated.yml), null 이면 플러그인 기본값
	 */
	boolean rewrite(Path log, String task, String recipe, Path rewriteInit, Path recipeLibs, Path configFile);

	default boolean rewrite(Path log, String task, String recipe, Path rewriteInit, Path recipeLibs) {
		return rewrite(log, task, recipe, rewriteInit, recipeLibs, null);
	}

	/** 대상 프로젝트와 JDK 로 BuildTool 을 만든다 */
	@FunctionalInterface
	interface Factory {

		BuildTool create(Path projectDir, String javaHome);

	}

}
