package com.eottabom.migration.exec;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * 대상 프로젝트의 build/test-results 집계. verify.init.gradle 이 ignoreFailures 를 켜므로 build 성공만으로는
 * 알 수 없다. .git / node_modules / .gradle 은 들어가지 않고, build 디렉토리 안에서는 test-results 만 본다 (큰
 * 저장소에서 반복 탐색 비용).
 */
final class TestResults {

	private static final Pattern SUITE = Pattern.compile("<testsuite [^>]*>");

	private static final Set<String> SKIP_DIRS = Set.of(".git", "node_modules", ".gradle", ".idea",
			".rewrite-migration");

	private TestResults() {
	}

	static Summary collect(Path projectDir) {
		int total = 0;
		int failed = 0;
		try {
			for (Path xml : files(projectDir, null)) {
				Matcher m = SUITE.matcher(Files.readString(xml));
				if (m.find()) {
					String suite = m.group();
					total += attribute(suite, "tests");
					failed += attribute(suite, "failures") + attribute(suite, "errors");
				}
			}
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		return new Summary(total, failed);
	}

	/** since 이후에 쓰인 결과 파일 수 (진행 표시용). */
	static int countSince(Path projectDir, FileTime since) {
		return files(projectDir, since).size();
	}

	/** since 이후에 쓰인 결과 XML (null 이면 전부) */
	static List<Path> files(Path projectDir, FileTime since) {
		List<Path> found = new ArrayList<>();
		try {
			Files.walkFileTree(projectDir, new SimpleFileVisitor<>() {
				@Override
				public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
					String name = (dir.getFileName() == null) ? "" : dir.getFileName().toString();
					if (SKIP_DIRS.contains(name)) {
						return FileVisitResult.SKIP_SUBTREE;
					}
					// build 안에서는 test-results 만 내려간다
					Path parent = dir.getParent();
					if (parent != null
							&& "build".equals((parent.getFileName() == null) ? "" : parent.getFileName().toString())
							&& !name.equals("test-results")) {
						return FileVisitResult.SKIP_SUBTREE;
					}
					return FileVisitResult.CONTINUE;
				}

				@Override
				public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
					String name = file.getFileName().toString();
					if (name.startsWith("TEST-") && name.endsWith(".xml") && isUnderTestResults(file)
							&& (since == null || attrs.lastModifiedTime().compareTo(since) > 0)) {
						found.add(file);
					}
					return FileVisitResult.CONTINUE;
				}

				@Override
				public FileVisitResult visitFileFailed(Path file, IOException exc) {
					return FileVisitResult.CONTINUE;
				}
			});
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		return found;
	}

	/** 실패하거나 에러가 난 테스트의 id (클래스#메서드) */
	static Set<String> failedTests(Path projectDir) {
		Set<String> failed = new TreeSet<>();
		DocumentBuilder parser = xmlParser();
		for (Path xml : files(projectDir, null)) {
			try {
				NodeList cases = parser.parse(xml.toFile()).getElementsByTagName("testcase");
				for (int i = 0; i < cases.getLength(); i++) {
					Element tc = (Element) cases.item(i);
					if (firstChild(tc, "failure") != null || firstChild(tc, "error") != null) {
						failed.add(id(tc));
					}
				}
			}
			catch (Exception ex) {
				// 쓰다 만 결과 파일은 건너뛴다
			}
		}
		return failed;
	}

	static String id(Element testcase) {
		return testcase.getAttribute("classname") + "#" + testcase.getAttribute("name");
	}

	static Element firstChild(Element parent, String tag) {
		for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
			if (n instanceof Element e && e.getTagName().equals(tag)) {
				return e;
			}
		}
		return null;
	}

	static DocumentBuilder xmlParser() {
		try {
			DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
			factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
			factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
			factory.setExpandEntityReferences(false);
			return factory.newDocumentBuilder();
		}
		catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

	private static boolean isUnderTestResults(Path file) {
		for (Path p = file.getParent(); p != null && p.getParent() != null; p = p.getParent()) {
			if ("test-results".equals(p.getFileName().toString())
					&& "build".equals(p.getParent().getFileName().toString())) {
				return true;
			}
		}
		return false;
	}

	private static int attribute(String tag, String name) {
		Matcher m = Pattern.compile(" " + name + "=\"(\\d+)\"").matcher(tag);
		return m.find() ? Integer.parseInt(m.group(1)) : 0;
	}

	record Summary(int total, int failed) {
	}

}
