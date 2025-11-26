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
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Scans the classpath to discover JAR files and extract dependency information. This class provides
 * various strategies to scan different types of class loaders.
 */
public class ClasspathDependencyScanner {

  private static final Logger logger = Logger.getLogger(ClasspathDependencyScanner.class.getName());

  private final JarMetadataExtractor metadataExtractor;
  private final DependencyCache cache;

  public ClasspathDependencyScanner() {
    this.metadataExtractor = new JarMetadataExtractor();
    this.cache = new DependencyCache();
  }

  public ClasspathDependencyScanner(DependencyCache cache) {
    this.metadataExtractor = new JarMetadataExtractor();
    this.cache = cache;
  }

  /**
   * Scans all discoverable class loaders for dependencies. This is the main entry point for
   * comprehensive dependency discovery.
   */
  public Set<DependencyInfo> scanAllClassLoaders() {
    Set<DependencyInfo> dependencies = new HashSet<>();

    try {
      // Scan system class loader
      dependencies.addAll(scanFromSystemClassLoader());

      // Scan context class loader
      dependencies.addAll(scanFromContextClassLoader());

      // Scan thread context class loaders
      dependencies.addAll(scanFromAllThreads());

      logger.log(Level.INFO, "Discovered {0} dependencies", dependencies.size());

    } catch (Exception e) {
      logger.log(Level.WARNING, "Error scanning class loaders for dependencies", e);
    }

    return dependencies;
  }

  /** Scans the system class loader for dependencies. */
  public Set<DependencyInfo> scanFromSystemClassLoader() {
    ClassLoader systemClassLoader = ClassLoader.getSystemClassLoader();
    if (systemClassLoader instanceof URLClassLoader) {
      return scanUrlClassLoader((URLClassLoader) systemClassLoader, "system");
    }
    return Collections.emptySet();
  }

  /** Scans the current thread's context class loader for dependencies. */
  public Set<DependencyInfo> scanFromContextClassLoader() {
    ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
    if (contextClassLoader instanceof URLClassLoader) {
      return scanUrlClassLoader((URLClassLoader) contextClassLoader, "context");
    }
    return Collections.emptySet();
  }

  /** Scans class loaders from all threads. */
  private Set<DependencyInfo> scanFromAllThreads() {
    Set<DependencyInfo> dependencies = new HashSet<>();

    try {
      // Get all thread groups
      ThreadGroup rootGroup = Thread.currentThread().getThreadGroup();
      while (rootGroup.getParent() != null) {
        rootGroup = rootGroup.getParent();
      }

      Thread[] threads = new Thread[rootGroup.activeCount() * 2];
      int count = rootGroup.enumerate(threads, true);

      for (int i = 0; i < count; i++) {
        Thread thread = threads[i];
        if (thread != null) {
          ClassLoader classLoader = thread.getContextClassLoader();
          if (classLoader instanceof URLClassLoader
              && classLoader != Thread.currentThread().getContextClassLoader()) {
            dependencies.addAll(
                scanUrlClassLoader((URLClassLoader) classLoader, "thread-" + thread.getName()));
          }
        }
      }
    } catch (Exception e) {
      logger.log(Level.WARNING, "Error scanning thread class loaders", e);
    }

    return dependencies;
  }

  /** Scans a URLClassLoader for JAR files. */
  private Set<DependencyInfo> scanUrlClassLoader(URLClassLoader classLoader, String source) {
    Set<DependencyInfo> dependencies = new HashSet<>();

    try {
      URL[] urls = classLoader.getURLs();
      logger.log(
          Level.FINE,
          "Scanning {0} URLs from {1} class loader",
          new Object[] {urls.length, source});

      for (URL url : urls) {
        if (url.getProtocol().equals("file")) {
          File file = new File(url.getPath());
          if (file.isFile() && file.getName().endsWith(".jar")) {
            try {
              DependencyInfo dependency = metadataExtractor.extractFromJar(file);
              if (dependency != null) {
                dependencies.add(dependency);
                logger.log(Level.FINE, "Discovered dependency: {0}", dependency);
              }
            } catch (Exception e) {
              logger.log(Level.WARNING, "Error extracting metadata from " + file, e);
            }
          }
        } else if (url.getProtocol().equals("jar")) {
          dependencies.addAll(scanJarUrl(url, source));
        }
      }
    } catch (Exception e) {
      logger.log(Level.WARNING, "Error scanning URL class loader from " + source, e);
    }

    return dependencies;
  }

  /** Scans a specific class loader and its parents recursively. */
  public Set<DependencyInfo> scanClassLoader(ClassLoader classLoader) {
    Set<DependencyInfo> dependencies = new HashSet<>();

    if (classLoader instanceof URLClassLoader) {
      dependencies.addAll(scanUrlClassLoader((URLClassLoader) classLoader, "specific"));
    }

    // Scan parent class loader
    ClassLoader parent = classLoader.getParent();
    if (parent != null) {
      dependencies.addAll(scanClassLoader(parent));
    }
    return dependencies;
  }

  /**
   * Scans a JAR URL (e.g., jar:file:/path/to/fat.jar!/BOOT-INF/lib/nested.jar) for dependencies.
   * This method handles nested JARs within a fat JAR.
   */
  private Set<DependencyInfo> scanJarUrl(URL jarUrl, String source) {
    Set<DependencyInfo> dependencies = new HashSet<>();
    try {
      String fullUrlString = jarUrl.toString();

      String pathWithoutScheme;
      if (fullUrlString.startsWith("jar:nested:file:")) {
        pathWithoutScheme = fullUrlString.substring("jar:nested:file:".length());
      } else if (fullUrlString.startsWith("jar:nested:")) {
        pathWithoutScheme = fullUrlString.substring("jar:nested:".length());
      } else if (fullUrlString.startsWith("jar:file:")) {
        pathWithoutScheme = fullUrlString.substring("jar:file:".length());
      } else {
        logger.log(Level.WARNING, "Unsupported JAR URL scheme: {0}", fullUrlString);
        return dependencies;
      }
      // Find the first '!/' which separates the outer JAR from its contents
      // Spring Boot nested JAR uses /! as separator, traditional JAR uses !/
      int outerJarSeparatorIndex = pathWithoutScheme.indexOf("/!");
      if (outerJarSeparatorIndex == -1) {
        // Fallback to traditional jar:file: format
        outerJarSeparatorIndex = pathWithoutScheme.indexOf("!/");
      }
      if (outerJarSeparatorIndex == -1) {
        logger.log(
            Level.WARNING, "Invalid JAR URL format (missing first '!/'): {0}", fullUrlString);
        return dependencies;
      }

      String outerJarFilePath = pathWithoutScheme.substring(0, outerJarSeparatorIndex);

      // The nested path starts after the separator
      String nestedPath;
      if (pathWithoutScheme.indexOf("/!") != -1) {
        // Spring Boot format: /!
        nestedPath = pathWithoutScheme.substring(outerJarSeparatorIndex + 2);
      } else {
        // Traditional format: !/
        nestedPath = pathWithoutScheme.substring(outerJarSeparatorIndex + 2);
      }

      // Remove trailing !/ if present
      if (nestedPath.endsWith("!/")) {
        nestedPath = nestedPath.substring(0, nestedPath.length() - 2);
      }
      File outerJarFile = new File(outerJarFilePath);
      if (!outerJarFile.isFile()) {
        logger.log(Level.WARNING, "Outer JAR file not found: {0}", outerJarFilePath);
        return dependencies;
      }

      try (JarFile jarFile = new JarFile(outerJarFile)) {
        Enumeration<JarEntry> entries = jarFile.entries();
        while (entries.hasMoreElements()) {
          JarEntry entry = entries.nextElement();
          if (!entry.isDirectory() && entry.getName().endsWith(".jar")) {
            // Check if this entry matches the nestedPath
            if (entry.getName().equals(nestedPath)) {
              try (InputStream is = jarFile.getInputStream(entry)) {
                // Create a temporary file for the nested JAR
                File tempFile = File.createTempFile("nested-jar-", ".jar");
                tempFile.deleteOnExit(); // Ensure the temporary file is deleted on exit
                Files.copy(is, tempFile.toPath(), StandardCopyOption.REPLACE_EXISTING);

                DependencyInfo dependency = metadataExtractor.extractFromJar(tempFile);
                if (dependency != null) {
                  dependencies.add(dependency);
                  logger.log(Level.FINE, "Discovered nested dependency: {0}", dependency);
                }
              } catch (Exception e) {
                logger.log(
                    Level.WARNING,
                    "Error extracting metadata from nested JAR " + entry.getName(),
                    e);
              }
            }
          }
        }
      }
    } catch (IOException e) { // Catch both IOException and URISyntaxException
      logger.log(Level.WARNING, "Error scanning JAR URL from " + source + ": " + jarUrl, e);
    }
    return dependencies;
  }
}
