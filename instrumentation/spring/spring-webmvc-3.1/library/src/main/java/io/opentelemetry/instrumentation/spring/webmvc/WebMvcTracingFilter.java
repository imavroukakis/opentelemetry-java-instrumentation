/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.instrumentation.spring.webmvc;

import io.opentelemetry.context.Scope;
import io.opentelemetry.trace.Span;
import io.opentelemetry.trace.Tracer;
import java.io.IOException;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

public class WebMvcTracingFilter extends OncePerRequestFilter implements Ordered {

  private static final String FILTER_CLASS = "WebMVCTracingFilter";
  private static final String FILTER_METHOD = "doFilterInteral";
  private final SpringWebMvcServerTracer tracer;

  public WebMvcTracingFilter(Tracer tracer) {
    this.tracer = new SpringWebMvcServerTracer(tracer);
  }

  @Override
  public void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    Span serverSpan = tracer.startSpan(request, request, FILTER_CLASS + "." + FILTER_METHOD);

    try (Scope ignored = tracer.startScope(serverSpan, request)) {
      filterChain.doFilter(request, response);
      tracer.end(serverSpan, response);
    } catch (Throwable t) {
      tracer.endExceptionally(serverSpan, t, response);
      throw t;
    }
  }

  @Override
  public void destroy() {}

  @Override
  public int getOrder() {
    // Run after all HIGHEST_PRECEDENCE items
    return Ordered.HIGHEST_PRECEDENCE + 1;
  }
}
