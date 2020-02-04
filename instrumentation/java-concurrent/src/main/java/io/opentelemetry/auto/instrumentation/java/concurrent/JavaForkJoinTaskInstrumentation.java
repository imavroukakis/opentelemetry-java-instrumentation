package io.opentelemetry.auto.instrumentation.java.concurrent;

import static io.opentelemetry.auto.tooling.ByteBuddyElementMatchers.safeHasSuperType;
import static net.bytebuddy.matcher.ElementMatchers.isAbstract;
import static net.bytebuddy.matcher.ElementMatchers.isInterface;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import io.opentelemetry.auto.bootstrap.ContextStore;
import io.opentelemetry.auto.bootstrap.InstrumentationContext;
import io.opentelemetry.auto.bootstrap.instrumentation.java.concurrent.State;
import io.opentelemetry.auto.instrumentation.api.SpanScopePair;
import io.opentelemetry.auto.tooling.Instrumenter;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;
import lombok.extern.slf4j.Slf4j;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

/**
 * Instrument {@link ForkJoinTask}.
 *
 * <p>Note: There are quite a few separate implementations of {@code ForkJoinTask}/{@code
 * ForkJoinPool}: JVM, Akka, Scala, Netty to name a few. This class handles JVM version.
 */
@Slf4j
@AutoService(Instrumenter.class)
public final class JavaForkJoinTaskInstrumentation extends Instrumenter.Default {

  public JavaForkJoinTaskInstrumentation() {
    super(AbstractExecutorInstrumentation.EXEC_NAME);
  }

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return not(isInterface()).and(safeHasSuperType(named(ForkJoinTask.class.getName())));
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      AdviceUtils.class.getName(),
    };
  }

  @Override
  public Map<String, String> contextStore() {
    final Map<String, String> map = new HashMap<>();
    map.put(Runnable.class.getName(), State.class.getName());
    map.put(Callable.class.getName(), State.class.getName());
    map.put(ForkJoinTask.class.getName(), State.class.getName());
    return Collections.unmodifiableMap(map);
  }

  @Override
  public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {
    final Map<ElementMatcher<? super MethodDescription>, String> transformers = new HashMap<>();
    transformers.put(
        named("exec").and(takesArguments(0)).and(not(isAbstract())),
        JavaForkJoinTaskInstrumentation.class.getName() + "$ForkJoinTaskAdvice");
    return transformers;
  }

  public static class ForkJoinTaskAdvice {

    /**
     * When {@link ForkJoinTask} object is submitted to {@link ForkJoinPool} as {@link Runnable} or
     * {@link Callable} it will not get wrapped, instead it will be casted to {@code ForkJoinTask}
     * directly. This means state is still stored in {@code Runnable} or {@code Callable} and we
     * need to use that state.
     */
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static SpanScopePair enter(@Advice.This final ForkJoinTask thiz) {
      final ContextStore<ForkJoinTask, State> contextStore =
          InstrumentationContext.get(ForkJoinTask.class, State.class);
      SpanScopePair scope = AdviceUtils.startTaskScope(contextStore, thiz);
      if (thiz instanceof Runnable) {
        final ContextStore<Runnable, State> runnableContextStore =
            InstrumentationContext.get(Runnable.class, State.class);
        final SpanScopePair newScope =
            AdviceUtils.startTaskScope(runnableContextStore, (Runnable) thiz);
        if (null != newScope) {
          if (null != scope) {
            newScope.getScope().close();
          } else {
            scope = newScope;
          }
        }
      }
      if (thiz instanceof Callable) {
        final ContextStore<Callable, State> callableContextStore =
            InstrumentationContext.get(Callable.class, State.class);
        final SpanScopePair newScope =
            AdviceUtils.startTaskScope(callableContextStore, (Callable) thiz);
        if (null != newScope) {
          if (null != scope) {
            newScope.getScope().close();
          } else {
            scope = newScope;
          }
        }
      }
      return scope;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void exit(@Advice.Enter final SpanScopePair scope) {
      AdviceUtils.endTaskScope(scope);
    }
  }
}