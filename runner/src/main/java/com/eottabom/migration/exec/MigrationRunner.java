package com.eottabom.migration.exec;

import com.eottabom.migration.exec.MigrationWorkspace.Resume;
import com.eottabom.migration.inspect.JdkLocator;
import com.eottabom.migration.inspect.ProjectInspector;
import com.eottabom.migration.knowledge.Compatibility;
import com.eottabom.migration.knowledge.KnownIssues;
import com.eottabom.migration.knowledge.KnownIssues.Issue;
import com.eottabom.migration.knowledge.KnownIssues.Match;
import com.eottabom.migration.knowledge.KnownIssues.Mode;
import com.eottabom.migration.model.MigrationPlan;
import com.eottabom.migration.model.MigrationRequest;
import com.eottabom.migration.model.ProjectModel;
import com.eottabom.migration.model.Stage;
import com.eottabom.migration.plan.MigrationPlanner;
import com.eottabom.migration.recipe.GeneratedRecipe;
import com.eottabom.migration.recipe.GeneratedRecipe.Generated;
import com.eottabom.migration.recipe.ProjectRecipes;
import com.eottabom.migration.recipe.ProjectRecipes.Phase;
import com.eottabom.migration.recipe.ProjectRecipes.ProjectRecipe;
import org.gradle.api.GradleException;
import org.gradle.api.logging.Logger;

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

/**
 * 단계 루프: 현재 버전의 다음 단계부터 목표까지 단계마다 rewriteRun → compile → build → 리포트 → (commit).
 *
 * 컴파일이 깨지면 그 단계에서 멈춘다. 같은 명령을 다시 실행하면
 *  - 컴파일 에러를 고쳐 두었으면 다음 단계부터 이어서 진행하고
 *  - 그대로면 단계별 누적 patch 로 그 단계 전 상태를 만들어 그 단계부터 다시 시도한다.
 */
public final class MigrationRunner {

    private static final List<String> GATES = List.of("compile", "build", "none");

    private final RunnerPaths paths;
    private final Logger logger;
    private final ProjectInspector inspector = new ProjectInspector();
    private final MigrationPlanner planner;
    private final KnownIssues knownIssues;

    /**
     * @param rewriteInit  init/rewrite.init.gradle
     * @param verifyInit   init/verify.init.gradle
     * @param recipeLibs   build/recipe-libs (레시피 jar + upstream 레시피 모듈)
     * @param knowledgeDir knowledge/ (compatibility.yml, known-issues.yml)
     */
    public record RunnerPaths(Path rewriteInit, Path verifyInit, Path recipeLibs, Path knowledgeDir) {
    }

    /** 대상 Gradle 데몬 JVM 옵션 (--gradle-jvmargs). null 이면 장비 메모리 기준 기본값 */
    private final String gradleJvmArgs;

    public MigrationRunner(RunnerPaths paths, String gradleJvmArgs, Logger logger) {
        this.paths = paths;
        this.gradleJvmArgs = gradleJvmArgs;
        this.logger = logger;
        this.planner = new MigrationPlanner(Compatibility.load(paths.knowledgeDir().resolve("compatibility.yml")));
        this.knownIssues = KnownIssues.load(paths.knowledgeDir().resolve("known-issues.yml"));
    }

    // ── migrationAnalyze ─────────────────────────────────────────────────────────────────────────────────────────────

    /** 현재 상태, resolve 된 의존성, 수동 검토 대상 위치. 소스는 바꾸지 않는다. */
    public void analyze(Path projectDir, boolean keepJavaHome) {
        ProjectModel project = inspector.inspect(projectDir);
        TargetGradle gradle = targetGradle(project, keepJavaHome);
        MigrationWorkspace ws = new MigrationWorkspace(projectDir);
        printProject(project, gradle);

        printProjectRecipes(projectDir, ProjectRecipes.discover(projectDir));

        step("[분석] 의존성 버전");
        Path versions = ws.file("analyze.versions.txt");
        if (resolvedVersions(gradle, ws.file("analyze.versions.log"), versions)) {
            logger.lifecycle("   의존성 {}개 → {}", MigrationWorkspace.countMatches(versions, "."), versions);
        } else {
            fail("의존성 버전 수집 실패 → " + ws.file("analyze.versions.log"));
        }

        step("[분석] 수동 검토 대상 (FindManualMigrationItems)");
        Path findPatch = ws.file("analyze.find.patch");
        if (scan(projectDir, gradle, ws.file("analyze.scan.log"), findPatch)) {
            logger.lifecycle("   {} 곳 → {}", MigrationWorkspace.countMatches(findPatch, "~~>"), findPatch);
        } else {
            fail("스캔 실패 → " + ws.file("analyze.scan.log"));
        }
    }

    // ── migrationPlan ────────────────────────────────────────────────────────────────────────────────────────────────

    /** 실행할 단계만 보여준다. 대상 프로젝트의 Gradle 을 띄우지 않는다. */
    public MigrationPlan plan(MigrationRequest request) {
        ProjectModel project = inspector.inspect(request.projectDir());
        MigrationPlan plan = planOrFail(project, request);
        step("프로젝트 : " + project.dir());
        logger.lifecycle("   현재     : Boot {} / Gradle {} / Java {}", project.bootVersion(), orQ(project.gradleVersion()), orQ(project.javaVersion()));
        logger.lifecycle("   목표     : Boot {} / Java {}", plan.targetBoot(), plan.targetJava() == null ? "유지" : plan.targetJava());
        printTargetLine(plan);
        printNotes(plan);
        if (plan.isEmpty()) {
            logger.lifecycle("   단계     : 없음 (이미 목표 이상)");
            return plan;
        }
        ProjectRecipes projectRecipes = projectRecipes(request);
        logger.lifecycle("   단계     :");
        for (Stage stage : plan.stages()) {
            logger.lifecycle("     {}  {}{}", String.format("%-11s", stage.name()), stage.recipe(), projectRecipeSuffix(projectRecipes, stage));
        }
        printProjectRecipes(request.projectDir(), projectRecipes);
        step("알려진 이슈 미리보기 (knowledge/known-issues.yml, 의존성 조건은 실행 때 판단)");
        Map<Mode, Integer> total = new EnumMap<>(Mode.class);
        for (Stage stage : plan.stages()) {
            List<Issue> issues = knownIssues.forStage(stage.issueKey());
            if (issues.isEmpty()) {
                continue;
            }
            logger.lifecycle("   [{}]", stage.name());
            for (Mode mode : List.of(Mode.REPORT_ONLY, Mode.REVIEW_REQUIRED, Mode.AUTO_FIX)) {
                for (Issue issue : issues) {
                    if (issue.mode() == mode) {
                        total.merge(mode, 1, Integer::sum);
                        String when = issue.requires().isEmpty() ? "" : "  (" + String.join(", ", issue.requires()) + " 사용 시)";
                        logger.lifecycle("     {}  {}{}", String.format("%-15s", mode), issue.title(), when);
                    }
                }
            }
        }
        logger.lifecycle("   합계: REPORT_ONLY {} / REVIEW_REQUIRED {} / AUTO_FIX {}",
                total.getOrDefault(Mode.REPORT_ONLY, 0), total.getOrDefault(Mode.REVIEW_REQUIRED, 0), total.getOrDefault(Mode.AUTO_FIX, 0));
        return plan;
    }

    // ── migrationVerify ──────────────────────────────────────────────────────────────────────────────────────────────

    /** 현재 소스의 컴파일(+제거 예정 API 경고)과 build(전체 테스트 + 패키징). 소스는 바꾸지 않는다. */
    public void verify(Path projectDir, String gate, boolean keepJavaHome) {
        checkGate(gate);
        ProjectModel project = inspector.inspect(projectDir);
        TargetGradle gradle = targetGradle(project, keepJavaHome);
        MigrationWorkspace ws = new MigrationWorkspace(projectDir);
        printProject(project, gradle);
        if (gate.equals("none")) {
            return;
        }
        step("[검증] compile (+deprecation/removal 경고 수집)");
        Path compileLog = ws.file("verify.compile.log");
        if (!gradle.run(compileLog, verifyArgs("clean", "compileJava", "compileTestJava"))) {
            throw failure("컴파일 실패 → " + compileLog);
        }
        logger.lifecycle("   [removal] 경고 {}건, [deprecation] 경고 {}건 → {}",
                MigrationWorkspace.countMatches(compileLog, "\\[removal\\]"),
                MigrationWorkspace.countMatches(compileLog, "\\[deprecation\\]"), compileLog);
        if (gate.equals("build")) {
            step("[검증] build (전체 테스트 + 패키징)");
            Path buildLog = ws.file("verify.build.log");
            boolean built = gradle.run(buildLog, verifyArgs("build"));
            TestResults.Summary tests = TestResults.collect(projectDir);
            logger.lifecycle("   테스트 {}개, 실패 {}개", tests.total(), tests.failed());
            if (!built) {
                throw failure("빌드 실패 (테스트 외: 패키징/플러그인/검사) → " + buildLog);
            }
            if (tests.failed() > 0) {
                throw failure("테스트 실패 " + tests.failed() + "개 → " + projectDir.resolve("build/reports/tests"));
            }
        }
    }

    // ── migrationRun ─────────────────────────────────────────────────────────────────────────────────────────────────

    public void run(MigrationRequest request) {
        checkGate(request.gate());
        Path projectDir = request.projectDir();
        MigrationWorkspace ws = new MigrationWorkspace(projectDir);
        Git git = new Git(projectDir);
        TargetGradle gradle = targetGradle(inspector.inspect(projectDir), request.keepJavaHome());

        // ── 재개: 지난 실행이 게이트(컴파일 / 테스트, 빌드) 실패로 멈춘 경우 ──
        Integer resumeFrom = null;
        String lastTag = null;
        String resumeNote = null;
        Optional<Resume> resume = request.dryRun() ? Optional.empty() : ws.readResume();
        if (resume.isPresent() && resume.get().reason().equals("build")) {
            // 테스트/빌드 실패로 멈춘 단계: 고친 내용(새 파일 포함)을 그 단계의 변경으로 보고 검증부터 다시 한다. 되돌리지 않는다
            Resume r = resume.get();
            step("[재개] 지난 실행이 " + r.stage() + " 단계 테스트/빌드 실패로 멈췄다. 이 단계 검증부터 다시 한다");
            ws.addCreatedFiles(git.untracked());
            GateResult gate = runGate(gradle, ws, r.tag(), request.gate(), "skip");
            if (!gate.passed()) {
                ws.writeResume(r);
                throw failure("[" + r.stage() + "] 아직 " + gate.describe() + ". 고친 뒤 같은 명령을 다시 실행한다 → " + ws.file(r.tag() + ".test.log"));
            }
            logger.lifecycle("   통과. 다음 단계부터 이어서 진행한다");
            if (request.commit()) {
                commitStage(git, ws, r.stage(), "(재개: 테스트/빌드 수정 포함)", r.tag());
            }
            lastTag = r.tag();
            resumeNote = "- 재개: " + r.stage() + " 단계 테스트/빌드를 고친 상태에서 이어서 진행";
            ws.clearResume();
            resume = Optional.empty();
        } else if (resume.isPresent()) {
            Resume r = resume.get();
            step("[재개] 지난 실행이 " + r.stage() + " 단계 컴파일 실패로 멈췄다");
            if (gradle.run(ws.file("00-resume-compile.log"), List.of("clean", "compileJava", "compileTestJava"))) {
                // 고치며 새로 만든 파일도 이 마이그레이션의 변경으로 담는다 (시작할 때 작업 트리는 깨끗했다)
                ws.addCreatedFiles(git.untracked());
                logger.lifecycle("   소스가 컴파일된다. 다음 단계부터 이어서 진행한다");
                lastTag = r.tag();
                resumeNote = "- 재개: " + r.stage() + " 단계 컴파일 에러를 고친 상태에서 이어서 진행";
            } else if (git.applyReverse(ws.file(r.tag() + ".patch"))
                    && (r.previousTag() == null || git.apply(ws.file(r.previousTag() + ".patch")))) {
                logger.lifecycle("   {} 단계 전 상태로 되돌렸다. {} 단계부터 다시 시도한다", r.stage(), r.stage());
                resumeFrom = Integer.parseInt(r.tag().substring(0, 2)) - 1;
                lastTag = r.previousTag();
                resumeNote = "- 재개: " + r.stage() + " 단계 전 상태로 되돌리고 다시 시도";
            } else {
                // 되돌리는 명령은 출력하지 않는다 (복사해서 실행하다 작업 내용을 지우는 일이 없도록)
                throw failure(r.stage() + " 단계 전 상태로 되돌리지 못했다 (실패 이후 소스가 바뀌었다). 컴파일 에러를 고친 뒤 다시 실행한다");
            }
            ws.clearResume();
        }

        ProjectModel project = inspector.inspect(projectDir);
        MigrationPlan plan = planOrFail(project, request);
        String opts = options(request, plan);
        step("프로젝트 : " + projectDir);
        logger.lifecycle("   현재     : Boot {} / Gradle {} / JAVA_HOME={}", project.bootVersion(), orQ(project.gradleVersion()), orDefault(gradle.javaHome()));
        logger.lifecycle("   목표     : Boot {}  ({})", plan.targetBoot(), opts);

        ProjectRecipes projectRecipes = projectRecipes(request);
        printProjectRecipes(projectDir, projectRecipes);

        String baseRev = null;
        if (project.git()) {
            // 리포트 디렉토리와 생성 레시피는 git 에 올리지 않는다 (patch 스냅샷 / 커밋 대상에서 제외)
            git.exclude(MigrationWorkspace.DIR_NAME + "/");
            git.exclude(GeneratedRecipe.RELATIVE_PATH);
            baseRev = git.head();
        }
        if (request.commit()) {
            if (!project.git()) {
                throw failure("--commit 은 git 저장소에서만 쓸 수 있습니다");
            }
            if (project.dirty()) {
                throw failure("--commit 은 작업 트리가 깨끗해야 합니다 (기존 변경이 커밋에 섞인다)");
            }
        }
        // 자동 코드 변경이 기존 변경과 섞이지 않도록 새로 시작할 때는 깨끗한 작업 트리를 요구한다 (재개는 제외)
        if (project.dirty() && resume.isEmpty() && !request.dryRun() && !request.allowDirty()) {
            throw failure("작업 트리에 커밋되지 않은 변경이 있다. 커밋하거나 --allow-dirty 로 계속한다");
        }

        if (plan.isEmpty()) {
            step("이미 Boot " + project.bootVersion() + " (목표 " + plan.targetBoot() + " 이상) 입니다.");
            return;
        }
        logger.lifecycle("   Java     : {} -> {}", orQ(project.javaVersion()), plan.targetJava() == null ? "유지" : plan.targetJava());
        logger.lifecycle("   단계     : {}", plan.stageNames());
        printTargetLine(plan);
        printNotes(plan);

        String projectName = projectDir.getFileName().toString();
        StringBuilder header = new StringBuilder()
                .append("## ").append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))).append(" 실행\n\n")
                .append("- 시작: Boot ").append(project.bootVersion()).append(" / Gradle ").append(orQ(project.gradleVersion()))
                .append(" / Java ").append(orQ(project.javaVersion())).append('\n')
                .append("- 목표: Boot ").append(plan.targetBoot()).append(plan.targetJava() == null ? "" : " / Java " + plan.targetJava())
                .append(" (").append(opts).append(")\n")
                .append("- 단계: ").append(plan.stageNames()).append('\n');
        plan.notes().forEach(n -> header.append("- 참고: ").append(n).append('\n'));
        if (resumeNote != null) {
            header.append(resumeNote).append('\n');
        }
        ws.appendSummary(projectName, header.toString());

        // ── 스캔 ──
        step("[스캔] 의존성 버전 / 수동 검토 대상 탐지");
        Path previousVersions = ws.file("00-versions.txt");
        if (resolvedVersions(gradle, ws.file("00-versions.log"), previousVersions)) {
            logger.lifecycle("   의존성 {}개 → {}", MigrationWorkspace.countMatches(previousVersions, "."), previousVersions);
        }
        // 원본 빌드 상태: 원본에서도 빌드가 깨지는 프로젝트(ex. 루트 bootJar main class 없음)는 단계별 "빌드 실패" 를 기존 문제로 표시한다
        String baselineBuildOk = "skip";
        if (request.gate().equals("build") && !request.dryRun()) {
            Path baselineLog = ws.file("00-baseline-assemble.log");
            if (gradle.run(baselineLog, List.of("assemble", "-x", "test"))) {
                baselineBuildOk = "1";
            } else {
                baselineBuildOk = "0";
                // OpenRewrite 는 레시피 실행 전에 프로젝트를 컴파일하므로 컴파일이 깨진 소스에서는 아무 단계도 진행할 수 없다
                if (MigrationWorkspace.countMatches(baselineLog, "Execution failed for task '[^']*:compile(Test)?(Java|Groovy|Kotlin)'") > 0) {
                    throw failure("현재 소스가 컴파일되지 않는다. 컴파일 에러를 고친 뒤 다시 실행한다 → " + baselineLog);
                }
                logger.lifecycle("   원본 assemble 실패 (기존 문제, 컴파일은 통과) → {}", baselineLog);
                ws.appendSummary(projectName, "- 원본 assemble 실패 (기존 문제, 마이그레이션 무관): 00-baseline-assemble.log\n");
            }
        }
        Path findPatch = ws.file("00-scan.find.patch");
        if (scan(projectDir, gradle, ws.file("00-scan.log"), findPatch)) {
            logger.lifecycle("   {} 곳 → {}", MigrationWorkspace.countMatches(findPatch, "~~>"), findPatch);
        } else {
            // 스캔은 수동 검토 대상 위치를 표시하는 용도라 실패해도 마이그레이션은 계속한다
            fail("스캔 실패 (수동 검토 대상 표시만 빠지고 계속 진행한다) → " + ws.file("00-scan.log"));
            ws.appendSummary(projectName, "- 스캔 실패: 수동 검토 대상 표시 없음 (00-scan.log)\n");
        }
        ws.appendSummary(projectName, "\n| 단계 | 컴파일 | 테스트 | 빌드 | 자동 보정 | 수동 검토 | 알려진 이슈 | 리포트 |\n|---|---|---|---|---|---|---|---|\n");

        Path failureHints = ws.file("failure-hints.json");
        writeJson(failureHints, knownIssues.failureHints().stream()
                .map(h -> (Object) Map.of("pattern", h.pattern(), "text", h.text())).toList());

        // ── 단계 루프 ── 단계 번호는 지난 기록 뒤에 이어서 붙인다 (재개 시에는 다시 시도하는 단계 번호부터)
        int order = resumeFrom != null ? resumeFrom : ws.stageReportCount();
        for (Stage stage : plan.stages()) {
            order++;
            String tag = stage.tag(order);
            // 단계 레시피 + 프로젝트 레시피를 .rewrite/rewrite.generated.yml 로 만들고 그 레시피를 실행한다 (기록용 사본은 .rewrite-migration/)
            Generated generated = GeneratedRecipe.write(projectDir, projectName, stage, tag, projectRecipes);
            MigrationWorkspace.copyOrEmpty(generated.file(), ws.file(tag + ".generated.yml"));
            String recipeLabel = stage.recipe() + projectRecipeSuffix(projectRecipes, stage);

            if (request.dryRun()) {
                step("[" + stage.name() + "] " + recipeLabel + " (preview)");
                if (!gradle.rewrite(ws.file(tag + ".rewrite.log"), "rewriteDryRun", generated.name(), paths.rewriteInit(), paths.recipeLibs(), generated.file())) {
                    throw failure("preview 실패 → " + ws.file(tag + ".rewrite.log"));
                }
                Path dryPatch = ws.file(tag + ".dry.patch");
                if (MigrationWorkspace.copyOrEmpty(rewritePatch(projectDir), dryPatch)) {
                    logger.lifecycle("   {} files → {}", MigrationWorkspace.countMatches(dryPatch, "^diff --git"), dryPatch);
                } else {
                    logger.lifecycle("   변경 없음");
                }
                logger.lifecycle("   (preview 는 소스를 바꾸지 않으므로 다음 단계는 이 단계 적용 후에 확인할 수 있다)");
                return;
            }

            step("[" + stage.name() + "] rewriteRun " + recipeLabel);
            Set<String> untrackedBefore = project.git() ? git.untracked() : Set.of();
            String treeBefore = project.git() ? git.snapshotTree(ws.createdFiles(), ws.file(".index-tmp")) : null;
            if (!gradle.rewrite(ws.file(tag + ".rewrite.log"), "rewriteRun", generated.name(), paths.rewriteInit(), paths.recipeLibs(), generated.file())) {
                throw failure("rewriteRun 실패 → " + ws.file(tag + ".rewrite.log"));
            }
            logger.lifecycle("   Boot {} / Gradle {}", inspector.bootVersion(projectDir), orQ(inspector.gradleVersion(projectDir)));
            if (project.git()) {
                // rewriteRun 이 새로 만든 파일만 기록한다. 이후 빌드/테스트가 만든 파일은 patch / 커밋에 넣지 않는다
                Set<String> created = git.untracked();
                created.removeAll(untrackedBefore);
                ws.addCreatedFiles(created);
            }

            Path stageVersions = ws.file(tag + ".versions.txt");
            GateResult gate = runGate(gradle, ws, tag, request.gate(), baselineBuildOk, stage.name(), stageVersions);
            boolean compileOk = gate.compileOk();
            String buildOk = gate.buildOk();

            Path issues = ws.file(tag + ".issues.json");
            writeJson(issues, issuesJson(stage, previousVersions, stageVersions));
            boolean reported = gradle.run(ws.file(tag + ".report.log"), verifyArgs("migrationReport",
                    "-PmigrationStage=" + stage.name(),
                    "-PmigrationIssues=" + issues,
                    "-PmigrationFailureHints=" + failureHints,
                    "-PmigrationProjectRecipes=" + String.join(",", projectRecipes.recipes().stream().map(ProjectRecipe::name).toList()),
                    "-PmigrationCompileLog=" + ws.file(tag + ".compile.log"),
                    "-PmigrationFindPatch=" + findPatch,
                    "-PmigrationRewriteLog=" + ws.file(tag + ".rewrite.log"),
                    "-PmigrationCompileOk=" + (request.gate().equals("none") ? "skip" : compileOk ? "1" : "0"),
                    "-PmigrationBuildOk=" + buildOk,
                    "-PmigrationBaselineBuildOk=" + baselineBuildOk,
                    "-PmigrationVersionsBefore=" + previousVersions,
                    "-PmigrationVersionsAfter=" + stageVersions,
                    "-PmigrationReportOut=" + ws.file(tag + ".md"),
                    "-PmigrationReportJson=" + ws.file(tag + ".report.json")));
            if (!reported) {
                fail("리포트 생성 실패 → " + ws.file(tag + ".report.log"));
            }
            // 이 단계에서만 바뀐 diff (HTML 리포트의 변경 파일 보기). 누적 변경은 아래 .patch
            String treeAfter = treeBefore != null ? git.snapshotTree(ws.createdFiles(), ws.file(".index-tmp")) : null;
            if (treeAfter != null) {
                git.diffTrees(treeBefore, treeAfter, ws.file(tag + ".stage.patch"));
            }
            if (baseRev != null && !git.diffSince(baseRev, ws.file(tag + ".patch"), ws.createdFiles())) {
                fail("patch 스냅샷 실패: " + ws.file(tag + ".patch"));
            }
            ws.appendSummary(projectName, ws.summaryRow(stage.name(), tag));
            if (stageVersions.toFile().exists()) {
                previousVersions = stageVersions;
            }
            ws.reportHead(tag).forEach(logger::lifecycle);
            Path html = HtmlReport.write(ws, projectName, project.bootVersion(), inspector.bootVersion(projectDir));
            logger.lifecycle("   리포트: {}", html.toUri());
            logger.lifecycle("           {} (마크다운)", ws.file(tag + ".md"));

            if (!compileOk) {
                ws.appendSummary(projectName, "\n결과: " + stage.name() + " 단계 컴파일 실패로 중단 (에러: " + tag + ".compile.log)\n\n");
                if (project.git()) {
                    ws.writeResume(new Resume(stage.name(), tag, lastTag, "compile"));
                }
                throw failure("[" + stage.name() + "] 컴파일 실패. 에러: " + ws.file(tag + ".compile.log") + "\n"
                        + "   같은 명령을 다시 실행하면, 에러를 고쳐 두었으면 다음 단계부터 이어서 하고\n"
                        + "   그대로면 " + stage.name() + " 단계 전 상태로 되돌려 " + stage.name() + " 단계를 다시 시도한다.");
            }
            if (buildOk.equals("0") && baselineBuildOk.equals("0")) {
                logger.lifecycle("   빌드 실패: 원본에서도 실패하는 기존 문제 ({})", ws.file("00-baseline-assemble.log"));
            }
            if (!gate.passed()) {
                // 테스트가 깨졌거나 이 단계에서 빌드가 깨졌다. 깨진 상태로 다음 단계로 가거나 커밋하지 않는다
                ws.appendSummary(projectName, "\n결과: " + stage.name() + " 단계 " + gate.describe() + "로 중단 (" + tag + ".md)\n\n");
                if (project.git()) {
                    ws.writeResume(new Resume(stage.name(), tag, lastTag, "build"));
                }
                throw failure("[" + stage.name() + "] " + gate.describe() + ". 리포트: " + ws.file(tag + ".md") + "\n"
                        + "   고친 뒤 같은 명령을 다시 실행하면 이 단계 검증부터 다시 하고, 통과하면 " + (request.commit() ? "커밋하고 " : "")
                        + "다음 단계로 간다. 테스트 결과와 무관하게 진행하려면 --gate=compile");
            }

            lastTag = tag;

            if (request.commit()) {
                commitStage(git, ws, stage.name(), "(OpenRewrite " + stage.recipe() + ")", tag);
            }
        }

        // ── 요약 ──
        String finalBoot = inspector.bootVersion(projectDir);
        ws.appendSummary(projectName, "\n결과: 완료, Boot " + project.bootVersion() + " → " + finalBoot + "\n\n");
        step("완료: Boot " + project.bootVersion() + " → " + finalBoot);
        logger.lifecycle("   리포트      : {}", ws.file(HtmlReport.FILE_NAME).toUri());
        logger.lifecycle("   기록        : {}", ws.file("SUMMARY.md"));
        logger.lifecycle("   리포트/패치 : {}", ws.dir());
        logger.lifecycle("   다음 할 일  :");
        logger.lifecycle("     1) 각 단계 리포트의 '설정 키 변경' / '제거 예정 API' / '수동 검토 대상' 확인");
        logger.lifecycle("     2) 개발/스테이징 배포 후 기동 로그에서 \"The use of configuration keys that\" 검색 (외부 설정 저장소 확인)");
        logger.lifecycle("     3) 정리가 끝나면 spring-boot-properties-migrator 의존성 제거");
        if (request.commit()) {
            logger.lifecycle("   커밋       : {}", git.recentCommits(plan.stages().size()));
        }
    }

    // ── 공통 ─────────────────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * 게이트 결과.
     *
     * @param buildOk      1 | 0 | skip
     * @param failedTests  build 게이트에서 실패한 테스트 수
     * @param buildBlocking 이 단계에서 새로 깨진 빌드 (원본에서도 깨졌던 빌드는 막지 않는다)
     */
    private record GateResult(boolean compileOk, String buildOk, int failedTests, boolean buildBlocking) {

        boolean passed() {
            return compileOk && failedTests == 0 && !buildBlocking;
        }

        String describe() {
            if (!compileOk) return "컴파일 실패";
            if (failedTests > 0 && buildBlocking) return "테스트 " + failedTests + "개 실패, 빌드 실패";
            if (failedTests > 0) return "테스트 " + failedTests + "개 실패";
            return "빌드 실패 (테스트 외: 패키징/플러그인/검사)";
        }
    }

    /** 재개 때 테스트/빌드만 다시 확인한다. */
    private GateResult runGate(TargetGradle gradle, MigrationWorkspace ws, String tag, String gate, String baselineBuildOk) {
        if (!gate.equals("build")) {
            return new GateResult(true, "skip", 0, false);
        }
        boolean built = gradle.run(ws.file(tag + ".test.log"), verifyArgs("clean", "build"));
        int failed = TestResults.collect(ws.dir().getParent()).failed();
        return new GateResult(true, built ? "1" : "0", failed, !built && !baselineBuildOk.equals("0"));
    }

    /** compile (+deprecation/removal 경고) → build (전체 테스트 + 패키징). gate=none 이면 아무것도 하지 않는다. */
    private GateResult runGate(TargetGradle gradle, MigrationWorkspace ws, String tag, String gate, String baselineBuildOk,
                               String stageName, Path stageVersions) {
        if (gate.equals("none")) {
            return new GateResult(true, "skip", 0, false);
        }
        step("[" + stageName + "] compile (+deprecation/removal 경고 수집)");
        // clean: rewriteRun 이 컴파일하며 src/main/generated 에 만든 Q-class 와 APT 가 다시 충돌하지 않도록
        boolean compileOk = gradle.run(ws.file(tag + ".compile.log"),
                verifyArgs("clean", "compileJava", "compileTestJava", "migrationResolvedVersions", "-PmigrationVersionsOut=" + stageVersions));
        // 컴파일이 깨져도 버전 목록은 남긴다
        if (!stageVersions.toFile().exists()) {
            gradle.runQuietly(verifyArgs("migrationResolvedVersions", "-PmigrationVersionsOut=" + stageVersions));
        }
        if (!compileOk || !gate.equals("build")) {
            return new GateResult(compileOk, "skip", 0, false);
        }
        // 테스트는 ignoreFailures 로 끝까지 돌리고 결과 XML 로 센다. build 자체의 실패는 테스트 외(패키징/플러그인/검사) 문제다
        step("[" + stageName + "] build (전체 테스트 + 패키징, properties-migrator 경고 수집)");
        boolean built = gradle.run(ws.file(tag + ".test.log"), verifyArgs("build"));
        TestResults.Summary tests = TestResults.collect(ws.dir().getParent());
        logger.lifecycle("   테스트 {}개, 실패 {}개", tests.total(), tests.failed());
        return new GateResult(true, built ? "1" : "0", tests.failed(), !built && !baselineBuildOk.equals("0"));
    }

    /** 추적 중인 파일의 변경과 레시피가 만든 파일만 커밋한다. */
    private void commitStage(Git git, MigrationWorkspace ws, String stageName, String detail, String tag) {
        if (git.commit(ws.createdFiles(), "chore: Spring Boot " + stageName + " 마이그레이션 " + detail,
                "리포트: " + MigrationWorkspace.DIR_NAME + "/" + tag + ".md")) {
            logger.lifecycle("   commit: {}", git.lastCommit());
        } else {
            fail("[" + stageName + "] commit 실패");
        }
    }

    /** 단계 전후 resolve 된 버전으로 알려진 이슈를 고른다. 리포트는 이 결과를 그리기만 한다. */
    private Map<String, Object> issuesJson(Stage stage, Path beforeVersions, Path afterVersions) {
        List<Object> items = new ArrayList<>();
        for (Match match : knownIssues.match(stage.issueKey(), readVersions(beforeVersions), readVersions(afterVersions))) {
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
        json.put("guide", knownIssues.guide(stage.issueKey()));
        json.put("issues", items);
        return json;
    }

    private static Map<String, String> readVersions(Path file) {
        Map<String, String> versions = new LinkedHashMap<>();
        MigrationWorkspace.read(file).lines().forEach(line -> {
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
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static ProjectRecipes projectRecipes(MigrationRequest request) {
        // upstream-only 는 upstream 만의 결과를 비교하는 모드라 프로젝트 레시피도 붙이지 않는다
        return request.skipProjectRecipes() || request.upstreamOnly() ? ProjectRecipes.none() : ProjectRecipes.discover(request.projectDir());
    }

    private static String projectRecipeSuffix(ProjectRecipes projectRecipes, Stage stage) {
        List<String> before = projectRecipes.names(stage.issueKey(), Phase.BEFORE);
        List<String> after = projectRecipes.names(stage.issueKey(), Phase.AFTER);
        if (before.isEmpty() && after.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder("  + 프로젝트");
        if (!before.isEmpty()) out.append(" before ").append(before);
        if (!after.isEmpty()) out.append(" after ").append(after);
        return out.toString();
    }

    private void printProjectRecipes(Path projectDir, ProjectRecipes projectRecipes) {
        if (projectRecipes.files().isEmpty()) {
            return;
        }
        step("프로젝트 레시피 (" + String.join(", ", projectRecipes.files().stream().map(f -> projectDir.relativize(f).toString()).toList()) + ")");
        if (projectRecipes.recipes().isEmpty()) {
            logger.lifecycle("   migration-stage 태그가 붙은 레시피가 없다 (태그가 없는 레시피는 다른 레시피가 참조할 때만 쓰인다)");
        }
        for (ProjectRecipe recipe : projectRecipes.recipes()) {
            logger.lifecycle("   {}  단계 {} / {}", recipe.name(), recipe.stages(), recipe.phase() == Phase.BEFORE ? "before" : "after");
        }
    }

    private void printTargetLine(MigrationPlan plan) {
        var line = plan.targetLine();
        logger.lifecycle("   호환성   : Java {} ~ {} / Gradle {} / Spring Framework {} / Spring Cloud {} ({}+) / Spring Cloud AWS {}",
                line.javaMin(), line.javaMax(), line.gradleRange(), line.framework(), line.springCloudTrain(), line.springCloudSince(), line.springCloudAws());
    }

    private void printNotes(MigrationPlan plan) {
        plan.notes().forEach(n -> logger.lifecycle("   참고     : {}", n));
    }

    private TargetGradle targetGradle(ProjectModel project, boolean keepJavaHome) {
        String javaHome = keepJavaHome ? System.getenv("JAVA_HOME") : new JdkLocator().javaHomeFor(project.toolchainJava());
        return new TargetGradle(project.dir(), javaHome, gradleJvmArgs, logger);
    }

    private MigrationPlan planOrFail(ProjectModel project, MigrationRequest request) {
        try {
            return planner.plan(project, request);
        } catch (IllegalArgumentException e) {
            throw failure(e.getMessage());
        }
    }

    private boolean resolvedVersions(TargetGradle gradle, Path log, Path out) {
        return gradle.run(log, verifyArgs("migrationResolvedVersions", "-PmigrationVersionsOut=" + out));
    }

    private boolean scan(Path projectDir, TargetGradle gradle, Path log, Path findPatch) {
        boolean ok = gradle.rewrite(log, "rewriteDryRun", "com.eottabom.rewrite.FindManualMigrationItems", paths.rewriteInit(), paths.recipeLibs());
        if (ok) {
            MigrationWorkspace.copyOrEmpty(rewritePatch(projectDir), findPatch);
        } else {
            MigrationWorkspace.writeEmpty(findPatch);
        }
        return ok;
    }

    private List<String> verifyArgs(String... args) {
        List<String> all = new ArrayList<>(List.of("--init-script", paths.verifyInit().toString()));
        all.addAll(List.of(args));
        return all;
    }

    private static Path rewritePatch(Path projectDir) {
        return projectDir.resolve("build/reports/rewrite/rewrite.patch");
    }

    private void printProject(ProjectModel project, TargetGradle gradle) {
        step("프로젝트 : " + project.dir());
        logger.lifecycle("   Boot     : {}", orQ(project.bootVersion()));
        logger.lifecycle("   Gradle   : {}", orQ(project.gradleVersion()));
        logger.lifecycle("   Java     : {}", orQ(project.javaVersion()));
        logger.lifecycle("   JAVA_HOME: {}", orDefault(gradle.javaHome()));
        logger.lifecycle("   git      : {}", !project.git() ? "아님" : project.dirty() ? "커밋되지 않은 변경 있음" : "깨끗함");
    }

    private static String options(MigrationRequest request, MigrationPlan plan) {
        StringBuilder opts = new StringBuilder("gate=").append(request.gate());
        if (request.commit()) opts.append(", commit");
        if (request.dryRun()) opts.append(", preview");
        if (request.oneShot()) opts.append(", one-shot");
        if (plan.targetJava() != null) opts.append(", java=").append(plan.targetJava());
        if (request.upstreamOnly()) opts.append(", upstream-only");
        if (request.allowDirty()) opts.append(", allow-dirty");
        return opts.toString();
    }

    private static void checkGate(String gate) {
        if (!GATES.contains(gate)) {
            throw new GradleException("--gate 는 compile | build | none");
        }
    }

    private void step(String message) {
        logger.lifecycle("");
        logger.lifecycle(">> {}", message);
    }

    private void fail(String message) {
        logger.error("!! {}", message);
    }

    private static GradleException failure(String message) {
        return new GradleException(message);
    }

    private static String orQ(Object value) {
        return value == null ? "?" : value.toString();
    }

    private static String orDefault(String value) {
        return value == null ? "default" : value;
    }
}
