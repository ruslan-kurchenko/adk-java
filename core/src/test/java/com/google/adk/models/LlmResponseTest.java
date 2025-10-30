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

import static com.google.common.truth.Truth.assertThat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.JsonBaseModel;
import com.google.adk.models.cache.CacheMetadata;
import com.google.common.collect.ImmutableList;
import com.google.genai.types.Content;
import com.google.genai.types.FinishReason;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.GenerateContentResponseUsageMetadata;
import com.google.genai.types.Part;
import java.util.Optional;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class LlmResponseTest {

  private ObjectMapper objectMapper;

  @Before
  public void setUp() {
    objectMapper = JsonBaseModel.getMapper();
  }

  private Content createSampleContent(String text) {
    return Content.builder().parts(ImmutableList.of(Part.fromText(text))).build();
  }

  private Content createSampleFunctionCallContent(String functionName) {
    return Content.builder()
        .parts(
            ImmutableList.of(
                Part.builder()
                    .functionCall(FunctionCall.builder().name(functionName).build())
                    .build()))
        .build();
  }

  @Test
  public void testSerializationAndDeserialization_allFieldsPresent()
      throws JsonProcessingException {
    Content sampleContent = createSampleContent("Hello, world!");
    GenerateContentResponseUsageMetadata usageMetadata =
        GenerateContentResponseUsageMetadata.builder()
            .promptTokenCount(10)
            .candidatesTokenCount(20)
            .totalTokenCount(30)
            .build();
    LlmResponse originalResponse =
        LlmResponse.builder()
            .content(sampleContent)
            .partial(true)
            .turnComplete(false)
            .errorCode(new FinishReason("ERR_123"))
            .errorMessage(Optional.of("An error occurred."))
            .interrupted(Optional.of(true))
            .usageMetadata(usageMetadata)
            .build();

    String json = originalResponse.toJson();
    assertThat(json).isNotNull();

    JsonNode jsonNode = objectMapper.readTree(json);
    assertThat(jsonNode.has("content")).isTrue();
    assertThat(jsonNode.get("content").get("parts").get(0).get("text").asText())
        .isEqualTo("Hello, world!");
    assertThat(jsonNode.get("partial").asBoolean()).isTrue();
    assertThat(jsonNode.get("turnComplete").asBoolean()).isFalse();
    assertThat(jsonNode.get("errorCode").asText()).isEqualTo("ERR_123");
    assertThat(jsonNode.get("errorMessage").asText()).isEqualTo("An error occurred.");
    assertThat(jsonNode.get("interrupted").asBoolean()).isTrue();
    assertThat(jsonNode.has("usageMetadata")).isTrue();
    assertThat(jsonNode.get("usageMetadata").get("promptTokenCount").asInt()).isEqualTo(10);
    assertThat(jsonNode.get("usageMetadata").get("candidatesTokenCount").asInt()).isEqualTo(20);
    assertThat(jsonNode.get("usageMetadata").get("totalTokenCount").asInt()).isEqualTo(30);

    LlmResponse deserializedResponse = LlmResponse.fromJsonString(json, LlmResponse.class);

    assertThat(deserializedResponse).isEqualTo(originalResponse);
    assertThat(deserializedResponse.content()).hasValue(sampleContent);
    assertThat(deserializedResponse.partial()).hasValue(true);
    assertThat(deserializedResponse.turnComplete()).hasValue(false);
    assertThat(deserializedResponse.errorCode()).hasValue(new FinishReason("ERR_123"));
    assertThat(deserializedResponse.errorMessage()).hasValue("An error occurred.");
    assertThat(deserializedResponse.interrupted()).hasValue(true);
    assertThat(deserializedResponse.usageMetadata()).hasValue(usageMetadata);
  }

  @Test
  public void testSerializationAndDeserialization_optionalFieldsEmpty()
      throws JsonProcessingException {
    Content sampleContent = createSampleFunctionCallContent("tool_abc");
    LlmResponse originalResponse =
        LlmResponse.builder()
            .content(sampleContent)
            .groundingMetadata(Optional.empty())
            .partial(Optional.empty())
            .turnComplete(false)
            .errorCode(Optional.empty())
            .errorMessage(Optional.empty())
            .interrupted(Optional.empty())
            .usageMetadata(Optional.empty())
            .build();

    String json = originalResponse.toJson();
    assertThat(json).isNotNull();

    JsonNode jsonNode = objectMapper.readTree(json);
    assertThat(jsonNode.has("content")).isTrue();
    assertThat(jsonNode.has("groundingMetadata")).isFalse();
    assertThat(jsonNode.has("partial")).isFalse();
    assertThat(jsonNode.has("turnComplete")).isTrue();
    assertThat(jsonNode.get("turnComplete").asBoolean()).isFalse();
    assertThat(jsonNode.has("errorCode")).isFalse();
    assertThat(jsonNode.has("errorMessage")).isFalse();
    assertThat(jsonNode.has("interrupted")).isFalse();
    assertThat(jsonNode.has("usageMetadata")).isFalse();

    LlmResponse deserializedResponse = LlmResponse.fromJsonString(json, LlmResponse.class);

    assertThat(deserializedResponse).isEqualTo(originalResponse);
    assertThat(deserializedResponse.content()).hasValue(sampleContent);
    assertThat(deserializedResponse.groundingMetadata()).isEmpty();
    assertThat(deserializedResponse.partial()).isEmpty();
    assertThat(deserializedResponse.turnComplete()).hasValue(false);
    assertThat(deserializedResponse.errorCode()).isEmpty();
    assertThat(deserializedResponse.errorMessage()).isEmpty();
    assertThat(deserializedResponse.interrupted()).isEmpty();
    assertThat(deserializedResponse.usageMetadata()).isEmpty();
  }

  @Test
  public void testDeserialization_optionalFieldsNullInJson() throws JsonProcessingException {

    String jsonWithNulls =
        "{"
            + "\"content\": {\"parts\": [{\"text\": \"Test content\"}]},"
            + "\"groundingMetadata\": null,"
            + "\"partial\": null,"
            + "\"turnComplete\": true,"
            + "\"errorCode\": null,"
            + "\"errorMessage\": null,"
            + "\"interrupted\": null,"
            + "\"usageMetadata\": null"
            + "}";

    LlmResponse deserializedResponse = LlmResponse.fromJsonString(jsonWithNulls, LlmResponse.class);

    assertThat(deserializedResponse.content()).isPresent();
    assertThat(deserializedResponse.content().get().parts().get().get(0).text())
        .hasValue("Test content");
    assertThat(deserializedResponse.groundingMetadata()).isEmpty();
    assertThat(deserializedResponse.partial()).isEmpty();
    assertThat(deserializedResponse.turnComplete()).hasValue(true);
    assertThat(deserializedResponse.errorCode()).isEmpty();
    assertThat(deserializedResponse.errorMessage()).isEmpty();
    assertThat(deserializedResponse.interrupted()).isEmpty();
    assertThat(deserializedResponse.usageMetadata()).isEmpty();
  }

  @Test
  public void testDeserialization_someOptionalFieldsMissingSomePresent()
      throws JsonProcessingException {
    Content sampleContent = createSampleContent("Partial data");

    LlmResponse originalResponse =
        LlmResponse.builder()
            .content(sampleContent)
            .turnComplete(true)
            .errorCode(new FinishReason("FATAL_ERROR"))
            .build();

    String json = originalResponse.toJson();
    JsonNode jsonNode = objectMapper.readTree(json);

    assertThat(jsonNode.has("content")).isTrue();
    assertThat(jsonNode.has("partial")).isFalse();
    assertThat(jsonNode.has("turnComplete")).isTrue();
    assertThat(jsonNode.get("turnComplete").asBoolean()).isTrue();
    assertThat(jsonNode.has("errorCode")).isTrue();
    assertThat(jsonNode.get("errorCode").asText()).isEqualTo("FATAL_ERROR");
    assertThat(jsonNode.has("errorMessage")).isFalse();
    assertThat(jsonNode.has("interrupted")).isFalse();
    assertThat(jsonNode.has("usageMetadata")).isFalse();

    LlmResponse deserializedResponse = LlmResponse.fromJsonString(json, LlmResponse.class);
    assertThat(deserializedResponse).isEqualTo(originalResponse);

    assertThat(deserializedResponse.content()).isPresent();
    assertThat(deserializedResponse.content().get().parts().get().get(0).text())
        .hasValue("Partial data");
    assertThat(deserializedResponse.partial()).isEmpty();
    assertThat(deserializedResponse.turnComplete()).hasValue(true);
    assertThat(deserializedResponse.errorCode()).hasValue(new FinishReason("FATAL_ERROR"));
    assertThat(deserializedResponse.errorMessage()).isEmpty();
    assertThat(deserializedResponse.interrupted()).isEmpty();
    assertThat(deserializedResponse.usageMetadata()).isEmpty();
  }

  @Test
  public void cacheMetadata_setAndRetrieve() {
    CacheMetadata cacheMetadata =
        CacheMetadata.builder()
            .cacheName("cachedContents/test123")
            .expireTime(System.currentTimeMillis() / 1000 + 1800)
            .fingerprint("abc123def456")
            .contentsCount(10)
            .createdAt(System.currentTimeMillis() / 1000)
            .build();

    LlmResponse response =
        LlmResponse.builder()
            .content(createSampleContent("Test"))
            .cacheMetadata(cacheMetadata)
            .build();

    assertThat(response.cacheMetadata()).hasValue(cacheMetadata);
  }

  @Test
  public void cacheMetadata_notSet_returnsEmpty() {
    LlmResponse response = LlmResponse.builder().content(createSampleContent("Test")).build();

    assertThat(response.cacheMetadata()).isEmpty();
  }

  @Test
  public void cacheMetadata_serializesToJson() throws JsonProcessingException {
    CacheMetadata cacheMetadata =
        CacheMetadata.builder().fingerprint("fingerprint123").contentsCount(7).build();

    LlmResponse response =
        LlmResponse.builder()
            .content(createSampleContent("Test"))
            .cacheMetadata(cacheMetadata)
            .build();

    String json = response.toJson();

    assertThat(json).contains("\"cacheMetadata\":");
    assertThat(json).contains("\"fingerprint\":\"fingerprint123\"");
    assertThat(json).contains("\"contents_count\":7");
  }

  @Test
  public void cacheMetadata_deserializesFromJson() throws JsonProcessingException {
    String json =
        "{"
            + "\"content\": {\"parts\": [{\"text\": \"Test\"}]},"
            + "\"cacheMetadata\": {"
            + "\"fingerprint\": \"test_fingerprint\","
            + "\"contents_count\": 15"
            + "}"
            + "}";

    LlmResponse response = LlmResponse.fromJsonString(json, LlmResponse.class);

    assertThat(response.cacheMetadata()).isPresent();
    assertThat(response.cacheMetadata().get().fingerprint()).isEqualTo("test_fingerprint");
    assertThat(response.cacheMetadata().get().contentsCount()).isEqualTo(15);
  }

  @Test
  public void toBuilder_preservesCacheMetadata() {
    CacheMetadata cacheMetadata =
        CacheMetadata.builder().fingerprint("preserved123").contentsCount(5).build();

    LlmResponse original =
        LlmResponse.builder()
            .content(createSampleContent("Original"))
            .cacheMetadata(cacheMetadata)
            .build();

    LlmResponse rebuilt = original.toBuilder().turnComplete(true).build();

    assertThat(rebuilt.cacheMetadata()).hasValue(cacheMetadata);
    assertThat(rebuilt.turnComplete()).hasValue(true);
  }
}
