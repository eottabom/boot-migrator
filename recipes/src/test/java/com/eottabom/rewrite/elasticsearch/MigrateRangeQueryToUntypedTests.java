package com.eottabom.rewrite.elasticsearch;

import org.junit.jupiter.api.Test;
import org.openrewrite.java.JavaParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;
import org.openrewrite.test.TypeValidation;

import static org.openrewrite.java.Assertions.java;

class MigrateRangeQueryToUntypedTests implements RewriteTest {

	// elasticsearch-java 8.13 모양의 stub
	private static final String[] ES_8_13 = { """
			package co.elastic.clients.json;
			public class JsonData { public static JsonData of(Object o) { return null; } }
			""", """
			package co.elastic.clients.elasticsearch._types.query_dsl;
			import co.elastic.clients.json.JsonData;
			public class RangeQuery {
			    public static class Builder {
			        public Builder field(String f) { return this; }
			        public Builder gte(JsonData v) { return this; }
			        public Builder lte(JsonData v) { return this; }
			        public RangeQuery build() { return null; }
			    }
			}
			""", """
			package co.elastic.clients.elasticsearch._types.query_dsl;
			public class Query {
			    public static class Builder {
			        public Builder range(RangeQuery r) { return this; }
			        public Query build() { return null; }
			    }
			}
			""" };

	@Override
	public void defaults(RecipeSpec spec) {
		spec.recipe(new MigrateRangeQueryToUntyped())
			.parser(JavaParser.fromJavaVersion().dependsOn(ES_8_13))
			.typeValidationOptions(TypeValidation.none());
	}

	@Test
	void builderVariable() {
		rewriteRun(java("""
				import co.elastic.clients.elasticsearch._types.query_dsl.Query;
				import co.elastic.clients.elasticsearch._types.query_dsl.RangeQuery;
				import co.elastic.clients.json.JsonData;

				class Search {
				    Query price(Long min, Long max) {
				        RangeQuery.Builder rangeQueryBuilder = new RangeQuery.Builder()
				                .field("basePrice");
				        return new Query.Builder()
				                .range(rangeQueryBuilder.gte(JsonData.of(min))
				                        .lte(JsonData.of(max))
				                        .build())
				                .build();
				    }
				}
				""", """
				import co.elastic.clients.elasticsearch._types.query_dsl.Query;
				import co.elastic.clients.elasticsearch._types.query_dsl.UntypedRangeQuery;
				import co.elastic.clients.json.JsonData;

				class Search {
				    Query price(Long min, Long max) {
				        UntypedRangeQuery.Builder rangeQueryBuilder = new UntypedRangeQuery.Builder()
				                .field("basePrice");
				        return new Query.Builder()
				                .range(rangeQueryBuilder.gte(JsonData.of(min))
				                        .lte(JsonData.of(max))
				                        .build()._toRangeQuery())
				                .build();
				    }
				}
				"""));
	}

}
