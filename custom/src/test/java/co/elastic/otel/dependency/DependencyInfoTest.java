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

class DependencyInfoTest {

  @Test
  void testBuilder() {
    DependencyInfo dependency =
        DependencyInfo.builder()
            .groupId("com.example")
            .artifactId("test-lib")
            .version("1.0.0")
            .type("jar")
            .classifier("sources")
            .filePath("/path/to/test-lib-1.0.0-sources.jar")
            .checksum("abc123")
            .fileSize(1024)
            .manifestInfo("Implementation-Title: Test Lib")
            .build();

    assertThat(dependency.getGroupId()).isEqualTo("com.example");
    assertThat(dependency.getArtifactId()).isEqualTo("test-lib");
    assertThat(dependency.getVersion()).isEqualTo("1.0.0");
    assertThat(dependency.getType()).isEqualTo("jar");
    assertThat(dependency.getClassifier()).isEqualTo("sources");
    assertThat(dependency.getFilePath()).isEqualTo("/path/to/test-lib-1.0.0-sources.jar");
    assertThat(dependency.getChecksum()).isEqualTo("abc123");
    assertThat(dependency.getFileSize()).isEqualTo(1024);
    assertThat(dependency.getManifestInfo()).isEqualTo("Implementation-Title: Test Lib");
  }

  @Test
  void testHasMavenCoordinates() {
    DependencyInfo withCoordinates =
        DependencyInfo.builder().groupId("com.example").artifactId("test").version("1.0").build();

    assertThat(withCoordinates.hasMavenCoordinates()).isTrue();

    DependencyInfo withoutGroup =
        DependencyInfo.builder().artifactId("test").version("1.0").build();

    assertThat(withoutGroup.hasMavenCoordinates()).isFalse();

    DependencyInfo withoutVersion =
        DependencyInfo.builder().groupId("com.example").artifactId("test").build();

    assertThat(withoutVersion.hasMavenCoordinates()).isFalse();
  }

  @Test
  void testEqualsAndHashCode() {
    DependencyInfo info1 =
        DependencyInfo.builder().groupId("com.example").artifactId("test").version("1.0").build();

    DependencyInfo info2 =
        DependencyInfo.builder().groupId("com.example").artifactId("test").version("1.0").build();

    DependencyInfo info3 =
        DependencyInfo.builder().groupId("com.example").artifactId("test").version("2.0").build();

    assertThat(info1).isEqualTo(info2);
    assertThat(info1).isNotEqualTo(info3);
    assertThat(info1.hashCode()).isEqualTo(info2.hashCode());
    assertThat(info1.hashCode()).isNotEqualTo(info3.hashCode());
  }
}
