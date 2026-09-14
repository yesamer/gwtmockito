/*
 * Copyright (C) 2026 YCM
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.google.gwtmockito;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;

/**
 * Verifies the success-path {@code injectIntoAllTargets} call in
 * {@link GwtMockito#initMocks(Object)}.
 *
 * <p>When {@link org.mockito.MockitoAnnotations#openMocks(Object)} completes without throwing
 * (no GWT base-class ambiguity), GwtMockito still calls {@code injectIntoAllTargets()} to
 * fill any fields that Mockito 5 may have skipped because constructor injection short-circuited
 * property/setter injection.
 *
 * <p>{@code PlainView} exposes a {@code String}-taking constructor that Mockito's constructor
 * strategy selects (it is the only constructor with a matching mock type — there is none, so
 * Mockito falls back to the no-arg path, but the {@code collaborator} field is left null after
 * constructor injection, forcing the success-path {@code injectIntoAllTargets} fallback to fill
 * it).  A plain no-arg constructor would allow Mockito's own property injection to set the field
 * directly, making the fallback invisible to the assertion.
 */
@RunWith(GwtMockitoTestRunner.class)
public class GwtMockitoSuccessPathInjectionTest {

  /** A plain collaborator interface — no GWT base class, so openMocks() never throws. */
  interface Collaborator {
    void doWork();
  }

  /**
   * Target whose explicit constructor causes Mockito 5 to attempt constructor injection
   * first.  The constructor takes a {@code String} for which no mock exists, so Mockito
   * falls back to the no-arg path and leaves {@code collaborator} null, requiring
   * GwtMockito's success-path {@code injectIntoAllTargets} fallback to populate it.
   */
  static class PlainView {
    Collaborator collaborator;
    PlainView(String ignored) {}
  }

  /** The sole mock — unique type, unique name, so injection is unambiguous. */
  @Mock Collaborator collaborator;

  @InjectMocks PlainView view;

  @Test
  public void testSuccessPathInjectIntoAllTargetsFillsCollaboratorField() {
    // openMocks() succeeds without throwing (no GWT base class ambiguity present).
    // GwtMockito's success-path injectIntoAllTargets() must fill view.collaborator.
    assertNotNull("view must not be null", view);
    assertSame("collaborator must be injected into PlainView.collaborator by name",
        collaborator, view.collaborator);
  }
}
