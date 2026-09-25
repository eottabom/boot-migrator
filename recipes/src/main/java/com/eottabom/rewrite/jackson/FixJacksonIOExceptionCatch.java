package com.eottabom.rewrite.jackson;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.openrewrite.ExecutionContext;
import org.openrewrite.Preconditions;
import org.openrewrite.Recipe;
import org.openrewrite.Tree;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.search.UsesType;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JRightPadded;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.NameTree;
import org.openrewrite.java.tree.Space;
import org.openrewrite.java.tree.TypeTree;
import org.openrewrite.java.tree.TypeUtils;
import org.openrewrite.marker.Markers;

/**
 * upstream Jackson 3 전환이 {@code catch (IOException e)} 를
 * {@code catch (JacksonException e)} 로 바꿀 때 같은 try 안에 {@code Files.readString()} 처럼
 * IOException 을 던지는 호출이 있어도 바꿔서 컴파일이 깨지는 것을 보정한다. (unreported exception IOException; must
 * be caught or declared to be thrown)
 * <p>
 * try 본문에 IOException 을 던지는 호출이 있고, 다른 catch 가 IOException 을 받지 않으면
 * {@code catch (JacksonException | IOException e)} 로 바꾼다.
 * <p>
 * 반대로 {@code catch (JacksonException | IOException e)} 인데 try 본문이 IOException 을 던지지 않으면
 * IOException 을 뺀다. (exception IOException is never thrown in body of corresponding try
 * statement) Jackson 3 는 IOException 을 던지지 않아서, upstream 이 multi-catch 에 남긴 IOException 이
 * 컴파일 에러가 된다.
 */
public class FixJacksonIOExceptionCatch extends Recipe {

	private static final String JACKSON_EXCEPTION = "tools.jackson.core.JacksonException";

	private static final String IO_EXCEPTION = "java.io.IOException";

	@Override
	public String getDisplayName() {
		return "Jackson 3 catch 절의 IOException 정리";
	}

	@Override
	public String getDescription() {
		return "try 본문이 IOException 을 던지면 catch (JacksonException | IOException e) 로 보완하고, 던지지 않으면 multi-catch 에서 IOException 을 뺀다.";
	}

	@Override
	public TreeVisitor<?, ExecutionContext> getVisitor() {
		return Preconditions.check(new UsesType<>(JACKSON_EXCEPTION, false), new JavaIsoVisitor<ExecutionContext>() {
			@Override
			public J.Try visitTry(J.Try tryable, ExecutionContext ctx) {
				J.Try t = super.visitTry(tryable, ctx);
				boolean bodyThrowsIO = throwsIOException(t.getBody());
				if (!bodyThrowsIO) {
					return removeIOExceptionFromJacksonMultiCatch(t);
				}
				if (catchesIOException(t)) {
					return t;
				}
				List<J.Try.Catch> catches = new ArrayList<>(t.getCatches());
				for (int i = 0; i < catches.size(); i++) {
					J.Try.Catch c = catches.get(i);
					J.VariableDeclarations param = c.getParameter().getTree();
					if (param.getTypeExpression() instanceof J.MultiCatch
							|| !TypeUtils.isOfClassType(param.getType(), JACKSON_EXCEPTION)) {
						continue;
					}
					J.Identifier io = new J.Identifier(Tree.randomId(), Space.SINGLE_SPACE, Markers.EMPTY,
							new ArrayList<>(), "IOException", JavaType.ShallowClass.build(IO_EXCEPTION), null);
					List<JRightPadded<NameTree>> alternatives = new ArrayList<>();
					alternatives
						.add(JRightPadded.<NameTree>build((NameTree) param.getTypeExpression().withPrefix(Space.EMPTY))
							.withAfter(Space.SINGLE_SPACE));
					alternatives.add(JRightPadded.<NameTree>build(io));
					J.MultiCatch multi = new J.MultiCatch(Tree.randomId(), param.getTypeExpression().getPrefix(),
							Markers.EMPTY, alternatives);
					catches.set(i, c.withParameter(c.getParameter().withTree(param.withTypeExpression(multi))));
					maybeAddImport(IO_EXCEPTION);
					return t.withCatches(catches);
				}
				return t;
			}

			/** catch (JacksonException | IOException e) → catch (JacksonException e) */
			private J.Try removeIOExceptionFromJacksonMultiCatch(J.Try t) {
				List<J.Try.Catch> catches = new ArrayList<>(t.getCatches());
				boolean changed = false;
				for (int i = 0; i < catches.size(); i++) {
					J.Try.Catch c = catches.get(i);
					J.VariableDeclarations param = c.getParameter().getTree();
					if (!(param.getTypeExpression() instanceof J.MultiCatch)) {
						continue;
					}
					J.MultiCatch multi = (J.MultiCatch) param.getTypeExpression();
					boolean hasJackson = multi.getAlternatives()
						.stream()
						.anyMatch((a) -> TypeUtils.isOfClassType(a.getType(), JACKSON_EXCEPTION));
					List<NameTree> rest = new ArrayList<>();
					for (NameTree a : multi.getAlternatives()) {
						if (!TypeUtils.isOfClassType(a.getType(), IO_EXCEPTION)) {
							rest.add(a);
						}
					}
					if (!hasJackson || rest.size() == multi.getAlternatives().size()) {
						continue;
					}
					J.VariableDeclarations updated;
					if (rest.size() == 1) {
						updated = param.withTypeExpression((TypeTree) rest.get(0).withPrefix(multi.getPrefix()));
					}
					else {
						List<JRightPadded<NameTree>> padded = new ArrayList<>();
						for (int k = 0; k < rest.size(); k++) {
							JRightPadded<NameTree> p = JRightPadded.build((k == 0) ? rest.get(k).withPrefix(Space.EMPTY)
									: rest.get(k).withPrefix(Space.SINGLE_SPACE));
							padded.add((k < rest.size() - 1) ? p.withAfter(Space.SINGLE_SPACE) : p);
						}
						updated = param.withTypeExpression(multi.getPadding().withAlternatives(padded));
					}
					catches.set(i, c.withParameter(c.getParameter().withTree(updated)));
					changed = true;
				}
				if (changed) {
					maybeRemoveImport(IO_EXCEPTION);
					return t.withCatches(catches);
				}
				return t;
			}

			private boolean catchesIOException(J.Try t) {
				for (J.Try.Catch c : t.getCatches()) {
					JavaType type = c.getParameter().getTree().getType();
					List<JavaType> types = new ArrayList<>();
					if (type instanceof JavaType.MultiCatch) {
						types.addAll(((JavaType.MultiCatch) type).getThrowableTypes());
					}
					else {
						types.add(type);
					}
					for (JavaType ct : types) {
						// IOException 이나 그 상위(Exception, Throwable)를 이미 받고 있으면 그대로 둔다
						if (TypeUtils.isAssignableTo(ct, JavaType.ShallowClass.build(IO_EXCEPTION))
								|| TypeUtils.isOfClassType(ct, IO_EXCEPTION)) {
							return true;
						}
					}
				}
				return false;
			}

			private boolean throwsIOException(J.Block body) {
				AtomicBoolean found = new AtomicBoolean();
				new JavaIsoVisitor<AtomicBoolean>() {
					@Override
					public J.MethodInvocation visitMethodInvocation(J.MethodInvocation method, AtomicBoolean f) {
						check(method.getMethodType(), f);
						return super.visitMethodInvocation(method, f);
					}

					@Override
					public J.NewClass visitNewClass(J.NewClass newClass, AtomicBoolean f) {
						check(newClass.getConstructorType(), f);
						return super.visitNewClass(newClass, f);
					}

					@Override
					public J.Lambda visitLambda(J.Lambda lambda, AtomicBoolean f) {
						return lambda; // 람다 안의 예외는 바깥 try 와 무관
					}

					private void check(JavaType.Method m, AtomicBoolean f) {
						// Jackson 3 메서드는 checked 예외를 던지지 않는다. upstream 은 타입 이름만
						// tools.jackson 으로 바꾸고
						// Jackson 2 로 파싱된 throws(JsonProcessingException extends
						// IOException) 정보는 그대로 남기므로 무시한다
						if (m != null && m.getDeclaringType().getFullyQualifiedName().startsWith("tools.jackson.")) {
							return;
						}
						if (m != null && m.getThrownExceptions()
							.stream()
							.anyMatch((e) -> TypeUtils.isAssignableTo(IO_EXCEPTION, e))) {
							f.set(true);
						}
					}
				}.visit(body, found);
				return found.get();
			}
		});
	}

}
