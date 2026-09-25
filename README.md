# boot-migrator

Spring Boot 프로젝트를 **명령 하나로** 최종 버전(Boot 4.1 + Java 25)까지 올리는 OpenRewrite 레시피와 러너.

```bash
./gradlew migrationAnalyze --project-path=~/workspace/my-api                      # 현재 상태 + 수동 검토 대상 (소스 안 바뀜)
./gradlew migrationPlan    --project-path=~/workspace/my-api --spring-boot=3.5    # 실행할 단계와 레시피만 확인
./gradlew migrationRun     --project-path=~/workspace/my-api                      # 현재 버전 -> 4.1 (Java/Gradle 은 지원되면 유지)
./gradlew migrationRun     --project-path=~/workspace/my-api --spring-boot=3.5 --java=latest   # 목표 Boot 가 지원하는 최신 LTS 까지
./gradlew migrationRun     --project-path=~/workspace/my-api --commit             # 단계마다 git commit (기본은 커밋 안 함)
./gradlew migrationRun     --project-path=~/workspace/my-api --preview            # 단계별로 바뀔 내용만 확인 (소스 안 바뀜)
./gradlew migrationVerify  --project-path=~/workspace/my-api                      # 현재 소스의 컴파일 + 전체 테스트
./gradlew migrationHelp                                                           # 태스크와 옵션 안내 (--help 는 Gradle 이 가로채서 쓸 수 없다)
```

JDK(17 / 21 / 25)와 대상 프로젝트의 Gradle wrapper 만 있으면 된다. 이 저장소를 clone 해서 바로 쓴다 (배포, ~/.m2 설치, 별도 도구 없음).

이 저장소는 Gradle 9.4 라서 `JAVA_HOME` 이 JDK 17 이상(25 포함)이면 된다 (레시피 컴파일은 toolchain 이 JDK 25 로 한다).
대상 프로젝트는 별도 프로세스로 자기 Gradle wrapper 로 실행하고, JDK 는 대상 프로젝트가 선언한 toolchain 버전으로 고른다 (`--keep-java-home` 으로 끔).

**필요한 만큼만 바꾼다.** Spring Boot 를 기준으로 삼고, Java 와 Gradle 은 목표 Boot 가 지원하면 그대로 둔다.
지원 범위 밖이거나 사용자가 요청할 때만 별도 단계로 올린다 (`playbook/compatibility.yml`).
공식 가이드와 실제로 겪은 문제는 `playbook/known-issues.yml` 에 쌓고, 단계마다 해당하는 것만 리포트에 나온다.

## 문서

| 문서 | 내용 |
|---|---|
| [사용 가이드](docs/usage.md) | 동작 흐름, 옵션, 리포트 읽는 법, 자동으로 하지 않는 것 |
| [구조와 확장](docs/architecture.md) | 단계 레시피 구성, 파일 구성, 커스텀 레시피 목록, 레시피와 playbook 추가 방법 |
