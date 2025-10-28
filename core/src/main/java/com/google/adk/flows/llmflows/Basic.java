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
import com.google.adk.agents.LlmAgent;
import com.google.adk.agents.ReadonlyContext;
import com.google.adk.models.LlmRequest;
import com.google.common.collect.ImmutableList;
import com.google.genai.types.Content;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.LiveConnectConfig;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Single;
import java.util.List;
import java.util.Optional;

/** {@link RequestProcessor} that handles basic information to build the LLM request. */
public final class Basic implements RequestProcessor {

  public Basic() {}

  @Override
  public Single<RequestProcessor.RequestProcessingResult> processRequest(
      InvocationContext context, LlmRequest request) {
    if (!(context.agent() instanceof LlmAgent agent)) {
      throw new IllegalArgumentException("Agent in InvocationContext is not an instance of Agent.");
    }

    String modelName = resolveModelName(agent);
    LiveConnectConfig liveConnectConfig = buildLiveConnectConfig(context);

    return buildConfigWithStaticInstruction(agent, new ReadonlyContext(context))
        .map(config -> buildRequest(request, agent, modelName, config, liveConnectConfig));
  }

  private String resolveModelName(LlmAgent agent) {
    return agent.resolvedModel().model().isPresent()
        ? agent.resolvedModel().model().get().model()
        : agent.resolvedModel().modelName().get();
  }

  private LiveConnectConfig buildLiveConnectConfig(InvocationContext context) {
    LiveConnectConfig.Builder builder =
        LiveConnectConfig.builder().responseModalities(context.runConfig().responseModalities());

    Optional.ofNullable(context.runConfig().speechConfig()).ifPresent(builder::speechConfig);
    Optional.ofNullable(context.runConfig().outputAudioTranscription())
        .ifPresent(builder::outputAudioTranscription);
    Optional.ofNullable(context.runConfig().inputAudioTranscription())
        .ifPresent(builder::inputAudioTranscription);

    return builder.build();
  }

  private Single<GenerateContentConfig> buildConfigWithStaticInstruction(
      LlmAgent agent, ReadonlyContext context) {

    GenerateContentConfig baseConfig =
        agent.generateContentConfig().orElse(GenerateContentConfig.builder().build());

    return agent
        .canonicalStaticInstruction(context)
        .map(
            instructionEntry -> {
              String staticInstr = instructionEntry.getKey();

              if (staticInstr.isEmpty()) {
                return baseConfig;
              }

              Content systemInstruction =
                  Content.builder()
                      .parts(List.of(Part.builder().text(staticInstr).build()))
                      .build();

              return baseConfig.toBuilder().systemInstruction(systemInstruction).build();
            });
  }

  private RequestProcessor.RequestProcessingResult buildRequest(
      LlmRequest request,
      LlmAgent agent,
      String modelName,
      GenerateContentConfig config,
      LiveConnectConfig liveConnectConfig) {

    LlmRequest.Builder builder =
        request.toBuilder().model(modelName).config(config).liveConnectConfig(liveConnectConfig);

    agent.outputSchema().ifPresent(builder::outputSchema);

    return RequestProcessor.RequestProcessingResult.create(builder.build(), ImmutableList.of());
  }
}
