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

package com.google.adk.models.cache;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.google.adk.JsonBaseModel;
import com.google.auto.value.AutoValue;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.util.Optional;
import javax.annotation.Nullable;

/**
 * Metadata for context cache associated with LLM responses.
 *
 * <p>This class stores cache identification, usage tracking, and lifecycle information for a
 * particular cache instance. It can be in two states:
 *
 * <ol>
 *   <li><b>Active cache state:</b> cacheName is set, all fields populated
 *   <li><b>Fingerprint-only state:</b> cacheName is null, only fingerprint and contentsCount are
 *       set for prefix matching
 * </ol>
 *
 * <p><b>Python Parity:</b> This exactly matches Python ADK's CacheMetadata model with the same
 * two-state design and field names.
 *
 * <p>Token counts (cached and total) are available in the LlmResponse.usageMetadata and should be
 * accessed from there to avoid duplication.
 *
 * @since 0.4.0
 */
@AutoValue
@JsonDeserialize(builder = CacheMetadata.Builder.class)
public abstract class CacheMetadata extends JsonBaseModel {

  /**
   * The full resource name of the cached content.
   *
   * <p>Example: "projects/123/locations/us-central1/cachedContents/456" or
   * "cachedContents/abc123-def456" (API key auth).
   *
   * <p>Null when no active cache exists (fingerprint-only state).
   *
   * @return The cache name, or empty if no active cache
   */
  @JsonProperty("cache_name")
  public abstract Optional<String> cacheName();

  /**
   * Unix timestamp (seconds) when the cache expires.
   *
   * <p>Null when no active cache exists.
   *
   * @return The expiration time, or empty if no active cache
   */
  @JsonProperty("expire_time")
  public abstract Optional<Long> expireTime();

  /**
   * Hash of cacheable contents used to detect changes.
   *
   * <p>Fingerprint is computed from: systemInstruction + tools + cacheable contents. Always present
   * for prefix matching.
   *
   * @return The SHA-256 fingerprint string
   */
  @JsonProperty("fingerprint")
  public abstract String fingerprint();

  /**
   * Number of contents in the cache or request.
   *
   * <p>When active cache exists: number of cached contents. When no active cache: total contents
   * count in request.
   *
   * @return The contents count
   */
  @JsonProperty("contents_count")
  public abstract int contentsCount();

  /**
   * Unix timestamp (seconds) when the cache was created.
   *
   * <p>Null when no active cache exists.
   *
   * @return The creation time, or empty if no active cache
   */
  @JsonProperty("created_at")
  public abstract Optional<Long> createdAt();

  /**
   * Number of times this cache has been used (invoked).
   *
   * <p>Initialized to 1 on cache creation and incremented on each reuse in a different invocation.
   * Used with {@link com.google.adk.apps.ContextCacheConfig#cacheIntervals()} to trigger automatic
   * cache refresh.
   *
   * <p><b>Multi-Pod Behavior:</b> In concurrent multi-pod deployments, the count may be approximate
   * due to race conditions (e.g., actual count 10 might be recorded as 9). This is acceptable as
   * cache refresh is an optimization, not a correctness requirement.
   *
   * <p>When invocations_used exceeds cache_intervals, the cache is invalidated and a fresh cache is
   * created with invocations_used reset to 1.
   *
   * <p>Matches Python ADK implementation (cache_metadata.py).
   *
   * @return The invocation count, or empty if not tracked
   * @since 0.4.0
   */
  @JsonProperty("invocations_used")
  public abstract Optional<Integer> invocationsUsed();

  /**
   * Check if the cache will expire soon (within 2-minute buffer).
   *
   * <p>Used to trigger proactive cache refresh before actual expiration.
   *
   * @return True if cache expires within 2 minutes, false otherwise or if no active cache
   */
  public boolean expireSoon() {
    if (expireTime().isEmpty()) {
      return false;
    }
    long bufferSeconds = 120; // 2 minutes buffer for processing time
    long currentTime = System.currentTimeMillis() / 1000;
    return currentTime > (expireTime().get() - bufferSeconds);
  }

  /**
   * Check if this is an active cache (has cache name) or fingerprint-only.
   *
   * @return True if active cache, false if fingerprint-only
   */
  public boolean isActiveCache() {
    return cacheName().isPresent();
  }

  /**
   * Estimate storage cost for this cache in USD.
   *
   * <p>Gemini charges approximately $1.00 per 1 million tokens per hour for cache storage. This
   * method calculates the estimated cost based on the cache size and remaining TTL.
   *
   * <p><b>Cost Formula:</b> {@code (contentsCount / 1,000,000) * $1.00/hour * remainingHours}
   *
   * <p><b>Note:</b> This is an estimation. Actual costs may vary based on:
   *
   * <ul>
   *   <li>Provider pricing updates
   *   <li>Token counting differences
   *   <li>Regional pricing variations
   * </ul>
   *
   * @return Estimated storage cost in USD, or 0.0 if no active cache
   */
  public double estimatedStorageCost() {
    if (!isActiveCache() || expireTime().isEmpty()) {
      return 0.0;
    }

    long currentTimeSeconds = System.currentTimeMillis() / 1000;
    long remainingSeconds = Math.max(0, expireTime().get() - currentTimeSeconds);
    double remainingHours = remainingSeconds / 3600.0;

    // Cost: $1.00 per 1 million tokens per hour
    double costPerTokenPerHour = 1.0 / 1_000_000.0;
    return contentsCount() * costPerTokenPerHour * remainingHours;
  }

  /**
   * Creates a new builder.
   *
   * @return A new builder instance.
   */
  public static Builder builder() {
    return new AutoValue_CacheMetadata.Builder();
  }

  /**
   * Creates a builder initialized with this instance's values.
   *
   * @return A builder for modification
   */
  public abstract Builder toBuilder();

  /** Builder for {@link CacheMetadata}. */
  @AutoValue.Builder
  public abstract static class Builder {

    /**
     * Jackson deserialization creator.
     *
     * @return A new builder for Jackson
     */
    @JsonCreator
    private static Builder create() {
      return builder();
    }

    /**
     * Sets the cache name.
     *
     * @param cacheName The cache resource name, or null for fingerprint-only state
     * @return This builder
     */
    @JsonProperty("cache_name")
    @CanIgnoreReturnValue
    public abstract Builder cacheName(@Nullable String cacheName);

    /**
     * Sets the cache name using Optional.
     *
     * @param cacheName Optional cache name
     * @return This builder
     */
    @CanIgnoreReturnValue
    public abstract Builder cacheName(Optional<String> cacheName);

    /**
     * Sets the expiration time.
     *
     * @param expireTime Unix timestamp in seconds, or null
     * @return This builder
     */
    @JsonProperty("expire_time")
    @CanIgnoreReturnValue
    public abstract Builder expireTime(@Nullable Long expireTime);

    /**
     * Sets the expiration time using Optional.
     *
     * @param expireTime Optional expiration time
     * @return This builder
     */
    @CanIgnoreReturnValue
    public abstract Builder expireTime(Optional<Long> expireTime);

    /**
     * Sets the content fingerprint.
     *
     * @param fingerprint SHA-256 hash of cached contents
     * @return This builder
     */
    @JsonProperty("fingerprint")
    @CanIgnoreReturnValue
    public abstract Builder fingerprint(String fingerprint);

    /**
     * Sets the contents count.
     *
     * @param count Number of cached/total contents
     * @return This builder
     */
    @JsonProperty("contents_count")
    @CanIgnoreReturnValue
    public abstract Builder contentsCount(int count);

    /**
     * Sets the creation timestamp.
     *
     * @param createdAt Unix timestamp in seconds, or null
     * @return This builder
     */
    @JsonProperty("created_at")
    @CanIgnoreReturnValue
    public abstract Builder createdAt(@Nullable Long createdAt);

    /**
     * Sets the creation timestamp using Optional.
     *
     * @param createdAt Optional creation time
     * @return This builder
     */
    @CanIgnoreReturnValue
    public abstract Builder createdAt(Optional<Long> createdAt);

    /**
     * Sets the invocations used count.
     *
     * @param invocationsUsed Number of times cache has been used, or null
     * @return This builder
     */
    @JsonProperty("invocations_used")
    @CanIgnoreReturnValue
    public abstract Builder invocationsUsed(@Nullable Integer invocationsUsed);

    /**
     * Sets the invocations used count using Optional.
     *
     * @param invocationsUsed Optional invocations count
     * @return This builder
     */
    @CanIgnoreReturnValue
    public abstract Builder invocationsUsed(Optional<Integer> invocationsUsed);

    /**
     * Builds the CacheMetadata instance.
     *
     * @return A new CacheMetadata instance
     */
    public abstract CacheMetadata build();
  }

  /**
   * Deserializes a CacheMetadata instance from a JSON string.
   *
   * @param json The JSON string to deserialize
   * @return The deserialized CacheMetadata instance
   */
  public static CacheMetadata fromJson(String json) {
    return JsonBaseModel.fromJsonString(json, CacheMetadata.class);
  }

  @Override
  public String toString() {
    if (cacheName().isEmpty()) {
      return String.format(
          "Fingerprint-only: %d contents, fingerprint=%s...",
          contentsCount(), fingerprint().substring(0, Math.min(8, fingerprint().length())));
    }

    String cacheId = cacheName().get().substring(cacheName().get().lastIndexOf('/') + 1);
    long currentTime = System.currentTimeMillis() / 1000;
    double timeUntilExpiryMinutes = (expireTime().get() - currentTime) / 60.0;

    return String.format(
        "Cache %s: %d contents, expires in %.1fmin",
        cacheId, contentsCount(), timeUntilExpiryMinutes);
  }
}
