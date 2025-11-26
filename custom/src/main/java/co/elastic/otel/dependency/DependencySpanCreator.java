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

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Creates OpenTelemetry spans for discovered dependencies. Each dependency gets its own span with
 * relevant metadata attributes.
 */
public class DependencySpanCreator {

  private static final Logger logger = Logger.getLogger(DependencySpanCreator.class.getName());

  private final Tracer tracer;
  private final PurlGenerator purlGenerator;

  // Attribute keys for dependency information
  public static final AttributeKey<String> DEPENDENCY_PURL =
      AttributeKey.stringKey("dependency.purl");
  public static final AttributeKey<String> DEPENDENCY_NAME =
      AttributeKey.stringKey("dependency.name");
  public static final AttributeKey<String> DEPENDENCY_VERSION =
      AttributeKey.stringKey("dependency.version");
  public static final AttributeKey<String> DEPENDENCY_TYPE =
      AttributeKey.stringKey("dependency.type");
  public static final AttributeKey<String> DEPENDENCY_CLASSIFIER =
      AttributeKey.stringKey("dependency.classifier");
  public static final AttributeKey<String> DEPENDENCY_FILE_PATH =
      AttributeKey.stringKey("dependency.file.path");
  public static final AttributeKey<Long> DEPENDENCY_FILE_SIZE =
      AttributeKey.longKey("dependency.file.size");
  public static final AttributeKey<String> DEPENDENCY_CHECKSUM =
      AttributeKey.stringKey("dependency.checksum");
  public static final AttributeKey<String> DEPENDENCY_GROUP_ID =
      AttributeKey.stringKey("code.namespace");
  public static final AttributeKey<String> DEPENDENCY_ARTIFACT_ID =
      AttributeKey.stringKey("code.function");
  public static final AttributeKey<String> DEPENDENCY_SCOPE =
      AttributeKey.stringKey("dependency.scope");
  public static final AttributeKey<Boolean> DEPENDENCY_DIRECT =
      AttributeKey.booleanKey("dependency.direct");
  public static final AttributeKey<String> DEPENDENCY_PARENT =
      AttributeKey.stringKey("dependency.parent");

  public DependencySpanCreator(Tracer tracer, PurlGenerator purlGenerator) {
    this.tracer = tracer;
    this.purlGenerator = purlGenerator;
  }

  /**
   * Creates a span for a discovered dependency.
   *
   * @param dependency the dependency information
   */
  public void createDependencySpan(DependencyInfo dependency) {
    if (dependency == null) {
      logger.log(Level.WARNING, "Cannot create span for null dependency");
      return;
    }

    try {
      String purl = purlGenerator.generatePurl(dependency);
      if (purl == null) {
        logger.log(Level.WARNING, "Failed to generate PURL for dependency: {0}", dependency);
        return;
      }

      String spanName = generateSpanName(dependency);

      // Use the dependency scan context to ensure proper parent-child relationship
      Span span;
      if (DependencyScanContext.isScanInProgress()) {
        span = DependencyScanContext.createChildSpan(tracer, spanName);
      } else {
        span = tracer.spanBuilder(spanName).setSpanKind(SpanKind.INTERNAL).startSpan();
      }

      try {
        span.setAllAttributes(buildAttributes(dependency, purl));
        logger.log(
            Level.FINE,
            "Created dependency span: {0} with PURL: {1}",
            new Object[] {spanName, purl});
      } finally {
        span.end();
      }

    } catch (Exception e) {
      logger.log(Level.WARNING, "Error creating dependency span for: " + dependency, e);
    }
  }

  /**
   * Creates spans for multiple dependencies.
   *
   * @param dependencies set of discovered dependencies
   */
  public void createDependencySpans(java.util.Set<DependencyInfo> dependencies) {
    if (dependencies == null || dependencies.isEmpty()) {
      logger.log(Level.FINE, "No dependencies to create spans for");
      return;
    }

    logger.log(Level.INFO, "Creating spans for {0} dependencies", dependencies.size());

    for (DependencyInfo dependency : dependencies) {
      createDependencySpan(dependency);
    }
  }

  /**
   * Builds the attributes for the dependency span.
   *
   * @param dependency the dependency information
   * @param purl the generated PURL
   * @return attributes for the span
   */
  private Attributes buildAttributes(DependencyInfo dependency, String purl) {
    io.opentelemetry.api.common.AttributesBuilder attributes =
        io.opentelemetry.api.common.Attributes.builder();

    // Required attributes
    attributes.put(DEPENDENCY_PURL, purl);
    attributes.put(DEPENDENCY_NAME, dependency.getArtifactId());
    attributes.put(DEPENDENCY_TYPE, dependency.getType());
    attributes.put(
        DEPENDENCY_VERSION,
        dependency.getVersion() != null && !dependency.getVersion().isEmpty()
            ? dependency.getVersion()
            : "unknown");
    attributes.put(
        DEPENDENCY_GROUP_ID,
        dependency.getGroupId() != null && !dependency.getGroupId().isEmpty()
            ? dependency.getGroupId()
            : "unknown");
    attributes.put(
        DEPENDENCY_ARTIFACT_ID,
        dependency.getArtifactId() != null && !dependency.getArtifactId().isEmpty()
            ? dependency.getArtifactId()
            : "unknown");
    attributes.put(DEPENDENCY_SCOPE, dependency.getScope());
    attributes.put(DEPENDENCY_DIRECT, dependency.isDirectDependency());

    // Optional attributes
    if (dependency.getClassifier() != null && !dependency.getClassifier().isEmpty()) {
      attributes.put(DEPENDENCY_CLASSIFIER, dependency.getClassifier());
    }

    if (dependency.getFilePath() != null && !dependency.getFilePath().isEmpty()) {
      attributes.put(DEPENDENCY_FILE_PATH, dependency.getFilePath());
    }

    if (dependency.getFileSize() > 0) {
      attributes.put(DEPENDENCY_FILE_SIZE, dependency.getFileSize());
    }

    if (dependency.getChecksum() != null && !dependency.getChecksum().isEmpty()) {
      attributes.put(DEPENDENCY_CHECKSUM, dependency.getChecksum());
    }

    if (dependency.getParentDependency() != null) {
      attributes.put(DEPENDENCY_PARENT, dependency.getParentDependency());
    }

    return attributes.build();
  }

  /**
   * Generates a span name for the dependency.
   *
   * @param dependency the dependency information
   * @return the span name
   */
  private String generateSpanName(DependencyInfo dependency) {
    String name = dependency.getArtifactId();
    if (name == null || name.trim().isEmpty()) {
      name = "unknown-dependency";
    }

    // Sanitize the name to be valid for span names
    name = sanitizeSpanName(name);

    // Use groupId if available for better identification
    String groupPrefix = "";
    if (dependency.getGroupId() != null
        && !"unknown".equals(dependency.getGroupId())
        && !dependency.getGroupId().trim().isEmpty()) {
      groupPrefix = sanitizeSpanName(dependency.getGroupId()) + ".";
    }

    return "dependency." + groupPrefix + name;
  }

  private String sanitizeSpanName(String name) {
    if (name == null || name.trim().isEmpty()) {
      return "unknown";
    }

    // Replace special characters with hyphens
    String sanitized =
        name.replaceAll("[^a-zA-Z0-9\\-_\\.]", "-")
            .replaceAll("-{2,}", "-")
            .replaceAll("^-|-$", "");

    // Ensure it starts with a letter or number
    if (sanitized.isEmpty() || !Character.isLetterOrDigit(sanitized.charAt(0))) {
      sanitized = "dep-" + sanitized;
    }

    return sanitized.toLowerCase();
  }

  /**
   * Gets the tracer instance used by this creator.
   *
   * @return the tracer
   */
  public Tracer getTracer() {
    return tracer;
  }

  /**
   * Gets the PURL generator used by this creator.
   *
   * @return the PURL generator
   */
  public PurlGenerator getPurlGenerator() {
    return purlGenerator;
  }
}
