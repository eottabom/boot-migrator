package com.eottabom.migration.plan;

import java.util.ArrayList;
import java.util.List;

import com.eottabom.migration.model.MigrationPlan;
import com.eottabom.migration.model.MigrationRequest;
import com.eottabom.migration.model.ProjectModel;
import com.eottabom.migration.model.Stage;
import com.eottabom.migration.playbook.Compatibility;
import com.eottabom.migration.playbook.Compatibility.BootLine;
import com.eottabom.migration.playbook.Compatibility.GradleSupport;
import com.eottabom.migration.playbook.Compatibility.JavaTarget;
import com.eottabom.migration.playbook.Versions;

/**
 * 현재 Boot / Java / Gradle 버전과 목표로 실행할 단계 목록을 정한다.
 *
 * 필요한 만큼만 바꾼다: Java 와 Gradle 은 목표 Boot 가 지원하면 그대로 두고, 지원하지 않거나 사용자가 요청할 때만 별도 단계로 올린다
 * (compatibility.yml). 단계마다 그 단계의 변경만 실행하고, --one-shot 은 체이닝한 목표 레시피 하나로 간다.
 */
public record MigrationPlanner(Compatibility compatibility) {

	/**
	 * spring-boot.yml 의 MigrateToSpringBoot_X_Y 와 맞춘다. 새 단계는 여기, yml, compatibility.yml 에
	 * 함께 추가한다.
	 */
	public static final List<String> BOOT_STAGES = List.of("3.0", "3.1", "3.2", "3.3", "3.4", "3.5", "4.0", "4.1");

	/** upstream(rewrite-spring) 에 UpgradeSpringBoot_X_Y 가 있는 단계. */
	public static final List<String> UPSTREAM_BOOT_STAGES = List.of("3.0", "3.1", "3.2", "3.3", "3.4", "3.5", "4.0");

	public MigrationPlan plan(ProjectModel project, MigrationRequest request) {
		if (project.bootVersion() == null) {
			throw new IllegalArgumentException("Spring Boot 버전을 찾지 못했습니다 (root build.gradle 의 plugins 블록 확인)");
		}
		List<String> bootStages = request.upstreamOnly() ? UPSTREAM_BOOT_STAGES : BOOT_STAGES;
		String target = (request.targetBoot() != null) ? request.targetBoot() : bootStages.get(bootStages.size() - 1);
		if (!bootStages.contains(target)) {
			throw new IllegalArgumentException("목표 버전은 다음 중 하나: " + String.join(" ", bootStages));
		}
		BootLine targetLine = this.compatibility.boot(target);
		Integer targetJava = targetJava(request.targetJava(), targetLine);

		List<String> notes = new ArrayList<>();
		String current = minor(project.bootVersion());
		List<String> bootPath = new ArrayList<>();
		for (String stage : bootStages) {
			if (compare(current, stage) < 0 && compare(stage, target) <= 0) {
				bootPath.add(stage);
			}
		}
		if (request.oneShot() && !bootPath.isEmpty()) {
			bootPath = List.of(target);
		}

		List<Stage> plan = new ArrayList<>();
		String gradle = project.gradleVersion();
		boolean fromBoot2 = compare(current, "3.0") < 0;
		for (String stage : bootPath) {
			gradle = ensureGradle(plan, notes, gradle, this.compatibility.boot(stage),
					stage.equals("3.0") && fromBoot2);
			plan.add(bootStage(stage, request.upstreamOnly(), request.oneShot()));
		}

		int currentJava = (project.javaVersion() != null) ? project.javaVersion() : 0;
		if (targetJava != null && currentJava < targetJava) {
			JavaTarget java = this.compatibility.java(targetJava);
			if (!java.upgradesGradle() && gradle != null && Versions.compare(gradle, java.gradleMin()) < 0) {
				notes.add("Gradle " + gradle + " 는 JDK " + targetJava + " 위에서 뜨지 않는다 (" + java.gradleMin()
						+ "+ 필요) → Gradle 단계 추가");
				plan.add(gradleStage());
				gradle = this.compatibility.gradleUpgradeVersion();
			}
			plan.add(new Stage(Stage.Kind.JAVA, "java" + targetJava, java.recipe()));
		}
		javaNotes(notes, request.targetJava(), project.javaVersion(), targetJava, targetLine);
		return new MigrationPlan(target, targetLine, targetJava, List.copyOf(plan), List.copyOf(notes));
	}

	/** Boot 단계 전에 Gradle 이 그 단계의 지원 범위보다 낮으면 Gradle 단계를 넣는다. 올린 뒤의 Gradle 버전을 돌려준다. */
	private String ensureGradle(List<Stage> plan, List<String> notes, String gradle, BootLine line, boolean fromBoot2) {
		if (gradle == null) {
			return null;
		}
		GradleSupport support = line.gradleSupport(gradle);
		if (support == GradleSupport.TOO_OLD) {
			if (fromBoot2) {
				// Boot 2.x Gradle 플러그인이 Gradle 8.14 에서 동작한다는 보장이 없어 자동으로 올리지 않는다
				notes.add("Boot 3.0 은 Gradle " + line.gradleRange() + " 가 필요하다. 현재 " + gradle
						+ " → Boot 2.x 에서 먼저 Gradle 을 올린다");
				return gradle;
			}
			notes.add("Gradle " + gradle + " 는 Boot " + line.version() + " 지원 범위(" + line.gradleRange()
					+ ") 밖 → Gradle " + this.compatibility.gradleUpgradeVersion() + " 단계 추가");
			plan.add(gradleStage());
			return this.compatibility.gradleUpgradeVersion();
		}
		if (support == GradleSupport.NOT_LISTED) {
			String note = "Gradle " + gradle + " 는 Boot " + line.version() + " 공식 지원 목록(" + line.gradleRange()
					+ ")에 없다. 동작은 하지만 문제가 생기면 이 점을 먼저 본다";
			if (!notes.contains(note)) {
				notes.add(note);
			}
		}
		return gradle;
	}

	/**
	 * auto(기본): 현재 Java 를 목표 Boot 가 지원하면 유지. latest: 목표 Boot 가 지원하는 최신 LTS. 숫자: 그 버전.
	 * none: 올리지 않음.
	 */
	private Integer targetJava(String option, BootLine line) {
		String value = (option != null) ? option : "auto";
		switch (value) {
			case "none":
			case "auto":
				return null;
			case "latest":
				return this.compatibility.latestLts(line);
			default:
				if (!value.matches("\\d+")) {
					throw new IllegalArgumentException("--java 는 auto | latest | none | 17 | 21 | 25");
				}
				int version = Integer.parseInt(value);
				this.compatibility.java(version);
				if (version < line.javaMin() || version > line.javaMax()) {
					throw new IllegalArgumentException("Java " + version + " 는 Boot " + line.version() + " 지원 범위("
							+ line.javaMin() + " ~ " + line.javaMax() + ") 밖이다");
				}
				return version;
		}
	}

	private static void javaNotes(List<String> notes, String option, Integer currentJava, Integer targetJava,
			BootLine line) {
		if (targetJava != null || currentJava == null) {
			return;
		}
		if (currentJava < line.javaMin()) {
			notes.add("Java " + currentJava + " → " + line.javaMin() + ": Boot 3.0 단계 레시피가 최소 요구 버전으로 맞춘다");
		}
		else if (currentJava > line.javaMax()) {
			notes.add("Java " + currentJava + " 는 Boot " + line.version() + " 가 검증한 범위(" + line.javaMin() + " ~ "
					+ line.javaMax() + ")보다 높다");
		}
		else if (currentJava >= line.javaMin() && !"none".equals(option)) {
			notes.add("Java " + currentJava + " 는 Boot " + line.version() + " 지원 범위(" + line.javaMin() + " ~ "
					+ line.javaMax() + ") 안이라 유지한다 (올리려면 --java=latest 또는 --java=21)");
		}
	}

	private Stage gradleStage() {
		return new Stage(Stage.Kind.GRADLE, "gradle" + this.compatibility.gradleUpgradeVersion(),
				this.compatibility.gradleUpgradeRecipe());
	}

	/**
	 * 단계별 실행(기본)은 그 단계의 변경만 담은 레시피를 쓴다. 이미 지난 단계의 레시피(Boot 2.x best practice 등)가 다시 돌지
	 * 않는다. --one-shot 은 한 번에 목표까지 가야 하므로 직전 단계까지 체이닝한 레시피를 쓴다. --upstream-only 는 upstream
	 * 만: 단계별이면 직전 단계 체인을 뺀 upstream 단계 레시피 (3.0 은 2.x 에서 올라오는 입구라 전체 체인).
	 */
	private static Stage bootStage(String version, boolean upstreamOnly, boolean oneShot) {
		String suffix = version.replace('.', '_');
		String upstreamFull = "org.openrewrite.java.spring.boot" + version.charAt(0) + ".UpgradeSpringBoot_" + suffix;
		String recipe;
		if (upstreamOnly) {
			recipe = (oneShot || version.equals("3.0")) ? upstreamFull
					: "com.eottabom.rewrite.spring.upstream.UpgradeSpringBootStep_" + suffix;
		}
		else {
			recipe = oneShot ? "com.eottabom.rewrite.spring.MigrateToSpringBoot_" + suffix
					: "com.eottabom.rewrite.spring.SpringBootStep_" + suffix;
		}
		return new Stage(Stage.Kind.BOOT, version, recipe);
	}

	static String minor(String version) {
		String[] parts = version.split("\\.");
		return (parts.length >= 2) ? parts[0] + "." + parts[1] : version;
	}

	static int compare(String a, String b) {
		return Versions.compare(minor(a), minor(b));
	}
}
