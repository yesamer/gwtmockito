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
 * Verifies that {@code resolveTypeVariable} correctly resolves a type-parameter field to its
 * concrete bound when injecting mocks into an {@code @InjectMocks} target.
 *
 * <p>When a field is declared as a generic type parameter (e.g. {@code protected P presenter}
 * in {@code BaseView<P>}), {@code f.getType()} returns the erasure {@code Object.class}, which
 * would match every mock and cause incorrect ambiguous-placeholder injection.
 * {@code resolveTypeVariable} walks the generic superclass chain to resolve {@code P} to its
 * concrete bound ({@code MyPresenter}), ensuring the correctly-typed mock is injected.
 */
@RunWith(GwtMockitoTestRunner.class)
public class GwtMockitoTypeVariableInjectionTest {

  /** Unique presenter interface — only one mock of this type exists, so injection is unambiguous. */
  interface MyPresenter {
    void present();
  }

  /**
   * Generic base class whose {@code presenter} field is declared with a type variable.
   * Mirrors the real-world pattern {@code abstract class BaseView<P> { protected P presenter; }}.
   */
  static abstract class BaseView<P> {
    /** Type-variable field — erasure is Object.class without resolveTypeVariable. */
    protected P presenter;
  }

  /** Concrete subclass that binds {@code P = MyPresenter}. */
  static class ConcreteView extends BaseView<MyPresenter> {}

  /** The only mock of type {@code MyPresenter} — injected after type-variable resolution. */
  @Mock MyPresenter presenter;

  @InjectMocks ConcreteView view;

  @Test
  public void testTypeVariableFieldIsResolvedAndInjectedCorrectly() {
    // resolveTypeVariable() must resolve P → MyPresenter, then find the unique
    // compatible mock and inject it.  Without the fix, presenter would be null
    // (erasure Object → ambiguous → placeholder used instead of our mock).
    assertNotNull("view must not be null", view);
    assertSame(
        "BaseView.presenter (P resolved to MyPresenter) must be the declared @Mock",
        presenter, view.presenter);
  }
}
