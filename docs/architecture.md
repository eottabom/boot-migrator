# 구조와 확장

사용법은 [사용 가이드](usage.md) 를 본다.

## 단계 레시피 구성

단계마다 레시피가 두 개 있다.

```
SpringBootStep_3_4                    러너가 단계별로 실행하는 레시피 = 이 단계의 변경만
 ├─ upstream.UpgradeSpringBootStep_3_4   upstream(rewrite-spring) UpgradeSpringBoot_3_4 에서 직전 단계 체인만 뺀 것 = 본체
 │                                       Boot/Cloud 버전, Framework/Security/Data, 프로퍼티 이름 변경 등 대부분의 작업
 ├─ ReplaceMockBeanAndSpyBean            이 단계에서만 필요한 보정 (커스텀)
 ├─ UpgradeSpringCloudAws_3_3            upstream 에 없는 라이브러리 정렬 (커스텀)
 ├─ AddPropertiesMigrator                이 단계의 설정 키 변경 확인용
 ├─ catalog.VersionCatalog_3_4           위 레시피들의 버전 변경을 gradle/libs.versions.toml 에도 적용 (생성)
 └─ CommonMigrationFixes                 매 단계 마지막에 도는 공통 보정 (커스텀)

MigrateToSpringBoot_3_4               직전 MigrateToSpringBoot_3_3 + SpringBootStep_3_4
                                      어느 버전에서 시작해도 하나로 목표까지 (--one-shot, 레시피 단독 실행)
```

upstream 이 큰 틀을 맡고, 커스텀 레시피는 **upstream 만으로는 컴파일/테스트가 깨지거나 사람이 고쳐야 하는 틈**을 메운다.
(OpenRewrite 는 레시피 실행 전에 프로젝트를 컴파일하므로, 한 단계가 깨지면 다음 단계로 못 간다)

**왜 단계 레시피를 따로 두나.** upstream `UpgradeSpringBoot_X_Y` 는 첫 항목으로 직전 단계를 부르고 그 체인이 Boot 2.0 까지 이어진다.
그대로 쓰면 4.0 프로젝트에 4.1 을 돌려도 Boot 2.x 의 best practice 레시피(프로파일별 yml 분리, JUnit4 -> 5 빌드 블록),
이미 지난 4.0 의 Testcontainers 2 전환 등이 다시 돈다. 실제 프로젝트에서 4.1 단계가 23개 파일을 바꾸고 컴파일이 깨졌고,
단계 레시피로 바꾼 뒤 2개 파일만 바뀌고 통과했다. 3.0 단계는 2.x 에서 올라오는 입구라 upstream 전체 체인을 그대로 쓴다.

upstream 단계 레시피(`upstream-spring-boot-steps.yml`)는 rewrite-spring jar 에서 생성한 파일이다.
rewrite-recipe-bom 을 올리면 `UpstreamStepsUpToDateTests` 가 깨지고, `./gradlew :recipes:syncUpstreamSteps` 로 다시 만든다.

**version catalog.** upstream 의 `UpgradeDependencyVersion`, `UpgradePluginVersion`, `ChangeDependency` 는 빌드 스크립트의 선언만 바꾸고
`gradle/*.versions.toml` 은 건드리지 않는다. catalog 로 Boot 플러그인 버전을 관리하는 프로젝트는 레시피가 돌아도 Boot 버전이 그대로다.
`VersionCatalogStepsGenerator` 가 단계 레시피 트리 전체에서 이 세 종류의 레시피를 모아 `version-catalog-steps.yml` 의 규칙으로 옮기고,
`UpgradeVersionCatalog` 가 같은 규칙을 catalog 에 적용한다. 버전은 upstream 과 같은 방식(`DependencyVersionSelector`)으로 대상 프로젝트의 저장소에서 고른다.
이 파일도 `syncUpstreamSteps` 가 함께 만들고 `UpstreamStepsUpToDateTests` 가 검사한다. 단계 레시피를 고친 뒤에도 다시 만든다.

## 파일 구성

```
settings.gradle.kts / build.gradle.kts  루트는 소스 없이 모듈을 묶고 migration* 태스크만 붙인다 (빌드 스크립트는 Kotlin DSL)
recipes/                              OpenRewrite 레시피 jar (대상 프로젝트의 rewrite classpath 에 올라간다)
  build.gradle.kts                    레시피 jar 빌드와 recipeLibs 태스크(recipes/build/recipe-libs). JDK 25 로 빌드하되 바이트코드는 17
                                      (레시피 jar 는 대상 프로젝트의 Gradle JVM 안에서 로딩되는데, JDK 17 로 Gradle 을 띄우는 프로젝트가 있다)
  src/main/resources/META-INF/rewrite/  레시피 선언 (yml)
    spring-boot.yml                   단계 레시피: 단계별로 무엇을 어떤 순서로 돌리는지 (SpringBootStep / MigrateToSpringBoot)
    upstream-spring-boot-steps.yml    upstream 단계 레시피에서 직전 단계 체인을 뺀 것 (생성 파일, ./gradlew :recipes:syncUpstreamSteps)
    version-catalog-steps.yml         단계별 버전 변경을 version catalog 규칙으로 옮긴 것 (생성 파일, 같은 태스크)
    common.yml                        단계 레시피가 가져다 쓰는 부품
    find-manual.yml                   코드를 바꾸지 않고 "사람이 봐야 할 곳" 만 표시하는 검색 레시피
  src/main/java/                      yml(upstream 조합)로는 불가능한 보정만 Java 로 구현 (4절)
  src/test/java/                      레시피 이름/옵션 검증 + Java 레시피 단위 테스트
runner/                               러너 Gradle 플러그인 (루트 빌드가 쓰는 플러그인이라 included build). migration* 태스크와 위 흐름 전체
  inspect/ProjectInspector            대상 프로젝트의 Boot / Gradle / Java 버전(version catalog 포함), git 상태
  plan/MigrationPlanner               현재 버전과 목표로 단계 목록 결정 (BOOT_STAGES + compatibility.yml)
  playbook/                           compatibility.yml / known-issues.yml 로딩, 알려진 이슈 매칭
  recipe/                             대상 프로젝트의 .rewrite/ 레시피 탐색, 단계별 rewrite.generated.yml 생성
  exec/MigrationRunner                단계 루프, 게이트, 재개, 커밋
  exec/TargetGradle                   대상 프로젝트의 gradlew 를 별도 프로세스로 실행 (로그는 .rewrite-migration/)
  exec/StageReport                    단계 리포트 (NN-*.md, NN-*.report.json)
  exec/HtmlReport                     전 단계를 한 페이지로 보는 report.html
  task/                               migrationAnalyze / Plan / Run / Verify / Help
playbook/compatibility.yml            Boot 단계별 Java / Gradle 범위, Spring Framework, Spring Cloud 트레인 (공식 문서 기준, 출처는 파일 상단)
playbook/known-issues.yml             알려진 이슈 레지스트리 (단계 / 라이브러리 버전 조건, AUTO_FIX / REVIEW_REQUIRED / REPORT_ONLY)
                                      와 테스트 실패 힌트
init/rewrite.init.gradle              대상 프로젝트에 OpenRewrite 플러그인과 레시피 jar 를 붙이는 Gradle init script
init/verify.init.gradle               컴파일 경고 옵션, 테스트 fail-fast 해제와 결과 XML 강제, resolve 된 의존성 버전 수집
                                      init script 는 대상 프로젝트의 Gradle 안에서 돌아서 Groovy 로 둔다
                                      (Gradle 8.x 의 Kotlin 스크립트 컴파일러는 JDK 25 에서 깨진다)
```

### init script

대상 프로젝트의 빌드 파일을 건드리지 않기 위해 Gradle init script(`--init-script`)로 필요한 설정을 실행 시점에만 붙인다.

**`init/rewrite.init.gradle`** (rewriteRun / rewriteDryRun 에 사용)

| 설정 | 내용 |
|---|---|
| `initscript { classpath("org.openrewrite:plugin:7.39.0") }` | OpenRewrite Gradle 플러그인을 가져온다. `-PrewritePluginVersion` 으로 변경 |
| `rootProject { plugins.apply(RewritePlugin) }` | 루트 프로젝트에 플러그인 적용. 서브모듈은 플러그인이 알아서 함께 파싱한다 |
| `rewrite(files(fileTree(rewriteRecipeLibs)))` | `recipes/build/recipe-libs/` 의 jar 전부(레시피 jar + upstream 레시피 모듈)를 rewrite classpath 로 쓴다. maven 저장소를 거치지 않는다 |
| `exclusion(...)` | build, generated(QueryDSL Q-class), node_modules 는 파싱하지 않는다 |
| `failOnDryRunResults = false` | dry-run 에서 변경 사항이 있어도 빌드를 실패로 처리하지 않는다 |

실행할 레시피는 `-Drewrite.activeRecipe=` 로 넘긴다 (쉼표로 여러 개).

이 방식은 `rewrite` configuration 이 루트 프로젝트의 의존성 그래프에 보인다. upstream 의 일부 precondition 이 이걸 실제 의존성으로 오인해서(`jackson-module-jaxb-annotations`) 생기는 부작용은 `RemoveSpuriousJaxbApi` 가 되돌린다.

**`init/verify.init.gradle`** (compile / test 단계에 사용, 대상 빌드 모델이 필요한 것만)

| 설정 | 내용 |
|---|---|
| `JavaCompile` 에 `-Xlint:deprecation -Xlint:removal -Xmaxwarns 10000` | deprecated / 제거 예정 API 사용처를 경고로 남긴다. 리포트의 "제거 예정 API" 섹션 출처 |
| `Test` 에 `ignoreFailures = true`, `failFast = false`, JUnit XML 강제 | 테스트가 실패해도 끝까지 실행하고 결과 XML 을 남긴다. 프로젝트가 fail-fast 를 켜 둬도 검증 때는 끈다 |
| `migrationResolvedVersions` 태스크 | 전 모듈의 runtime/test classpath 에서 resolve 된 `group:artifact=version` 목록. 단계 전후 비교와 라이브러리 이슈 판단에 쓴다 |

### 리포트 (runner 의 `StageReport`)

단계가 끝나면 러너가 남은 파일을 읽어 `NN-*.md` 와 HTML 리포트용 `NN-*.report.json` 을 만든다. 대상 프로젝트의 Gradle 을 다시 띄우지 않는다.

| 입력 | 리포트에 쓰는 곳 |
|---|---|
| `NN-*.compile.log` | `[removal]` / `[deprecation]` 경고 (같은 위치는 한 번만) |
| `build/test-results/**/TEST-*.xml` | 테스트 수와 실패 원인(가장 안쪽 예외, 위치, playbook 의 실패 힌트). system-out 에서 properties-migrator 의 설정 키 변경 |
| `NN-*.rewrite.log` | 파일별 레시피 트리에서 말단 레시피를 가장 가까운 커스텀 레시피에 귀속시킨 "자동 보정 내역" |
| `00-scan.find.patch` | FindManualMigrationItems 의 `~~>` 마커 위치를 "수동 검토 대상" 으로 |
| `NN-*.versions.txt` (단계 전후) | 의존성 버전 변경 (major / minor / patch) |
| `NN-*.issues.json` | 러너가 playbook 에서 이 단계와 의존성 변경에 맞게 고른 알려진 이슈 |

### yml 파일

| 파일 | 내용 | 코드 변경 |
|---|---|---|
| `spring-boot.yml` | `SpringBootStep_3_0` ~ `4_1` (이 단계의 변경만) 과 `MigrateToSpringBoot_3_0` ~ `4_1` (체이닝). 단계마다 upstream 레시피와 커스텀 부품을 어떤 순서로 돌릴지 정의 | 예 |
| `upstream-spring-boot-steps.yml` | upstream `UpgradeSpringBoot_3_1` ~ `4_0` 에서 직전 단계 체인을 뺀 것. 생성 파일 | 예 |
| `version-catalog-steps.yml` | `VersionCatalog_3_0` ~ `4_1`, `Java21`, `Java25`, `Gradle8_14`. 단계 레시피의 버전 변경을 catalog 규칙으로 옮긴 것. 생성 파일 | 예 |
| `common.yml` | Spring Cloud AWS / QueryDSL / logstash / Gradle 정렬, 버전 고정 정리, Hibernate, Boot 4 패키지 보정, `CommonMigrationFixes` | 예 |
| `find-manual.yml` | `FindManualMigrationItems`. 자동으로 바꾸면 위험한 곳(mariadb-java-client 2.x, redisson, Jackson 3 전환 대상, `@EntityGraph` 등)을 찾아 리포트에 위치만 표시 | 아니오 |

## 커스텀 레시피

비고의 "실측" 은 대상 프로젝트에서 upstream 만으로는 실제로 깨지는 것을 확인한 항목이다.

### Java 레시피

| 레시피 (`recipes/src/main/java/com/eottabom/rewrite/`) | 내용 | 비고 |
|---|---|---|
| `querydsl/QuerydslJakartaClassifier` | `querydsl-apt:${ver}:jpa` → `:jakarta`, `querydsl-jpa` → `::jakarta` | upstream `ChangeDependencyClassifier` 는 `${}` 문자열, 버전 생략 선언을 처리하지 못함. 실측, Q-class 미생성 |
| `querydsl/EnsureQuerydslAptJakartaApis` | APT 용 `jakarta.annotation-api` / `persistence-api` 보장 | upstream 이 javax 코드 기준으로 판단해 삭제하고, `AddDependency` 는 갱신 안 된 Gradle 모델을 봐서 복구 못 함 |
| `gradle/DeclareUsedDependency` | import 는 하는데 선언이 없는 의존성을 소스셋별 configuration 에 선언 | Boot 2.7 때 transitive 로 들어오던 것이 사라짐. upstream `AddDependency` 는 transitive 로 있으면 건너뜀. 실측, commons-lang3, commons-io |
| `gradle/RemoveDependencyVersion` | 지정 그룹의 `g:a:v` 에서 버전만 제거 (BOM 좌표는 보호) | upstream `RemoveRedundantDependencyVersions` 는 선언 자체를 지움. 실측, Kafka, AWS SDK 버전 혼합 |
| `feign/DisambiguateRetryableExceptionNull` | `new RetryableException(..., null, req)` → `(Long) null` (3.2~) | Feign 12.2+ 생성자 모호성. 실측, 컴파일 에러 |
| `spring/PreserveConditionalBeanReturnType` | (3.4) `@Bean` 메서드의 `@ConditionalOn(Missing)Bean(annotation = ..)` 에 `value = 반환타입.class` 추가 | Boot 3.4 부터 `annotation` 만 주면 반환 타입을 기본 검사 대상으로 쓰지 않는다. 컴파일은 되고 빈 등록 여부만 바뀐다 (3.4 릴리즈 노트) |
| `spring/FindBeanMethodsReturning` | 지정한 타입(하위 타입 포함)을 반환하는 `@Bean` 메서드 표시 (검색 전용) | upstream `FindTypes` 는 타입이 쓰인 모든 곳을 표시하고 하위 타입을 따라가지 않는다 |
| `testing/FindSpyStubbingThroughCachingProxy` | 캐시 어노테이션이 있는 빈을 `@MockitoSpyBean`/`@SpyBean` 으로 stubbing 하는 테스트 필드 표시 (검색 전용) | 프록시를 거친 stubbing 이 기본값을 캐시한다 ([spring-framework#37121](https://github.com/spring-projects/spring-framework/issues/37121)) |
| `spring/RemoveDependsOnDatabaseInitializationFromDataSourceConfig` | `HikariConfig`/`DataSource` 빈의 `@DependsOnDatabaseInitialization` 제거 | upstream Boot 2.5 레시피가 잘못 붙여서 순환 참조. 실측, contextLoads 실패 |
| `hibernate/FixHypersistenceJsonAttributes` | JSON 컬럼 값 객체(와 하위 객체)에 `Serializable`, equals 가 없으면 `@EqualsAndHashCode` | 없으면 저장 시 `NonSerializableObjectException`(실측), equals 가 없으면 트랜잭션마다 유령 UPDATE. Hypersistence `@Type(Json*Type)` 과 `@JdbcTypeCode(SqlTypes.JSON)` 모두 대상 |
| `testing/AddLenientMockitoExtension` | `@Mock` 필드가 있고 `@ExtendWith` 가 없는 테스트에 lenient MockitoExtension (4.0~) | Boot 4 에서 `@Mock` 자동 초기화가 제거됨. upstream 버전은 strict 라 기존 테스트가 깨짐 (실측) |
| `jackson/NarrowJsonMapperBeanReturnType` | (4.0) `JsonMapper` 를 돌려주는 `@Bean ObjectMapper` 메서드의 반환 타입을 `JsonMapper` 로 | Boot 4 는 `JsonMapper` 타입 빈이 있을 때만 자동 설정 매퍼가 물러난다. `@Bean ObjectMapper` 는 무시되고 커스텀 모듈이 적용되지 않는다 ([spring-boot#50870](https://github.com/spring-projects/spring-boot/issues/50870)). 컴파일은 된다 |
| `jackson/FixJacksonIOExceptionCatch` | try 본문이 IOException 을 던지면 `catch (JacksonException \| IOException e)` 로 보완, 안 던지면 multi-catch 에서 IOException 제거 (4.0~) | upstream Jackson 3 전환이 양방향으로 틀림. 실측, IOException catch 누락으로 컴파일 에러, 던지지 않는 IOException 을 catch 해서 컴파일 에러(never thrown) |
| `httpclient/RevertHttpClient5ForElasticsearchRestClient` | (3.0) Elasticsearch `RestClient` 를 쓰는 파일에서 upstream 의 HttpClient 5 전환을 되돌려 HttpClient 4 유지 | Boot 3.x 의 elasticsearch-java 8.x 는 HttpClient 4 기반. upstream `UpgradeApacheHttpClient_5` 는 REST Assured 모듈만 건너뛰고 ES RestClient 는 제외 목록에 없어 바꿔버림. 실측, 컴파일 에러. 4.0 에서 `MigrateToRest5Client` 가 HttpClient 5 기반 `Rest5Client` 로 옮긴다. 되돌릴 때 생성자 타입 정보도 되돌려야 4.0 에서 upstream 이 `HttpHost` 인자 순서를 다시 바꾼다 |
| `httpclient/FixHttpClient5AsyncInterceptors` | (3.0 opensearch, 4.0 Rest5Client) `addInterceptorLast/First` → `addResponseInterceptorLast/First` (요청 인터셉터는 `addRequestInterceptor*`), 인터셉터 람다 `(response, context)` → `(response, entity, context)` | upstream `UpgradeApacheHttpClient_5` 가 타입만 HttpClient 5 로 바꾸고 async 빌더 메서드 이름과 람다 인자 수는 그대로 둔다. opensearch-rest-client 2.x (HttpClient 4) 에서 3.x 로 올리는 코드에 해당 |
| `elasticsearch/MigrateRangeQueryToUntyped` | (3.4) `RangeQuery.Builder` → `UntypedRangeQuery.Builder`, `build()` → `build()._toRangeQuery()` | Boot 3.4 BOM 의 elasticsearch-java 8.15 에서 `RangeQuery` 가 untyped/date/number/term 중 하나를 고르는 구조로 바뀌어 `field`/`gte`/`lte` 가 `UntypedRangeQuery` 로 옮겨짐. 실측, 컴파일 에러. `q.range(r -> r.field(..))` 람다 형태는 바꾸지 않는다 |
| `elasticsearch/Rest5ClientCallbacksToConsumer` | (4.0) `setHttpClientConfigCallback` / `setRequestConfigCallback` 람다 끝의 `return builder;` 제거 (`return builder.setX(..)` 는 호출만 남김) | `Rest5ClientBuilder` 의 콜백은 `Consumer` 라 값을 돌려주면 컴파일 에러 |
| `lombok/CopyJacksonAnnotationsToAccessors` | 루트 `lombok.config` 에 `lombok.copyJacksonAnnotationsToAccessors = true` (없으면 만들고, 있으면 한 줄 추가, 키가 이미 있으면 그대로). 루트 `.gitignore` 가 `lombok.config` 를 무시하면 `!/lombok.config` 를 추가해 커밋되게 한다. Lombok 과 Jackson 어노테이션을 함께 쓰는 프로젝트만 | Lombok 1.18.40 부터 필드의 `@JsonProperty` 를 getter 에 복사하지 않는다([lombok#3978](https://github.com/projectlombok/lombok/issues/3978)). `@JsonProperty("isShow") boolean isShow` 가 JSON 에 `isShow` 와 `show` 로 두 번 나간다. 실측, REST Docs 테스트 실패(3.4, freefair 플러그인 업그레이드로 새 Lombok 적용). 예전 freefair 가 만들던 파일 때문에 `lombok.config` 를 무시하던 프로젝트가 있었다 |
| `gradle/UpgradeVersionCatalog` | upstream 이 빌드 스크립트에 하는 버전 업그레이드와 좌표 변경을 `gradle/*.versions.toml` 에 적용. 버전 키를 다른 항목과 같이 쓰면 새 키를 만들어 나머지는 그대로 둔다 | upstream 은 catalog 를 바꾸지 않는다. 실측, catalog 를 쓰는 프로젝트의 Boot 버전이 올라가지 않음 |
| `gradle/UpgradeJacocoToolVersion` | `jacoco { toolVersion = "x" }` 를 지정 버전 이상으로 (Java 단계) | upstream `UpgradeJaCoCo` 는 의존성만 올림. 구버전 JaCoCo 는 새 Java 클래스 파일을 못 읽음 |

### yml 레시피 (`common.yml`)

| 레시피 | 내용 | 비고 |
|---|---|---|
| `aws.UpgradeSpringCloudAws_*`, `aws.MigrateSpringCloudAws_2_to_3` | Spring Cloud AWS 버전과 artifact 이름 | upstream 에 없음 |
| `aws.UnpinAwsSdkVersions` | AWS SDK 고정 버전 제거 | 실측, `IncompatibleClassChangeError` |
| Kafka / Hibernate / Spring Boot 모듈 고정 버전 제거 (`CommonMigrationFixes` 안) | BOM 관리 버전을 따르게 함. buildscript `classpath`(플러그인)는 건드리지 않는다 | 실측, Kafka `NoSuchMethodError`. Hibernate 는 패치 릴리즈 버그 수정(HHH-17294, HHH-18378 등) 반영. Spring Boot 는 upstream 이 test starter 를 추가하며 `:4.0.8` 을 박는 문제 |
| `spring.UnpinSpringRestDocs`, `spring.RemoveSpuriousJaxbApi` | upstream 부작용 되돌리기 | 실측 |
| `java.UnshadeRelocatedImports` | `org.testcontainers.shaded.*`, logstash 내부 `commons-lang3` 등 relocate 된 패키지 → 원래 라이브러리 | 실측, Testcontainers 2, logstash 8 |
| `logging.FixLogstashDecoratorForJackson3` | decorator 의 `ObjectMapper.enable()` 과 캐스트 제거 | 실측, Jackson 3 컴파일 에러. 캐스트를 남기면 런타임 ClassCastException |
| `hibernate.MigrateWhereToSQLRestriction` | `@Where` → `@SQLRestriction` (3.2~) | 실측, Hibernate 7 에서 제거 |
| `spring.Boot4JpaRelocations`, `spring.Boot4RestDocsModule` | Boot 4 모듈 분리로 옮겨진 클래스와 의존성 | 실측, upstream 이 잘못 옮기거나 누락 (jar 에서 실제 위치 확인) |
| `spring.Boot4TestStartersForMainSources` | (4.0) `@DataJpaTest`, `@AutoConfigureTestDatabase`, `@WebMvcTest`, `TestRestTemplate` 등을 쓰는 소스셋에 upstream 과 같은 test starter 선언 (버전 없음) | upstream `MigrateToModularStarters` 는 `scope: test` 고정이라 테스트 공용 코드를 `src/main` 에 둔 모듈이 빠진다. 실측, 테스트 전용 모듈의 4.0 컴파일 에러 |
| `search.UpgradeOpenSearchJava_3` | (3.0) `opensearch-java`, `opensearch-rest-client` 를 3.x 로 | Boot BOM 이 관리하지 않아서 직접 올린다. rest-client 3.x 는 HttpClient 5 기반이라 upstream 의 HttpClient 5 전환과 같은 단계에 둔다 |
| `elasticsearch.MigrateToRest5Client` | (4.0) ES `RestClient` → `Rest5Client`, `RestClientTransport` → `Rest5ClientTransport` (HttpClient 5) | Boot 4 BOM 이 elasticsearch-java 9.x 를 관리하고, 9.x 는 저수준 RestClient(HttpClient 4)를 의존성으로 가져오지 않는다. HttpClient 4 코드는 upstream `UpgradeApacheHttpClient_5` 로 바꾸고 남는 차이는 Java 레시피가 맞춘다. 실측, 4.0 컴파일 에러 |
| `elasticsearch.ElasticsearchJava9ApiChanges` | (4.0) `JacksonJsonpMapper` → `Jackson3JsonpMapper`, 응답 36종의 `valueBody()` → 응답별 이름(`AliasesResponse.aliases()` 등) | upstream Jackson 3 전환이 매퍼를 Jackson 3 로 바꾼다. valueBody 대응은 8.18 / 9.2 jar 를 비교해 반환 타입이 같은 메서드로 정했다. 실측, 4.0 컴파일 에러 |
| `kafka.UseJsonMapperForJacksonJsonSerializer` | (4.0) `JacksonJsonSerializer`/`Deserializer` 를 쓰는 파일의 `ObjectMapper` → `JsonMapper` | Spring Kafka 4 는 Jackson 3 `JsonMapper` 만 받는다. Boot 4 가 `JsonMapper` 빈을 등록하므로 주입도 그대로 된다. 실측, 4.0 컴파일 에러 |
| `hibernate.ReplaceAnnotationsQueryHints` | (4.0) `org.hibernate.annotations.QueryHints.*` → 같은 값의 `org.hibernate.jpa.HibernateHints.HINT_*` | Hibernate 7 에서 제거. upstream `MigrateToHibernate70` 에 대체 규칙이 없다. 실측, 4.0 컴파일 에러 |
| spring-retry 선언 (4.0 단계의 `DeclareUsedDependency`) | (4.0) `org.springframework.retry` 를 쓰는 모듈에 `spring-retry` 2.0.x | Boot 4 BOM 에서 빠져 transitive 로 받던 프로젝트에서 사라진다. 실측, 4.0 컴파일 에러 |
| `hibernate.KeepLegacyIdGeneratorNaming` | 2.x 에서 올 때 Hibernate 시퀀스 이름 legacy 유지 | 예방 |
| `logging.UpgradeLogstashEncoder_7/8/9`, `gradle.UpgradeGradleToolchain_8_14` | 라이브러리, 빌드 도구 정렬 | 예방 |
| `spring.Boot3Extras` | REST Docs 3 API, feign 설정 키, resilience4j | 기존 spring-boot-migrate 에서 가져옴 |

### upstream 을 그대로 쓰는 것

| 작업 | upstream 레시피 | 비고 |
|---|---|---|
| Boot 단계 본체 | `UpgradeSpringBoot_3_0` ~ `4_0` | 4.1 은 upstream 에 없어서 프로퍼티 레시피와 버전 업그레이드로 구성 |
| Java 버전업 | `UpgradeToJava21` / `UpgradeToJava25` | 빌드 설정, 플러그인, 문법 (Java 25 는 Gradle 9.1 까지). 최소 버전 17 은 Boot 3.0 레시피가 항상 맞춘다. 커스텀 래퍼 `java.UpgradeToJava21/25` 가 JaCoCo `toolVersion` 과 Gradle 9 용 플러그인(freefair 9.x, sonarqube 7.x, gradle-git-properties 4.x, asciidoctor 4.x)을 함께 맞춘다 |
| Jackson 3 코드 전환 | `UpgradeJackson_2_3` | upstream 4.0 의 `UpgradeSpringFramework_7_0` 에 포함되어 4.0 단계에서 함께 된다 |

## Java 레시피 코드 구조

OpenRewrite 레시피는 소스를 LST(Lossless Semantic Tree, 타입 정보가 붙은 구문 트리)로 읽고, visitor 로 트리를 바꾼 뒤 원래 포맷을 유지한 채 다시 쓴다.
이 프로젝트의 Java 레시피는 두 가지 형태다.

| 형태 | 흐름 | 해당 레시피 |
|---|---|---|
| `Recipe` | `getVisitor()` 가 파일마다 트리를 돌며 바로 수정 | QuerydslJakartaClassifier, EnsureQuerydslAptJakartaApis, RemoveDependencyVersion, UpgradeJacocoToolVersion, DisambiguateRetryableExceptionNull, RemoveDependsOnDatabaseInitializationFromDataSourceConfig, AddLenientMockitoExtension, FixJacksonIOExceptionCatch, RevertHttpClient5ForElasticsearchRestClient, FixHttpClient5AsyncInterceptors, MigrateRangeQueryToUntyped, Rest5ClientCallbacksToConsumer, PreserveConditionalBeanReturnType, NarrowJsonMapperBeanReturnType, FindBeanMethodsReturning |
| `ScanningRecipe` | 1) `getScanner()` 로 전체 파일을 먼저 훑어 정보 수집 2) `getVisitor()` 에서 그 정보로 수정 | DeclareUsedDependency (Java import 를 모은 뒤 build.gradle 수정), FixHypersistenceJsonAttributes (JSON 속성 타입을 모은 뒤 해당 클래스 수정), CopyJacksonAnnotationsToAccessors (Lombok + Jackson 사용 여부와 lombok.config 존재 여부를 본 뒤 파일 생성 또는 추가), UpgradeVersionCatalog (루트 프로젝트의 저장소 정보를 모은 뒤 catalog 수정), FindSpyStubbingThroughCachingProxy (캐시 어노테이션이 있는 타입을 모은 뒤 테스트의 spy 필드 표시) |

- build.gradle 은 Groovy LST, build.gradle.kts 는 Kotlin LST 로 읽힌다. 둘 다 `J.MethodInvocation` / `J.Literal` 로 보이므로 `JavaIsoVisitor` 로 함께 처리한다 (`GroovyIsoVisitor` 는 kts 를 조용히 건너뛴다). `IsBuildGradle` 로 대상을 제한하고, Gradle 모델(선언된 의존성, configuration)은 `GradleProject` 마커에서 읽는다
- Java 소스는 `JavaIsoVisitor` 로 돈다. 어노테이션 추가는 `JavaTemplate`, 인터페이스 추가는 `ImplementInterface` 를 쓴다
- yml 에서 옵션을 주는 레시피(DeclareUsedDependency, RemoveDependencyVersion)는 생성자 파라미터 이름으로 매핑된다 (`-parameters` 컴파일 옵션)
- 스캔은 편집 전 원본 기준으로 한 번 돈다. 같은 실행 안에서 upstream 이 패키지를 바꾸는 경우(commons-lang → lang3) 바뀌기 전 패키지도 같이 적는 이유다
- 각 클래스의 Javadoc 에 대상 증상과 조건을 적어 두었다. 테스트(`recipes/src/test/java`)가 입력/출력 예시 역할을 한다

## 확장

| 하고 싶은 것 | 방법 |
|---|---|
| 누락 의존성 선언 | `common.yml` 의 `DeclareUsedTransitiveDependencies` 에 `DeclareUsedDependency` 한 줄 추가 |
| 3rd-party 버전 정렬 | `common.yml` 에 레시피를 만들고 `spring-boot.yml` 의 해당 `SpringBootStep_X_Y` 에 추가 |
| 새 알려진 이슈 | `playbook/known-issues.yml` 에 항목 추가. 장애/버그 → 원인 → 재현 조건(단계 또는 라이브러리 버전) → 등록 → 가능하면 탐지/수정 레시피를 만들어 `fix` 에 연결 |
| 호환성 변경 (새 Boot 라인, Java/Gradle 범위) | `playbook/compatibility.yml` |
| rewrite-recipe-bom 버전 올리기 | 올린 뒤 `./gradlew :recipes:syncUpstreamSteps` 로 upstream 단계 레시피와 catalog 규칙을 다시 만들고 `./gradlew test` |
| 새 Boot 단계 | `UpstreamStepsGenerator` 의 단계 목록, `spring-boot.yml` (`SpringBootStep` + `MigrateToSpringBoot`), `MigrationPlanner.BOOT_STAGES`, `compatibility.yml`, `known-issues.yml` 의 `guides` 에 추가 |

`./gradlew test` 는 playbook 파일 형식과 `fix` / `recipe` 가 가리키는 레시피가 실제로 있는지까지 검증한다.
한 프로젝트에서만 나온 문제도 공용 레시피로 만든다. 해당 타입이나 의존성이 있을 때만 바뀌도록 조건을 걸어(`UsesType`, 원래 타입의 메서드 확인 등) 다른 프로젝트에는 영향이 없게 한다.
- 추가한 뒤 `./gradlew test` (레시피 이름/옵션 검증) → `migrationRun --preview` 로 대상 프로젝트 확인

| 항목 | 위치 | 현재 |
|---|---|---|
| rewrite-recipe-bom | `recipes/build.gradle.kts` | 3.37.0 |
| OpenRewrite Gradle plugin | `init/rewrite.init.gradle` | 7.39.0 |

두 버전은 같은 `rewrite-bom` 을 참조하는 조합으로 맞춘다.
