package com.eottabom.migration.task;

import org.gradle.api.DefaultTask;
import org.gradle.api.tasks.TaskAction;

/** ./gradlew migrationHelp : 태스크와 옵션 안내. */
public abstract class MigrationHelpTask extends DefaultTask {

	static final String USAGE = """
			Spring Boot 마이그레이션 (OpenRewrite)

			태스크
			  migrationAnalyze   현재 Boot / Gradle / Java, resolve 된 의존성, 수동 검토 대상 위치 (소스 안 바뀜)
			  migrationPlan      실행할 단계, 호환성 판단 근거, 단계별 알려진 이슈 미리보기 (대상 Gradle 을 띄우지 않음)
			  migrationRun       단계별 마이그레이션: rewriteRun → compile → build(테스트) → 리포트 → (commit)
			  migrationVerify    현재 소스의 컴파일 + 전체 테스트 (소스 안 바뀜)
			  migrationHelp      이 안내

			공통 옵션
			  --project-path=<경로>         대상 프로젝트 (필수). 상대 경로는 명령을 실행한 위치 기준
			  --keep-java-home              JDK 자동 선택을 끄고 현재 JAVA_HOME 으로 대상 프로젝트를 실행
			  --gradle-jvmargs="<옵션>"     대상 Gradle 데몬 JVM 옵션 (기본: -Xmx 는 장비 메모리의 절반, 최대 6g)

			migrationPlan / migrationRun
			  --spring-boot=<버전>          목표 Boot: 3.0 ~ 3.5 | 4.0 | 4.1 (기본: 최종 단계)
			  --java=<값>                   auto(기본: 목표 Boot 가 지원하면 유지) | latest(지원하는 최신 LTS) | 17 | 21 | 25 | none
			  --one-shot                    단계별 게이트 없이 목표 레시피를 한 번에
			  --upstream-only               커스텀/프로젝트 레시피 없이 upstream 만 (비교용)
			  --skip-project-recipes        대상 프로젝트의 .rewrite/ 레시피를 붙이지 않음

			migrationRun
			  --gate=<값>                   build(기본: 컴파일 + 전체 테스트 + 패키징) | compile | none
			                                테스트가 하나라도 깨지거나 이 단계에서 빌드가 깨지면 멈추고 커밋하지 않는다
			  --commit                      게이트를 통과한 단계마다 git commit (작업 트리가 깨끗해야 함)
			  --preview                     소스를 바꾸지 않고 다음 단계 patch 만 만든다 (Gradle 자체의 --dry-run 과 다름)
			  --allow-dirty                 커밋되지 않은 변경이 있어도 시작

			migrationVerify
			  --gate=<값>                   build(기본) | compile

			예
			  ./gradlew migrationPlan --project-path=../my-api --spring-boot=3.5
			  ./gradlew migrationRun  --project-path=../my-api --commit
			  ./gradlew migrationRun  --project-path=../my-api --spring-boot=4.0 --java=latest --preview

			결과는 대상 프로젝트의 .rewrite-migration/ (단계별 리포트 NN-*.md, 누적 patch, SUMMARY.md).
			멈춘 뒤 고치고 같은 명령을 다시 실행하면 멈춘 단계부터 이어서 진행한다.
			프로젝트 전용 레시피: 대상 프로젝트의 .rewrite/ 에 tags ["migration-stage:4.0", "migration-phase:before|after"]
			자세한 내용: docs/usage.md, 옵션 원문: ./gradlew help --task migrationRun
			""";

	public MigrationHelpTask() {
		setGroup("migration");
		setDescription("migration 태스크와 옵션 안내");
	}

	@TaskAction
	public void print() {
		// -q 로 실행해도 보이도록 quiet 레벨로 찍는다
		getLogger().quiet(USAGE);
	}

}
