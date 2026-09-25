package com.eottabom.migration.exec;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import com.eottabom.migration.playbook.KnownIssues.FailureHint;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * 단계 리포트. 단계가 끝난 뒤 남은 파일(컴파일 로그, rewriteRun 로그, 테스트 결과 XML, 스캔 patch, 의존성 버전 목록)을 읽어 사람이
 * 보는 NN-*.md 와 HTML 리포트용 NN-*.report.json 을 만든다. 대상 프로젝트의 Gradle 을 띄우지 않는다.
 */
final class StageReport {

	private static final Pattern WARNING = Pattern
		.compile("^(/\\S+\\.java):(\\d+): warning: \\[(removal|deprecation)\\] (.*)$");

	// 제목 뒤에 빈 줄이 오고 Property source 가 여러 개일 수 있어서, Boot 가 마지막에 찍는 안내 문장이나 다음 블록까지를 본문으로
	// 본다
	private static final Pattern MIGRATOR_BLOCK = Pattern.compile(
			"(?s)The use of configuration keys that (have been renamed|are no longer supported) was found in the environment:"
					+ "(.*?)(?=Each configuration key|Please refer to the release notes|The use of configuration keys that|\\z)");

	private static final Pattern PROPERTY_SOURCE = Pattern.compile("Property source '(.+)':\\s*$");

	private static final Pattern CHANGED_FILE = Pattern
		.compile("^(?:Changes have been made to|These recipes would make changes to) (.+?)(?: by)?:$");

	private static final Pattern FIND_MARKER = Pattern.compile("/\\*~~(\\([^)]*\\))?>\\*/");

	private static final Pattern FRAME_LOCATION = Pattern.compile("\\(([^()]+\\.(?:java|kt|groovy):\\d+)\\)");

	private StageReport() {
	}

	static void write(Input in, Path markdown, Path json) {
		String root = in.projectDir().toAbsolutePath() + "/";
		Map<String, Map<String, Set<String>>> warnings = warnings(in.compileLog(), root);
		Tests tests = tests(in.projectDir(), in.failureHints());
		List<Map<String, Object>> manual = manual(in.findPatch());
		Fixes fixes = fixes(in.rewriteLog(), in.projectRecipes());
		Deps deps = deps(readVersions(in.versionsBefore()), readVersions(in.versionsAfter()));
		boolean compileFailed = "0".equals(in.compileOk());

		writeFile(markdown, markdown(in, warnings, tests, manual, fixes, deps, compileFailed));

		Map<String, Object> data = new LinkedHashMap<>();
		data.put("stage", in.stage());
		data.put("compile", "skip".equals(in.compileOk()) ? "skip" : compileFailed ? "fail" : "ok");
		data.put("build", in.buildOk());
		data.put("baselineBuild", in.baselineBuildOk());
		data.put("tests", Map.of("total", tests.total(), "failures", tests.failures()));
		data.put("warnings", Map.of("removal", warningList(warnings.get("removal")), "deprecation",
				warningList(warnings.get("deprecation"))));
		data.put("properties", Map.of("renamed", new ArrayList<>(tests.renamed().values()), "unsupported",
				new ArrayList<>(tests.unsupported().values())));
		data.put("manual", manual);
		List<Object> fixList = new ArrayList<>();
		fixes.byRecipe()
			.forEach((recipe, files) -> fixList.add(Map.of("recipe", recipe, "files", new ArrayList<>(files))));
		data.put("fixes", fixList);
		data.put("changedFiles", new ArrayList<>(fixes.changed()));
		data.put("deps", Map.of("changed", deps.changed(), "added", deps.added(), "removed", deps.removed()));
		data.put("knownIssues", in.knownIssues());
		data.put("guide", in.guide());
		writeFile(json, Json.write(data));
	}

	private static Map<String, Map<String, Set<String>>> warnings(Path compileLog, String root) {
		Map<String, Map<String, Set<String>>> warnings = new LinkedHashMap<>();
		warnings.put("removal", new LinkedHashMap<>());
		warnings.put("deprecation", new LinkedHashMap<>());
		for (String line : lines(compileLog)) {
			Matcher m = WARNING.matcher(line);
			if (m.find()) {
				String file = m.group(1).startsWith(root) ? m.group(1).substring(root.length()) : m.group(1);
				warnings.get(m.group(3))
					.computeIfAbsent(m.group(4), (k) -> new LinkedHashSet<>())
					.add(file + ":" + m.group(2));
			}
		}
		return warnings;
	}

	private static List<Object> warningList(Map<String, Set<String>> byMessage) {
		List<Object> list = new ArrayList<>();
		byMessage.entrySet()
			.stream()
			.sorted((a, b) -> b.getValue().size() - a.getValue().size())
			.forEach((e) -> list.add(Map.of("message", e.getKey(), "locations", new ArrayList<>(e.getValue()))));
		return list;
	}

	private static Tests tests(Path projectDir, List<FailureHint> hints) {
		int total = 0;
		List<Map<String, Object>> failures = new ArrayList<>();
		Map<String, Map<String, Object>> renamed = new LinkedHashMap<>();
		Map<String, Map<String, Object>> unsupported = new LinkedHashMap<>();
		DocumentBuilder parser = xmlParser();
		for (Path xml : TestResults.files(projectDir, null)) {
			Document doc;
			try {
				doc = parser.parse(xml.toFile());
			}
			catch (Exception ex) {
				continue;
			}
			Element suite = doc.getDocumentElement();
			total += intAttr(suite, "tests");
			NodeList cases = suite.getElementsByTagName("testcase");
			for (int i = 0; i < cases.getLength(); i++) {
				Element tc = (Element) cases.item(i);
				Element failure = firstChild(tc, "failure");
				if (failure == null) {
					failure = firstChild(tc, "error");
				}
				if (failure != null) {
					failures.add(testFailure(tc.getAttribute("classname"), tc.getAttribute("name"),
							failure.getAttribute("message"), failure.getTextContent(), hints));
				}
			}
			Element out = firstChild(suite, "system-out");
			if (out != null) {
				propertiesMigrator(out.getTextContent(), renamed, unsupported);
			}
		}
		return new Tests(total, failures, renamed, unsupported);
	}

	/** PropertiesMigrationListener 출력의 Property source / Key / Replacement 줄을 모은다 */
	private static void propertiesMigrator(String systemOut, Map<String, Map<String, Object>> renamed,
			Map<String, Map<String, Object>> unsupported) {
		Matcher block = MIGRATOR_BLOCK.matcher(systemOut);
		while (block.find()) {
			Map<String, Map<String, Object>> bucket = block.group(1).contains("renamed") ? renamed : unsupported;
			String source = "";
			Map<String, Object> current = null;
			for (String line : block.group(2).split("\n")) {
				Matcher s = PROPERTY_SOURCE.matcher(line);
				if (s.find()) {
					source = s.group(1);
				}
				Matcher k = Pattern.compile("Key: (\\S+)").matcher(line);
				if (k.find()) {
					current = new LinkedHashMap<>();
					current.put("key", k.group(1));
					current.put("replacement", null);
					current.put("source", source);
					bucket.putIfAbsent(k.group(1) + "|" + source, current);
					current = bucket.get(k.group(1) + "|" + source);
				}
				Matcher r = Pattern.compile("Replacement: (\\S+)").matcher(line);
				if (r.find() && current != null) {
					current.put("replacement", r.group(1));
				}
			}
		}
	}

	static Map<String, Object> testFailure(String classname, String name, String message, String stack,
			List<FailureHint> hints) {
		int dollar = classname.indexOf('$');
		String cls = (dollar >= 0) ? classname.substring(0, dollar) : classname;
		String nested = (dollar >= 0) ? classname.substring(dollar + 1).replace("$", " > ") + " > " : "";
		List<String> lines = ((stack != null && !stack.isBlank()) ? stack : (message != null) ? message : "").lines()
			.toList();
		String root = lines.stream()
			.filter((l) -> l.startsWith("Caused by: "))
			.reduce((a, b) -> b)
			.map((l) -> l.substring(11))
			.orElse(lines.isEmpty() ? ((message != null) ? message : "") : lines.get(0));
		int colon = root.indexOf(": ");
		String exception = ((colon > 0) ? root.substring(0, colon) : root).trim();
		String msg = (colon > 0) ? root.substring(colon + 2).trim() : "";
		String[] parts = cls.split("\\.");
		String base = (parts.length >= 2) ? parts[0] + "." + parts[1] + "." : cls + ".";
		String location = lines.stream()
			.map(String::trim)
			.filter((l) -> l.startsWith("at " + base))
			.findFirst()
			.map(FRAME_LOCATION::matcher)
			.filter(Matcher::find)
			.map((m) -> m.group(1))
			.orElse(null);
		String subject = exception + ": " + msg;
		String hint = hints.stream()
			.filter((h) -> Pattern.compile(h.pattern()).matcher(subject).find())
			.map(FailureHint::text)
			.findFirst()
			.orElse(null);
		Map<String, Object> failure = new LinkedHashMap<>();
		failure.put("cls", cls);
		failure.put("test", nested + name);
		failure.put("exception", exception.substring(exception.lastIndexOf('.') + 1));
		failure.put("message", (msg.length() > 200) ? msg.substring(0, 200) : msg);
		failure.put("location", location);
		failure.put("hint", hint);
		return failure;
	}

	private static List<Map<String, Object>> manual(Path findPatch) {
		Set<Map<String, Object>> manual = new LinkedHashSet<>();
		String current = null;
		for (String line : lines(findPatch)) {
			if (line.startsWith("+++ b/")) {
				current = line.substring(6).trim();
			}
			else if (line.startsWith("+") && line.contains("~~>") && current != null) {
				String code = FIND_MARKER.matcher(line.substring(1)).replaceAll("").trim();
				manual.add(Map.of("file", current, "code", (code.length() > 160) ? code.substring(0, 160) : code));
			}
		}
		return new ArrayList<>(manual);
	}

	/**
	 * rewriteRun 로그의 "Changes have been made to &lt;파일&gt; by:" 아래 레시피 트리에서, 말단(실제로 바꾼)
	 * 레시피를 가장 가까운 커스텀 보정 레시피에 귀속시킨다. upstream 은 파일 수만 센다.
	 */
	private static Fixes fixes(Path rewriteLog, Set<String> projectRecipes) {
		Fixes fixes = new Fixes(new TreeMap<>(), new LinkedHashSet<>());
		Map<String, List<RecipeNode>> blocks = new LinkedHashMap<>();
		String current = null;
		for (String line : lines(rewriteLog)) {
			Matcher m = CHANGED_FILE.matcher(line);
			if (m.find()) {
				current = m.group(1);
				fixes.changed().add(current);
				blocks.put(current, new ArrayList<>());
			}
			else if (current != null && line.startsWith("    ")) {
				String stripped = line.stripLeading();
				blocks.get(current)
					.add(new RecipeNode(line.length() - stripped.length(),
							stripped.trim().replaceAll(":\\s*\\{.*$", "")));
			}
			else {
				current = null;
			}
		}
		blocks.forEach((file, nodes) -> {
			boolean buildFile = file.endsWith(".gradle") || file.endsWith(".gradle.kts") || file.endsWith(".properties")
					|| file.endsWith("pom.xml");
			List<RecipeNode> stack = new ArrayList<>();
			for (int i = 0; i < nodes.size(); i++) {
				RecipeNode node = nodes.get(i);
				while (!stack.isEmpty() && stack.get(stack.size() - 1).indent() >= node.indent()) {
					stack.remove(stack.size() - 1);
				}
				stack.add(node);
				boolean leaf = i == nodes.size() - 1 || nodes.get(i + 1).indent() <= node.indent();
				// 의존성 레시피가 빌드 파일이 아닌 곳에 남긴 기록은 메타데이터 갱신이라 텍스트 변경이 없다
				if (!leaf || (!buildFile && (node.name().startsWith("org.openrewrite.java.dependencies.")
						|| node.name().startsWith("org.openrewrite.gradle.")))) {
					continue;
				}
				for (int j = stack.size() - 1; j >= 0; j--) {
					if (isCustomFix(stack.get(j).name(), projectRecipes)) {
						fixes.byRecipe().computeIfAbsent(stack.get(j).name(), (k) -> new TreeSet<>()).add(file);
						break;
					}
				}
			}
		});
		return fixes;
	}

	/**
	 * 단계 레시피 묶음(SpringBootStep / MigrateToSpringBoot / upstream 단계 /
	 * CommonMigrationFixes)은 보정으로 세지 않는다
	 */
	static boolean isCustomFix(String name, Set<String> projectRecipes) {
		return projectRecipes.contains(name) || (name.startsWith("com.eottabom.rewrite.")
				&& !name.contains(".spring.upstream.") && !name.contains(".MigrateToSpringBoot_")
				&& !name.contains(".SpringBootStep_") && !name.equals("com.eottabom.rewrite.CommonMigrationFixes"));
	}

	private static Deps deps(Map<String, String> before, Map<String, String> after) {
		if (before.isEmpty() || after.isEmpty()) {
			return new Deps(List.of(), List.of(), List.of());
		}
		List<Map<String, Object>> rows = new ArrayList<>();
		after.forEach((name, version) -> {
			String previous = before.get(name);
			if (previous != null && !previous.equals(version)) {
				Map<String, Object> row = new LinkedHashMap<>();
				row.put("name", name);
				row.put("before", previous);
				row.put("after", version);
				row.put("level", level(previous, version));
				rows.add(row);
			}
		});
		List<String> order = List.of("major", "minor", "patch");
		rows.sort((a, b) -> {
			int d = order.indexOf((String) a.get("level")) - order.indexOf((String) b.get("level"));
			return (d != 0) ? d : ((String) a.get("name")).compareTo((String) b.get("name"));
		});
		List<String> added = new ArrayList<>(new TreeSet<>(after.keySet()));
		added.removeAll(before.keySet());
		List<String> removed = new ArrayList<>(new TreeSet<>(before.keySet()));
		removed.removeAll(after.keySet());
		return new Deps(new ArrayList<>(rows), added, removed);
	}

	static String level(String before, String after) {
		String[] x = before.split("[.-]");
		String[] y = after.split("[.-]");
		if (!x[0].equals(y[0])) {
			return "major";
		}
		return (x.length > 1 && y.length > 1 && !x[1].equals(y[1])) ? "minor" : "patch";
	}

	static Map<String, String> readVersions(Path file) {
		Map<String, String> versions = new LinkedHashMap<>();
		for (String line : lines(file)) {
			int eq = line.indexOf('=');
			if (eq > 0) {
				versions.put(line.substring(0, eq), line.substring(eq + 1));
			}
		}
		return versions;
	}

	@SuppressWarnings("unchecked")
	private static String markdown(Input in, Map<String, Map<String, Set<String>>> warnings, Tests tests,
			List<Map<String, Object>> manual, Fixes fixes, Deps deps, boolean compileFailed) {
		List<String> L = new ArrayList<>();
		L.add("# Spring Boot " + in.stage() + " 마이그레이션 검증 리포트");
		L.add("");
		L.add("| 항목 | 결과 |");
		L.add("|---|---|");
		L.add("| 컴파일 | " + ("skip".equals(in.compileOk()) ? "실행 안 함" : compileFailed ? "❌ 실패" : "✅ 통과") + " |");
		L.add("| 테스트 | " + ((tests.total() == 0) ? "실행 안 함" : !tests.failures().isEmpty()
				? "❌ " + tests.failures().size() + " / " + tests.total() + " 실패" : "✅ " + tests.total() + "개 통과")
				+ " |");
		String buildFail = "0".equals(in.baselineBuildOk()) ? "❌ 실패 (원본에서도 실패하던 태스크만 실패한 기존 문제, 00-baseline-build.log)"
				: "❌ 실패 (테스트 외 태스크, 패키징이나 asciidoctor, checkstyle 등. test.log 참고)";
		L.add("| 빌드 | " + ("1".equals(in.buildOk()) ? "✅ 통과" : "0".equals(in.buildOk()) ? buildFail : "실행 안 함") + " |");
		L.add("| 제거 예정 API 사용 ([removal]) | " + warnings.get("removal").size() + " 종류 |");
		L.add("| deprecated API 사용 | " + warnings.get("deprecation").size() + " 종류 |");
		L.add("| 설정 키 변경 (properties-migrator) | 이름 변경 " + tests.renamed().size() + " / 지원 중단 "
				+ tests.unsupported().size() + " |");
		L.add("| 수동 검토 대상 | " + manual.size() + " 곳 |");
		if (!in.knownIssues().isEmpty()) {
			L.add("| 알려진 이슈 | 판단 " + countMode(in.knownIssues(), "REPORT_ONLY") + " / 확인 "
					+ countMode(in.knownIssues(), "REVIEW_REQUIRED") + " / 자동 보정 "
					+ countMode(in.knownIssues(), "AUTO_FIX") + " |");
		}
		L.add("| 자동 변경 파일 | " + fixes.changed().size() + " 개 (커스텀 보정 레시피 " + fixes.byRecipe().size() + " 종) |");
		L.add("");

		if (!fixes.byRecipe().isEmpty()) {
			L.add("## 자동 보정 내역 (커스텀 레시피)");
			L.add("이 단계에서 커스텀 보정 레시피가 바꾼 파일. 전체 변경은 같은 이름의 .patch 파일 참고.");
			L.add("");
			fixes.byRecipe().forEach((name, files) -> L.add("- **" + name + "** 로 바뀐 파일 " + joinCode(files, 5, "개")));
			L.add("");
		}

		if (!tests.failures().isEmpty()) {
			Map<String, List<Map<String, Object>>> byClass = new LinkedHashMap<>();
			tests.failures()
				.forEach((f) -> byClass.computeIfAbsent((String) f.get("cls"), (k) -> new ArrayList<>()).add(f));
			L.add("## 실패한 테스트 (" + tests.failures().size() + "건, " + byClass.size() + "개 클래스)");
			L.add("원인은 스택트레이스의 가장 안쪽 예외(Caused by) 기준. 전체 스택은 각 모듈의 build/test-results 참고.");
			L.add("");
			byClass.entrySet().stream().sorted((a, b) -> b.getValue().size() - a.getValue().size()).forEach((e) -> {
				String cls = e.getKey();
				L.add("### " + cls.substring(cls.lastIndexOf('.') + 1) + " (" + e.getValue().size() + "건)");
				L.add("`" + cls + "`");
				L.add("");
				Map<String, List<Map<String, Object>>> byCause = new LinkedHashMap<>();
				e.getValue()
					.forEach((f) -> byCause
						.computeIfAbsent(f.get("exception") + "|" + f.get("message"), (k) -> new ArrayList<>())
						.add(f));
				byCause.values().stream().sorted((a, b) -> b.size() - a.size()).forEach((same) -> {
					Map<String, Object> first = same.get(0);
					L.add("- **`" + first.get("exception") + "`** " + first.get("message") + " (" + same.size() + "건)");
					if (first.get("location") != null) {
						L.add("  - 위치 `" + first.get("location") + "`");
					}
					if (first.get("hint") != null) {
						L.add("  - " + first.get("hint"));
					}
					List<String> names = same.stream().map((f) -> (String) f.get("test")).toList();
					L.add("  - 해당 테스트 " + String.join(" / ", names.subList(0, Math.min(5, names.size())))
							+ ((names.size() > 5) ? " 외 " + (names.size() - 5) + "건" : ""));
				});
				L.add("");
			});
		}

		L.add("## 설정 키 변경 (spring-boot-properties-migrator)");
		if (!tests.renamed().isEmpty() || !tests.unsupported().isEmpty()) {
			L.add("테스트 중 로딩된 설정에서 발견됨. 저장소 밖(외부 설정 저장소)의 키가 나오면 그쪽을 고쳐야 한다.");
			L.add("");
			if (!tests.renamed().isEmpty()) {
				L.add("### 이름 변경됨 (지금은 임시로 자동 매핑 중)");
				tests.renamed()
					.values()
					.forEach((p) -> L.add("- `" + p.get("key") + "`"
							+ ((p.get("replacement") != null) ? " → `" + p.get("replacement") + "`" : "") + " ("
							+ p.get("source") + ")"));
				L.add("");
			}
			if (!tests.unsupported().isEmpty()) {
				L.add("### 지원 중단됨 (값이 무시됨)");
				tests.unsupported()
					.values()
					.forEach((p) -> L.add("- `" + p.get("key") + "` (" + p.get("source") + ")"));
				L.add("");
			}
		}
		else {
			L.add("테스트 로그에서 발견된 것 없음. 테스트 프로파일은 외부 설정 저장소를 끄는 경우가 많으니 개발/스테이징 배포 후");
			L.add("기동 로그에서 `The use of configuration keys that` 를 검색해서 다시 확인한다.");
			L.add("");
		}

		for (String[] sec : new String[][] {
				{ "removal", "제거 예정 API 사용 ([removal])", "다음 단계로 올리면 컴파일이 깨질 수 있는 곳. 다음 단계 전에 먼저 정리한다." },
				{ "deprecation", "deprecated API 사용", "당장 문제는 없지만 이후 버전에서 제거될 수 있다." } }) {
			Map<String, Set<String>> w = warnings.get(sec[0]);
			if (!w.isEmpty()) {
				L.add("## " + sec[1]);
				L.add(sec[2]);
				L.add("");
				w.entrySet().stream().sorted((a, b) -> b.getValue().size() - a.getValue().size()).forEach((e) -> {
					List<String> locs = new ArrayList<>(e.getValue());
					L.add("- **" + e.getKey() + "** " + locs.size() + "곳 ("
							+ String.join(", ", locs.subList(0, Math.min(3, locs.size())))
							+ ((locs.size() > 3) ? " …" : "") + ")");
				});
				L.add("");
			}
		}

		if (!deps.changed().isEmpty() || !deps.added().isEmpty() || !deps.removed().isEmpty()) {
			L.add("## 의존성 버전 변경 (transitive 포함, 전 모듈)");
			L.add("변경 " + deps.changed().size() + "개 / 추가 " + deps.added().size() + "개 / 제거 " + deps.removed().size()
					+ "개. 라이브러리 버그는 여기서 나오므로 major/minor 변경을 먼저 확인한다.");
			L.add("");
			List<Map<String, Object>> important = deps.changed()
				.stream()
				.map((o) -> (Map<String, Object>) o)
				.filter((r) -> !"patch".equals(r.get("level")))
				.toList();
			if (!important.isEmpty()) {
				L.add("### major / minor 변경");
				L.add("| 라이브러리 | 이전 | 이후 | 구분 |");
				L.add("|---|---|---|---|");
				important.forEach((r) -> L.add("| `" + r.get("name") + "` | " + r.get("before") + " | " + r.get("after")
						+ " | " + r.get("level") + " |"));
				L.add("");
			}
			if (!deps.added().isEmpty()) {
				L.add("추가된 의존성 " + joinCode(deps.added(), 30, "개"));
				L.add("");
			}
			if (!deps.removed().isEmpty()) {
				L.add("제거된 의존성 " + joinCode(deps.removed(), 30, "개"));
				L.add("");
			}
		}

		if (!in.knownIssues().isEmpty()) {
			L.add("## 알려진 이슈 (" + in.stage() + ")");
			L.add("컴파일/테스트가 통과해도 확인할 항목. playbook/known-issues.yml 기준"
					+ ((in.guide() != null) ? " (원문 " + in.guide() + ")" : ""));
			L.add("");
			for (String[] sec : new String[][] { { "REPORT_ONLY", "사람이 판단 (자동으로 바꾸지 않음)" },
					{ "REVIEW_REQUIRED", "레시피가 바꿨지만 확인 필요" }, { "AUTO_FIX", "레시피가 보정 (결과만 확인)" } }) {
				List<Map<String, Object>> items = in.knownIssues()
					.stream()
					.filter((i) -> sec[0].equals(i.get("mode")))
					.toList();
				if (!items.isEmpty()) {
					L.add("### " + sec[1]);
					for (Map<String, Object> i : items) {
						String box = sec[0].equals("AUTO_FIX") ? "-" : "- [ ]";
						String trigger = (i.get("trigger") != null) ? " (" + i.get("trigger") + ")" : "";
						String link = (i.get("source") != null) ? " [원문](" + i.get("source") + ")" : "";
						L.add(box + " **" + i.get("title") + "**" + trigger);
						if (i.get("detail") != null || !link.isEmpty()) {
							L.add("  " + ((i.get("detail") != null) ? i.get("detail") : "") + link);
						}
					}
					L.add("");
				}
			}
		}

		if (!manual.isEmpty()) {
			L.add("## 수동 검토 대상 (FindManualMigrationItems)");
			L.add("레시피가 자동으로 바꾸지 않는 것. 항목별 이유는 find-manual.yml 주석 참고.");
			L.add("");
			manual.forEach((m) -> L.add("- `" + m.get("file") + "` 의 `" + truncate((String) m.get("code"), 120) + "`"));
			L.add("");
		}
		return String.join("\n", L);
	}

	private static long countMode(List<Map<String, Object>> issues, String mode) {
		return issues.stream().filter((i) -> mode.equals(i.get("mode"))).count();
	}

	private static String joinCode(Collection<String> items, int max, String unit) {
		List<String> list = new ArrayList<>(items);
		List<String> shown = list.subList(0, Math.min(max, list.size())).stream().map((s) -> "`" + s + "`").toList();
		return String.join(", ", shown) + ((list.size() > max) ? " 외 " + (list.size() - max) + unit : "");
	}

	private static String truncate(String s, int max) {
		return (s.length() > max) ? s.substring(0, max) : s;
	}

	private static List<String> lines(Path file) {
		if (file == null || !Files.exists(file)) {
			return List.of();
		}
		return MigrationWorkspace.read(file).lines().toList();
	}

	private static int intAttr(Element e, String name) {
		String v = e.getAttribute(name);
		return v.matches("\\d+") ? Integer.parseInt(v) : 0;
	}

	private static Element firstChild(Element parent, String tag) {
		for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
			if (n instanceof Element e && e.getTagName().equals(tag)) {
				return e;
			}
		}
		return null;
	}

	private static DocumentBuilder xmlParser() {
		try {
			DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
			factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
			factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
			factory.setExpandEntityReferences(false);
			return factory.newDocumentBuilder();
		}
		catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

	private static void writeFile(Path file, String content) {
		try {
			Files.createDirectories(file.getParent());
			Files.writeString(file, content);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	/**
	 * @param compileOk 1 | 0 | skip (러너가 판단한 컴파일 결과)
	 * @param buildOk 1 | 0 | skip
	 * @param baselineBuildOk 0 이면 빌드 실패를 원본에서도 실패하던 기존 문제로 표시한다
	 * @param knownIssues 러너가 playbook 에서 고른 알려진 이슈 (id, mode, title, detail, source, fix,
	 * trigger)
	 * @param projectRecipes 대상 프로젝트의 .rewrite/ 레시피 이름 (자동 보정 내역에 커스텀 보정으로 센다)
	 */
	record Input(String stage, Path projectDir, Path compileLog, Path rewriteLog, Path findPatch, Path versionsBefore,
			Path versionsAfter, String compileOk, String buildOk, String baselineBuildOk,
			List<Map<String, Object>> knownIssues, String guide, List<FailureHint> failureHints,
			Set<String> projectRecipes) {
	}

	/** renamed / unsupported 는 "key|source" 로 중복을 없앤 {key, replacement, source} */
	private record Tests(int total, List<Map<String, Object>> failures, Map<String, Map<String, Object>> renamed,
			Map<String, Map<String, Object>> unsupported) {
	}

	private record Fixes(Map<String, Set<String>> byRecipe, Set<String> changed) {
	}

	private record RecipeNode(int indent, String name) {
	}

	private record Deps(List<Object> changed, List<String> added, List<String> removed) {
	}

}
