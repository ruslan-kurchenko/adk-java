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

package com.google.adk.flows.llmflows;

import com.google.adk.Telemetry;
import com.google.adk.agents.InvocationContext;
import com.google.adk.agents.LlmAgent;
import com.google.adk.agents.ReadonlyContext;
import com.google.adk.apps.ContextCacheConfig;
import com.google.adk.events.Event;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.cache.CacheMetadata;
import com.google.adk.models.cache.GeminiContextCacheManager;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.ToolContext;
import com.google.common.collect.ImmutableList;
import com.google.genai.types.Content;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Single;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Request processor that implements context caching for LlmAgent.
 *
 * <p>This processor:
 *
 * <ol>
 *   <li>Checks if agent has context caching configured (RunConfig.contextCacheConfig +
 *       staticInstruction)
 *   <li>Retrieves latest cache metadata from session events
 *   <li>Calls GeminiContextCacheManager to validate/create cache
 *   <li>Injects cache metadata into LlmRequest for downstream processing
 * </ol>
 *
 * <p>Cache metadata flows through session events for horizontal scaling support across multiple
 * pods.
 *
 * @since 0.4.0
 */
public final class ContextCacheProcessor implements RequestProcessor {

  private static final Logger logger = LoggerFactory.getLogger(ContextCacheProcessor.class);

  private final GeminiContextCacheManager cacheManager;

  public ContextCacheProcessor(GeminiContextCacheManager cacheManager) {
    this.cacheManager = cacheManager;
  }

  @Override
  public Single<RequestProcessingResult> processRequest(
      InvocationContext context, LlmRequest request) {

    if (!(context.agent() instanceof LlmAgent agent)) {
      return passThrough(request);
    }

    if (!isCachingEnabled(context, agent)) {
      logger.debug("Context caching not configured for agent: {}", agent.name());
      return passThrough(request);
    }

    logger.debug("Processing context cache for agent: {}", agent.name());

    ContextCacheConfig cacheConfig = context.runConfig().contextCacheConfig().get();

    // Check minimum token threshold
    if (!meetsTokenThreshold(request, cacheConfig)) {
      logger.debug(
          "Request does not meet minimum token threshold ({}), skipping cache",
          cacheConfig.minTokens());
      return passThrough(request);
    }

    CacheMetadata existingMetadata =
        findLatestCacheMetadata(context, agent.name(), context.invocationId());

    // Use count from existing metadata (fingerprint-only OR active cache) for stable fingerprint
    int cacheContentsCount;
    if (existingMetadata != null) {
      // ALWAYS use count from metadata for consistent fingerprint matching
      // Cache fingerprint is "frozen" at creation time and must remain stable
      cacheContentsCount = existingMetadata.contentsCount();
      logger.debug("Using contents count from metadata: {}", cacheContentsCount);
    } else {
      // No existing metadata - calculate fresh count for new fingerprint-only
      cacheContentsCount = calculateCacheContentsCount(context);
    }

    LlmRequest requestWithCacheInfo =
        buildRequestWithCacheInfo(request, cacheConfig, existingMetadata);

    // Populate tools for cache creation (tools are added after processors in BaseLlmFlow)
    return populateToolsForCache(requestWithCacheInfo, agent, context)
        .flatMap(
            requestWithTools ->
                cacheManager
                    .handleContextCaching(requestWithTools, cacheContentsCount)
                    .map(
                        cacheMetadata ->
                            handleCachingSuccess(request, cacheConfig, cacheMetadata, agent))
                    .onErrorResumeNext(error -> handleCachingError(request, agent, error)));
  }

  private boolean isCachingEnabled(InvocationContext context, LlmAgent agent) {
    return context.runConfig().contextCacheConfig().isPresent()
        && agent.staticInstruction().isPresent();
  }

  private LlmRequest buildRequestWithCacheInfo(
      LlmRequest request,
      ContextCacheConfig cacheConfig,
      @Nullable CacheMetadata existingMetadata) {

    LlmRequest.Builder builder = request.toBuilder().cacheConfig(cacheConfig);

    if (existingMetadata != null) {
      builder.cacheMetadata(existingMetadata);
    }

    return builder.build();
  }

  private RequestProcessingResult handleCachingSuccess(
      LlmRequest request,
      ContextCacheConfig cacheConfig,
      CacheMetadata cacheMetadata,
      LlmAgent agent) {

    logger.debug(
        "Cache metadata for agent {}: {}",
        agent.name(),
        cacheMetadata.isActiveCache()
            ? "active cache " + cacheMetadata.cacheName().get()
            : "fingerprint-only");

    // Contents removal is handled in Gemini.java right before API call
    // (request here doesn't have contents populated yet)
    LlmRequest updatedRequest =
        request.toBuilder()
            .cacheConfig(cacheConfig)
            .cacheMetadata(cacheMetadata)
            .cacheableContentsTokenCount(0)
            .build();

    return RequestProcessingResult.create(updatedRequest, ImmutableList.of());
  }

  private Single<RequestProcessingResult> handleCachingError(
      LlmRequest request, LlmAgent agent, Throwable error) {

    logger.error(
        "Cache operation failed for agent: {}, proceeding without cache", agent.name(), error);

    return passThrough(request);
  }

  private Single<RequestProcessingResult> passThrough(LlmRequest request) {
    return Single.just(RequestProcessingResult.create(request, ImmutableList.of()));
  }

  /**
   * Find the latest cache metadata from session events for this agent.
   *
   * <p>This method handles the race condition where multiple pods may create duplicate caches:
   *
   * <ol>
   *   <li>Scans session events to find the most recent cache metadata for this agent
   *   <li>Detects if multiple DIFFERENT caches exist (by cache name)
   *   <li>If duplicates found: triggers cleanup (keeps newest, deletes others)
   *   <li>Returns the most recent cache metadata
   * </ol>
   *
   * <p><b>Race Condition Handling:</b> When two pods simultaneously create caches, both cache
   * metadata entries persist in session events with different cache names. This method detects the
   * duplicate cache names and asynchronously cleans up older caches, preventing resource leaks and
   * storage cost waste.
   *
   * <p><b>Note:</b> Multiple events may reference the SAME cache (same cache name) - this is normal
   * as cache metadata is appended to events. Only different cache names indicate true duplicates.
   *
   * @param context Invocation context containing session events
   * @param agentName Name of the agent to find metadata for
   * @param currentInvocationId Current invocation ID (currently unused, reserved for future)
   * @return Cache metadata if found, null otherwise
   */
  @Nullable
  private CacheMetadata findLatestCacheMetadata(
      InvocationContext context, String agentName, String currentInvocationId) {

    List<Event> events = context.session().events();

    // Find most recent cache metadata (active or fingerprint-only)
    CacheMetadata mostRecentMetadata = null;

    for (int i = events.size() - 1; i >= 0; i--) {
      Event event = events.get(i);

      if (!isMatchingAgentEvent(event, agentName)) {
        continue;
      }

      if (event.cacheMetadata().isEmpty()) {
        continue;
      }

      CacheMetadata metadata = event.cacheMetadata().get();

      // Only process active caches for duplicate detection
      if (metadata.isActiveCache()) {
        if (mostRecentMetadata == null) {
          mostRecentMetadata = metadata;
        }
        // Don't break - continue scanning to find all unique caches
      } else if (mostRecentMetadata == null) {
        // Found fingerprint-only metadata and no active cache yet
        mostRecentMetadata = metadata;
      }
    }

    if (mostRecentMetadata == null) {
      logger.debug("No cache metadata found in session events for agent: {}", agentName);
      return null;
    }

    // If active cache found, check for duplicates with different cache names
    if (mostRecentMetadata.isActiveCache()) {
      detectAndCleanupDuplicateCaches(events, agentName, mostRecentMetadata);

      // Increment invocations if this is a different invocation
      mostRecentMetadata =
          incrementInvocationsIfNeeded(mostRecentMetadata, events, currentInvocationId);
    }

    logger.debug("Using cache metadata: {}", mostRecentMetadata.toString());
    return mostRecentMetadata;
  }

  /**
   * Detects and cleans up duplicate caches with different cache names.
   *
   * <p>Scans all events to find caches with different names for the same agent. Multiple events may
   * reference the SAME cache (same cache name) - this is normal. Only different cache names
   * indicate true duplicates from race conditions.
   *
   * @param events All session events
   * @param agentName Agent to check for duplicates
   * @param currentMetadata The current cache being used
   */
  private void detectAndCleanupDuplicateCaches(
      List<Event> events, String agentName, CacheMetadata currentMetadata) {

    // Collect all UNIQUE cache names for this agent
    List<CacheMetadata> uniqueCaches = new ArrayList<>();
    List<String> seenCacheNames = new ArrayList<>();

    for (Event event : events) {
      if (!isMatchingAgentEvent(event, agentName)) {
        continue;
      }

      if (event.cacheMetadata().isEmpty()) {
        continue;
      }

      CacheMetadata metadata = event.cacheMetadata().get();

      if (!metadata.isActiveCache()) {
        continue; // Skip fingerprint-only
      }

      String cacheName = metadata.cacheName().get();

      // Only add if we haven't seen this cache name before
      if (!seenCacheNames.contains(cacheName)) {
        seenCacheNames.add(cacheName);
        uniqueCaches.add(metadata);
      }
    }

    // Check for duplicates (different cache names)
    if (uniqueCaches.size() > 1) {
      logger.warn(
          "Detected {} duplicate caches for agent {}, cleaning up", uniqueCaches.size(), agentName);

      Telemetry.recordCacheFragmentation(agentName, uniqueCaches.size());

      cleanupDuplicateCaches(uniqueCaches, currentMetadata);
    }
  }

  /**
   * Cleans up duplicate caches by deleting all except the current one.
   *
   * <p>This handles the race condition where multiple pods simultaneously create caches for the
   * same fingerprint. The cleanup is asynchronous and best-effort - deletion failures are logged
   * but don't block the request.
   *
   * @param uniqueCaches List of caches with different cache names
   * @param currentMetadata The cache currently being used
   */
  private void cleanupDuplicateCaches(
      List<CacheMetadata> uniqueCaches, CacheMetadata currentMetadata) {

    String currentCacheName = currentMetadata.cacheName().get();

    logger.info("Keeping current cache: {}", currentCacheName);

    // Delete all OTHER caches (not the current one)
    uniqueCaches.stream()
        .filter(cache -> !cache.cacheName().get().equals(currentCacheName))
        .forEach(
            olderCache -> {
              String olderCacheName = olderCache.cacheName().get();
              logger.info("Deleting duplicate cache: {}", olderCacheName);

              cacheManager
                  .deleteCache(olderCacheName)
                  .subscribe(
                      () -> {
                        logger.info("Successfully deleted duplicate cache: {}", olderCacheName);
                        Telemetry.recordCacheDeletion("duplicate-cleanup", olderCacheName);
                      },
                      error ->
                          logger.error(
                              "Failed to delete duplicate cache: {}", olderCacheName, error));
            });
  }

  private boolean isMatchingAgentEvent(Event event, String agentName) {
    return agentName.equals(event.author());
  }

  /**
   * Calculate how many contents should be cached.
   *
   * <p>Cacheable contents include:
   *
   * <ol>
   *   <li>System instruction (always cached if present) - counts as 1
   *   <li>Conversation history up to (but not including) the last user message batch
   * </ol>
   *
   * <p>The last continuous batch of user messages is NOT cached as it represents dynamic user input
   * that changes frequently.
   *
   * <p><b>Examples:</b>
   *
   * <ul>
   *   <li>First request: Returns 1 (system instruction only)
   *   <li>After 1 turn: Returns 1 (system instruction, current user batch excludes history)
   *   <li>After 2 turns: Returns 3 (system instruction + first turn's 2 events)
   * </ul>
   *
   * @param context Invocation context
   * @return Number of contents to include in cache (minimum 1 if system instruction present)
   */
  private int calculateCacheContentsCount(InvocationContext context) {
    List<Event> events = context.session().events();

    // Always include system instruction in cacheable count (if present)
    int cacheableCount = hasSystemInstruction(context) ? 1 : 0;

    if (events.isEmpty()) {
      logger.debug("First request: cacheable count = {} (system instruction only)", cacheableCount);
      return cacheableCount;
    }

    // Find where the last user message batch starts
    int lastUserBatchStart = findLastUserMessageBatchStart(events);

    // Cache everything BEFORE the last user batch + system instruction
    cacheableCount += lastUserBatchStart;

    logger.debug(
        "Cacheable contents: {} (system: {}, history: {}, total events: {})",
        cacheableCount,
        hasSystemInstruction(context) ? 1 : 0,
        lastUserBatchStart,
        events.size());

    return cacheableCount;
  }

  /**
   * Checks if the agent has a system instruction configured.
   *
   * @param context Invocation context
   * @return True if system instruction is present and non-empty
   */
  private boolean hasSystemInstruction(InvocationContext context) {
    if (!(context.agent() instanceof LlmAgent agent)) {
      return false;
    }

    return agent.staticInstruction().isPresent();
  }

  private int findLastUserMessageBatchStart(List<Event> events) {
    int batchStart = events.size();

    for (int i = events.size() - 1; i >= 0; i--) {
      if (isUserMessage(events.get(i))) {
        batchStart = i;
      } else {
        break;
      }
    }

    return batchStart;
  }

  private boolean isUserMessage(Event event) {
    return "user".equals(event.author());
  }

  /**
   * Checks if request meets minimum token threshold for caching.
   *
   * <p>Uses estimated token count from request or previous response. The minTokens threshold helps
   * avoid caching overhead for small requests where storage costs may exceed benefits.
   *
   * <p><b>Token Estimation Strategy:</b>
   *
   * <ol>
   *   <li>If cacheableContentsTokenCount is set (from previous response): Use it
   *   <li>Otherwise: Estimate from system instruction and content lengths (~4 chars per token)
   *   <li>If minTokens=0: Always allow caching (no threshold)
   * </ol>
   *
   * @param request Request to check
   * @param cacheConfig Cache configuration
   * @return True if threshold is met or no threshold configured
   */
  private boolean meetsTokenThreshold(LlmRequest request, ContextCacheConfig cacheConfig) {
    int minTokens = cacheConfig.minTokens();

    if (minTokens == 0) {
      return true; // No threshold configured
    }

    // Try to get token count from previous response
    Optional<Integer> tokenCount = request.cacheableContentsTokenCount();

    if (tokenCount.isPresent()) {
      boolean meetsThreshold = tokenCount.get() >= minTokens;

      if (!meetsThreshold) {
        logger.info(
            "Request does not meet minimum token threshold. Tokens: {}, Required: {}",
            tokenCount.get(),
            minTokens);
      }

      return meetsThreshold;
    }

    // No previous token count - estimate from content
    int estimatedTokens = estimateTokenCount(request);

    boolean meetsThreshold = estimatedTokens >= minTokens;

    if (!meetsThreshold) {
      logger.debug(
          "Estimated request tokens {} below threshold {}, skipping cache",
          estimatedTokens,
          minTokens);
    } else {
      logger.debug(
          "Estimated request tokens {} meets threshold {}, proceeding with cache",
          estimatedTokens,
          minTokens);
    }

    return meetsThreshold;
  }

  /**
   * Estimates token count for a request.
   *
   * <p>Uses rough approximation: 1 token ≈ 4 characters for English text. This is used when no
   * actual token count is available from previous responses.
   *
   * @param request Request to estimate
   * @return Estimated token count
   */
  private int estimateTokenCount(LlmRequest request) {
    // Use array to allow modification in lambda
    final int[] totalChars = {0};

    // System instruction
    totalChars[0] += String.join("", request.getSystemInstructions()).length();

    // Contents (conversation history)
    for (Content content : request.contents()) {
      content
          .parts()
          .ifPresent(
              parts -> {
                for (Part part : parts) {
                  totalChars[0] += part.text().orElse("").length();
                }
              });
    }

    // Tool declarations (rough estimate: ~200 chars per tool)
    totalChars[0] += request.tools().size() * 200;

    // Convert chars to tokens (rough: 4 chars = 1 token)
    int estimatedTokens = totalChars[0] / 4;

    logger.debug("Estimated {} tokens from {} characters", estimatedTokens, totalChars[0]);

    return estimatedTokens;
  }

  /**
   * Populates tools in request config for cache creation.
   *
   * <p>Tools are normally added AFTER RequestProcessors run (in BaseLlmFlow.preprocess). But cache
   * creation happens IN a RequestProcessor, so we need to pre-populate tools to ensure they're
   * included in the cache.
   *
   * @param request Request to populate tools in
   * @param agent Agent containing tools
   * @param context Invocation context
   * @return Single emitting request with tools populated
   */
  private Single<LlmRequest> populateToolsForCache(
      LlmRequest request, LlmAgent agent, InvocationContext context) {

    return agent
        .canonicalTools(new ReadonlyContext(context))
        .toList()
        .map(
            tools -> {
              if (tools.isEmpty()) {
                logger.debug("No tools to add for cache creation");
                return request;
              }

              LlmRequest.Builder builder = request.toBuilder();

              // Process each tool to add to request config
              for (BaseTool tool : tools) {
                try {
                  tool.processLlmRequest(builder, ToolContext.builder(context).build());
                } catch (Exception e) {
                  logger.warn(
                      "Failed to process tool {} for cache creation: {}",
                      tool.name(),
                      e.getMessage());
                }
              }

              LlmRequest requestWithTools = builder.build();

              logger.debug(
                  "Populated {} tools for cache creation",
                  requestWithTools
                      .config()
                      .flatMap(GenerateContentConfig::tools)
                      .map(List::size)
                      .orElse(0));

              return requestWithTools;
            });
  }

  /**
   * Increments cache invocations count if this is a different invocation.
   *
   * <p>Prevents double-counting by only incrementing when the current invocation ID differs from
   * the last event that used this cache.
   *
   * <p>Matches Python ADK behavior (context_cache_processor.py:136-140).
   *
   * @param metadata Current cache metadata
   * @param events Session events to find last invocation ID
   * @param currentInvocationId Current request's invocation ID
   * @return Updated metadata with incremented invocations, or original if same invocation
   */
  private CacheMetadata incrementInvocationsIfNeeded(
      CacheMetadata metadata, List<Event> events, String currentInvocationId) {

    String cacheName = metadata.cacheName().get();

    // Find the last event that used this cache
    String lastInvocationId = findLastInvocationIdForCache(events, cacheName);

    // Only increment if this is a different invocation
    if (lastInvocationId != null && !currentInvocationId.equals(lastInvocationId)) {
      int currentInvocations = metadata.invocationsUsed().orElse(1);
      int newInvocations = currentInvocations + 1;

      logger.debug(
          "Incrementing cache invocations: {} -> {} (different invocation)",
          currentInvocations,
          newInvocations);

      return metadata.toBuilder().invocationsUsed(newInvocations).build();
    }

    // Same invocation - don't increment
    logger.debug("Same invocation, not incrementing cache usage count");
    return metadata;
  }

  /**
   * Finds the invocation ID of the last event that used this cache.
   *
   * @param events Session events to search
   * @param cacheName Cache name to search for
   * @return Last invocation ID that used this cache, or null if not found
   */
  @Nullable
  private String findLastInvocationIdForCache(List<Event> events, String cacheName) {
    for (int i = events.size() - 1; i >= 0; i--) {
      Event event = events.get(i);

      if (event.cacheMetadata().isEmpty()) {
        continue;
      }

      CacheMetadata eventMetadata = event.cacheMetadata().get();

      if (eventMetadata.cacheName().isPresent()
          && eventMetadata.cacheName().get().equals(cacheName)) {
        return event.invocationId();
      }
    }

    return null;
  }
}
