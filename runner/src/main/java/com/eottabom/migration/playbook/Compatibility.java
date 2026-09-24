package com.eottabom.migration.playbook;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** playbook/compatibility.yml: Spring Boot 단계별 Java / Gradle / Spring Cloud 호환성. */
public record Compatibility(Map<String, BootLine> boot, List<Integer> lts, Map<Integer, JavaTarget> java,
                            String gradleUpgradeVersion, String gradleUpgradeRecipe) {

    /**
     * @param gradle Gradle major → 그 major 에서 지원하는 최소 버전
     */
    public record BootLine(String version, int javaMin, int javaMax, Map<Integer, String> gradle,
                           String framework, String springCloudTrain, String springCloudSince, String springCloudAws) {

        public GradleSupport gradleSupport(String gradleVersion) {
            int major = Versions.major(gradleVersion);
            String min = gradle.get(major);
            if (min != null) {
                return Versions.compare(gradleVersion, min) >= 0 ? GradleSupport.SUPPORTED : GradleSupport.TOO_OLD;
            }
            return major < gradle.keySet().stream().min(Integer::compare).orElseThrow() ? GradleSupport.TOO_OLD : GradleSupport.NOT_LISTED;
        }

        /** 예) "7.6.4+ / 8.4+" */
        public String gradleRange() {
            return String.join(" / ", gradle.values().stream().map(v -> v + "+").toList());
        }
    }

    public enum GradleSupport { SUPPORTED, TOO_OLD, NOT_LISTED }

    /**
     * @param gradleMin      이 JDK 위에서 Gradle 을 띄울 수 있는 최소 버전
     * @param upgradesGradle 레시피가 Gradle 도 함께 올린다 (따로 Gradle 단계를 넣지 않는다)
     */
    public record JavaTarget(int version, String gradleMin, String recipe, boolean upgradesGradle) {
    }

    public static Compatibility load(Path file) {
        Map<String, Object> root = Yaml.load(file);

        Map<String, BootLine> boot = new LinkedHashMap<>();
        Yaml.map(root.get("springBoot")).forEach((version, value) -> {
            Map<String, Object> line = Yaml.map(value);
            Map<String, Object> javaRange = Yaml.map(line.get("java"));
            Map<Integer, String> gradle = new TreeMap<>();
            Yaml.map(line.get("gradle")).forEach((major, min) -> gradle.put(Integer.parseInt(major), Yaml.string(min)));
            Map<String, Object> cloud = Yaml.map(line.get("springCloud"));
            boot.put(version, new BootLine(version,
                    (Integer) javaRange.get("min"), (Integer) javaRange.get("max"), gradle,
                    Yaml.string(line.get("framework")), Yaml.string(cloud.get("train")), Yaml.string(cloud.get("since")),
                    Yaml.string(line.get("springCloudAws"))));
        });

        Map<String, Object> javaSection = Yaml.map(root.get("java"));
        List<Integer> lts = new ArrayList<>();
        Yaml.list(javaSection.get("lts")).forEach(v -> lts.add((Integer) v));
        Map<Integer, JavaTarget> java = new TreeMap<>();
        javaSection.forEach((key, value) -> {
            if (key.matches("\\d+")) {
                Map<String, Object> target = Yaml.map(value);
                java.put(Integer.parseInt(key), new JavaTarget(Integer.parseInt(key), Yaml.string(target.get("gradleMin")),
                        Yaml.string(target.get("recipe")), Boolean.TRUE.equals(target.get("upgradesGradle"))));
            }
        });

        Map<String, Object> gradleUpgrade = Yaml.map(root.get("gradleUpgrade"));
        return new Compatibility(boot, List.copyOf(lts), java,
                Yaml.string(gradleUpgrade.get("version")), Yaml.string(gradleUpgrade.get("recipe")));
    }

    public BootLine boot(String version) {
        BootLine line = boot.get(version);
        if (line == null) {
            throw new IllegalArgumentException("compatibility.yml 에 Spring Boot " + version + " 이 없다");
        }
        return line;
    }

    /** 목표 Boot 가 지원하는 가장 높은 LTS. */
    public int latestLts(BootLine line) {
        return lts.stream().filter(v -> v <= line.javaMax()).max(Integer::compare).orElse(line.javaMin());
    }

    public JavaTarget java(int version) {
        JavaTarget target = java.get(version);
        if (target == null) {
            throw new IllegalArgumentException("--java 는 " + String.join(" | ", java.keySet().stream().map(String::valueOf).toList()) + " | latest | auto | none");
        }
        return target;
    }
}
