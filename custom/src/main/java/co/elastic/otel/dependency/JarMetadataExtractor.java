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
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Extracts metadata from JAR files including Maven coordinates, manifest information, and file
 * properties.
 */
public class JarMetadataExtractor {

  private static final Logger logger = Logger.getLogger(JarMetadataExtractor.class.getName());

  /**
   * Extracts dependency information from a JAR file.
   *
   * @param jarFile the JAR file to extract metadata from
   * @return DependencyInfo object or null if extraction fails
   */
  public DependencyInfo extractFromJar(File jarFile) {
    if (jarFile == null || !jarFile.exists() || !jarFile.isFile()) {
      return null;
    }

    DependencyInfo.Builder builder =
        DependencyInfo.builder()
            .filePath(jarFile.getAbsolutePath())
            .fileSize(jarFile.length())
            .type("jar");

    try (JarFile jar = new JarFile(jarFile)) {

      // Extract manifest information
      extractManifestInfo(jar, builder);

      // Extract Maven coordinates from pom.properties
      extractMavenCoordinates(jar, builder);

      // Fallback to filename parsing if no Maven coordinates found
      if (!builder.build().hasMavenCoordinates()) {
        extractFromFilename(jarFile, builder);
      }

      // Infer dependency scope and relationship from file path
      inferDependencyScope(jarFile, builder);

      DependencyInfo dependency = builder.build();
      logger.log(
          Level.FINE,
          "Extracted metadata from {0}: {1}",
          new Object[] {jarFile.getName(), dependency});

      return dependency;

    } catch (IOException e) {
      logger.log(Level.WARNING, "Error reading JAR file: " + jarFile.getAbsolutePath(), e);
      return null;
    }
  }

  /** Infers dependency scope and relationship from file path patterns. */
  private void inferDependencyScope(File jarFile, DependencyInfo.Builder builder) {
    String path = jarFile.getAbsolutePath().toLowerCase();

    // Infer scope from path patterns
    if (path.contains("/test/") || path.contains("/test-") || path.contains("/test_")) {
      builder.scope("test");
    } else if (path.contains("/runtime/") || path.contains("/runtime-")) {
      builder.scope("runtime");
    } else if (path.contains("/provided/") || path.contains("/provided-")) {
      builder.scope("provided");
    } else {
      builder.scope("compile");
    }

    // Infer if it's a direct dependency based on path structure
    // This is a heuristic - direct dependencies are more likely to be in the main classpath
    boolean likelyDirect = !path.contains("/transitive/") && !path.contains("/nested/");
    builder.isDirectDependency(likelyDirect);
  }

  /** Extracts information from MANIFEST.MF file. */
  private void extractManifestInfo(JarFile jar, DependencyInfo.Builder builder) {
    try {
      Manifest manifest = jar.getManifest();
      if (manifest != null) {
        Attributes attributes = manifest.getMainAttributes();

        String title = attributes.getValue(Attributes.Name.IMPLEMENTATION_TITLE);
        String version = attributes.getValue(Attributes.Name.IMPLEMENTATION_VERSION);
        String vendor = attributes.getValue(Attributes.Name.IMPLEMENTATION_VENDOR);

        if (title != null && builder.build().getArtifactId() == null) {
          builder.artifactId(title);
        }
        if (version != null && builder.build().getVersion() == null) {
          builder.version(version);
        }

        // Store manifest info
        StringBuilder manifestInfo = new StringBuilder();
        manifestInfo.append("Implementation-Title: ").append(title).append("\n");
        manifestInfo.append("Implementation-Version: ").append(version).append("\n");
        manifestInfo.append("Implementation-Vendor: ").append(vendor).append("\n");
        builder.manifestInfo(manifestInfo.toString().trim());
      }
    } catch (Exception e) {
      logger.log(Level.FINE, "Error reading manifest", e);
    }
  }

  /** Extracts Maven coordinates from pom.properties file. */
  private void extractMavenCoordinates(JarFile jar, DependencyInfo.Builder builder) {
    try {
      JarEntry pomEntry = jar.getJarEntry("META-INF/maven/pom.properties");
      if (pomEntry == null) {
        // Look for any pom.properties in META-INF/maven/*/*/pom.properties
        java.util.Enumeration<JarEntry> entries = jar.entries();
        while (entries.hasMoreElements()) {
          JarEntry entry = entries.nextElement();
          if (entry.getName().matches("META-INF/maven/.+/.+/pom.properties")) {
            pomEntry = entry;
            break;
          }
        }
      }

      if (pomEntry != null) {
        try (InputStream is = jar.getInputStream(pomEntry)) {
          Properties props = new Properties();
          props.load(is);

          String groupId = props.getProperty("groupId");
          String artifactId = props.getProperty("artifactId");
          String version = props.getProperty("version");

          if (groupId != null && !groupId.trim().isEmpty()) {
            builder.groupId(groupId.trim());
          }
          if (artifactId != null && !artifactId.trim().isEmpty()) {
            builder.artifactId(artifactId.trim());
          }
          if (version != null && !version.trim().isEmpty()) {
            builder.version(version.trim());
          }
        }
      }

      // Try to extract from MANIFEST.MF if still missing
      extractMavenCoordinatesFromManifest(jar, builder);

    } catch (Exception e) {
      logger.log(Level.FINE, "Error reading pom.properties", e);
    }
  }

  /** Extracts Maven coordinates from MANIFEST.MF attributes. */
  private void extractMavenCoordinatesFromManifest(JarFile jar, DependencyInfo.Builder builder) {
    try {
      Manifest manifest = jar.getManifest();
      if (manifest != null) {
        Attributes attributes = manifest.getMainAttributes();

        // Try to extract from Implementation attributes
        String vendorId = attributes.getValue("Implementation-Vendor-Id");
        if (vendorId != null
            && !vendorId.trim().isEmpty()
            && (builder.build().getGroupId() == null
                || "unknown".equals(builder.build().getGroupId()))) {
          builder.groupId(vendorId.trim());
        }

        String title = attributes.getValue("Implementation-Title");
        if (title != null
            && !title.trim().isEmpty()
            && (builder.build().getArtifactId() == null
                || "unknown".equals(builder.build().getArtifactId()))) {
          builder.artifactId(title.trim());
        }

        String version = attributes.getValue("Implementation-Version");
        if (version != null
            && !version.trim().isEmpty()
            && (builder.build().getVersion() == null
                || "unknown".equals(builder.build().getVersion()))) {
          builder.version(version.trim());
        }

        // Try to extract from Specification attributes
        String specVendor = attributes.getValue("Specification-Vendor");
        if (specVendor != null
            && !specVendor.trim().isEmpty()
            && (builder.build().getGroupId() == null
                || "unknown".equals(builder.build().getGroupId()))) {
          builder.groupId(specVendor.trim());
        }

        String specTitle = attributes.getValue("Specification-Title");
        if (specTitle != null
            && !specTitle.trim().isEmpty()
            && (builder.build().getArtifactId() == null
                || "unknown".equals(builder.build().getArtifactId()))) {
          builder.artifactId(specTitle.trim());
        }

        String specVersion = attributes.getValue("Specification-Version");
        if (specVersion != null
            && !specVersion.trim().isEmpty()
            && (builder.build().getVersion() == null
                || "unknown".equals(builder.build().getVersion()))) {
          builder.version(specVersion.trim());
        }
      }
    } catch (Exception e) {
      logger.log(Level.FINE, "Error reading manifest for Maven coordinates", e);
    }
  }

  /** Extracts information from JAR filename as fallback. */
  private void extractFromFilename(File jarFile, DependencyInfo.Builder builder) {
    String filename = jarFile.getName();
    if (filename.endsWith(".jar")) {
      filename = filename.substring(0, filename.length() - 4);
    }

    // Parse filename pattern: artifactId-version[-classifier].jar
    int lastDash = filename.lastIndexOf('-');
    if (lastDash > 0) {
      String artifactId = filename.substring(0, lastDash);
      String versionAndClassifier = filename.substring(lastDash + 1);

      // Handle classifier (e.g., -sources, -javadoc)
      String version = versionAndClassifier;
      String classifier = null;

      // Simple version detection: look for version pattern
      if (versionAndClassifier.matches(".*-.*")) {
        int classifierDash = versionAndClassifier.lastIndexOf('-');
        if (classifierDash > 0) {
          version = versionAndClassifier.substring(0, classifierDash);
          classifier = versionAndClassifier.substring(classifierDash + 1);
        }
      }

      if (builder.build().getArtifactId() == null) {
        builder.artifactId(artifactId);
      }
      if (builder.build().getVersion() == null) {
        builder.version(version);
      }
      if (classifier != null && builder.build().getClassifier() == null) {
        builder.classifier(classifier);
      }
    } else {
      // Fallback: use filename as artifactId
      if (builder.build().getArtifactId() == null) {
        builder.artifactId(filename);
      }
      if (builder.build().getVersion() == null) {
        builder.version("unknown");
      }
    }

    // Set groupId to "unknown" if not found
    if (builder.build().getGroupId() == null) {
      builder.groupId("unknown");
    }
  }

  /**
   * Calculates a simple checksum for the JAR file. Note: This is a basic implementation for MVP. In
   * production, consider using SHA-256.
   */
  public String calculateChecksum(File file) {
    try {
      // Simple checksum based on file size and name for MVP
      return "size-" + file.length() + "-" + file.getName().hashCode();
    } catch (Exception e) {
      logger.log(Level.WARNING, "Error calculating checksum", e);
      return "unknown";
    }
  }
}
