package com.eottabom.rewrite;

import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.openrewrite.Recipe;
import org.openrewrite.config.DeclarativeRecipe;
import org.openrewrite.config.Environment;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * yml 로 정의한 레시피가 전부 로딩되고, 참조하는 upstream 레시피 이름/옵션이 유효한지 검증한다. (rewrite-recipe-bom 버전을 올렸을
 * 때 upstream 레시피 이름이 바뀌면 여기서 먼저 깨진다)
 */
class RecipeValidationTests {

	private static final Environment ENV = Environment.builder().scanRuntimeClasspath().build();

	@Test
	void allCustomRecipesAreValid() {
		List<Recipe> customRecipes = ENV.listRecipes()
			.stream()
			.filter((r) -> r.getName().startsWith("com.eottabom.rewrite."))
			// yml 레시피만 검증한다. 하위 레시피(옵션 포함)는 validateAll 로 함께 검증된다.
			// 옵션이 필수인 Java 레시피는 classpath 스캔 시 빈 옵션으로 잡히므로 제외
			.filter((r) -> r instanceof DeclarativeRecipe)
			.collect(Collectors.toList());

		assertThat(customRecipes).isNotEmpty();
		for (Recipe recipe : customRecipes) {
			assertThat(recipe.validateAll()).as(recipe.getName())
				.allSatisfy((v) -> assertThat(v.isValid()).as(recipe.getName() + " -> " + v).isTrue());
		}
	}

	@Test
	void stageRecipesExist() {
		for (String stage : List.of("3_0", "3_1", "3_2", "3_3", "3_4", "3_5", "4_0")) {
			Recipe recipe = ENV.activateRecipes("com.eottabom.rewrite.spring.MigrateToSpringBoot_" + stage);
			assertThat(recipe.getRecipeList()).as(stage).isNotEmpty();
		}
	}

}
