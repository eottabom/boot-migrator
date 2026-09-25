package com.eottabom.rewrite;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 생성 파일이 지금 레시피로 만든 결과와 같은지. 다르면 ./gradlew syncUpstreamSteps
 */
class UpstreamStepsUpToDateTests {

	@ParameterizedTest(name = "[{index}] {0}")
	@MethodSource("generatedFiles")
	void generatedFileIsUpToDate(Path file, Supplier<String> generator) throws IOException {
		assertThat(Files.readString(file)).as("레시피가 바뀌었다. ./gradlew syncUpstreamSteps 로 다시 만든다")
			.isEqualTo(generator.get());
	}

	static Stream<Arguments> generatedFiles() {
		return Stream.of(
				Arguments.of(UpstreamStepsGenerator.OUTPUT, (Supplier<String>) UpstreamStepsGenerator::generate),
				Arguments.of(VersionCatalogStepsGenerator.OUTPUT,
						(Supplier<String>) VersionCatalogStepsGenerator::generate));
	}

}
