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

package com.google.adk.apps;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link ContextCacheConfig}. */
@RunWith(JUnit4.class)
public class ContextCacheConfigTest {

  @Test
  public void builder_createsConfigWithDefaults() {
    ContextCacheConfig config = ContextCacheConfig.builder().build();

    assertThat(config.ttlSeconds()).isEqualTo(1800);
    assertThat(config.minTokens()).isEqualTo(0);
  }

  @Test
  public void builder_createsConfigWithCustomValues() {
    ContextCacheConfig config =
        ContextCacheConfig.builder().ttlSeconds(3600).minTokens(1000).build();

    assertThat(config.ttlSeconds()).isEqualTo(3600);
    assertThat(config.minTokens()).isEqualTo(1000);
  }

  @Test
  public void builder_acceptsMinimumValidValues() {
    ContextCacheConfig config = ContextCacheConfig.builder().ttlSeconds(1).minTokens(0).build();

    assertThat(config.ttlSeconds()).isEqualTo(1);
    assertThat(config.minTokens()).isEqualTo(0);
  }

  @Test
  public void builder_acceptsMaximumValidValues() {
    ContextCacheConfig config =
        ContextCacheConfig.builder()
            .ttlSeconds(Integer.MAX_VALUE)
            .minTokens(Integer.MAX_VALUE)
            .build();

    assertThat(config.ttlSeconds()).isEqualTo(Integer.MAX_VALUE);
    assertThat(config.minTokens()).isEqualTo(Integer.MAX_VALUE);
  }

  @Test
  public void build_throwsException_whenTtlSecondsZero() {
    ContextCacheConfig.Builder builder = ContextCacheConfig.builder().ttlSeconds(0);

    IllegalArgumentException exception =
        assertThrows(IllegalArgumentException.class, builder::build);

    assertThat(exception).hasMessageThat().contains("ttlSeconds must be greater than 0");
    assertThat(exception).hasMessageThat().contains("got: 0");
  }

  @Test
  public void build_throwsException_whenTtlSecondsNegative() {
    ContextCacheConfig.Builder builder = ContextCacheConfig.builder().ttlSeconds(-100);

    IllegalArgumentException exception =
        assertThrows(IllegalArgumentException.class, builder::build);

    assertThat(exception).hasMessageThat().contains("ttlSeconds must be greater than 0");
    assertThat(exception).hasMessageThat().contains("got: -100");
  }

  @Test
  public void build_throwsException_whenMinTokensNegative() {
    ContextCacheConfig.Builder builder = ContextCacheConfig.builder().minTokens(-1);

    IllegalArgumentException exception =
        assertThrows(IllegalArgumentException.class, builder::build);

    assertThat(exception).hasMessageThat().contains("minTokens must be >= 0");
    assertThat(exception).hasMessageThat().contains("got: -1");
  }

  @Test
  public void ttlString_returnsCorrectFormat() {
    ContextCacheConfig config = ContextCacheConfig.builder().ttlSeconds(1800).build();

    assertThat(config.ttlString()).isEqualTo("1800s");
  }

  @Test
  public void ttlString_handlesLargeValues() {
    ContextCacheConfig config = ContextCacheConfig.builder().ttlSeconds(86400).build();

    assertThat(config.ttlString()).isEqualTo("86400s");
  }

  @Test
  public void ttlString_handlesSmallValues() {
    ContextCacheConfig config = ContextCacheConfig.builder().ttlSeconds(1).build();

    assertThat(config.ttlString()).isEqualTo("1s");
  }

  @Test
  public void toString_returnsFormattedString() {
    ContextCacheConfig config =
        ContextCacheConfig.builder().ttlSeconds(3600).minTokens(5000).build();

    String result = config.toString();

    assertThat(result).contains("ttl=3600s");
    assertThat(result).contains("minTokens=5000");
  }

  @Test
  public void toString_includesAllFields() {
    ContextCacheConfig config = ContextCacheConfig.builder().build();

    String result = config.toString();

    assertThat(result).contains("ContextCacheConfig");
    assertThat(result).contains("ttl=");
    assertThat(result).contains("minTokens=");
  }

  @Test
  public void equals_returnsTrueForIdenticalConfigs() {
    ContextCacheConfig config1 =
        ContextCacheConfig.builder().ttlSeconds(3600).minTokens(1000).build();

    ContextCacheConfig config2 =
        ContextCacheConfig.builder().ttlSeconds(3600).minTokens(1000).build();

    assertThat(config1).isEqualTo(config2);
  }

  @Test
  public void equals_returnsFalseForDifferentTtlSeconds() {
    ContextCacheConfig config1 = ContextCacheConfig.builder().ttlSeconds(1800).build();

    ContextCacheConfig config2 = ContextCacheConfig.builder().ttlSeconds(3600).build();

    assertThat(config1).isNotEqualTo(config2);
  }

  @Test
  public void equals_returnsFalseForDifferentMinTokens() {
    ContextCacheConfig config1 = ContextCacheConfig.builder().minTokens(0).build();

    ContextCacheConfig config2 = ContextCacheConfig.builder().minTokens(1000).build();

    assertThat(config1).isNotEqualTo(config2);
  }

  @Test
  public void hashCode_returnsSameValueForIdenticalConfigs() {
    ContextCacheConfig config1 =
        ContextCacheConfig.builder().ttlSeconds(3600).minTokens(1000).build();

    ContextCacheConfig config2 =
        ContextCacheConfig.builder().ttlSeconds(3600).minTokens(1000).build();

    assertThat(config1.hashCode()).isEqualTo(config2.hashCode());
  }

  @Test
  public void hashCode_returnsDifferentValueForDifferentConfigs() {
    ContextCacheConfig config1 = ContextCacheConfig.builder().ttlSeconds(1800).build();

    ContextCacheConfig config2 = ContextCacheConfig.builder().ttlSeconds(3600).build();

    assertThat(config1.hashCode()).isNotEqualTo(config2.hashCode());
  }

  @Test
  public void builder_supportsMethodChaining() {
    ContextCacheConfig config =
        ContextCacheConfig.builder().ttlSeconds(7200).minTokens(2000).build();

    assertThat(config.ttlSeconds()).isEqualTo(7200);
    assertThat(config.minTokens()).isEqualTo(2000);
  }
}
