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

import java.io.File;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Generates Package URLs (PURLs) according to the PURL specification. Supports various package
 * types including Maven, Gradle, and generic packages.
 *
 * <p>PURL format: scheme:type/namespace/name@version?qualifiers#subpath
 */
public class PurlGenerator {

  private static final Logger logger = Logger.getLogger(PurlGenerator.class.getName());

  /**
   * Generates a PURL from dependency information.
   *
   * @param dependency the dependency information
   * @return the generated PURL string
   */
  public String generatePurl(DependencyInfo dependency) {
    if (dependency == null) {
      return null;
    }

    try {
      String type = determineType(dependency);

      switch (type.toLowerCase()) {
        case "maven":
          return generateMavenPurl(dependency);
        case "gradle":
          return generateGradlePurl(dependency);
        case "generic":
        default:
          return generateGenericPurl(dependency);
      }
    } catch (Exception e) {
      logger.log(Level.WARNING, "Error generating PURL for dependency: " + dependency, e);
      return generateFallbackPurl(dependency);
    }
  }

  /** Determines the package type based on dependency information. */
  private String determineType(DependencyInfo dependency) {
    if (dependency.getGroupId() != null
        && dependency.getArtifactId() != null
        && !"unknown".equals(dependency.getGroupId())
        && !dependency.getGroupId().trim().isEmpty()) {
      return "maven";
    }

    // Check if it's a Gradle dependency (similar to Maven)
    if (dependency.getFilePath() != null && dependency.getFilePath().contains(".gradle")) {
      return "gradle";
    }

    // Check for Maven repository structure in path
    if (dependency.getFilePath() != null) {
      String path = dependency.getFilePath();
      if (path.contains("/maven/") || path.contains("/.m2/") || path.contains("/repository/")) {
        return "maven";
      }
    }

    return "generic";
  }

  /** Generates a Maven PURL. Format: pkg:maven/groupId/artifactId@version */
  private String generateMavenPurl(DependencyInfo dependency) {
    StringBuilder purl = new StringBuilder("pkg:maven/");

    String groupId = dependency.getGroupId();
    String artifactId = dependency.getArtifactId();
    String version = dependency.getVersion();
    String classifier = dependency.getClassifier();

    // Encode components
    purl.append(encodeComponent(groupId != null ? groupId : "unknown"));
    purl.append("/");
    purl.append(encodeComponent(artifactId != null ? artifactId : "unknown"));

    if (version != null && !version.isEmpty() && !"unknown".equals(version)) {
      purl.append("@").append(encodeComponent(version));
    }

    // Add classifier as qualifier
    if (classifier != null && !classifier.isEmpty()) {
      purl.append("?classifier=").append(encodeComponent(classifier));
    }

    return purl.toString();
  }

  /** Generates a Gradle PURL (same as Maven for now). */
  private String generateGradlePurl(DependencyInfo dependency) {
    // Gradle uses the same format as Maven for PURLs
    return generateMavenPurl(dependency);
  }

  /** Generates a generic PURL. Format: pkg:generic/name@version */
  private String generateGenericPurl(DependencyInfo dependency) {
    StringBuilder purl = new StringBuilder("pkg:generic/");

    String name = dependency.getArtifactId();
    String version = dependency.getVersion();

    // Use artifactId as name, fallback to filename
    if (name == null || name.isEmpty()) {
      String filePath = dependency.getFilePath();
      if (filePath != null) {
        name = new File(filePath).getName();
        if (name.endsWith(".jar")) {
          name = name.substring(0, name.length() - 4);
        }
      } else {
        name = "unknown";
      }
    }

    purl.append(encodeComponent(name));

    if (version != null && !version.isEmpty() && !"unknown".equals(version)) {
      purl.append("@").append(encodeComponent(version));
    }

    return purl.toString();
  }

  /** Generates a fallback PURL when normal generation fails. */
  private String generateFallbackPurl(DependencyInfo dependency) {
    String name = dependency.getArtifactId();
    String version = dependency.getVersion();

    if (name == null || name.isEmpty()) {
      name = "unknown-dependency";
    }

    if (version == null || version.isEmpty()) {
      version = "unknown";
    }

    return "pkg:generic/" + encodeComponent(name) + "@" + encodeComponent(version);
  }

  /**
   * URL-encodes a component for use in PURL.
   *
   * @param component the component to encode
   * @return the encoded component
   */
  public String encodeComponent(String component) {
    if (component == null || component.isEmpty()) {
      return "";
    }

    try {
      // PURL encoding rules:
      // - Space -> %20
      // - Special characters: @, :, /, ?, #, [, ], etc.
      return URLEncoder.encode(component, StandardCharsets.UTF_8.toString())
          .replace("+", "%20") // Replace + with %20 for spaces
          .replace("%2F", "/") // Don't encode forward slashes in namespace
          .replace("%3A", ":"); // Don't encode colons in namespace
    } catch (UnsupportedEncodingException e) {
      logger.log(Level.WARNING, "Error encoding component: " + component, e);
      return component.replaceAll("[^a-zA-Z0-9\\-\\._~]", "_");
    }
  }

  /**
   * Validates a PURL string.
   *
   * @param purl the PURL to validate
   * @return true if valid, false otherwise
   */
  public boolean isValidPurl(String purl) {
    if (purl == null || purl.isEmpty()) {
      return false;
    }

    return purl.startsWith("pkg:") && purl.contains("/");
  }

  /**
   * Parses a PURL string to extract components. This is mainly for testing purposes.
   *
   * @param purl the PURL string
   * @return array of [scheme, type, namespace, name, version]
   */
  public String[] parsePurl(String purl) {
    if (!isValidPurl(purl)) {
      return new String[] {"", "", "", "", ""};
    }

    try {
      // Remove "pkg:" prefix
      String withoutScheme = purl.substring(4);

      // Split by type separator
      int typeSeparator = withoutScheme.indexOf('/');
      if (typeSeparator == -1) return new String[] {"", "", "", "", ""};

      String type = withoutScheme.substring(0, typeSeparator);
      String rest = withoutScheme.substring(typeSeparator + 1);

      // Split namespace/name and version
      String namespaceName;
      String version = "";

      int versionSeparator = rest.lastIndexOf('@');
      if (versionSeparator != -1) {
        namespaceName = rest.substring(0, versionSeparator);
        version = rest.substring(versionSeparator + 1);
      } else {
        namespaceName = rest;
      }

      // Split namespace and name
      String namespace = "";
      String name = namespaceName;

      int namespaceSeparator = namespaceName.lastIndexOf('/');
      if (namespaceSeparator != -1) {
        namespace = namespaceName.substring(0, namespaceSeparator);
        name = namespaceName.substring(namespaceSeparator + 1);
      }

      return new String[] {"pkg", type, namespace, name, version};

    } catch (Exception e) {
      logger.log(Level.WARNING, "Error parsing PURL: " + purl, e);
      return new String[] {"", "", "", "", ""};
    }
  }
}
