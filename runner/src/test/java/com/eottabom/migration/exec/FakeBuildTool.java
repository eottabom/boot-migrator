package com.eottabom.migration.exec;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Consumer;

/**
 * 러너 통합 테스트용 가짜 대상 빌드. 명령 인자로 무엇을 하려는지 알아보고, 테스트가 정해 둔 결과를 순서대로 돌려준다.
 * 정해 둔 결과가 없으면 성공으로 본다.
 */
final class FakeBuildTool implements BuildTool {

    /** @param failedTasks 로그에 남길 실패 태스크 (Gradle 의 "Execution failed for task" 형식) */
    record BuildOutcome(boolean ok, int failedTests, List<String> failedTasks) {
        static BuildOutcome pass() {
            return new BuildOutcome(true, 0, List.of());
        }
    }

    final Path projectDir;
    final Deque<Consumer<Path>> rewrites = new ArrayDeque<>();
    final Deque<Boolean> compiles = new ArrayDeque<>();
    final Deque<BuildOutcome> builds = new ArrayDeque<>();
    BuildOutcome baseline = BuildOutcome.pass();
    final List<String> calls = new ArrayList<>();

    FakeBuildTool(Path projectDir) {
        this.projectDir = projectDir;
    }

    @Override
    public String javaHome() {
        return null;
    }

    @Override
    public boolean runQuietly(List<String> args) {
        return run(null, args);
    }

    @Override
    public boolean rewrite(Path log, String task, String recipe, Path rewriteInit, Path recipeLibs, Path configFile) {
        calls.add(task + " " + recipe);
        if (task.equals("rewriteRun") && !rewrites.isEmpty()) {
            rewrites.poll().accept(projectDir);
        }
        write(log, "BUILD SUCCESSFUL");
        return true;
    }

    @Override
    public boolean run(Path log, List<String> args) {
        String cmd = String.join(" ", args);
        calls.add(cmd.replaceAll("--init-script \\S+ ", ""));
        args.stream().filter(a -> a.startsWith("-PmigrationVersionsOut=")).findFirst()
                .ifPresent(a -> write(Path.of(a.substring(a.indexOf('=') + 1)), "org.springframework.boot:spring-boot=3.4.0\n"));
        if (args.contains("migrationReport")) {
            String md = value(args, "-PmigrationReportOut=");
            String stage = value(args, "-PmigrationStage=");
            write(Path.of(md), "# Spring Boot " + stage + "\n\n| 항목 | 결과 |\n|---|---|\n| 컴파일 | " + value(args, "-PmigrationCompileOk=") + " |\n");
            write(Path.of(value(args, "-PmigrationReportJson=")), "{\"stage\":\"" + stage + "\",\"compile\":\"ok\",\"tests\":{\"total\":0,\"failures\":[]}}");
            return true;
        }
        if (args.contains("-x") && args.contains("build")) {
            return finish(log, baseline);
        }
        if (args.contains("compileJava")) {
            boolean ok = compiles.isEmpty() || compiles.poll();
            write(log, ok ? "BUILD SUCCESSFUL" : "Compilation failed\nExecution failed for task ':compileJava'.");
            return ok;
        }
        if (args.contains("build")) {
            return finish(log, builds.isEmpty() ? BuildOutcome.pass() : builds.poll());
        }
        write(log, "BUILD SUCCESSFUL");
        return true;
    }

    long count(String fragment) {
        return calls.stream().filter(c -> c.contains(fragment)).count();
    }

    private boolean finish(Path log, BuildOutcome outcome) {
        StringBuilder out = new StringBuilder();
        outcome.failedTasks().forEach(t -> out.append("* What went wrong:\nExecution failed for task '").append(t).append("'.\n"));
        write(log, out.length() == 0 ? (outcome.ok() ? "BUILD SUCCESSFUL" : "BUILD FAILED") : out.toString());
        // 테스트 결과 XML (빌드 산출물이라 .gitignore 의 build/ 아래)
        Path xml = projectDir.resolve("build/test-results/test/TEST-demo.AppTest.xml");
        StringBuilder cases = new StringBuilder();
        for (int i = 0; i < outcome.failedTests(); i++) {
            cases.append("<testcase classname=\"demo.AppTest\" name=\"t").append(i).append("\"><failure message=\"boom\">boom</failure></testcase>");
        }
        write(xml, "<testsuite name=\"demo.AppTest\" tests=\"" + Math.max(1, outcome.failedTests()) + "\" failures=\"" + outcome.failedTests()
                + "\" errors=\"0\">" + cases + "</testsuite>");
        // 빌드가 만든 추적 안 되는 파일 (.gitignore 에 없는 산출물). 커밋에 섞이면 안 된다
        write(projectDir.resolve("test-output.log"), "junk");
        return outcome.ok();
    }

    private static String value(List<String> args, String prefix) {
        return args.stream().filter(a -> a.startsWith(prefix)).map(a -> a.substring(prefix.length())).findFirst().orElse("");
    }

    static void write(Path file, String content) {
        if (file == null) {
            return;
        }
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, content);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
