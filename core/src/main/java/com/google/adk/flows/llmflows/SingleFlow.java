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

import com.google.adk.models.cache.GeminiContextCacheManager;
import com.google.common.collect.ImmutableList;
import com.google.genai.Client;
import java.util.List;
import java.util.Optional;

/** Basic LLM flow with fixed request and response processors. */
public class SingleFlow extends BaseLlmFlow {
  // TODO: We should eventually remove this class since it complicates things.

  // Default cache manager (will be null if caching not used)
  private static final GeminiContextCacheManager DEFAULT_CACHE_MANAGER =
      createDefaultCacheManager();

  private static GeminiContextCacheManager createDefaultCacheManager() {
    try {
      // Create default client for caching API
      Client client = Client.builder().build();
      return new GeminiContextCacheManager(client, null);
    } catch (Exception e) {
      // If client creation fails, caching will be disabled
      return null;
    }
  }

  protected static final ImmutableList<RequestProcessor> REQUEST_PROCESSORS =
      ImmutableList.of(
          new Basic(), // Process staticInstruction first
          DEFAULT_CACHE_MANAGER != null
              ? new ContextCacheProcessor(DEFAULT_CACHE_MANAGER)
              : new NoOpRequestProcessor(),
          new Instructions(),
          new Identity(),
          new Contents(),
          new Examples(),
          CodeExecution.requestProcessor);

  protected static final ImmutableList<ResponseProcessor> RESPONSE_PROCESSORS =
      ImmutableList.of(CodeExecution.responseProcessor);

  public SingleFlow() {
    this(/* maxSteps= */ Optional.empty());
  }

  public SingleFlow(Optional<Integer> maxSteps) {
    this(REQUEST_PROCESSORS, RESPONSE_PROCESSORS, maxSteps);
  }

  protected SingleFlow(
      List<RequestProcessor> requestProcessors,
      List<ResponseProcessor> responseProcessors,
      Optional<Integer> maxSteps) {
    super(requestProcessors, responseProcessors, maxSteps);
  }
}
