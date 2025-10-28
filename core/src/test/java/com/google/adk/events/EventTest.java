package com.google.adk.events;

import static com.google.common.truth.Truth.assertThat;

import com.google.adk.models.cache.CacheMetadata;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.genai.types.Content;
import com.google.genai.types.FinishReason;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.Part;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class EventTest {

  private static final FunctionCall FUNCTION_CALL =
      FunctionCall.builder().name("function_name").args(ImmutableMap.of("key", "value")).build();
  private static final Content CONTENT =
      Content.builder()
          .parts(ImmutableList.of(Part.builder().functionCall(FUNCTION_CALL).build()))
          .build();
  private static final EventActions EVENT_ACTIONS =
      EventActions.builder()
          .skipSummarization(true)
          .stateDelta(new ConcurrentHashMap<>(ImmutableMap.of("key", "value")))
          .artifactDelta(
              new ConcurrentHashMap<>(
                  ImmutableMap.of("artifact_key", Part.builder().text("artifact_value").build())))
          .transferToAgent("agent_id")
          .escalate(true)
          .requestedAuthConfigs(
              new ConcurrentHashMap<>(
                  ImmutableMap.of(
                      "auth_config_key",
                      new ConcurrentHashMap<>(ImmutableMap.of("auth_key", "auth_value")))))
          .build();
  private static final Event EVENT =
      Event.builder()
          .id("event_id")
          .invocationId("invocation_id")
          .author("agent")
          .content(CONTENT)
          .actions(EVENT_ACTIONS)
          .longRunningToolIds(ImmutableSet.of("tool_id"))
          .partial(true)
          .turnComplete(true)
          .errorCode(new FinishReason("error_code"))
          .errorMessage("error_message")
          .interrupted(true)
          .timestamp(123456789L)
          .build();

  @Test
  public void event_builder_works() {
    assertThat(EVENT.functionCalls()).containsExactly(FUNCTION_CALL);
    assertThat(EVENT.functionResponses()).isEmpty();
    assertThat(EVENT.longRunningToolIds().get()).containsExactly("tool_id");
    assertThat(EVENT.partial().get()).isTrue();
    assertThat(EVENT.turnComplete().get()).isTrue();
    assertThat(EVENT.errorCode()).hasValue(new FinishReason("error_code"));
    assertThat(EVENT.errorMessage()).hasValue("error_message");
    assertThat(EVENT.interrupted()).hasValue(true);
    assertThat(EVENT.timestamp()).isEqualTo(123456789L);
    assertThat(EVENT.actions()).isEqualTo(EVENT_ACTIONS);
  }

  @Test
  public void event_builder_fills_default_actions() {
    Event event =
        Event.builder().id("event_id").invocationId("invocation_id").author("agent").build();
    assertThat(event.id()).isEqualTo("event_id");
    assertThat(event.invocationId()).isEqualTo("invocation_id");
    assertThat(event.author()).isEqualTo("agent");
    assertThat(event.actions()).isEqualTo(EventActions.builder().build());
  }

  @Test
  public void event_builder_fills_default_timestamp() {
    long before = Instant.now().toEpochMilli();
    Event event =
        Event.builder().id("event_id").invocationId("invocation_id").author("agent").build();
    long after = Instant.now().toEpochMilli();
    assertThat(event.timestamp()).isAtLeast(before);
    assertThat(event.timestamp()).isAtMost(after);
  }

  @Test
  public void event_equals_works() {
    Event event1 =
        Event.builder()
            .id("event_id")
            .invocationId("invocation_id")
            .author("agent")
            .timestamp(123456789L)
            .build();
    Event event2 =
        Event.builder()
            .id("event_id")
            .invocationId("invocation_id")
            .author("agent")
            .timestamp(123456789L)
            .build();

    assertThat(event1).isEqualTo(event2);
  }

  @Test
  public void event_hashcode_works() {
    Event event1 =
        Event.builder()
            .id("event_id")
            .invocationId("invocation_id")
            .author("agent")
            .timestamp(123456789L)
            .build();
    Event event2 =
        Event.builder()
            .id("event_id")
            .invocationId("invocation_id")
            .author("agent")
            .timestamp(123456789L)
            .build();

    assertThat(event1.hashCode()).isEqualTo(event2.hashCode());
  }

  @Test
  public void event_hashcode_works_with_map() {
    Event event1 =
        Event.builder()
            .id("event_id")
            .invocationId("invocation_id")
            .author("agent")
            .timestamp(123456789L)
            .build();
    Event event2 =
        Event.builder()
            .id("event_id_2")
            .invocationId("invocation_id")
            .author("agent")
            .timestamp(123456789L)
            .build();

    ImmutableMap<Event, String> map = ImmutableMap.of(event1, "e1", event2, "e2");
    assertThat(map).containsEntry(event1, "e1");
    assertThat(map).containsEntry(event2, "e2");
  }

  @Test
  public void event_json_serialization_works() throws Exception {
    String json = EVENT.toJson();
    Event deserializedEvent = Event.fromJson(json);
    assertThat(deserializedEvent).isEqualTo(EVENT);
  }

  @Test
  public void event_withCacheMetadata_storesAndRetrievesCorrectly() {
    CacheMetadata cacheMetadata =
        CacheMetadata.builder()
            .cacheName("cachedContents/test123")
            .expireTime(System.currentTimeMillis() / 1000 + 1800)
            .fingerprint("abc123def456")
            .invocationsUsed(5)
            .contentsCount(10)
            .createdAt(System.currentTimeMillis() / 1000)
            .build();

    Event event =
        Event.builder()
            .id("event_id")
            .invocationId("invocation_id")
            .author("agent")
            .cacheMetadata(cacheMetadata)
            .build();

    assertThat(event.cacheMetadata()).hasValue(cacheMetadata);
  }

  @Test
  public void event_withoutCacheMetadata_returnsEmpty() {
    Event event =
        Event.builder().id("event_id").invocationId("invocation_id").author("agent").build();

    assertThat(event.cacheMetadata()).isEmpty();
  }

  @Test
  public void event_cacheMetadata_serializesToJson() {
    CacheMetadata cacheMetadata =
        CacheMetadata.builder().fingerprint("fingerprint123").contentsCount(7).build();

    Event event =
        Event.builder()
            .id("event_id")
            .invocationId("invocation_id")
            .author("agent")
            .cacheMetadata(cacheMetadata)
            .build();

    String json = event.toJson();

    assertThat(json).contains("\"cacheMetadata\":");
    assertThat(json).contains("\"fingerprint\":\"fingerprint123\"");
    assertThat(json).contains("\"contents_count\":7");
  }

  @Test
  public void event_cacheMetadata_deserializesFromJson() {
    String json =
        "{"
            + "\"id\":\"event_id\","
            + "\"invocationId\":\"invocation_id\","
            + "\"author\":\"agent\","
            + "\"cacheMetadata\":{"
            + "\"fingerprint\":\"test_fingerprint\","
            + "\"contents_count\":15"
            + "},"
            + "\"actions\":{},"
            + "\"timestamp\":1234567890"
            + "}";

    Event event = Event.fromJson(json);

    assertThat(event.cacheMetadata()).isPresent();
    assertThat(event.cacheMetadata().get().fingerprint()).isEqualTo("test_fingerprint");
    assertThat(event.cacheMetadata().get().contentsCount()).isEqualTo(15);
  }

  @Test
  public void event_toBuilder_preservesCacheMetadata() {
    CacheMetadata cacheMetadata =
        CacheMetadata.builder().fingerprint("preserved123").contentsCount(5).build();

    Event original =
        Event.builder()
            .id("original_id")
            .invocationId("invocation_id")
            .author("agent")
            .cacheMetadata(cacheMetadata)
            .build();

    Event rebuilt = original.toBuilder().id("new_id").build();

    assertThat(rebuilt.cacheMetadata()).hasValue(cacheMetadata);
    assertThat(rebuilt.id()).isEqualTo("new_id");
  }
}
