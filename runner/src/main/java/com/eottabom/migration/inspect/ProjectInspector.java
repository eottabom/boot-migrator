package com.eottabom.migration.inspect;

import com.eottabom.migration.exec.Processes;
import com.eottabom.migration.playbook.Versions;
import com.eottabom.migration.model.ProjectModel;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 대상 프로젝트의 빌드 파일을 읽어 {@link ProjectModel} 을 만든다.
 * 대상 프로젝트의 Gradle 을 띄우지 않고 파일만 본다 (resolve 된 의존성 버전은 verify.init.gradle 의 migrationResolvedVersions).
 */
public final class ProjectInspector {

    // id 'org.springframework.boot' version '3.4.5'  /  id("org.springframework.boot") version "3.4.5"
    private static final Pattern BOOT_PLUGIN = Pattern.compile("org\\.springframework\\.boot['\"]?\\)? version ['\"]([0-9][0-9.]*)");
    // springBootVersion = '2.7.18'  /  set('springBootVersion', '2.7.18')
    private static final Pattern BOOT_PROPERTY = Pattern.compile("springBootVersion['\"]?,? *=? *['\"]([0-9][0-9.]*)");
    private static final Pattern MAJOR_MINOR_PATCH = Pattern.compile("[0-9]+\\.[0-9]+(\\.[0-9]+)?");
    private static final Pattern GRADLE_DIST = Pattern.compile("gradle-([0-9][0-9.]*[0-9])-(bin|all)");
    private static final Pattern TOOLCHAIN = Pattern.compile("JavaLanguageVersion\\.of\\((\\d+)\\)|VERSION_(?:1_)?(\\d+)");
    // JavaLanguageVersion.of(libs.versions.java.get().toInteger())
    private static final Pattern TOOLCHAIN_CATALOG = Pattern.compile("JavaLanguageVersion\\.of\\(\\s*libs\\.versions\\.([\\w.]+?)\\.get\\(\\)");
    private static final Pattern SOURCE_COMPATIBILITY = Pattern.compile("sourceCompatibility *= *['\"]?(?:1\\.)?(\\d+)");
    private static final Set<String> SKIP_DIRS = Set.of(".git", ".gradle", "build", "node_modules", ".rewrite-migration");

    public ProjectModel inspect(Path dir) {
        List<String> buildFiles = readAll(buildGradleFiles(dir));
        VersionCatalog catalog = VersionCatalog.read(dir);
        List<Integer> toolchains = numbers(TOOLCHAIN, buildFiles);
        toolchains.addAll(catalogJava(catalog, buildFiles));
        List<Integer> declared = new ArrayList<>(toolchains);
        declared.addAll(numbers(SOURCE_COMPATIBILITY, buildFiles));
        boolean git = Files.isDirectory(dir.resolve(".git"));
        String status = git ? Processes.capture(dir, "git", "status", "--porcelain") : null;
        return new ProjectModel(
                dir,
                bootVersion(dir, catalog, buildFiles),
                gradleVersion(dir),
                declared.stream().min(Integer::compare).orElse(null),
                toolchains.stream().max(Integer::compare).orElse(null),
                git,
                status != null && !status.isBlank());
    }

    /** rewriteRun 뒤에 다시 읽는 용도. */
    public String bootVersion(Path dir) {
        return bootVersion(dir, VersionCatalog.read(dir), readAll(buildGradleFiles(dir)));
    }

    /**
     * 찾는 순서: 루트 빌드 파일의 플러그인 버전 → springBootVersion 속성 → version catalog 의 org.springframework.boot 플러그인
     * → 서브프로젝트 빌드 파일의 플러그인 버전 (모듈마다 다르면 가장 낮은 것).
     */
    private String bootVersion(Path dir, VersionCatalog catalog, List<String> allBuildFiles) {
        List<Path> rootBuildFiles = List.of(dir.resolve("build.gradle"), dir.resolve("build.gradle.kts"));
        List<Path> propertyFiles = new ArrayList<>(rootBuildFiles);
        propertyFiles.add(dir.resolve("gradle.properties"));
        return first(BOOT_PLUGIN, readAll(rootBuildFiles))
                .or(() -> first(BOOT_PROPERTY, readAll(propertyFiles)))
                .or(() -> catalog.plugin("org.springframework.boot"))
                .or(() -> lowest(BOOT_PLUGIN, allBuildFiles))
                .map(v -> {
                    Matcher m = MAJOR_MINOR_PATCH.matcher(v);
                    return m.find() ? m.group() : null;
                })
                .orElse(null);
    }

    private static List<Integer> catalogJava(VersionCatalog catalog, List<String> buildFiles) {
        List<Integer> found = new ArrayList<>();
        for (String content : buildFiles) {
            Matcher m = TOOLCHAIN_CATALOG.matcher(content);
            while (m.find()) {
                catalog.version(m.group(1)).filter(v -> v.matches("\\d+")).map(Integer::parseInt).ifPresent(found::add);
            }
        }
        return found;
    }

    private static Optional<String> lowest(Pattern pattern, List<String> contents) {
        List<String> all = new ArrayList<>();
        for (String content : contents) {
            Matcher m = pattern.matcher(content);
            while (m.find()) {
                all.add(m.group(1));
            }
        }
        return all.stream().min(Versions::compare);
    }

    public String gradleVersion(Path dir) {
        return first(GRADLE_DIST, readAll(List.of(dir.resolve("gradle/wrapper/gradle-wrapper.properties")))).orElse(null);
    }

    private static Optional<String> first(Pattern pattern, List<String> contents) {
        for (String content : contents) {
            Matcher m = pattern.matcher(content);
            if (m.find()) {
                return Optional.of(m.group(1));
            }
        }
        return Optional.empty();
    }

    private static List<Integer> numbers(Pattern pattern, List<String> contents) {
        List<Integer> found = new ArrayList<>();
        for (String content : contents) {
            Matcher m = pattern.matcher(content);
            while (m.find()) {
                for (int g = 1; g <= m.groupCount(); g++) {
                    if (m.group(g) != null) {
                        found.add(Integer.parseInt(m.group(g)));
                    }
                }
            }
        }
        return found;
    }

    private static List<Path> buildGradleFiles(Path dir) {
        List<Path> files = new ArrayList<>();
        collect(dir, files);
        return files;
    }

    private static void collect(Path dir, List<Path> files) {
        try (Stream<Path> children = Files.list(dir)) {
            for (Path child : children.toList()) {
                String name = child.getFileName().toString();
                if (Files.isDirectory(child)) {
                    if (!SKIP_DIRS.contains(name)) {
                        collect(child, files);
                    }
                } else if (name.equals("build.gradle") || name.equals("build.gradle.kts")) {
                    files.add(child);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<String> readAll(List<Path> files) {
        List<String> contents = new ArrayList<>();
        for (Path file : files) {
            if (Files.isRegularFile(file)) {
                try {
                    contents.add(Files.readString(file));
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        }
        return contents;
    }
}
