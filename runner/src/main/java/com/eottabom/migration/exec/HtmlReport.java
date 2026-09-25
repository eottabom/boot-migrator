package com.eottabom.migration.exec;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * .rewrite-migration/report.html: 전 단계를 한 페이지로 보는 리포트. 브라우저로 바로 연다 (외부 리소스 없음). 단계마다 리포트
 * 스크립트가 남긴 NN-*.report.json 과 러너가 남긴 NN-*.stage.patch(그 단계에서만 바뀐 diff)를 모은다.
 */
final class HtmlReport {

	static final String FILE_NAME = "report.html";

	/** 단계 diff 를 페이지에 넣는 최대 크기. 넘으면 앞부분만 넣고 .stage.patch 를 보라고 표시한다 */
	private static final int MAX_PATCH_CHARS = 1_500_000;

	private static final Pattern STAGE_JSON = Pattern.compile("^(\\d{2}-.+)\\.report\\.json$");

	private HtmlReport() {
	}

	/**
	 * @return 만든 파일
	 */
	static Path write(MigrationWorkspace ws, String projectName, String startBoot, String currentBoot) {
		List<Object> stages = new ArrayList<>();
		for (String tag : stageTags(ws)) {
			String json = MigrationWorkspace.read(ws.file(tag + ".report.json"));
			Path patchFile = ws.file(tag + ".stage.patch");
			String patch = Files.exists(patchFile) ? MigrationWorkspace.read(patchFile) : null;
			boolean truncated = patch != null && patch.length() > MAX_PATCH_CHARS;
			Map<String, Object> stage = new LinkedHashMap<>();
			stage.put("tag", tag);
			stage.put("name", stageName(tag));
			stage.put("report", json.isBlank() ? null : new Json.Raw(json));
			stage.put("patch",
					truncated ? patch.substring(0, patch.lastIndexOf("\ndiff --git", MAX_PATCH_CHARS) + 1) : patch);
			stage.put("patchTruncated", truncated);
			stages.add(stage);
		}
		Map<String, Object> data = new LinkedHashMap<>();
		data.put("project", projectName);
		data.put("startBoot", (startBoot != null) ? startBoot : "?");
		data.put("currentBoot", (currentBoot != null) ? currentBoot : "?");
		data.put("generatedAt", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")));
		data.put("stages", stages);

		// </script> 가 데이터 안에 있어도 스크립트 태그가 끝나지 않도록
		String json = Json.write(data).replace("</", "<\\/");
		String html = template().replace("/*__DATA__*/", json);
		Path out = ws.file(FILE_NAME);
		try {
			Files.writeString(out, html);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		return out;
	}

	private static List<String> stageTags(MigrationWorkspace ws) {
		try (Stream<Path> files = Files.list(ws.dir())) {
			return files.map((p) -> STAGE_JSON.matcher(p.getFileName().toString()))
				.filter(Matcher::matches)
				.map((m) -> m.group(1))
				.sorted()
				.toList();
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	/** 03-boot-3.4 → 3.4, 06-java25 → java25, 02-gradle8.14 → gradle8.14 */
	private static String stageName(String tag) {
		String rest = tag.substring(3);
		return rest.startsWith("boot-") ? rest.substring(5) : rest;
	}

	private static String template() {
		try (InputStream in = HtmlReport.class.getResourceAsStream("/com/eottabom/migration/report-template.html")) {
			if (in == null) {
				throw new IllegalStateException("report-template.html 이 없다");
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

}
