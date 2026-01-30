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

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link CacheMetadata}. */
@RunWith(JUnit4.class)
public class CacheMetadataTest {

  private static final String TEST_CACHE_NAME = "cachedContents/abc123-def456";
  private static final String TEST_FINGERPRINT = "a1b2c3d4e5f6g7h8";
  private static final long TEST_EXPIRE_TIME = System.currentTimeMillis() / 1000 + 1800; // 30 min
  private static final long TEST_CREATED_AT = System.currentTimeMillis() / 1000;

  @Test
  public void builder_createsFingerprintOnlyMetadata() {
    CacheMetadata metadata =
        CacheMetadata.builder().fingerprint(TEST_FINGERPRINT).contentsCount(5).build();

    assertThat(metadata.cacheName()).isEmpty();
    assertThat(metadata.expireTime()).isEmpty();
    assertThat(metadata.fingerprint()).isEqualTo(TEST_FINGERPRINT);
    assertThat(metadata.contentsCount()).isEqualTo(5);
    assertThat(metadata.createdAt()).isEmpty();
  }

  @Test
  public void builder_createsActiveCacheMetadata() {
    CacheMetadata metadata =
        CacheMetadata.builder()
            .cacheName(TEST_CACHE_NAME)
            .expireTime(TEST_EXPIRE_TIME)
            .fingerprint(TEST_FINGERPRINT)
            .contentsCount(10)
            .createdAt(TEST_CREATED_AT)
            .build();

    assertThat(metadata.cacheName()).hasValue(TEST_CACHE_NAME);
    assertThat(metadata.expireTime()).hasValue(TEST_EXPIRE_TIME);
    assertThat(metadata.fingerprint()).isEqualTo(TEST_FINGERPRINT);
    assertThat(metadata.contentsCount()).isEqualTo(10);
    assertThat(metadata.createdAt()).hasValue(TEST_CREATED_AT);
  }

  @Test
  public void isActiveCache_returnsTrue_whenCacheNamePresent() {
    CacheMetadata metadata =
        CacheMetadata.builder()
            .cacheName(TEST_CACHE_NAME)
            .expireTime(TEST_EXPIRE_TIME)
            .fingerprint(TEST_FINGERPRINT)
            .contentsCount(5)
            .build();

    assertThat(metadata.isActiveCache()).isTrue();
  }

  @Test
  public void isActiveCache_returnsFalse_whenCacheNameAbsent() {
    CacheMetadata metadata =
        CacheMetadata.builder().fingerprint(TEST_FINGERPRINT).contentsCount(5).build();

    assertThat(metadata.isActiveCache()).isFalse();
  }

  @Test
  public void expireSoon_returnsFalse_whenNoExpireTime() {
    CacheMetadata metadata =
        CacheMetadata.builder().fingerprint(TEST_FINGERPRINT).contentsCount(5).build();

    assertThat(metadata.expireSoon()).isFalse();
  }

  @Test
  public void expireSoon_returnsFalse_whenExpiresInFuture() {
    long futureExpireTime = System.currentTimeMillis() / 1000 + 300; // 5 minutes from now

    CacheMetadata metadata =
        CacheMetadata.builder()
            .cacheName(TEST_CACHE_NAME)
            .expireTime(futureExpireTime)
            .fingerprint(TEST_FINGERPRINT)
            .contentsCount(5)
            .build();

    assertThat(metadata.expireSoon()).isFalse();
  }

  @Test
  public void expireSoon_returnsTrue_whenExpiresWithinBuffer() {
    long soonExpireTime = System.currentTimeMillis() / 1000 + 60; // 1 minute from now

    CacheMetadata metadata =
        CacheMetadata.builder()
            .cacheName(TEST_CACHE_NAME)
            .expireTime(soonExpireTime)
            .fingerprint(TEST_FINGERPRINT)
            .contentsCount(5)
            .build();

    assertThat(metadata.expireSoon()).isTrue();
  }

  @Test
  public void expireSoon_returnsTrue_whenExpired() {
    long pastExpireTime = System.currentTimeMillis() / 1000 - 60; // 1 minute ago

    CacheMetadata metadata =
        CacheMetadata.builder()
            .cacheName(TEST_CACHE_NAME)
            .expireTime(pastExpireTime)
            .fingerprint(TEST_FINGERPRINT)
            .contentsCount(5)
            .build();

    assertThat(metadata.expireSoon()).isTrue();
  }

  @Test
  public void toString_fingerPrintOnly_showsCorrectFormat() {
    CacheMetadata metadata =
        CacheMetadata.builder().fingerprint(TEST_FINGERPRINT).contentsCount(8).build();

    String result = metadata.toString();

    assertThat(result).contains("Fingerprint-only");
    assertThat(result).contains("8 contents");
    assertThat(result).contains("fingerprint=a1b2c3d4");
  }

  @Test
  public void toString_activeCache_showsCorrectFormat() {
    CacheMetadata metadata =
        CacheMetadata.builder()
            .cacheName("cachedContents/xyz789")
            .expireTime(TEST_EXPIRE_TIME)
            .fingerprint(TEST_FINGERPRINT)
            .contentsCount(12)
            .createdAt(TEST_CREATED_AT)
            .build();

    String result = metadata.toString();

    assertThat(result).contains("Cache xyz789");
    assertThat(result).contains("12 contents");
    assertThat(result).contains("expires in");
  }

  @Test
  public void toJson_fingerprintOnly_serializesCorrectly() {
    CacheMetadata metadata =
        CacheMetadata.builder().fingerprint(TEST_FINGERPRINT).contentsCount(5).build();

    String json = metadata.toJson();

    assertThat(json).contains("\"fingerprint\":\"" + TEST_FINGERPRINT + "\"");
    assertThat(json).contains("\"contents_count\":5");
    assertThat(json).doesNotContain("\"cache_name\":");
  }

  @Test
  public void toJson_activeCache_serializesAllFields() {
    CacheMetadata metadata =
        CacheMetadata.builder()
            .cacheName(TEST_CACHE_NAME)
            .expireTime(TEST_EXPIRE_TIME)
            .fingerprint(TEST_FINGERPRINT)
            .contentsCount(10)
            .createdAt(TEST_CREATED_AT)
            .build();

    String json = metadata.toJson();

    assertThat(json).contains("\"cache_name\":\"" + TEST_CACHE_NAME + "\"");
    assertThat(json).contains("\"expire_time\":" + TEST_EXPIRE_TIME);
    assertThat(json).contains("\"fingerprint\":\"" + TEST_FINGERPRINT + "\"");
    assertThat(json).contains("\"contents_count\":10");
    assertThat(json).contains("\"created_at\":" + TEST_CREATED_AT);
  }

  @Test
  public void fromJson_fingerprintOnly_deserializesCorrectly() {
    String json =
        "{" + "\"fingerprint\":\"" + TEST_FINGERPRINT + "\"," + "\"contents_count\":7" + "}";

    CacheMetadata metadata = CacheMetadata.fromJson(json);

    assertThat(metadata.fingerprint()).isEqualTo(TEST_FINGERPRINT);
    assertThat(metadata.contentsCount()).isEqualTo(7);
    assertThat(metadata.cacheName()).isEmpty();
    assertThat(metadata.expireTime()).isEmpty();
    assertThat(metadata.createdAt()).isEmpty();
  }

  @Test
  public void fromJson_activeCache_deserializesAllFields() {
    String json =
        "{"
            + "\"cache_name\":\""
            + TEST_CACHE_NAME
            + "\","
            + "\"expire_time\":"
            + TEST_EXPIRE_TIME
            + ","
            + "\"fingerprint\":\""
            + TEST_FINGERPRINT
            + "\","
            + "\"invocations_used\":4,"
            + "\"contents_count\":15,"
            + "\"created_at\":"
            + TEST_CREATED_AT
            + "}";

    CacheMetadata metadata = CacheMetadata.fromJson(json);

    assertThat(metadata.cacheName()).hasValue(TEST_CACHE_NAME);
    assertThat(metadata.expireTime()).hasValue(TEST_EXPIRE_TIME);
    assertThat(metadata.fingerprint()).isEqualTo(TEST_FINGERPRINT);
    assertThat(metadata.contentsCount()).isEqualTo(15);
    assertThat(metadata.createdAt()).hasValue(TEST_CREATED_AT);
  }

  @Test
  public void equals_returnsTrue_forIdenticalMetadata() {
    CacheMetadata metadata1 =
        CacheMetadata.builder()
            .cacheName(TEST_CACHE_NAME)
            .expireTime(TEST_EXPIRE_TIME)
            .fingerprint(TEST_FINGERPRINT)
            .contentsCount(5)
            .createdAt(TEST_CREATED_AT)
            .build();

    CacheMetadata metadata2 =
        CacheMetadata.builder()
            .cacheName(TEST_CACHE_NAME)
            .expireTime(TEST_EXPIRE_TIME)
            .fingerprint(TEST_FINGERPRINT)
            .contentsCount(5)
            .createdAt(TEST_CREATED_AT)
            .build();

    assertThat(metadata1).isEqualTo(metadata2);
  }

  @Test
  public void equals_returnsFalse_forDifferentFingerprint() {
    CacheMetadata metadata1 =
        CacheMetadata.builder().fingerprint("fingerprint1").contentsCount(5).build();

    CacheMetadata metadata2 =
        CacheMetadata.builder().fingerprint("fingerprint2").contentsCount(5).build();

    assertThat(metadata1).isNotEqualTo(metadata2);
  }

  @Test
  public void equals_returnsFalse_forDifferentContentsCount() {
    CacheMetadata metadata1 =
        CacheMetadata.builder().fingerprint(TEST_FINGERPRINT).contentsCount(5).build();

    CacheMetadata metadata2 =
        CacheMetadata.builder().fingerprint(TEST_FINGERPRINT).contentsCount(10).build();

    assertThat(metadata1).isNotEqualTo(metadata2);
  }

  @Test
  public void hashCode_returnsSameValue_forIdenticalMetadata() {
    CacheMetadata metadata1 =
        CacheMetadata.builder()
            .cacheName(TEST_CACHE_NAME)
            .fingerprint(TEST_FINGERPRINT)
            .contentsCount(5)
            .build();

    CacheMetadata metadata2 =
        CacheMetadata.builder()
            .cacheName(TEST_CACHE_NAME)
            .fingerprint(TEST_FINGERPRINT)
            .contentsCount(5)
            .build();

    assertThat(metadata1.hashCode()).isEqualTo(metadata2.hashCode());
  }

  @Test
  public void builder_supportsMethodChaining() {
    CacheMetadata metadata =
        CacheMetadata.builder()
            .cacheName(TEST_CACHE_NAME)
            .expireTime(TEST_EXPIRE_TIME)
            .fingerprint(TEST_FINGERPRINT)
            .contentsCount(20)
            .createdAt(TEST_CREATED_AT)
            .build();

    assertThat(metadata.cacheName()).hasValue(TEST_CACHE_NAME);
    assertThat(metadata.expireTime()).hasValue(TEST_EXPIRE_TIME);
    assertThat(metadata.fingerprint()).isEqualTo(TEST_FINGERPRINT);
    assertThat(metadata.contentsCount()).isEqualTo(20);
    assertThat(metadata.createdAt()).hasValue(TEST_CREATED_AT);
  }

  @Test
  public void builder_handlesShortFingerprint() {
    String shortFingerprint = "abc";
    CacheMetadata metadata =
        CacheMetadata.builder().fingerprint(shortFingerprint).contentsCount(3).build();

    String result = metadata.toString();

    assertThat(result).contains("fingerprint=abc");
  }

  @Test
  public void builder_handlesZeroInvocationsUsed() {
    CacheMetadata metadata =
        CacheMetadata.builder()
            .cacheName(TEST_CACHE_NAME)
            .expireTime(TEST_EXPIRE_TIME)
            .fingerprint(TEST_FINGERPRINT)
            .contentsCount(5)
            .build();
  }

  @Test
  public void builder_handlesZeroContentsCount() {
    CacheMetadata metadata =
        CacheMetadata.builder().fingerprint(TEST_FINGERPRINT).contentsCount(0).build();

    assertThat(metadata.contentsCount()).isEqualTo(0);
  }

  @Test
  public void estimatedStorageCost_returnsZeroForFingerprintOnly() {
    CacheMetadata metadata =
        CacheMetadata.builder().fingerprint(TEST_FINGERPRINT).contentsCount(1000).build();

    assertThat(metadata.estimatedStorageCost()).isEqualTo(0.0);
  }

  @Test
  public void estimatedStorageCost_calculatesCorrectlyForActiveCache() {
    long currentTime = System.currentTimeMillis() / 1000;
    long expireTime = currentTime + 3600; // 1 hour remaining

    CacheMetadata metadata =
        CacheMetadata.builder()
            .cacheName(TEST_CACHE_NAME)
            .expireTime(expireTime)
            .fingerprint(TEST_FINGERPRINT)
            .contentsCount(1_000_000) // 1 million tokens
            .createdAt(currentTime - 1800)
            .build();

    // Expected: 1M tokens × $1/1M/hour × 1 hour = $1.00
    assertThat(metadata.estimatedStorageCost()).isWithin(0.01).of(1.0);
  }

  @Test
  public void estimatedStorageCost_handlesSmallCache() {
    long currentTime = System.currentTimeMillis() / 1000;
    long expireTime = currentTime + 7200; // 2 hours remaining

    CacheMetadata metadata =
        CacheMetadata.builder()
            .cacheName(TEST_CACHE_NAME)
            .expireTime(expireTime)
            .fingerprint(TEST_FINGERPRINT)
            .contentsCount(10_000) // 10K tokens
            .createdAt(currentTime)
            .build();

    // Expected: 10K tokens × $1/1M/hour × 2 hours = $0.02
    assertThat(metadata.estimatedStorageCost()).isWithin(0.001).of(0.02);
  }

  @Test
  public void estimatedStorageCost_returnsZeroForExpiredCache() {
    long currentTime = System.currentTimeMillis() / 1000;
    long expireTime = currentTime - 3600; // Expired 1 hour ago

    CacheMetadata metadata =
        CacheMetadata.builder()
            .cacheName(TEST_CACHE_NAME)
            .expireTime(expireTime)
            .fingerprint(TEST_FINGERPRINT)
            .contentsCount(100_000)
            .createdAt(currentTime - 7200)
            .build();

    assertThat(metadata.estimatedStorageCost()).isEqualTo(0.0);
  }
}
