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

import java.util.Objects;

/**
 * Represents information about a discovered dependency (JAR file). This class holds metadata
 * extracted from JAR files including Maven coordinates, file information, and other relevant
 * details for PURL generation.
 */
public class DependencyInfo {

  private final String groupId;
  private final String artifactId;
  private final String version;
  private final String type;
  private final String classifier;
  private final String filePath;
  private final String checksum;
  private final long fileSize;
  private final String manifestInfo;
  private final String scope;
  private final boolean isDirectDependency;
  private final String parentDependency;

  private DependencyInfo(Builder builder) {
    this.groupId = builder.groupId;
    this.artifactId = builder.artifactId;
    this.version = builder.version;
    this.type = builder.type;
    this.classifier = builder.classifier;
    this.filePath = builder.filePath;
    this.checksum = builder.checksum;
    this.fileSize = builder.fileSize;
    this.manifestInfo = builder.manifestInfo;
    this.scope = builder.scope;
    this.isDirectDependency = builder.isDirectDependency;
    this.parentDependency = builder.parentDependency;
  }

  public String getGroupId() {
    return groupId;
  }

  public String getArtifactId() {
    return artifactId;
  }

  public String getVersion() {
    return version;
  }

  public String getType() {
    return type;
  }

  public String getClassifier() {
    return classifier;
  }

  public String getFilePath() {
    return filePath;
  }

  public String getChecksum() {
    return checksum;
  }

  public long getFileSize() {
    return fileSize;
  }

  public String getManifestInfo() {
    return manifestInfo;
  }

  public String getScope() {
    return scope;
  }

  public boolean isDirectDependency() {
    return isDirectDependency;
  }

  public String getParentDependency() {
    return parentDependency;
  }

  public boolean hasMavenCoordinates() {
    return groupId != null && artifactId != null && version != null;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    DependencyInfo that = (DependencyInfo) o;
    return fileSize == that.fileSize
        && Objects.equals(groupId, that.groupId)
        && Objects.equals(artifactId, that.artifactId)
        && Objects.equals(version, that.version)
        && Objects.equals(type, that.type)
        && Objects.equals(classifier, that.classifier)
        && Objects.equals(filePath, that.filePath)
        && Objects.equals(checksum, that.checksum)
        && Objects.equals(manifestInfo, that.manifestInfo);
  }

  @Override
  public int hashCode() {
    return Objects.hash(
        groupId, artifactId, version, type, classifier, filePath, checksum, fileSize, manifestInfo);
  }

  @Override
  public String toString() {
    return "DependencyInfo{"
        + "groupId='"
        + groupId
        + '\''
        + ", artifactId='"
        + artifactId
        + '\''
        + ", version='"
        + version
        + '\''
        + ", type='"
        + type
        + '\''
        + ", classifier='"
        + classifier
        + '\''
        + ", filePath='"
        + filePath
        + '\''
        + ", checksum='"
        + checksum
        + '\''
        + ", fileSize="
        + fileSize
        + ", manifestInfo='"
        + manifestInfo
        + '\''
        + '}';
  }

  public static Builder builder() {
    return new Builder();
  }

  public static class Builder {
    private String groupId;
    private String artifactId;
    private String version;
    private String type = "jar";
    private String classifier;
    private String filePath;
    private String checksum;
    private long fileSize;
    private String manifestInfo;
    private String scope = "compile";
    private boolean isDirectDependency = true;
    private String parentDependency;

    public Builder groupId(String groupId) {
      this.groupId = groupId;
      return this;
    }

    public Builder artifactId(String artifactId) {
      this.artifactId = artifactId;
      return this;
    }

    public Builder version(String version) {
      this.version = version;
      return this;
    }

    public Builder type(String type) {
      this.type = type;
      return this;
    }

    public Builder classifier(String classifier) {
      this.classifier = classifier;
      return this;
    }

    public Builder filePath(String filePath) {
      this.filePath = filePath;
      return this;
    }

    public Builder checksum(String checksum) {
      this.checksum = checksum;
      return this;
    }

    public Builder fileSize(long fileSize) {
      this.fileSize = fileSize;
      return this;
    }

    public Builder manifestInfo(String manifestInfo) {
      this.manifestInfo = manifestInfo;
      return this;
    }

    public Builder scope(String scope) {
      this.scope = scope;
      return this;
    }

    public Builder isDirectDependency(boolean isDirectDependency) {
      this.isDirectDependency = isDirectDependency;
      return this;
    }

    public Builder parentDependency(String parentDependency) {
      this.parentDependency = parentDependency;
      return this;
    }

    public DependencyInfo build() {
      return new DependencyInfo(this);
    }
  }
}
