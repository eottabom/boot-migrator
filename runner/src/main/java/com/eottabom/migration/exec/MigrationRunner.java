package com.eottabom.migration.exec;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.eottabom.migration.exec.MigrationWorkspace.Resume;
import com.eottabom.migration.inspect.JdkLocator;
import com.eottabom.migration.inspect.ProjectInspector;
import com.eottabom.migration.model.MigrationPlan;
import com.eottabom.migration.model.MigrationRequest;
import com.eottabom.migration.model.ProjectModel;
import com.eottabom.migration.model.Stage;
import com.eottabom.migration.plan.MigrationPlanner;
import com.eottabom.migration.playbook.Compatibility;
import com.eottabom.migration.playbook.KnownIssues;
import com.eottabom.migration.playbook.KnownIssues.Issue;
import com.eottabom.migration.playbook.KnownIssues.Match;
import com.eottabom.migration.playbook.KnownIssues.Mode;
import com.eottabom.migration.recipe.GeneratedRecipe;
import com.eottabom.migration.recipe.GeneratedRecipe.Generated;
import com.eottabom.migration.recipe.ProjectRecipes;
import com.eottabom.migration.recipe.ProjectRecipes.Phase;
import com.eottabom.migration.recipe.ProjectRecipes.ProjectRecipe;
import org.gradle.api.GradleException;
import org.gradle.api.logging.Logger;
import org.yaml.snakeyaml.Yaml;

/**
 * 단계 루프: 현재 버전의 다음 단계부터 목표까지 단계마다 rewriteRun → compile → build → 리포트 → (commit).
 *
 * 컴파일이 깨지면 그 단계에서 멈춘다. 같은 명령을 다시 실행하면 - 컴파일 에러를 고쳐 두었으면 다음 단계부터 이어서 진행하고 - 그대로면 단계별 누적
 * patch 로 그 단계 전 상태를 만들어 그 단계부터 다시 시도한다.
 */
public final class MigrationRunner {

	private static final List<String> GATES = List.of("compile", "build", "none");

	private final RunnerPaths paths;

	private final Logger logger;

	private final ProjectInspector inspector = new ProjectInspector();

	private final MigrationPlanner planner;

	private final KnownIssues knownIssues;

	private final BuildTool.Factory buildTools;

	/**
	 * @param gradleJvmArgs 대상 Gradle 데몬 JVM 옵션 (--gradle-jvmargs). null 이면 장비 메모리 기준 기본값
	 */
	public MigrationRunner(RunnerPaths paths, String gradleJvmArgs, Logger logger) {
		this(paths, logger, (dir, javaHome) -> new TargetGradle(dir, javaHome, gradleJvmArgs, logger));
	}

	/** 대상 빌드 실행을 바꿔 끼운다 (러너 통합 테스트) */
	MigrationRunner(RunnerPaths paths, Logger logger, BuildTool.Factory buildTools) {
		this.paths = paths;
		this.buildTools = buildTools;
		this.logger = logger;
		this.planner = new MigrationPlanner(Compatibility.load(paths.playbookDir().resolve("compatibility.yml")));
		this.knownIssues = KnownIssues.load(paths.playbookDir().resolve("known-issues.yml"));
	}

	/** 현재 상태, resolve 된 의존성, 수동 검토 대상 위치. 소스는 바꾸지 않는다. */
	public void analyze(Path projectDir, boolean keepJavaHome) {
		ProjectModel project = this.inspector.inspect(projectDir);
		BuildTool gradle = targetGradle(project, keepJavaHome);
		MigrationWorkspace ws = MigrationWorkspace.in(projectDir);
		printProject(project, gradle);

		printProjectRecipes(projectDir, ProjectRecipes.discover(projectDir));

		step("[분석] 의존성 버전");
		Path versions = ws.file("analyze.versions.txt");
		if (resolvedVersions(gradle, ws.file("analyze.versions.log"), versions)) {
			this.logger.lifecycle("   의존성 {}개 → {}", MigrationWorkspace.countMatches(versions, "."), versions);
		}
		else {
			fail("의존성 버전 수집 실패 → " + ws.file("analyze.versions.log"));
		}

		step("[분석] 수동 검토 대상 (FindManualMigrationItems)");
		Path findPatch = ws.file("analyze.find.patch");
		if (scan(projectDir, gradle, ws.file("analyze.scan.log"), findPatch)) {
			this.logger.lifecycle("   {} 곳 → {}", MigrationWorkspace.countMatches(findPatch, "~~>"), findPatch);
		}
		else {
			fail("스캔 실패 → " + ws.file("analyze.scan.log"));
		}
	}

	/** 실행할 단계만 보여준다. 대상 프로젝트의 Gradle 을 띄우지 않는다. */
	public MigrationPlan plan(MigrationRequest request) {
		ProjectModel project = this.inspector.inspect(request.projectDir());
		MigrationPlan plan = planOrFail(project, request);
		step("프로젝트 : " + project.dir());
		this.logger.lifecycle("   현재     : Boot {} / Gradle {} / Java {}", project.bootVersion(),
				orQ(project.gradleVersion()), orQ(project.javaVersion()));
		this.logger.lifecycle("   목표     : Boot {} / Java {}", plan.targetBoot(),
				(plan.targetJava() == null) ? "유지" : plan.targetJava());
		printTargetLine(plan);
		printNotes(plan);
		if (plan.isEmpty()) {
			this.logger.lifecycle("   단계     : 없음 (이미 목표 이상)");
			return plan;
		}
		ProjectRecipes projectRecipes = projectRecipes(request);
		this.logger.lifecycle("   단계     :");
		for (Stage stage : plan.stages()) {
			this.logger.lifecycle("     {}  {}{}", String.format("%-11s", stage.name()), stage.recipe(),
					projectRecipeSuffix(projectRecipes, stage));
		}
		printProjectRecipes(request.projectDir(), projectRecipes);
		step("알려진 이슈 미리보기 (playbook/known-issues.yml, 의존성 조건은 실행 때 판단)");
		Map<Mode, Integer> total = new EnumMap<>(Mode.class);
		for (Stage stage : plan.stages()) {
			List<Issue> issues = this.knownIssues.forStage(stage.issueKey());
			if (issues.isEmpty()) {
				continue;
			}
			this.logger.lifecycle("   [{}]", stage.name());
			for (Mode mode : List.of(Mode.REPORT_ONLY, Mode.REVIEW_REQUIRED, Mode.AUTO_FIX)) {
				for (Issue issue : issues) {
					if (issue.mode() == mode) {
						total.merge(mode, 1, Integer::sum);
						String when = issue.requires().isEmpty() ? ""
								: "  (" + String.join(", ", issue.requires()) + " 사용 시)";
						this.logger.lifecycle("     {}  {}{}", String.format("%-15s", mode), issue.title(), when);
					}
				}
			}
		}
		this.logger.lifecycle("   합계: REPORT_ONLY {} / REVIEW_REQUIRED {} / AUTO_FIX {}",
				total.getOrDefault(Mode.REPORT_ONLY, 0), total.getOrDefault(Mode.REVIEW_REQUIRED, 0),
				total.getOrDefault(Mode.AUTO_FIX, 0));
		return plan;
	}

	/** 현재 소스의 컴파일(+제거 예정 API 경고)과 build(전체 테스트 + 패키징). 소스는 바꾸지 않는다. */
	public void verify(Path projectDir, String gate, boolean keepJavaHome) {
		checkGate(gate);
		ProjectModel project = this.inspector.inspect(projectDir);
		BuildTool gradle = targetGradle(project, keepJavaHome);
		MigrationWorkspace ws = MigrationWorkspace.in(projectDir);
		printProject(project, gradle);
		if (gate.equals("none")) {
			return;
		}
		step("[검증] compile (+deprecation/removal 경고 수집)");
		Path compileLog = ws.file("verify.compile.log");
		if (!gradle.run(compileLog, verifyArgs("clean", "compileJava", "compileTestJava"))) {
			throw failure("컴파일 실패 → " + compileLog);
		}
		this.logger.lifecycle("   [removal] 경고 {}건, [deprecation] 경고 {}건 → {}",
				MigrationWorkspace.countMatches(compileLog, "\\[removal\\]"),
				MigrationWorkspace.countMatches(compileLog, "\\[deprecation\\]"), compileLog);
		if (gate.equals("build")) {
			step("[검증] build (전체 테스트 + 패키징)");
			Path buildLog = ws.file("verify.build.log");
			boolean built = gradle.run(buildLog, verifyArgs("build"));
			TestResults.Summary tests = TestResults.collect(projectDir);
			this.logger.lifecycle("   테스트 {}개, 실패 {}개", tests.total(), tests.failed());
			if (!built) {
				throw failure("테스트 외 태스크(패키징, 플러그인, 검사)에서 빌드 실패 → " + buildLog);
			}
			if (tests.failed() > 0) {
				throw failure("테스트 실패 " + tests.failed() + "개 → " + projectDir.resolve("build/reports/tests"));
			}
		}
	}

	public void run(MigrationRequest request) {
		checkGate(request.gate());
		Path projectDir = request.projectDir();
		String projectName = projectDir.getFileName().toString();
		MigrationWorkspace ws = MigrationWorkspace.in(projectDir);
		Git git = new Git(projectDir);
		BuildTool gradle = targetGradle(this.inspector.inspect(projectDir), request.keepJavaHome());
		ProjectRecipes projectRecipes = projectRecipes(request);

		// 지난 실행이 게이트(컴파일, 테스트, 빌드) 실패로 멈춘 경우
		Resumed resumed = request.dryRun() ? Resumed.NONE
				: resume(request, ws, git, gradle, projectName, projectRecipes);

		ProjectModel project = this.inspector.inspect(projectDir);
		MigrationPlan plan = planOrFail(project, request);
		String opts = options(request, plan);
		step("프로젝트 : " + projectDir);
		this.logger.lifecycle("   현재     : Boot {} / Gradle {} / JAVA_HOME={}", project.bootVersion(),
				orQ(project.gradleVersion()), orDefault(gradle.javaHome()));
		this.logger.lifecycle("   목표     : Boot {}  ({})", plan.targetBoot(), opts);
		printProjectRecipes(projectDir, projectRecipes);

		String baseRev = null;
		if (project.git()) {
			// 리포트 디렉토리와 생성 레시피는 git 에 올리지 않는다 (patch 스냅샷 / 커밋 대상에서 제외)
			git.exclude(MigrationWorkspace.DIR_NAME + "/");
			git.exclude(GeneratedRecipe.RELATIVE_PATH);
			baseRev = ws.baseRevision().orElse(git.head());
			ws.recordBaseRevision(baseRev);
		}
		if (request.commit() && !project.git()) {
			throw failure("--commit 은 git 저장소에서만 쓸 수 있습니다");
		}
		// 자동 코드 변경이 기존 변경과 섞이지 않도록 새로 시작할 때는 깨끗한 작업 트리를 요구한다.
		// 재개했다면 남아 있는 변경은 이 마이그레이션의 변경이다
		if (project.dirty() && !resumed.active() && !request.dryRun()) {
			if (request.commit()) {
				throw failure("--commit 은 작업 트리가 깨끗해야 합니다 (기존 변경이 커밋에 섞인다)");
			}
			if (!request.allowDirty()) {
				throw failure("작업 트리에 커밋되지 않은 변경이 있다. 커밋하거나 --allow-dirty 로 계속한다");
			}
		}

		if (plan.isEmpty()) {
			step("이미 Boot " + project.bootVersion() + " (목표 " + plan.targetBoot() + " 이상) 입니다.");
			return;
		}
		ws.recordStartBoot(project.bootVersion());
		this.logger.lifecycle("   Java     : {} -> {}", orQ(project.javaVersion()),
				(plan.targetJava() == null) ? "유지" : plan.targetJava());
		this.logger.lifecycle("   단계     : {}", plan.stageNames());
		printTargetLine(plan);
		printNotes(plan);

		StringBuilder header = new StringBuilder().append("## ")
			.append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")))
			.append(" 실행\n\n| 시작 | 목표 | 단계 |\n|---|---|---|\n| Boot ")
			.append(project.bootVersion())
			.append(" / Gradle ")
			.append(orQ(project.gradleVersion()))
			.append(" / Java ")
			.append(orQ(project.javaVersion()))
			.append(" | Boot ")
			.append(plan.targetBoot())
			.append((plan.targetJava() == null) ? "" : " / Java " + plan.targetJava())
			.append(" (")
			.append(opts)
			.append(") | ")
			.append(plan.stageNames())
			.append(" |\n\n");
		plan.notes().forEach((n) -> header.append("- ").append(n).append('\n'));
		if (resumed.note() != null) {
			header.append(resumed.note()).append('\n');
		}
		ws.appendSummary(projectName, header.toString());

		step("[스캔] 의존성 버전 / 수동 검토 대상 탐지");
		Path previousVersions = (resumed.lastTag() != null
				&& Files.exists(ws.file(resumed.lastTag() + ".versions.txt")))
						? ws.file(resumed.lastTag() + ".versions.txt") : ws.file("00-versions.txt");
		if (!resumed.active() && resolvedVersions(gradle, ws.file("00-versions.log"), previousVersions)) {
			this.logger.lifecycle("   의존성 {}개 → {}", MigrationWorkspace.countMatches(previousVersions, "."),
					previousVersions);
		}
		// 원본 빌드: 원본에서도 실패하던 태스크와 테스트는 단계의 실패로 보지 않는다. 재개했다면 처음 실행 때 저장한 목록을 쓴다
		if (request.gate().equals("build") && !request.dryRun() && !resumed.active()) {
			baselineBuild(gradle, ws, projectName);
		}
		Path findPatch = ws.file("00-scan.find.patch");
		if (resumed.active() && Files.exists(findPatch)) {
			this.logger.lifecycle("   수동 검토 대상은 처음 실행 때 스캔한 결과를 쓴다 → {}", findPatch);
		}
		else if (scan(projectDir, gradle, ws.file("00-scan.log"), findPatch)) {
			this.logger.lifecycle("   {} 곳 → {}", MigrationWorkspace.countMatches(findPatch, "~~>"), findPatch);
		}
		else {
			// 스캔은 수동 검토 대상 위치를 표시하는 용도라 실패해도 마이그레이션은 계속한다
			fail("스캔 실패 (수동 검토 대상 표시만 빠지고 계속 진행한다) → " + ws.file("00-scan.log"));
			ws.appendSummary(projectName, "- 스캔이 실패해서 수동 검토 대상 표시가 없다 (00-scan.log)\n");
		}
		ws.appendSummary(projectName,
				"\n| 단계 | 컴파일 | 테스트 | 빌드 | 자동 보정 | 수동 검토 | 알려진 이슈 | 리포트 |\n|---|---|---|---|---|---|---|---|\n");

		// 단계 번호는 지난 기록 뒤에 이어서 붙인다 (재개 시에는 다시 시도하는 단계 번호부터)
		int order = (resumed.retryFrom() != null) ? resumed.retryFrom() : ws.stageReportCount();
		String lastTag = resumed.lastTag();
		for (Stage stage : plan.stages()) {
			order++;
			String tag = stage.tag(order);
			// 단계 레시피 + 프로젝트 레시피를 .rewrite/rewrite.generated.yml 로 만들고 그 레시피를 실행한다 (기록용
			// 사본은 .rewrite-migration/)
			Generated generated = GeneratedRecipe.write(projectDir, projectName, stage, tag, projectRecipes);
			MigrationWorkspace.copyOrEmpty(generated.file(), ws.file(tag + ".generated.yml"));
			String recipeLabel = stage.recipe() + projectRecipeSuffix(projectRecipes, stage);

			if (request.dryRun()) {
				step("[" + stage.name() + "] " + recipeLabel + " (preview)");
				if (!gradle.rewrite(ws.file(tag + ".rewrite.log"), "rewriteDryRun", generated.name(),
						this.paths.rewriteInit(), this.paths.recipeLibs(), generated.file())) {
					throw failure("preview 실패 → " + ws.file(tag + ".rewrite.log"));
				}
				Path dryPatch = ws.file(tag + ".dry.patch");
				if (MigrationWorkspace.copyOrEmpty(rewritePatch(projectDir), dryPatch)) {
					this.logger.lifecycle("   {} files → {}", MigrationWorkspace.countMatches(dryPatch, "^diff --git"),
							dryPatch);
				}
				else {
					this.logger.lifecycle("   변경 없음");
				}
				this.logger.lifecycle("   (preview 는 소스를 바꾸지 않으므로 다음 단계는 이 단계 적용 후에 확인할 수 있다)");
				return;
			}

			step("[" + stage.name() + "] rewriteRun " + recipeLabel);
			Set<String> untrackedBefore = project.git() ? git.untracked() : Set.of();
			String treeBefore = project.git() ? git.snapshotTree(ws.createdFiles(), ws.file(".index-tmp")) : null;
			if (!gradle.rewrite(ws.file(tag + ".rewrite.log"), "rewriteRun", generated.name(), this.paths.rewriteInit(),
					this.paths.recipeLibs(), generated.file())) {
				throw failure("rewriteRun 실패 → " + ws.file(tag + ".rewrite.log"));
			}
			this.logger.lifecycle("   Boot {} / Gradle {}", this.inspector.bootVersion(projectDir),
					orQ(this.inspector.gradleVersion(projectDir)));
			if (project.git()) {
				// rewriteRun 이 새로 만든 파일만 기록한다. 이후 빌드/테스트가 만든 파일은 patch / 커밋에 넣지 않는다
				Set<String> created = git.untracked();
				created.removeAll(untrackedBefore);
				ws.addCreatedFiles(created);
			}

			Path stageVersions = ws.file(tag + ".versions.txt");
			GateResult gate = runGate(gradle, ws, tag, request.gate(), stage.name(), stageVersions);
			writeJson(ws.file(tag + ".issues.json"), issuesJson(stage, previousVersions, stageVersions));
			reportStage(ws, projectName, projectRecipes, stage.name(), tag, previousVersions, gate, request.gate());

			// 이 단계에서만 바뀐 diff (HTML 리포트의 변경 파일 보기). 누적 변경은 아래 .patch
			String treeAfter = (treeBefore != null) ? git.snapshotTree(ws.createdFiles(), ws.file(".index-tmp")) : null;
			if (treeAfter != null) {
				git.diffTrees(treeBefore, treeAfter, ws.file(tag + ".stage.patch"));
				HtmlReport.write(ws, projectName, ws.startBoot().orElse(project.bootVersion()),
						this.inspector.bootVersion(projectDir));
			}
			if (baseRev != null
					&& !git.diffSince(baseRev, ws.file(tag + ".patch"), ws.createdFiles(), ws.file(".index-tmp"))) {
				fail("patch 스냅샷 실패: " + ws.file(tag + ".patch"));
			}
			ws.appendSummary(projectName, ws.summaryRow(stage.name(), tag));
			if (stageVersions.toFile().exists()) {
				previousVersions = stageVersions;
			}

			if (!gate.passed()) {
				// 깨진 상태로 다음 단계로 가거나 커밋하지 않는다. 같은 명령을 다시 실행하면 이 단계부터 이어서 한다
				ws.appendSummary(projectName,
						"\n" + stage.name() + " 단계에서 " + gate.describe() + "로 중단했다 (" + tag + ".md)\n\n");
				if (project.git()) {
					ws.writeResume(new Resume(stage.name(), tag, lastTag, gate.compileOk() ? "build" : "compile"));
					ws.recordUntrackedAtStop(git.untracked());
				}
				if (!gate.compileOk()) {
					throw failure("[" + stage.name() + "] 컴파일 실패. 에러는 " + ws.file(tag + ".compile.log") + "\n"
							+ "   같은 명령을 다시 실행하면, 에러를 고쳐 두었으면 이 단계의 테스트/빌드 검증을 이어서 하고\n" + "   그대로면 " + stage.name()
							+ " 단계 전 상태로 되돌려 " + stage.name() + " 단계를 다시 시도한다.");
				}
				throw failure("[" + stage.name() + "] " + gate.describe() + ". 리포트는 "
						+ ws.file(HtmlReport.FILE_NAME).toUri() + "\n" + "   고친 뒤 같은 명령을 다시 실행하면 이 단계 검증부터 다시 하고, 통과하면 "
						+ (request.commit() ? "커밋하고 " : "") + "다음 단계로 간다. 테스트 결과와 무관하게 진행하려면 --gate=compile");
			}

			lastTag = tag;
			if (request.commit()) {
				commitStage(git, ws, stage.name(), "(OpenRewrite " + stage.recipe() + ")", tag);
			}
		}

		String finalBoot = this.inspector.bootVersion(projectDir);
		ws.appendSummary(projectName, "\nBoot " + project.bootVersion() + " → " + finalBoot + " 완료\n\n");
		step("완료: Boot " + project.bootVersion() + " → " + finalBoot);
		this.logger.lifecycle("   리포트      : {}", ws.file(HtmlReport.FILE_NAME).toUri());
		this.logger.lifecycle("   기록        : {}", ws.file("SUMMARY.md"));
		this.logger.lifecycle("   리포트/패치 : {}", ws.dir());
		this.logger.lifecycle("   다음 할 일  :");
		this.logger.lifecycle("     1) 각 단계 리포트의 '설정 키 변경' / '제거 예정 API' / '수동 검토 대상' 확인");
		this.logger.lifecycle("     2) 개발/스테이징 배포 후 기동 로그에서 \"The use of configuration keys that\" 검색 (외부 설정 저장소 확인)");
		this.logger.lifecycle("     3) 정리가 끝나면 spring-boot-properties-migrator 의존성 제거");
		if (request.commit()) {
			this.logger.lifecycle("   커밋       : {}", git.recentCommits(plan.stages().size()));
		}
	}

	/**
	 * 멈춘 단계부터 이어서 한다. 컴파일 실패로 멈췄다면 컴파일을 다시 확인하고, 고쳐 두었으면 테스트/빌드 게이트까지 이어서 확인한다. 그대로면 그 단계
	 * 전 상태로 되돌린다. 테스트/빌드 실패로 멈췄다면 게이트를 다시 확인한다. 통과해야 커밋하고 다음 단계로 간다.
	 */
	private Resumed resume(MigrationRequest request, MigrationWorkspace ws, Git git, BuildTool gradle,
			String projectName, ProjectRecipes projectRecipes) {
		Optional<Resume> saved = ws.readResume();
		if (saved.isEmpty()) {
			return Resumed.NONE;
		}
		Resume r = saved.get();
		boolean compileStopped = r.reason().equals("compile");
		if (compileStopped) {
			step("[재개] 지난 실행이 " + r.stage() + " 단계 컴파일 실패로 멈췄다");
			if (!gradle.run(ws.file("00-resume-compile.log"), List.of("clean", "compileJava", "compileTestJava"))) {
				if (git.applyReverse(ws.file(r.tag() + ".patch"))
						&& (r.previousTag() == null || git.apply(ws.file(r.previousTag() + ".patch")))) {
					this.logger.lifecycle("   {} 단계 전 상태로 되돌렸다. {} 단계부터 다시 시도한다", r.stage(), r.stage());
					ws.clearResume();
					return new Resumed(true, Integer.parseInt(r.tag().substring(0, 2)) - 1, r.previousTag(),
							"- 재개해서 " + r.stage() + " 단계 전 상태로 되돌리고 다시 시도했다");
				}
				// 되돌리는 명령은 출력하지 않는다 (복사해서 실행하다 작업 내용을 지우는 일이 없도록)
				throw failure(r.stage() + " 단계 전 상태로 되돌리지 못했다 (실패 이후 소스가 바뀌었다). 컴파일 에러를 고친 뒤 다시 실행한다");
			}
			this.logger.lifecycle("   소스가 컴파일된다. 이 단계의 게이트({})를 이어서 확인한다", request.gate());
		}
		else {
			step("[재개] 지난 실행이 " + r.stage() + " 단계 테스트/빌드 실패로 멈췄다. 이 단계 검증부터 다시 한다");
		}
		// 고치며 새로 만든 파일도 이 단계의 변경으로 담는다. 멈출 때 이미 있던 파일(실패한 빌드의 산출물 등)은 빼고 그 뒤에 생긴 것만
		Set<String> fixedFiles = git.untracked();
		fixedFiles.removeAll(ws.untrackedAtStop());
		ws.addCreatedFiles(fixedFiles);
		GateResult gate = runResumeGate(gradle, ws, r.tag(), request.gate());
		Path previousVersions = (r.previousTag() != null && Files.exists(ws.file(r.previousTag() + ".versions.txt")))
				? ws.file(r.previousTag() + ".versions.txt") : ws.file("00-versions.txt");
		reportStage(ws, projectName, projectRecipes, r.stage(), r.tag(), previousVersions, gate, request.gate());
		HtmlReport.write(ws, projectName, ws.startBoot().orElse(null),
				this.inspector.bootVersion(ws.dir().getParent()));
		if (!gate.passed()) {
			ws.writeResume(new Resume(r.stage(), r.tag(), r.previousTag(), "build"));
			ws.recordUntrackedAtStop(git.untracked());
			throw failure("[" + r.stage() + "] 아직 " + gate.describe() + ". 고친 뒤 같은 명령을 다시 실행한다. 리포트는 "
					+ ws.file(HtmlReport.FILE_NAME).toUri());
		}
		this.logger.lifecycle("   통과. 다음 단계부터 이어서 진행한다");
		if (request.commit()) {
			commitStage(git, ws, r.stage(), "(재개, 수정 포함)", r.tag());
		}
		ws.clearResume();
		return new Resumed(true, null, r.tag(), "- 재개해서 " + r.stage() + " 단계를 고친 상태로 검증을 통과하고 이어서 진행했다");
	}

	/**
	 * compile (+deprecation/removal 경고) → build (전체 테스트 + 패키징). gate=none 이면 아무것도 하지 않는다.
	 */
	private GateResult runGate(BuildTool gradle, MigrationWorkspace ws, String tag, String gate, String stageName,
			Path stageVersions) {
		if (gate.equals("none")) {
			return GateResult.SKIPPED;
		}
		step("[" + stageName + "] compile (+deprecation/removal 경고 수집)");
		// clean: rewriteRun 이 컴파일하며 src/main/generated 에 만든 Q-class 와 APT 가 다시 충돌하지 않도록
		boolean compileOk = gradle.run(ws.file(tag + ".compile.log"), verifyArgs("clean", "compileJava",
				"compileTestJava", "migrationResolvedVersions", "-PmigrationVersionsOut=" + stageVersions));
		// 컴파일이 깨져도 버전 목록은 남긴다
		if (!stageVersions.toFile().exists()) {
			gradle.runQuietly(verifyArgs("migrationResolvedVersions", "-PmigrationVersionsOut=" + stageVersions));
		}
		if (!compileOk || !gate.equals("build")) {
			return new GateResult(compileOk, Outcome.SKIPPED, 0, Set.of());
		}
		step("[" + stageName + "] build (전체 테스트 + 패키징, properties-migrator 경고 수집)");
		return runBuild(gradle, ws, tag, List.of("build", "--continue"));
	}

	/** 재개 때는 선택한 게이트를 다시 확인한다. compile 게이트는 재개 전에 이미 컴파일을 확인했다. */
	private GateResult runResumeGate(BuildTool gradle, MigrationWorkspace ws, String tag, String gate) {
		if (!gate.equals("build")) {
			return GateResult.SKIPPED;
		}
		step("[재개] build (전체 테스트 + 패키징)");
		return runBuild(gradle, ws, tag, List.of("clean", "build", "--continue"));
	}

	/**
	 * 테스트는 ignoreFailures 로 끝까지 돌리고 결과 XML 로 센다. --continue 로 실패한 태스크를 모두 모은 뒤 원본에서도 실패하던
	 * 태스크만 실패했으면 막지 않는다. 로그에서 실패 태스크를 찾지 못한 실패는 원인을 모르므로 막는다.
	 */
	private GateResult runBuild(BuildTool gradle, MigrationWorkspace ws, String tag, List<String> args) {
		Path log = ws.file(tag + ".test.log");
		boolean built = gradle.run(log, verifyArgs(args.toArray(String[]::new)));
		TestResults.Summary tests = TestResults.collect(ws.dir().getParent());
		Set<String> failedTests = TestResults.failedTests(ws.dir().getParent());
		int existing = failedTests.size();
		failedTests.removeAll(ws.baselineFailedTests());
		existing -= failedTests.size();
		this.logger.lifecycle("   테스트 {}개, 실패 {}개{}", tests.total(), failedTests.size(),
				(existing > 0) ? " (원본에서도 실패하던 " + existing + "개 제외)" : "");
		Set<String> newFailures = new TreeSet<>();
		if (!built) {
			Set<String> failed = failedTasks(log);
			if (failed.isEmpty()) {
				newFailures.add("(로그에서 실패 태스크를 찾지 못함)");
			}
			else {
				failed.removeAll(ws.baselineFailedTasks());
				newFailures.addAll(failed);
			}
			if (newFailures.isEmpty()) {
				this.logger.lifecycle("   빌드 실패: 원본에서도 실패하던 태스크만 실패했다 ({})", ws.file("00-baseline-build.log"));
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
	private void baselineBuild(BuildTool gradle, MigrationWorkspace ws, String projectName) {
		Path log = ws.file("00-baseline-build.log");
		boolean built = gradle.run(log, verifyArgs("clean", "build", "--continue"));
		if (!built && MigrationWorkspace.countMatches(log,
				"Execution failed for task '[^']*:compile(Test)?(Java|Groovy|Kotlin)'") > 0) {
			throw failure("현재 소스가 컴파일되지 않는다. 컴파일 에러를 고친 뒤 다시 실행한다 → " + log);
		}
		Set<String> failedTasks = built ? Set.of() : failedTasks(log);
		Set<String> failedTests = TestResults.failedTests(ws.dir().getParent());
		ws.writeBaselineFailedTasks(failedTasks);
		ws.writeBaselineFailedTests(failedTests);
		if (!failedTasks.isEmpty()) {
			this.logger.lifecycle("   원본 빌드에서도 실패하는 태스크 (기존 문제, 컴파일은 통과) {} → {}", failedTasks, log);
			ws.appendSummary(projectName,
					"- 원본 빌드에서도 실패하는 태스크 (기존 문제, 마이그레이션 무관) " + failedTasks + ", 00-baseline-build.log\n");
		}
		if (!failedTests.isEmpty()) {
			this.logger.lifecycle("   원본에서도 실패하는 테스트 {}개 (단계를 막지 않는다) → {}", failedTests.size(),
					ws.file("00-baseline-failed-tests.txt"));
			ws.appendSummary(projectName,
					"- 원본에서도 실패하는 테스트 " + failedTests.size() + "개 (기존 문제, 단계를 막지 않는다), 00-baseline-failed-tests.txt\n");
		}
	}

	/** 단계 리포트(md, json)를 만들고 HTML 리포트를 갱신한다. */
	@SuppressWarnings("unchecked")
	private void reportStage(MigrationWorkspace ws, String projectName, ProjectRecipes projectRecipes, String stageName,
			String tag, Path previousVersions, GateResult gate, String gateOption) {
		Map<String, Object> issues = Files.exists(ws.file(tag + ".issues.json"))
				? new Yaml().load(MigrationWorkspace.read(ws.file(tag + ".issues.json"))) : Map.of();
		Outcome compile = gateOption.equals("none") ? Outcome.SKIPPED : Outcome.of(gate.compileOk());
		// 원본에서도 실패하던 태스크만 실패했으면 기존 문제로 표시한다
		boolean buildFailureExisting = gate.build() == Outcome.FAILED && !gate.buildBlocking();
		List<Map<String, Object>> matchedIssues = (List<Map<String, Object>>) issues.getOrDefault("issues", List.of());
		Set<String> projectRecipeNames = Set
			.copyOf(projectRecipes.recipes().stream().map(ProjectRecipe::name).toList());
		StageReport.Input input = new StageReport.Input(stageName, ws.dir().getParent(), ws.file(tag + ".compile.log"),
				ws.file(tag + ".rewrite.log"), ws.file("00-scan.find.patch"), previousVersions,
				ws.file(tag + ".versions.txt"), compile, gate.build(), buildFailureExisting, matchedIssues,
				(String) issues.get("guide"), this.knownIssues.failureHints(), projectRecipeNames,
				ws.baselineFailedTests());
		StageReport.write(input, ws.file(tag + ".md"), ws.file(tag + ".report.json"));
		ws.reportHead(tag).forEach(this.logger::lifecycle);
		Path html = HtmlReport.write(ws, projectName, ws.startBoot().orElse(null),
				this.inspector.bootVersion(ws.dir().getParent()));
		this.logger.lifecycle("   리포트: {}", html.toUri());
		this.logger.lifecycle("           {} (마크다운)", ws.file(tag + ".md"));
	}

	/** 추적 중인 파일의 변경과 레시피가 만든 파일만 커밋한다. */
	private void commitStage(Git git, MigrationWorkspace ws, String stageName, String detail, String tag) {
		if (git.commit(ws.createdFiles(), "chore: Spring Boot " + stageName + " 마이그레이션 " + detail,
				"- 리포트는 " + MigrationWorkspace.DIR_NAME + "/" + tag + ".md")) {
			this.logger.lifecycle("   commit: {}", git.lastCommit());
		}
		else {
			fail("[" + stageName + "] commit 실패");
		}
	}

	/** 단계 전후 resolve 된 버전으로 알려진 이슈를 고른다. 리포트는 이 결과를 그리기만 한다. */
	private Map<String, Object> issuesJson(Stage stage, Path beforeVersions, Path afterVersions) {
		List<Object> items = new ArrayList<>();
		for (Match match : this.knownIssues.match(stage.issueKey(), readVersions(beforeVersions),
				readVersions(afterVersions))) {
			Issue issue = match.issue();
			Map<String, Object> item = new LinkedHashMap<>();
			item.put("id", issue.id());
			item.put("mode", issue.mode().name());
			item.put("title", issue.title());
			item.put("detail", issue.detail());
			item.put("source", issue.source());
			item.put("fix", issue.fix());
			item.put("trigger", match.trigger());
			items.add(item);
		}
		Map<String, Object> json = new LinkedHashMap<>();
		json.put("stage", stage.name());
		json.put("guide", this.knownIssues.guide(stage.issueKey()));
		json.put("issues", items);
		return json;
	}

	private static Map<String, String> readVersions(Path file) {
		Map<String, String> versions = new LinkedHashMap<>();
		MigrationWorkspace.read(file).lines().forEach((line) -> {
			int eq = line.indexOf('=');
			if (eq > 0) {
				versions.put(line.substring(0, eq), line.substring(eq + 1));
			}
		});
		return versions;
	}

	private static void writeJson(Path file, Object value) {
		try {
			Files.writeString(file, Json.write(value));
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	private static ProjectRecipes projectRecipes(MigrationRequest request) {
		// upstream-only 는 upstream 만의 결과를 비교하는 모드라 프로젝트 레시피도 붙이지 않는다
		return (request.skipProjectRecipes() || request.upstreamOnly()) ? ProjectRecipes.none()
				: ProjectRecipes.discover(request.projectDir());
	}

	private static String projectRecipeSuffix(ProjectRecipes projectRecipes, Stage stage) {
		List<String> before = projectRecipes.names(stage.issueKey(), Phase.BEFORE);
		List<String> after = projectRecipes.names(stage.issueKey(), Phase.AFTER);
		if (before.isEmpty() && after.isEmpty()) {
			return "";
		}
		StringBuilder out = new StringBuilder("  + 프로젝트");
		if (!before.isEmpty()) {
			out.append(" before ").append(before);
		}
		if (!after.isEmpty()) {
			out.append(" after ").append(after);
		}
		return out.toString();
	}

	private void printProjectRecipes(Path projectDir, ProjectRecipes projectRecipes) {
		if (projectRecipes.files().isEmpty()) {
			return;
		}
		step("프로젝트 레시피 ("
				+ String.join(", ",
						projectRecipes.files().stream().map((f) -> projectDir.relativize(f).toString()).toList())
				+ ")");
		if (projectRecipes.recipes().isEmpty()) {
			this.logger.lifecycle("   migration-stage 태그가 붙은 레시피가 없다 (태그가 없는 레시피는 다른 레시피가 참조할 때만 쓰인다)");
		}
		for (ProjectRecipe recipe : projectRecipes.recipes()) {
			this.logger.lifecycle("   {}  단계 {} / {}", recipe.name(), recipe.stages(),
					(recipe.phase() == Phase.BEFORE) ? "before" : "after");
		}
	}

	private void printTargetLine(MigrationPlan plan) {
		var line = plan.targetLine();
		this.logger.lifecycle(
				"   호환성   : Java {} ~ {} / Gradle {} / Spring Framework {} / Spring Cloud {} ({}+) / Spring Cloud AWS {}",
				line.javaMin(), line.javaMax(), line.gradleRange(), line.framework(), line.springCloudTrain(),
				line.springCloudSince(), line.springCloudAws());
	}

	private void printNotes(MigrationPlan plan) {
		plan.notes().forEach((n) -> this.logger.lifecycle("   참고     : {}", n));
	}

	private BuildTool targetGradle(ProjectModel project, boolean keepJavaHome) {
		String javaHome = keepJavaHome ? System.getenv("JAVA_HOME")
				: new JdkLocator().javaHomeFor(project.toolchainJava());
		return this.buildTools.create(project.dir(), javaHome);
	}

	private MigrationPlan planOrFail(ProjectModel project, MigrationRequest request) {
		try {
			return this.planner.plan(project, request);
		}
		catch (IllegalArgumentException ex) {
			throw failure(ex.getMessage());
		}
	}

	private boolean resolvedVersions(BuildTool gradle, Path log, Path out) {
		return gradle.run(log, verifyArgs("migrationResolvedVersions", "-PmigrationVersionsOut=" + out));
	}

	private boolean scan(Path projectDir, BuildTool gradle, Path log, Path findPatch) {
		boolean ok = gradle.rewrite(log, "rewriteDryRun", "com.eottabom.rewrite.FindManualMigrationItems",
				this.paths.rewriteInit(), this.paths.recipeLibs());
		if (ok) {
			MigrationWorkspace.copyOrEmpty(rewritePatch(projectDir), findPatch);
		}
		else {
			MigrationWorkspace.writeEmpty(findPatch);
		}
		return ok;
	}

	private List<String> verifyArgs(String... args) {
		List<String> all = new ArrayList<>(List.of("--init-script", this.paths.verifyInit().toString()));
		all.addAll(List.of(args));
		return all;
	}

	private static Path rewritePatch(Path projectDir) {
		return projectDir.resolve("build/reports/rewrite/rewrite.patch");
	}

	private void printProject(ProjectModel project, BuildTool gradle) {
		step("프로젝트 : " + project.dir());
		this.logger.lifecycle("   Boot     : {}", orQ(project.bootVersion()));
		this.logger.lifecycle("   Gradle   : {}", orQ(project.gradleVersion()));
		this.logger.lifecycle("   Java     : {}", orQ(project.javaVersion()));
		this.logger.lifecycle("   JAVA_HOME: {}", orDefault(gradle.javaHome()));
		this.logger.lifecycle("   git      : {}", !project.git() ? "아님" : project.dirty() ? "커밋되지 않은 변경 있음" : "깨끗함");
	}

	private static String options(MigrationRequest request, MigrationPlan plan) {
		StringBuilder opts = new StringBuilder("gate=").append(request.gate());
		if (request.commit()) {
			opts.append(", commit");
		}
		if (request.dryRun()) {
			opts.append(", preview");
		}
		if (request.oneShot()) {
			opts.append(", one-shot");
		}
		if (plan.targetJava() != null) {
			opts.append(", java=").append(plan.targetJava());
		}
		if (request.upstreamOnly()) {
			opts.append(", upstream-only");
		}
		if (request.allowDirty()) {
			opts.append(", allow-dirty");
		}
		return opts.toString();
	}

	private static void checkGate(String gate) {
		if (!GATES.contains(gate)) {
			throw new GradleException("--gate 는 compile | build | none");
		}
	}

	private void step(String message) {
		this.logger.lifecycle("");
		this.logger.lifecycle(">> {}", message);
	}

	private void fail(String message) {
		this.logger.error("!! {}", message);
	}

	private static GradleException failure(String message) {
		return new GradleException(message);
	}

	private static String orQ(Object value) {
		return (value != null) ? value.toString() : "?";
	}

	private static String orDefault(String value) {
		return (value != null) ? value : "default";
	}

	/**
	 * @param rewriteInit init/rewrite.init.gradle
	 * @param verifyInit init/verify.init.gradle
	 * @param recipeLibs build/recipe-libs (레시피 jar + upstream 레시피 모듈)
	 * @param playbookDir playbook/ (compatibility.yml, known-issues.yml)
	 */
	public record RunnerPaths(Path rewriteInit, Path verifyInit, Path recipeLibs, Path playbookDir) {
	}

	/**
	 * 재개 결과.
	 *
	 * @param active 재개했다 (남아 있는 변경은 이 마이그레이션의 변경이므로 작업 트리 검사를 하지 않는다)
	 * @param retryFrom 멈춘 단계를 다시 시도하는 경우 그 단계 번호 - 1, 이어서 가는 경우 null
	 * @param lastTag 마지막으로 통과한 단계의 태그
	 */
	record Resumed(boolean active, Integer retryFrom, String lastTag, String note) {
		static final Resumed NONE = new Resumed(false, null, null, null);

	}

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

}
