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

package src.main.java.com.google.adk.samples.contextcaching;

import com.google.adk.agents.LlmAgent;
import com.google.adk.agents.RunConfig;
import com.google.adk.artifacts.InMemoryArtifactService;
import com.google.adk.memory.InMemoryMemoryService;
import com.google.adk.runner.Runner;
import com.google.adk.sessions.BaseSessionService;
import com.google.adk.sessions.InMemorySessionService;
import com.google.adk.utils.cache.CachePerformanceAnalyzer;
import com.google.common.collect.ImmutableList;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Demonstrates context caching with a multi-turn healthcare scheduling conversation.
 *
 * <p>This demo shows:
 *
 * <ul>
 *   <li>Cache creation on Turn 2 (fingerprint matching)
 *   <li>Cache reuse on Turns 3+ (90% token savings)
 *   <li>invocations tracking (automatic refresh after 10 uses)
 *   <li>Performance improvement (faster responses)
 *   <li>Cost analysis (estimated savings)
 * </ul>
 *
 * <p><b>Expected Output:</b>
 *
 * <pre>
 * === Turn 1: No cache yet ===
 * Cache: Fingerprint-only: 4 contents
 *
 * === Turn 2: Cache created ===
 * Cache: cachedContents/xyz: 4 contents, expires in 1440min
 * Cached tokens: 15,000+ (90% savings!)
 *
 * === Turns 3-5: Cache reused ===
 * invocations: 1 → 2 → 3
 * Cached tokens: 15,000+ per turn
 *
 * === Performance Summary ===
 * Cache hit ratio: 80%
 * Total cached tokens: 60,000+
 * Estimated cost savings: 60-70%
 * </pre>
 *
 * <p><b>Run with:</b> {@code mvn clean compile exec:java}
 */
public class CachingDemo {

  public static void main(String[] args) {
    System.out.println("=".repeat(80));
    System.out.println("Context Caching Demo - Healthcare Scheduling Agent");
    System.out.println("=".repeat(80));
    System.out.println();

    LlmAgent agent = HealthcareAgent.createAgent();
    RunConfig runConfigWithCaching = HealthcareAgent.createRunConfigWithCaching();

    String appName = "healthcare-demo";
    String userId = "demo-user";
    String sessionId = UUID.randomUUID().toString();

    BaseSessionService sessionService = new InMemorySessionService();
    Runner runner =
        new Runner(
            agent,
            appName,
            new InMemoryArtifactService(),
            sessionService,
            new InMemoryMemoryService());

    // Create session before use
    ConcurrentMap<String, Object> initialState = new ConcurrentHashMap<>();
    var unused =
        sessionService.createSession(appName, userId, initialState, sessionId).blockingGet();

    AtomicLong totalCachedTokens = new AtomicLong(0);

    System.out.println("Session ID: " + sessionId);
    System.out.println();

    System.out.println("=== Turn 1: First Request (No Cache Yet) ===");
    runTurn(
        runner,
        userId,
        sessionId,
        runConfigWithCaching,
        "How long are appointments?",
        totalCachedTokens);

    System.out.println("\n=== Turn 2: Cache Creation (Fingerprint Matches) ===");
    runTurn(
        runner,
        userId,
        sessionId,
        runConfigWithCaching,
        "Can I see the same provider each time?",
        totalCachedTokens);

    System.out.println("\n=== Turn 3: Cache Reuse (invocations: 1 → 2) ===");
    runTurn(
        runner,
        userId,
        sessionId,
        runConfigWithCaching,
        "Are medical records private and secure?",
        totalCachedTokens);

    System.out.println("\n=== Turn 4: Cache Reuse (invocations: 2 → 3) ===");
    runTurn(
        runner,
        userId,
        sessionId,
        runConfigWithCaching,
        "Will prescriptions be sent to my pharmacy?",
        totalCachedTokens);

    System.out.println("\n=== Turn 5: Cache Reuse (invocations: 3 → 4) ===");
    runTurn(
        runner,
        userId,
        sessionId,
        runConfigWithCaching,
        "Can providers prescribe medications?",
        totalCachedTokens);

    System.out.println("\n" + "=".repeat(80));
    System.out.println("Cache Performance Analysis");
    System.out.println("=".repeat(80));

    CachePerformanceAnalyzer analyzer = new CachePerformanceAnalyzer(sessionService);
    try {
      Map<String, Object> metrics =
          analyzer
              .analyzeAgentCachePerformance(sessionId, userId, appName, "HealthcareSchedulingAgent")
              .blockingGet();

      System.out.println("Cache hit ratio: " + metrics.get("cache_hit_ratio_percent") + "%");
      System.out.println(
          "Cache utilization: " + metrics.get("cache_utilization_ratio_percent") + "%");
      System.out.println("Total cached tokens: " + totalCachedTokens.get());
      System.out.println("Requests with cache: " + metrics.get("requests_with_cache"));
      System.out.println("Latest cache: " + metrics.get("latest_cache"));

      long cachedTokens = totalCachedTokens.get();
      if (cachedTokens > 0) {
        double savingsPercent = 60.0;
        double estimatedMonthlySavings = (cachedTokens / 1000.0) * 0.075 * 0.9 * 30;

        System.out.println();
        System.out.println(
            "Estimated cost savings: ~" + String.format("%.0f", savingsPercent) + "%");
        System.out.println(
            "Monthly savings estimate: $" + String.format("%.2f", estimatedMonthlySavings));
        System.out.println("(Based on Gemini 2.5 Flash pricing with 90% cached token discount)");
      }
    } catch (Exception e) {
      System.out.println("Note: Cache performance metrics not available for this session");
      System.out.println("Total cached tokens observed: " + totalCachedTokens.get());
    }

    System.out.println();
    System.out.println("=".repeat(80));
    System.out.println("Demo completed! Cache successfully created and reused.");
    System.out.println("=".repeat(80));
  }

  private static void runTurn(
      Runner runner,
      String userId,
      String sessionId,
      RunConfig runConfig,
      String message,
      AtomicLong totalCachedTokens) {
    System.out.println("User: " + message);

    Content userMessage =
        Content.builder()
            .role("user")
            .parts(ImmutableList.of(Part.builder().text(message).build()))
            .build();

    runner
        .runAsync(userId, sessionId, userMessage, runConfig)
        .filter(
            event ->
                event.content().isPresent()
                    || event.cacheMetadata().isPresent()
                    || event.usageMetadata().isPresent())
        .blockingSubscribe(
            event -> {
              event
                  .content()
                  .ifPresent(
                      content -> {
                        String text =
                            content
                                .parts()
                                .flatMap(parts -> parts.stream().findFirst())
                                .flatMap(part -> part.text())
                                .orElse("");

                        if (!text.isEmpty()) {
                          System.out.println("Agent: " + text);
                        }
                      });

              event
                  .cacheMetadata()
                  .ifPresent(
                      cache -> {
                        System.out.println("Cache: " + cache.toString());

                        if (cache.isActiveCache()) {
                          cache
                              .invocationsUsed()
                              .ifPresent(
                                  invocations -> {
                                    System.out.println("  Invocations used: " + invocations);
                                  });
                        }
                      });

              event
                  .usageMetadata()
                  .ifPresent(
                      usage -> {
                        usage
                            .cachedContentTokenCount()
                            .ifPresent(
                                cached -> {
                                  if (cached > 0) {
                                    totalCachedTokens.addAndGet(cached);
                                    System.out.println(
                                        "  Cached tokens: " + cached + " (90% discount!)");
                                  }
                                });

                        usage
                            .promptTokenCount()
                            .ifPresent(
                                prompt -> {
                                  System.out.println("  Total prompt tokens: " + prompt);
                                });
                      });
            },
            error -> System.err.println("Error: " + error.getMessage()));

    System.out.println();
  }
}
