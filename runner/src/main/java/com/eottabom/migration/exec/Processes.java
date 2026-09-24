package com.eottabom.migration.exec;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** 짧게 끝나는 외부 명령(git, java_home) 실행. */
public final class Processes {

	private Processes() {
	}

	/** 명령의 표준 출력. 실패(0 이 아닌 종료 코드)하면 null. */
	public static String capture(Path dir, String... command) {
		return capture(dir, Map.of(), command);
	}

	public static String capture(Path dir, Map<String, String> env, String... command) {
		try {
			ProcessBuilder builder = new ProcessBuilder(command).directory(dir.toFile());
			builder.environment().putAll(env);
			Process process = builder.redirectErrorStream(true).start();
			String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
			return (process.waitFor() == 0) ? out : null;
		}
		catch (IOException ex) {
			return null;
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(ex);
		}
	}

	/** 명령을 실행하고 종료 코드가 0 인지 돌려준다. out 이 있으면 표준 출력을 그 파일로 보낸다. */
	public static boolean run(Path dir, Path out, List<String> command) {
		return run(dir, out, command, Map.of());
	}

	public static boolean run(Path dir, Path out, List<String> command, Map<String, String> env) {
		try {
			ProcessBuilder builder = new ProcessBuilder(command).directory(dir.toFile());
			builder.environment().putAll(env);
			if (out != null) {
				builder.redirectOutput(out.toFile());
			}
			else {
				builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
			}
			builder.redirectError(ProcessBuilder.Redirect.DISCARD);
			return builder.start().waitFor() == 0;
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(ex);
		}
	}

}
