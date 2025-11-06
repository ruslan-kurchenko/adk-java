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
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Cache performance metrics for an agent. */
record CachePerformanceMetrics(
    String status,
    int totalRequests,
    int requestsWithCacheHits,
    double cacheHitRatioPercent,
    double cacheUtilizationRatioPercent,
    long totalPromptTokens,
    long totalCachedTokens,
    double avgCachedTokensPerRequest,
    int requestsWithCache,
    String latestCache,
    int cacheRefreshes) {

  static CachePerformanceMetrics noCacheData() {
    return new CachePerformanceMetrics("no_cache_data", 0, 0, 0.0, 0.0, 0, 0, 0.0, 0, "", 0);
  }

  Map<String, Object> toMap() {
    Map<String, Object> map = new HashMap<>();
    map.put("status", status);

    if ("no_cache_data".equals(status)) {
      return map;
    }

    map.put("total_requests", totalRequests);
    map.put("requests_with_cache_hits", requestsWithCacheHits);
    map.put("cache_hit_ratio_percent", cacheHitRatioPercent);
    map.put("cache_utilization_ratio_percent", cacheUtilizationRatioPercent);
    map.put("total_prompt_tokens", totalPromptTokens);
    map.put("total_cached_tokens", totalCachedTokens);
    map.put("avg_cached_tokens_per_request", avgCachedTokensPerRequest);
    map.put("requests_with_cache", requestsWithCache);
    map.put("latest_cache", latestCache);
    map.put("cache_refreshes", cacheRefreshes);
    return map;
  }
}

record TokenMetrics(
    long totalPromptTokens, long totalCachedTokens, int requestsWithCacheHits, int totalRequests) {}

record CacheStatistics(int uniqueCacheCount, String latestCacheName) {}

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
   * <p>Extracts cache metadata and usage statistics from session events to calculate comprehensive
   * performance metrics.
   *
   * @param sessionId Session to analyze
   * @param userId User ID for session lookup
   * @param appName App name for session lookup
   * @param agentName Agent to analyze
   * @return Single emitting performance analysis map (for backward compatibility)
   */
  public Single<Map<String, Object>> analyzeAgentCachePerformance(
      String sessionId, String userId, String appName, String agentName) {

    return getAgentCacheHistory(sessionId, userId, appName, agentName)
        .flatMap(
            cacheHistory -> {
              if (cacheHistory.isEmpty()) {
                return Single.just(CachePerformanceMetrics.noCacheData().toMap());
              }

              return buildPerformanceMetrics(cacheHistory, sessionId, userId, appName, agentName)
                  .map(CachePerformanceMetrics::toMap);
            });
  }

  private Single<CachePerformanceMetrics> buildPerformanceMetrics(
      List<CacheMetadata> cacheHistory,
      String sessionId,
      String userId,
      String appName,
      String agentName) {

    return sessionService
        .getSession(appName, userId, sessionId, Optional.empty())
        .toSingle()
        .map(
            session -> {
              TokenMetrics tokenMetrics = collectTokenMetrics(session.events(), agentName);
              CacheStatistics cacheStats = calculateCacheStatistics(cacheHistory);

              CachePerformanceMetrics metrics =
                  new CachePerformanceMetrics(
                      "active",
                      tokenMetrics.totalRequests(),
                      tokenMetrics.requestsWithCacheHits(),
                      calculateCacheHitRatio(tokenMetrics),
                      calculateUtilizationRatio(tokenMetrics),
                      tokenMetrics.totalPromptTokens(),
                      tokenMetrics.totalCachedTokens(),
                      calculateAvgCachedTokens(tokenMetrics),
                      cacheHistory.size(),
                      cacheStats.latestCacheName(),
                      cacheStats.uniqueCacheCount());

              logger.debug(
                  "Cache performance for agent {}: hit_ratio={:.2f}%, utilization={:.2f}%, requests={}",
                  agentName,
                  metrics.cacheHitRatioPercent(),
                  metrics.cacheUtilizationRatioPercent(),
                  metrics.totalRequests());

              return metrics;
            });
  }

  private TokenMetrics collectTokenMetrics(List<Event> events, String agentName) {
    long totalPromptTokens = 0;
    long totalCachedTokens = 0;
    int requestsWithCacheHits = 0;
    int totalRequests = 0;

    for (Event event : events) {
      if (isAgentEventWithUsage(event, agentName)) {
        totalRequests++;
        GenerateContentResponseUsageMetadata usage = event.usageMetadata().get();

        totalPromptTokens += usage.promptTokenCount().orElse(0);

        if (hasCachedTokens(usage)) {
          totalCachedTokens += usage.cachedContentTokenCount().get();
          requestsWithCacheHits++;
        }
      }
    }

    return new TokenMetrics(
        totalPromptTokens, totalCachedTokens, requestsWithCacheHits, totalRequests);
  }

  private boolean isAgentEventWithUsage(Event event, String agentName) {
    return event.author() != null
        && agentName.equals(event.author())
        && event.usageMetadata().isPresent();
  }

  private boolean hasCachedTokens(GenerateContentResponseUsageMetadata usage) {
    return usage.cachedContentTokenCount().isPresent() && usage.cachedContentTokenCount().get() > 0;
  }

  private CacheStatistics calculateCacheStatistics(List<CacheMetadata> cacheHistory) {
    int uniqueCacheCount = countUniqueCaches(cacheHistory);
    String latestCacheName = cacheHistory.get(cacheHistory.size() - 1).cacheName().orElse("");

    return new CacheStatistics(uniqueCacheCount, latestCacheName);
  }

  private int countUniqueCaches(List<CacheMetadata> cacheHistory) {
    return (int)
        cacheHistory.stream()
            .map(c -> c.cacheName().orElse(""))
            .filter(name -> !name.isEmpty())
            .distinct()
            .count();
  }

  private double calculateCacheHitRatio(TokenMetrics metrics) {
    return metrics.totalPromptTokens() > 0
        ? (metrics.totalCachedTokens() / (double) metrics.totalPromptTokens()) * 100
        : 0.0;
  }

  private double calculateUtilizationRatio(TokenMetrics metrics) {
    return metrics.totalRequests() > 0
        ? (metrics.requestsWithCacheHits() / (double) metrics.totalRequests()) * 100
        : 0.0;
  }

  private double calculateAvgCachedTokens(TokenMetrics metrics) {
    return metrics.totalRequests() > 0
        ? metrics.totalCachedTokens() / (double) metrics.totalRequests()
        : 0.0;
  }

  /**
   * Get cache usage history for agent from events.
   *
   * <p>Scans session events to extract cache metadata for the specified agent.
   *
   * @param sessionId Session to analyze
   * @param userId User ID for session lookup
   * @param appName App name for session lookup
   * @param agentName Agent to get history for
   * @return Single emitting list of cache metadata in chronological order
   */
  private Single<List<CacheMetadata>> getAgentCacheHistory(
      String sessionId, String userId, String appName, String agentName) {

    return sessionService
        .getSession(appName, userId, sessionId, Optional.empty())
        .map(session -> extractCacheMetadataFromEvents(session.events(), agentName, sessionId))
        .defaultIfEmpty(new ArrayList<>());
  }

  private List<CacheMetadata> extractCacheMetadataFromEvents(
      List<Event> events, String agentName, String sessionId) {

    List<CacheMetadata> cacheHistory =
        events.stream()
            .filter(event -> isMatchingCacheEvent(event, agentName))
            .map(event -> event.cacheMetadata().get())
            .collect(Collectors.toList());

    logger.debug(
        "Found {} cache metadata entries for agent {} in session {}",
        cacheHistory.size(),
        agentName,
        sessionId);

    return cacheHistory;
  }

  private boolean isMatchingCacheEvent(Event event, String agentName) {
    return event.author() != null
        && event.cacheMetadata().isPresent()
        && agentName.equals(event.author());
  }
}
