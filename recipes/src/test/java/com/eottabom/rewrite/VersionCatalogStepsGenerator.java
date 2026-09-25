package com.eottabom.rewrite;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.openrewrite.Recipe;
import org.openrewrite.config.DeclarativeRecipe;
import org.openrewrite.config.Environment;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

/**
 * 단계 레시피 트리에 들어 있는 의존성/플러그인 버전 변경을 모아 version catalog 용 레시피를 만든다.
 * <p>
 * upstream 레시피는 빌드 스크립트만 바꾸므로, 같은 변경을 {@code UpgradeVersionCatalog} 규칙으로 옮겨 catalog 에도
 * 적용한다. upstream 단계는 생성된 upstream-spring-boot-steps.yml 대신 rewrite-spring 의 원래 레시피에서 직전
 * 단계만 빼고 읽는다. 그래서 rewrite-recipe-bom 을 올린 뒤 한 번의 syncUpstreamSteps 로 두 파일이 함께 맞춰진다.
 */
public final class VersionCatalogStepsGenerator {

	public static final Path OUTPUT = Path.of("src/main/resources/META-INF/rewrite/version-catalog-steps.yml");

	static final String PREFIX = "com.eottabom.rewrite.gradle.catalog.VersionCatalog_";

	/** 단계 → 러너가 실행하는 레시피 */
	private static final Map<String, String> STAGES = new LinkedHashMap<>();

	static {
		for (String v : List.of("3_0", "3_1", "3_2", "3_3", "3_4", "3_5", "4_0", "4_1")) {
			STAGES.put(v, "com.eottabom.rewrite.spring.SpringBootStep_" + v);
		}
		STAGES.put("Java21", "com.eottabom.rewrite.java.UpgradeToJava21");
		STAGES.put("Java25", "com.eottabom.rewrite.java.UpgradeToJava25");
		STAGES.put("Gradle8_14", "com.eottabom.rewrite.gradle.UpgradeGradleToolchain_8_14");
	}

	private VersionCatalogStepsGenerator() {
	}

	public static void main(String[] args) throws IOException {
		Files.writeString(OUTPUT, generate());
		System.out.println("생성: " + OUTPUT);
	}

	static String generate() {
		Environment env = Environment.builder().scanRuntimeClasspath().build();
		List<Object> docs = new ArrayList<>();
		for (Map.Entry<String, String> stage : STAGES.entrySet()) {
			Set<String> rules = new LinkedHashSet<>();
			Set<String> conditional = new LinkedHashSet<>();
			new Walker(env, rules, conditional, new int[] { 0 }, new String[1])
				.walk(env.activateRecipes(stage.getValue()));
			conditional.removeAll(rules);
			Map<String, Object> options = new LinkedHashMap<>();
			options.put("rules", new ArrayList<>(rules));
			Map<String, Object> doc = new LinkedHashMap<>();
			doc.put("type", "specs.openrewrite.org/v1beta/recipe");
			doc.put("name", PREFIX + stage.getKey());
			doc.put("displayName", "version catalog 정렬 (" + stage.getKey() + ")");
			doc.put("description",
					stage.getValue() + " 의 의존성/플러그인 버전 변경을 gradle/*.versions.toml 에 적용한다."
							+ (conditional.isEmpty() ? "" : " upstream 조건(precondition) 안의 규칙 " + conditional.size()
									+ "개는 catalog 에서 조건을 판단할 수 없어 뺐다."));
			doc.put("recipeList", List.of(Map.of("com.eottabom.rewrite.gradle.UpgradeVersionCatalog", options)));
			docs.add(doc);
		}
		DumperOptions options = new DumperOptions();
		options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
		options.setAllowUnicode(true);
		options.setWidth(200);
		return "# 생성 파일: ./gradlew syncUpstreamSteps (VersionCatalogStepsGenerator). 직접 고치지 않는다.\n" + "---\n"
				+ new Yaml(options).dumpAll(docs.iterator());
	}

	/**
	 * @param conditional upstream 조건 안에 있어서 뺀 규칙
	 * @param depth 지나온 조건부 레시피 수 (0 이면 조건 밖)
	 * @param plugin 지나온 ModuleHasPlugin 조건의 플러그인 id. catalog 레시피가 Gradle 모델로 판단한다
	 */
	private record Walker(Environment env, Set<String> rules, Set<String> conditional, int[] depth, String[] plugin) {

		void walk(Recipe recipe) {
			Recipe r = unwrap(recipe);
			String name = r.getName();
			if (name.startsWith(PREFIX)) {
				return;
			}
			String rule = rule(r);
			if (rule != null) {
				String withCondition = (this.plugin[0] != null) ? rule + " when-plugin " + this.plugin[0] : rule;
				((this.depth[0] == 0) ? this.rules : this.conditional).add(withCondition);
				return;
			}
			String requiredPlugin = requiredPlugin(r);
			if (requiredPlugin != null && this.plugin[0] == null) {
				this.plugin[0] = requiredPlugin;
				walkChildren(r);
				this.plugin[0] = null;
				return;
			}
			// ModuleHasDependency, DoesNotUseType 같은 조건은 빌드 파일과 소스 기준이라 catalog 에서는 판단할 수
			// 없다
			if (hasPreconditions(r)) {
				this.depth[0]++;
				walkChildren(r);
				this.depth[0]--;
				return;
			}
			walkChildren(r);
		}

		private void walkChildren(Recipe r) {
			String name = r.getName();
			if (name.startsWith(UpstreamStepsGenerator.STEP_PREFIX)) {
				String version = name.substring(UpstreamStepsGenerator.STEP_PREFIX.length()).replace('_', '.');
				String upstream = UpstreamStepsGenerator.upstreamOf(version);
				String previous = UpstreamStepsGenerator.previousOf(version);
				for (Recipe child : this.env.activateRecipes(upstream).getRecipeList()) {
					if (!unwrap(child).getName().equals(previous)) {
						walk(child);
					}
				}
				return;
			}
			r.getRecipeList().forEach(this::walk);
		}

		private static boolean hasPreconditions(Recipe r) {
			return !preconditions(r).isEmpty();
		}

		/** 조건이 ModuleHasPlugin(pluginId) 하나뿐이면 그 플러그인 id */
		private static String requiredPlugin(Recipe r) {
			List<Recipe> preconditions = preconditions(r);
			if (preconditions.size() != 1
					|| !"org.openrewrite.gradle.search.ModuleHasPlugin".equals(preconditions.get(0).getName())
					|| field(preconditions.get(0), "pluginClass") != null) {
				return null;
			}
			return (String) field(preconditions.get(0), "pluginId");
		}

		/** Singleton 을 뺀 조건 */
		@SuppressWarnings("unchecked")
		private static List<Recipe> preconditions(Recipe r) {
			if (!(r instanceof DeclarativeRecipe)) {
				return List.of();
			}
			return ((List<Recipe>) field(r, "preconditions")).stream()
				.map(Walker::unwrap)
				.filter((p) -> !"org.openrewrite.Singleton".equals(p.getName()))
				.toList();
		}

		private static Recipe unwrap(Recipe recipe) {
			if (!recipe.getClass().getName().contains("BellwetherDecorated")) {
				return recipe;
			}
			return (Recipe) field(recipe, "delegate");
		}

		private static String rule(Recipe r) {
			return switch (r.getClass().getName()) {
				case "org.openrewrite.java.dependencies.UpgradeDependencyVersion",
						"org.openrewrite.gradle.UpgradeDependencyVersion" ->
					join("dependency", field(r, "groupId") + ":" + field(r, "artifactId"), field(r, "newVersion"),
							field(r, "versionPattern"));
				case "org.openrewrite.gradle.plugins.UpgradePluginVersion" ->
					join("plugin", field(r, "pluginIdPattern"), field(r, "newVersion"), field(r, "versionPattern"));
				case "org.openrewrite.java.dependencies.ChangeDependency",
						"org.openrewrite.gradle.ChangeDependency" ->
					join("change",
							field(r, "oldGroupId") + ":" + field(r, "oldArtifactId") + " " + or(field(r, "newGroupId"))
									+ ":" + or(field(r, "newArtifactId")),
							field(r, "newVersion"), field(r, "versionPattern"));
				default -> null;
			};
		}

		private static String join(String kind, Object coordinates, Object newVersion, Object versionPattern) {
			if (newVersion == null && !"change".equals(kind)) {
				return null;
			}
			StringBuilder rule = new StringBuilder(kind).append(' ').append(coordinates);
			if (newVersion != null) {
				rule.append(' ').append(newVersion);
				if (versionPattern != null) {
					rule.append(' ').append(versionPattern);
				}
			}
			return rule.toString();
		}

		private static Object or(Object value) {
			return (value != null) ? value : "*";
		}

		private static Object field(Object target, String name) {
			for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
				try {
					Field field = type.getDeclaredField(name);
					field.setAccessible(true);
					return field.get(target);
				}
				catch (NoSuchFieldException ex) {
					// 상위 클래스에서 찾는다
				}
				catch (IllegalAccessException ex) {
					throw new IllegalStateException(ex);
				}
			}
			throw new IllegalStateException(target.getClass().getName() + " 에 " + name + " 필드가 없다");
		}

	}

}
