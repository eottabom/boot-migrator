package com.eottabom.rewrite;

import org.junit.jupiter.api.Test;
import org.openrewrite.Recipe;
import org.openrewrite.config.Environment;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * playbook/*.yml 이 가리키는 레시피(fix, recipe)가 실제로 있는지 검증한다.
 * 레시피 이름을 바꾸거나 upstream 레시피가 사라지면 러너의 단계/리포트가 조용히 어긋나므로 여기서 먼저 깨진다.
 */
class PlaybookRecipesTest {

    private static final Environment ENV = Environment.builder().scanRuntimeClasspath().build();
    private static final Pattern RECIPE_REF = Pattern.compile("(?m)^\\s*(?:fix|recipe):\\s*([\\w.]+)");

    @Test
    void playbookRecipesExist() throws IOException {
        Set<String> available = ENV.listRecipes().stream().map(Recipe::getName).collect(Collectors.toSet());
        Set<String> referenced = new TreeSet<>();
        for (String file : new String[]{"../playbook/compatibility.yml", "../playbook/known-issues.yml"}) {
            Matcher m = RECIPE_REF.matcher(Files.readString(Path.of(file)));
            while (m.find()) {
                referenced.add(m.group(1));
            }
        }

        assertThat(referenced).hasSizeGreaterThan(10);
        assertThat(referenced).allSatisfy(name -> assertThat(available).as(name).contains(name));
    }
}
