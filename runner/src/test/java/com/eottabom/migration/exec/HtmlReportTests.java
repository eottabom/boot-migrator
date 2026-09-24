package com.eottabom.migration.exec;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.Yaml;

import static org.assertj.core.api.Assertions.assertThat;

@SuppressWarnings("unchecked")
class HtmlReportTests {

	@TempDir
	Path project;

	@Test
	void embedsStageReportsAndDiffsIntoOnePage() throws IOException {
		MigrationWorkspace ws = MigrationWorkspace.in(this.project);
		// 백슬래시, 한글, </script> 가 그대로 살아남아야 한다
		Files.writeString(ws.file("01-boot-3.4.report.json"),
				"{\"stage\":\"3.4\",\"compile\":\"ok\",\"tests\":{\"total\":1,\"failures\":[]},"
						+ "\"manual\":[{\"file\":\"A.java\",\"code\":\"String p = \\\"C:\\\\\\\\tmp\\\"; // 한글 </script>\"}]}");
		Files.writeString(ws.file("01-boot-3.4.stage.patch"),
				"diff --git a/A.java b/A.java\n--- a/A.java\n+++ b/A.java\n@@ -1 +1 @@\n-a\n+b\n");
		Files.writeString(ws.file("02-java21.report.json"), "{\"stage\":\"java21\"}");

		Path html = HtmlReport.write(ws, "demo", "3.3.5", "3.4.13");

		String page = Files.readString(html);
		assertThat(page).doesNotContain("/*__DATA__*/").doesNotContain("한글 </script>");
		Matcher m = Pattern.compile("<script id=\"data\" type=\"application/json\">(.*?)</script>", Pattern.DOTALL)
			.matcher(page);
		assertThat(m.find()).isTrue();
		// 페이지는 </script> 를 막으려고 <\/ 로 쓴다 (JSON 에서는 같은 문자). YAML 파서는 \/ 를 모르므로 되돌려서 읽는다
		Map<String, Object> data = new Yaml().load(m.group(1).replace("<\\/", "</"));
		assertThat(data).containsEntry("project", "demo").containsEntry("startBoot", "3.3.5");
		List<Map<String, Object>> stages = (List<Map<String, Object>>) data.get("stages");
		assertThat(stages).extracting((s) -> s.get("name")).containsExactly("3.4", "java21");
		Map<String, Object> report = (Map<String, Object>) stages.get(0).get("report");
		Map<String, Object> manual = ((List<Map<String, Object>>) report.get("manual")).get(0);
		assertThat(manual.get("code")).isEqualTo("String p = \"C:\\\\tmp\"; // 한글 </script>");
		assertThat((String) stages.get(0).get("patch")).contains("+b");
		assertThat(stages.get(1).get("patch")).isNull();
	}

}
