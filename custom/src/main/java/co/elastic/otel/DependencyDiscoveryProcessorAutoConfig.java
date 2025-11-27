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
package co.elastic.otel;

import co.elastic.otel.common.ChainingSpanProcessorAutoConfiguration;
import co.elastic.otel.common.ChainingSpanProcessorRegisterer;
import co.elastic.otel.dependency.DependencyDiscoverySpanProcessor;
import com.google.auto.service.AutoService;
import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

@AutoService(ChainingSpanProcessorAutoConfiguration.class)
public class DependencyDiscoveryProcessorAutoConfig
    implements ChainingSpanProcessorAutoConfiguration {

  private static final Logger logger =
      Logger.getLogger(DependencyDiscoveryProcessorAutoConfig.class.getName());

  static final String ENABLED_OPTION = "elastic.dependency.discovery.enabled";
  static final String DELAY_SECONDS_OPTION = "elastic.dependency.discovery.delay.seconds";
  static final String INTERVAL_HOURS_OPTION = "elastic.dependency.discovery.interval.hours";

  @Override
  public void registerSpanProcessors(
      ConfigProperties properties, ChainingSpanProcessorRegisterer registerer) {
    boolean enabled = properties.getBoolean(ENABLED_OPTION, true);

    if (enabled) {
      long delaySeconds = properties.getLong(DELAY_SECONDS_OPTION, 5L);
      long intervalHours = properties.getLong(INTERVAL_HOURS_OPTION, 6L);

      long delayMillis = TimeUnit.SECONDS.toMillis(delaySeconds);
      long intervalMillis = TimeUnit.HOURS.toMillis(intervalHours);

      registerer.register(
          next -> {
            // Use lazy initialization to avoid GlobalOpenTelemetry conflicts
            DependencyDiscoverySpanProcessor processor =
                new DependencyDiscoverySpanProcessor(delayMillis, intervalMillis);
            logger.log(
                Level.INFO,
                "Registered DependencyDiscoverySpanProcessor with delay {0}s and interval {1}h",
                new Object[] {delaySeconds, intervalHours});
            return processor;
          });
    } else {
      logger.log(Level.INFO, "Dependency discovery is disabled by configuration.");
    }
  }
}
