package com.eottabom.migration.inspect;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.eottabom.migration.exec.Processes;

/**
 * 대상 프로젝트의 Gradle 을 띄울 JDK 를 고른다. 서브모듈에 toolchain 이 없으면 Gradle 을 띄운 JVM 으로 컴파일되므로, 프로젝트가
 * 선언한 버전의 JDK 를 쓴다.
 *
 * 찾는 순서 (OS 공통) 1. 환경 변수 JAVA_HOME_{N}_X64 / JAVA_HOME_{N}_AARCH64 / JAVA_HOME_{N}
 * (GitHub Actions setup-java 등) 2. macOS: /usr/libexec/java_home -v N 3. 설치 디렉토리의 release
 * 파일(JAVA_VERSION): /usr/lib/jvm, /Library/Java/JavaVirtualMachines,
 * ~/Library/Java/JavaVirtualMachines, ~/.sdkman/candidates/java, ~/.gradle/jdks, ~/.jdks,
 * ~/.asdf/installs/java, C:\Program Files\{Java,Eclipse Adoptium,Zulu,Amazon Corretto} 못
 * 찾으면 현재 JAVA_HOME 을 쓴다.
 */
public final class JdkLocator {

	private static final Path MAC_JAVA_HOME = Path.of("/usr/libexec/java_home");

	private static final Pattern RELEASE_VERSION = Pattern.compile("(?m)^JAVA_VERSION=\"(?:1\\.)?(\\d+)");

	/** 선언된 버전의 JDK 가 설치되어 있으면 그 경로, 아니면 현재 JAVA_HOME (없으면 null). */
	public String javaHomeFor(Integer version) {
		String current = System.getenv("JAVA_HOME");
		if (version == null) {
			return current;
		}
		return fromEnvironment(version).or(() -> fromMacJavaHome(version))
			.or(() -> fromInstallDirectories(version))
			.orElse(current);
	}

	private static Optional<String> fromEnvironment(int version) {
		for (String name : List.of("JAVA_HOME_" + version + "_X64", "JAVA_HOME_" + version + "_AARCH64",
				"JAVA_HOME_" + version + "_ARM64", "JAVA_HOME_" + version)) {
			String home = System.getenv(name);
			if (home != null && Files.isDirectory(Path.of(home))) {
				return Optional.of(home);
			}
		}
		return Optional.empty();
	}

	private static Optional<String> fromMacJavaHome(int version) {
		if (!Files.isExecutable(MAC_JAVA_HOME)) {
			return Optional.empty();
		}
		Path cwd = Path.of(System.getProperty("user.home"));
		// java_home -v 17 은 17 이 없으면 다른 버전을 돌려주므로 설치 목록에 해당 major 가 있을 때만 쓴다
		String installed = Processes.capture(cwd, MAC_JAVA_HOME.toString(), "-V");
		if (installed == null || !Pattern.compile("(?m)^ +" + version + "[ .]").matcher(installed).find()) {
			return Optional.empty();
		}
		String home = Processes.capture(cwd, MAC_JAVA_HOME.toString(), "-v", String.valueOf(version));
		return Optional.ofNullable(home).map(String::trim).filter((h) -> !h.isEmpty());
	}

	static Optional<String> fromInstallDirectories(int version) {
		String userHome = System.getProperty("user.home");
		List<Path> roots = List.of(Path.of("/usr/lib/jvm"), Path.of("/Library/Java/JavaVirtualMachines"),
				Path.of(userHome, "Library/Java/JavaVirtualMachines"), Path.of(userHome, ".sdkman/candidates/java"),
				Path.of(userHome, ".gradle/jdks"), Path.of(userHome, ".jdks"), Path.of(userHome, ".asdf/installs/java"),
				Path.of("C:\\Program Files\\Java"), Path.of("C:\\Program Files\\Eclipse Adoptium"),
				Path.of("C:\\Program Files\\Zulu"), Path.of("C:\\Program Files\\Amazon Corretto"));
		for (Path root : roots) {
			for (Path home : candidates(root)) {
				if (majorVersion(home).filter((v) -> v == version).isPresent()) {
					return Optional.of(home.toString());
				}
			}
		}
		return Optional.empty();
	}

	/** root 아래의 JDK 홈 후보. macOS 번들은 Contents/Home 이 실제 홈이다. */
	private static List<Path> candidates(Path root) {
		List<Path> homes = new ArrayList<>();
		if (!Files.isDirectory(root)) {
			return homes;
		}
		try (Stream<Path> children = Files.list(root)) {
			for (Path child : children.sorted().toList()) {
				Path bundle = child.resolve("Contents/Home");
				homes.add(Files.isDirectory(bundle) ? bundle : child);
			}
		}
		catch (IOException ignored) {
			// 읽을 수 없는 디렉토리는 건너뛴다
		}
		return homes;
	}

	static Optional<Integer> majorVersion(Path home) {
		Path release = home.resolve("release");
		if (!Files.isRegularFile(release)) {
			return Optional.empty();
		}
		try {
			Matcher m = RELEASE_VERSION.matcher(Files.readString(release));
			return m.find() ? Optional.of(Integer.parseInt(m.group(1))) : Optional.empty();
		}
		catch (IOException ex) {
			return Optional.empty();
		}
	}

}
