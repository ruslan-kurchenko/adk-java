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

package com.google.adk.agents;

import static com.google.common.base.Strings.nullToEmpty;

import com.google.adk.agents.ConfigAgentUtils.ConfigurationException;
import com.google.adk.events.Event;
import com.google.adk.utils.ComponentRegistry;
import com.google.common.collect.ImmutableList;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import io.reactivex.rxjava3.core.Flowable;
import java.util.List;
import java.util.function.Consumer;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** An agent that runs its sub-agents sequentially. */
public class SequentialAgent extends BaseAgent {

  private static final Logger logger = LoggerFactory.getLogger(SequentialAgent.class);

  /**
   * Constructor for SequentialAgent.
   *
   * @param name The agent's name.
   * @param description The agent's description.
   * @param subAgents The list of sub-agents to run sequentially.
   * @param beforeAgentCallback Optional callback before the agent runs.
   * @param afterAgentCallback Optional callback after the agent runs.
   */
  private SequentialAgent(
      String name,
      String description,
      List<? extends BaseAgent> subAgents,
      List<Callbacks.BeforeAgentCallback> beforeAgentCallback,
      List<Callbacks.AfterAgentCallback> afterAgentCallback) {

    super(name, description, subAgents, beforeAgentCallback, afterAgentCallback);
  }

  /** Builder for {@link SequentialAgent}. */
  public static class Builder {
    private String name;
    private String description;
    private List<? extends BaseAgent> subAgents;
    private ImmutableList<Callbacks.BeforeAgentCallback> beforeAgentCallback;
    private ImmutableList<Callbacks.AfterAgentCallback> afterAgentCallback;

    @CanIgnoreReturnValue
    public Builder name(String name) {
      this.name = name;
      return this;
    }

    @CanIgnoreReturnValue
    public Builder description(String description) {
      this.description = description;
      return this;
    }

    @CanIgnoreReturnValue
    public Builder subAgents(List<? extends BaseAgent> subAgents) {
      this.subAgents = subAgents;
      return this;
    }

    @CanIgnoreReturnValue
    public Builder subAgents(BaseAgent... subAgents) {
      this.subAgents = ImmutableList.copyOf(subAgents);
      return this;
    }

    @CanIgnoreReturnValue
    public Builder beforeAgentCallback(Callbacks.BeforeAgentCallback beforeAgentCallback) {
      this.beforeAgentCallback = ImmutableList.of(beforeAgentCallback);
      return this;
    }

    @CanIgnoreReturnValue
    public Builder beforeAgentCallback(
        List<Callbacks.BeforeAgentCallbackBase> beforeAgentCallback) {
      this.beforeAgentCallback = CallbackUtil.getBeforeAgentCallbacks(beforeAgentCallback);
      return this;
    }

    @CanIgnoreReturnValue
    public Builder afterAgentCallback(Callbacks.AfterAgentCallback afterAgentCallback) {
      this.afterAgentCallback = ImmutableList.of(afterAgentCallback);
      return this;
    }

    @CanIgnoreReturnValue
    public Builder afterAgentCallback(List<Callbacks.AfterAgentCallbackBase> afterAgentCallback) {
      this.afterAgentCallback = CallbackUtil.getAfterAgentCallbacks(afterAgentCallback);
      return this;
    }

    public SequentialAgent build() {
      // TODO(b/410859954): Add validation for required fields like name.
      return new SequentialAgent(
          name, description, subAgents, beforeAgentCallback, afterAgentCallback);
    }
  }

  public static Builder builder() {
    return new Builder();
  }

  /**
   * Runs sub-agents sequentially.
   *
   * @param invocationContext Invocation context.
   * @return Flowable emitting events from sub-agents.
   */
  @Override
  protected Flowable<Event> runAsyncImpl(InvocationContext invocationContext) {
    return Flowable.fromIterable(subAgents())
        .concatMap(subAgent -> subAgent.runAsync(invocationContext));
  }

  /**
   * Runs sub-agents sequentially in live mode.
   *
   * @param invocationContext Invocation context.
   * @return Flowable emitting events from sub-agents in live mode.
   */
  @Override
  protected Flowable<Event> runLiveImpl(InvocationContext invocationContext) {
    return Flowable.fromIterable(subAgents())
        .concatMap(subAgent -> subAgent.runLive(invocationContext));
  }

  /**
   * Creates a SequentialAgent from configuration.
   *
   * @param config the agent configuration
   * @param configAbsPath The absolute path to the agent config file.
   * @return the configured SequentialAgent
   * @throws ConfigurationException if the configuration is invalid
   */
  public static SequentialAgent fromConfig(SequentialAgentConfig config, String configAbsPath)
      throws ConfigurationException {
    logger.debug("Creating SequentialAgent from config: {}", config.name());

    // Validate required fields
    if (config.name() == null || config.name().trim().isEmpty()) {
      throw new ConfigurationException("Agent name is required");
    }

    // Create builder with required fields
    Builder builder =
        SequentialAgent.builder()
            .name(config.name())
            .description(nullToEmpty(config.description()));

    // Resolve and add subagents using the utility class
    if (config.subAgents() != null && !config.subAgents().isEmpty()) {
      ImmutableList<BaseAgent> subAgents =
          ConfigAgentUtils.resolveSubAgents(config.subAgents(), configAbsPath);
      builder.subAgents(subAgents);
    }

    // Resolve callbacks if configured
    setCallbacksFromConfig(config, builder);

    // Build and return the agent
    SequentialAgent agent = builder.build();
    logger.info(
        "Successfully created SequentialAgent: {} with {} subagents",
        agent.name(),
        agent.subAgents() != null ? agent.subAgents().size() : 0);

    return agent;
  }

  private static void setCallbacksFromConfig(SequentialAgentConfig config, Builder builder)
      throws ConfigurationException {
    setCallbackFromConfig(
        config.beforeAgentCallbacks(),
        Callbacks.BeforeAgentCallbackBase.class,
        "before_agent_callback",
        builder::beforeAgentCallback);
    setCallbackFromConfig(
        config.afterAgentCallbacks(),
        Callbacks.AfterAgentCallbackBase.class,
        "after_agent_callback",
        builder::afterAgentCallback);
  }

  private static <T> void setCallbackFromConfig(
      @Nullable List<SequentialAgentConfig.CallbackRef> refs,
      Class<T> callbackBaseClass,
      String callbackTypeName,
      Consumer<ImmutableList<T>> builderSetter)
      throws ConfigurationException {
    if (refs != null) {
      ImmutableList.Builder<T> list = ImmutableList.builder();
      for (SequentialAgentConfig.CallbackRef ref : refs) {
        list.add(
            ComponentRegistry.getInstance()
                .get(ref.name(), callbackBaseClass)
                .orElseThrow(
                    () ->
                        new ConfigurationException(
                            "Invalid " + callbackTypeName + ": " + ref.name())));
      }
      builderSetter.accept(list.build());
    }
  }
}
