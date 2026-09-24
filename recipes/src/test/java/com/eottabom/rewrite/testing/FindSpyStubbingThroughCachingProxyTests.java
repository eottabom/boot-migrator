package com.eottabom.rewrite.testing;

import org.junit.jupiter.api.Test;
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

	@Test
	void marksSpyOfCachedBeanStubbedThroughProxy() {
		rewriteRun(java(SERVICE), java("""
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
				""", """
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
				"""));
	}

	@Test
	void skipsFileThatUnwrapsSpyWithAopTestUtils() {
		rewriteRun(java(SERVICE), java("""
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
				"""));
	}

}
