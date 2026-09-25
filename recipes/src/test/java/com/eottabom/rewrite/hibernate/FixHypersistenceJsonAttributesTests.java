package com.eottabom.rewrite.hibernate;

import org.junit.jupiter.api.Test;
import org.openrewrite.java.JavaParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.java.Assertions.java;

class FixHypersistenceJsonAttributesTests implements RewriteTest {

	@Override
	public void defaults(RecipeSpec spec) {
		spec.recipe(new FixHypersistenceJsonAttributes())
			.parser(JavaParser.fromJavaVersion()
				.dependsOn("package org.hibernate.annotations; public @interface Type { Class<?> value(); }",
						"package io.hypersistence.utils.hibernate.type.json; public class JsonStringType {}",
						"package lombok; public @interface Getter {}",
						"package lombok; public @interface EqualsAndHashCode {}"));
	}

	@Test
	void addsSerializableToJsonAttributeTypesAndChildren() {
		rewriteRun(java("""
				package com.example;
				import io.hypersistence.utils.hibernate.type.json.JsonStringType;
				import org.hibernate.annotations.Type;
				import java.util.List;
				public class Event {
				    @Type(JsonStringType.class)
				    private List<Condition> conditions;
				    @Type(JsonStringType.class)
				    private EtcCondition etc;
				    private Plain notJson;
				}
				"""), java("""
				package com.example;
				public class Condition {
				    private Detail detail;
				}
				""", """
				package com.example;

				import java.io.Serializable;

				public class Condition implements Serializable {
				    private Detail detail;
				}
				"""), java("""
				package com.example;
				public class Detail {
				    private String name;
				}
				""", """
				package com.example;

				import java.io.Serializable;

				public class Detail implements Serializable {
				    private String name;
				}
				"""), java("""
				package com.example;
				public class EtcCondition {
				    private boolean excludeReservation;
				}
				""", """
				package com.example;

				import java.io.Serializable;

				public class EtcCondition implements Serializable {
				    private boolean excludeReservation;
				}
				"""), java("""
				package com.example;
				public class Plain {
				    private String value;
				}
				"""));
	}

	@Test
	void addsEqualsToLombokValueObjects() {
		rewriteRun(java("""
				package com.example;
				import io.hypersistence.utils.hibernate.type.json.JsonStringType;
				import org.hibernate.annotations.Type;
				import java.util.List;
				public class Event {
				    @Type(JsonStringType.class)
				    private List<Item> items;
				    @Type(JsonStringType.class)
				    private Single single;
				}
				"""), java("""
				package com.example;
				import lombok.Getter;
				@Getter
				public class Item {
				    private String code;
				}
				""", """
				package com.example;
				import lombok.EqualsAndHashCode;
				import lombok.Getter;

				import java.io.Serializable;

				@EqualsAndHashCode
				@Getter
				public class Item implements Serializable {
				    private String code;
				}
				"""), java("""
				package com.example;
				import lombok.Getter;
				@Getter
				public class Single {
				    private String code;
				}
				""", """
				package com.example;
				import lombok.EqualsAndHashCode;
				import lombok.Getter;

				import java.io.Serializable;

				@EqualsAndHashCode
				@Getter
				public class Single implements Serializable {
				    private String code;
				}
				"""));
	}

	@Test
	void skipsEqualsWhenClassExtendsAnother() {
		rewriteRun(java("""
				package com.example;
				import io.hypersistence.utils.hibernate.type.json.JsonStringType;
				import org.hibernate.annotations.Type;
				public class Holder {
				    @Type(JsonStringType.class)
				    private Child child;
				}
				"""), java("""
				package com.example;
				public class Base implements java.io.Serializable {
				    private String common;
				}
				"""), java("""
				package com.example;
				import lombok.Getter;
				@Getter
				public class Child extends Base {
				    private String code;
				}
				"""));
	}

}
