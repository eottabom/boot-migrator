package com.eottabom.migration.exec;

import java.nio.file.Path;
import java.util.List;

/**
 * 대상 프로젝트의 빌드 실행. 실제로는 {@link TargetGradle} 이 대상 프로젝트의 gradlew 를 띄우고, 러너 통합 테스트는 가짜 구현으로
 * 실패 → 수정 → 재개 흐름을 재현한다.
 */
public interface BuildTool {

	/** null 이면 현재 JAVA_HOME */
	String javaHome();

	boolean run(Path log, List<String> args);

	boolean runQuietly(List<String> args);

	/** configFile 은 .rewrite/rewrite.generated.yml, null 이면 플러그인 기본값 */
	boolean rewrite(Path log, String task, String recipe, Path rewriteInit, Path recipeLibs, Path configFile);

	default boolean rewrite(Path log, String task, String recipe, Path rewriteInit, Path recipeLibs) {
		return rewrite(log, task, recipe, rewriteInit, recipeLibs, null);
	}

	@FunctionalInterface
	interface Factory {

		BuildTool create(Path projectDir, String javaHome);

	}

}
