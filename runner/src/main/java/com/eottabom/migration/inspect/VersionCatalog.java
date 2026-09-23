package com.eottabom.migration.inspect;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * gradle/libs.versions.toml 에서 버전을 찾는 데 필요한 만큼만 읽는다 ([versions] 와 [plugins] 의 한 줄 항목).
 *
 * <pre>
 * [versions]
 * spring-boot = "4.0.7"
 * [plugins]
 * spring-boot = { id = "org.springframework.boot", version.ref = "spring-boot" }
 * </pre>
 */
final class VersionCatalog {

    private static final Pattern SECTION = Pattern.compile("^\\s*\\[([\\w.-]+)]\\s*$");
    private static final Pattern STRING_ENTRY = Pattern.compile("^\\s*([\\w.-]+)\\s*=\\s*\"([^\"]*)\"");
    private static final Pattern TABLE_ENTRY = Pattern.compile("^\\s*([\\w.-]+)\\s*=\\s*\\{(.*)}");
    private static final Pattern ID = Pattern.compile("\\bid\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern VERSION_REF = Pattern.compile("\\bversion\\.ref\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern VERSION = Pattern.compile("\\bversion\\s*=\\s*\"([^\"]+)\"");

    /** 정규화한 키(-, _, . 를 구분하지 않음) → 버전 */
    private final Map<String, String> versions = new HashMap<>();
    /** 플러그인 id → 버전 */
    private final Map<String, String> plugins = new HashMap<>();

    static VersionCatalog read(Path projectDir) {
        VersionCatalog catalog = new VersionCatalog();
        Path file = projectDir.resolve("gradle/libs.versions.toml");
        if (!Files.isRegularFile(file)) {
            return catalog;
        }
        try {
            String section = "";
            Map<String, String[]> pluginRefs = new HashMap<>();
            for (String line : Files.readAllLines(file)) {
                String content = line.replaceFirst("\\s#.*$", "");
                Matcher s = SECTION.matcher(content);
                if (s.matches()) {
                    section = s.group(1);
                    continue;
                }
                if (section.equals("versions")) {
                    Matcher m = STRING_ENTRY.matcher(content);
                    if (m.find()) {
                        catalog.versions.put(normalize(m.group(1)), m.group(2));
                    }
                } else if (section.equals("plugins")) {
                    Matcher m = TABLE_ENTRY.matcher(content);
                    if (m.find()) {
                        Matcher id = ID.matcher(m.group(2));
                        if (id.find()) {
                            Matcher ref = VERSION_REF.matcher(m.group(2));
                            Matcher version = VERSION.matcher(m.group(2));
                            pluginRefs.put(id.group(1), new String[]{ref.find() ? ref.group(1) : null, version.find() ? version.group(1) : null});
                        }
                    }
                }
            }
            pluginRefs.forEach((id, ref) -> {
                String version = ref[0] != null ? catalog.versions.get(normalize(ref[0])) : ref[1];
                if (version != null) {
                    catalog.plugins.put(id, version);
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return catalog;
    }

    Optional<String> plugin(String id) {
        return Optional.ofNullable(plugins.get(id));
    }

    /** libs.versions.java 처럼 빌드 스크립트의 접근자 경로로 찾는다. */
    Optional<String> version(String accessor) {
        return Optional.ofNullable(versions.get(normalize(accessor)));
    }

    private static String normalize(String key) {
        return key.replaceAll("[-_.]", ".").toLowerCase();
    }
}
