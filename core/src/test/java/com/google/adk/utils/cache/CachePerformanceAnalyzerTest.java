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

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.google.adk.events.Event;
import com.google.adk.models.cache.CacheMetadata;
import com.google.adk.sessions.BaseSessionService;
import com.google.adk.sessions.Session;
import com.google.genai.types.GenerateContentResponseUsageMetadata;
import io.reactivex.rxjava3.core.Maybe;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@RunWith(JUnit4.class)
public class CachePerformanceAnalyzerTest {

  @Mock private BaseSessionService mockSessionService;

  private CachePerformanceAnalyzer analyzer;

  @Before
  public void setUp() {
    MockitoAnnotations.openMocks(this);
    analyzer = new CachePerformanceAnalyzer(mockSessionService);
  }

  private CacheMetadata createCacheMetadata(String cacheName) {
    return CacheMetadata.builder()
        .cacheName("projects/test/locations/us-central1/cachedContents/" + cacheName)
        .expireTime(System.currentTimeMillis() / 1000 + 1800)
        .fingerprint("test_fingerprint_" + cacheName)
        .contentsCount(5)
        .createdAt(System.currentTimeMillis() / 1000 - 600)
        .build();
  }

  private GenerateContentResponseUsageMetadata createUsageMetadata(
      int promptTokens, int cachedTokens) {
    return GenerateContentResponseUsageMetadata.builder()
        .promptTokenCount(promptTokens)
        .cachedContentTokenCount(cachedTokens)
        .candidatesTokenCount(100)
        .totalTokenCount(promptTokens + 100)
        .build();
  }

  private Event createEvent(String author, CacheMetadata cacheMetadata) {
    return Event.builder()
        .id("event-" + System.currentTimeMillis())
        .author(author)
        .cacheMetadata(Optional.ofNullable(cacheMetadata))
        .build();
  }

  private Event createEventWithUsage(
      String author,
      CacheMetadata cacheMetadata,
      GenerateContentResponseUsageMetadata usageMetadata) {
    return Event.builder()
        .id("event-" + System.currentTimeMillis())
        .author(author)
        .cacheMetadata(Optional.ofNullable(cacheMetadata))
        .usageMetadata(Optional.ofNullable(usageMetadata))
        .build();
  }

  private Session createSession(List<Event> events) {
    return Session.builder("test-session")
        .appName("test-app")
        .userId("test-user")
        .events(events)
        .build();
  }

  @Test
  public void constructor_withNullSessionService_throwsNullPointerException() {
    assertThrows(NullPointerException.class, () -> new CachePerformanceAnalyzer(null));
  }

  @Test
  public void analyzeAgentCachePerformance_emptySession_returnsNoCacheData() {
    Session emptySession = createSession(new ArrayList<>());
    when(mockSessionService.getSession(anyString(), anyString(), anyString(), any()))
        .thenReturn(Maybe.just(emptySession));

    Map<String, Object> result =
        analyzer
            .analyzeAgentCachePerformance("test-session", "test-user", "test-app", "test-agent")
            .blockingGet();

    assertThat(result).containsEntry("status", "no_cache_data");
    assertThat(result).hasSize(1);
  }

  @Test
  public void analyzeAgentCachePerformance_noCacheEvents_returnsNoCacheData() {
    List<Event> events = new ArrayList<>();
    events.add(createEvent("test-agent", null));
    events.add(createEvent("other-agent", null));
    events.add(createEvent("test-agent", null));

    Session session = createSession(events);
    when(mockSessionService.getSession(anyString(), anyString(), anyString(), any()))
        .thenReturn(Maybe.just(session));

    Map<String, Object> result =
        analyzer
            .analyzeAgentCachePerformance("test-session", "test-user", "test-app", "test-agent")
            .blockingGet();

    assertThat(result).containsEntry("status", "no_cache_data");
  }

  @Test
  public void analyzeAgentCachePerformance_specificAgent_filtersCorrectly() {
    CacheMetadata cache1 = createCacheMetadata("cache1");
    CacheMetadata cache2 = createCacheMetadata("cache2");
    CacheMetadata cache3 = createCacheMetadata("cache3");

    GenerateContentResponseUsageMetadata usage = createUsageMetadata(1000, 800);

    List<Event> events = new ArrayList<>();
    events.add(createEventWithUsage("test-agent", cache1, usage));
    events.add(createEventWithUsage("other-agent", cache2, usage));
    events.add(createEventWithUsage("test-agent", cache3, usage));
    events.add(createEventWithUsage("test-agent", null, usage)); // No cache metadata

    Session session = createSession(events);
    when(mockSessionService.getSession(anyString(), anyString(), anyString(), any()))
        .thenReturn(Maybe.just(session));

    Map<String, Object> result =
        analyzer
            .analyzeAgentCachePerformance("test-session", "test-user", "test-app", "test-agent")
            .blockingGet();

    assertThat(result).containsEntry("status", "active");
    assertThat(result).containsEntry("requests_with_cache", 2); // Only test-agent's 2 caches
    assertThat(result).containsEntry("total_requests", 3); // 3 test-agent requests with usage
  }

  @Test
  public void analyzeAgentCachePerformance_extractsPromptTokens() {
    CacheMetadata cache = createCacheMetadata("cache1");
    GenerateContentResponseUsageMetadata usage1 = createUsageMetadata(1000, 800);
    GenerateContentResponseUsageMetadata usage2 = createUsageMetadata(1500, 1200);

    List<Event> events = new ArrayList<>();
    events.add(createEventWithUsage("test-agent", cache, usage1));
    events.add(createEventWithUsage("test-agent", cache, usage2));

    Session session = createSession(events);
    when(mockSessionService.getSession(anyString(), anyString(), anyString(), any()))
        .thenReturn(Maybe.just(session));

    Map<String, Object> result =
        analyzer
            .analyzeAgentCachePerformance("test-session", "test-user", "test-app", "test-agent")
            .blockingGet();

    assertThat(result).containsEntry("total_prompt_tokens", 2500L); // 1000 + 1500
  }

  @Test
  public void analyzeAgentCachePerformance_extractsCachedTokens() {
    CacheMetadata cache = createCacheMetadata("cache1");
    GenerateContentResponseUsageMetadata usage1 = createUsageMetadata(1000, 800);
    GenerateContentResponseUsageMetadata usage2 = createUsageMetadata(1500, 1200);

    List<Event> events = new ArrayList<>();
    events.add(createEventWithUsage("test-agent", cache, usage1));
    events.add(createEventWithUsage("test-agent", cache, usage2));

    Session session = createSession(events);
    when(mockSessionService.getSession(anyString(), anyString(), anyString(), any()))
        .thenReturn(Maybe.just(session));

    Map<String, Object> result =
        analyzer
            .analyzeAgentCachePerformance("test-session", "test-user", "test-app", "test-agent")
            .blockingGet();

    assertThat(result).containsEntry("total_cached_tokens", 2000L); // 800 + 1200
    assertThat(result).containsEntry("requests_with_cache_hits", 2);
  }

  @Test
  public void analyzeAgentCachePerformance_handlesNullUsageMetadata() {
    CacheMetadata cache = createCacheMetadata("cache1");

    List<Event> events = new ArrayList<>();
    events.add(createEventWithUsage("test-agent", cache, null));
    events.add(createEventWithUsage("test-agent", cache, createUsageMetadata(1000, 800)));

    Session session = createSession(events);
    when(mockSessionService.getSession(anyString(), anyString(), anyString(), any()))
        .thenReturn(Maybe.just(session));

    Map<String, Object> result =
        analyzer
            .analyzeAgentCachePerformance("test-session", "test-user", "test-app", "test-agent")
            .blockingGet();

    assertThat(result).containsEntry("total_requests", 1); // Only count with usage_metadata
    assertThat(result).containsEntry("total_prompt_tokens", 1000L);
  }

  @Test
  public void analyzeAgentCachePerformance_handlesNullTokenCounts() {
    CacheMetadata cache = createCacheMetadata("cache1");
    GenerateContentResponseUsageMetadata usageWithNulls =
        GenerateContentResponseUsageMetadata.builder().build(); // All nulls

    List<Event> events = new ArrayList<>();
    events.add(createEventWithUsage("test-agent", cache, usageWithNulls));

    Session session = createSession(events);
    when(mockSessionService.getSession(anyString(), anyString(), anyString(), any()))
        .thenReturn(Maybe.just(session));

    Map<String, Object> result =
        analyzer
            .analyzeAgentCachePerformance("test-session", "test-user", "test-app", "test-agent")
            .blockingGet();

    assertThat(result).containsEntry("total_prompt_tokens", 0L);
    assertThat(result).containsEntry("total_cached_tokens", 0L);
    assertThat(result).containsEntry("requests_with_cache_hits", 0);
  }

  @Test
  public void analyzeAgentCachePerformance_calculatesCacheHitRatio() {
    CacheMetadata cache = createCacheMetadata("cache1");
    GenerateContentResponseUsageMetadata usage = createUsageMetadata(1000, 800);

    List<Event> events = new ArrayList<>();
    events.add(createEventWithUsage("test-agent", cache, usage));

    Session session = createSession(events);
    when(mockSessionService.getSession(anyString(), anyString(), anyString(), any()))
        .thenReturn(Maybe.just(session));

    Map<String, Object> result =
        analyzer
            .analyzeAgentCachePerformance("test-session", "test-user", "test-app", "test-agent")
            .blockingGet();

    // cache_hit_ratio = (800 / 1000) * 100 = 80.0
    assertThat((Double) result.get("cache_hit_ratio_percent")).isWithin(0.01).of(80.0);
  }

  @Test
  public void analyzeAgentCachePerformance_calculatesCacheUtilizationRatio() {
    CacheMetadata cache = createCacheMetadata("cache1");
    GenerateContentResponseUsageMetadata usageWithCache = createUsageMetadata(1000, 800);
    GenerateContentResponseUsageMetadata usageWithoutCache = createUsageMetadata(1000, 0);

    List<Event> events = new ArrayList<>();
    events.add(createEventWithUsage("test-agent", cache, usageWithCache));
    events.add(createEventWithUsage("test-agent", cache, usageWithCache));
    events.add(createEventWithUsage("test-agent", cache, usageWithoutCache));

    Session session = createSession(events);
    when(mockSessionService.getSession(anyString(), anyString(), anyString(), any()))
        .thenReturn(Maybe.just(session));

    Map<String, Object> result =
        analyzer
            .analyzeAgentCachePerformance("test-session", "test-user", "test-app", "test-agent")
            .blockingGet();

    // utilization = (2 / 3) * 100 = 66.67%
    assertThat((Double) result.get("cache_utilization_ratio_percent")).isWithin(0.01).of(66.67);
  }

  @Test
  public void analyzeAgentCachePerformance_calculatesAvgCachedTokensPerRequest() {
    CacheMetadata cache = createCacheMetadata("cache1");
    GenerateContentResponseUsageMetadata usage1 = createUsageMetadata(1000, 800);
    GenerateContentResponseUsageMetadata usage2 = createUsageMetadata(1500, 1200);

    List<Event> events = new ArrayList<>();
    events.add(createEventWithUsage("test-agent", cache, usage1));
    events.add(createEventWithUsage("test-agent", cache, usage2));

    Session session = createSession(events);
    when(mockSessionService.getSession(anyString(), anyString(), anyString(), any()))
        .thenReturn(Maybe.just(session));

    Map<String, Object> result =
        analyzer
            .analyzeAgentCachePerformance("test-session", "test-user", "test-app", "test-agent")
            .blockingGet();

    // avg = 2000 / 2 = 1000.0
    assertThat((Double) result.get("avg_cached_tokens_per_request")).isWithin(0.01).of(1000.0);
  }

  @Test
  public void analyzeAgentCachePerformance_countsCacheRefreshes() {
    CacheMetadata cache1 = createCacheMetadata("cache1");
    CacheMetadata cache2 = createCacheMetadata("cache2");
    CacheMetadata cache3 = createCacheMetadata("cache1"); // Same cache name as cache1

    GenerateContentResponseUsageMetadata usage = createUsageMetadata(1000, 800);

    List<Event> events = new ArrayList<>();
    events.add(createEventWithUsage("test-agent", cache1, usage));
    events.add(createEventWithUsage("test-agent", cache2, usage));
    events.add(createEventWithUsage("test-agent", cache3, usage));

    Session session = createSession(events);
    when(mockSessionService.getSession(anyString(), anyString(), anyString(), any()))
        .thenReturn(Maybe.just(session));

    Map<String, Object> result =
        analyzer
            .analyzeAgentCachePerformance("test-session", "test-user", "test-app", "test-agent")
            .blockingGet();

    assertThat(result).containsEntry("cache_refreshes", 2); // Only 2 unique cache names
  }

  @Test
  public void analyzeAgentCachePerformance_zeroPromptTokens_returnsZeroRatio() {
    CacheMetadata cache = createCacheMetadata("cache1");
    GenerateContentResponseUsageMetadata usage = createUsageMetadata(0, 0);

    List<Event> events = new ArrayList<>();
    events.add(createEventWithUsage("test-agent", cache, usage));

    Session session = createSession(events);
    when(mockSessionService.getSession(anyString(), anyString(), anyString(), any()))
        .thenReturn(Maybe.just(session));

    Map<String, Object> result =
        analyzer
            .analyzeAgentCachePerformance("test-session", "test-user", "test-app", "test-agent")
            .blockingGet();

    assertThat((Double) result.get("cache_hit_ratio_percent")).isWithin(0.01).of(0.0);
  }

  @Test
  public void analyzeAgentCachePerformance_zeroRequests_returnsZeroUtilization() {
    CacheMetadata cache = createCacheMetadata("cache1");

    List<Event> events = new ArrayList<>();
    events.add(createEvent("test-agent", cache)); // No usage metadata

    Session session = createSession(events);
    when(mockSessionService.getSession(anyString(), anyString(), anyString(), any()))
        .thenReturn(Maybe.just(session));

    Map<String, Object> result =
        analyzer
            .analyzeAgentCachePerformance("test-session", "test-user", "test-app", "test-agent")
            .blockingGet();

    assertThat((Double) result.get("cache_utilization_ratio_percent")).isWithin(0.01).of(0.0);
  }

  @Test
  public void analyzeAgentCachePerformance_hundredPercentHitRate() {
    CacheMetadata cache = createCacheMetadata("cache1");
    GenerateContentResponseUsageMetadata usage = createUsageMetadata(1000, 1000);

    List<Event> events = new ArrayList<>();
    events.add(createEventWithUsage("test-agent", cache, usage));

    Session session = createSession(events);
    when(mockSessionService.getSession(anyString(), anyString(), anyString(), any()))
        .thenReturn(Maybe.just(session));

    Map<String, Object> result =
        analyzer
            .analyzeAgentCachePerformance("test-session", "test-user", "test-app", "test-agent")
            .blockingGet();

    assertThat((Double) result.get("cache_hit_ratio_percent")).isWithin(0.01).of(100.0);
  }

  @Test
  public void analyzeAgentCachePerformance_returnsLatestCache() {
    CacheMetadata cache1 = createCacheMetadata("cache1");
    CacheMetadata cache2 = createCacheMetadata("cache2");
    CacheMetadata cache3 = createCacheMetadata("cache3");

    GenerateContentResponseUsageMetadata usage = createUsageMetadata(1000, 800);

    List<Event> events = new ArrayList<>();
    events.add(createEventWithUsage("test-agent", cache1, usage));
    events.add(createEventWithUsage("test-agent", cache2, usage));
    events.add(createEventWithUsage("test-agent", cache3, usage));

    Session session = createSession(events);
    when(mockSessionService.getSession(anyString(), anyString(), anyString(), any()))
        .thenReturn(Maybe.just(session));

    Map<String, Object> result =
        analyzer
            .analyzeAgentCachePerformance("test-session", "test-user", "test-app", "test-agent")
            .blockingGet();

    assertThat(result.get("latest_cache"))
        .isEqualTo("projects/test/locations/us-central1/cachedContents/cache3");
  }

  @Test
  public void analyzeAgentCachePerformance_fullAnalysis_allFieldsPresent() {
    CacheMetadata cache = createCacheMetadata("cache1");
    GenerateContentResponseUsageMetadata usage = createUsageMetadata(1000, 800);

    List<Event> events = new ArrayList<>();
    events.add(createEventWithUsage("test-agent", cache, usage));

    Session session = createSession(events);
    when(mockSessionService.getSession(anyString(), anyString(), anyString(), any()))
        .thenReturn(Maybe.just(session));

    Map<String, Object> result =
        analyzer
            .analyzeAgentCachePerformance("test-session", "test-user", "test-app", "test-agent")
            .blockingGet();

    assertThat(result).containsKey("status");
    assertThat(result).containsKey("requests_with_cache");
    assertThat(result).containsKey("latest_cache");
    assertThat(result).containsKey("cache_refreshes");
    assertThat(result).containsKey("total_prompt_tokens");
    assertThat(result).containsKey("total_cached_tokens");
    assertThat(result).containsKey("cache_hit_ratio_percent");
    assertThat(result).containsKey("cache_utilization_ratio_percent");
    assertThat(result).containsKey("avg_cached_tokens_per_request");
    assertThat(result).containsKey("total_requests");
    assertThat(result).containsKey("requests_with_cache_hits");
    assertThat(result)
        .hasSize(11); // Exactly 11 fields (removed avg_invocations_used, total_invocations)
  }

  @Test
  public void analyzeAgentCachePerformance_multipleAgents_isolatesCorrectly() {
    CacheMetadata cache1 = createCacheMetadata("cache1");
    CacheMetadata cache2 = createCacheMetadata("cache2");

    GenerateContentResponseUsageMetadata usage = createUsageMetadata(1000, 800);

    List<Event> events = new ArrayList<>();
    events.add(createEventWithUsage("agent-A", cache1, usage));
    events.add(createEventWithUsage("agent-B", cache2, usage));
    events.add(createEventWithUsage("agent-A", cache1, usage));

    Session session = createSession(events);
    when(mockSessionService.getSession(anyString(), anyString(), anyString(), any()))
        .thenReturn(Maybe.just(session));

    Map<String, Object> resultA =
        analyzer
            .analyzeAgentCachePerformance("test-session", "test-user", "test-app", "agent-A")
            .blockingGet();

    Map<String, Object> resultB =
        analyzer
            .analyzeAgentCachePerformance("test-session", "test-user", "test-app", "agent-B")
            .blockingGet();

    assertThat(resultA).containsEntry("requests_with_cache", 2);
    assertThat(resultA).containsEntry("total_requests", 2);

    assertThat(resultB).containsEntry("requests_with_cache", 1);
    assertThat(resultB).containsEntry("total_requests", 1);
  }

  @Test
  public void analyzeAgentCachePerformance_mixedEventsWithAndWithoutCache() {
    CacheMetadata cache = createCacheMetadata("cache1");
    GenerateContentResponseUsageMetadata usage = createUsageMetadata(1000, 800);

    List<Event> events = new ArrayList<>();
    events.add(createEventWithUsage("test-agent", cache, usage));
    events.add(createEventWithUsage("test-agent", null, usage)); // No cache
    events.add(createEventWithUsage("test-agent", cache, usage));

    Session session = createSession(events);
    when(mockSessionService.getSession(anyString(), anyString(), anyString(), any()))
        .thenReturn(Maybe.just(session));

    Map<String, Object> result =
        analyzer
            .analyzeAgentCachePerformance("test-session", "test-user", "test-app", "test-agent")
            .blockingGet();

    assertThat(result).containsEntry("requests_with_cache", 2);
    assertThat(result).containsEntry("total_requests", 3);
  }

  @Test
  public void analyzeAgentCachePerformance_multipleCacheRefreshes() {
    CacheMetadata cache1a = createCacheMetadata("cache1");
    CacheMetadata cache2 = createCacheMetadata("cache2");
    CacheMetadata cache1b = createCacheMetadata("cache1");
    CacheMetadata cache3 = createCacheMetadata("cache3");

    GenerateContentResponseUsageMetadata usage = createUsageMetadata(1000, 800);

    List<Event> events = new ArrayList<>();
    events.add(createEventWithUsage("test-agent", cache1a, usage));
    events.add(createEventWithUsage("test-agent", cache2, usage));
    events.add(createEventWithUsage("test-agent", cache1b, usage)); // Refresh to cache1
    events.add(createEventWithUsage("test-agent", cache3, usage));

    Session session = createSession(events);
    when(mockSessionService.getSession(anyString(), anyString(), anyString(), any()))
        .thenReturn(Maybe.just(session));

    Map<String, Object> result =
        analyzer
            .analyzeAgentCachePerformance("test-session", "test-user", "test-app", "test-agent")
            .blockingGet();

    assertThat(result).containsEntry("cache_refreshes", 3); // cache1, cache2, cache3
    assertThat(result).containsEntry("requests_with_cache", 4); // All 4 requests
  }
}
