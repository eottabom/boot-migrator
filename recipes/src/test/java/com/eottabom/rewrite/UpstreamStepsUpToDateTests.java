package com.eottabom.rewrite;

import java.io.IOException;
import java.nio.file.Files;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 생성 파일(upstream-spring-boot-steps.yml, version-catalog-steps.yml)이 지금 레시피로 만든 결과와 같은지.
 * 다르면 ./gradlew syncUpstreamSteps
 */
class UpstreamStepsUpToDateTests {

	@Test
	void upstreamStepsAreUpToDate() throws IOException {
		assertThat(Files.readString(UpstreamStepsGenerator.OUTPUT))
			.as("rewrite-spring 이 바뀌었다. ./gradlew syncUpstreamSteps 로 다시 만든다")
			.isEqualTo(UpstreamStepsGenerator.generate());
	}

	@Test
	void versionCatalogStepsAreUpToDate() throws IOException {
		assertThat(Files.readString(VersionCatalogStepsGenerator.OUTPUT))
			.as("단계 레시피가 바뀌었다. ./gradlew syncUpstreamSteps 로 다시 만든다")
			.isEqualTo(VersionCatalogStepsGenerator.generate());
	}

}
