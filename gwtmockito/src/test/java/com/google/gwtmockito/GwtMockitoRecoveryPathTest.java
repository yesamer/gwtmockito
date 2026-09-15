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
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mockStatic;

import com.google.gwt.user.client.ui.Composite;
import com.google.gwt.user.client.ui.Label;
import com.google.gwt.user.client.ui.TextBox;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.MockitoAnnotations;
import org.mockito.exceptions.base.MockitoException;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * Runnerless (JUnit4) tests for the recovery paths introduced in
 * {@link GwtMockito#openMocksWithObjectFieldFix} and {@link GwtMockito#recoverInjection}.
 *
 * <p>Running under plain JUnit4 (not {@link GwtMockitoTestRunner}) means JaCoCo can instrument
 * the production class directly, covering lines that the Javassist-classloader tests cannot reach.
 *
 * <p>Covered lines:
 * <ul>
 *   <li>{@code catch (ClassCastException)} with {@code @InjectMocks} → {@code recoverInjection()}
 *       and without {@code @InjectMocks} → re-throw; both via {@code mockStatic}.</li>
 *   <li>Non-ambiguity {@code MockitoException} re-throw — abstract-class {@code @InjectMocks}
 *       target produces a message without "multiple matching mocks".</li>
 *   <li>Plain-user-class ambiguity re-throw — {@code @InjectMocks} target that does NOT extend
 *       a GWT base class: the {@code hasInjectMocksTargetExtendingGwtBase} guard re-throws.</li>
 *   <li>{@code recoverInjection} lambda body ({@code sm.closeOnDemand()}) — via the runnerless
 *       API: {@code initMocks} on an ambiguity-triggering owner, then {@code tearDown()}.</li>
 * </ul>
 */
@RunWith(JUnit4.class)
public class GwtMockitoRecoveryPathTest {

  // ── helpers ────────────────────────────────────────────────────────────────

  private static AutoCloseable callOpenMocksWithObjectFieldFix(Object owner) throws Exception {
    Method m = GwtMockito.class.getDeclaredMethod("openMocksWithObjectFieldFix", Object.class);
    m.setAccessible(true);
    try {
      return (AutoCloseable) m.invoke(null, owner);
    } catch (InvocationTargetException ite) {
      Throwable cause = ite.getCause();
      if (cause instanceof RuntimeException) throw (RuntimeException) cause;
      throw new RuntimeException(cause);
    }
  }

  // ── catch (ClassCastException) ────────────────────────────────────────────

  static class SimpleTarget {
    Object collaborator;
  }

  static class SimpleOwner {
    @Mock Object collaborator;
    @InjectMocks SimpleTarget target;
  }

  /** Owner with no @InjectMocks field — a CCE here is unrelated and must propagate. */
  static class NoInjectMocksOwner {
    @Mock Object mock;
  }

  @Test
  public void classCastException_withInjectMocks_routesToRecoverInjection() throws Exception {
    SimpleOwner owner = new SimpleOwner();
    // Pre-populate @Mock fields so recoverInjection can find them.
    AutoCloseable mocks = MockitoAnnotations.openMocks(owner);

    try (MockedStatic<MockitoAnnotations> mockedStatic = mockStatic(MockitoAnnotations.class)) {
      mockedStatic.when(() -> MockitoAnnotations.openMocks(owner))
          .thenThrow(new ClassCastException("simulated TypeVariableImpl CCE"));

      GwtMockito.initMocks(new Object()); // open bridge
      AutoCloseable closeable = callOpenMocksWithObjectFieldFix(owner);

      assertNotNull("recoverInjection must return a non-null closeable", closeable);
      assertNotNull("target must have been constructed", owner.target);
      assertSame("collaborator must be injected by name",
          owner.collaborator, owner.target.collaborator);
      closeable.close();
    } finally {
      GwtMockito.tearDown();
      mocks.close();
    }
  }

  @Test
  public void classCastException_withoutInjectMocks_isRethrown() throws Exception {
    NoInjectMocksOwner owner = new NoInjectMocksOwner();
    AutoCloseable mocks = MockitoAnnotations.openMocks(owner);

    ClassCastException cce = new ClassCastException("unrelated CCE");
    try (MockedStatic<MockitoAnnotations> mockedStatic = mockStatic(MockitoAnnotations.class)) {
      mockedStatic.when(() -> MockitoAnnotations.openMocks(owner)).thenThrow(cce);

      GwtMockito.initMocks(new Object()); // open bridge
      try {
        callOpenMocksWithObjectFieldFix(owner);
        fail("Expected ClassCastException to propagate when there is no @InjectMocks field");
      } catch (ClassCastException e) {
        if (e != cce) {
          fail("A different exception was thrown: " + e);
        }
      }
    } finally {
      GwtMockito.tearDown();
      mocks.close();
    }
  }

  // ── non-ambiguity MockitoException re-throw ───────────────────────────────

  /** Abstract class — Mockito cannot instantiate it and throws a different MockitoException. */
  abstract static class UninstantiableTarget {}

  static class OwnerWithAbstractInjectMocks {
    @Mock Object mock;
    @InjectMocks UninstantiableTarget target;
  }

  @Test
  public void nonAmbiguityMockitoException_isRethrownUnchanged() throws Exception {
    GwtMockito.initMocks(new Object()); // open bridge
    try {
      callOpenMocksWithObjectFieldFix(new OwnerWithAbstractInjectMocks());
      fail("Expected MockitoException to be rethrown");
    } catch (MockitoException e) {
      // Confirm the message does NOT contain the ambiguity string — this is the re-throw branch.
      String msg = e.getMessage();
      if (msg != null && msg.contains("there were multiple matching mocks")) {
        fail("This exception should NOT be the ambiguity one — got: " + msg);
      }
    } finally {
      GwtMockito.tearDown();
    }
  }

  // ── plain-user-class ambiguity: hasInjectMocksTargetExtendingGwtBase guard ─

  /**
   * A plain user class — does NOT extend any GWT base class. When Mockito reports
   * "there were multiple matching mocks" for this target, the
   * {@code hasInjectMocksTargetExtendingGwtBase} guard must re-throw the exception
   * rather than silently recovering with placeholder injection.
   *
   * <p>The pre-population call uses a separate owner instance whose fields do NOT conflict
   * (only one mock, so openMocks() succeeds). The {@code mockStatic} block then makes
   * openMocks() throw the ambiguity exception when called on {@code ambiguousOwner}.
   */
  static class PlainTarget {
    Object collaborator;
  }

  /** Single-mock owner used for the pre-population openMocks() call (no ambiguity). */
  static class SingleMockOwner {
    @Mock Object collaborator;
    @InjectMocks PlainTarget target;
  }

  @Test
  public void ambiguityOnPlainUserClass_isRethrown() throws Exception {
    // Pre-populate mocks on a non-ambiguous owner so Mockito has an active session.
    SingleMockOwner prePopOwner = new SingleMockOwner();
    AutoCloseable mocks = MockitoAnnotations.openMocks(prePopOwner);

    // Simulate Mockito 5 throwing the ambiguity exception on a call with a plain target.
    MockitoException ambiguityEx = new MockitoException(
        "there were multiple matching mocks of type Object");
    try (MockedStatic<MockitoAnnotations> mockedStatic = mockStatic(MockitoAnnotations.class)) {
      mockedStatic.when(() -> MockitoAnnotations.openMocks(prePopOwner))
          .thenThrow(ambiguityEx);

      GwtMockito.initMocks(new Object()); // open bridge
      try {
        callOpenMocksWithObjectFieldFix(prePopOwner);
        fail("Expected the ambiguity MockitoException to be rethrown for a plain user class");
      } catch (MockitoException e) {
        // The exact same exception instance must propagate — not swallowed by recovery.
        if (e != ambiguityEx) {
          fail("A different exception was thrown: " + e);
        }
      }
    } finally {
      GwtMockito.tearDown();
      mocks.close();
    }
  }

  // ── lines 236-238: recoverInjection lambda body — closeOnDemand() called ───

  static class AmbiguityOwner {
    @Mock Label label;
    @Mock TextBox textBox;
    @InjectMocks CompositeView view;
  }

  static class CompositeView extends Composite {
    Label label;
    TextBox textBox;
  }

  @Test
  public void recoverInjectionLambda_executedWhenTearDownCloses() {
    AmbiguityOwner owner = new AmbiguityOwner();
    // initMocks triggers the "multiple matching mocks" recovery path. The returned
    // AutoCloseable wraps the (empty) scopedMocks list; tearDown() calls close() on it,
    // executing the for-loop in recoverInjection. The list is empty here so the loop
    // body (sm.closeOnDemand()) needs the separate test below to be covered.
    GwtMockito.initMocks(owner);
    GwtMockito.tearDown();
    assertNotNull("view must not be null after recovery", owner.view);
    assertSame("label must be injected by name", owner.label, owner.view.label);
    assertSame("textBox must be injected by name", owner.textBox, owner.view.textBox);
  }

  // Owner that carries a ScopedMock alongside a regular mock and an @InjectMocks target.
  // Used to exercise the sm.closeOnDemand() loop body inside recoverInjection.
  static class OwnerWithScopedMockAndInjectMocks {
    @Mock org.mockito.ScopedMock scoped;
    @InjectMocks SimpleTarget target;
  }

  @Test
  public void recoverInjectionLambda_closeOnDemandCalledForScopedMock() throws Exception {
    OwnerWithScopedMockAndInjectMocks owner = new OwnerWithScopedMockAndInjectMocks();

    // Pre-populate @Mock fields. The scoped field gets a real ScopedMock mock instance.
    AutoCloseable mocks = MockitoAnnotations.openMocks(owner);
    // Manually replace the ScopedMock field with a spy we can verify.
    org.mockito.ScopedMock scopedSpy = org.mockito.Mockito.mock(org.mockito.ScopedMock.class);
    java.lang.reflect.Field f =
        OwnerWithScopedMockAndInjectMocks.class.getDeclaredField("scoped");
    f.setAccessible(true);
    f.set(owner, scopedSpy);

    // Make openMocks throw ClassCastException so recoverInjection runs and collects scopedSpy.
    try (MockedStatic<MockitoAnnotations> mockedStatic = mockStatic(MockitoAnnotations.class)) {
      mockedStatic.when(() -> MockitoAnnotations.openMocks(owner))
          .thenThrow(new ClassCastException("simulated CCE with ScopedMock"));

      GwtMockito.initMocks(new Object()); // open bridge
      AutoCloseable closeable = callOpenMocksWithObjectFieldFix(owner);
      // Closing the AutoCloseable returned by recoverInjection must call closeOnDemand()
      // on the ScopedMock — this exercises lines 237-238.
      closeable.close();
      org.mockito.Mockito.verify(scopedSpy).closeOnDemand();
    } finally {
      GwtMockito.tearDown();
      mocks.close();
    }
  }

  // ── lifecycle ──────────────────────────────────────────────────────────────

  @Before
  public void setUp() {
    // Each test manages initMocks/tearDown explicitly.
  }

  @After
  public void tearDown() {
    GwtMockito.tearDown(); // idempotent no-op if already closed
  }
}
