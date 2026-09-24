# 사용 가이드

빠른 시작은 [README](../README.md), 내부 구조와 레시피 추가는 [구조와 확장](architecture.md) 을 본다.

## 동작 방식

```
./gradlew migrationRun
 │
 ├─ 1) 레시피 빌드 ──── recipes/ 를 빌드해서 레시피 jar 와 의존 jar 를 recipes/build/recipe-libs/ 에 모은다 (recipeLibs 태스크 의존)
 │
 ├─ 2) 계획 ──────────── 현재 Boot / Gradle / Java 버전 감지 → compatibility.yml 로 단계 결정
 │                       (Gradle 이 목표 Boot 지원 범위보다 낮으면 그 Boot 단계 앞에 gradle8.14 단계, --java 를 주면 java 단계)
 │
 ├─ 3) 스캔 ──────────── resolve 된 의존성 버전 수집, FindManualMigrationItems 로 수동 검토 대상 위치 탐지
 │
 ├─ 4) 단계 루프 ─────── 현재 버전의 다음 단계부터 목표까지 반복
 │    │                  (예: 3.3 -> 3.4 -> 3.5 -> gradle8.14 -> 4.0 -> 4.1 -> java25)
 │    ├─ rewriteRun     init/rewrite.init.gradle 로 대상 프로젝트에 OpenRewrite 플러그인 + 레시피 jar 를 붙여 실행
 │    │                 (대상 프로젝트의 build.gradle 은 수정하지 않는다)
 │    ├─ compile        init/verify.init.gradle: javac -Xlint:removal 로 "다음 버전에서 제거될 API" 수집
 │    ├─ build          전체 테스트(fail-fast 끔) + 패키징, asciidoctor, checkstyle (--continue). 테스트 실패 목록과 properties-migrator 경고 수집
 │    ├─ 알려진 이슈     known-issues.yml 에서 이 단계 + 단계 전후 의존성 버전 변경에 맞는 항목을 고른다
 │    ├─ 리포트          .rewrite-migration/NN-boot-X.Y.md    단계별 결과
 │    ├─ 패치           .rewrite-migration/NN-boot-X.Y.patch 시작 시점 대비 누적 변경
 │    └─ commit         --commit 일 때만. 게이트를 통과한 단계만, 추적 중인 파일의 변경과 레시피가 만든 파일만 담는다
 │
 │    다음 중 하나면 그 단계에서 멈추고 커밋하지 않는다
 │      컴파일 실패, 테스트 실패 1개 이상, 원본 빌드(build -x test)에서는 실패하지 않던 태스크의 실패, 원인을 모르는 빌드 실패
 │    고치고 같은 명령을 다시 실행하면 그 단계의 게이트를 처음부터 다시 확인하고, 통과하면 커밋하고 다음 단계로 간다
 │    (컴파일 에러를 고치지 않고 다시 실행하면 단계별 누적 patch 로 그 단계 전 상태를 만들어 그 단계부터 다시 시도)
 │
 └─ 5) 요약
```

## 옵션

| 옵션 | 태스크 | 기본 | |
|---|---|---|---|
| `--project-path=<경로>` | 전부 | (필수) | 대상 프로젝트. 상대 경로는 명령을 실행한 위치 기준 |
| `--spring-boot=<값>` | Plan, Run | 4.1 | 3.0 ~ 3.5, 4.0, 4.1 |
| `--java=<값>` | Plan, Run | `auto` (목표 Boot 가 지원하면 유지) | `latest` (목표 Boot 가 지원하는 최신 LTS), `17` `21` `25`, `none`. 이미 그 이상이면 건너뜀. 목표 Boot 지원 범위 밖이면 거부 |
| `--one-shot` | Plan, Run | | 단계별 게이트 없이 목표 레시피를 한 번에 |
| `--upstream-only` | Plan, Run | | 커스텀 레시피 없이 upstream 만 (비교용) |
| `--commit` | Run | 커밋 안 함 | 게이트를 통과한 단계마다 commit (작업 트리가 깨끗해야 함) |
| `--preview` | Run | | 다음 단계 patch 만 만들고 끝 (Gradle 자체의 `--dry-run` 과 겹치지 않게 이름이 다르다) |
| `--allow-dirty` | Run | | 커밋되지 않은 변경이 있어도 시작 (기본은 중단. 재개와 preview 는 검사하지 않음) |
| `--gate=<값>` | Run, Verify | build | `compile` / `build` (전체 테스트 + 패키징, asciidoctor, checkstyle 등) / `none` |
| `--keep-java-home` | 전부 | | JDK 자동 선택 끄기 |
| `--gradle-jvmargs="<옵션>"` | 전부 | `-Xmx` 는 장비 메모리의 절반, 최대 6g | 대상 Gradle 데몬 JVM 옵션 |
| `--skip-project-recipes` | Plan, Run | | 대상 프로젝트의 `.rewrite/` 레시피를 붙이지 않음 |

## 프로젝트 전용 레시피

대상 프로젝트에만 필요한 보정은 그 프로젝트의 `.rewrite/*.yml` 에 선언형 레시피로 둔다. tags 로 붙일 단계와 순서를 정한다.
단계 값은 `3.0` ~ `4.1`, `java21`, `java25`, `gradle`, `*`(모든 단계) 중 하나다.

```yaml
type: specs.openrewrite.org/v1beta/recipe
name: com.example.FixLegacyClient
tags:
  - migration-stage:4.0
  - migration-phase:before     # 단계 레시피보다 먼저. 없으면 after
recipeList:
  - org.openrewrite.java.ChangeType:
      oldFullyQualifiedTypeName: com.example.legacy.Client
      newFullyQualifiedTypeName: com.example.client.Client
```

러너가 단계마다 `.rewrite/rewrite.generated.yml` 을 만들어 적용한다. `--skip-project-recipes` 로 끈다.

## 리포트 (`.rewrite-migration/NN-boot-X.Y.md`)

| 섹션 | 출처 | 내용 |
|---|---|---|
| 컴파일 / 테스트 | Gradle | 이 단계에서 깨진 것 |
| 자동 보정 내역 | rewriteRun 로그 | 어떤 커스텀 레시피가 어떤 파일을 바꿨는지 (전체 변경은 같은 이름의 .patch) |
| 설정 키 변경 | spring-boot-properties-migrator | 이름이 바뀌었거나 없어진 설정 키 |
| 제거 예정 API (`[removal]`) | javac | 다음 단계에서 깨질 곳 |
| 알려진 이슈 | `playbook/known-issues.yml` | 컴파일/테스트가 통과해도 확인할 항목. 사람이 판단 / 레시피가 바꿨지만 확인 / 레시피가 보정 으로 나눠 보여준다 |
| 수동 검토 대상 | `FindManualMigrationItems` | 자동으로 바꾸지 않은 곳의 위치 |

**spring-boot-properties-migrator** 는 모든 단계 레시피가 `runtimeOnly` 로 추가한다. Spring 컨텍스트가 뜰 때 이름이 바뀌었거나
없어진 설정 키를 WARN 으로 알려준다. OpenRewrite 는 저장소 안 yml 만 고칠 수 있으므로 외부 설정 저장소(Spring Cloud Config, Vault, Secrets Manager 등) 의 설정은 이걸로 확인한다.
개발/스테이징 배포 후 기동 로그에서 `The use of configuration keys that` 를 검색하고, 정리되면 의존성을 제거한다.

## 자동으로 하지 않는 것

리포트의 "확인할 것" 에 위치와 함께 나온다.

| 대상 | 이유와 확인할 것 |
|---|---|
| mariadb-java-client 2.x | 자동으로 올리지 않는다. 3.x 는 `jdbc:mariadb:aurora://` 스킴 제거와 failover 설정 변경이 있어 JDBC URL 검토가 필요하다 |
| Elasticsearch / OpenSearch 클라이언트 | elasticsearch-java 는 Boot BOM 버전을 따라 올라가고(3.4 에서 8.15, 4.0 에서 9.x), 3.x 동안은 저수준 RestClient(HttpClient 4)를 유지하고 4.0 에서 `Rest5Client`(HttpClient 5)로 옮긴다. opensearch-java 는 레시피가 3.x 로 올린다. 서버 버전 호환과 Jackson 3 날짜 형식 변화는 개발 환경에서 확인한다 |
| redisson-spring-data-XX, Sentry, playtika embedded-* | Boot 버전에 1:1 로 묶인 artifact 라서 이름 자체가 바뀐다 |
| spring-retry 에서 Spring Framework 7 core retry 로 | 4.0 에서 레시피는 `spring-retry` 2.0.x 를 선언해 기존 코드를 유지한다. core retry 는 API 가 달라(재시도 횟수 계산, checked `RetryException`, 리스너에 재시도 횟수 없음) 직접 옮긴다. 사용처는 수동 검토 대상에 표시된다 |
| Hibernate 6.6 검증 강화 | 저장 안 된 엔티티를 참조하면 `TransientObjectException`. cascade 와 저장 순서는 도메인 판단이다 |
| JSON 컬럼 안의 `LocalDate` | Hypersistence 자체 ObjectMapper 로 저장 형식이 바뀔 수 있어 기존 데이터 형식을 확인한다 |
| 배포 이미지와 CI 의 JDK | Java 단계 후 Dockerfile 베이스 이미지 등 저장소 밖 설정은 직접 바꾼다 |
| Boot 4.1 과 Spring Cloud | 2025.1.2 이상이 필요하다 (레시피가 2025.1.x 최신으로 올린다) |

## 외부 사례 기반 검토

`runtime-migration-risks.yml` 의 검색 레시피와 `FindSpyStubbingThroughCachingProxy` 를 `FindManualMigrationItems` 에 연결했다.
분석/preview 단계에서 후보 위치를 표시하고, 단계별 영향과 공식 출처는
`playbook/known-issues.yml` 의 REPORT_ONLY 항목으로 제공한다. 이 검색 레시피들은 소스를 자동 수정하지 않는다.
변환 결과가 분명한 두 가지(3.4 조건부 빈의 반환 타입, 4.0 `@Bean ObjectMapper` 반환 타입)는 단계 레시피가 고친다.

| 단계 | 검토 대상 | 확인할 회귀 동작 |
|---|---|---|
| 3.0 | 수동 로그인 SecurityContext 저장 | 동일 세션의 다음 요청도 인증 유지 |
| 3.0 | SPA CSRF | 최초 접근, 로그인, 로그아웃 후 POST |
| 3.2 | 전역 예외 처리와 NoResourceFoundException | 없는 URL 은 404 응답 |
| 3.2 | 비동기 캐시 | 캐시 적중, 완료, 오류 시 동작 |
| 3.2 | 트랜잭션 이벤트 리스너 | 기동 및 commit/rollback 별 저장 |
| 3.2 | 요청 본문 버퍼링과 Content-Length | Content-Length 를 요구하는 서버로의 요청 |
| 3.4 | 조건부 ComponentScan | 조건에 따른 컨텍스트 기동 |
| 3.4 | 캐시 프록시를 거치는 spy stubbing | stubbing 한 값이 캐시되지 않고 spy 에 닿는지 |
| 4.0 | Redis JSON serializer | 기존 데이터, 타입 정보, null 값 호환 |
| 4.0 | HttpHeaders / MultiValueMap | 타입 호환 및 헤더 대소문자 의미 |
| 4.0 | TestExecutionListener | 최상위, 중첩 테스트 초기화 |
| 4.0 | HttpMessageConverter 빈 | 직접 등록한 컨버터로 JSON 이 나가고 들어오는지 |
| 4.0 | OpenFeign 과 Boot 컨버터 customizer | Feign 응답 역직렬화의 JSON 설정 |

검색 결과는 결함 확정이 아니다. 일부 레시피는 파일 단위 조합을 검색하므로 서로 무관한 선언이
같은 파일에 있으면 후보가 될 수 있다. 어노테이션 배열, 생략된 예외 타입, 다른 파일의 합성 어노테이션,
상속/외부 설정 및 별도 메서드의 저장 흐름을 완전히 분석하지 않는다. 스캔은 단계와 무관하게 후보를
보여주며 단계별 적용 여부는 알려진 이슈 리포트에서 확인한다. upstream 적용 후에도 남는 사례를
재현한 뒤 자동 보정으로 확장한다.
