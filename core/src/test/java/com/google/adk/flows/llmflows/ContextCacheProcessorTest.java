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

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.InvocationContext;
import com.google.adk.agents.LlmAgent;
import com.google.adk.agents.RunConfig;
import com.google.adk.apps.ContextCacheConfig;
import com.google.adk.artifacts.InMemoryArtifactService;
import com.google.adk.events.Event;
import com.google.adk.flows.llmflows.RequestProcessor.RequestProcessingResult;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.cache.CacheMetadata;
import com.google.adk.models.cache.GeminiContextCacheManager;
import com.google.adk.sessions.InMemorySessionService;
import com.google.adk.sessions.Session;
import com.google.common.collect.ImmutableList;
import io.reactivex.rxjava3.core.Single;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link ContextCacheProcessor}. */
@RunWith(JUnit4.class)
public class ContextCacheProcessorTest {

  private static final String TEST_AGENT_NAME = "test-agent";
  private static final String TEST_MODEL = "gemini-2.5-flash";
  private static final String TEST_STATIC_INSTRUCTION = "You are a test assistant.";

  private GeminiContextCacheManager mockCacheManager;
  private ContextCacheProcessor processor;
  private InMemorySessionService sessionService;

  @Before
  public void setUp() {
    mockCacheManager = mock(GeminiContextCacheManager.class);
    processor = new ContextCacheProcessor(mockCacheManager);
    sessionService = new InMemorySessionService();
  }

  @Test
  public void processRequest_nonLlmAgent_passesThrough() {
    BaseAgent nonLlmAgent = mock(BaseAgent.class);
    when(nonLlmAgent.name()).thenReturn("non-llm-agent");

    InvocationContext context = createContext(nonLlmAgent, ImmutableList.of());
    LlmRequest request = LlmRequest.builder().build();

    RequestProcessingResult result = processor.processRequest(context, request).blockingGet();

    assertThat(result.updatedRequest()).isEqualTo(request);
    assertThat(result.events()).isEmpty();
    verify(mockCacheManager, never()).handleContextCaching(any(), anyInt());
  }

  @Test
  public void processRequest_noCacheConfig_passesThrough() {
    LlmAgent agent = createAgentWithoutCaching();
    InvocationContext context = createContext(agent, ImmutableList.of());
    LlmRequest request = LlmRequest.builder().build();

    RequestProcessingResult result = processor.processRequest(context, request).blockingGet();

    assertThat(result.updatedRequest()).isEqualTo(request);
    verify(mockCacheManager, never()).handleContextCaching(any(), anyInt());
  }

  @Test
  public void processRequest_noStaticInstruction_passesThrough() {
    ContextCacheConfig cacheConfig = ContextCacheConfig.builder().build();
    LlmAgent agent =
        LlmAgent.builder()
            .name(TEST_AGENT_NAME)
            .model(TEST_MODEL)
            .instruction("Regular instruction")
            // No staticInstruction
            .build();

    RunConfig runConfig = RunConfig.builder().setContextCacheConfig(cacheConfig).build();
    InvocationContext context = createContextWithRunConfig(agent, ImmutableList.of(), runConfig);
    LlmRequest request = LlmRequest.builder().build();

    RequestProcessingResult result = processor.processRequest(context, request).blockingGet();

    assertThat(result.updatedRequest()).isEqualTo(request);
    verify(mockCacheManager, never()).handleContextCaching(any(), anyInt());
  }

  @Test
  public void processRequest_cachingEnabled_callsCacheManager() {
    ContextCacheConfig cacheConfig = ContextCacheConfig.builder().build();
    LlmAgent agent = createAgentWithCaching();

    CacheMetadata mockMetadata =
        CacheMetadata.builder().fingerprint("test-fingerprint").contentsCount(5).build();

    when(mockCacheManager.handleContextCaching(any(LlmRequest.class), anyInt()))
        .thenReturn(Single.just(mockMetadata));

    RunConfig runConfig = RunConfig.builder().setContextCacheConfig(cacheConfig).build();
    InvocationContext context = createContextWithRunConfig(agent, ImmutableList.of(), runConfig);
    LlmRequest request = LlmRequest.builder().build();

    RequestProcessingResult result = processor.processRequest(context, request).blockingGet();

    assertThat(result.updatedRequest().cacheMetadata()).hasValue(mockMetadata);
    assertThat(result.updatedRequest().cacheConfig()).hasValue(cacheConfig);
    verify(mockCacheManager).handleContextCaching(any(LlmRequest.class), eq(0));
  }

  @Test
  public void processRequest_noSessionEvents_cachesZeroContents() {
    ContextCacheConfig cacheConfig = ContextCacheConfig.builder().build();
    LlmAgent agent = createAgentWithCaching();

    CacheMetadata mockMetadata =
        CacheMetadata.builder().fingerprint("fingerprint").contentsCount(0).build();

    when(mockCacheManager.handleContextCaching(any(LlmRequest.class), eq(0)))
        .thenReturn(Single.just(mockMetadata));

    RunConfig runConfig = RunConfig.builder().setContextCacheConfig(cacheConfig).build();
    InvocationContext context = createContextWithRunConfig(agent, ImmutableList.of(), runConfig);
    LlmRequest request = LlmRequest.builder().build();

    RequestProcessingResult result = processor.processRequest(context, request).blockingGet();

    assertThat(result.updatedRequest().cacheMetadata()).hasValue(mockMetadata);
    verify(mockCacheManager).handleContextCaching(any(LlmRequest.class), eq(0));
  }

  @Test
  public void processRequest_withPreviousCacheMetadata_retrievesFromEvents() {
    ContextCacheConfig cacheConfig = ContextCacheConfig.builder().cacheIntervals(10).build();
    LlmAgent agent = createAgentWithCaching();

    // Create previous event with cache metadata
    CacheMetadata previousMetadata =
        CacheMetadata.builder()
            .cacheName("cachedContents/previous")
            .expireTime(System.currentTimeMillis() / 1000 + 1800)
            .fingerprint("previous-fingerprint")
            .invocationsUsed(3)
            .contentsCount(5)
            .build();

    Event previousEvent =
        Event.builder()
            .id("prev-event")
            .invocationId("prev-invocation")
            .author(TEST_AGENT_NAME)
            .cacheMetadata(previousMetadata)
            .build();

    // Expected: invocationsUsed should be incremented to 4
    CacheMetadata expectedMetadata = previousMetadata.toBuilder().invocationsUsed(4).build();

    when(mockCacheManager.handleContextCaching(any(LlmRequest.class), anyInt()))
        .thenReturn(Single.just(expectedMetadata));

    RunConfig runConfig = RunConfig.builder().setContextCacheConfig(cacheConfig).build();
    InvocationContext context =
        createContextWithRunConfig(agent, ImmutableList.of(previousEvent), runConfig);
    LlmRequest request = LlmRequest.builder().build();

    RequestProcessingResult result = processor.processRequest(context, request).blockingGet();

    // Verify metadata was retrieved and passed to cache manager
    assertThat(result.updatedRequest().cacheMetadata()).isPresent();
    verify(mockCacheManager).handleContextCaching(any(LlmRequest.class), anyInt());
  }

  @Test
  public void processRequest_sameInvocationId_doesNotIncrementUsage() {
    ContextCacheConfig cacheConfig = ContextCacheConfig.builder().build();
    LlmAgent agent = createAgentWithCaching();

    String currentInvocationId = "same-invocation";

    CacheMetadata metadata =
        CacheMetadata.builder()
            .cacheName("cachedContents/test")
            .expireTime(System.currentTimeMillis() / 1000 + 1800)
            .fingerprint("fingerprint")
            .invocationsUsed(5)
            .contentsCount(3)
            .build();

    Event sameInvocationEvent =
        Event.builder()
            .id("event-1")
            .invocationId(currentInvocationId) // SAME invocation ID
            .author(TEST_AGENT_NAME)
            .cacheMetadata(metadata)
            .build();

    when(mockCacheManager.handleContextCaching(any(LlmRequest.class), anyInt()))
        .thenReturn(Single.just(metadata));

    RunConfig runConfig = RunConfig.builder().setContextCacheConfig(cacheConfig).build();
    Session session = createSessionWithEvents(ImmutableList.of(sameInvocationEvent));
    InvocationContext context =
        new InvocationContext(
            sessionService,
            new InMemoryArtifactService(),
            null,
            null,
            Optional.empty(),
            Optional.empty(),
            currentInvocationId, // Same ID
            agent,
            session,
            Optional.empty(),
            runConfig,
            false);

    LlmRequest request = LlmRequest.builder().build();

    RequestProcessingResult result = processor.processRequest(context, request).blockingGet();

    // Verify cache metadata is passed but NOT incremented
    verify(mockCacheManager).handleContextCaching(any(LlmRequest.class), anyInt());
  }

  @Test
  public void processRequest_fingerprintOnlyMetadata_doesNotIncrement() {
    ContextCacheConfig cacheConfig = ContextCacheConfig.builder().build();
    LlmAgent agent = createAgentWithCaching();

    CacheMetadata fingerprintOnly =
        CacheMetadata.builder().fingerprint("fingerprint-only").contentsCount(5).build();

    Event previousEvent =
        Event.builder()
            .id("prev-event")
            .invocationId("prev-invocation")
            .author(TEST_AGENT_NAME)
            .cacheMetadata(fingerprintOnly)
            .build();

    when(mockCacheManager.handleContextCaching(any(LlmRequest.class), anyInt()))
        .thenReturn(Single.just(fingerprintOnly));

    RunConfig runConfig = RunConfig.builder().setContextCacheConfig(cacheConfig).build();
    InvocationContext context =
        createContextWithRunConfig(agent, ImmutableList.of(previousEvent), runConfig);
    LlmRequest request = LlmRequest.builder().build();

    RequestProcessingResult result = processor.processRequest(context, request).blockingGet();

    assertThat(result.updatedRequest().cacheMetadata()).hasValue(fingerprintOnly);
  }

  @Test
  public void processRequest_multipleAgentEvents_findsCorrectAgent() {
    ContextCacheConfig cacheConfig = ContextCacheConfig.builder().build();
    LlmAgent agent = createAgentWithCaching();

    CacheMetadata agentMetadata =
        CacheMetadata.builder().fingerprint("agent-fingerprint").contentsCount(3).build();

    CacheMetadata otherAgentMetadata =
        CacheMetadata.builder().fingerprint("other-fingerprint").contentsCount(5).build();

    Event otherAgentEvent =
        Event.builder()
            .id("other-event")
            .invocationId("other-invocation")
            .author("other-agent")
            .cacheMetadata(otherAgentMetadata)
            .build();

    Event ourAgentEvent =
        Event.builder()
            .id("our-event")
            .invocationId("our-invocation")
            .author(TEST_AGENT_NAME)
            .cacheMetadata(agentMetadata)
            .build();

    when(mockCacheManager.handleContextCaching(any(LlmRequest.class), anyInt()))
        .thenReturn(Single.just(agentMetadata));

    RunConfig runConfig = RunConfig.builder().setContextCacheConfig(cacheConfig).build();
    InvocationContext context =
        createContextWithRunConfig(
            agent, ImmutableList.of(otherAgentEvent, ourAgentEvent), runConfig);
    LlmRequest request = LlmRequest.builder().build();

    RequestProcessingResult result = processor.processRequest(context, request).blockingGet();

    // Should find our agent's metadata, not other agent's
    verify(mockCacheManager).handleContextCaching(any(LlmRequest.class), anyInt());
  }

  @Test
  public void processRequest_multipleEventsFromSameAgent_usesMostRecent() {
    ContextCacheConfig cacheConfig = ContextCacheConfig.builder().build();
    LlmAgent agent = createAgentWithCaching();

    CacheMetadata olderMetadata =
        CacheMetadata.builder().fingerprint("old-fingerprint").contentsCount(2).build();

    CacheMetadata newerMetadata =
        CacheMetadata.builder().fingerprint("new-fingerprint").contentsCount(3).build();

    Event olderEvent =
        Event.builder()
            .id("old-event")
            .invocationId("old-invocation")
            .author(TEST_AGENT_NAME)
            .cacheMetadata(olderMetadata)
            .build();

    Event newerEvent =
        Event.builder()
            .id("new-event")
            .invocationId("new-invocation")
            .author(TEST_AGENT_NAME)
            .cacheMetadata(newerMetadata)
            .build();

    when(mockCacheManager.handleContextCaching(any(LlmRequest.class), anyInt()))
        .thenReturn(Single.just(newerMetadata));

    RunConfig runConfig = RunConfig.builder().setContextCacheConfig(cacheConfig).build();
    InvocationContext context =
        createContextWithRunConfig(agent, ImmutableList.of(olderEvent, newerEvent), runConfig);
    LlmRequest request = LlmRequest.builder().build();

    processor.processRequest(context, request).blockingGet();

    // Should use newer metadata (not older)
    verify(mockCacheManager).handleContextCaching(any(LlmRequest.class), anyInt());
  }

  @Test
  public void processRequest_cacheManagerError_gracefulDegradation() {
    ContextCacheConfig cacheConfig = ContextCacheConfig.builder().build();
    LlmAgent agent = createAgentWithCaching();

    when(mockCacheManager.handleContextCaching(any(LlmRequest.class), anyInt()))
        .thenReturn(Single.error(new RuntimeException("Cache API failure")));

    RunConfig runConfig = RunConfig.builder().setContextCacheConfig(cacheConfig).build();
    InvocationContext context = createContextWithRunConfig(agent, ImmutableList.of(), runConfig);
    LlmRequest request = LlmRequest.builder().build();

    RequestProcessingResult result = processor.processRequest(context, request).blockingGet();

    // Should return original request without cache metadata (graceful degradation)
    assertThat(result.updatedRequest()).isEqualTo(request);
    assertThat(result.updatedRequest().cacheMetadata()).isEmpty();
  }

  @Test
  public void processRequest_calculatesCacheContentsCount_lastUserBatch() {
    ContextCacheConfig cacheConfig = ContextCacheConfig.builder().build();
    LlmAgent agent = createAgentWithCaching();

    // Events: agent, agent, user, user (last 2 are user batch, cache first 2)
    List<Event> events =
        ImmutableList.of(
            createEvent("e1", "inv1", TEST_AGENT_NAME, null),
            createEvent("e2", "inv1", TEST_AGENT_NAME, null),
            createEvent("e3", "inv2", "user", null),
            createEvent("e4", "inv2", "user", null));

    CacheMetadata mockMetadata =
        CacheMetadata.builder().fingerprint("fingerprint").contentsCount(2).build();

    when(mockCacheManager.handleContextCaching(any(LlmRequest.class), eq(2)))
        .thenReturn(Single.just(mockMetadata));

    RunConfig runConfig = RunConfig.builder().setContextCacheConfig(cacheConfig).build();
    InvocationContext context = createContextWithRunConfig(agent, events, runConfig);
    LlmRequest request = LlmRequest.builder().build();

    RequestProcessingResult result = processor.processRequest(context, request).blockingGet();

    // Should cache 2 contents (before last user batch)
    verify(mockCacheManager).handleContextCaching(any(LlmRequest.class), eq(2));
  }

  @Test
  public void processRequest_allUserEvents_cachesZero() {
    ContextCacheConfig cacheConfig = ContextCacheConfig.builder().build();
    LlmAgent agent = createAgentWithCaching();

    List<Event> allUserEvents =
        ImmutableList.of(
            createEvent("e1", "inv1", "user", null),
            createEvent("e2", "inv1", "user", null),
            createEvent("e3", "inv1", "user", null));

    CacheMetadata mockMetadata =
        CacheMetadata.builder().fingerprint("fingerprint").contentsCount(0).build();

    when(mockCacheManager.handleContextCaching(any(LlmRequest.class), eq(0)))
        .thenReturn(Single.just(mockMetadata));

    RunConfig runConfig = RunConfig.builder().setContextCacheConfig(cacheConfig).build();
    InvocationContext context = createContextWithRunConfig(agent, allUserEvents, runConfig);
    LlmRequest request = LlmRequest.builder().build();

    RequestProcessingResult result = processor.processRequest(context, request).blockingGet();

    verify(mockCacheManager).handleContextCaching(any(LlmRequest.class), eq(0));
  }

  @Test
  public void processRequest_noUserEvents_cachesAllEvents() {
    ContextCacheConfig cacheConfig = ContextCacheConfig.builder().build();
    LlmAgent agent = createAgentWithCaching();

    List<Event> agentEvents =
        ImmutableList.of(
            createEvent("e1", "inv1", TEST_AGENT_NAME, null),
            createEvent("e2", "inv1", TEST_AGENT_NAME, null),
            createEvent("e3", "inv1", TEST_AGENT_NAME, null));

    CacheMetadata mockMetadata =
        CacheMetadata.builder().fingerprint("fingerprint").contentsCount(3).build();

    when(mockCacheManager.handleContextCaching(any(LlmRequest.class), eq(3)))
        .thenReturn(Single.just(mockMetadata));

    RunConfig runConfig = RunConfig.builder().setContextCacheConfig(cacheConfig).build();
    InvocationContext context = createContextWithRunConfig(agent, agentEvents, runConfig);
    LlmRequest request = LlmRequest.builder().build();

    RequestProcessingResult result = processor.processRequest(context, request).blockingGet();

    verify(mockCacheManager).handleContextCaching(any(LlmRequest.class), eq(3));
  }

  @Test
  public void processRequest_mixedEvents_cachesBeforeLastUserBatch() {
    ContextCacheConfig cacheConfig = ContextCacheConfig.builder().build();
    LlmAgent agent = createAgentWithCaching();

    // Pattern: agent, user, agent, agent, user, user (cache first 4)
    List<Event> events =
        ImmutableList.of(
            createEvent("e1", "inv1", TEST_AGENT_NAME, null),
            createEvent("e2", "inv1", "user", null),
            createEvent("e3", "inv2", TEST_AGENT_NAME, null),
            createEvent("e4", "inv2", TEST_AGENT_NAME, null),
            createEvent("e5", "inv3", "user", null), // Last user batch starts here
            createEvent("e6", "inv3", "user", null));

    CacheMetadata mockMetadata =
        CacheMetadata.builder().fingerprint("fingerprint").contentsCount(4).build();

    when(mockCacheManager.handleContextCaching(any(LlmRequest.class), eq(4)))
        .thenReturn(Single.just(mockMetadata));

    RunConfig runConfig = RunConfig.builder().setContextCacheConfig(cacheConfig).build();
    InvocationContext context = createContextWithRunConfig(agent, events, runConfig);
    LlmRequest request = LlmRequest.builder().build();

    RequestProcessingResult result = processor.processRequest(context, request).blockingGet();

    verify(mockCacheManager).handleContextCaching(any(LlmRequest.class), eq(4));
  }

  @Test
  public void processRequest_incrementsInvocationsForActiveCache() {
    ContextCacheConfig cacheConfig = ContextCacheConfig.builder().cacheIntervals(10).build();
    LlmAgent agent = createAgentWithCaching();

    long futureExpireTime = System.currentTimeMillis() / 1000 + 1800;
    CacheMetadata activeCache =
        CacheMetadata.builder()
            .cacheName("cachedContents/active")
            .expireTime(futureExpireTime)
            .fingerprint("active-fingerprint")
            .invocationsUsed(7) // Will be incremented to 8
            .contentsCount(5)
            .build();

    Event previousEvent =
        Event.builder()
            .id("prev-event")
            .invocationId("different-invocation")
            .author(TEST_AGENT_NAME)
            .cacheMetadata(activeCache)
            .build();

    CacheMetadata incrementedMetadata = activeCache.toBuilder().invocationsUsed(8).build();

    when(mockCacheManager.handleContextCaching(any(LlmRequest.class), anyInt()))
        .thenReturn(Single.just(incrementedMetadata));

    RunConfig runConfig = RunConfig.builder().setContextCacheConfig(cacheConfig).build();
    InvocationContext context =
        createContextWithRunConfig(agent, ImmutableList.of(previousEvent), runConfig);
    LlmRequest request = LlmRequest.builder().build();

    processor.processRequest(context, request).blockingGet();

    verify(mockCacheManager).handleContextCaching(any(LlmRequest.class), anyInt());
  }

  @Test
  public void processRequest_emptySession_noMetadataPassedToManager() {
    ContextCacheConfig cacheConfig = ContextCacheConfig.builder().build();
    LlmAgent agent = createAgentWithCaching();

    CacheMetadata mockMetadata =
        CacheMetadata.builder().fingerprint("new-fingerprint").contentsCount(0).build();

    when(mockCacheManager.handleContextCaching(any(LlmRequest.class), eq(0)))
        .thenReturn(Single.just(mockMetadata));

    RunConfig runConfig = RunConfig.builder().setContextCacheConfig(cacheConfig).build();
    InvocationContext context = createContextWithRunConfig(agent, ImmutableList.of(), runConfig);
    LlmRequest request = LlmRequest.builder().build();

    processor.processRequest(context, request).blockingGet();

    verify(mockCacheManager).handleContextCaching(any(LlmRequest.class), eq(0));
  }

  @Test
  public void processRequest_eventWithoutCacheMetadata_skipsEvent() {
    ContextCacheConfig cacheConfig = ContextCacheConfig.builder().build();
    LlmAgent agent = createAgentWithCaching();

    Event eventWithoutMetadata =
        Event.builder().id("no-metadata").invocationId("inv1").author(TEST_AGENT_NAME).build();

    CacheMetadata mockMetadata =
        CacheMetadata.builder().fingerprint("fingerprint").contentsCount(1).build();

    when(mockCacheManager.handleContextCaching(any(LlmRequest.class), anyInt()))
        .thenReturn(Single.just(mockMetadata));

    RunConfig runConfig = RunConfig.builder().setContextCacheConfig(cacheConfig).build();
    InvocationContext context =
        createContextWithRunConfig(agent, ImmutableList.of(eventWithoutMetadata), runConfig);
    LlmRequest request = LlmRequest.builder().build();

    processor.processRequest(context, request).blockingGet();

    // Should not find any metadata from events
    verify(mockCacheManager).handleContextCaching(any(LlmRequest.class), eq(1));
  }

  @Test
  public void processRequest_multiplePreviousInvocations_incrementsOnlyOnce() {
    ContextCacheConfig cacheConfig = ContextCacheConfig.builder().build();
    LlmAgent agent = createAgentWithCaching();

    CacheMetadata metadata1 =
        CacheMetadata.builder()
            .cacheName("cachedContents/test")
            .expireTime(System.currentTimeMillis() / 1000 + 1800)
            .fingerprint("fingerprint")
            .invocationsUsed(5)
            .contentsCount(3)
            .build();

    CacheMetadata metadata2 =
        CacheMetadata.builder()
            .cacheName("cachedContents/test")
            .expireTime(System.currentTimeMillis() / 1000 + 1800)
            .fingerprint("fingerprint")
            .invocationsUsed(6)
            .contentsCount(3)
            .build();

    // Multiple events from previous invocations
    Event event1 =
        Event.builder()
            .id("e1")
            .invocationId("inv1")
            .author(TEST_AGENT_NAME)
            .cacheMetadata(metadata1)
            .build();

    Event event2 =
        Event.builder()
            .id("e2")
            .invocationId("inv2")
            .author(TEST_AGENT_NAME)
            .cacheMetadata(metadata2)
            .build();

    CacheMetadata incrementedMetadata = metadata2.toBuilder().invocationsUsed(7).build();

    when(mockCacheManager.handleContextCaching(any(LlmRequest.class), anyInt()))
        .thenReturn(Single.just(incrementedMetadata));

    RunConfig runConfig = RunConfig.builder().setContextCacheConfig(cacheConfig).build();
    InvocationContext context =
        createContextWithRunConfig(agent, ImmutableList.of(event1, event2), runConfig);
    LlmRequest request = LlmRequest.builder().build();

    processor.processRequest(context, request).blockingGet();

    // Should use most recent metadata (metadata2 with invocations=6, then increment to 7)
    verify(mockCacheManager).handleContextCaching(any(LlmRequest.class), anyInt());
  }

  @Test
  public void processRequest_nullInvocationIdInEvent_handlesGracefully() {
    ContextCacheConfig cacheConfig = ContextCacheConfig.builder().build();
    LlmAgent agent = createAgentWithCaching();

    CacheMetadata metadata =
        CacheMetadata.builder()
            .cacheName("cachedContents/test")
            .expireTime(System.currentTimeMillis() / 1000 + 1800)
            .fingerprint("fingerprint")
            .invocationsUsed(3)
            .contentsCount(2)
            .build();

    Event eventWithNullInvocationId =
        Event.builder()
            .id("event-1")
            .invocationId(null) // Null invocation ID
            .author(TEST_AGENT_NAME)
            .cacheMetadata(metadata)
            .build();

    when(mockCacheManager.handleContextCaching(any(LlmRequest.class), anyInt()))
        .thenReturn(Single.just(metadata));

    RunConfig runConfig = RunConfig.builder().setContextCacheConfig(cacheConfig).build();
    InvocationContext context =
        createContextWithRunConfig(agent, ImmutableList.of(eventWithNullInvocationId), runConfig);
    LlmRequest request = LlmRequest.builder().build();

    RequestProcessingResult result = processor.processRequest(context, request).blockingGet();

    // Should handle null invocation ID gracefully
    assertThat(result.updatedRequest().cacheMetadata()).isPresent();
  }

  @Test
  public void processRequest_singleUserEventAtEnd_cachesZero() {
    ContextCacheConfig cacheConfig = ContextCacheConfig.builder().build();
    LlmAgent agent = createAgentWithCaching();

    List<Event> events =
        ImmutableList.of(
            createEvent("e1", "inv1", TEST_AGENT_NAME, null),
            createEvent("e2", "inv1", TEST_AGENT_NAME, null),
            createEvent("e3", "inv2", "user", null)); // Single user at end

    CacheMetadata mockMetadata =
        CacheMetadata.builder().fingerprint("fingerprint").contentsCount(2).build();

    when(mockCacheManager.handleContextCaching(any(LlmRequest.class), eq(2)))
        .thenReturn(Single.just(mockMetadata));

    RunConfig runConfig = RunConfig.builder().setContextCacheConfig(cacheConfig).build();
    InvocationContext context = createContextWithRunConfig(agent, events, runConfig);
    LlmRequest request = LlmRequest.builder().build();

    processor.processRequest(context, request).blockingGet();

    verify(mockCacheManager).handleContextCaching(any(LlmRequest.class), eq(2));
  }

  @Test
  public void processRequest_onlyOneUserEvent_cachesZero() {
    ContextCacheConfig cacheConfig = ContextCacheConfig.builder().build();
    LlmAgent agent = createAgentWithCaching();

    List<Event> singleUserEvent = ImmutableList.of(createEvent("e1", "inv1", "user", null));

    CacheMetadata mockMetadata =
        CacheMetadata.builder().fingerprint("fingerprint").contentsCount(0).build();

    when(mockCacheManager.handleContextCaching(any(LlmRequest.class), eq(0)))
        .thenReturn(Single.just(mockMetadata));

    RunConfig runConfig = RunConfig.builder().setContextCacheConfig(cacheConfig).build();
    InvocationContext context = createContextWithRunConfig(agent, singleUserEvent, runConfig);
    LlmRequest request = LlmRequest.builder().build();

    processor.processRequest(context, request).blockingGet();

    verify(mockCacheManager).handleContextCaching(any(LlmRequest.class), eq(0));
  }

  // Helper methods

  private LlmAgent createAgentWithCaching() {
    return LlmAgent.builder()
        .name(TEST_AGENT_NAME)
        .model(TEST_MODEL)
        .staticInstruction(TEST_STATIC_INSTRUCTION)
        .instruction("Dynamic instruction")
        .build();
  }

  private LlmAgent createAgentWithoutCaching() {
    return LlmAgent.builder()
        .name(TEST_AGENT_NAME)
        .model(TEST_MODEL)
        .instruction("Regular instruction")
        .build();
  }

  private InvocationContext createContext(BaseAgent agent, List<Event> events) {
    Session session = createSessionWithEvents(events);
    return new InvocationContext(
        sessionService,
        new InMemoryArtifactService(),
        null,
        null,
        Optional.empty(),
        Optional.empty(),
        "test-invocation-id",
        agent,
        session,
        Optional.empty(),
        RunConfig.builder().build(),
        false);
  }

  private InvocationContext createContextWithRunConfig(
      BaseAgent agent, List<Event> events, RunConfig runConfig) {
    Session session = createSessionWithEvents(events);
    return new InvocationContext(
        sessionService,
        new InMemoryArtifactService(),
        null,
        null,
        Optional.empty(),
        Optional.empty(),
        "test-invocation-id",
        agent,
        session,
        Optional.empty(),
        runConfig,
        false);
  }

  private Session createSessionWithEvents(List<Event> events) {
    Session session =
        Session.builder("test-session")
            .appName("test-app")
            .userId("test-user")
            .events(new ArrayList<>(events))
            .build();
    return session;
  }

  private Event createEvent(
      String id, String invocationId, String author, CacheMetadata cacheMetadata) {
    Event.Builder builder = Event.builder().id(id).invocationId(invocationId).author(author);

    if (cacheMetadata != null) {
      builder.cacheMetadata(cacheMetadata);
    }

    return builder.build();
  }
}
