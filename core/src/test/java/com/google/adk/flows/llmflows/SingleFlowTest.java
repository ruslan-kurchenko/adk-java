/*
 * Copyright 2026 Google LLC
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

import static com.google.common.truth.Truth.assertThat;

import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class SingleFlowTest {

  @Test
  public void requestProcessors_containsCompaction() {
    boolean hasCompaction =
        SingleFlow.REQUEST_PROCESSORS.stream()
            .anyMatch(processor -> processor instanceof Compaction);
    assertThat(hasCompaction).isTrue();
  }

  @Test
  public void requestProcessors_contextCacheProcessor_isPerSingleFlowInstance() {
    ContextCacheProcessor processor1 =
        findContextCacheProcessor(new SingleFlow().requestProcessors);
    ContextCacheProcessor processor2 =
        findContextCacheProcessor(new SingleFlow().requestProcessors);

    assertThat(processor1).isNotSameInstanceAs(processor2);
  }

  @Test
  public void requestProcessors_contextCacheProcessor_isPerAutoFlowInstance() {
    ContextCacheProcessor processor1 = findContextCacheProcessor(new AutoFlow().requestProcessors);
    ContextCacheProcessor processor2 = findContextCacheProcessor(new AutoFlow().requestProcessors);

    assertThat(processor1).isNotSameInstanceAs(processor2);
  }

  private static ContextCacheProcessor findContextCacheProcessor(
      List<RequestProcessor> requestProcessors) {
    return (ContextCacheProcessor)
        requestProcessors.stream()
            .filter(processor -> processor instanceof ContextCacheProcessor)
            .findFirst()
            .orElseThrow(
                () -> new AssertionError("ContextCacheProcessor not found in request processors"));
  }
}
