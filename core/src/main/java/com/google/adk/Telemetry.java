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

package com.google.adk;

import static com.google.common.collect.ImmutableList.toImmutableList;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.google.adk.agents.InvocationContext;
import com.google.adk.events.Event;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.common.collect.ImmutableList;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.reactivex.rxjava3.core.Flowable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Utility class for capturing and reporting telemetry data within the ADK. This class provides
 * methods to trace various aspects of the agent's execution, including tool calls, tool responses,
 * LLM interactions, and data handling. It leverages OpenTelemetry for tracing, metrics, and logging
 * for detailed information. These traces and metrics can be exported to DataDog, Prometheus,
 * Grafana, or other observability platforms via OTLP.
 *
 * <p>Metrics are recorded automatically during agent execution and exported via OpenTelemetry. To
 * enable metric export, configure the OTEL_EXPORTER_OTLP_ENDPOINT environment variable to point to
 * your collector (e.g., DataDog agent on localhost:4318).
 */
public class Telemetry {

  private static final Logger log = LoggerFactory.getLogger(Telemetry.class);
  private static final Tracer tracer = GlobalOpenTelemetry.getTracer("gcp.vertex.agent");

  // Lazy initialization of cache metrics to support testing
  private static volatile LongCounter cacheHitsCounter;
  private static volatile LongCounter cacheMissesCounter;
  private static volatile LongCounter cacheCreationsCounter;
  private static volatile LongCounter cachedTokensSavedCounter;

  private static LongCounter getCacheHitsCounter() {
    if (cacheHitsCounter == null) {
      synchronized (Telemetry.class) {
        if (cacheHitsCounter == null) {
          Meter meter = GlobalOpenTelemetry.getMeter("gcp.vertex.agent");
          cacheHitsCounter =
              meter
                  .counterBuilder("adk.cache.hits")
                  .setDescription("Number of context cache hits")
                  .setUnit("1")
                  .build();
        }
      }
    }
    return cacheHitsCounter;
  }

  private static LongCounter getCacheMissesCounter() {
    if (cacheMissesCounter == null) {
      synchronized (Telemetry.class) {
        if (cacheMissesCounter == null) {
          Meter meter = GlobalOpenTelemetry.getMeter("gcp.vertex.agent");
          cacheMissesCounter =
              meter
                  .counterBuilder("adk.cache.misses")
                  .setDescription("Number of context cache misses (new cache created)")
                  .setUnit("1")
                  .build();
        }
      }
    }
    return cacheMissesCounter;
  }

  private static LongCounter getCacheCreationsCounter() {
    if (cacheCreationsCounter == null) {
      synchronized (Telemetry.class) {
        if (cacheCreationsCounter == null) {
          Meter meter = GlobalOpenTelemetry.getMeter("gcp.vertex.agent");
          cacheCreationsCounter =
              meter
                  .counterBuilder("adk.cache.creations")
                  .setDescription("Number of context caches created")
                  .setUnit("1")
                  .build();
        }
      }
    }
    return cacheCreationsCounter;
  }

  private static LongCounter getCachedTokensSavedCounter() {
    if (cachedTokensSavedCounter == null) {
      synchronized (Telemetry.class) {
        if (cachedTokensSavedCounter == null) {
          Meter meter = GlobalOpenTelemetry.getMeter("gcp.vertex.agent");
          cachedTokensSavedCounter =
              meter
                  .counterBuilder("adk.cache.tokens.saved")
                  .setDescription("Total tokens saved by using cached content")
                  .setUnit("tokens")
                  .build();
        }
      }
    }
    return cachedTokensSavedCounter;
  }

  private Telemetry() {}

  /**
   * Record a cache hit event.
   *
   * <p>Called when an existing cache is successfully reused, indicating the cache validation passed
   * and the cached content will be used instead of re-processing the static instruction.
   *
   * @param agentName Name of the agent using the cache
   * @param cacheName Cache resource name (e.g., "cachedContents/abc123")
   */
  public static void recordCacheHit(String agentName, String cacheName) {
    try {
      getCacheHitsCounter()
          .add(
              1,
              Attributes.of(
                  AttributeKey.stringKey("agent.name"), agentName,
                  AttributeKey.stringKey("cache.name"), cacheName));
    } catch (Exception e) {
      log.debug("Failed to record cache hit metric", e);
    }
  }

  /**
   * Record a cache miss event.
   *
   * <p>Called when no valid cache exists and a new cache will be created. This typically happens on
   * the first request or when the cache has expired/been invalidated.
   *
   * @param agentName Name of the agent that missed the cache
   */
  public static void recordCacheMiss(String agentName) {
    try {
      getCacheMissesCounter()
          .add(1, Attributes.of(AttributeKey.stringKey("agent.name"), agentName));
    } catch (Exception e) {
      log.debug("Failed to record cache miss metric", e);
    }
  }

  /**
   * Record cache creation.
   *
   * <p>Called when a new cache is successfully created via the Gemini Caching API.
   *
   * @param agentName Name of the agent for which the cache was created
   * @param contentsCount Number of contents cached
   */
  public static void recordCacheCreation(String agentName, int contentsCount) {
    try {
      getCacheCreationsCounter()
          .add(1, Attributes.of(AttributeKey.stringKey("agent.name"), agentName));
    } catch (Exception e) {
      log.debug("Failed to record cache creation metric", e);
    }
  }

  /**
   * Record tokens saved by using cached content.
   *
   * <p>Called when processing an LLM response that used cached content. The token count represents
   * the number of tokens that were served from cache instead of being re-processed.
   *
   * @param agentName Name of the agent that saved tokens
   * @param tokenCount Number of tokens served from cache
   */
  public static void recordCachedTokensSaved(String agentName, long tokenCount) {
    try {
      getCachedTokensSavedCounter()
          .add(tokenCount, Attributes.of(AttributeKey.stringKey("agent.name"), agentName));
    } catch (Exception e) {
      log.debug("Failed to record cached tokens metric", e);
    }
  }

  /**
   * Gets the OpenTelemetry Meter for custom metric recording.
   *
   * @return The Meter instance
   */
  public static Meter getMeter() {
    return GlobalOpenTelemetry.getMeter("gcp.vertex.agent");
  }

  /**
   * Resets metric counters for testing.
   *
   * <p>This method is package-private and should only be used in tests to reset the
   * lazy-initialized metric counters after GlobalOpenTelemetry.resetForTest() is called.
   */
  static void resetMetricsForTest() {
    synchronized (Telemetry.class) {
      cacheHitsCounter = null;
      cacheMissesCounter = null;
      cacheCreationsCounter = null;
      cachedTokensSavedCounter = null;
    }
  }

  /**
   * Traces tool call arguments.
   *
   * @param args The arguments to the tool call.
   */
  public static void traceToolCall(Map<String, Object> args) {
    Span span = Span.current();
    if (span == null || !span.getSpanContext().isValid()) {
      log.trace("traceToolCall: No valid span in current context.");
      return;
    }

    span.setAttribute("gen_ai.system", "gcp.vertex.agent");
    try {
      span.setAttribute(
          "gcp.vertex.agent.tool_call_args", JsonBaseModel.getMapper().writeValueAsString(args));
    } catch (JsonProcessingException e) {
      log.warn("traceToolCall: Failed to serialize tool call args to JSON", e);
    }
  }

  /**
   * Traces tool response event.
   *
   * @param invocationContext The invocation context for the current agent run.
   * @param eventId The ID of the event.
   * @param functionResponseEvent The function response event.
   */
  public static void traceToolResponse(
      InvocationContext invocationContext, String eventId, Event functionResponseEvent) {
    Span span = Span.current();
    if (span == null || !span.getSpanContext().isValid()) {
      log.trace("traceToolResponse: No valid span in current context.");
      return;
    }

    span.setAttribute("gen_ai.system", "gcp.vertex.agent");
    span.setAttribute("gcp.vertex.agent.invocation_id", invocationContext.invocationId());
    span.setAttribute("gcp.vertex.agent.event_id", eventId);
    span.setAttribute("gcp.vertex.agent.tool_response", functionResponseEvent.toJson());

    // Setting empty llm request and response (as the AdkDevServer UI expects these)
    span.setAttribute("gcp.vertex.agent.llm_request", "{}");
    span.setAttribute("gcp.vertex.agent.llm_response", "{}");
    if (invocationContext.session() != null && invocationContext.session().id() != null) {
      span.setAttribute("gcp.vertex.agent.session_id", invocationContext.session().id());
    }
  }

  /**
   * Builds a dictionary representation of the LLM request for tracing. {@code GenerationConfig} is
   * included as a whole. For other fields like {@code Content}, parts that cannot be easily
   * serialized or are not needed for the trace (e.g., inlineData) are excluded.
   *
   * @param llmRequest The LlmRequest object.
   * @return A Map representation of the LLM request for tracing.
   */
  private static Map<String, Object> buildLlmRequestForTrace(LlmRequest llmRequest) {
    Map<String, Object> result = new HashMap<>();
    result.put("model", llmRequest.model().orElse(null));
    llmRequest.config().ifPresent(config -> result.put("config", config));

    List<Content> contentsList = new ArrayList<>();
    for (Content content : llmRequest.contents()) {
      ImmutableList<Part> filteredParts =
          content.parts().orElse(ImmutableList.of()).stream()
              .filter(part -> part.inlineData().isEmpty())
              .collect(toImmutableList());

      Content.Builder contentBuilder = Content.builder();
      content.role().ifPresent(contentBuilder::role);
      contentBuilder.parts(filteredParts);
      contentsList.add(contentBuilder.build());
    }
    result.put("contents", contentsList);
    return result;
  }

  /**
   * Traces a call to the LLM.
   *
   * @param invocationContext The invocation context.
   * @param eventId The ID of the event associated with this LLM call/response.
   * @param llmRequest The LLM request object.
   * @param llmResponse The LLM response object.
   */
  public static void traceCallLlm(
      InvocationContext invocationContext,
      String eventId,
      LlmRequest llmRequest,
      LlmResponse llmResponse) {
    Span span = Span.current();
    if (span == null || !span.getSpanContext().isValid()) {
      log.trace("traceCallLlm: No valid span in current context.");
      return;
    }

    span.setAttribute("gen_ai.system", "gcp.vertex.agent");
    llmRequest.model().ifPresent(modelName -> span.setAttribute("gen_ai.request.model", modelName));
    span.setAttribute("gcp.vertex.agent.invocation_id", invocationContext.invocationId());
    span.setAttribute("gcp.vertex.agent.event_id", eventId);

    if (invocationContext.session() != null && invocationContext.session().id() != null) {
      span.setAttribute("gcp.vertex.agent.session_id", invocationContext.session().id());
    } else {
      log.trace(
          "traceCallLlm: InvocationContext session or session ID is null, cannot set"
              + " gcp.vertex.agent.session_id");
    }

    try {
      span.setAttribute(
          "gcp.vertex.agent.llm_request",
          JsonBaseModel.getMapper().writeValueAsString(buildLlmRequestForTrace(llmRequest)));
      span.setAttribute("gcp.vertex.agent.llm_response", llmResponse.toJson());
    } catch (JsonProcessingException e) {
      log.warn("traceCallLlm: Failed to serialize LlmRequest or LlmResponse to JSON", e);
    }
  }

  /**
   * Traces the sending of data (history or new content) to the agent/model.
   *
   * @param invocationContext The invocation context.
   * @param eventId The ID of the event, if applicable.
   * @param data A list of content objects being sent.
   */
  public static void traceSendData(
      InvocationContext invocationContext, String eventId, List<Content> data) {
    Span span = Span.current();
    if (span == null || !span.getSpanContext().isValid()) {
      log.trace("traceSendData: No valid span in current context.");
      return;
    }

    span.setAttribute("gcp.vertex.agent.invocation_id", invocationContext.invocationId());
    if (eventId != null && !eventId.isEmpty()) {
      span.setAttribute("gcp.vertex.agent.event_id", eventId);
    }

    if (invocationContext.session() != null && invocationContext.session().id() != null) {
      span.setAttribute("gcp.vertex.agent.session_id", invocationContext.session().id());
    }

    try {
      List<Map<String, Object>> dataList = new ArrayList<>();
      if (data != null) {
        for (Content content : data) {
          if (content != null) {
            dataList.add(
                JsonBaseModel.getMapper()
                    .convertValue(content, new TypeReference<Map<String, Object>>() {}));
          }
        }
      }
      span.setAttribute("gcp.vertex.agent.data", JsonBaseModel.toJsonString(dataList));
    } catch (IllegalStateException e) {
      log.warn("traceSendData: Failed to serialize data to JSON", e);
    }
  }

  /**
   * Gets the tracer.
   *
   * @return The tracer.
   */
  public static Tracer getTracer() {
    return tracer;
  }

  /**
   * Executes a Flowable with an OpenTelemetry Scope active for its entire lifecycle.
   *
   * <p>This helper manages the OpenTelemetry Scope lifecycle for RxJava Flowables to ensure proper
   * context propagation across async boundaries. The scope remains active from when the Flowable is
   * returned through all operators until stream completion (onComplete, onError, or cancel).
   *
   * <p><b>Why not try-with-resources?</b> RxJava Flowables execute lazily - operators run at
   * subscription time, not at chain construction time. Using try-with-resources would close the
   * scope before the Flowable subscribes, causing Context.current() to return ROOT in nested
   * operations and breaking parent-child span relationships (fragmenting traces).
   *
   * <p>The scope is properly closed via doFinally when the stream terminates, ensuring no resource
   * leaks regardless of completion mode (success, error, or cancellation).
   *
   * @param spanContext The context containing the span to activate
   * @param span The span to end when the stream completes
   * @param flowableSupplier Supplier that creates the Flowable to execute with active scope
   * @param <T> The type of items emitted by the Flowable
   * @return Flowable with OpenTelemetry scope lifecycle management
   */
  @SuppressWarnings("MustBeClosedChecker") // Scope lifecycle managed by RxJava doFinally
  public static <T> Flowable<T> traceFlowable(
      Context spanContext, Span span, Supplier<Flowable<T>> flowableSupplier) {
    Scope scope = spanContext.makeCurrent();
    return flowableSupplier
        .get()
        .doFinally(
            () -> {
              scope.close();
              span.end();
            });
  }
}
