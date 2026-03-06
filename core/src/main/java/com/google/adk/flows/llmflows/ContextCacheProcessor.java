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

import com.google.adk.agents.ContextCacheConfig;
import com.google.adk.agents.Instruction;
import com.google.adk.agents.InvocationContext;
import com.google.adk.agents.LlmAgent;
import com.google.adk.agents.ReadonlyContext;
import com.google.adk.codeexecutors.BuiltInCodeExecutor;
import com.google.adk.events.Event;
import com.google.adk.models.BaseLlm;
import com.google.adk.models.Gemini;
import com.google.adk.models.LlmRegistry;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.cache.CacheMetadata;
import com.google.adk.models.cache.GeminiContextCacheManager;
import com.google.adk.telemetry.Tracing;
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
 *   <li>Checks if agent has context caching configured (resolves cache config from
 *       RunConfig.contextCacheConfig with fallback to InvocationContext.contextCacheConfig, and
 *       requires staticInstruction)
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

  private volatile GeminiContextCacheManager cacheManager;
  private volatile com.google.genai.Client cacheManagerClient;
  private final Object cacheManagerLock = new Object();

  /** Creates a processor that lazily initializes its cache manager on first use. */
  public ContextCacheProcessor() {
    this.cacheManager = null;
    this.cacheManagerClient = null;
  }

  /** Creates a processor with an explicit cache manager (for testing). */
  public ContextCacheProcessor(GeminiContextCacheManager cacheManager) {
    this.cacheManager = cacheManager;
    this.cacheManagerClient = null;
  }

  private GeminiContextCacheManager getOrInitCacheManager(LlmAgent agent) {
    if (cacheManager != null && cacheManagerClient == null) {
      return cacheManager;
    }

    com.google.genai.Client configuredClient = resolveGeminiClient(agent);
    if (configuredClient == null) {
      return null;
    }

    if (cacheManager != null && cacheManagerClient == configuredClient) {
      return cacheManager;
    }

    synchronized (cacheManagerLock) {
      if (cacheManager != null && cacheManagerClient == configuredClient) {
        return cacheManager;
      }
      try {
        cacheManager = createCacheManager(configuredClient, null);
        cacheManagerClient = configuredClient;
        logger.info("Initialized context cache manager from agent model configuration");
        return cacheManager;
      } catch (Exception e) {
        logger.error("Failed to initialize context cache manager", e);
        return null;
      }
    }
  }

  private com.google.genai.Client resolveGeminiClient(LlmAgent agent) {
    BaseLlm resolvedLlm =
        agent
            .resolvedModel()
            .model()
            .orElseGet(() -> LlmRegistry.getLlm(agent.resolvedModel().modelName().orElseThrow()));

    if (!(resolvedLlm instanceof Gemini geminiModel)) {
      logger.debug(
          "Context caching requires Gemini model, but resolved model is {} for agent {}",
          resolvedLlm.getClass().getSimpleName(),
          agent.name());
      return null;
    }

    return geminiModel.apiClient();
  }

  GeminiContextCacheManager createCacheManager(
      com.google.genai.Client client, @Nullable String projectId) {
    return new GeminiContextCacheManager(client, projectId, null);
  }

  @Override
  public Single<RequestProcessingResult> processRequest(
      InvocationContext context, LlmRequest request) {

    if (!(context.agent() instanceof LlmAgent agent)) {
      return passThrough(request);
    }

    if (!isCachingEnabled(context, agent, request)) {
      logger.debug("Context caching not configured for agent: {}", agent.name());
      return passThrough(request);
    }

    GeminiContextCacheManager manager = getOrInitCacheManager(agent);
    if (manager == null) {
      logger.debug("Cache manager unavailable, skipping cache for agent: {}", agent.name());
      return passThrough(request);
    }

    logger.debug("Processing context cache for agent: {}", agent.name());

    ContextCacheConfig cacheConfig = resolveEffectiveCacheConfig(context).get();

    CacheMetadata existingMetadata =
        findLatestCacheMetadata(
            context, agent.name(), context.invocationId(), context.branch(), manager);

    int cacheContentsCount;
    if (existingMetadata != null && existingMetadata.isActiveCache()) {
      cacheContentsCount = existingMetadata.contentsCount();
    } else {
      cacheContentsCount = calculateCacheContentsCount(request);
    }

    LlmRequest requestWithCacheInfo =
        buildRequestWithCacheInfo(request, cacheConfig, existingMetadata);

    // Populate tools for cache creation (tools are added after processors in BaseLlmFlow)
    return populateToolsForCache(requestWithCacheInfo, agent, context)
        .flatMap(
            requestWithTools -> {
              boolean hasActiveExistingCache =
                  existingMetadata != null && existingMetadata.isActiveCache();
              if (!hasActiveExistingCache && !meetsTokenThreshold(requestWithTools, cacheConfig)) {
                logger.debug(
                    "Request does not meet minimum token threshold ({}) after tool"
                        + " population, skipping cache",
                    cacheConfig.minTokens());
                return passThrough(request);
              }
              return manager
                  .handleContextCaching(requestWithTools, cacheContentsCount, null)
                  .map(
                      cacheMetadata ->
                          handleCachingSuccess(requestWithTools, cacheConfig, cacheMetadata, agent))
                  .onErrorResumeNext(error -> handleCachingError(request, agent, error));
            });
  }

  /**
   * Resolves the effective cache config by checking RunConfig first, then falling back to the
   * App-level config on InvocationContext. RunConfig takes precedence when both are present.
   */
  private Optional<ContextCacheConfig> resolveEffectiveCacheConfig(InvocationContext context) {
    Optional<ContextCacheConfig> runConfigCache = context.runConfig().contextCacheConfig();
    if (runConfigCache.isPresent()) {
      return runConfigCache;
    }
    return context.contextCacheConfig();
  }

  private boolean isCachingEnabled(InvocationContext context, LlmAgent agent, LlmRequest request) {
    Optional<ContextCacheConfig> effectiveCacheConfig = resolveEffectiveCacheConfig(context);
    if (effectiveCacheConfig.isEmpty() || !hasStableInstructionAnchor(agent)) {
      return false;
    }
    if (!hasNonEmptySystemInstruction(agent, request)) {
      return false;
    }
    if (agent.codeExecutor().isPresent()
        && !(agent.codeExecutor().get() instanceof BuiltInCodeExecutor)) {
      logger.debug(
          "Context caching disabled for non-built-in code executor on agent '{}'", agent.name());
      return false;
    }
    ContextCacheConfig cacheConfig = effectiveCacheConfig.get();
    if (cacheConfig.maxInvocations() <= 0
        || cacheConfig.ttl().isZero()
        || cacheConfig.ttl().isNegative()) {
      logger.debug("Context caching explicitly disabled for this run on agent '{}'", agent.name());
      return false;
    }
    if (context.runConfig().streamingMode() == com.google.adk.agents.RunConfig.StreamingMode.BIDI) {
      logger.debug("Context caching disabled for live/BIDI mode on agent '{}'", agent.name());
      return false;
    }
    if (agent.exampleProvider().isPresent()) {
      logger.warn(
          "Context caching is incompatible with exampleProvider on agent '{}'. "
              + "Examples are per-query dynamic content that cannot be cached. "
              + "Caching is disabled for this agent.",
          agent.name());
      return false;
    }
    return true;
  }

  private boolean hasStableInstructionAnchor(LlmAgent agent) {
    if (agent.staticInstruction().isPresent()) {
      return true;
    }
    return agent.instruction() instanceof Instruction.Static;
  }

  private boolean hasNonEmptySystemInstruction(LlmAgent agent, LlmRequest request) {
    if (request.getFirstSystemInstruction().map(text -> !text.isBlank()).orElse(false)) {
      return true;
    }

    if (agent.staticInstruction().isPresent()
        && agent.staticInstruction().get() instanceof Instruction.Static staticInstruction
        && !staticInstruction.instruction().isBlank()) {
      return true;
    }

    return agent.instruction() instanceof Instruction.Static staticInstruction
        && !staticInstruction.instruction().isBlank();
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
      InvocationContext context,
      String agentName,
      String currentInvocationId,
      Optional<String> currentBranch,
      GeminiContextCacheManager manager) {

    List<Event> events = context.session().events();

    CacheMetadata mostRecentMetadata = null;
    CacheMetadata mostRecentActiveMetadata = null;

    for (int i = events.size() - 1; i >= 0; i--) {
      Event event = events.get(i);

      if (!isMatchingAgentEvent(event, agentName, currentBranch)) {
        continue;
      }

      if (event.cacheMetadata().isEmpty()) {
        continue;
      }

      CacheMetadata metadata = event.cacheMetadata().get();

      if (mostRecentMetadata == null) {
        mostRecentMetadata = metadata;
      }

      if (metadata.isActiveCache()) {
        if (mostRecentActiveMetadata == null) {
          mostRecentActiveMetadata = metadata;
        }
      }
    }

    if (mostRecentMetadata != null
        && !mostRecentMetadata.isActiveCache()
        && mostRecentActiveMetadata != null
        && mostRecentActiveMetadata.fingerprint().equals(mostRecentMetadata.fingerprint())
        && isReusableActiveMetadata(mostRecentActiveMetadata, context)) {
      mostRecentMetadata = mostRecentActiveMetadata;
    }

    if (mostRecentMetadata == null) {
      logger.debug("No cache metadata found in session events for agent: {}", agentName);
      return null;
    }

    // If active cache found, check for duplicates with different cache names
    if (mostRecentMetadata.isActiveCache()) {
      detectAndCleanupDuplicateCaches(
          events, agentName, currentBranch, mostRecentMetadata, manager);

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
      List<Event> events,
      String agentName,
      Optional<String> currentBranch,
      CacheMetadata currentMetadata,
      GeminiContextCacheManager manager) {

    // Collect all UNIQUE cache names for this agent
    List<CacheMetadata> uniqueCaches = new ArrayList<>();
    List<String> seenCacheNames = new ArrayList<>();
    String currentFingerprint = currentMetadata.fingerprint();

    for (Event event : events) {
      if (!isMatchingAgentEvent(event, agentName, currentBranch)) {
        continue;
      }

      if (event.cacheMetadata().isEmpty()) {
        continue;
      }

      CacheMetadata metadata = event.cacheMetadata().get();

      if (!metadata.isActiveCache()) {
        continue; // Skip fingerprint-only
      }

      if (!currentFingerprint.equals(metadata.fingerprint())) {
        continue;
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

      Tracing.recordCacheFragmentation(agentName, uniqueCaches.size());

      cleanupDuplicateCaches(uniqueCaches, currentMetadata, manager);
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
      List<CacheMetadata> uniqueCaches,
      CacheMetadata currentMetadata,
      GeminiContextCacheManager manager) {

    String currentCacheName = currentMetadata.cacheName().get();

    logger.info("Keeping current cache: {}", currentCacheName);

    // Delete all OTHER caches (not the current one)
    uniqueCaches.stream()
        .filter(cache -> !cache.cacheName().get().equals(currentCacheName))
        .forEach(
            olderCache -> {
              String olderCacheName = olderCache.cacheName().get();
              logger.info("Deleting duplicate cache: {}", olderCacheName);

              manager
                  .deleteCache(olderCacheName)
                  .subscribe(
                      () -> {
                        logger.info("Successfully deleted duplicate cache: {}", olderCacheName);
                        Tracing.recordCacheDeletion("duplicate-cleanup", olderCacheName);
                      },
                      error ->
                          logger.error(
                              "Failed to delete duplicate cache: {}", olderCacheName, error));
            });
  }

  private boolean isMatchingAgentEvent(
      Event event, String agentName, Optional<String> currentBranch) {
    if (!agentName.equals(event.author())) {
      return false;
    }
    return isVisibleInCurrentBranch(currentBranch, event.branch());
  }

  private boolean isVisibleInCurrentBranch(
      Optional<String> invocationBranchOpt, Optional<String> eventBranchOpt) {
    if (invocationBranchOpt.isEmpty() || invocationBranchOpt.get().isEmpty()) {
      return true;
    }
    if (eventBranchOpt.isEmpty() || eventBranchOpt.get().isEmpty()) {
      return true;
    }
    return invocationBranchOpt.get().startsWith(eventBranchOpt.get());
  }

  private boolean isReusableActiveMetadata(
      CacheMetadata activeMetadata, InvocationContext context) {
    if (!activeMetadata.isActiveCache()) {
      return false;
    }

    long currentTime = System.currentTimeMillis() / 1000;
    if (activeMetadata.expireTime().isPresent()
        && currentTime >= activeMetadata.expireTime().get()) {
      return false;
    }

    Optional<ContextCacheConfig> cacheConfig = resolveEffectiveCacheConfig(context);
    if (cacheConfig.isPresent()) {
      int invocationsUsed = activeMetadata.invocationsUsed().orElse(1);
      if (invocationsUsed > cacheConfig.get().maxInvocations()) {
        return false;
      }
    }

    return true;
  }

  /**
   * Calculate how many contents should be cached.
   *
   * <p>Cacheable contents include conversation history up to (but not including) the last user
   * message batch.
   *
   * <p><b>Important:</b> System instruction is NOT counted here because it is stored separately in
   * {@code GenerateContentConfig.systemInstruction()}, not in {@code llmRequest.contents()}. The
   * count returned here is used to slice {@code llmRequest.contents()} in {@code
   * Gemini.generateContent()}, so it must only reflect conversation contents.
   *
   * <p>The last continuous batch of user messages is NOT cached as it represents dynamic user input
   * that changes frequently.
   *
   * <p><b>Examples:</b>
   *
   * <ul>
   *   <li>First request: Returns 0 (no conversation history yet)
   *   <li>After 1 turn: Returns 0 (current user batch excludes history)
   *   <li>After 2 turns: Returns 2 (first turn's user + model events)
   * </ul>
   *
   * @param context Invocation context
   * @return Number of conversation contents to include in cache (excludes system instruction)
   */
  private int calculateCacheContentsCount(LlmRequest request) {
    List<Content> contents = request.contents();

    if (contents.isEmpty()) {
      logger.debug("First request: cacheable count = 0 (no conversation history)");
      return 0;
    }

    int lastUserBatchStart = findLastUserContentBatchStart(contents);

    logger.debug(
        "Cacheable contents: {} (history before last user batch, total contents: {})",
        lastUserBatchStart,
        contents.size());

    return lastUserBatchStart;
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

  private int findLastUserContentBatchStart(List<Content> contents) {
    int batchStart = contents.size();

    for (int i = contents.size() - 1; i >= 0; i--) {
      if (isUserContent(contents.get(i))) {
        batchStart = i;
      } else {
        break;
      }
    }

    return batchStart;
  }

  private boolean isUserContent(Content content) {
    return content.role().isPresent() && "user".equals(content.role().get());
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

    LlmRequest.Builder builder = request.toBuilder();

    return agent
        .canonicalTools(new ReadonlyContext(context))
        .concatMapCompletable(
            tool ->
                tool.processLlmRequest(builder, ToolContext.builder(context).build())
                    .doOnError(
                        e ->
                            logger.warn(
                                "Failed to process tool {} for cache creation: {}",
                                tool.name(),
                                e.getMessage())))
        .andThen(
            Single.fromCallable(
                () -> {
                  if (agent.codeExecutor().isPresent()
                      && agent.codeExecutor().get() instanceof BuiltInCodeExecutor builtIn) {
                    builtIn.processLlmRequest(builder);
                    logger.debug("Included built-in code-execution tool in cache creation");
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
                }));
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
