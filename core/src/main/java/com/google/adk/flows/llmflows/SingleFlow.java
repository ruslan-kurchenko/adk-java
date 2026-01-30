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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Basic LLM flow with fixed request and response processors. */
public class SingleFlow extends BaseLlmFlow {
  // TODO: We should eventually remove this class since it complicates things.

  private static final Logger logger = LoggerFactory.getLogger(SingleFlow.class);

  private static volatile GeminiContextCacheManager cacheManagerInstance;
  private static final Object CACHE_MANAGER_LOCK = new Object();

  protected static final ImmutableList<RequestProcessor> REQUEST_PROCESSORS =
      createRequestProcessors();

  protected static final ImmutableList<ResponseProcessor> RESPONSE_PROCESSORS =
      ImmutableList.of(CodeExecution.responseProcessor);

  private static ImmutableList<RequestProcessor> createRequestProcessors() {
    GeminiContextCacheManager cacheManager = getCacheManager();

    return ImmutableList.of(
        new Basic(),
        new Instructions(),
        new Identity(),
        new Contents(),
        cacheManager != null ? new ContextCacheProcessor(cacheManager) : new NoOpRequestProcessor(),
        new Examples(),
        new RequestConfirmationLlmRequestProcessor(),
        CodeExecution.requestProcessor);
  }

  /**
   * Gets or creates the singleton cache manager instance.
   *
   * <p>Uses double-checked locking for thread-safe lazy initialization. Reads GOOGLE_CLOUD_PROJECT
   * environment variable for Vertex AI authentication.
   *
   * @return Singleton cache manager, or null if initialization fails
   */
  private static GeminiContextCacheManager getCacheManager() {
    if (cacheManagerInstance == null) {
      synchronized (CACHE_MANAGER_LOCK) {
        if (cacheManagerInstance == null) {
          try {
            String projectId = System.getenv("GOOGLE_CLOUD_PROJECT");
            Client client = Client.builder().build();
            cacheManagerInstance = new GeminiContextCacheManager(client, projectId, null);

            logger.info(
                "Initialized context cache manager (project: {})",
                projectId != null ? projectId : "API key mode");

          } catch (Exception e) {
            logger.error("Failed to initialize context cache manager, caching will be disabled", e);
            return null;
          }
        }
      }
    }
    return cacheManagerInstance;
  }

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
