package com.eottabom.migration.recipe;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import org.yaml.snakeyaml.Yaml;

/**
 * 대상 프로젝트가 가진 OpenRewrite 선언형 레시피(개발자 관리). 러너는 이 파일들을 고치지 않고 읽기만 한다.
 *
 * <pre>
 * &lt;project&gt;/
 *   rewrite.yml                 OpenRewrite 기본 위치 (있으면)
 *   .rewrite/rewrite.yml
 *   .rewrite/custom/**.yml
 * </pre>
 *
 * 마이그레이션 단계에 붙일 레시피는 OpenRewrite 표준 필드인 tags 로 표시한다. <pre>
 * tags:
 *   - "migration-stage:3.4"      이 단계에서 실행 (3.0 ~ 4.1 | java21 | java25 | gradle | *)
 *   - "migration-phase:before"   단계 레시피보다 먼저 (기본 after)
 * </pre> 태그가 없는 레시피는 부품으로 보고, 다른 레시피가 참조할 때만 쓰인다.
 */
public record ProjectRecipes(List<Path> files, List<Map<String, Object>> documents, List<ProjectRecipe> recipes) {

	static final String RECIPE_TYPE = "specs.openrewrite.org/v1beta/recipe";

	private static final String STAGE_TAG = "migration-stage:";

	private static final String PHASE_TAG = "migration-phase:";

	public enum Phase {

		BEFORE, AFTER

	}

	/**
	 * @param stages 붙일 단계 키. "*" 는 모든 단계
	 */
	public record ProjectRecipe(String name, Path file, Set<String> stages, Phase phase) {

		public boolean appliesTo(String stageKey) {
			return this.stages.contains("*") || this.stages.contains(stageKey);
		}
	}

	public static ProjectRecipes none() {
		return new ProjectRecipes(List.of(), List.of(), List.of());
	}

	public static ProjectRecipes discover(Path projectDir) {
		List<Path> files = new ArrayList<>();
		addIfFile(files, projectDir.resolve("rewrite.yml"));
		addIfFile(files, projectDir.resolve(".rewrite/rewrite.yml"));
		Path custom = projectDir.resolve(".rewrite/custom");
		if (Files.isDirectory(custom)) {
			try (Stream<Path> walk = Files.walk(custom)) {
				walk.filter((p) -> p.toString().endsWith(".yml") || p.toString().endsWith(".yaml"))
					.sorted()
					.forEach(files::add);
			}
			catch (IOException ex) {
				throw new UncheckedIOException(ex);
			}
		}

		List<Map<String, Object>> documents = new ArrayList<>();
		List<ProjectRecipe> recipes = new ArrayList<>();
		for (Path file : files) {
			for (Map<String, Object> doc : load(file)) {
				documents.add(doc);
				if (RECIPE_TYPE.equals(doc.get("type")) && doc.get("name") != null) {
					tagged(String.valueOf(doc.get("name")), file, doc.get("tags")).ifPresent(recipes::add);
				}
			}
		}
		return new ProjectRecipes(List.copyOf(files), List.copyOf(documents), List.copyOf(recipes));
	}

	public List<String> names(String stageKey, Phase phase) {
		return this.recipes.stream()
			.filter((r) -> r.phase() == phase && r.appliesTo(stageKey))
			.map(ProjectRecipe::name)
			.toList();
	}

	private static Optional<ProjectRecipe> tagged(String name, Path file, Object tags) {
		if (!(tags instanceof List<?> list)) {
			return Optional.empty();
		}
		Set<String> stages = new LinkedHashSet<>();
		Phase phase = Phase.AFTER;
		for (Object tag : list) {
			String t = String.valueOf(tag).trim();
			if (t.startsWith(STAGE_TAG)) {
				stages.add(t.substring(STAGE_TAG.length()).trim());
			}
			else if (t.startsWith(PHASE_TAG)) {
				phase = Phase.valueOf(t.substring(PHASE_TAG.length()).trim().toUpperCase());
			}
		}
		return stages.isEmpty() ? Optional.empty()
				: Optional.of(new ProjectRecipe(name, file, Set.copyOf(stages), phase));
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> load(Path file) {
		List<Map<String, Object>> docs = new ArrayList<>();
		try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			for (Object doc : new Yaml().loadAll(in)) {
				// 빈 문서(--- 연속 등)는 버린다
				if (doc instanceof Map<?, ?> map) {
					docs.add((Map<String, Object>) map);
				}
				else if (doc != null) {
					throw new IllegalArgumentException(file + ": YAML 문서가 맵이 아니다");
				}
			}
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		catch (RuntimeException ex) {
			throw new IllegalArgumentException("프로젝트 레시피를 읽지 못했다: " + file + " (" + ex.getMessage() + ")", ex);
		}
		return docs;
	}

	private static void addIfFile(List<Path> files, Path file) {
		if (Files.isRegularFile(file)) {
			files.add(file);
		}
	}
}
