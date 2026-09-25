package com.eottabom.rewrite.testing;

import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.openrewrite.java.JavaParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.java.Assertions.java;

class FindSpyStubbingThroughCachingProxyTests implements RewriteTest {

	private static final String SERVICE = """
			package com.example;

			import org.springframework.cache.annotation.Cacheable;

			public class ProductService implements Catalog {
			    @Cacheable("products")
			    public String find(String id) {
			        return id;
			    }
			}
			""";

	@Override
	public void defaults(RecipeSpec spec) {
		spec.recipe(new FindSpyStubbingThroughCachingProxy())
			.parser(JavaParser.fromJavaVersion()
				.dependsOn(
						"package org.springframework.cache.annotation; public @interface Cacheable { String[] value() default {}; }",
						"package org.springframework.test.context.bean.override.mockito; public @interface MockitoSpyBean {}",
						"package com.example; public interface Catalog { String find(String id); }", """
												package org.mockito;
												public class BDDMockito {
												    public static <T> T given(T call) { return call; }
												    public static BDDMockito willReturn(Object value) { return null; }
												}
								""",
						"""
								package org.springframework.test.util;
								public class AopTestUtils { public static <T> T getUltimateTargetObject(Object candidate) { return null; } }
								"""));
	}

	@ParameterizedTest(name = "[{index}] {0}")
	@MethodSource("scenarios")
	void marksSpyStubbedThroughCachingProxy(String scenario, String before, String after) {
		rewriteRun(java(SERVICE), (after != null) ? java(before, after) : java(before));
	}

	// @formatter:off
	static Stream<Arguments> scenarios() {
		return Stream.of(
			Arguments.of(
				"캐시 빈의 spy 를 프록시로 stubbing 하면 필드를 표시",
				"""
				import com.example.Catalog;
				import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

				import static org.mockito.BDDMockito.given;

				class CatalogTests {
				    @MockitoSpyBean
				    Catalog catalog;

				    void stub() {
				        given(catalog.find("1"));
				    }
				}
				""",
				"""
				import com.example.Catalog;
				import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

				import static org.mockito.BDDMockito.given;

				class CatalogTests {
				    /*~~>*/@MockitoSpyBean
				    Catalog catalog;

				    void stub() {
				        given(catalog.find("1"));
				    }
				}
				"""
			),
			Arguments.of(
				"AopTestUtils 로 spy 를 꺼내면 그대로",
				"""
				import com.example.ProductService;
				import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
				import org.springframework.test.util.AopTestUtils;

				import static org.mockito.BDDMockito.given;

				class ProductServiceTests {
				    @MockitoSpyBean
				    ProductService productService;

				    void stub() {
				        ProductService spy = AopTestUtils.getUltimateTargetObject(productService);
				        given(spy.find("1"));
				    }
				}
				""",
				null
			)
		);
	}
	// @formatter:on

}
