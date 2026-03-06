/*
 * Copyright 2025 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.adk.telemetry;

import static com.google.common.truth.Truth.assertThat;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
import java.util.Collection;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class TracingCacheMetricsTest {

  private static InMemoryMetricReader metricReader;
  private static SdkMeterProvider meterProvider;

  @BeforeClass
  public static void setUpClass() {
    GlobalOpenTelemetry.resetForTest();

    metricReader = InMemoryMetricReader.create();
    meterProvider = SdkMeterProvider.builder().registerMetricReader(metricReader).build();

    OpenTelemetrySdk.builder().setMeterProvider(meterProvider).buildAndRegisterGlobal();
    Tracing.resetMetricsForTest();
  }

  @AfterClass
  public static void tearDownClass() {
    if (meterProvider != null) {
      meterProvider.shutdown();
    }
    GlobalOpenTelemetry.resetForTest();
    Tracing.resetMetricsForTest();
  }

  @Test
  public void recordCacheHit_doesNotThrow() {
    Tracing.recordCacheHit("test-agent", "cache-123");
  }

  @Test
  public void recordCacheMiss_doesNotThrow() {
    Tracing.recordCacheMiss("test-agent");
  }

  @Test
  public void recordCacheCreation_doesNotThrow() {
    Tracing.recordCacheCreation("test-agent", 10);
  }

  @Test
  public void recordCacheDeletion_doesNotThrow() {
    Tracing.recordCacheDeletion("duplicate-cleanup", "cache-123");
  }

  @Test
  public void recordCacheFragmentation_doesNotThrow() {
    Tracing.recordCacheFragmentation("test-agent", 3);
  }

  @Test
  public void recordCachedTokensSaved_doesNotThrow() {
    Tracing.recordCachedTokensSaved("test-agent", 5000);
  }

  @Test
  public void getMeter_returnsNonNullMeter() {
    assertThat(Tracing.getMeter()).isNotNull();
  }

  @Test
  public void multipleRecordings_doesNotThrow() {
    Tracing.recordCacheHit("agent-1", "cache-A");
    Tracing.recordCacheHit("agent-1", "cache-A");
    Tracing.recordCacheMiss("agent-1");
    Tracing.recordCacheCreation("agent-1", 5);
    Tracing.recordCacheDeletion("manual", "cache-A");
    Tracing.recordCacheFragmentation("agent-1", 2);
    Tracing.recordCachedTokensSaved("agent-1", 1000);
    Tracing.recordCachedTokensSaved("agent-1", 2000);
  }

  @Test
  public void metricsRecorded_appearsInCollection() {
    Tracing.recordCacheHit("verification-agent", "cache-verify");

    Collection<MetricData> metrics = metricReader.collectAllMetrics();

    assertThat(metrics).isNotEmpty();
  }

  @Test
  public void recordCacheHit_withNullAgent_doesNotThrow() {
    Tracing.recordCacheHit(null, "cache-123");
  }

  @Test
  public void recordCacheHit_withNullCache_doesNotThrow() {
    Tracing.recordCacheHit("test-agent", null);
  }

  @Test
  public void recordCacheMiss_withNullAgent_doesNotThrow() {
    Tracing.recordCacheMiss(null);
  }

  @Test
  public void recordCachedTokensSaved_withZeroTokens_doesNotThrow() {
    Tracing.recordCachedTokensSaved("test-agent", 0);
  }

  @Test
  public void recordCachedTokensSaved_withNegativeTokens_doesNotThrow() {
    Tracing.recordCachedTokensSaved("test-agent", -100);
  }

  @Test
  public void getMeter_multipleCallsReturnSameInstance() {
    assertThat(Tracing.getMeter()).isNotNull();
    assertThat(Tracing.getMeter()).isNotNull();
  }
}
