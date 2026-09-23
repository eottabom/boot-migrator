package com.eottabom.migration.exec;

import org.gradle.api.logging.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 대상 프로젝트의 Gradle wrapper 를 별도 프로세스로 실행한다.
 * 대상 프로젝트는 자기 Gradle 버전, 플러그인, JDK 로 돌아야 하므로 이 빌드 안에서 직접 실행하지 않는다.
 */
public final class TargetGradle {

    private static final long PROGRESS_INTERVAL_MS = 20_000;
    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase().startsWith("windows");

    private final Path projectDir;
    private final String javaHome;
    private final String jvmArgs;
    private final Logger logger;

    /** @param jvmArgs 대상 Gradle 데몬 JVM 옵션. null 이면 {@link #defaultJvmArgs()} */
    public TargetGradle(Path projectDir, String javaHome, String jvmArgs, Logger logger) {
        this.projectDir = projectDir;
        this.javaHome = javaHome;
        this.jvmArgs = jvmArgs != null ? jvmArgs : defaultJvmArgs();
        this.logger = logger;
    }

    /**
     * OpenRewrite 는 전체 소스의 LST 를 메모리에 올리므로 넉넉해야 한다. 최대 6g, 장비 메모리의 절반을 넘지 않는다
     * (메모리 제한이 있는 CI 컨테이너에서 OOM Killer 에 죽지 않도록). --gradle-jvmargs 로 바꿀 수 있다.
     */
    static String defaultJvmArgs() {
        long totalMb = 8192;
        if (java.lang.management.ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os) {
            totalMb = os.getTotalMemorySize() / (1024 * 1024);
        }
        long heapMb = Math.max(1024, Math.min(6144, totalMb / 2));
        return "-Xmx" + heapMb + "m -XX:MaxMetaspaceSize=1g";
    }

    public String javaHome() {
        return javaHome;
    }

    /**
     * rewriteRun / rewriteDryRun.
     * clean: src/main/generated 등에 남은 QueryDSL Q-class 가 있으면 APT 가 "Attempt to recreate a file" 로 실패한다.
     * --no-daemon: 데몬이 이전에 로딩한 레시피 jar 를 캐시해서, 레시피를 고쳐도 옛 버전이 도는 문제 방지.
     */
    public boolean rewrite(Path log, String task, String recipe, Path rewriteInit, Path recipeLibs) {
        return rewrite(log, task, recipe, rewriteInit, recipeLibs, null);
    }

    /** configFile: 선언형 레시피 파일 (.rewrite/rewrite.generated.yml). null 이면 플러그인 기본값 */
    public boolean rewrite(Path log, String task, String recipe, Path rewriteInit, Path recipeLibs, Path configFile) {
        List<String> args = new ArrayList<>(List.of("--no-daemon", "--init-script", rewriteInit.toString(), "clean", task,
                "-Drewrite.activeRecipe=" + recipe, "-PrewriteRecipeLibs=" + recipeLibs));
        if (configFile != null) {
            args.add("-PrewriteConfigFile=" + configFile);
        }
        return run(log, args);
    }

    /**
     * 출력은 log 파일로 보내고, 실행 중에는 경과 시간과 현재 Gradle 태스크를 주기적으로 찍는다
     * (테스트 태스크면 끝난 테스트 클래스 수도). 끝나면 소요 시간을 남긴다.
     */
    public boolean run(Path log, List<String> args) {
        long start = System.currentTimeMillis();
        FileTime startTime = FileTime.fromMillis(start);
        AtomicReference<String> currentTask = new AtomicReference<>("준비 중");
        try {
            Files.createDirectories(log.getParent());
            Process process = start(args);
            Thread pump = new Thread(() -> pump(process, log, currentTask), "target-gradle-output");
            pump.start();
            long lastReport = start;
            while (!process.waitFor(2, TimeUnit.SECONDS)) {
                long now = System.currentTimeMillis();
                if (now - lastReport >= PROGRESS_INTERVAL_MS) {
                    lastReport = now;
                    String task = currentTask.get();
                    String extra = task.endsWith(":test") ? "  (테스트 클래스 " + TestResults.countSince(projectDir, startTime) + "개 완료)" : "";
                    logger.lifecycle("   {}  {}{}", elapsed(start), task, extra);
                }
            }
            pump.join();
            logger.lifecycle("   소요 {}", elapsed(start));
            return process.exitValue() == 0;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** 결과만 필요하고 로그는 남기지 않는 실행. */
    public boolean runQuietly(List<String> args) {
        try {
            Process process = start(args);
            process.getInputStream().transferTo(OutputStream.nullOutputStream());
            return process.waitFor() == 0;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private Process start(List<String> args) throws IOException {
        List<String> command = new ArrayList<>();
        command.add(projectDir.resolve(WINDOWS ? "gradlew.bat" : "gradlew").toString());
        command.add("-Dorg.gradle.jvmargs=" + jvmArgs);
        command.add("--console=plain");
        // OpenRewrite 플러그인과 init script 의 태스크는 configuration cache 를 지원하지 않는다.
        // 대상 프로젝트가 gradle.properties 로 켜 두었어도 마이그레이션 중에는 끈다
        command.add("--no-configuration-cache");
        command.addAll(args);
        ProcessBuilder builder = new ProcessBuilder(command).directory(projectDir.toFile()).redirectErrorStream(true);
        if (javaHome != null) {
            builder.environment().put("JAVA_HOME", javaHome);
        }
        return builder.start();
    }

    private static void pump(Process process, Path log, AtomicReference<String> currentTask) {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
             Writer out = Files.newBufferedWriter(log, StandardCharsets.UTF_8)) {
            String line;
            while ((line = in.readLine()) != null) {
                out.write(line);
                out.write('\n');
                if (line.startsWith("> Task ")) {
                    currentTask.set(line.substring(7));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String elapsed(long start) {
        long seconds = (System.currentTimeMillis() - start) / 1000;
        return String.format("%02d:%02d", seconds / 60, seconds % 60);
    }
}
