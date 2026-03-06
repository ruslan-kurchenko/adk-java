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

import com.google.adk.events.Event;
import com.google.adk.sessions.Session;
import com.google.genai.types.Content;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Provides read-only access to the context of an agent run. */
public class ReadonlyContext {

  protected final InvocationContext invocationContext;
  private List<Event> eventsView;
  private Map<String, Object> stateView;

  public ReadonlyContext(InvocationContext invocationContext) {
    this.invocationContext = invocationContext;
  }

  /** Returns the user content that initiated this invocation. */
  public Optional<Content> userContent() {
    return invocationContext.userContent();
  }

  /**
   * Returns the invocation context.
   *
   * <p><b>Note:</b> The returned context is mutable. Prefer using the specific accessor methods on
   * this class (e.g., {@link #events()}, {@link #state()}) which provide unmodifiable views.
   */
  public InvocationContext invocationContext() {
    return invocationContext;
  }

  /** Returns the ID of the current invocation. */
  public String invocationId() {
    return invocationContext.invocationId();
  }

  /** Returns the branch of the current invocation, if present. */
  public Optional<String> branch() {
    return invocationContext.branch();
  }

  /** Returns the name of the agent currently running. */
  public String agentName() {
    return invocationContext.agent().name();
  }

  /** Returns the user ID. */
  public String userId() {
    return invocationContext.session().userId();
  }

  /** Returns the session ID. */
  public String sessionId() {
    return invocationContext.session().id();
  }

  /**
   * Returns the session object from the current invocation context.
   *
   * @deprecated This method exposes the mutable {@link Session} object, which is inconsistent with
   *     the read-only contract of this class. Use {@link #events()}, {@link #state()}, {@link
   *     #sessionId()}, or {@link #userId()} instead. If full session access is required, use {@link
   *     #invocationContext()} explicitly.
   */
  @Deprecated
  public Session session() {
    return invocationContext.session();
  }

  /**
   * Returns an unmodifiable view of the events of the session.
   *
   * <p><b>Warning:</b> This is a live view, not a snapshot.
   */
  public List<Event> events() {
    if (eventsView == null) {
      eventsView = Collections.unmodifiableList(invocationContext.session().events());
    }
    return eventsView;
  }

  /**
   * Returns an unmodifiable view of the state of the session.
   *
   * <p><b>Warning:</b> This is a live view, not a snapshot.
   */
  public Map<String, Object> state() {
    if (stateView == null) {
      stateView = Collections.unmodifiableMap(invocationContext.session().state());
    }
    return stateView;
  }
}
