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

package com.google.adk.models;

import static com.google.common.base.StandardSystemProperty.JAVA_VERSION;

import com.google.adk.Version;
import com.google.adk.models.cache.CacheMetadata;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.genai.Client;
import com.google.genai.ResponseStream;
import com.google.genai.types.Candidate;
import com.google.genai.types.Content;
import com.google.genai.types.FinishReason;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.HttpOptions;
import com.google.genai.types.LiveConnectConfig;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Flowable;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutionException;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Represents the Gemini Generative AI model.
 *
 * <p>This class provides methods for interacting with the Gemini model, including standard
 * request-response generation and establishing persistent bidirectional connections.
 */
public class Gemini extends BaseLlm {

  private static final Logger logger = LoggerFactory.getLogger(Gemini.class);
  private static final ImmutableMap<String, String> TRACKING_HEADERS;

  static {
    String frameworkLabel = "google-adk/" + Version.JAVA_ADK_VERSION;
    String languageLabel = "gl-java/" + JAVA_VERSION.value();
    String versionHeaderValue = String.format("%s %s", frameworkLabel, languageLabel);

    TRACKING_HEADERS =
        ImmutableMap.of(
            "x-goog-api-client", versionHeaderValue,
            "user-agent", versionHeaderValue);
  }

  private final Client apiClient;

  /**
   * Constructs a new Gemini instance.
   *
   * @param modelName The name of the Gemini model to use (e.g., "gemini-2.0-flash").
   * @param apiClient The genai {@link com.google.genai.Client} instance for making API calls.
   */
  public Gemini(String modelName, Client apiClient) {
    super(modelName);
    this.apiClient = Objects.requireNonNull(apiClient, "apiClient cannot be null");
  }

  /**
   * Constructs a new Gemini instance with a Google Gemini API key.
   *
   * @param modelName The name of the Gemini model to use (e.g., "gemini-2.0-flash").
   * @param apiKey The Google Gemini API key.
   */
  public Gemini(String modelName, String apiKey) {
    super(modelName);
    Objects.requireNonNull(apiKey, "apiKey cannot be null");
    this.apiClient =
        Client.builder()
            .apiKey(apiKey)
            .httpOptions(HttpOptions.builder().headers(TRACKING_HEADERS).build())
            .build();
  }

  /**
   * Constructs a new Gemini instance with a Google Gemini API key.
   *
   * @param modelName The name of the Gemini model to use (e.g., "gemini-2.0-flash").
   * @param vertexCredentials The Vertex AI credentials to access the Gemini model.
   */
  public Gemini(String modelName, VertexCredentials vertexCredentials) {
    super(modelName);
    Objects.requireNonNull(vertexCredentials, "vertexCredentials cannot be null");
    Client.Builder apiClientBuilder =
        Client.builder().httpOptions(HttpOptions.builder().headers(TRACKING_HEADERS).build());
    vertexCredentials.project().ifPresent(apiClientBuilder::project);
    vertexCredentials.location().ifPresent(apiClientBuilder::location);
    vertexCredentials.credentials().ifPresent(apiClientBuilder::credentials);
    this.apiClient = apiClientBuilder.build();
  }

  /**
   * Returns a new Builder instance for constructing Gemini objects. Note that when building a
   * Gemini object, at least one of apiKey, vertexCredentials, or an explicit apiClient must be set.
   * If multiple are set, the explicit apiClient will take precedence.
   *
   * @return A new {@link Builder}.
   */
  public static Builder builder() {
    return new Builder();
  }

  /** Builder for {@link Gemini}. */
  public static class Builder {
    private String modelName;
    private Client apiClient;
    private String apiKey;
    private VertexCredentials vertexCredentials;

    private Builder() {}

    /**
     * Sets the name of the Gemini model to use.
     *
     * @param modelName The model name (e.g., "gemini-2.0-flash").
     * @return This builder.
     */
    @CanIgnoreReturnValue
    public Builder modelName(String modelName) {
      this.modelName = modelName;
      return this;
    }

    /**
     * Sets the explicit {@link com.google.genai.Client} instance for making API calls. If this is
     * set, apiKey and vertexCredentials will be ignored.
     *
     * @param apiClient The client instance.
     * @return This builder.
     */
    @CanIgnoreReturnValue
    public Builder apiClient(Client apiClient) {
      this.apiClient = apiClient;
      return this;
    }

    /**
     * Sets the Google Gemini API key. If {@link #apiClient(Client)} is also set, the explicit
     * client will take precedence. If {@link #vertexCredentials(VertexCredentials)} is also set,
     * this apiKey will take precedence.
     *
     * @param apiKey The API key.
     * @return This builder.
     */
    @CanIgnoreReturnValue
    public Builder apiKey(String apiKey) {
      this.apiKey = apiKey;
      return this;
    }

    /**
     * Sets the Vertex AI credentials. If {@link #apiClient(Client)} or {@link #apiKey(String)} are
     * also set, they will take precedence over these credentials.
     *
     * @param vertexCredentials The Vertex AI credentials.
     * @return This builder.
     */
    @CanIgnoreReturnValue
    public Builder vertexCredentials(VertexCredentials vertexCredentials) {
      this.vertexCredentials = vertexCredentials;
      return this;
    }

    /**
     * Builds the {@link Gemini} instance.
     *
     * @return A new {@link Gemini} instance.
     * @throws NullPointerException if modelName is null.
     */
    public Gemini build() {
      Objects.requireNonNull(modelName, "modelName must be set.");

      if (apiClient != null) {
        return new Gemini(modelName, apiClient);
      } else if (apiKey != null) {
        return new Gemini(modelName, apiKey);
      } else if (vertexCredentials != null) {
        return new Gemini(modelName, vertexCredentials);
      } else {
        return new Gemini(
            modelName,
            Client.builder()
                .httpOptions(HttpOptions.builder().headers(TRACKING_HEADERS).build())
                .build());
      }
    }
  }

  @Override
  public Flowable<LlmResponse> generateContent(LlmRequest llmRequest, boolean stream) {
    llmRequest =
        GeminiUtil.prepareGenenerateContentRequest(
            llmRequest, !apiClient.vertexAI(), /* stripThoughts= */ false);
    GenerateContentConfig config = llmRequest.config().orElse(null);

    // Inject cached content ID if cache is active
    config = injectCachedContentId(llmRequest, config);

    // Remove cached contents from request (matches Python implementation)
    // Python: llm_request.contents = llm_request.contents[cache_contents_count
    if (llmRequest.cacheMetadata().isPresent()
        && llmRequest.cacheMetadata().get().isActiveCache()) {

      int cachedCount = llmRequest.cacheMetadata().get().contentsCount();
      List<Content> allContents = llmRequest.contents();

      logger.info("Contents removal in Gemini.generateContent():");
      logger.info("  - cachedCount: {}", cachedCount);
      logger.info("  - allContents.size(): {}", allContents.size());

      if (cachedCount > 0 && allContents.size() > cachedCount) {
        List<Content> uncachedContents = allContents.subList(cachedCount, allContents.size());
        llmRequest = llmRequest.toBuilder().contents(uncachedContents).build();
        logger.info(
            "Removed {} cached contents, sending {} uncached contents to Gemini",
            cachedCount,
            uncachedContents.size());
      } else if (cachedCount > 0 && allContents.size() == cachedCount) {
        llmRequest = llmRequest.toBuilder().contents(ImmutableList.of()).build();
        logger.info("All {} contents cached, sending empty contents list", cachedCount);
      } else if (allContents.size() < cachedCount) {
        logger.warn(
            "Request has fewer contents ({}) than cached ({}), sending all contents",
            allContents.size(),
            cachedCount);
      }
    }

    String effectiveModelName = llmRequest.model().orElse(model());

    logger.trace("Request Contents: {}", llmRequest.contents());
    logger.trace("Request Config: {}", config);

    if (stream) {
      logger.debug("Sending streaming generateContent request to model {}", effectiveModelName);
      CompletableFuture<ResponseStream<GenerateContentResponse>> streamFuture =
          apiClient.async.models.generateContentStream(
              effectiveModelName, llmRequest.contents(), config);

      return Flowable.defer(
              () ->
                  processRawResponses(
                      Flowable.fromFuture(streamFuture).flatMapIterable(iterable -> iterable)))
          .filter(
              llmResponse ->
                  llmResponse
                      .content()
                      .flatMap(Content::parts)
                      .map(
                          parts ->
                              !parts.isEmpty()
                                  && parts.stream()
                                      .anyMatch(
                                          p ->
                                              p.functionCall().isPresent()
                                                  || p.functionResponse().isPresent()
                                                  || p.text().map(t -> !t.isBlank()).orElse(false)))
                      .orElse(false));
    } else {
      logger.debug("Sending generateContent request to model {}", effectiveModelName);
      return Flowable.fromFuture(
          apiClient
              .async
              .models
              .generateContent(effectiveModelName, llmRequest.contents(), config)
              .thenApplyAsync(LlmResponse::create));
    }
  }

  static Flowable<LlmResponse> processRawResponses(Flowable<GenerateContentResponse> rawResponses) {
    final StringBuilder accumulatedText = new StringBuilder();
    final StringBuilder accumulatedThoughtText = new StringBuilder();
    // Array to bypass final local variable reassignment in lambda.
    final GenerateContentResponse[] lastRawResponseHolder = {null};
    return rawResponses
        .concatMap(
            rawResponse -> {
              lastRawResponseHolder[0] = rawResponse;
              logger.trace("Raw streaming response: {}", rawResponse);

              List<LlmResponse> responsesToEmit = new ArrayList<>();
              LlmResponse currentProcessedLlmResponse = LlmResponse.create(rawResponse);
              Optional<Part> part = GeminiUtil.getPart0FromLlmResponse(currentProcessedLlmResponse);
              String currentTextChunk = part.flatMap(Part::text).orElse("");

              if (!currentTextChunk.isBlank()) {
                if (part.get().thought().orElse(false)) {
                  accumulatedThoughtText.append(currentTextChunk);
                  responsesToEmit.add(
                      thinkingResponseFromText(currentTextChunk).toBuilder().partial(true).build());
                } else {
                  accumulatedText.append(currentTextChunk);
                  responsesToEmit.add(
                      responseFromText(currentTextChunk).toBuilder().partial(true).build());
                }
              } else {
                if (accumulatedThoughtText.length() > 0
                    && GeminiUtil.shouldEmitAccumulatedText(currentProcessedLlmResponse)) {
                  LlmResponse aggregatedThoughtResponse =
                      thinkingResponseFromText(accumulatedThoughtText.toString());
                  responsesToEmit.add(aggregatedThoughtResponse);
                  accumulatedThoughtText.setLength(0);
                }
                if (accumulatedText.length() > 0
                    && GeminiUtil.shouldEmitAccumulatedText(currentProcessedLlmResponse)) {
                  LlmResponse aggregatedTextResponse = responseFromText(accumulatedText.toString());
                  responsesToEmit.add(aggregatedTextResponse);
                  accumulatedText.setLength(0);
                }
                responsesToEmit.add(currentProcessedLlmResponse);
              }
              logger.debug("Responses to emit: {}", responsesToEmit);
              return Flowable.fromIterable(responsesToEmit);
            })
        .concatWith(
            Flowable.defer(
                () -> {
                  GenerateContentResponse finalRawResp = lastRawResponseHolder[0];
                  if (finalRawResp == null) {
                    return Flowable.empty();
                  }
                  boolean isStop =
                      finalRawResp
                          .candidates()
                          .flatMap(candidates -> candidates.stream().findFirst())
                          .flatMap(Candidate::finishReason)
                          .map(finishReason -> finishReason.knownEnum() == FinishReason.Known.STOP)
                          .orElse(false);

                  if (isStop) {
                    List<LlmResponse> finalResponses = new ArrayList<>();
                    if (accumulatedThoughtText.length() > 0) {
                      finalResponses.add(
                          thinkingResponseFromText(accumulatedThoughtText.toString()));
                    }
                    if (accumulatedText.length() > 0) {
                      finalResponses.add(responseFromText(accumulatedText.toString()));
                    }
                    return Flowable.fromIterable(finalResponses);
                  }
                  return Flowable.empty();
                }));
  }

  private static LlmResponse responseFromText(String accumulatedText) {
    return LlmResponse.builder()
        .content(Content.builder().role("model").parts(Part.fromText(accumulatedText)).build())
        .build();
  }

  private static LlmResponse thinkingResponseFromText(String accumulatedThoughtText) {
    return LlmResponse.builder()
        .content(
            Content.builder()
                .role("model")
                .parts(Part.fromText(accumulatedThoughtText).toBuilder().thought(true).build())
                .build())
        .build();
  }

  /**
   * Injects cached content ID into GenerateContentConfig if cache is active.
   *
   * <p>When cache metadata indicates an active cache, builds a fresh config with cachedContent
   * reference and all original settings EXCEPT systemInstruction (already in the cache).
   *
   * @param llmRequest Request that may contain cache metadata
   * @param config Current GenerateContentConfig (may be null)
   * @return Updated config with cachedContent field if applicable
   */
  private GenerateContentConfig injectCachedContentId(
      LlmRequest llmRequest, GenerateContentConfig config) {

    return llmRequest
        .cacheMetadata()
        .filter(CacheMetadata::isActiveCache)
        .flatMap(CacheMetadata::cacheName)
        .map(cacheName -> buildConfigWithCachedContent(cacheName, config))
        .orElse(config);
  }

  /**
   * Builds GenerateContentConfig for cached content requests.
   *
   * <p>Creates a fresh config with cachedContent reference and copies all settings from original
   * config EXCEPT systemInstruction (which is already in the cache).
   *
   * <p>Gemini API rejects requests with both cachedContent and systemInstruction, so
   * systemInstruction is explicitly excluded.
   *
   * @param cacheName The cache resource name to reference
   * @param originalConfig Original config to copy settings from (may be null)
   * @return New config with cached content
   */
  private GenerateContentConfig buildConfigWithCachedContent(
      String cacheName, @Nullable GenerateContentConfig originalConfig) {

    logger.debug("Building config with cached content: {}", cacheName);

    GenerateContentConfig.Builder builder = GenerateContentConfig.builder();
    builder.cachedContent(cacheName);

    if (originalConfig != null) {
      copyNonCachedFields(originalConfig, builder);
    }

    GenerateContentConfig builtConfig = builder.build();

    logger.debug("Config built without systemInstruction (already in cache)");
    logger.info("Cached content request config:");
    logger.info("  - cachedContent: {}", cacheName);
    logger.info(
        "  - tools: {}",
        builtConfig.tools().isPresent() ? builtConfig.tools().get().size() + " tools" : "NONE");
    logger.info("  - toolConfig: {}", builtConfig.toolConfig().isPresent() ? "present" : "NONE");
    logger.info(
        "  - systemInstruction: {}",
        builtConfig.systemInstruction().isPresent() ? "PRESENT (BUG!)" : "excluded");

    return builtConfig;
  }

  /**
   * Copies non-cached fields from original config to builder.
   *
   * <p>When using cached content, systemInstruction, tools, and toolConfig are already in the cache
   * and must NOT be included in the request (matches Python ADK implementation).
   *
   * <p>Fields copied (response-related only):
   *
   * <ul>
   *   <li>responseModalities
   *   <li>safetySettings
   * </ul>
   *
   * <p>Fields excluded (already in CachedContent):
   *
   * <ul>
   *   <li>systemInstruction
   *   <li>tools
   *   <li>toolConfig
   * </ul>
   *
   * @param original Original config to copy from
   * @param builder Builder to copy fields to
   */
  private void copyNonCachedFields(
      GenerateContentConfig original, GenerateContentConfig.Builder builder) {

    original.responseModalities().ifPresent(builder::responseModalities);
    original.safetySettings().ifPresent(builder::safetySettings);
  }
  @Override
  public BaseLlmConnection connect(LlmRequest llmRequest) {
    if (!apiClient.vertexAI()) {
      llmRequest = GeminiUtil.sanitizeRequestForGeminiApi(llmRequest);
    }
    logger.debug("Establishing Gemini connection.");
    LiveConnectConfig liveConnectConfig = llmRequest.liveConnectConfig();

    // TODO: Inject cached content into LiveConnectConfig when SDK supports it
    // Currently LiveConnectConfig.Builder does not have cachedContent() method
    if (llmRequest.cacheMetadata().isPresent()
        && llmRequest.cacheMetadata().get().isActiveCache()) {
      logger.warn(
          "Context caching is not yet supported for Live mode (runLive). "
              + "Cache will be ignored for this connection. Use runAsync for cached requests.");
    }

    String effectiveModelName = llmRequest.model().orElse(model());

    logger.debug("Connecting to model {}", effectiveModelName);
    logger.trace("Connection Config: {}", liveConnectConfig);

    return new GeminiLlmConnection(apiClient, effectiveModelName, liveConnectConfig);
  }
}
