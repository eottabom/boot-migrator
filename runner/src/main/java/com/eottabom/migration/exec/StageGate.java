package com.eottabom.migration.exec;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.gradle.api.GradleException;

/**
 * 단계 게이트. 원본 빌드로 원래 실패하던 태스크와 테스트를 기록하고, 단계마다 compile 과 build 를 돌려 새로 깨진 것만 막는다.
 */
final class StageGate {

	private final BuildTool gradle;

	private final MigrationWorkspace ws;

	private final Path verifyInit;

	private final RunnerConsole console;

	StageGate(BuildTool gradle, MigrationWorkspace ws, Path verifyInit, RunnerConsole console) {
		this.gradle = gradle;
		this.ws = ws;
		this.verifyInit = verifyInit;
		this.console = console;
	}

	/**
	 * compile (+deprecation/removal 경고) → build (전체 테스트 + 패키징). gate=none 이면 아무것도 하지 않는다.
	 */
	GateResult stage(String tag, String gate, String stageName, Path stageVersions) {
		if (gate.equals("none")) {
			return GateResult.SKIPPED;
		}
		this.console.step("[" + stageName + "] compile (+deprecation/removal 경고 수집)");
		// clean: rewriteRun 이 컴파일하며 src/main/generated 에 만든 Q-class 와 APT 가 다시 충돌하지 않도록
		boolean compileOk = this.gradle.run(this.ws.file(tag + ".compile.log"),
				verifyArgs(this.verifyInit, "clean", "compileJava", "compileTestJava", "migrationResolvedVersions",
						"-PmigrationVersionsOut=" + stageVersions));
		// 컴파일이 깨져도 버전 목록은 남긴다
		if (!stageVersions.toFile().exists()) {
			this.gradle.runQuietly(verifyArgs(this.verifyInit, "migrationResolvedVersions",
					"-PmigrationVersionsOut=" + stageVersions));
		}
		if (!compileOk || !gate.equals("build")) {
			return new GateResult(compileOk, Outcome.SKIPPED, 0, Set.of());
		}
		this.console.step("[" + stageName + "] build (전체 테스트 + 패키징, properties-migrator 경고 수집)");
		return build(tag, List.of("build", "--continue"));
	}

	/** 재개 때는 선택한 게이트를 다시 확인한다. compile 게이트는 재개 전에 이미 컴파일을 확인했다. */
	GateResult resume(String tag, String gate) {
		if (!gate.equals("build")) {
			return GateResult.SKIPPED;
		}
		this.console.step("[재개] build (전체 테스트 + 패키징)");
		return build(tag, List.of("clean", "build", "--continue"));
	}

	/**
	 * 테스트는 ignoreFailures 로 끝까지 돌리고 결과 XML 로 센다. --continue 로 실패한 태스크를 모두 모은 뒤 원본에서도 실패하던
	 * 태스크만 실패했으면 막지 않는다. 로그에서 실패 태스크를 찾지 못한 실패는 원인을 모르므로 막는다.
	 */
	private GateResult build(String tag, List<String> args) {
		Path log = this.ws.file(tag + ".test.log");
		boolean built = this.gradle.run(log, verifyArgs(this.verifyInit, args.toArray(String[]::new)));
		TestResults.Summary tests = TestResults.collect(this.ws.dir().getParent());
		Set<String> failedTests = TestResults.failedTests(this.ws.dir().getParent());
		int existing = failedTests.size();
		failedTests.removeAll(this.ws.baselineFailedTests());
		existing -= failedTests.size();
		this.console.line("   테스트 {}개, 실패 {}개{}", tests.total(), failedTests.size(),
				(existing > 0) ? " (원본에서도 실패하던 " + existing + "개 제외)" : "");
		Set<String> newFailures = new TreeSet<>();
		if (!built) {
			Set<String> failed = failedTasks(log);
			if (failed.isEmpty()) {
				newFailures.add("(로그에서 실패 태스크를 찾지 못함)");
			}
			else {
				failed.removeAll(this.ws.baselineFailedTasks());
				newFailures.addAll(failed);
			}
			if (newFailures.isEmpty()) {
				this.console.line("   빌드 실패: 원본에서도 실패하던 태스크만 실패했다 ({})", this.ws.file("00-baseline-build.log"));
			}
		}
		return new GateResult(true, Outcome.of(built), failedTests.size(), newFailures);
	}

	/** --continue 로 돌린 빌드 로그에서 실패한 태스크 경로를 모은다. */
	static Set<String> failedTasks(Path log) {
		Set<String> tasks = new TreeSet<>();
		Matcher m = Pattern.compile("Execution failed for task '([^']+)'").matcher(MigrationWorkspace.read(log));
		while (m.find()) {
			tasks.add(m.group(1));
		}
		return tasks;
	}

	/**
	 * 원본 빌드를 한 번 돌려 원래부터 실패하던 태스크와 테스트를 저장한다. 테스트는 ignoreFailures 로 끝까지 돌린다. 원본에서 컴파일이
	 * 깨지면 OpenRewrite 가 레시피를 실행할 수 없으므로 멈춘다.
	 */
	void baseline(String projectName) {
		Path log = this.ws.file("00-baseline-build.log");
		boolean built = this.gradle.run(log, verifyArgs(this.verifyInit, "clean", "build", "--continue"));
		if (!built && MigrationWorkspace.countMatches(log,
				"Execution failed for task '[^']*:compile(Test)?(Java|Groovy|Kotlin)'") > 0) {
			throw new GradleException("현재 소스가 컴파일되지 않는다. 컴파일 에러를 고친 뒤 다시 실행한다 → " + log);
		}
		Set<String> failedTasks = built ? Set.of() : failedTasks(log);
		Set<String> failedTests = TestResults.failedTests(this.ws.dir().getParent());
		this.ws.writeBaselineFailedTasks(failedTasks);
		this.ws.writeBaselineFailedTests(failedTests);
		if (!failedTasks.isEmpty()) {
			this.console.line("   원본 빌드에서도 실패하는 태스크 (기존 문제, 컴파일은 통과) {} → {}", failedTasks, log);
			this.ws.appendSummary(projectName,
					"- 원본 빌드에서도 실패하는 태스크 (기존 문제, 마이그레이션 무관) " + failedTasks + ", 00-baseline-build.log\n");
		}
		if (!failedTests.isEmpty()) {
			this.console.line("   원본에서도 실패하는 테스트 {}개 (단계를 막지 않는다) → {}", failedTests.size(),
					this.ws.file("00-baseline-failed-tests.txt"));
			this.ws.appendSummary(projectName,
					"- 원본에서도 실패하는 테스트 " + failedTests.size() + "개 (기존 문제, 단계를 막지 않는다), 00-baseline-failed-tests.txt\n");
		}
	}

	/** verify.init.gradle 을 붙인 Gradle 인자 */
	static List<String> verifyArgs(Path verifyInit, String... args) {
		List<String> all = new ArrayList<>(List.of("--init-script", verifyInit.toString()));
		all.addAll(List.of(args));
		return all;
	}

}
