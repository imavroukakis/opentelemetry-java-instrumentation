/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.javaagent.instrumentation.dropwizardviews;

import static io.opentelemetry.javaagent.tooling.ClassLoaderMatcher.hasClassesNamed;
import static io.opentelemetry.javaagent.tooling.bytebuddy.matcher.AgentElementMatchers.implementsInterface;
import static io.opentelemetry.trace.TracingContextUtils.currentContextWith;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import io.dropwizard.views.View;
import io.opentelemetry.OpenTelemetry;
import io.opentelemetry.instrumentation.api.decorator.BaseDecorator;
import io.opentelemetry.javaagent.instrumentation.api.SpanWithScope;
import io.opentelemetry.javaagent.tooling.Instrumenter;
import io.opentelemetry.trace.Span;
import io.opentelemetry.trace.StatusCanonicalCode;
import io.opentelemetry.trace.Tracer;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public final class DropwizardViewInstrumentation extends Instrumenter.Default {

  public DropwizardViewInstrumentation() {
    super("dropwizard", "dropwizard-view");
  }

  @Override
  public ElementMatcher<ClassLoader> classLoaderMatcher() {
    // Optimization for expensive typeMatcher.
    return hasClassesNamed("io.dropwizard.views.ViewRenderer");
  }

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return implementsInterface(named("io.dropwizard.views.ViewRenderer"));
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {getClass().getName() + "$RenderAdvice"};
  }

  @Override
  public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {
    return singletonMap(
        isMethod()
            .and(named("render"))
            .and(takesArgument(0, named("io.dropwizard.views.View")))
            .and(isPublic()),
        DropwizardViewInstrumentation.class.getName() + "$RenderAdvice");
  }

  public static class RenderAdvice {
    public static final Tracer TRACER =
        OpenTelemetry.getTracer("io.opentelemetry.auto.dropwizard-views-0.7");

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static SpanWithScope onEnter(@Advice.Argument(0) View view) {
      if (!TRACER.getCurrentSpan().getContext().isValid()) {
        return null;
      }
      Span span = TRACER.spanBuilder("Render " + view.getTemplateName()).startSpan();
      return new SpanWithScope(span, currentContextWith(span));
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void stopSpan(
        @Advice.Enter SpanWithScope spanWithScope, @Advice.Thrown Throwable throwable) {
      if (spanWithScope == null) {
        return;
      }
      Span span = spanWithScope.getSpan();
      if (throwable != null) {
        span.setStatus(StatusCanonicalCode.ERROR);
        BaseDecorator.addThrowable(span, throwable);
      }
      span.end();
      spanWithScope.closeScope();
    }
  }
}
