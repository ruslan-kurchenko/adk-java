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

import com.google.adk.agents.InvocationContext;
import com.google.adk.agents.LlmAgent;
import com.google.adk.apps.ContextCacheConfig;
import com.google.adk.events.Event;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.cache.CacheMetadata;
import com.google.adk.models.cache.GeminiContextCacheManager;
import com.google.common.collect.ImmutableList;
import io.reactivex.rxjava3.core.Single;
import java.util.List;
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
    CacheMetadata existingMetadata =
        findLatestCacheMetadata(context, agent.name(), context.invocationId());
    int cacheContentsCount = calculateCacheContentsCount(context);

    LlmRequest requestWithCacheInfo = buildRequestWithCacheInfo(request, cacheConfig, existingMetadata);

    return cacheManager
        .handleContextCaching(requestWithCacheInfo, cacheContentsCount)
        .map(cacheMetadata -> handleCachingSuccess(request, cacheConfig, cacheMetadata, agent))
        .onErrorResumeNext(error -> handleCachingError(request, agent, error));
  }

  private boolean isCachingEnabled(InvocationContext context, LlmAgent agent) {
    return context.runConfig().contextCacheConfig().isPresent()
        && agent.staticInstruction().isPresent();
  }

  private LlmRequest buildRequestWithCacheInfo(
      LlmRequest request, ContextCacheConfig cacheConfig, @Nullable CacheMetadata existingMetadata) {

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

    return Single.just(passThrough(request).blockingGet());
  }

  private Single<RequestProcessingResult> passThrough(LlmRequest request) {
    return Single.just(RequestProcessingResult.create(request, ImmutableList.of()));
  }

  /**
   * Find the latest cache metadata from session events for this agent.
   *
   * <p>Scans backward through session events to find the most recent cache metadata. Increments
   * invocations counter for active caches from different invocations.
   *
   * @param context Invocation context containing session events
   * @param agentName Name of the agent to find metadata for
   * @param currentInvocationId Current invocation ID
   * @return Cache metadata if found, null otherwise
   */
  @Nullable
  private CacheMetadata findLatestCacheMetadata(
      InvocationContext context, String agentName, String currentInvocationId) {

    List<Event> events = context.session().events();

    for (int i = events.size() - 1; i >= 0; i--) {
      Event event = events.get(i);

      if (!isMatchingAgentEvent(event, agentName)) {
        continue;
      }

      if (event.cacheMetadata().isEmpty()) {
        continue;
      }

      return processFoundMetadata(event, currentInvocationId);
    }

    logger.debug("No cache metadata found in session events for agent: {}", agentName);
    return null;
  }

  private boolean isMatchingAgentEvent(Event event, String agentName) {
    return agentName.equals(event.author());
  }

  private CacheMetadata processFoundMetadata(Event event, String currentInvocationId) {
    CacheMetadata metadata = event.cacheMetadata().get();

    if (shouldIncrementInvocations(event, currentInvocationId, metadata)) {
      return incrementInvocations(metadata);
    }

    logger.debug("Found cache metadata: {}", metadata.toString());
    return metadata;
  }

  private boolean shouldIncrementInvocations(
      Event event, String currentInvocationId, CacheMetadata metadata) {
    return event.invocationId() != null
        && !event.invocationId().equals(currentInvocationId)
        && metadata.isActiveCache();
  }

  private CacheMetadata incrementInvocations(CacheMetadata metadata) {
    int currentCount = metadata.invocationsUsed().orElse(0);
    int newCount = currentCount + 1;

    logger.debug("Incrementing cache invocations: {} -> {}", currentCount, newCount);

    return metadata.toBuilder().invocationsUsed(newCount).build();
  }

  /**
   * Calculate how many contents should be cached.
   *
   * <p>Per Python ADK: cache everything before the last continuous batch of user messages. This
   * ensures dynamic user input is not cached while static context is.
   *
   * @param context Invocation context
   * @return Number of contents to include in cache
   */
  private int calculateCacheContentsCount(InvocationContext context) {
    List<Event> events = context.session().events();

    if (events.isEmpty()) {
      return 0;
    }

    int lastUserBatchStart = findLastUserMessageBatchStart(events);
    int cacheableCount = lastUserBatchStart;

    logger.debug(
        "Cacheable contents: {} (total: {}, user batch at: {})",
        cacheableCount,
        events.size(),
        lastUserBatchStart);

    return cacheableCount;
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
}
