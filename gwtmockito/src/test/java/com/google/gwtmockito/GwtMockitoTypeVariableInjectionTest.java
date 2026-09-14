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
 *
 * <p>Also covers the multi-level propagation case: {@code Concrete extends Middle<MyPresenter>},
 * {@code Middle<P> extends Base<P>} — the type variable {@code Base.P} is forwarded through
 * {@code Middle} before being bound at {@code Concrete}. Resolution must recurse with the
 * original concrete class, not with {@code Middle}, to find the binding.
 */
@RunWith(GwtMockitoTestRunner.class)
public class GwtMockitoTypeVariableInjectionTest {

  /** Unique presenter interface — only one mock of this type exists, so injection is unambiguous. */
  interface MyPresenter {
    void present();
  }

  // ── single-level hierarchy: Concrete extends Base<MyPresenter> ─────────────

  /**
   * Generic base class whose {@code presenter} field is declared with a type variable.
   * Mirrors the real-world pattern {@code abstract class BaseView<P> { protected P presenter; }}.
   */
  static abstract class BaseView<P> {
    /** Type-variable field — erasure is Object.class without resolveTypeVariable. */
    protected P presenter;
  }

  /** Concrete subclass that binds {@code P = MyPresenter} directly. */
  static class ConcreteView extends BaseView<MyPresenter> {}

  // ── multi-level hierarchy: Concrete2 extends Middle<MyPresenter>, Middle<P> extends Base<P> ──

  /**
   * Intermediate generic class that forwards the type variable unchanged.
   * {@code Middle.P} is the same parameter slot as {@code BaseView.P}; it is only
   * bound to {@code MyPresenter} by {@code ConcreteView2}, not by {@code Middle} itself.
   */
  static abstract class MiddleView<P> extends BaseView<P> {}

  /** Concrete subclass that binds {@code P = MyPresenter} two levels up. */
  static class ConcreteView2 extends MiddleView<MyPresenter> {}

  // ── test fields ────────────────────────────────────────────────────────────

  /** The only mock of type {@code MyPresenter} — injected after type-variable resolution. */
  @Mock MyPresenter presenter;

  @InjectMocks ConcreteView view;
  @InjectMocks ConcreteView2 view2;

  // ── tests ──────────────────────────────────────────────────────────────────

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

  @Test
  public void testTypeVariableResolvedThroughIntermediateClass() {
    // Multi-level chain: ConcreteView2 → MiddleView<MyPresenter> → BaseView<P>.
    // resolveTypeVariable() encounters Base.P bound to Middle.P (still a TypeVariable),
    // then must recurse with concreteClass=ConcreteView2 (not with Middle) to find
    // the binding Middle.P=MyPresenter supplied by ConcreteView2.
    // Before the fix (child passed instead of concreteClass), the recursion started
    // from Middle and could not see ConcreteView2's binding, returning null.
    assertNotNull("view2 must not be null", view2);
    assertSame(
        "BaseView.presenter must be resolved through MiddleView and injected",
        presenter, view2.presenter);
  }
}
