/*
 * Licensed to Elasticsearch B.V. under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch B.V. licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package co.elastic.otel.dependency;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Manages the dependency scanning context and span hierarchy. This ensures all dependency spans
 * share the same trace ID and have proper parent-child relationships.
 */
public class DependencyScanContext {

  private static final AtomicBoolean scanInProgress = new AtomicBoolean(false);
  private static volatile Span rootSpan = null;
  private static final Object lock = new Object();

  /**
   * Starts the dependency scanning root span.
   *
   * @param tracer the tracer to use
   * @return the root span, or null if already started
   */
  public static Span startRootSpan(Tracer tracer) {
    if (scanInProgress.compareAndSet(false, true)) {
      synchronized (lock) {
        if (rootSpan == null) {
          rootSpan =
              tracer
                  .spanBuilder("application.dependencies.scan")
                  .setSpanKind(SpanKind.INTERNAL)
                  .setAttribute("dependency.scan.type", "classpath")
                  .setAttribute("dependency.scan.trigger", "auto")
                  .startSpan();
        }
        return rootSpan;
      }
    }
    return rootSpan;
  }

  /**
   * Gets the current root span for dependency scanning.
   *
   * @return the root span, or null if not started
   */
  public static Span getRootSpan() {
    return rootSpan;
  }

  /**
   * Creates a child span under the dependency scanning root span.
   *
   * @param tracer the tracer to use
   * @param spanName the name of the child span
   * @return the child span builder
   */
  public static Span createChildSpan(Tracer tracer, String spanName) {
    Span parent = getRootSpan();
    if (parent != null) {
      return tracer
          .spanBuilder(spanName)
          .setParent(Context.current().with(parent))
          .setSpanKind(SpanKind.INTERNAL)
          .startSpan();
    } else {
      // Fallback if root span not available
      return tracer.spanBuilder(spanName).setSpanKind(SpanKind.INTERNAL).startSpan();
    }
  }

  /** Ends the root span and cleans up the context. */
  public static void endRootSpan() {
    synchronized (lock) {
      if (rootSpan != null) {
        rootSpan.end();
        rootSpan = null;
      }
      scanInProgress.set(false);
    }
  }

  /**
   * Checks if a dependency scan is currently in progress.
   *
   * @return true if scanning is in progress
   */
  public static boolean isScanInProgress() {
    return scanInProgress.get();
  }
}
