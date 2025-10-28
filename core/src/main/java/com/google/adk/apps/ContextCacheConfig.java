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
 * CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.adk.apps;

import com.google.auto.value.AutoValue;
import com.google.errorprone.annotations.CanIgnoreReturnValue;

/**
 * Configuration for context caching across all agents in an app.
 *
 * <p>This configuration enables and controls context caching behavior for all LLM agents in an app.
 * When this config is present on an app, context caching is enabled for all agents. When absent
 * (null), context caching is disabled.
 *
 * <p>Context caching can significantly reduce costs and improve response times by reusing
 * previously processed context across multiple requests.
 *
 * <p><b>Cost Model:</b> Context caching has two cost components:
 *
 * <ul>
 *   <li><b>Storage:</b> $1.00 per 1 million tokens per hour (charged for TTL duration)
 *   <li><b>Input tokens (cached):</b> 90% discount on Gemini 2.5 models (vs standard input pricing)
 * </ul>
 *
 * <p><b>Example Cost Analysis:</b>
 *
 * <pre>
 * Cache size: 10,000 tokens
 * TTL: 24 hours
 * Requests: 100/day
 *
 * Storage cost: (10,000 / 1,000,000) × $1.00/hour × 24h = $0.24/day
 * Request savings: $1.125 → $0.412 per request (90% discount)
 * Daily savings: $71.30/day - $0.24 = $71.06/day
 * Annual savings: $25,937/year
 * </pre>
 *
 * <p><b>IMPORTANT - Python Parity:</b> This matches Python ADK's implementation where
 * ContextCacheConfig is set at the App level, not on individual agents.
 *
 * <p>Example usage:
 *
 * <pre>{@code
 * ContextCacheConfig config = ContextCacheConfig.builder()
 *     .cacheIntervals(10)  // Refresh cache every 10 invocations
 *     .ttlSeconds(1800)    // 30 minutes TTL
 *     .minTokens(1000)     // Only cache if >1000 tokens
 *     .build();
 *
 * App app = App.builder()
 *     .agent(myAgent)
 *     .contextCacheConfig(config)
 *     .build();
 * }</pre>
 *
 * @since 0.4.0
 */
@AutoValue
public abstract class ContextCacheConfig {

  /**
   * Maximum number of invocations to reuse the same cache before refreshing it.
   *
   * <p>After this many invocations, the cache will be recreated even if not expired. This prevents
   * stale caches and allows for instruction updates to take effect.
   *
   * <p>Range: 1-100, Default: 10
   *
   * @return The cache interval count.
   */
  public abstract int cacheIntervals();

  /**
   * Time-to-live for cache in seconds.
   *
   * <p>After this duration, the cache will expire and be automatically deleted by the provider.
   * Minimum: 300 seconds (5 minutes), No maximum (provider-dependent).
   *
   * <p>Default: 1800 seconds (30 minutes)
   *
   * @return The TTL in seconds.
   */
  public abstract int ttlSeconds();

  /**
   * Minimum estimated request tokens required to enable caching.
   *
   * <p>This compares against the estimated total tokens of the request (system instruction + tools
   * + contents). Context cache storage may have cost. Set higher to avoid caching small requests
   * where overhead may exceed benefits.
   *
   * <p>Range: 0+, Default: 0 (cache all requests)
   *
   * <p>Recommended values:
   *
   * <ul>
   *   <li>0: Always cache (default)
   *   <li>1000: Skip caching for simple queries
   *   <li>5000: Only cache complex multi-turn conversations
   * </ul>
   *
   * @return The minimum token threshold.
   */
  public abstract int minTokens();

  /**
   * Get TTL as string format for cache creation API.
   *
   * @return TTL string (e.g., "1800s")
   */
  public String ttlString() {
    return ttlSeconds() + "s";
  }

  /**
   * Creates a new builder with default values.
   *
   * @return A new builder instance.
   */
  public static Builder builder() {
    return new AutoValue_ContextCacheConfig.Builder()
        .cacheIntervals(10)
        .ttlSeconds(1800) // 30 minutes default
        .minTokens(0);
  }

  /** Builder for {@link ContextCacheConfig}. */
  @AutoValue.Builder
  public abstract static class Builder {
    /**
     * Sets the maximum number of invocations before cache refresh.
     *
     * @param intervals Number of invocations (1-100)
     * @return This builder
     */
    @CanIgnoreReturnValue
    public abstract Builder cacheIntervals(int intervals);

    /**
     * Sets the time-to-live in seconds.
     *
     * @param seconds TTL in seconds (minimum 300)
     * @return This builder
     */
    @CanIgnoreReturnValue
    public abstract Builder ttlSeconds(int seconds);

    /**
     * Sets the minimum token threshold for caching.
     *
     * @param tokens Minimum tokens (0+)
     * @return This builder
     */
    @CanIgnoreReturnValue
    public abstract Builder minTokens(int tokens);

    abstract ContextCacheConfig autoBuild();

    /**
     * Builds and validates the ContextCacheConfig.
     *
     * @return A validated ContextCacheConfig instance.
     * @throws IllegalArgumentException if validation fails.
     */
    public final ContextCacheConfig build() {
      ContextCacheConfig config = autoBuild();

      // Validation matching Python implementation
      if (config.cacheIntervals() < 1 || config.cacheIntervals() > 100) {
        throw new IllegalArgumentException(
            "cacheIntervals must be between 1 and 100, got: " + config.cacheIntervals());
      }

      if (config.ttlSeconds() <= 0) {
        throw new IllegalArgumentException(
            "ttlSeconds must be greater than 0, got: " + config.ttlSeconds());
      }

      if (config.minTokens() < 0) {
        throw new IllegalArgumentException("minTokens must be >= 0, got: " + config.minTokens());
      }

      return config;
    }
  }

  @Override
  public String toString() {
    return String.format(
        "ContextCacheConfig(cacheIntervals=%d, ttl=%ds, minTokens=%d)",
        cacheIntervals(), ttlSeconds(), minTokens());
  }
}
