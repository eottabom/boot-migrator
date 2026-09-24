package com.eottabom.rewrite;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;

import org.openrewrite.java.spring.boot3.AddRouteTrailingSlash;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

/**
 * upstream(rewrite-spring) 의 UpgradeSpringBoot_X_Y 에서 직전 단계 체인 항목만 뺀 "단계 레시피" 를 만든다.
 * <p>
 * upstream 단계 레시피는 첫 항목으로 직전 단계를 부르고, 그 체인이 Boot 2.0 까지 이어진다. 그래서 4.0 프로젝트에 4.1 을 돌려도
 * Boot 2.x 의 best practice 레시피(프로파일별 yml 분리, JUnit4 -> 5 빌드 블록 등)와 이미 지난 단계의 마이그레이션이 다시
 * 돈다. 러너는 단계를 하나씩 실행하므로 각 단계에서는 그 단계의 변경만 필요하다.
 * <p>
 * 결과는 src/main/resources/META-INF/rewrite/upstream-spring-boot-steps.yml 에 넣어 둔다 (생성 파일,
 * 직접 고치지 않는다). rewrite-recipe-bom 을 올리면 UpstreamStepsUpToDateTests 가 깨지고, ./gradlew
 * syncUpstreamSteps 로 다시 만든다.
 */
public final class UpstreamStepsGenerator {

	public static final Path OUTPUT = Path.of("src/main/resources/META-INF/rewrite/upstream-spring-boot-steps.yml");
	static final String STEP_PREFIX = "com.eottabom.rewrite.spring.upstream.UpgradeSpringBootStep_";

	/** 단계 → upstream 레시피. 3.0 은 2.x 에서 올라오는 입구라 체인을 그대로 쓰므로 만들지 않는다. */
	private static final Map<String, String> UPSTREAM = new LinkedHashMap<>();

	static {
		UPSTREAM.put("3.1", "org.openrewrite.java.spring.boot3.UpgradeSpringBoot_3_1");
		UPSTREAM.put("3.2", "org.openrewrite.java.spring.boot3.UpgradeSpringBoot_3_2");
		UPSTREAM.put("3.3", "org.openrewrite.java.spring.boot3.UpgradeSpringBoot_3_3");
		UPSTREAM.put("3.4", "org.openrewrite.java.spring.boot3.UpgradeSpringBoot_3_4");
		UPSTREAM.put("3.5", "org.openrewrite.java.spring.boot3.UpgradeSpringBoot_3_5");
		UPSTREAM.put("4.0", "org.openrewrite.java.spring.boot4.UpgradeSpringBoot_4_0");
	}

	private UpstreamStepsGenerator() {
	}

	public static void main(String[] args) throws IOException {
		Path out = (args.length > 0) ? Path.of(args[0]) : OUTPUT;
		Files.writeString(out, generate());
		System.out.println("생성: " + out);
	}

	@SuppressWarnings("unchecked")
	static String generate() {
		Map<String, Map<String, Object>> upstream = upstreamRecipes();
		List<Object> docs = new ArrayList<>();
		String previous = "org.openrewrite.java.spring.boot3.UpgradeSpringBoot_3_0";
		for (Map.Entry<String, String> e : UPSTREAM.entrySet()) {
			Map<String, Object> source = upstream.get(e.getValue());
			if (source == null) {
				throw new IllegalStateException("rewrite-spring 에 " + e.getValue() + " 이 없다");
			}
			List<Object> recipeList = new ArrayList<>((List<Object>) source.get("recipeList"));
			if (recipeList.isEmpty() || !previous.equals(recipeList.get(0))) {
				throw new IllegalStateException(
						e.getValue() + " 의 첫 항목이 직전 단계(" + previous + ")가 아니다. upstream 구조가 바뀌었는지 확인한다");
			}
			recipeList.remove(0);
			Map<String, Object> step = new LinkedHashMap<>();
			step.put("type", "specs.openrewrite.org/v1beta/recipe");
			step.put("name", STEP_PREFIX + e.getKey().replace('.', '_'));
			step.put("displayName", "upstream Spring Boot " + e.getKey() + " 단계 (직전 단계 체인 제외)");
			step.put("description", e.getValue() + " 에서 첫 항목(" + previous + ")만 뺀 것.");
			if (source.containsKey("preconditions")) {
				step.put("preconditions", source.get("preconditions"));
			}
			step.put("recipeList", recipeList);
			docs.add(step);
			previous = e.getValue();
		}
		DumperOptions options = new DumperOptions();
		options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
		options.setAllowUnicode(true);
		options.setWidth(160);
		return "# 생성 파일: ./gradlew syncUpstreamSteps (UpstreamStepsGenerator). 직접 고치지 않는다.\n" + "# 출처: "
				+ springJar().getFileName() + "\n" + "---\n" + new Yaml(options).dumpAll(docs.iterator());
	}

	/** rewrite-spring jar 의 META-INF/rewrite/*.yml 에서 recipe 문서를 이름으로 모은다. */
	@SuppressWarnings("unchecked")
	private static Map<String, Map<String, Object>> upstreamRecipes() {
		Map<String, Map<String, Object>> recipes = new LinkedHashMap<>();
		try (JarFile jar = new JarFile(springJar().toFile())) {
			Enumeration<? extends ZipEntry> entries = jar.entries();
			while (entries.hasMoreElements()) {
				ZipEntry entry = entries.nextElement();
				if (!entry.getName().startsWith("META-INF/rewrite/") || !entry.getName().endsWith(".yml")) {
					continue;
				}
				try (InputStream in = jar.getInputStream(entry)) {
					for (Object doc : new Yaml().loadAll(new String(in.readAllBytes(), StandardCharsets.UTF_8))) {
						if (doc instanceof Map<?, ?> map && map.get("name") != null) {
							recipes.put(String.valueOf(map.get("name")), (Map<String, Object>) map);
						}
					}
				}
			}
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		return recipes;
	}

	private static Path springJar() {
		try {
			return Path.of(AddRouteTrailingSlash.class.getProtectionDomain().getCodeSource().getLocation().toURI());
		}
		catch (Exception ex) {
			throw new IllegalStateException("rewrite-spring jar 를 찾지 못했다", ex);
		}
	}

}
