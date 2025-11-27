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
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SpanProcessor;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Span processor that discovers dependencies and creates spans for them. This processor runs
 * dependency discovery asynchronously after the agent starts.
 */
public class DependencyDiscoverySpanProcessor implements SpanProcessor {

  private static final Logger logger =
      Logger.getLogger(DependencyDiscoverySpanProcessor.class.getName());

  private final ClasspathDependencyScanner scanner;
  private DependencySpanCreator spanCreator;
  private final ExecutorService executorService;
  private final AtomicBoolean discoveryStarted = new AtomicBoolean(false);
  private final long discoveryDelayMillis;
  private final long discoveryIntervalMillis;
  private Tracer tracer;

  public DependencyDiscoverySpanProcessor() {
    this(
        io.opentelemetry.api.GlobalOpenTelemetry.getTracer("elastic-otel-dependency"),
        5000L,
        TimeUnit.HOURS.toMillis(6));
  }

  public DependencyDiscoverySpanProcessor(
      Tracer tracer, long discoveryDelayMillis, long discoveryIntervalMillis) {
    this.tracer = tracer;
    this.scanner = new ClasspathDependencyScanner();
    this.purlGenerator = new PurlGenerator();
    this.spanCreator = new DependencySpanCreator(tracer, purlGenerator);
    this.executorService =
        Executors.newFixedThreadPool(
            Math.min(4, Runtime.getRuntime().availableProcessors()),
            r -> {
              Thread thread = new Thread(r, "dependency-discovery");
              thread.setDaemon(true);
              return thread;
            });
    this.discoveryDelayMillis = discoveryDelayMillis;
    this.discoveryIntervalMillis = discoveryIntervalMillis;
  }

  public DependencyDiscoverySpanProcessor(long discoveryDelayMillis, long discoveryIntervalMillis) {
    this.tracer = null; // Will be lazily initialized
    this.scanner = new ClasspathDependencyScanner();
    this.purlGenerator = new PurlGenerator();
    this.spanCreator = null; // Will be initialized when tracer is available
    this.executorService =
        Executors.newFixedThreadPool(
            Math.min(4, Runtime.getRuntime().availableProcessors()),
            r -> {
              Thread thread = new Thread(r, "dependency-discovery");
              thread.setDaemon(true);
              return thread;
            });
    this.discoveryDelayMillis = discoveryDelayMillis;
    this.discoveryIntervalMillis = discoveryIntervalMillis;
  }

  private final PurlGenerator purlGenerator;

  private synchronized void initializeTracer() {
    if (tracer == null) {
      tracer = io.opentelemetry.api.GlobalOpenTelemetry.getTracer("elastic-otel-dependency");
      this.spanCreator = new DependencySpanCreator(tracer, purlGenerator);
    }
  }

  @Override
  public void onStart(Context parentContext, ReadWriteSpan span) {
    // Initialize tracer lazily on first span
    if (tracer == null) {
      initializeTracer();
    }

    // Trigger dependency discovery on first span if not already started
    if (discoveryStarted.compareAndSet(false, true)) {
      scheduleDiscovery();
    }
  }

  @Override
  public boolean isStartRequired() {
    return true;
  }

  @Override
  public void onEnd(ReadableSpan span) {
    // No action needed on span end
  }

  @Override
  public boolean isEndRequired() {
    return false;
  }

  /** Schedules the dependency discovery to run asynchronously. */
  private void scheduleDiscovery() {
    CompletableFuture.runAsync(
        () -> {
          try {
            // Initial delay
            Thread.sleep(discoveryDelayMillis);

            // Run initial discovery
            performDiscovery();

            // Schedule periodic discovery if interval > 0
            if (discoveryIntervalMillis > 0) {
              while (!Thread.currentThread().isInterrupted()) {
                Thread.sleep(discoveryIntervalMillis);
                performDiscovery();
              }
            }
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.log(Level.FINE, "Dependency discovery thread interrupted");
          } catch (Exception e) {
            logger.log(Level.WARNING, "Error in dependency discovery", e);
          }
        },
        executorService);
  }

  /** Performs the actual dependency discovery and span creation. */
  private void performDiscovery() {
    try {
      // Ensure tracer is initialized before use
      if (tracer == null) {
        initializeTracer();
      }

      Set<DependencyInfo> dependencies = scanner.scanAllClassLoaders();
      logger.log(Level.INFO, "Creating spans for {0} discovered dependencies", dependencies.size());

      // Start root span for dependency scanning
      Span rootSpan = DependencyScanContext.startRootSpan(tracer);
      if (rootSpan != null) {
        try (Scope scope = rootSpan.makeCurrent()) {
          rootSpan.setAttribute("dependency.total.count", dependencies.size());

          long startTime = System.currentTimeMillis();

          // Create spans for each dependency as children of root span
          for (DependencyInfo dependency : dependencies) {
            spanCreator.createDependencySpan(dependency);
          }

          long duration = System.currentTimeMillis() - startTime;
          rootSpan.setAttribute("dependency.scan.duration.ms", duration);

        } finally {
          DependencyScanContext.endRootSpan();
        }
      } else {
        // Fallback to individual spans if root span already exists
        for (DependencyInfo dependency : dependencies) {
          spanCreator.createDependencySpan(dependency);
        }
      }
    } catch (Exception e) {
      logger.log(Level.WARNING, "Error during dependency discovery", e);
    }
  }

  /** Forces immediate dependency discovery. This is mainly for testing purposes. */
  public void forceDiscovery() {
    if (discoveryStarted.get()) {
      CompletableFuture.runAsync(this::performDiscovery, executorService);
    } else {
      discoveryStarted.set(true);
      scheduleDiscovery();
    }
  }

  /** Shuts down the processor and its executor service. */
  public io.opentelemetry.sdk.common.CompletableResultCode shutdown() {
    try {
      executorService.shutdown();
      if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
        executorService.shutdownNow();
      }
      return io.opentelemetry.sdk.common.CompletableResultCode.ofSuccess();
    } catch (InterruptedException e) {
      executorService.shutdownNow();
      Thread.currentThread().interrupt();
      return io.opentelemetry.sdk.common.CompletableResultCode.ofFailure();
    }
  }

  /**
   * Gets the scanner instance.
   *
   * @return the scanner
   */
  public ClasspathDependencyScanner getScanner() {
    return scanner;
  }

  /**
   * Gets the span creator instance.
   *
   * @return the span creator
   */
  public DependencySpanCreator getSpanCreator() {
    return spanCreator;
  }

  /**
   * Checks if discovery has been started.
   *
   * @return true if discovery has started
   */
  public boolean isDiscoveryStarted() {
    return discoveryStarted.get();
  }
}
