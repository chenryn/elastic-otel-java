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

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PurlGeneratorTest {

  private final PurlGenerator generator = new PurlGenerator();

  @Test
  void testGenerateMavenPurl() {
    DependencyInfo dependency =
        DependencyInfo.builder()
            .groupId("com.fasterxml.jackson.core")
            .artifactId("jackson-databind")
            .version("2.13.4")
            .type("jar")
            .build();

    String purl = generator.generatePurl(dependency);

    assertThat(purl).isEqualTo("pkg:maven/com.fasterxml.jackson.core/jackson-databind@2.13.4");
  }

  @Test
  void testGenerateMavenPurlWithClassifier() {
    DependencyInfo dependency =
        DependencyInfo.builder()
            .groupId("org.springframework")
            .artifactId("spring-core")
            .version("5.3.21")
            .classifier("sources")
            .build();

    String purl = generator.generatePurl(dependency);

    assertThat(purl)
        .isEqualTo("pkg:maven/org.springframework/spring-core@5.3.21?classifier=sources");
  }

  @Test
  void testGenerateGenericPurl() {
    DependencyInfo dependency =
        DependencyInfo.builder().artifactId("my-custom-lib").version("1.0.0").type("jar").build();

    String purl = generator.generatePurl(dependency);

    assertThat(purl).isEqualTo("pkg:generic/my-custom-lib@1.0.0");
  }

  @Test
  void testGeneratePurlWithUnknownGroup() {
    DependencyInfo dependency =
        DependencyInfo.builder()
            .groupId("unknown")
            .artifactId("some-lib")
            .version("1.0")
            .type("jar")
            .build();

    String purl = generator.generatePurl(dependency);

    assertThat(purl).isEqualTo("pkg:generic/some-lib@1.0");
  }

  @Test
  void testGeneratePurlWithoutVersion() {
    DependencyInfo dependency =
        DependencyInfo.builder().groupId("com.example").artifactId("example-lib").build();

    String purl = generator.generatePurl(dependency);

    assertThat(purl).isEqualTo("pkg:maven/com.example/example-lib");
  }

  @Test
  void testIsValidPurl() {
    assertThat(generator.isValidPurl("pkg:maven/com.example/example@1.0")).isTrue();
    assertThat(generator.isValidPurl("pkg:generic/example@1.0")).isTrue();
    assertThat(generator.isValidPurl("invalid-purl")).isFalse();
    assertThat(generator.isValidPurl(null)).isFalse();
    assertThat(generator.isValidPurl("")).isFalse();
  }

  @Test
  void testParsePurl() {
    String[] parts = generator.parsePurl("pkg:maven/com.example/example@1.0");

    assertThat(parts).hasSize(5);
    assertThat(parts[0]).isEqualTo("pkg");
    assertThat(parts[1]).isEqualTo("maven");
    assertThat(parts[2]).isEqualTo("com.example");
    assertThat(parts[3]).isEqualTo("example");
    assertThat(parts[4]).isEqualTo("1.0");
  }

  @Test
  void testEncodeComponent() {
    String encoded = generator.encodeComponent("test with spaces");
    assertThat(encoded).isEqualTo("test%20with%20spaces");
  }
}
