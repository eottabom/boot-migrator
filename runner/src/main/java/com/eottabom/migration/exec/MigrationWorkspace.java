package com.eottabom.migration.exec;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 대상 프로젝트의 .rewrite-migration 디렉토리. 리포트, 패치, 로그, 실행 기록(SUMMARY.md), 재개 정보를 둔다.
 * clean 에 지워지지 않도록 build 밖에 두고, git 이면 .git/info/exclude 로 커밋 대상에서 뺀다.
 */
public final class MigrationWorkspace {

    public static final String DIR_NAME = ".rewrite-migration";
    private static final Pattern STAGE_REPORT = Pattern.compile("^\\d{2}-.*\\.md$");

    private final Path dir;

    public MigrationWorkspace(Path projectDir) {
        this.dir = projectDir.resolve(DIR_NAME);
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public Path dir() {
        return dir;
    }

    public Path file(String name) {
        return dir.resolve(name);
    }

    /** 지난 기록 뒤에 이어 붙일 단계 번호의 시작값 (이미 있는 NN-*.md 개수). */
    public int stageReportCount() {
        try (Stream<Path> files = Files.list(dir)) {
            return (int) files.filter(p -> STAGE_REPORT.matcher(p.getFileName().toString()).matches()).count();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ── 실행 기록 ─────────────────────────────────────────────────────────────────────────────────────────────────────

    public void appendSummary(String projectName, String text) {
        Path summary = file("SUMMARY.md");
        try {
            if (!Files.exists(summary)) {
                Files.writeString(summary, "# 마이그레이션 기록: " + projectName + "\n\n");
            }
            Files.writeString(summary, text, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 단계 리포트 상단 표에서 요약 한 줄을 만든다. */
    public String summaryRow(String stage, String tag) {
        String md = read(file(tag + ".md"));
        return "| " + stage + " | " + cell(md, "컴파일") + " | " + cell(md, "테스트") + " | " + cell(md, "빌드") + " | "
                + cell(md, "자동 변경 파일") + " | " + cell(md, "수동 검토 대상") + " | " + cell(md, "알려진 이슈")
                + " | [" + tag + ".md](" + tag + ".md) |\n";
    }

    /** 단계 리포트 상단의 요약 표. */
    public List<String> reportHead(String tag) {
        return read(file(tag + ".md")).lines()
                .dropWhile(line -> !line.startsWith("|"))
                .takeWhile(line -> line.startsWith("|"))
                .toList();
    }

    private static String cell(String md, String label) {
        Matcher m = Pattern.compile("(?m)^\\| " + Pattern.quote(label) + " \\| (.*) \\|$").matcher(md);
        return m.find() ? m.group(1) : "-";
    }

    // ── 재개 정보: 컴파일 실패로 멈춘 단계 ───────────────────────────────────────────────────────────────────────────────

    /** @param reason compile(컴파일 실패) | build(테스트/빌드 실패) */
    public record Resume(String stage, String tag, String previousTag, String reason) {
    }

    public Optional<Resume> readResume() {
        Path file = file(".resume");
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        Properties p = new Properties();
        try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            p.load(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        String previous = p.getProperty("R_PREV_TAG", "");
        return Optional.of(new Resume(p.getProperty("R_STAGE"), p.getProperty("R_TAG"), previous.isEmpty() ? null : previous,
                p.getProperty("R_REASON", "compile")));
    }

    public void writeResume(Resume resume) {
        Properties p = new Properties();
        p.setProperty("R_STAGE", resume.stage());
        p.setProperty("R_TAG", resume.tag());
        p.setProperty("R_PREV_TAG", resume.previousTag() == null ? "" : resume.previousTag());
        p.setProperty("R_REASON", resume.reason());
        try (Writer out = Files.newBufferedWriter(file(".resume"), StandardCharsets.UTF_8)) {
            p.store(out, null);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void clearResume() {
        try {
            Files.deleteIfExists(file(".resume"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ── 레시피가 새로 만든 파일 (patch / 커밋에 담을 추적 안 된 파일) ─────────────────────────────────────────────────────

    public Set<String> createdFiles() {
        return new LinkedHashSet<>(read(file("created-files.txt")).lines().filter(l -> !l.isBlank()).toList());
    }

    public void addCreatedFiles(Collection<String> files) {
        Set<String> all = createdFiles();
        all.addAll(files);
        try {
            Files.writeString(file("created-files.txt"), String.join("\n", all));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ── 파일 도우미 ──────────────────────────────────────────────────────────────────────────────────────────────────

    public static String read(Path file) {
        try {
            return Files.exists(file) ? Files.readString(file) : "";
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static long countMatches(Path file, String regex) {
        Pattern pattern = Pattern.compile(regex);
        return read(file).lines().filter(l -> pattern.matcher(l).find()).count();
    }

    /** from 이 없으면 빈 파일을 만들고 false. */
    public static boolean copyOrEmpty(Path from, Path to) {
        try {
            if (Files.exists(from)) {
                Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING);
                return true;
            }
            writeEmpty(to);
            return false;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static void writeEmpty(Path file) {
        try {
            Files.writeString(file, "");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
