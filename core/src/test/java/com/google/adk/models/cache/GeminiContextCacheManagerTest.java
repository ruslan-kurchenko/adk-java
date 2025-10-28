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

package com.google.adk.models.cache;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.mock;

import com.google.adk.apps.ContextCacheConfig;
import com.google.adk.models.LlmRequest;
import com.google.common.collect.ImmutableList;
import com.google.genai.Client;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Completable;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link GeminiContextCacheManager}. */
@RunWith(JUnit4.class)
public class GeminiContextCacheManagerTest {

  private static final String TEST_PROJECT_ID = "test-project-123";
  private static final String TEST_MODEL = "gemini-2.5-flash";
  private static final String TEST_SYSTEM_INSTRUCTION = "You are a helpful assistant.";

  private GeminiContextCacheManager manager;
  private Client mockClient;

  @Before
  public void setUp() {
    mockClient = mock(Client.class);
    manager = new GeminiContextCacheManager(mockClient, TEST_PROJECT_ID);
  }

  @Test
  public void constructor_withNullClient_throwsNullPointerException() {
    assertThrows(
        NullPointerException.class, () -> new GeminiContextCacheManager(null, TEST_PROJECT_ID));
  }

  @Test
  public void constructor_withNullProjectId_succeeds() {
    GeminiContextCacheManager managerWithoutProject =
        new GeminiContextCacheManager(mockClient, null);
    assertThat(managerWithoutProject).isNotNull();
  }

  @Test
  public void generateCacheFingerprint_withSystemInstruction_returnsConsistentHash() {
    LlmRequest request =
        LlmRequest.builder()
            .model(TEST_MODEL)
            .config(
                com.google.genai.types.GenerateContentConfig.builder()
                    .systemInstruction(
                        Content.builder()
                            .parts(
                                ImmutableList.of(
                                    Part.builder().text(TEST_SYSTEM_INSTRUCTION).build()))
                            .build())
                    .build())
            .build();

    String fingerprint1 = manager.generateCacheFingerprint(request, 5);
    String fingerprint2 = manager.generateCacheFingerprint(request, 5);

    assertThat(fingerprint1).isEqualTo(fingerprint2);
    assertThat(fingerprint1).hasLength(64); // SHA-256 produces 64 hex characters
  }

  @Test
  public void generateCacheFingerprint_differentSystemInstructions_returnsDifferentHashes() {
    LlmRequest request1 =
        LlmRequest.builder()
            .model(TEST_MODEL)
            .config(
                com.google.genai.types.GenerateContentConfig.builder()
                    .systemInstruction(
                        Content.builder()
                            .parts(ImmutableList.of(Part.builder().text("Instruction 1").build()))
                            .build())
                    .build())
            .build();

    LlmRequest request2 =
        LlmRequest.builder()
            .model(TEST_MODEL)
            .config(
                com.google.genai.types.GenerateContentConfig.builder()
                    .systemInstruction(
                        Content.builder()
                            .parts(ImmutableList.of(Part.builder().text("Instruction 2").build()))
                            .build())
                    .build())
            .build();

    String fingerprint1 = manager.generateCacheFingerprint(request1, 5);
    String fingerprint2 = manager.generateCacheFingerprint(request2, 5);

    assertThat(fingerprint1).isNotEqualTo(fingerprint2);
  }

  @Test
  public void generateCacheFingerprint_differentModels_returnsDifferentHashes() {
    LlmRequest request1 =
        LlmRequest.builder()
            .model("gemini-2.0-flash")
            .config(
                com.google.genai.types.GenerateContentConfig.builder()
                    .systemInstruction(
                        Content.builder()
                            .parts(
                                ImmutableList.of(
                                    Part.builder().text(TEST_SYSTEM_INSTRUCTION).build()))
                            .build())
                    .build())
            .build();

    LlmRequest request2 =
        LlmRequest.builder()
            .model("gemini-2.5-flash")
            .config(
                com.google.genai.types.GenerateContentConfig.builder()
                    .systemInstruction(
                        Content.builder()
                            .parts(
                                ImmutableList.of(
                                    Part.builder().text(TEST_SYSTEM_INSTRUCTION).build()))
                            .build())
                    .build())
            .build();

    String fingerprint1 = manager.generateCacheFingerprint(request1, 5);
    String fingerprint2 = manager.generateCacheFingerprint(request2, 5);

    assertThat(fingerprint1).isNotEqualTo(fingerprint2);
  }

  @Test
  public void generateCacheFingerprint_differentContentsCount_returnsDifferentHashes() {
    LlmRequest request =
        LlmRequest.builder()
            .model(TEST_MODEL)
            .config(
                com.google.genai.types.GenerateContentConfig.builder()
                    .systemInstruction(
                        Content.builder()
                            .parts(
                                ImmutableList.of(
                                    Part.builder().text(TEST_SYSTEM_INSTRUCTION).build()))
                            .build())
                    .build())
            .build();

    String fingerprint1 = manager.generateCacheFingerprint(request, 5);
    String fingerprint2 = manager.generateCacheFingerprint(request, 10);

    assertThat(fingerprint1).isNotEqualTo(fingerprint2);
  }

  @Test
  public void generateCacheFingerprint_noSystemInstruction_generatesFingerprint() {
    LlmRequest request = LlmRequest.builder().model(TEST_MODEL).build();

    String fingerprint = manager.generateCacheFingerprint(request, 5);

    assertThat(fingerprint).isNotEmpty();
    assertThat(fingerprint).hasLength(64);
  }

  @Test
  public void generateCacheFingerprint_multipleSystemInstructions_includesAll() {
    LlmRequest request =
        LlmRequest.builder()
            .model(TEST_MODEL)
            .config(
                com.google.genai.types.GenerateContentConfig.builder()
                    .systemInstruction(
                        Content.builder()
                            .parts(
                                ImmutableList.of(
                                    Part.builder().text("Instruction 1").build(),
                                    Part.builder().text("Instruction 2").build()))
                            .build())
                    .build())
            .build();

    String fingerprint = manager.generateCacheFingerprint(request, 5);

    assertThat(fingerprint).isNotEmpty();
  }

  @Test
  public void handleContextCaching_withoutCacheConfig_throwsException() {
    LlmRequest request = LlmRequest.builder().model(TEST_MODEL).build();

    IllegalArgumentException exception =
        assertThrows(
            IllegalArgumentException.class,
            () -> manager.handleContextCaching(request, 5).blockingGet());

    assertThat(exception).hasMessageThat().contains("must have cacheConfig");
  }

  @Test
  public void handleContextCaching_noExistingMetadata_returnsFingerprintOnly() {
    ContextCacheConfig cacheConfig = ContextCacheConfig.builder().build();
    LlmRequest request =
        LlmRequest.builder()
            .model(TEST_MODEL)
            .cacheConfig(cacheConfig)
            .config(
                com.google.genai.types.GenerateContentConfig.builder()
                    .systemInstruction(
                        Content.builder()
                            .parts(
                                ImmutableList.of(
                                    Part.builder().text(TEST_SYSTEM_INSTRUCTION).build()))
                            .build())
                    .build())
            .build();

    CacheMetadata result = manager.handleContextCaching(request, 5).blockingGet();

    assertThat(result.isActiveCache()).isFalse();
    assertThat(result.fingerprint()).isNotEmpty();
    assertThat(result.contentsCount()).isEqualTo(5);
    assertThat(result.cacheName()).isEmpty();
  }

  @Test
  public void handleContextCaching_validActiveCache_reusesCache() {
    ContextCacheConfig cacheConfig = ContextCacheConfig.builder().cacheIntervals(10).build();
    String fingerprint = "abc123fingerprint";
    long futureExpireTime = System.currentTimeMillis() / 1000 + 1800; // 30 min future

    CacheMetadata existingMetadata =
        CacheMetadata.builder()
            .cacheName("cachedContents/test123")
            .expireTime(futureExpireTime)
            .fingerprint(fingerprint)
            .invocationsUsed(5)
            .contentsCount(5)
            .createdAt(System.currentTimeMillis() / 1000)
            .build();

    LlmRequest request =
        LlmRequest.builder()
            .model(TEST_MODEL)
            .cacheConfig(cacheConfig)
            .cacheMetadata(existingMetadata)
            .config(
                com.google.genai.types.GenerateContentConfig.builder()
                    .systemInstruction(
                        Content.builder()
                            .parts(
                                ImmutableList.of(
                                    Part.builder().text(TEST_SYSTEM_INSTRUCTION).build()))
                            .build())
                    .build())
            .build();

    // Generate same fingerprint for this request
    String currentFingerprint = manager.generateCacheFingerprint(request, 5);

    // Update existing metadata to have matching fingerprint
    CacheMetadata matchingMetadata =
        existingMetadata.toBuilder().fingerprint(currentFingerprint).build();
    LlmRequest requestWithMatchingFingerprint =
        request.toBuilder().cacheMetadata(matchingMetadata).build();

    CacheMetadata result =
        manager.handleContextCaching(requestWithMatchingFingerprint, 5).blockingGet();

    assertThat(result.isActiveCache()).isTrue();
    assertThat(result.cacheName()).hasValue("cachedContents/test123");
  }

  @Test
  public void handleContextCaching_expiredCache_returnsFingerprintOnly() {
    ContextCacheConfig cacheConfig = ContextCacheConfig.builder().build();
    long pastExpireTime = System.currentTimeMillis() / 1000 - 60; // 1 min ago
    String fingerprint = "def456fingerprint";

    CacheMetadata expiredMetadata =
        CacheMetadata.builder()
            .cacheName("cachedContents/expired")
            .expireTime(pastExpireTime)
            .fingerprint(fingerprint)
            .invocationsUsed(2)
            .contentsCount(5)
            .build();

    LlmRequest request =
        LlmRequest.builder()
            .model(TEST_MODEL)
            .cacheConfig(cacheConfig)
            .cacheMetadata(expiredMetadata)
            .config(
                com.google.genai.types.GenerateContentConfig.builder()
                    .systemInstruction(
                        Content.builder()
                            .parts(
                                ImmutableList.of(
                                    Part.builder().text(TEST_SYSTEM_INSTRUCTION).build()))
                            .build())
                    .build())
            .build();

    CacheMetadata result = manager.handleContextCaching(request, 5).blockingGet();

    // Should return fingerprint-only metadata (cache was invalid)
    assertThat(result.cacheName()).isEmpty();
    assertThat(result.fingerprint()).isNotEmpty();
  }

  @Test
  public void handleContextCaching_invocationsExceeded_returnsFingerprintOnly() {
    ContextCacheConfig cacheConfig = ContextCacheConfig.builder().cacheIntervals(10).build();
    long futureExpireTime = System.currentTimeMillis() / 1000 + 1800;
    String fingerprint = "ghi789fingerprint";

    CacheMetadata exceededMetadata =
        CacheMetadata.builder()
            .cacheName("cachedContents/exceeded")
            .expireTime(futureExpireTime)
            .fingerprint(fingerprint)
            .invocationsUsed(15) // Exceeds limit of 10
            .contentsCount(5)
            .build();

    LlmRequest request =
        LlmRequest.builder()
            .model(TEST_MODEL)
            .cacheConfig(cacheConfig)
            .cacheMetadata(exceededMetadata)
            .config(
                com.google.genai.types.GenerateContentConfig.builder()
                    .systemInstruction(
                        Content.builder()
                            .parts(
                                ImmutableList.of(
                                    Part.builder().text(TEST_SYSTEM_INSTRUCTION).build()))
                            .build())
                    .build())
            .build();

    CacheMetadata result = manager.handleContextCaching(request, 5).blockingGet();

    assertThat(result.cacheName()).isEmpty();
  }

  @Test
  public void handleContextCaching_fingerprintMismatch_returnsFingerprintOnly() {
    ContextCacheConfig cacheConfig = ContextCacheConfig.builder().build();
    long futureExpireTime = System.currentTimeMillis() / 1000 + 1800;

    CacheMetadata oldMetadata =
        CacheMetadata.builder()
            .cacheName("cachedContents/old")
            .expireTime(futureExpireTime)
            .fingerprint("old-fingerprint-that-wont-match")
            .invocationsUsed(2)
            .contentsCount(5)
            .build();

    LlmRequest request =
        LlmRequest.builder()
            .model(TEST_MODEL)
            .cacheConfig(cacheConfig)
            .cacheMetadata(oldMetadata)
            .config(
                com.google.genai.types.GenerateContentConfig.builder()
                    .systemInstruction(
                        Content.builder()
                            .parts(
                                ImmutableList.of(
                                    Part.builder().text("DIFFERENT system instruction").build()))
                            .build())
                    .build())
            .build();

    CacheMetadata result = manager.handleContextCaching(request, 5).blockingGet();

    assertThat(result.cacheName()).isEmpty();
    assertThat(result.fingerprint()).isNotEqualTo("old-fingerprint-that-wont-match");
  }

  @Test
  public void handleContextCaching_fingerprintOnlyMetadata_returnsFingerprintOnly() {
    ContextCacheConfig cacheConfig = ContextCacheConfig.builder().build();

    CacheMetadata fingerprintOnly =
        CacheMetadata.builder().fingerprint("existing-fingerprint").contentsCount(5).build();

    LlmRequest request =
        LlmRequest.builder()
            .model(TEST_MODEL)
            .cacheConfig(cacheConfig)
            .cacheMetadata(fingerprintOnly)
            .config(
                com.google.genai.types.GenerateContentConfig.builder()
                    .systemInstruction(
                        Content.builder()
                            .parts(
                                ImmutableList.of(
                                    Part.builder().text(TEST_SYSTEM_INSTRUCTION).build()))
                            .build())
                    .build())
            .build();

    CacheMetadata result = manager.handleContextCaching(request, 5).blockingGet();

    // Should still be fingerprint-only (no active cache to validate)
    assertThat(result.cacheName()).isEmpty();
    assertThat(result.fingerprint()).isNotEmpty();
  }

  @Test
  public void deleteCache_succeeds() {
    String cacheName = "cachedContents/test-delete";

    Completable result = manager.deleteCache(cacheName);

    result.blockingAwait(); // Should complete without error
  }

  @Test
  public void handleContextCaching_matchingFingerprintOnly_createsCache() {
    ContextCacheConfig cacheConfig = ContextCacheConfig.builder().build();
    String existingFingerprint = "matching-fingerprint-123";

    CacheMetadata fingerprintOnlyMetadata =
        CacheMetadata.builder().fingerprint(existingFingerprint).contentsCount(5).build();

    LlmRequest request =
        LlmRequest.builder()
            .model(TEST_MODEL)
            .cacheConfig(cacheConfig)
            .cacheMetadata(fingerprintOnlyMetadata)
            .config(
                com.google.genai.types.GenerateContentConfig.builder()
                    .systemInstruction(
                        Content.builder()
                            .parts(
                                ImmutableList.of(
                                    Part.builder().text(TEST_SYSTEM_INSTRUCTION).build()))
                            .build())
                    .build())
            .build();

    // Generate actual fingerprint and update request to match
    String actualFingerprint = manager.generateCacheFingerprint(request, 5);
    CacheMetadata updatedMetadata =
        fingerprintOnlyMetadata.toBuilder().fingerprint(actualFingerprint).build();
    LlmRequest updatedRequest = request.toBuilder().cacheMetadata(updatedMetadata).build();

    CacheMetadata result = manager.handleContextCaching(updatedRequest, 5).blockingGet();

    // Should create cache when fingerprints match
    assertThat(result.isActiveCache()).isTrue();
    assertThat(result.cacheName()).isPresent();
    assertThat(result.fingerprint()).isEqualTo(actualFingerprint);
  }

  @Test
  public void generateCacheFingerprint_longSystemInstruction_generatesConsistentHash() {
    String longInstruction = "a".repeat(10000); // 10K character instruction
    LlmRequest request =
        LlmRequest.builder()
            .model(TEST_MODEL)
            .config(
                com.google.genai.types.GenerateContentConfig.builder()
                    .systemInstruction(
                        Content.builder()
                            .parts(ImmutableList.of(Part.builder().text(longInstruction).build()))
                            .build())
                    .build())
            .build();

    String fingerprint1 = manager.generateCacheFingerprint(request, 5);
    String fingerprint2 = manager.generateCacheFingerprint(request, 5);

    assertThat(fingerprint1).isEqualTo(fingerprint2);
    assertThat(fingerprint1).hasLength(64);
  }

  @Test
  public void generateCacheFingerprint_specialCharacters_handlesCorrectly() {
    String instructionWithSpecialChars = "Instruction with special chars: \n\t\\\"{}[]<>@#$%^&*()";
    LlmRequest request =
        LlmRequest.builder()
            .model(TEST_MODEL)
            .config(
                com.google.genai.types.GenerateContentConfig.builder()
                    .systemInstruction(
                        Content.builder()
                            .parts(
                                ImmutableList.of(
                                    Part.builder().text(instructionWithSpecialChars).build()))
                            .build())
                    .build())
            .build();

    String fingerprint = manager.generateCacheFingerprint(request, 5);

    assertThat(fingerprint).isNotEmpty();
    assertThat(fingerprint).hasLength(64);
  }

  @Test
  public void handleContextCaching_invocationsAtLimit_returnsFingerprintOnly() {
    ContextCacheConfig cacheConfig = ContextCacheConfig.builder().cacheIntervals(10).build();
    long futureExpireTime = System.currentTimeMillis() / 1000 + 1800;

    LlmRequest request =
        LlmRequest.builder()
            .model(TEST_MODEL)
            .cacheConfig(cacheConfig)
            .config(
                com.google.genai.types.GenerateContentConfig.builder()
                    .systemInstruction(
                        Content.builder()
                            .parts(
                                ImmutableList.of(
                                    Part.builder().text(TEST_SYSTEM_INSTRUCTION).build()))
                            .build())
                    .build())
            .build();

    String fingerprint = manager.generateCacheFingerprint(request, 5);

    CacheMetadata metadataAtLimit =
        CacheMetadata.builder()
            .cacheName("cachedContents/at-limit")
            .expireTime(futureExpireTime)
            .fingerprint(fingerprint)
            .invocationsUsed(10) // Exactly at limit
            .contentsCount(5)
            .build();

    LlmRequest requestWithMetadata = request.toBuilder().cacheMetadata(metadataAtLimit).build();

    CacheMetadata result = manager.handleContextCaching(requestWithMetadata, 5).blockingGet();

    assertThat(result.cacheName()).isEmpty(); // Should be invalid when at limit
  }

  @Test
  public void handleContextCaching_invocationsBelowLimit_reusesCache() {
    ContextCacheConfig cacheConfig = ContextCacheConfig.builder().cacheIntervals(10).build();
    long futureExpireTime = System.currentTimeMillis() / 1000 + 1800;

    LlmRequest request =
        LlmRequest.builder()
            .model(TEST_MODEL)
            .cacheConfig(cacheConfig)
            .config(
                com.google.genai.types.GenerateContentConfig.builder()
                    .systemInstruction(
                        Content.builder()
                            .parts(
                                ImmutableList.of(
                                    Part.builder().text(TEST_SYSTEM_INSTRUCTION).build()))
                            .build())
                    .build())
            .build();

    String fingerprint = manager.generateCacheFingerprint(request, 5);

    CacheMetadata metadataBelowLimit =
        CacheMetadata.builder()
            .cacheName("cachedContents/below-limit")
            .expireTime(futureExpireTime)
            .fingerprint(fingerprint)
            .invocationsUsed(9) // Below limit of 10
            .contentsCount(5)
            .build();

    LlmRequest requestWithMetadata = request.toBuilder().cacheMetadata(metadataBelowLimit).build();

    CacheMetadata result = manager.handleContextCaching(requestWithMetadata, 5).blockingGet();

    assertThat(result.isActiveCache()).isTrue();
    assertThat(result.cacheName()).hasValue("cachedContents/below-limit");
  }

  @Test
  public void handleContextCaching_cacheExpiringSoon_stillValid() {
    ContextCacheConfig cacheConfig = ContextCacheConfig.builder().build();
    long soonExpireTime = System.currentTimeMillis() / 1000 + 180; // 3 min future (within buffer)

    LlmRequest request =
        LlmRequest.builder()
            .model(TEST_MODEL)
            .cacheConfig(cacheConfig)
            .config(
                com.google.genai.types.GenerateContentConfig.builder()
                    .systemInstruction(
                        Content.builder()
                            .parts(
                                ImmutableList.of(
                                    Part.builder().text(TEST_SYSTEM_INSTRUCTION).build()))
                            .build())
                    .build())
            .build();

    String fingerprint = manager.generateCacheFingerprint(request, 5);

    CacheMetadata metadataExpiringSoon =
        CacheMetadata.builder()
            .cacheName("cachedContents/expiring-soon")
            .expireTime(soonExpireTime)
            .fingerprint(fingerprint)
            .invocationsUsed(2)
            .contentsCount(5)
            .build();

    LlmRequest requestWithMetadata =
        request.toBuilder().cacheMetadata(metadataExpiringSoon).build();

    CacheMetadata result = manager.handleContextCaching(requestWithMetadata, 5).blockingGet();

    // Should still be valid (not yet expired)
    assertThat(result.isActiveCache()).isTrue();
    assertThat(result.cacheName()).hasValue("cachedContents/expiring-soon");
  }

  @Test
  public void handleContextCaching_cacheExpiresExactlyNow_returnsInvalid() {
    ContextCacheConfig cacheConfig = ContextCacheConfig.builder().build();
    long currentTime = System.currentTimeMillis() / 1000;

    LlmRequest request =
        LlmRequest.builder()
            .model(TEST_MODEL)
            .cacheConfig(cacheConfig)
            .config(
                com.google.genai.types.GenerateContentConfig.builder()
                    .systemInstruction(
                        Content.builder()
                            .parts(
                                ImmutableList.of(
                                    Part.builder().text(TEST_SYSTEM_INSTRUCTION).build()))
                            .build())
                    .build())
            .build();

    String fingerprint = manager.generateCacheFingerprint(request, 5);

    CacheMetadata metadataExpiringNow =
        CacheMetadata.builder()
            .cacheName("cachedContents/expiring-now")
            .expireTime(currentTime) // Expires exactly now
            .fingerprint(fingerprint)
            .invocationsUsed(2)
            .contentsCount(5)
            .build();

    LlmRequest requestWithMetadata = request.toBuilder().cacheMetadata(metadataExpiringNow).build();

    CacheMetadata result = manager.handleContextCaching(requestWithMetadata, 5).blockingGet();

    assertThat(result.cacheName()).isEmpty();
  }

  @Test
  public void generateCacheFingerprint_emptySystemInstruction_generatesFingerprint() {
    LlmRequest request =
        LlmRequest.builder()
            .model(TEST_MODEL)
            .config(
                com.google.genai.types.GenerateContentConfig.builder()
                    .systemInstruction(
                        Content.builder()
                            .parts(ImmutableList.of(Part.builder().text("").build()))
                            .build())
                    .build())
            .build();

    String fingerprint = manager.generateCacheFingerprint(request, 5);

    assertThat(fingerprint).isNotEmpty();
    assertThat(fingerprint).hasLength(64);
  }

  @Test
  public void generateCacheFingerprint_noModel_generatesFingerprint() {
    LlmRequest request =
        LlmRequest.builder()
            .config(
                com.google.genai.types.GenerateContentConfig.builder()
                    .systemInstruction(
                        Content.builder()
                            .parts(
                                ImmutableList.of(
                                    Part.builder().text(TEST_SYSTEM_INSTRUCTION).build()))
                            .build())
                    .build())
            .build();

    String fingerprint = manager.generateCacheFingerprint(request, 5);

    assertThat(fingerprint).isNotEmpty();
  }

  @Test
  public void generateCacheFingerprint_sameInputTwice_producesSameHash() {
    LlmRequest request =
        LlmRequest.builder()
            .model(TEST_MODEL)
            .config(
                com.google.genai.types.GenerateContentConfig.builder()
                    .systemInstruction(
                        Content.builder()
                            .parts(
                                ImmutableList.of(
                                    Part.builder().text(TEST_SYSTEM_INSTRUCTION).build()))
                            .build())
                    .build())
            .build();

    String hash1 = manager.generateCacheFingerprint(request, 5);
    String hash2 = manager.generateCacheFingerprint(request, 5);
    String hash3 = manager.generateCacheFingerprint(request, 5);

    assertThat(hash1).isEqualTo(hash2);
    assertThat(hash2).isEqualTo(hash3);
  }

  @Test
  public void handleContextCaching_zeroInvocationsUsed_reusesCache() {
    ContextCacheConfig cacheConfig = ContextCacheConfig.builder().cacheIntervals(10).build();
    long futureExpireTime = System.currentTimeMillis() / 1000 + 1800;

    LlmRequest request =
        LlmRequest.builder()
            .model(TEST_MODEL)
            .cacheConfig(cacheConfig)
            .config(
                com.google.genai.types.GenerateContentConfig.builder()
                    .systemInstruction(
                        Content.builder()
                            .parts(
                                ImmutableList.of(
                                    Part.builder().text(TEST_SYSTEM_INSTRUCTION).build()))
                            .build())
                    .build())
            .build();

    String fingerprint = manager.generateCacheFingerprint(request, 5);

    CacheMetadata freshCache =
        CacheMetadata.builder()
            .cacheName("cachedContents/fresh")
            .expireTime(futureExpireTime)
            .fingerprint(fingerprint)
            .invocationsUsed(0) // Never used yet
            .contentsCount(5)
            .build();

    LlmRequest requestWithMetadata = request.toBuilder().cacheMetadata(freshCache).build();

    CacheMetadata result = manager.handleContextCaching(requestWithMetadata, 5).blockingGet();

    assertThat(result.isActiveCache()).isTrue();
    assertThat(result.cacheName()).hasValue("cachedContents/fresh");
  }

  @Test
  public void handleContextCaching_differentCacheIntervals_affectsValidation() {
    long futureExpireTime = System.currentTimeMillis() / 1000 + 1800;

    LlmRequest request =
        LlmRequest.builder()
            .model(TEST_MODEL)
            .config(
                com.google.genai.types.GenerateContentConfig.builder()
                    .systemInstruction(
                        Content.builder()
                            .parts(
                                ImmutableList.of(
                                    Part.builder().text(TEST_SYSTEM_INSTRUCTION).build()))
                            .build())
                    .build())
            .build();

    String fingerprint = manager.generateCacheFingerprint(request, 5);

    CacheMetadata metadata =
        CacheMetadata.builder()
            .cacheName("cachedContents/test")
            .expireTime(futureExpireTime)
            .fingerprint(fingerprint)
            .invocationsUsed(15)
            .contentsCount(5)
            .build();

    // With cacheIntervals=20, invocations=15 should be valid
    ContextCacheConfig config1 = ContextCacheConfig.builder().cacheIntervals(20).build();
    LlmRequest request1 = request.toBuilder().cacheConfig(config1).cacheMetadata(metadata).build();
    CacheMetadata result1 = manager.handleContextCaching(request1, 5).blockingGet();
    assertThat(result1.isActiveCache()).isTrue();

    // With cacheIntervals=10, invocations=15 should be invalid
    ContextCacheConfig config2 = ContextCacheConfig.builder().cacheIntervals(10).build();
    LlmRequest request2 = request.toBuilder().cacheConfig(config2).cacheMetadata(metadata).build();
    CacheMetadata result2 = manager.handleContextCaching(request2, 5).blockingGet();
    assertThat(result2.cacheName()).isEmpty();
  }

  @Test
  public void generateCacheFingerprint_whitespaceInInstruction_affectsHash() {
    LlmRequest request1 =
        LlmRequest.builder()
            .model(TEST_MODEL)
            .config(
                com.google.genai.types.GenerateContentConfig.builder()
                    .systemInstruction(
                        Content.builder()
                            .parts(ImmutableList.of(Part.builder().text("Instruction").build()))
                            .build())
                    .build())
            .build();

    LlmRequest request2 =
        LlmRequest.builder()
            .model(TEST_MODEL)
            .config(
                com.google.genai.types.GenerateContentConfig.builder()
                    .systemInstruction(
                        Content.builder()
                            .parts(
                                ImmutableList.of(
                                    Part.builder().text("Instruction ").build())) // Trailing space
                            .build())
                    .build())
            .build();

    String fingerprint1 = manager.generateCacheFingerprint(request1, 5);
    String fingerprint2 = manager.generateCacheFingerprint(request2, 5);

    assertThat(fingerprint1).isNotEqualTo(fingerprint2);
  }

  @Test
  public void handleContextCaching_nullInvocationsUsed_treatedAsZero() {
    ContextCacheConfig cacheConfig = ContextCacheConfig.builder().cacheIntervals(10).build();
    long futureExpireTime = System.currentTimeMillis() / 1000 + 1800;

    LlmRequest request =
        LlmRequest.builder()
            .model(TEST_MODEL)
            .cacheConfig(cacheConfig)
            .config(
                com.google.genai.types.GenerateContentConfig.builder()
                    .systemInstruction(
                        Content.builder()
                            .parts(
                                ImmutableList.of(
                                    Part.builder().text(TEST_SYSTEM_INSTRUCTION).build()))
                            .build())
                    .build())
            .build();

    String fingerprint = manager.generateCacheFingerprint(request, 5);

    CacheMetadata metadataWithoutInvocations =
        CacheMetadata.builder()
            .cacheName("cachedContents/no-invocations")
            .expireTime(futureExpireTime)
            .fingerprint(fingerprint)
            // invocationsUsed not set (null/Optional.empty())
            .contentsCount(5)
            .build();

    LlmRequest requestWithMetadata =
        request.toBuilder().cacheMetadata(metadataWithoutInvocations).build();

    CacheMetadata result = manager.handleContextCaching(requestWithMetadata, 5).blockingGet();

    assertThat(result.isActiveCache()).isTrue();
  }

  @Test
  public void generateCacheFingerprint_unicodeCharacters_handlesCorrectly() {
    String unicodeInstruction = "Instruction with unicode: 你好世界 🌍 café";
    LlmRequest request =
        LlmRequest.builder()
            .model(TEST_MODEL)
            .config(
                com.google.genai.types.GenerateContentConfig.builder()
                    .systemInstruction(
                        Content.builder()
                            .parts(
                                ImmutableList.of(Part.builder().text(unicodeInstruction).build()))
                            .build())
                    .build())
            .build();

    String fingerprint = manager.generateCacheFingerprint(request, 5);

    assertThat(fingerprint).isNotEmpty();
    assertThat(fingerprint).hasLength(64);
  }

  @Test
  public void generateCacheFingerprint_veryLongContentCount_generatesHash() {
    LlmRequest request =
        LlmRequest.builder()
            .model(TEST_MODEL)
            .config(
                com.google.genai.types.GenerateContentConfig.builder()
                    .systemInstruction(
                        Content.builder()
                            .parts(
                                ImmutableList.of(
                                    Part.builder().text(TEST_SYSTEM_INSTRUCTION).build()))
                            .build())
                    .build())
            .build();

    String fingerprint = manager.generateCacheFingerprint(request, 10000);

    assertThat(fingerprint).isNotEmpty();
  }

  @Test
  public void generateCacheFingerprint_zeroContentCount_generatesHash() {
    LlmRequest request =
        LlmRequest.builder()
            .model(TEST_MODEL)
            .config(
                com.google.genai.types.GenerateContentConfig.builder()
                    .systemInstruction(
                        Content.builder()
                            .parts(
                                ImmutableList.of(
                                    Part.builder().text(TEST_SYSTEM_INSTRUCTION).build()))
                            .build())
                    .build())
            .build();

    String fingerprint = manager.generateCacheFingerprint(request, 0);

    assertThat(fingerprint).isNotEmpty();
  }

  @Test
  public void createCache_withValidRequest_createsMetadata() {
    ContextCacheConfig cacheConfig = ContextCacheConfig.builder().ttlSeconds(3600).build();
    LlmRequest request =
        LlmRequest.builder()
            .model(TEST_MODEL)
            .cacheConfig(cacheConfig)
            .config(
                com.google.genai.types.GenerateContentConfig.builder()
                    .systemInstruction(
                        Content.builder()
                            .parts(
                                ImmutableList.of(
                                    Part.builder().text(TEST_SYSTEM_INSTRUCTION).build()))
                            .build())
                    .build())
            .build();

    String fingerprint = manager.generateCacheFingerprint(request, 5);

    // Update request to have fingerprint-only metadata matching current fingerprint
    CacheMetadata fingerprintMetadata =
        CacheMetadata.builder().fingerprint(fingerprint).contentsCount(5).build();
    LlmRequest requestWithMetadata = request.toBuilder().cacheMetadata(fingerprintMetadata).build();

    CacheMetadata result = manager.handleContextCaching(requestWithMetadata, 5).blockingGet();

    assertThat(result.isActiveCache()).isTrue();
    assertThat(result.cacheName()).isPresent();
    assertThat(result.cacheName().get()).startsWith("cachedContents/");
    assertThat(result.fingerprint()).isEqualTo(fingerprint);
    assertThat(result.contentsCount()).isEqualTo(5);
    assertThat(result.invocationsUsed()).hasValue(0);
    assertThat(result.expireTime()).isPresent();
    assertThat(result.createdAt()).isPresent();
  }

  @Test
  public void deleteCache_withNullCacheName_completesWithoutError() {
    Completable result = manager.deleteCache(null);

    result.blockingAwait(); // Should complete
  }

  @Test
  public void deleteCache_withEmptyCacheName_completesWithoutError() {
    Completable result = manager.deleteCache("");

    result.blockingAwait(); // Should complete
  }
}
