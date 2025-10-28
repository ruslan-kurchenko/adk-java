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

package com.google.adk.utils.cache;

import com.google.adk.events.Event;
import com.google.adk.models.cache.CacheMetadata;
import com.google.adk.sessions.BaseSessionService;
import com.google.genai.types.GenerateContentResponseUsageMetadata;
import io.reactivex.rxjava3.core.Single;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Analyzes cache performance through event history.
 *
 * <p>This analyzer extracts cache performance metrics from session events, providing insights into
 * cache hit ratios, cost savings, and cache refresh patterns.
 *
 * <p><b>Python Parity:</b> This class exactly matches Python ADK's CachePerformanceAnalyzer
 * implementation, including the returned metrics structure and calculations.
 *
 * <p>Example usage:
 *
 * <pre>{@code
 * BaseSessionService sessionService = new InMemorySessionService();
 * CachePerformanceAnalyzer analyzer = new CachePerformanceAnalyzer(sessionService);
 *
 * Single<Map<String, Object>> analysis = analyzer.analyzeAgentCachePerformance(
 *     "session-123",
 *     "user-456",
 *     "my-app",
 *     "scheduling-agent"
 * );
 *
 * analysis.subscribe(metrics -> {
 *     System.out.println("Cache hit ratio: " + metrics.get("cache_hit_ratio_percent") + "%");
 *     System.out.println("Total requests: " + metrics.get("total_requests"));
 * });
 * }</pre>
 *
 * @since 0.4.0
 */
public class CachePerformanceAnalyzer {

  private static final Logger logger = LoggerFactory.getLogger(CachePerformanceAnalyzer.class);

  private final BaseSessionService sessionService;

  /**
   * Constructs a cache performance analyzer.
   *
   * @param sessionService The session service to retrieve session events from
   * @throws NullPointerException if sessionService is null
   */
  public CachePerformanceAnalyzer(BaseSessionService sessionService) {
    if (sessionService == null) {
      throw new NullPointerException("sessionService cannot be null");
    }
    this.sessionService = sessionService;
  }

  /**
   * Analyze cache performance for agent.
   *
   * <p>This method extracts cache metadata and usage statistics from session events to calculate
   * comprehensive performance metrics.
   *
   * @param sessionId Session to analyze
   * @param userId User ID for session lookup
   * @param appName App name for session lookup
   * @param agentName Agent to analyze
   * @return Single emitting performance analysis map containing:
   *     <ul>
   *       <li>status: "active" if cache data found, "no_cache_data" if none
   *       <li>requests_with_cache: Number of requests that used caching
   *       <li>avg_invocations_used: Average number of invocations each cache was used
   *       <li>latest_cache: Resource name of most recent cache used
   *       <li>cache_refreshes: Number of unique cache instances created
   *       <li>total_invocations: Total number of invocations across all caches
   *       <li>total_prompt_tokens: Total prompt tokens across all requests
   *       <li>total_cached_tokens: Total cached content tokens across all requests
   *       <li>cache_hit_ratio_percent: Percentage of tokens served from cache
   *       <li>cache_utilization_ratio_percent: Percentage of requests with cache hits
   *       <li>avg_cached_tokens_per_request: Average cached tokens per request
   *       <li>total_requests: Total number of requests processed
   *       <li>requests_with_cache_hits: Number of requests that had cache hits
   *     </ul>
   */
  public Single<Map<String, Object>> analyzeAgentCachePerformance(
      String sessionId, String userId, String appName, String agentName) {

    return getAgentCacheHistory(sessionId, userId, appName, agentName)
        .flatMap(
            cacheHistory -> {
              if (cacheHistory.isEmpty()) {
                Map<String, Object> result = new HashMap<>();
                result.put("status", "no_cache_data");
                return Single.just(result);
              }

              // Get all events for token analysis
              return sessionService
                  .getSession(appName, userId, sessionId, Optional.empty())
                  .toSingle() // Session must exist if we have cache history
                  .map(
                      session -> {
                        // Collect token metrics from events
                        long totalPromptTokens = 0;
                        long totalCachedTokens = 0;
                        int requestsWithCacheHits = 0;
                        int totalRequests = 0;

                        for (Event event : session.events()) {
                          if (agentName.equals(event.author())
                              && event.usageMetadata().isPresent()) {
                            totalRequests++;
                            GenerateContentResponseUsageMetadata usage =
                                event.usageMetadata().get();

                            if (usage.promptTokenCount().isPresent()) {
                              totalPromptTokens += usage.promptTokenCount().get();
                            }
                            if (usage.cachedContentTokenCount().isPresent()
                                && usage.cachedContentTokenCount().get() > 0) {
                              totalCachedTokens += usage.cachedContentTokenCount().get();
                              requestsWithCacheHits++;
                            }
                          }
                        }

                        // Calculate cache metrics
                        double cacheHitRatioPercent =
                            totalPromptTokens > 0
                                ? (totalCachedTokens / (double) totalPromptTokens) * 100
                                : 0.0;

                        double cacheUtilizationRatioPercent =
                            totalRequests > 0
                                ? (requestsWithCacheHits / (double) totalRequests) * 100
                                : 0.0;

                        double avgCachedTokensPerRequest =
                            totalRequests > 0 ? totalCachedTokens / (double) totalRequests : 0.0;

                        List<Integer> invocationsUsed =
                            cacheHistory.stream()
                                .map(c -> c.invocationsUsed().orElse(0))
                                .collect(Collectors.toList());

                        int totalInvocations =
                            invocationsUsed.stream().mapToInt(Integer::intValue).sum();

                        double avgInvocationsUsed =
                            !invocationsUsed.isEmpty()
                                ? invocationsUsed.stream()
                                    .mapToInt(Integer::intValue)
                                    .average()
                                    .orElse(0.0)
                                : 0.0;

                        Set<String> uniqueCaches =
                            cacheHistory.stream()
                                .map(c -> c.cacheName().orElse(""))
                                .filter(name -> !name.isEmpty())
                                .collect(Collectors.toSet());

                        // Build result map (matches Python dict structure)
                        Map<String, Object> result = new HashMap<>();
                        result.put("status", "active");
                        result.put("requests_with_cache", cacheHistory.size());
                        result.put("avg_invocations_used", avgInvocationsUsed);
                        result.put(
                            "latest_cache",
                            cacheHistory.get(cacheHistory.size() - 1).cacheName().orElse(""));
                        result.put("cache_refreshes", uniqueCaches.size());
                        result.put("total_invocations", totalInvocations);
                        result.put("total_prompt_tokens", totalPromptTokens);
                        result.put("total_cached_tokens", totalCachedTokens);
                        result.put("cache_hit_ratio_percent", cacheHitRatioPercent);
                        result.put("cache_utilization_ratio_percent", cacheUtilizationRatioPercent);
                        result.put("avg_cached_tokens_per_request", avgCachedTokensPerRequest);
                        result.put("total_requests", totalRequests);
                        result.put("requests_with_cache_hits", requestsWithCacheHits);

                        logger.debug(
                            "Cache performance for agent {}: hit_ratio={:.2f}%, "
                                + "utilization={:.2f}%, requests={}",
                            agentName,
                            cacheHitRatioPercent,
                            cacheUtilizationRatioPercent,
                            totalRequests);

                        return result;
                      });
            });
  }

  /**
   * Get cache usage history for agent from events.
   *
   * <p>This method scans through session events to extract cache metadata for the specified agent.
   *
   * @param sessionId Session to analyze
   * @param userId User ID for session lookup
   * @param appName App name for session lookup
   * @param agentName Agent to get history for. If null, gets all cache events.
   * @return Single emitting list of cache metadata in chronological order
   */
  private Single<List<CacheMetadata>> getAgentCacheHistory(
      String sessionId, String userId, String appName, String agentName) {

    return sessionService
        .getSession(appName, userId, sessionId, Optional.empty())
        .map(
            session -> {
              List<CacheMetadata> cacheHistory = new ArrayList<>();

              for (Event event : session.events()) {
                // Check if event has cache metadata and optionally filter by agent
                if (event.cacheMetadata().isPresent()
                    && (agentName == null || agentName.equals(event.author()))) {
                  cacheHistory.add(event.cacheMetadata().get());
                }
              }

              logger.debug(
                  "Found {} cache metadata entries for agent {} in session {}",
                  cacheHistory.size(),
                  agentName,
                  sessionId);

              return cacheHistory;
            })
        .defaultIfEmpty(new ArrayList<>()); // Default to empty list if session not found
  }
}
