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
 * 대상 프로젝트의 .rewrite-migration 디렉토리. 리포트, 패치, 로그, 실행 기록(SUMMARY.md), 재개 정보를 둔다. clean 에
 * 지워지지 않도록 build 밖에 두고, git 이면 .git/info/exclude 로 커밋 대상에서 뺀다.
 */
public record MigrationWorkspace(Path dir) {

	public static final String DIR_NAME = ".rewrite-migration";

	private static final Pattern STAGE_REPORT = Pattern.compile("^\\d{2}-.*\\.md$");

	public MigrationWorkspace {
		try {
			Files.createDirectories(dir);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	/** 대상 프로젝트의 .rewrite-migration */
	public static MigrationWorkspace in(Path projectDir) {
		return new MigrationWorkspace(projectDir.resolve(DIR_NAME));
	}

	public Path file(String name) {
		return this.dir.resolve(name);
	}

	/** 지난 기록 뒤에 이어 붙일 단계 번호의 시작값 (이미 있는 NN-*.md 개수). */
	public int stageReportCount() {
		try (Stream<Path> files = Files.list(this.dir)) {
			return (int) files.filter((p) -> STAGE_REPORT.matcher(p.getFileName().toString()).matches()).count();
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	// ── 실행 기록
	// ─────────────────────────────────────────────────────────────────────────────────────────────────────

	public void appendSummary(String projectName, String text) {
		Path summary = file("SUMMARY.md");
		try {
			if (!Files.exists(summary)) {
				Files.writeString(summary, "# 마이그레이션 기록: " + projectName + "\n\n");
			}
			Files.writeString(summary, text, StandardOpenOption.APPEND);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	/** 단계 리포트 상단 표에서 요약 한 줄을 만든다. */
	public String summaryRow(String stage, String tag) {
		String md = read(file(tag + ".md"));
		return "| " + stage + " | " + cell(md, "컴파일") + " | " + cell(md, "테스트") + " | " + cell(md, "빌드") + " | "
				+ cell(md, "자동 변경 파일") + " | " + cell(md, "수동 검토 대상") + " | " + cell(md, "알려진 이슈") + " | [" + tag
				+ ".md](" + tag + ".md) |\n";
	}

	/** 단계 리포트 상단의 요약 표. */
	public List<String> reportHead(String tag) {
		return read(file(tag + ".md")).lines()
			.dropWhile((line) -> !line.startsWith("|"))
			.takeWhile((line) -> line.startsWith("|"))
			.toList();
	}

	private static String cell(String md, String label) {
		Matcher m = Pattern.compile("(?m)^\\| " + Pattern.quote(label) + " \\| (.*) \\|$").matcher(md);
		return m.find() ? m.group(1) : "-";
	}

	public Optional<Resume> readResume() {
		Path file = file(".resume");
		if (!Files.exists(file)) {
			return Optional.empty();
		}
		Properties p = new Properties();
		try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			p.load(in);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		String previous = p.getProperty("R_PREV_TAG", "");
		return Optional.of(new Resume(p.getProperty("R_STAGE"), p.getProperty("R_TAG"),
				previous.isEmpty() ? null : previous, p.getProperty("R_REASON", "compile")));
	}

	public void writeResume(Resume resume) {
		Properties p = new Properties();
		p.setProperty("R_STAGE", resume.stage());
		p.setProperty("R_TAG", resume.tag());
		p.setProperty("R_PREV_TAG", (resume.previousTag() == null) ? "" : resume.previousTag());
		p.setProperty("R_REASON", resume.reason());
		try (Writer out = Files.newBufferedWriter(file(".resume"), StandardCharsets.UTF_8)) {
			p.store(out, null);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	public void clearResume() {
		try {
			Files.deleteIfExists(file(".resume"));
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	// ── 레시피가 새로 만든 파일 (patch / 커밋에 담을 추적 안 된 파일)
	// ─────────────────────────────────────────────────────

	public Set<String> createdFiles() {
		return new LinkedHashSet<>(read(file("created-files.txt")).lines().filter((l) -> !l.isBlank()).toList());
	}

	public void addCreatedFiles(Collection<String> files) {
		Set<String> all = createdFiles();
		all.addAll(files);
		try {
			Files.writeString(file("created-files.txt"), String.join("\n", all));
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	// ── 처음 실행 때 정한 값 (재개해도 바뀌지 않는다)
	// ─────────────────────────────────────────────────────────────────

	/** 원본 빌드(테스트 제외)에서 실패한 태스크. 저장된 적이 없으면 빈 집합 (원본 빌드가 통과했다고 본다) */
	public Set<String> baselineFailedTasks() {
		return new LinkedHashSet<>(
				read(file("00-baseline-failed-tasks.txt")).lines().filter((l) -> !l.isBlank()).toList());
	}

	public void writeBaselineFailedTasks(Collection<String> tasks) {
		write(file("00-baseline-failed-tasks.txt"), String.join("\n", tasks));
	}

	/** 멈출 때 있던 추적 안 된 파일 (실패한 빌드가 남긴 것 포함). 재개 때는 그 뒤에 생긴 파일만 사용자가 고치며 만든 파일로 본다 */
	public Set<String> untrackedAtStop() {
		return new LinkedHashSet<>(read(file("untracked-at-stop.txt")).lines().filter((l) -> !l.isBlank()).toList());
	}

	public void recordUntrackedAtStop(Collection<String> files) {
		write(file("untracked-at-stop.txt"), String.join("\n", files));
	}

	/** 누적 patch 의 기준 커밋. 재개한 실행도 처음 실행과 같은 기준이어야 단계 전 상태로 되돌릴 수 있다 */
	public Optional<String> baseRevision() {
		return firstLine(file("base-revision.txt"));
	}

	public void recordBaseRevision(String revision) {
		if (baseRevision().isEmpty() && revision != null) {
			write(file("base-revision.txt"), revision);
		}
	}

	/** 처음 실행 때의 Boot 버전 (리포트 제목의 "시작 → 현재") */
	public Optional<String> startBoot() {
		return firstLine(file("start-boot.txt"));
	}

	public void recordStartBoot(String version) {
		if (startBoot().isEmpty() && version != null) {
			write(file("start-boot.txt"), version);
		}
	}

	private static Optional<String> firstLine(Path file) {
		return read(file).lines().map(String::trim).filter((l) -> !l.isEmpty()).findFirst();
	}

	private static void write(Path file, String content) {
		try {
			Files.writeString(file, content);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	// ── 파일 도우미
	// ──────────────────────────────────────────────────────────────────────────────────────────────────

	public static String read(Path file) {
		try {
			return Files.exists(file) ? Files.readString(file) : "";
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	public static long countMatches(Path file, String regex) {
		Pattern pattern = Pattern.compile(regex);
		return read(file).lines().filter((l) -> pattern.matcher(l).find()).count();
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
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	public static void writeEmpty(Path file) {
		try {
			Files.writeString(file, "");
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	// ── 재개 정보: 컴파일 실패로 멈춘 단계
	// ───────────────────────────────────────────────────────────────────────────────

	/**
	 * @param reason compile(컴파일 실패) | build(테스트/빌드 실패)
	 */
	public record Resume(String stage, String tag, String previousTag, String reason) {
	}
}
