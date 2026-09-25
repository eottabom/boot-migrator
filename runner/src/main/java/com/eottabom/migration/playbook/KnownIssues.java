package com.eottabom.migration.playbook;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** playbook/known-issues.yml: 알려진 이슈 레지스트리와 테스트 실패 힌트. */
public record KnownIssues(List<Issue> issues, Map<String, String> guides, List<FailureHint> failureHints) {

	public static KnownIssues load(Path file) {
		Map<String, Object> root = Yaml.load(file);
		List<Issue> issues = new ArrayList<>();
		for (Object item : Yaml.list(root.get("issues"))) {
			Map<String, Object> m = Yaml.map(item);
			Issue issue = new Issue(Yaml.string(m.get("id")), Mode.valueOf(Yaml.string(m.get("mode"))),
					Yaml.string(m.get("title")), Yaml.string(m.get("detail")), Yaml.string(m.get("source")),
					Yaml.string(m.get("fix")), Yaml.string(m.get("stage")),
					Yaml.list(m.get("requires")).stream().map(Yaml::string).toList(), Yaml.string(m.get("dependency")),
					Yaml.string(m.get("crosses")), Yaml.list(m.get("affected")).stream().map(Yaml::string).toList());
			validate(issue);
			issues.add(issue);
		}
		Map<String, String> guides = new LinkedHashMap<>();
		Yaml.map(root.get("guides")).forEach((k, v) -> guides.put(k, Yaml.string(v)));
		List<FailureHint> hints = new ArrayList<>();
		for (Object item : Yaml.list(root.get("failureHints"))) {
			Map<String, Object> m = Yaml.map(item);
			FailureHint hint = new FailureHint(Yaml.string(m.get("pattern")), Yaml.string(m.get("text")));
			Pattern.compile(hint.pattern());
			hints.add(hint);
		}
		return new KnownIssues(List.copyOf(issues), guides, List.copyOf(hints));
	}

	private static void validate(Issue issue) {
		String where = "known-issues.yml " + issue.id() + ": ";
		if (issue.id() == null || issue.title() == null) {
			throw new IllegalArgumentException(where + "id 와 title 이 필요하다");
		}
		boolean byStage = issue.stage() != null;
		boolean byDependency = issue.dependency() != null;
		if (byStage == byDependency) {
			throw new IllegalArgumentException(where + "stage 와 dependency 중 하나만 쓴다");
		}
		if (byDependency && (issue.crosses() == null) == issue.affected().isEmpty()) {
			throw new IllegalArgumentException(where + "dependency 에는 crosses 와 affected 중 하나만 쓴다");
		}
		if (!issue.affected().isEmpty() && issue.affected().size() != 2) {
			throw new IllegalArgumentException(where + "affected 는 [from, until)");
		}
		if (issue.mode() == Mode.AUTO_FIX && issue.fix() == null) {
			throw new IllegalArgumentException(where + "AUTO_FIX 에는 fix(레시피)가 필요하다");
		}
	}

	public String guide(String stageKey) {
		return this.guides.get(stageKey);
	}

	/**
	 * 한 단계에 해당하는 이슈.
	 * @param stageKey 3.4 | java21 | gradle ...
	 * @param before 단계 전 resolve 된 버전 (group:artifact → version). 없으면 빈 맵
	 * @param after 단계 후 resolve 된 버전. 없으면 빈 맵 (이 경우 requires 조건은 걸린 것으로 본다)
	 */
	public List<Match> match(String stageKey, Map<String, String> before, Map<String, String> after) {
		List<Match> matches = new ArrayList<>();
		for (Issue issue : this.issues) {
			if (issue.stage() != null) {
				if (issue.stage().equals(stageKey) && requiresMet(issue.requires(), before, after)) {
					matches.add(new Match(issue, null));
				}
			}
			else {
				String from = before.get(issue.dependency());
				String to = after.get(issue.dependency());
				if (from != null && to != null && !from.equals(to) && versionHit(issue, from, to)) {
					matches.add(new Match(issue, "`" + issue.dependency() + "` " + from + " → " + to));
				}
			}
		}
		return matches;
	}

	/** 의존성 정보 없이 단계만으로 볼 수 있는 이슈 (migrationPlan 미리보기). requires 는 걸린 것으로 본다. */
	public List<Issue> forStage(String stageKey) {
		return this.issues.stream().filter((i) -> stageKey.equals(i.stage())).toList();
	}

	private static boolean versionHit(Issue issue, String from, String to) {
		if (issue.crosses() != null) {
			return Versions.compare(from, issue.crosses()) < 0 && Versions.compare(to, issue.crosses()) >= 0;
		}
		return Versions.compare(to, issue.affected().get(0)) >= 0 && Versions.compare(to, issue.affected().get(1)) < 0;
	}

	private static boolean requiresMet(List<String> requires, Map<String, String> before, Map<String, String> after) {
		if (requires.isEmpty() || (before.isEmpty() && after.isEmpty())) {
			return true;
		}
		List<Pattern> patterns = requires.stream().map(KnownIssues::glob).toList();
		for (Set<String> keys : List.of(before.keySet(), after.keySet())) {
			for (String key : keys) {
				if (patterns.stream().anyMatch((p) -> p.matcher(key).matches())) {
					return true;
				}
			}
		}
		return false;
	}

	private static Pattern glob(String pattern) {
		StringBuilder regex = new StringBuilder();
		for (char c : pattern.toCharArray()) {
			regex.append((c == '*') ? ".*" : Pattern.quote(String.valueOf(c)));
		}
		return Pattern.compile(regex.toString());
	}

	public enum Mode {

		/** 레시피가 고친다. 결과 확인용 */
		AUTO_FIX,
		/** 레시피가 바꿨거나 바꿀 수 있지만 사람이 확인해야 한다 */
		REVIEW_REQUIRED,
		/** 자동으로 바꾸지 않는다. 도메인/운영 판단 */
		REPORT_ONLY

	}

	/**
	 * stage 조건이면 stage (+ requires), 라이브러리 조건이면 dependency + crosses 또는 affected 를 쓴다.
	 */
	public record Issue(String id, Mode mode, String title, String detail, String source, String fix, String stage,
			List<String> requires, String dependency, String crosses, List<String> affected) {
	}

	/**
	 * @param trigger 라이브러리 조건으로 걸린 경우 "group:artifact 이전 → 이후", 단계 조건이면 null
	 */
	public record Match(Issue issue, String trigger) {
	}

	public record FailureHint(String pattern, String text) {
	}
}
