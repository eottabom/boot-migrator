// 루트에는 소스가 없다. 마이그레이션 태스크만 붙인다.
//   recipes/    OpenRewrite 레시피 jar (대상 프로젝트의 rewrite classpath)
//   runner/     러너 Gradle 플러그인 (단계 결정, 실행, 게이트, 리포트)
//   playbook/   호환성 표, 알려진 이슈
//   init/       대상 프로젝트에 붙이는 Gradle init script (대상 프로젝트의 Gradle 안에서 돌아서 Groovy 로 둔다)
plugins {
    id("com.eottabom.migration")
}
