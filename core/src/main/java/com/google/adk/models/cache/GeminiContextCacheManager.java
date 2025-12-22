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
import com.google.adk.cache.DistributedLock;
import com.google.adk.cache.InMemoryDistributedLock;
import com.google.adk.models.LlmRequest;
import com.google.common.hash.Hashing;
import com.google.genai.Client;
import com.google.genai.types.CachedContent;
import com.google.genai.types.Content;
import com.google.genai.types.CreateCachedContentConfig;
import com.google.genai.types.DeleteCachedContentConfig;
import com.google.genai.types.GenerateContentConfig;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.schedulers.Schedulers;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
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

  // Lock timeout for cache creation (should be > expected cache creation time)
  private static final Duration LOCK_TIMEOUT = Duration.ofSeconds(10);

  private final Client genaiClient;
  private final String projectId;
  private final DistributedLock distributedLock;

  /**
   * Constructs a stateless cache manager for Gemini models.
   *
   * @param genaiClient The Google GenAI client for API calls
   * @param projectId Google Cloud project ID (for Vertex AI) or null (for API key auth)
   * @param distributedLock Distributed lock for coordinating cache creation across pods, or null to
   *     use default InMemoryDistributedLock (suitable for single-pod deployments)
   * @throws NullPointerException if genaiClient is null
   */
  public GeminiContextCacheManager(
      Client genaiClient, @Nullable String projectId, @Nullable DistributedLock distributedLock) {
    if (genaiClient == null) {
      throw new NullPointerException("genaiClient cannot be null");
    }
    this.genaiClient = genaiClient;
    this.projectId = projectId;
    this.distributedLock =
        distributedLock != null ? distributedLock : new InMemoryDistributedLock();

    if (distributedLock == null) {
      logger.info(
          "Using InMemoryDistributedLock. For multi-pod deployments, provide a Redis or Firestore-based DistributedLock implementation.");
    }
  }

  /**
   * Validate existing cache metadata and create new cache if needed.
   *
   * <p>Stateless method that receives cache metadata from session events and validates it locally
   * without maintaining internal state. Performs fingerprint comparison, expiration checks, and
   * invocation limits.
   *
   * @param llmRequest Request containing cache config and existing metadata
   * @param cacheContentsCount Number of contents to include in cache
   * @param cacheStrategy Cache strategy ("explicit" for ADK-managed, "implicit" for Gemini
   *     automatic)
   * @return Single emitting updated cache metadata
   * @since 0.4.0
   */
  public Single<CacheMetadata> handleContextCaching(
      LlmRequest llmRequest, int cacheContentsCount, String cacheStrategy) {

    ContextCacheConfig cacheConfig = llmRequest.cacheConfig().orElse(null);
    if (cacheConfig == null) {
      return Single.error(
          new IllegalArgumentException("LlmRequest must have cacheConfig for caching"));
    }

    CacheMetadata existingMetadata = llmRequest.cacheMetadata().orElse(null);

    if (canReuseExistingCache(existingMetadata, llmRequest, cacheContentsCount, cacheConfig)) {
      return reuseCache(existingMetadata, cacheStrategy);
    }

    if (shouldCreateNewCache(existingMetadata, llmRequest, cacheContentsCount)) {
      return createNewCache(llmRequest, cacheContentsCount, cacheStrategy);
    }

    return returnFingerprintOnlyMetadata(llmRequest, cacheContentsCount);
  }

  private boolean canReuseExistingCache(
      CacheMetadata metadata,
      LlmRequest llmRequest,
      int cacheContentsCount,
      ContextCacheConfig cacheConfig) {

    if (metadata == null || !metadata.isActiveCache()) {
      return false;
    }

    if (isCacheValid(metadata, llmRequest, cacheContentsCount, cacheConfig)) {
      return true;
    }

    logger.info("Cache invalid, cleaning up: {}", metadata.cacheName().get());
    String cacheName = metadata.cacheName().get();
    deleteCache(cacheName)
        .subscribe(
            () -> logger.debug("Successfully deleted invalid cache: {}", cacheName),
            error -> logger.error("Failed to delete invalid cache: {}", cacheName, error));
    return false;
  }

  private Single<CacheMetadata> reuseCache(CacheMetadata metadata, String cacheStrategy) {
    logger.debug("Cache is valid, reusing: {}", metadata.cacheName().get());
    Telemetry.recordCacheHit("cache-manager", metadata.cacheName().get(), cacheStrategy);
    return Single.just(metadata);
  }

  private boolean shouldCreateNewCache(
      CacheMetadata metadata, LlmRequest llmRequest, int cacheContentsCount) {

    if (metadata == null || metadata.isActiveCache()) {
      return false;
    }

    String currentFingerprint = generateCacheFingerprint(llmRequest, cacheContentsCount);
    return currentFingerprint.equals(metadata.fingerprint());
  }

  /**
   * Creates a new cache when fingerprint-only metadata matches.
   *
   * <p><b>Race Condition Prevention:</b> Uses distributed lock to prevent multiple pods from
   * simultaneously creating duplicate caches. Lock key is based on fingerprint, ensuring only one
   * pod creates a cache for each unique cache configuration.
   *
   * <p><b>Lock Behavior:</b>
   *
   * <ul>
   *   <li>Lock timeout: 10 seconds (should exceed expected cache creation time)
   *   <li>If lock held by another pod: waits up to timeout, then proceeds with creation
   *   <li>Lock auto-released after cache creation completes (success or failure)
   * </ul>
   *
   * <p><b>Production Deployments:</b> Use Redis or Firestore-based {@link DistributedLock}
   * implementation for true distributed coordination. The default {@link InMemoryDistributedLock}
   * only prevents duplicates within a single JVM.
   *
   * @param llmRequest Request to create cache for
   * @param cacheContentsCount Number of contents to cache
   * @param cacheStrategy Cache strategy for telemetry tagging
   * @return Single emitting created cache metadata
   */
  private Single<CacheMetadata> createNewCache(
      LlmRequest llmRequest, int cacheContentsCount, String cacheStrategy) {
    String fingerprint = generateCacheFingerprint(llmRequest, cacheContentsCount);
    String lockKey = "cache-creation:" + fingerprint;

    logger.debug("Fingerprint-only metadata matches, acquiring lock for cache creation");
    Telemetry.recordCacheMiss("cache-manager", cacheStrategy);

    return distributedLock
        .acquire(lockKey, LOCK_TIMEOUT)
        .flatMap(
            lockHandle -> {
              logger.debug("Lock acquired for {}, creating cache", lockKey);

              return createCache(llmRequest, cacheContentsCount, fingerprint, cacheStrategy)
                  .doFinally(
                      () -> {
                        lockHandle
                            .release()
                            .subscribe(
                                () -> logger.debug("Lock released for {}", lockKey),
                                error ->
                                    logger.warn(
                                        "Failed to release lock for {}: {}", lockKey, error));
                      });
            })
        .onErrorResumeNext(
            error -> {
              // If lock acquisition fails (timeout), proceed anyway with duplicate detection
              // fallback
              if (error instanceof DistributedLock.LockTimeoutException) {
                logger.warn(
                    "Lock timeout for {}, proceeding with cache creation. "
                        + "Duplicate detection will handle any race conditions.",
                    lockKey);
                return createCache(llmRequest, cacheContentsCount, fingerprint, cacheStrategy);
              }
              return Single.error(error);
            });
  }

  private Single<CacheMetadata> returnFingerprintOnlyMetadata(
      LlmRequest llmRequest, int cacheContentsCount) {
    String fingerprint = generateCacheFingerprint(llmRequest, cacheContentsCount);
    logger.debug(
        "Returning fingerprint-only metadata: {}",
        fingerprint.substring(0, Math.min(8, fingerprint.length())));

    return Single.just(
        CacheMetadata.builder().fingerprint(fingerprint).contentsCount(cacheContentsCount).build());
  }

  /**
   * Create new cached content via Gemini Caching API.
   *
   * @param llmRequest Request containing system instruction and configuration
   * @param cacheContentsCount Number of contents to cache
   * @param fingerprint Pre-computed fingerprint for this cache
   * @param cacheStrategy Cache strategy for telemetry tagging
   * @return Single emitting created cache metadata
   */
  private Single<CacheMetadata> createCache(
      LlmRequest llmRequest, int cacheContentsCount, String fingerprint, String cacheStrategy) {

    return Single.fromCallable(
            () -> {
              validateCacheCreationRequest(llmRequest);

              String modelName = llmRequest.model().orElseThrow();
              ContextCacheConfig config = llmRequest.cacheConfig().orElseThrow();

              String cacheName = callGeminiCachingAPI(llmRequest, config, cacheContentsCount);
              long expireTime = calculateExpireTime(config);

              logCacheCreation(cacheName, modelName, cacheContentsCount, config);
              warnIfCacheCostIsHigh(cacheContentsCount, config);
              recordCacheCreationMetrics(cacheContentsCount, cacheStrategy);

              return buildCacheMetadata(cacheName, fingerprint, cacheContentsCount, expireTime);
            })
        .subscribeOn(Schedulers.io());
  }

  private void validateCacheCreationRequest(LlmRequest llmRequest) {
    String systemInstructionText = llmRequest.getFirstSystemInstruction().orElse("");
    if (systemInstructionText.isEmpty()) {
      throw new IllegalArgumentException("Cannot create cache without system instruction");
    }
  }

  private String callGeminiCachingAPI(
      LlmRequest llmRequest, ContextCacheConfig config, int cacheContentsCount) {
    try {
      String modelName =
          llmRequest
              .model()
              .orElseThrow(() -> new IllegalStateException("Model name required for caching"));

      // Ensure model name has proper format
      if (!modelName.startsWith("models/")) {
        modelName = "models/" + modelName;
      }

      // Extract system instruction Content object
      Content systemInstruction =
          llmRequest
              .config()
              .flatMap(GenerateContentConfig::systemInstruction)
              .orElseThrow(
                  () -> new IllegalStateException("System instruction required for caching"));

      // Build cache creation config
      CreateCachedContentConfig.Builder configBuilder =
          CreateCachedContentConfig.builder()
              .systemInstruction(systemInstruction)
              .ttl(Duration.ofSeconds(config.ttlSeconds()));

      // Add tools if present
      llmRequest.config().flatMap(GenerateContentConfig::tools).ifPresent(configBuilder::tools);

      // Add tool config if present
      llmRequest
          .config()
          .flatMap(GenerateContentConfig::toolConfig)
          .ifPresent(configBuilder::toolConfig);

      // Add cacheable conversation contents (per-session caching)
      if (cacheContentsCount > 0 && !llmRequest.contents().isEmpty()) {
        int contentsToCache = Math.min(cacheContentsCount, llmRequest.contents().size());

        if (contentsToCache > 0) {
          List<Content> cacheableContents = llmRequest.contents().subList(0, contentsToCache);
          configBuilder.contents(cacheableContents);

          logger.debug(
              "Including {} conversation contents in cache (per-session caching)", contentsToCache);
        }
      } else {
        logger.debug(
            "No conversation contents to cache (cacheContentsCount={}, request contents={})",
            cacheContentsCount,
            llmRequest.contents().size());
      }

      // Create cache via Gemini API
      logger.debug("Creating cache for model {} with TTL {}s", modelName, config.ttlSeconds());

      CachedContent cachedContent =
          genaiClient
              .async
              .caches
              .create(modelName, configBuilder.build())
              .join(); // Block and wait for result (on IO scheduler)

      // Validate cache creation response
      validateCacheCreationResponse(cachedContent, modelName);

      String cacheName = cachedContent.name().get(); // Safe after validation

      logger.debug("Successfully created cache: {}", cacheName);
      return cacheName;

    } catch (java.util.concurrent.CompletionException e) {
      Throwable cause = e.getCause();

      // Handle specific API errors
      if (cause != null && cause.getMessage() != null) {
        String message = cause.getMessage();

        if (message.contains("429") || message.contains("RESOURCE_EXHAUSTED")) {
          logger.warn("Rate limited creating cache, will retry on next invocation");
          throw new RuntimeException("Rate limited creating cache", e);
        } else if (message.contains("400") || message.contains("INVALID_ARGUMENT")) {
          logger.error("Invalid cache creation request: {}", message);
          throw new IllegalArgumentException("Invalid cache configuration: " + message, e);
        } else if (message.contains("401") || message.contains("UNAUTHENTICATED")) {
          logger.error("Authentication failed for cache creation");
          throw new RuntimeException("Authentication failed", e);
        }
      }

      logger.error("Failed to create cache via Gemini API", e);
      throw new RuntimeException("Cache creation failed: " + e.getMessage(), e);

    } catch (Exception e) {
      logger.error("Unexpected error creating cache", e);
      throw new RuntimeException("Cache creation failed: " + e.getMessage(), e);
    }
  }

  /**
   * Validates cache creation response from Gemini API.
   *
   * <p>Ensures the cache was created successfully and has valid metadata.
   *
   * @param cachedContent The response from cache creation API
   * @param expectedModel The model name we requested
   * @throws IllegalStateException if validation fails
   */
  private void validateCacheCreationResponse(CachedContent cachedContent, String expectedModel) {
    // Validate cache name is present and non-empty
    String cacheName =
        cachedContent
            .name()
            .filter(name -> !name.trim().isEmpty())
            .orElseThrow(
                () ->
                    new IllegalStateException("Cache creation returned null or empty cache name"));

    // Validate cache name format
    if (!isValidCacheNameFormat(cacheName)) {
      throw new IllegalStateException(
          String.format("Cache creation returned invalid name format: %s", cacheName));
    }

    // Validate expiration time is set and in the future
    if (cachedContent.expireTime().isEmpty()) {
      throw new IllegalStateException("Cache creation returned no expiration time");
    }

    long expireTimeSeconds = cachedContent.expireTime().get().getEpochSecond();
    long currentTime = System.currentTimeMillis() / 1000;

    if (expireTimeSeconds <= currentTime) {
      throw new IllegalStateException(
          String.format(
              "Cache expiration time %d is not in the future (current: %d)",
              expireTimeSeconds, currentTime));
    }

    // Validate model matches (if provided in response)
    cachedContent
        .model()
        .ifPresent(
            returnedModel -> {
              if (!returnedModel.equals(expectedModel)
                  && !returnedModel.endsWith("/" + expectedModel)) {
                logger.warn(
                    "Cache created for different model. Expected: {}, Got: {}",
                    expectedModel,
                    returnedModel);
              }
            });

    logger.debug("Cache creation response validated successfully: {}", cacheName);
  }

  /**
   * Checks if cache name matches expected format.
   *
   * <p>Valid formats:
   *
   * <ul>
   *   <li>API key mode: {@code cachedContents/{id}}
   *   <li>Vertex AI: {@code projects/{project}/locations/{location}/cachedContents/{id}}
   * </ul>
   *
   * @param cacheName The cache name to validate
   * @return True if format is valid
   */
  private boolean isValidCacheNameFormat(String cacheName) {
    // API key format: cachedContents/{id}
    if (cacheName.matches("^cachedContents/[a-zA-Z0-9_-]+$")) {
      return true;
    }

    // Vertex AI format: projects/{project}/locations/{location}/cachedContents/{id}
    if (cacheName.matches("^projects/.+/locations/.+/cachedContents/[a-zA-Z0-9_-]+$")) {
      return true;
    }

    return false;
  }

  private long calculateExpireTime(ContextCacheConfig config) {
    return System.currentTimeMillis() / 1000 + config.ttlSeconds();
  }

  private void logCacheCreation(
      String cacheName, String modelName, int cacheContentsCount, ContextCacheConfig config) {
    logger.info(
        "Created cache {} for model {} ({} contents, expires in {}s)",
        cacheName,
        modelName,
        cacheContentsCount,
        config.ttlSeconds());
  }

  private void warnIfCacheCostIsHigh(int cacheContentsCount, ContextCacheConfig config) {
    if (cacheContentsCount > CACHE_SIZE_CRITICAL_THRESHOLD) {
      logCriticalCostWarning(cacheContentsCount, config);
    } else if (cacheContentsCount > CACHE_SIZE_WARNING_THRESHOLD) {
      logCostInfo(cacheContentsCount, config);
    }
  }

  private void logCriticalCostWarning(int cacheContentsCount, ContextCacheConfig config) {
    double estimatedCost = calculateStorageCost(cacheContentsCount, config.ttlSeconds());
    logger.warn(
        "Large cache created ({} tokens). Estimated storage cost: ${} for {}h TTL. "
            + "Consider reducing cache size or TTL to minimize costs.",
        cacheContentsCount,
        estimatedCost,
        config.ttlSeconds() / 3600.0);
  }

  private void logCostInfo(int cacheContentsCount, ContextCacheConfig config) {
    double estimatedCost = calculateStorageCost(cacheContentsCount, config.ttlSeconds());
    logger.info(
        "Cache size: {} tokens. Storage cost: ~${} for {}h TTL",
        cacheContentsCount,
        estimatedCost,
        config.ttlSeconds() / 3600.0);
  }

  private double calculateStorageCost(int tokenCount, int ttlSeconds) {
    return (tokenCount / 1_000_000.0) * (ttlSeconds / 3600.0);
  }

  private void recordCacheCreationMetrics(int cacheContentsCount, String cacheStrategy) {
    Telemetry.recordCacheCreation("cache-manager", cacheContentsCount, cacheStrategy);
  }

  private CacheMetadata buildCacheMetadata(
      String cacheName, String fingerprint, int cacheContentsCount, long expireTime) {
    long currentTimeSeconds = System.currentTimeMillis() / 1000;

    return CacheMetadata.builder()
        .cacheName(cacheName)
        .expireTime(expireTime)
        .fingerprint(fingerprint)
        .contentsCount(cacheContentsCount)
        .createdAt(currentTimeSeconds)
        .invocationsUsed(1) // Initialize to 1 (matches Python)
        .build();
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
              try {
                DeleteCachedContentConfig config = DeleteCachedContentConfig.builder().build();

                logger.debug("Attempting to delete cache: {}", cacheName);

                genaiClient
                    .async
                    .caches
                    .delete(cacheName, config)
                    .join(); // Block and wait for deletion

                logger.info("Successfully deleted cache: {}", cacheName);

              } catch (java.util.concurrent.CompletionException e) {
                Throwable cause = e.getCause();

                // Handle specific errors gracefully
                if (cause != null && cause.getMessage() != null) {
                  String message = cause.getMessage();

                  if (message.contains("404") || message.contains("NOT_FOUND")) {
                    logger.debug("Cache {} not found (may have already expired)", cacheName);
                    return;
                  }
                }

                // Log but don't throw - deletion failures are not critical
                logger.warn("Failed to delete cache {}: {}", cacheName, e.getMessage());

              } catch (Exception e) {
                // Log but don't throw - deletion failures are not critical
                logger.warn("Unexpected error deleting cache {}: {}", cacheName, e.getMessage());
              }
            })
        .subscribeOn(Schedulers.io());
  }

  /**
   * Validate if cache metadata is still valid.
   *
   * <p>Performs local validation without API calls using fingerprint comparison, expiration checks,
   * and invocation limits.
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

    if (!metadata.isActiveCache()) {
      logger.debug("Cache invalid: fingerprint-only metadata");
      return false;
    }

    if (isCacheExpired(metadata)) {
      logger.debug("Cache invalid: expired at {}", metadata.expireTime().orElse(0L));
      return false;
    }

    if (!isFingerprintMatching(metadata, llmRequest, cacheContentsCount)) {
      logger.debug("Cache invalid: fingerprint mismatch");
      return false;
    }

    // Check invocation limit (usage-based refresh)
    int invocationsUsed = metadata.invocationsUsed().orElse(1);
    if (invocationsUsed > cacheConfig.cacheIntervals()) {
      logger.info(
          "Cache exceeded invocation limit ({} > {} intervals), will refresh",
          invocationsUsed,
          cacheConfig.cacheIntervals());
      return false;
    }

    return true;
  }

  private boolean isCacheExpired(CacheMetadata metadata) {
    long currentTime = System.currentTimeMillis() / 1000;
    return currentTime >= metadata.expireTime().orElse(0L);
  }

  private boolean isFingerprintMatching(
      CacheMetadata metadata, LlmRequest llmRequest, int cacheContentsCount) {
    String currentFingerprint = generateCacheFingerprint(llmRequest, cacheContentsCount);
    return currentFingerprint.equals(metadata.fingerprint());
  }

  /**
   * Generate cache fingerprint for change detection.
   *
   * <p>Fingerprint is computed from: model + systemInstruction + tools + cacheable contents.
   *
   * <p>Including conversation contents in the fingerprint ensures per-session cache uniqueness.
   * Different conversations generate different fingerprints, preventing incorrect cache sharing
   * across sessions while allowing first requests (no conversation yet) to share a global cache.
   *
   * <p>Matches Python ADK implementation (gemini_context_cache_manager.py:226-271).
   *
   * @param llmRequest Request to generate fingerprint for
   * @param cacheContentsCount Number of contents to include in fingerprint
   * @return SHA-256 fingerprint string
   */
  public String generateCacheFingerprint(LlmRequest llmRequest, int cacheContentsCount) {
    String fingerprintInput = buildFingerprintInput(llmRequest, cacheContentsCount);
    String hash = hashFingerprint(fingerprintInput);

    logger.debug(
        "Generated fingerprint: {} (from {} chars)",
        hash.substring(0, Math.min(16, hash.length())),
        fingerprintInput.length());

    return hash;
  }

  private String buildFingerprintInput(LlmRequest llmRequest, int cacheContentsCount) {
    StringBuilder input = new StringBuilder();

    appendModelName(llmRequest, input);
    appendSystemInstructions(llmRequest, input);
    appendTools(llmRequest, input);
    appendCacheableContents(llmRequest, cacheContentsCount, input);

    return input.toString();
  }

  private void appendModelName(LlmRequest llmRequest, StringBuilder input) {
    llmRequest.model().ifPresent(model -> input.append("model:").append(model).append("|"));
  }

  private void appendSystemInstructions(LlmRequest llmRequest, StringBuilder input) {
    List<String> systemInstructions = llmRequest.getSystemInstructions();
    if (!systemInstructions.isEmpty()) {
      input.append("system:").append(String.join("\n", systemInstructions)).append("|");
    }
  }

  /**
   * Appends tool names to fingerprint input.
   *
   * <p>Uses tool names only (not full schemas) for deterministic fingerprinting across agent
   * instances. Tool schema changes require agent redeployment, so name-based fingerprinting is
   * sufficient for cache invalidation.
   *
   * <p>This approach ensures fingerprint stability when agents are recreated per-request with
   * identical configurations (common pattern for concurrency).
   *
   * @param llmRequest Request containing tools
   * @param input StringBuilder to append to
   */
  private void appendTools(LlmRequest llmRequest, StringBuilder input) {
    if (llmRequest.tools().isEmpty()) {
      return;
    }

    input.append("tools:");

    llmRequest.tools().keySet().stream()
        .sorted()
        .forEach(
            toolName -> {
              input.append(toolName).append(",");
            });

    input.append("|");
  }

  /**
   * Appends cacheable conversation contents to fingerprint input.
   *
   * <p>Includes first N contents from the request in the fingerprint to ensure per-session cache
   * uniqueness. Different conversations generate different fingerprints, preventing incorrect cache
   * sharing across sessions.
   *
   * <p>This matches Python ADK behavior where conversation contents are included in the cache
   * fingerprint (gemini_context_cache_manager.py:261-267).
   *
   * @param llmRequest Request containing conversation contents
   * @param cacheContentsCount Number of contents to include in fingerprint
   * @param input StringBuilder to append to
   */
  private void appendCacheableContents(
      LlmRequest llmRequest, int cacheContentsCount, StringBuilder input) {

    if (cacheContentsCount <= 0 || llmRequest.contents().isEmpty()) {
      return;
    }

    input.append("contents:");

    int contentsToInclude = Math.min(cacheContentsCount, llmRequest.contents().size());

    for (int i = 0; i < contentsToInclude; i++) {
      Content content = llmRequest.contents().get(i);

      // Use toString() for deterministic representation
      // Content.toString() is stable across instances with same data
      input.append(content.toString()).append("|");
    }
  }

  private String hashFingerprint(String input) {
    return Hashing.sha256().hashString(input, StandardCharsets.UTF_8).toString();
  }
}
