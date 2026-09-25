package com.eottabom.migration.exec;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.eottabom.migration.exec.FakeBuildTool.BuildOutcome;
import com.eottabom.migration.model.MigrationRequest;
import org.gradle.api.GradleException;
import org.gradle.api.logging.Logging;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 러너의 실행 흐름을 임시 git 저장소와 가짜 대상 빌드로 검증한다. 실패 → 수정 → 재개 → 검증 → 커밋, 원본 빌드 실패와 새 실패의 구분, 작업
 * 트리 검사, 스테이징 보존.
 */
class MigrationRunnerFlowTests {

	@TempDir
	Path project;

	FakeBuildTool fake;

	MigrationRunner runner;

	@BeforeEach
	void setUp() throws IOException {
		write(".gitignore", "build/\n.gradle/\n");
		write("settings.gradle", "rootProject.name = 'demo'\n");
		write("build.gradle", "plugins {\n    id 'org.springframework.boot' version '3.3.5'\n}\n");
		write("gradle/wrapper/gradle-wrapper.properties",
				"distributionUrl=https\\://services.gradle.org/distributions/gradle-8.14-bin.zip\n");
		write("src/main/java/demo/App.java", "package demo;\nclass App {}\n");
		git("init", "-q", "-b", "main");
		// 러너의 커밋이 전역 git 설정 없이도 되도록
		git("config", "user.email", "t@t");
		git("config", "user.name", "t");
		git("add", "-A");
		commit("init");

		this.fake = new FakeBuildTool(this.project);
		// 3.4 단계는 Boot 버전을 올리고 새 파일(lombok.config)을 만든다, 3.5 단계는 버전만.
		// dir 은 대상 프로젝트 또는 preview 의 임시 worktree
		this.fake.rewrites.add((dir) -> {
			replace(dir.resolve("build.gradle"), "3.3.5", "3.4.0");
			FakeBuildTool.write(dir.resolve("lombok.config"), "config.stopBubbling = true\n");
		});
		this.fake.rewrites.add((dir) -> replace(dir.resolve("build.gradle"), "3.4.0", "3.5.0"));
		this.runner = new MigrationRunner(new MigrationRunner.RunnerPaths(this.project.resolve("rewrite.init.gradle"),
				this.project.resolve("verify.init.gradle"), this.project.resolve("libs"), Path.of("../playbook")),
				Logging.getLogger(MigrationRunnerFlowTests.class), (dir, javaHome) -> this.fake.at(dir));
	}

	@Test
	void previewsEveryStageInTemporaryWorktree() throws IOException {
		this.runner.run(preview("3.5"));

		assertThat(read("build.gradle")).contains("3.3.5");
		assertThat(read(".rewrite-migration/01-boot-3.4.dry.patch")).contains("+", "3.4.0");
		assertThat(read(".rewrite-migration/01-boot-3.4.dry.patch")).contains("lombok.config");
		assertThat(read(".rewrite-migration/02-boot-3.5.dry.patch")).contains("-", "3.4.0", "3.5.0")
			.doesNotContain("lombok.config");
		assertThat(this.project.resolve("lombok.config")).doesNotExist();
		assertThat(git("worktree", "list").lines()).hasSize(1);
	}

	@Test
	void commitsOnlyRecipeChangesWhenEachStagePasses() throws IOException {
		this.runner.run(request("3.5", true));

		assertThat(migrationCommits()).hasSize(2);
		assertThat(git("show", "--name-only", "--format=", "HEAD~1")).contains("build.gradle", "lombok.config");
		// 빌드가 만든 추적 안 되는 파일은 커밋에 들어가지 않는다
		assertThat(git("log", "--name-only", "--format=")).doesNotContain("test-output.log");
		assertThat(read("build.gradle")).contains("3.5.0");
		assertThat(this.project.resolve(".rewrite-migration/report.html")).exists();
		assertThat(this.project.resolve(".rewrite-migration/01-boot-3.4.stage.patch")).exists();
	}

	@Test
	void resumeAfterFixedCompileRunsBuildGateBeforeCommit() throws IOException {
		this.fake.compiles.add(false);
		assertThatThrownBy(() -> this.runner.run(request("3.5", true))).isInstanceOf(GradleException.class)
			.hasMessageContaining("컴파일 실패");
		assertThat(migrationCommits()).isEmpty();
		assertThat(read(".rewrite-migration/.resume")).contains("R_REASON=compile");

		// 사용자가 고치며 새 파일을 만들었다. 재개 때 컴파일은 통과하고, 테스트/빌드 게이트가 다시 돈다
		write("src/main/java/demo/Fix.java", "package demo;\nclass Fix {}\n");
		this.runner.run(request("3.5", true));

		// 원본 빌드 1번과 재개 게이트 1번
		assertThat(this.fake.count("clean build --continue")).isEqualTo(2);
		assertThat(migrationCommits()).hasSize(2);
		assertThat(git("show", "--name-only", "--format=", "HEAD~1")).contains("Fix.java", "lombok.config");
		assertThat(git("log", "--name-only", "--format=")).doesNotContain("test-output.log");
	}

	@Test
	void doesNotFinishLastStageWhenTestsFailAfterCompileFix() throws IOException {
		this.fake.compiles.add(false);
		assertThatThrownBy(() -> this.runner.run(request("3.4", true))).hasMessageContaining("컴파일 실패");

		this.fake.builds.add(new BuildOutcome(true, 1, List.of()));
		assertThatThrownBy(() -> this.runner.run(request("3.4", true))).hasMessageContaining("테스트 1개 실패");
		assertThat(migrationCommits()).isEmpty();
		assertThat(read(".rewrite-migration/.resume")).contains("R_REASON=build");
	}

	@Test
	void resumeWithoutCommitIsNotBlockedByDirtyTree() throws IOException {
		this.fake.builds.add(new BuildOutcome(true, 2, List.of()));
		assertThatThrownBy(() -> this.runner.run(request("3.5", false))).hasMessageContaining("테스트 2개 실패");
		assertThat(git("status", "--porcelain")).contains("build.gradle");

		this.runner.run(request("3.5", false));

		assertThat(read("build.gradle")).contains("3.5.0");
		assertThat(this.project.resolve(".rewrite-migration/.resume")).doesNotExist();
	}

	@Test
	void passesBaselineFailuresButStopsOnNewFailedTasks() throws IOException {
		this.fake.baseline = new BuildOutcome(false, 0, List.of(":app:bootJar"));
		this.fake.builds.add(new BuildOutcome(false, 0, List.of(":app:bootJar")));
		this.fake.builds.add(new BuildOutcome(false, 0, List.of(":app:bootJar", ":app:checkstyleMain")));

		assertThatThrownBy(() -> this.runner.run(request("3.5", true))).hasMessageContaining("[3.5]")
			.hasMessageContaining(":app:checkstyleMain")
			.hasMessageNotContaining(":app:bootJar,");
		assertThat(migrationCommits()).hasSize(1);
	}

	@Test
	void passesTestsThatAlreadyFailedButStopsOnNewTestFailures() throws IOException {
		this.fake.baseline = new BuildOutcome(true, 2, List.of());
		this.fake.builds.add(new BuildOutcome(true, 2, List.of()));
		this.fake.builds.add(new BuildOutcome(true, 3, List.of()));

		assertThatThrownBy(() -> this.runner.run(request("3.5", true))).hasMessageContaining("[3.5]")
			.hasMessageContaining("테스트 1개 실패");
		assertThat(migrationCommits()).hasSize(1);
		assertThat(read(".rewrite-migration/00-baseline-failed-tests.txt")).contains("demo.AppTest#t0",
				"demo.AppTest#t1");
	}

	@Test
	void stopsOnBuildFailureWithUnknownCause() {
		this.fake.baseline = new BuildOutcome(false, 0, List.of(":app:bootJar"));
		this.fake.builds.add(new BuildOutcome(false, 0, List.of()));

		assertThatThrownBy(() -> this.runner.run(request("3.4", true))).hasMessageContaining("로그에서 실패 태스크를 찾지 못함");
	}

	@Test
	void revertsAndRetriesStageWhenCompileStillFails() throws IOException {
		this.fake.compiles.add(false);
		assertThatThrownBy(() -> this.runner.run(request("3.4", false))).hasMessageContaining("컴파일 실패");
		assertThat(read("build.gradle")).contains("3.4.0");

		// 재개 때 컴파일이 여전히 실패 → 되돌리고 3.4 를 다시 실행 (이번에는 레시피가 바뀌어 컴파일된다고 가정)
		this.fake.compiles.add(false);
		this.fake.rewrites.addFirst((dir) -> replace(dir.resolve("build.gradle"), "3.3.5", "3.4.1"));
		this.runner.run(request("3.4", false));

		assertThat(read("build.gradle")).contains("3.4.1");
		assertThat(this.project.resolve("lombok.config")).doesNotExist();
	}

	@Test
	void refusesToStartWithUncommittedChanges() throws IOException {
		write("src/main/java/demo/App.java", "package demo;\nclass App { int x; }\n");

		assertThatThrownBy(() -> this.runner.run(request("3.4", false))).hasMessageContaining("--allow-dirty");
		assertThatThrownBy(() -> this.runner.run(request("3.4", true))).hasMessageContaining("--commit 은 작업 트리가 깨끗해야");
	}

	@Test
	void keepsUserStagingWhenWritingPatch() throws IOException {
		// --allow-dirty 로 시작한 사용자가 일부만 스테이징해 둔 상태
		write("src/main/java/demo/App.java", "package demo;\nclass App { int staged; }\n");
		git("add", "src/main/java/demo/App.java");
		write("build.gradle", read("build.gradle") + "// not staged\n");
		String staged = git("diff", "--cached", "--name-only");
		String unstaged = git("diff", "--name-only");

		new Git(this.project).diffSince(git("rev-parse", "HEAD").trim(), this.project.resolve("out.patch"), List.of(),
				this.project.resolve(".index-tmp"));

		assertThat(git("diff", "--cached", "--name-only")).isEqualTo(staged)
			.contains("App.java")
			.doesNotContain("build.gradle");
		assertThat(git("diff", "--name-only")).isEqualTo(unstaged);
		assertThat(read("out.patch")).contains("App.java", "build.gradle");
	}

	private MigrationRequest request(String target, boolean commit) {
		return request(target, commit, false);
	}

	private MigrationRequest preview(String target) {
		return request(target, false, true);
	}

	/** gate=build, java 유지, JAVA_HOME 그대로, 프로젝트 레시피 없음 */
	private MigrationRequest request(String target, boolean commit, boolean preview) {
		return new MigrationRequest(this.project, target, "none", "build", commit, preview, false, false, false, true,
				true);
	}

	private List<String> migrationCommits() throws IOException {
		return git("log", "--format=%s").lines().filter((l) -> l.contains("마이그레이션")).toList();
	}

	private void commit(String message) throws IOException {
		git("commit", "-q", "-m", message);
	}

	private String git(String... args) throws IOException {
		List<String> command = new ArrayList<>(List.of("git", "-c", "user.email=t@t", "-c", "user.name=t"));
		command.addAll(List.of(args));
		Process process = new ProcessBuilder(command).directory(this.project.toFile())
			.redirectErrorStream(true)
			.start();
		String out = new String(process.getInputStream().readAllBytes());
		try {
			process.waitFor();
		}
		catch (InterruptedException ex) {
			throw new IllegalStateException(ex);
		}
		return out;
	}

	private void write(String path, String content) {
		FakeBuildTool.write(this.project.resolve(path), content);
	}

	private String read(String path) throws IOException {
		return Files.readString(this.project.resolve(path));
	}

	private static void replace(Path file, String from, String to) {
		try {
			FakeBuildTool.write(file, Files.readString(file).replace(from, to));
		}
		catch (IOException ex) {
			throw new IllegalStateException(ex);
		}
	}

}
