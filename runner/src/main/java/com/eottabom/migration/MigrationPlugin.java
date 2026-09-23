package com.eottabom.migration;

import com.eottabom.migration.task.MigrationAnalyzeTask;
import com.eottabom.migration.task.MigrationPlanTask;
import com.eottabom.migration.task.MigrationRunTask;
import com.eottabom.migration.task.MigrationTask;
import com.eottabom.migration.task.MigrationVerifyTask;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.file.Directory;

/**
 * 저장소 루트에 적용해서 migrationAnalyze / migrationPlan / migrationRun / migrationVerify 를 등록한다.
 * 레시피 jar 는 recipes 모듈의 recipeLibs 태스크가 만들고, 대상 프로젝트에는 init script 로 붙인다.
 */
public class MigrationPlugin implements Plugin<Project> {

    @Override
    public void apply(Project project) {
        Directory root = project.getLayout().getProjectDirectory();
        // 레시피 jar 와 의존 jar 는 recipes 모듈의 recipeLibs 태스크가 만든다 (루트보다 나중에 설정되므로 경로로 건다)
        String recipeLibs = ":recipes:recipeLibs";

        project.getTasks().withType(MigrationTask.class).configureEach(task -> {
            // 상대 경로 --project-path 는 이 저장소가 아니라 명령을 실행한 위치 기준 (./gradlew -p 로 실행해도 같다)
            task.getInvocationDir().convention(project.getLayout().dir(project.provider(() -> project.getGradle().getStartParameter().getCurrentDir())));
            task.getRewriteInitScript().convention(root.file("init/rewrite.init.gradle"));
            task.getVerifyInitScript().convention(root.file("init/verify.init.gradle"));
            task.getRecipeLibs().convention(root.dir("recipes/build/recipe-libs"));
            task.getKnowledgeDir().convention(root.dir("knowledge"));
        });

        project.getTasks().register("migrationHelp", com.eottabom.migration.task.MigrationHelpTask.class);
        project.getTasks().register("migrationAnalyze", MigrationAnalyzeTask.class, task -> task.dependsOn(recipeLibs));
        project.getTasks().register("migrationPlan", MigrationPlanTask.class);
        project.getTasks().register("migrationRun", MigrationRunTask.class, task -> task.dependsOn(recipeLibs));
        project.getTasks().register("migrationVerify", MigrationVerifyTask.class);
    }
}
