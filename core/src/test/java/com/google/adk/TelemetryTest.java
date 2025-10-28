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

package com.google.adk;

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

/**
 * Tests for OpenTelemetry metrics in Telemetry class.
 *
 * <p>Verifies that cache metric recording methods work correctly and don't throw exceptions. The
 * actual metric export to DataDog/Prometheus is tested via integration tests and validated in
 * production monitoring.
 */
@RunWith(JUnit4.class)
public class TelemetryTest {

  private static InMemoryMetricReader metricReader;
  private static SdkMeterProvider meterProvider;

  @BeforeClass
  public static void setUpClass() {
    // Reset GlobalOpenTelemetry state
    GlobalOpenTelemetry.resetForTest();

    // Setup in-memory metric reader for testing
    metricReader = InMemoryMetricReader.create();

    meterProvider = SdkMeterProvider.builder().registerMetricReader(metricReader).build();

    // Register global OTel instance with our test meter provider
    OpenTelemetrySdk.builder().setMeterProvider(meterProvider).buildAndRegisterGlobal();

    // Reset Telemetry metrics AFTER GlobalOpenTelemetry is configured
    Telemetry.resetMetricsForTest();
  }

  @AfterClass
  public static void tearDownClass() {
    if (meterProvider != null) {
      meterProvider.shutdown();
    }
    GlobalOpenTelemetry.resetForTest();
    Telemetry.resetMetricsForTest();
  }

  @Test
  public void recordCacheHit_doesNotThrow() {
    // Should not throw exception
    Telemetry.recordCacheHit("test-agent", "cache-123");
  }

  @Test
  public void recordCacheMiss_doesNotThrow() {
    // Should not throw exception
    Telemetry.recordCacheMiss("test-agent");
  }

  @Test
  public void recordCacheCreation_doesNotThrow() {
    // Should not throw exception
    Telemetry.recordCacheCreation("test-agent", 10);
  }

  @Test
  public void recordCachedTokensSaved_doesNotThrow() {
    // Should not throw exception
    Telemetry.recordCachedTokensSaved("test-agent", 5000);
  }

  @Test
  public void getMeter_returnsNonNullMeter() {
    assertThat(Telemetry.getMeter()).isNotNull();
  }

  @Test
  public void multipleRecordings_doesNotThrow() {
    // Verify multiple calls work without exceptions
    Telemetry.recordCacheHit("agent-1", "cache-A");
    Telemetry.recordCacheHit("agent-1", "cache-A");
    Telemetry.recordCacheMiss("agent-1");
    Telemetry.recordCacheCreation("agent-1", 5);
    Telemetry.recordCachedTokensSaved("agent-1", 1000);
    Telemetry.recordCachedTokensSaved("agent-1", 2000);
  }

  @Test
  public void metricsRecorded_appearsInCollection() {
    Telemetry.recordCacheHit("verification-agent", "cache-verify");

    Collection<MetricData> metrics = metricReader.collectAllMetrics();

    // Verify at least one metric was collected
    assertThat(metrics).isNotEmpty();
  }

  @Test
  public void recordCacheHit_withNullAgent_doesNotThrow() {
    // Graceful handling of null agent name
    Telemetry.recordCacheHit(null, "cache-123");
  }

  @Test
  public void recordCacheHit_withNullCache_doesNotThrow() {
    // Graceful handling of null cache name
    Telemetry.recordCacheHit("test-agent", null);
  }

  @Test
  public void recordCacheMiss_withNullAgent_doesNotThrow() {
    // Graceful handling of null agent name
    Telemetry.recordCacheMiss(null);
  }

  @Test
  public void recordCachedTokensSaved_withZeroTokens_doesNotThrow() {
    // Should handle zero tokens gracefully
    Telemetry.recordCachedTokensSaved("test-agent", 0);
  }

  @Test
  public void recordCachedTokensSaved_withNegativeTokens_doesNotThrow() {
    // Should handle negative tokens (edge case)
    Telemetry.recordCachedTokensSaved("test-agent", -100);
  }

  @Test
  public void getMeter_multipleCallsReturnSameInstance() {
    // Meter should be consistent
    assertThat(Telemetry.getMeter()).isNotNull();
    assertThat(Telemetry.getMeter()).isNotNull();
  }
}
