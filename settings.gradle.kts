pluginManagement {
    // migrationAnalyze / migrationPlan / migrationRun / migrationVerify / migrationHelp 태스크.
    // 루트 빌드가 쓰는 Gradle 플러그인이라 하위 모듈이 아니라 included build 로 둔다
    includeBuild("runner")
}

rootProject.name = "boot-migrator"

// OpenRewrite 레시피 jar (대상 프로젝트에 init script 로 붙는다)
include("recipes")
