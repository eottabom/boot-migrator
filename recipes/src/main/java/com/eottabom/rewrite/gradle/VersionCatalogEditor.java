package com.eottabom.rewrite.gradle;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.openrewrite.internal.StringUtils;

/**
 * Gradle version catalog(libs.versions.toml) 텍스트에 버전 규칙을 적용한다.
 * <p>
 * 한 줄에 한 항목인 일반적인 형태만 다룬다. rich version({@code strictly}, {@code prefer})과 여러 줄에 걸친
 * inline table 은 건드리지 않는다. 주석과 정렬은 그대로 둔다.
 */
final class VersionCatalogEditor {

	private static final Pattern KEY_VALUE = Pattern.compile("^\\s*\"?([A-Za-z0-9_.\\-]+)\"?\\s*=\\s*(.*)$");

	private static final Pattern SECTION = Pattern.compile("^\\s*\\[([A-Za-z0-9_.\\-]+)]\\s*(#.*)?$");

	private static final Pattern STRING = Pattern.compile("^\"([^\"]*)\"");

	private static final Pattern MODULE = Pattern.compile("\\bmodule\\s*=\\s*\"([^\"]*)\"");

	private static final Pattern GROUP = Pattern.compile("\\bgroup\\s*=\\s*\"([^\"]*)\"");

	private static final Pattern NAME = Pattern.compile("\\bname\\s*=\\s*\"([^\"]*)\"");

	private static final Pattern ID = Pattern.compile("\\bid\\s*=\\s*\"([^\"]*)\"");

	private static final Pattern VERSION = Pattern.compile("\\bversion\\s*=\\s*\"([^\"]*)\"");

	private static final Pattern VERSION_REF = Pattern
		.compile("\\bversion(?:\\.ref\\s*=\\s*|\\s*=\\s*\\{\\s*ref\\s*=\\s*)\"([^\"]*)\"");

	private final List<String> lines;

	private final Resolver resolver;

	private VersionCatalogEditor(String toml, Resolver resolver) {
		this.lines = new ArrayList<>(Arrays.asList(toml.split("\n", -1)));
		this.resolver = resolver;
	}

	/**
	 * @param plugins 대상 프로젝트 모듈이 적용한 플러그인 id. 플러그인 조건이 붙은 규칙은 그 플러그인이 있을 때만 적용한다
	 */
	static String apply(String toml, List<Rule> rules, Resolver resolver, Set<String> plugins) {
		VersionCatalogEditor editor = new VersionCatalogEditor(toml, resolver);
		for (Rule rule : rules) {
			if (rule.requiredPlugin() == null || plugins.contains(rule.requiredPlugin())) {
				editor.apply(rule);
			}
		}
		return String.join("\n", editor.lines);
	}

	private void apply(Rule rule) {
		Map<String, String> resolvedKeys = new HashMap<>();
		// 항목 수는 그대로고 줄 번호만 밀릴 수 있어서 항목마다 다시 읽는다
		for (int i = 0; i < parse().entries().size(); i++) {
			Catalog catalog = parse();
			Entry entry = catalog.entries().get(i);
			if (!rule.matches(entry)) {
				continue;
			}
			if (rule.kind() == Rule.Kind.CHANGE) {
				change(catalog, entry, rule);
				continue;
			}
			String current = catalog.versionOf(entry);
			if (current == null) {
				continue;
			}
			String target = (entry.ref() != null)
					? resolvedKeys.computeIfAbsent(entry.ref(), (k) -> resolve(entry, current, rule))
					: resolve(entry, current, rule);
			if (target == null || target.equals(current)) {
				continue;
			}
			if (entry.ref() != null) {
				setVersionKey(catalog, entry.ref(), target);
			}
			else {
				setInlineVersion(entry, current, target);
			}
		}
	}

	private String resolve(Entry entry, String current, Rule rule) {
		return this.resolver.resolve(entry.group(), entry.artifact(), current, rule.newVersion(), rule.versionPattern(),
				entry.plugin());
	}

	private void change(Catalog catalog, Entry entry, Rule rule) {
		String group = "*".equals(rule.newGroup()) ? entry.group() : rule.newGroup();
		String artifact = "*".equals(rule.newArtifact()) ? entry.artifact() : rule.newArtifact();
		String current = catalog.versionOf(entry);
		String target = null;
		if (current != null && rule.newVersion() != null) {
			target = this.resolver.resolve(group, artifact, null, rule.newVersion(), rule.versionPattern(), false);
			// 좌표만 바꾸고 버전을 못 올리면 새 좌표에 없는 버전이 남는다
			if (target == null) {
				return;
			}
		}
		setCoordinates(entry, group, artifact);
		if (target == null || target.equals(current)) {
			return;
		}
		if (entry.ref() == null) {
			setInlineVersion(entry, current, target);
		}
		else if (catalog.refUsers(entry.ref()).stream().allMatch(rule::matches)) {
			setVersionKey(catalog, entry.ref(), target);
		}
		else {
			// 같은 버전 키를 쓰는 다른 항목(ex. jackson-annotations)은 이전 버전에 남아야 한다
			String key = catalog.newKey(entry.alias());
			int at = catalog.versions().get(entry.ref()) + 1;
			this.lines.add(at, key + " = \"" + target + "\"");
			int line = (at <= entry.line()) ? entry.line() + 1 : entry.line();
			this.lines.set(line, replaceGroup(this.lines.get(line), VERSION_REF, entry.ref(), key));
		}
	}

	private void setVersionKey(Catalog catalog, String key, String version) {
		int line = catalog.versions().get(key);
		String text = this.lines.get(line);
		Matcher m = KEY_VALUE.matcher(text);
		m.matches();
		int start = m.start(2);
		this.lines.set(line, text.substring(0, start)
				+ text.substring(start).replaceFirst("\"[^\"]*\"", Matcher.quoteReplacement("\"" + version + "\"")));
	}

	private void setInlineVersion(Entry entry, String current, String version) {
		String text = this.lines.get(entry.line());
		if (entry.notation()) {
			this.lines.set(entry.line(), text.replace(":" + current + "\"", ":" + version + "\""));
		}
		else {
			this.lines.set(entry.line(), replaceGroup(text, VERSION, current, version));
		}
	}

	private void setCoordinates(Entry entry, String group, String artifact) {
		String text = this.lines.get(entry.line());
		if (entry.notation()) {
			text = text.replace("\"" + entry.group() + ":" + entry.artifact(), "\"" + group + ":" + artifact);
		}
		else if (MODULE.matcher(text).find()) {
			text = replaceGroup(text, MODULE, entry.group() + ":" + entry.artifact(), group + ":" + artifact);
		}
		else {
			text = replaceGroup(replaceGroup(text, GROUP, entry.group(), group), NAME, entry.artifact(), artifact);
		}
		this.lines.set(entry.line(), text);
	}

	private static String replaceGroup(String text, Pattern pattern, String from, String to) {
		Matcher m = pattern.matcher(text);
		while (m.find()) {
			if (m.group(1).equals(from)) {
				return text.substring(0, m.start(1)) + to + text.substring(m.end(1));
			}
		}
		return text;
	}

	private Catalog parse() {
		Map<String, Integer> versions = new LinkedHashMap<>();
		Map<String, String> versionValues = new HashMap<>();
		List<Entry> entries = new ArrayList<>();
		String section = "";
		for (int i = 0; i < this.lines.size(); i++) {
			String text = this.lines.get(i);
			Matcher s = SECTION.matcher(text);
			if (s.matches()) {
				section = s.group(1);
				continue;
			}
			Matcher kv = KEY_VALUE.matcher(text);
			if (text.trim().startsWith("#") || !kv.matches()) {
				continue;
			}
			String key = kv.group(1);
			String value = kv.group(2);
			switch (section) {
				case "versions" -> {
					Matcher str = STRING.matcher(value);
					if (str.find()) {
						versions.put(key, i);
						versionValues.put(key, str.group(1));
					}
				}
				case "libraries" -> {
					Entry entry = library(i, key, value);
					if (entry != null) {
						entries.add(entry);
					}
				}
				case "plugins" -> {
					Entry entry = plugin(i, key, value);
					if (entry != null) {
						entries.add(entry);
					}
				}
				default -> {
				}
			}
		}
		return new Catalog(versions, versionValues, entries);
	}

	private static Entry library(int line, String alias, String value) {
		Matcher str = STRING.matcher(value);
		if (str.find()) {
			String[] gav = str.group(1).split(":");
			if (gav.length < 2) {
				return null;
			}
			return new Entry(line, alias, false, true, gav[0], gav[1], (gav.length > 2) ? gav[2] : null, null);
		}
		if (!value.startsWith("{")) {
			return null;
		}
		String group;
		String artifact;
		Matcher module = MODULE.matcher(value);
		if (module.find()) {
			String[] ga = module.group(1).split(":");
			if (ga.length != 2) {
				return null;
			}
			group = ga[0];
			artifact = ga[1];
		}
		else {
			group = find(GROUP, value);
			artifact = find(NAME, value);
			if (group == null || artifact == null) {
				return null;
			}
		}
		return new Entry(line, alias, false, false, group, artifact, find(VERSION, value), find(VERSION_REF, value));
	}

	private static Entry plugin(int line, String alias, String value) {
		Matcher str = STRING.matcher(value);
		if (str.find()) {
			String[] parts = str.group(1).split(":");
			return new Entry(line, alias, true, true, parts[0], parts[0] + ".gradle.plugin",
					(parts.length > 1) ? parts[1] : null, null);
		}
		String id = find(ID, value);
		if (id == null) {
			return null;
		}
		return new Entry(line, alias, true, false, id, id + ".gradle.plugin", find(VERSION, value),
				find(VERSION_REF, value));
	}

	private static String find(Pattern pattern, String value) {
		Matcher m = pattern.matcher(value);
		return m.find() ? m.group(1) : null;
	}

	/**
	 * upstream 의 버전 변경 레시피 하나를 catalog 에 옮긴 규칙.
	 * <ul>
	 * <li>{@code dependency <group>:<artifact> <newVersion> [<versionPattern>]}</li>
	 * <li>{@code plugin <id> <newVersion> [<versionPattern>]}</li>
	 * <li>{@code change <group>:<artifact> <newGroup>:<newArtifact> [<newVersion> [<versionPattern>]]}
	 * ({@code *} 는 그대로 둔다)</li>
	 * </ul>
	 * 좌표는 glob 을 쓸 수 있다. 끝에 {@code when-plugin <id>} 가 붙으면 그 플러그인을 적용한 모듈이 있을 때만 적용한다
	 * (upstream 의 ModuleHasPlugin 조건).
	 */
	record Rule(Kind kind, String group, String artifact, String newGroup, String newArtifact, String newVersion,
			String versionPattern, String requiredPlugin) {

		private static final String WHEN_PLUGIN = " when-plugin ";

		static Rule parse(String rule) {
			String body = rule.trim();
			String requiredPlugin = null;
			int when = body.indexOf(WHEN_PLUGIN);
			if (when >= 0) {
				requiredPlugin = body.substring(when + WHEN_PLUGIN.length()).trim();
				body = body.substring(0, when);
			}
			String[] t = body.split("\\s+");
			Kind kind = Kind.valueOf(t[0].toUpperCase());
			return switch (kind) {
				case DEPENDENCY -> {
					String[] ga = t[1].split(":");
					yield new Rule(kind, ga[0], ga[1], null, null, t[2], arg(t, 3), requiredPlugin);
				}
				case PLUGIN -> new Rule(kind, t[1], null, null, null, t[2], arg(t, 3), requiredPlugin);
				case CHANGE -> {
					String[] ga = t[1].split(":");
					String[] target = t[2].split(":");
					yield new Rule(kind, ga[0], ga[1], target[0], target[1], arg(t, 3), arg(t, 4), requiredPlugin);
				}
			};
		}

		private static String arg(String[] tokens, int index) {
			return (tokens.length > index) ? tokens[index] : null;
		}

		boolean matches(Entry entry) {
			if (this.kind == Kind.PLUGIN) {
				return entry.plugin() && StringUtils.matchesGlob(entry.group(), this.group);
			}
			return !entry.plugin() && StringUtils.matchesGlob(entry.group(), this.group)
					&& StringUtils.matchesGlob(entry.artifact(), this.artifact);
		}

		enum Kind {

			DEPENDENCY, PLUGIN, CHANGE

		}

	}

	/**
	 * 새 버전을 고른다. 올릴 버전이 없으면 null.
	 */
	@FunctionalInterface
	interface Resolver {

		/**
		 * @param currentVersion null 이면 현재 버전과 비교하지 않는다 (좌표가 바뀌는 경우)
		 * @param plugin group 이 plugin id, artifact 가 plugin marker 이다
		 */
		String resolve(String group, String artifact, String currentVersion, String newVersion, String versionPattern,
				boolean plugin);

	}

	/**
	 * @param notation {@code "g:a:v"} 문자열 표기
	 * @param group plugin 이면 plugin id
	 */
	private record Entry(int line, String alias, boolean plugin, boolean notation, String group, String artifact,
			String version, String ref) {
	}

	/**
	 * @param versions [versions] 키와 줄 번호
	 */
	private record Catalog(Map<String, Integer> versions, Map<String, String> versionValues, List<Entry> entries) {

		String versionOf(Entry entry) {
			return (entry.ref() != null) ? this.versionValues.get(entry.ref()) : entry.version();
		}

		List<Entry> refUsers(String ref) {
			return this.entries.stream().filter((e) -> ref.equals(e.ref())).toList();
		}

		String newKey(String alias) {
			String key = alias;
			for (int i = 2; this.versions.containsKey(key); i++) {
				key = alias + "-" + i;
			}
			return key;
		}

	}

}
