package com.eottabom.migration.playbook;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * playbook/*.yml 을 Map 으로 읽는 도우미. 키와 값은 문자열로 다룬다 (YAML 의 3.0 이 숫자 3 으로 읽히지 않도록 파일에서 따옴표를
 * 쓴다).
 */
final class Yaml {

	private Yaml() {
	}

	static Map<String, Object> load(Path file) {
		try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			Map<String, Object> root = new org.yaml.snakeyaml.Yaml().load(in);
			if (root == null) {
				throw new IllegalArgumentException("비어 있다: " + file);
			}
			return root;
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	@SuppressWarnings("unchecked")
	static Map<String, Object> map(Object value) {
		return (value != null) ? (Map<String, Object>) value : Map.of();
	}

	@SuppressWarnings("unchecked")
	static List<Object> list(Object value) {
		return (value != null) ? (List<Object>) value : List.of();
	}

	static String string(Object value) {
		return (value != null) ? String.valueOf(value) : null;
	}

}
