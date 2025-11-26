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

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

/** Simple in-memory cache for dependency information to avoid redundant processing. */
public class DependencyCache {

  private static final int MAX_CACHE_SIZE = 1000;
  private static final long CACHE_EXPIRY_HOURS = 1;

  private final ConcurrentMap<String, CacheEntry<DependencyInfo>> cache = new ConcurrentHashMap<>();

  /**
   * Gets cached dependency information by file path.
   *
   * @param filePath the file path to look up
   * @return the cached dependency info, or null if not found or expired
   */
  public DependencyInfo get(String filePath) {
    if (filePath == null) {
      return null;
    }

    CacheEntry<DependencyInfo> entry = cache.get(filePath);
    if (entry == null || entry.isExpired()) {
      cache.remove(filePath);
      return null;
    }

    return entry.getValue();
  }

  /**
   * Puts dependency information into the cache.
   *
   * @param filePath the file path as cache key
   * @param dependency the dependency information to cache
   */
  public void put(String filePath, DependencyInfo dependency) {
    if (filePath == null || dependency == null) {
      return;
    }

    // Simple eviction strategy: remove oldest entries if cache is too large
    if (cache.size() >= MAX_CACHE_SIZE) {
      // Remove 10% of entries to make room
      int toRemove = MAX_CACHE_SIZE / 10;
      cache.entrySet().stream()
          .sorted(
              (e1, e2) -> Long.compare(e1.getValue().getTimestamp(), e2.getValue().getTimestamp()))
          .limit(toRemove)
          .forEach(entry -> cache.remove(entry.getKey()));
    }

    cache.put(filePath, new CacheEntry<>(dependency));
  }

  /** Clears the cache. */
  public void clear() {
    cache.clear();
  }

  /**
   * Gets the current cache size.
   *
   * @return number of entries in cache
   */
  public int size() {
    return cache.size();
  }

  private static class CacheEntry<T> {
    private final T value;
    private final long timestamp;
    private final long expiryTime;

    CacheEntry(T value) {
      this.value = value;
      this.timestamp = System.currentTimeMillis();
      this.expiryTime = this.timestamp + TimeUnit.HOURS.toMillis(CACHE_EXPIRY_HOURS);
    }

    T getValue() {
      return value;
    }

    long getTimestamp() {
      return timestamp;
    }

    boolean isExpired() {
      return System.currentTimeMillis() > expiryTime;
    }
  }
}
