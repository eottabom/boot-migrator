package com.eottabom.migration.recipe;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.eottabom.migration.model.Stage;
import com.eottabom.migration.recipe.ProjectRecipes.Phase;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

/**
 * 단계마다 실행할 레시피를 대상 프로젝트의 .rewrite/rewrite.generated.yml 로 만든다 (러너 관리, 매 단계 덮어쓴다).
 *
 * <pre>
 * migration.generated.Stage_03_boot_3_4
 *   ├─ 프로젝트 레시피 (migration-phase:before)
 *   ├─ 단계 레시피 (MigrateToSpringBoot_3_4 등)
 *   └─ 프로젝트 레시피 (migration-phase:after)
 * </pre> OpenRewrite Gradle 플러그인은 설정 파일을 하나만 읽으므로, 프로젝트의 레시피 문서도 이 파일에 함께 넣는다. 개발자가 관리하는
 * 원본 파일은 건드리지 않는다.
 */
public final class GeneratedRecipe {

	public static final String RELATIVE_PATH = ".rewrite/rewrite.generated.yml";

	/**
	 * @param name 활성화할 레시피 이름 (-Drewrite.activeRecipe)
	 */
	public record Generated(String name, Path file, List<String> before, List<String> after) {

		public boolean hasProjectRecipes() {
			return !this.before.isEmpty() || !this.after.isEmpty();
		}
	}

	private GeneratedRecipe() {
	}

	public static Generated write(Path projectDir, String projectName, Stage stage, String tag,
			ProjectRecipes projectRecipes) {
		String name = "migration.generated.Stage_" + tag.replaceAll("[^A-Za-z0-9]", "_");
		List<String> before = projectRecipes.names(stage.issueKey(), Phase.BEFORE);
		List<String> after = projectRecipes.names(stage.issueKey(), Phase.AFTER);

		List<Object> recipeList = new ArrayList<>(before);
		recipeList.add(stage.recipe());
		recipeList.addAll(after);
		Map<String, Object> recipe = new LinkedHashMap<>();
		recipe.put("type", ProjectRecipes.RECIPE_TYPE);
		recipe.put("name", name);
		recipe.put("displayName", "Generated migration for " + projectName + " (" + stage.name() + ")");
		recipe.put("description", "migrationRun 이 만든 파일. 직접 고치지 않는다 (매 단계 덮어쓴다).");
		recipe.put("recipeList", recipeList);

		List<Object> documents = new ArrayList<>();
		documents.add(recipe);
		documents.addAll(projectRecipes.documents());

		DumperOptions options = new DumperOptions();
		options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
		options.setAllowUnicode(true);
		options.setWidth(160);
		String header = "# 러너(migrationRun)가 단계마다 만든다. 직접 고치지 않는다.\n" + "# 프로젝트 레시피 원본: "
				+ (projectRecipes.files().isEmpty() ? "없음" : String.join(", ",
						projectRecipes.files().stream().map((f) -> projectDir.relativize(f).toString()).toList()))
				+ "\n";
		String yaml = header + new Yaml(options).dumpAll(documents.iterator());

		Path file = projectDir.resolve(RELATIVE_PATH);
		try {
			Files.createDirectories(file.getParent());
			Files.writeString(file, yaml);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		return new Generated(name, file, before, after);
	}

}
