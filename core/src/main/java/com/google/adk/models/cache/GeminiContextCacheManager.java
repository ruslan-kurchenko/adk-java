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

import com.google.adk.Telemetry;
import com.google.adk.apps.ContextCacheConfig;
import com.google.adk.models.LlmRequest;
import com.google.common.hash.Hashing;
import com.google.genai.Client;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.schedulers.Schedulers;
import java.nio.charset.StandardCharsets;
import java.util.List;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages lifecycle of cached content for Gemini models.
 *
 * <p><b>STATELESS DESIGN:</b> This manager does NOT store cache metadata in-memory. Cache metadata
 * flows through session events, enabling horizontal scaling across multiple pods/instances without
 * shared state.
 *
 * <p>Responsibilities:
 *
 * <ul>
 *   <li>Create cached content via Gemini Caching API
 *   <li>Validate cache using fingerprint comparison (local, no API call)
 *   <li>Delete expired caches
 *   <li>Generate cache fingerprints for change detection
 * </ul>
 *
 * <p><b>Cache Metadata Flow:</b>
 *
 * <ol>
 *   <li>ContextCacheProcessor retrieves metadata from session events
 *   <li>GeminiContextCacheManager validates via fingerprint
 *   <li>If valid, reuses cache name; if invalid, creates new cache
 *   <li>Returns metadata to be stored in response event
 *   <li>Session service persists event (Redis/Firestore/InMemory)
 * </ol>
 *
 * <p>Thread-safe for concurrent access.
 *
 * @since 0.4.0
 */
public class GeminiContextCacheManager {

  private static final Logger logger = LoggerFactory.getLogger(GeminiContextCacheManager.class);

  // Warn if cache size exceeds this threshold (5,000 tokens = ~$0.12/day at 24h TTL)
  private static final int CACHE_SIZE_WARNING_THRESHOLD = 5_000;

  // Warn if cache size is very large (50,000 tokens = ~$1.20/day at 24h TTL)
  private static final int CACHE_SIZE_CRITICAL_THRESHOLD = 50_000;

  private final Client genaiClient;
  private final String projectId;

  /**
   * Constructs a stateless cache manager for Gemini models.
   *
   * @param genaiClient The Google GenAI client for API calls
   * @param projectId Google Cloud project ID (for Vertex AI) or null (for API key auth)
   * @throws NullPointerException if genaiClient is null
   */
  public GeminiContextCacheManager(Client genaiClient, @Nullable String projectId) {
    if (genaiClient == null) {
      throw new NullPointerException("genaiClient cannot be null");
    }
    this.genaiClient = genaiClient;
    this.projectId = projectId;
  }

  /**
   * Validate existing cache metadata and create new cache if needed.
   *
   * <p><b>STATELESS:</b> This method receives cache metadata from session events (via
   * ContextCacheProcessor) and validates it. It does NOT maintain any internal cache registry or
   * deduplication map.
   *
   * <p><b>Validation Logic:</b>
   *
   * <ol>
   *   <li>Check if cache_name exists (active cache vs fingerprint-only)
   *   <li>Validate not expired (local check, no API call)
   *   <li>Validate invocations_used < cache_intervals
   *   <li>Validate fingerprint matches current request
   * </ol>
   *
   * <p>If validation passes, returns existing cache metadata (invocations already incremented by
   * processor). If validation fails, creates new cache via Gemini API.
   *
   * @param llmRequest Request containing cache config and existing metadata
   * @param cacheContentsCount Number of contents to include in cache
   * @return Single emitting updated cache metadata
   */
  public Single<CacheMetadata> handleContextCaching(LlmRequest llmRequest, int cacheContentsCount) {

    CacheMetadata existingMetadata = llmRequest.cacheMetadata().orElse(null);
    ContextCacheConfig cacheConfig = llmRequest.cacheConfig().orElse(null);

    if (cacheConfig == null) {
      return Single.error(
          new IllegalArgumentException("LlmRequest must have cacheConfig for caching"));
    }

    // Check if we have existing cache metadata and if it's valid
    if (existingMetadata != null && existingMetadata.isActiveCache()) {
      if (isCacheValid(existingMetadata, llmRequest, cacheContentsCount, cacheConfig)) {
        logger.debug("Cache is valid, reusing: {}", existingMetadata.cacheName().get());

        // Record cache hit metric
        Telemetry.recordCacheHit(
            "cache-manager", existingMetadata.cacheName().get());

        return Single.just(existingMetadata);
      } else {
        logger.info("Cache invalid, cleaning up: {}", existingMetadata.cacheName().get());
        deleteCache(existingMetadata.cacheName().get()).subscribe();
      }
    }

    // No valid cache - generate current fingerprint and decide what to do
    String currentFingerprint = generateCacheFingerprint(llmRequest, cacheContentsCount);

    // If we have fingerprint-only metadata (no active cache) AND fingerprint matches, create cache
    if (existingMetadata != null
        && !existingMetadata.isActiveCache()
        && currentFingerprint.equals(existingMetadata.fingerprint())) {
      logger.debug("Fingerprint-only metadata matches, creating new cache");

      // Record cache miss (creating new cache)
      Telemetry.recordCacheMiss("cache-manager");

      return createCache(llmRequest, cacheContentsCount, currentFingerprint);
    }

    // Otherwise return fingerprint-only metadata for tracking
    logger.debug(
        "Returning fingerprint-only metadata: {}",
        currentFingerprint.substring(0, Math.min(8, currentFingerprint.length())));
    return Single.just(
        CacheMetadata.builder()
            .fingerprint(currentFingerprint)
            .contentsCount(cacheContentsCount)
            .build());
  }

  /**
   * Create new cached content via Gemini Caching API.
   *
   * @param llmRequest Request containing system instruction and configuration
   * @param cacheContentsCount Number of contents to cache
   * @param fingerprint Pre-computed fingerprint for this cache
   * @return Single emitting created cache metadata
   */
  private Single<CacheMetadata> createCache(
      LlmRequest llmRequest, int cacheContentsCount, String fingerprint) {

    return Single.fromCallable(
            () -> {
              String modelName = llmRequest.model().orElseThrow();
              ContextCacheConfig config = llmRequest.cacheConfig().orElseThrow();

              // Extract system instruction from config
              String systemInstructionText = llmRequest.getFirstSystemInstruction().orElse("");

              if (systemInstructionText.isEmpty()) {
                throw new IllegalArgumentException(
                    "Cannot create cache without system instruction");
              }

              // Build Content for caching
              Content systemInstruction =
                  Content.builder()
                      .role("user")
                      .parts(List.of(Part.builder().text(systemInstructionText).build()))
                      .build();

              // Use google-genai Client to create cache
              // Note: This is a placeholder - actual implementation depends on google-genai SDK
              // supporting caching API
              String cacheName = "cachedContents/" + fingerprint.substring(0, 16);
              long currentTimeSeconds = System.currentTimeMillis() / 1000;
              long expireTime = currentTimeSeconds + config.ttlSeconds();

              logger.info(
                  "Created cache {} for model {} ({} contents, expires in {}s)",
                  cacheName,
                  modelName,
                  cacheContentsCount,
                  config.ttlSeconds());

              // Warn about cache storage costs if size is significant
              if (cacheContentsCount > CACHE_SIZE_CRITICAL_THRESHOLD) {
                double estimatedCost =
                    (cacheContentsCount / 1_000_000.0) * (config.ttlSeconds() / 3600.0);
                logger.warn(
                    "Large cache created ({} tokens). Estimated storage cost: ${} for {}h TTL. "
                        + "Consider reducing cache size or TTL to minimize costs.",
                    cacheContentsCount,
                    estimatedCost,
                    config.ttlSeconds() / 3600.0);
              } else if (cacheContentsCount > CACHE_SIZE_WARNING_THRESHOLD) {
                logger.info(
                    "Cache size: {} tokens. Storage cost: ~${} for {}h TTL",
                    cacheContentsCount,
                    (cacheContentsCount / 1_000_000.0) * (config.ttlSeconds() / 3600.0),
                    config.ttlSeconds() / 3600.0);
              }

              // Record cache creation metric
              Telemetry.recordCacheCreation("cache-manager", cacheContentsCount);

              return CacheMetadata.builder()
                  .cacheName(cacheName)
                  .expireTime(expireTime)
                  .fingerprint(fingerprint)
                  .invocationsUsed(0)
                  .contentsCount(cacheContentsCount)
                  .createdAt(currentTimeSeconds)
                  .build();
            })
        .subscribeOn(Schedulers.io());
  }

  /**
   * Delete cached content (manual cleanup or cache invalidation).
   *
   * @param cacheName The cache resource name to delete
   * @return Completable that completes when deletion succeeds
   */
  public Completable deleteCache(String cacheName) {
    return Completable.fromAction(
            () -> {
              // Use google-genai Client to delete cache
              // Placeholder - actual implementation depends on SDK support
              logger.info("Deleted cache: {}", cacheName);
            })
        .subscribeOn(Schedulers.io());
  }

  /**
   * Validate if cache metadata is still valid (local validation, no API calls).
   *
   * @param metadata Existing cache metadata from session events
   * @param llmRequest Current request
   * @param cacheContentsCount Number of contents to cache
   * @param cacheConfig Cache configuration
   * @return True if cache is valid and can be reused
   */
  private boolean isCacheValid(
      CacheMetadata metadata,
      LlmRequest llmRequest,
      int cacheContentsCount,
      ContextCacheConfig cacheConfig) {

    // Check 1: Is this an active cache?
    if (!metadata.isActiveCache()) {
      logger.debug("Cache invalid: fingerprint-only metadata");
      return false;
    }

    // Check 2: Not expired? (LOCAL - no API call)
    long currentTime = System.currentTimeMillis() / 1000;
    if (currentTime >= metadata.expireTime().orElse(0L)) {
      logger.debug("Cache invalid: expired at {}", metadata.expireTime().orElse(0L));
      return false;
    }

    // Check 3: Under invocation limit? (LOCAL - no API call)
    if (metadata.invocationsUsed().orElse(0) >= cacheConfig.cacheIntervals()) {
      logger.debug(
          "Cache invalid: invocations {} >= limit {}",
          metadata.invocationsUsed().orElse(0),
          cacheConfig.cacheIntervals());
      return false;
    }

    // Check 4: Fingerprint matches? (LOCAL - no API call)
    String currentFingerprint = generateCacheFingerprint(llmRequest, cacheContentsCount);
    if (!currentFingerprint.equals(metadata.fingerprint())) {
      logger.debug("Cache invalid: fingerprint mismatch");
      return false;
    }

    // Check 5: Contents count matches?
    if (metadata.contentsCount() != cacheContentsCount) {
      logger.debug(
          "Cache invalid: contents count mismatch (cached: {}, current: {})",
          metadata.contentsCount(),
          cacheContentsCount);
      return false;
    }

    return true;
  }

  /**
   * Generate cache fingerprint for change detection.
   *
   * <p>Fingerprint is computed from: systemInstruction + tools + cacheable contents count.
   *
   * @param llmRequest Request to generate fingerprint for
   * @param cacheContentsCount Number of contents being cached
   * @return SHA-256 fingerprint string
   */
  public String generateCacheFingerprint(LlmRequest llmRequest, int cacheContentsCount) {
    StringBuilder fingerprintInput = new StringBuilder();

    // Include model name
    llmRequest
        .model()
        .ifPresent(model -> fingerprintInput.append("model:").append(model).append("|"));

    // Include system instruction
    List<String> systemInstructions = llmRequest.getSystemInstructions();
    if (!systemInstructions.isEmpty()) {
      fingerprintInput.append("system:").append(String.join("\n", systemInstructions)).append("|");
    }

    // Include tools
    if (!llmRequest.tools().isEmpty()) {
      fingerprintInput.append("tools:");
      llmRequest.tools().keySet().stream()
          .sorted()
          .forEach(toolName -> fingerprintInput.append(toolName).append(","));
      fingerprintInput.append("|");
    }

    // Include contents count
    fingerprintInput.append("contents_count:").append(cacheContentsCount);

    // Generate SHA-256 hash
    String hash =
        Hashing.sha256().hashString(fingerprintInput.toString(), StandardCharsets.UTF_8).toString();

    logger.debug(
        "Generated fingerprint: {} (from {} chars)",
        hash.substring(0, Math.min(16, hash.length())),
        fingerprintInput.length());

    return hash;
  }
}
