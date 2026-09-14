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
 * property/setter injection.  This test has a single plain (non-GWT-base) {@code @InjectMocks}
 * target with one uniquely-named collaborator to confirm that field is populated.
 */
@RunWith(GwtMockitoTestRunner.class)
public class GwtMockitoSuccessPathInjectionTest {

  /** A plain collaborator interface — no GWT base class, so openMocks() never throws. */
  interface Collaborator {
    void doWork();
  }

  /** Simple target with one collaborator field, injected by name. */
  static class PlainView {
    Collaborator collaborator;
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
