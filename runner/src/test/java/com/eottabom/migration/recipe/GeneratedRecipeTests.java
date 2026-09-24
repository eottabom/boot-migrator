package com.eottabom.migration.recipe;

import com.eottabom.migration.model.Stage;
import com.eottabom.migration.recipe.ProjectRecipes.Phase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SuppressWarnings("unchecked")
class GeneratedRecipeTest {

    @TempDir
    Path dir;

    @Test
    void tagsDecideStageAndPhaseAndUntaggedRecipesAreBuildingBlocks() throws IOException {
        write(".rewrite/custom/auth.yml", """
                ---
                type: specs.openrewrite.org/v1beta/recipe
                name: com.example.MigrateLegacyAuthClient
                tags: ["migration-stage:3.4", "migration-phase:before"]
                recipeList:
                  - com.example.RenameAuthPackage
                ---
                type: specs.openrewrite.org/v1beta/recipe
                name: com.example.RenameAuthPackage
                recipeList:
                  - org.openrewrite.java.ChangePackage:
                      oldPackageName: com.example.auth.v1
                      newPackageName: com.example.auth.v2
                ---
                """);
        write(".rewrite/rewrite.yml", """
                type: specs.openrewrite.org/v1beta/recipe
                name: com.example.EveryStageCleanup
                tags:
                  - migration-stage:*
                recipeList:
                  - org.openrewrite.java.RemoveUnusedImports
                """);

        ProjectRecipes recipes = ProjectRecipes.discover(dir);

        assertThat(recipes.files()).hasSize(2);
        assertThat(recipes.documents()).hasSize(3);
        assertThat(recipes.names("3.4", Phase.BEFORE)).containsExactly("com.example.MigrateLegacyAuthClient");
        assertThat(recipes.names("3.4", Phase.AFTER)).containsExactly("com.example.EveryStageCleanup");
        assertThat(recipes.names("3.5", Phase.BEFORE)).isEmpty();
        assertThat(recipes.names("java21", Phase.AFTER)).containsExactly("com.example.EveryStageCleanup");
    }

    @Test
    void generatedFileWrapsStageRecipeWithProjectRecipes() throws IOException {
        write(".rewrite/custom/auth.yml", """
                type: specs.openrewrite.org/v1beta/recipe
                name: com.example.MigrateLegacyAuthClient
                tags: ["migration-stage:3.4", "migration-phase:before"]
                recipeList:
                  - org.openrewrite.java.RemoveUnusedImports
                """);
        Stage stage = new Stage(Stage.Kind.BOOT, "3.4", "com.eottabom.rewrite.spring.MigrateToSpringBoot_3_4");

        GeneratedRecipe.Generated generated = GeneratedRecipe.write(dir, "product-api", stage, stage.tag(3), ProjectRecipes.discover(dir));

        assertThat(generated.name()).isEqualTo("migration.generated.Stage_03_boot_3_4");
        assertThat(generated.file()).isEqualTo(dir.resolve(".rewrite/rewrite.generated.yml"));
        List<Map<String, Object>> docs = new ArrayList<>();
        new Yaml().loadAll(Files.readString(generated.file())).forEach(d -> docs.add((Map<String, Object>) d));
        assertThat(docs).hasSize(2);
        assertThat(docs.get(0)).containsEntry("name", generated.name());
        assertThat((List<Object>) docs.get(0).get("recipeList"))
                .containsExactly("com.example.MigrateLegacyAuthClient", "com.eottabom.rewrite.spring.MigrateToSpringBoot_3_4");
        assertThat(docs.get(1)).containsEntry("name", "com.example.MigrateLegacyAuthClient");
    }

    @Test
    void generatesStageRecipeWithoutProjectRecipes() {
        Stage stage = new Stage(Stage.Kind.GRADLE, "gradle8.14", "com.eottabom.rewrite.gradle.UpgradeGradleToolchain_8_14");

        GeneratedRecipe.Generated generated = GeneratedRecipe.write(dir, "p", stage, stage.tag(1), ProjectRecipes.discover(dir));

        assertThat(generated.hasProjectRecipes()).isFalse();
        assertThat(generated.name()).isEqualTo("migration.generated.Stage_01_gradle8_14");
    }

    @Test
    void failsWithFileNameOnInvalidYaml() throws IOException {
        write(".rewrite/rewrite.yml", "type: [broken\n");

        assertThatThrownBy(() -> ProjectRecipes.discover(dir)).hasMessageContaining(".rewrite/rewrite.yml");
    }

    private void write(String path, String content) throws IOException {
        Path file = dir.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
