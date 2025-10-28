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

import com.google.adk.agents.InvocationContext;
import com.google.adk.models.LlmRequest;
import com.google.common.collect.ImmutableList;
import io.reactivex.rxjava3.core.Single;

/** No-op request processor that passes through requests unchanged. */
final class NoOpRequestProcessor implements RequestProcessor {

  @Override
  public Single<RequestProcessingResult> processRequest(
      InvocationContext context, LlmRequest request) {
    return Single.just(RequestProcessingResult.create(request, ImmutableList.of()));
  }
}
