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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
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
 *   <li>{@code catch (ClassCastException)} with valid {@code TypeBasedCandidateFilter} frame
 *       → {@code recoverInjection()}; without valid frame → re-throw; message mismatch →
 *       re-throw. Guard predicate tested via reflection; recovery logic tested via
 *       direct {@code recoverInjection} call.</li>
 *   <li>Non-ambiguity {@code MockitoException} re-throw — abstract-class {@code @InjectMocks}
 *       target produces a message without "multiple matching mocks".</li>
 *   <li>Plain-user-class ambiguity re-throw — {@code @InjectMocks} target that does NOT extend
 *       a GWT base class: the {@code hasInjectMocksTargetExtendingGwtBase} guard re-throws.</li>
 *   <li>{@code recoverInjection} lambda body ({@code sm.closeOnDemand()}) — called via direct
 *       {@code recoverInjection} invocation with a pre-populated {@code ScopedMock} field.</li>
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

  private static final String TYPE_BASED_FILTER_CLASS =
      "org.mockito.internal.configuration.injection.filter.TypeBasedCandidateFilter";

  /**
   * Produces a {@code ClassCastException} with:
   * <ol>
   *   <li>The real JVM message from casting a {@code TypeVariableImpl} to {@code Class}.</li>
   *   <li>A stack trace containing a {@code TypeBasedCandidateFilter.isCompatibleTypes} frame,
   *       exactly as Mockito 5 would produce.</li>
   * </ol>
   * Used both by predicate unit tests and end-to-end recovery tests.
   */
  private static ClassCastException realTypeVariableImplCce() throws Exception {
    java.lang.reflect.Type tv =
        GwtMockitoTypeVariableInjectionTest.BaseView.class
            .getDeclaredField("presenter").getGenericType();
    String msg;
    try { @SuppressWarnings("unused") Class<?> c = (Class<?>) tv; throw new AssertionError(); }
    catch (ClassCastException e) { msg = e.getMessage(); }

    ClassCastException cce = new ClassCastException(msg);
    cce.setStackTrace(new StackTraceElement[]{
        new StackTraceElement(TYPE_BASED_FILTER_CLASS, "isCompatibleTypes",
            "TypeBasedCandidateFilter.java", 42),
        new StackTraceElement(
            "org.mockito.internal.configuration.injection.PropertyAndSetterInjection",
            "injectMockCandidates", "PropertyAndSetterInjection.java", 100),
    });
    return cce;
  }

  private static boolean callIsTypeVariableImplCastException(ClassCastException e)
      throws Exception {
    Method m = GwtMockito.class.getDeclaredMethod(
        "isTypeVariableImplCastException", ClassCastException.class);
    m.setAccessible(true);
    return (Boolean) m.invoke(null, e);
  }

  // ── isTypeVariableImplCastException predicate unit tests ──────────────────

  @Test
  public void isTypeVariableImplCastException_trueForRealMockitoFrame() throws Exception {
    assertTrue("real TypeVariableImpl CCE with TypeBasedCandidateFilter frame must return true",
        callIsTypeVariableImplCastException(realTypeVariableImplCce()));
  }

  @Test
  public void isTypeVariableImplCastException_falseWhenMessageMismatch() throws Exception {
    ClassCastException cce = new ClassCastException("String cannot be cast to Integer");
    cce.setStackTrace(new StackTraceElement[]{
        new StackTraceElement(TYPE_BASED_FILTER_CLASS, "isCompatibleTypes",
            "TypeBasedCandidateFilter.java", 42),
    });
    assertFalse("CCE without TypeVariableImpl in message must return false",
        callIsTypeVariableImplCastException(cce));
  }

  @Test
  public void isTypeVariableImplCastException_falseWhenFrameMissing() throws Exception {
    java.lang.reflect.Type tv =
        GwtMockitoTypeVariableInjectionTest.BaseView.class
            .getDeclaredField("presenter").getGenericType();
    String msg;
    try { @SuppressWarnings("unused") Class<?> c = (Class<?>) tv; throw new AssertionError(); }
    catch (ClassCastException e) { msg = e.getMessage(); }
    ClassCastException cce = new ClassCastException(msg);
    cce.setStackTrace(new StackTraceElement[]{
        new StackTraceElement("com.myapp.SomeOtherClass", "someMethod", "SomeOtherClass.java", 10),
    });
    assertFalse("CCE with TypeVariableImpl message but wrong frame must return false",
        callIsTypeVariableImplCastException(cce));
  }

  // ── end-to-end: CCE recovery path ─────────────────────────────────────────
  //
  // The JVM fills in the stack trace at the `athrow` instruction regardless of
  // setStackTrace, so mockStatic cannot inject a synthetic stack frame into an
  // exception it throws. The guard predicate is verified separately above.
  // The recovery logic is verified here by calling recoverInjection directly,
  // with target left null so injection is observable.

  static class SimpleTarget {
    Object collaborator;
  }

  static class SimpleOwner {
    @Mock Object collaborator;
    @InjectMocks SimpleTarget target;
  }

  @Test
  public void recoverInjection_withNullTarget_constructsAndInjects() throws Exception {
    SimpleOwner owner = new SimpleOwner();
    // Populate only the @Mock field — simulates IndependentAnnotationEngine running
    // before PropertyAndSetterInjection threw, leaving target null.
    AutoCloseable mocks = MockitoAnnotations.openMocks(owner);
    owner.target = null;

    Method recoverInjection = GwtMockito.class.getDeclaredMethod(
        "recoverInjection", Object.class, RuntimeException.class);
    recoverInjection.setAccessible(true);
    try {
      AutoCloseable closeable = (AutoCloseable) recoverInjection.invoke(
          null, owner, realTypeVariableImplCce());

      assertNotNull("recoverInjection must return a non-null closeable", closeable);
      assertNotNull("recoverInjection must construct the null target", owner.target);
      assertSame("recoverInjection must inject collaborator by name",
          owner.collaborator, owner.target.collaborator);
      closeable.close();
    } finally {
      mocks.close();
    }
  }

  // ── catch (ClassCastException) re-throw branches ─────────────────────────

  @Test
  public void classCastException_rightMessageWrongFrame_isRethrown() throws Exception {
    SimpleOwner owner = new SimpleOwner();
    AutoCloseable mocks = MockitoAnnotations.openMocks(owner);

    java.lang.reflect.Type tv =
        GwtMockitoTypeVariableInjectionTest.BaseView.class
            .getDeclaredField("presenter").getGenericType();
    String msg;
    try { @SuppressWarnings("unused") Class<?> c = (Class<?>) tv; throw new AssertionError(); }
    catch (ClassCastException e) { msg = e.getMessage(); }
    ClassCastException unrelated = new ClassCastException(msg);
    unrelated.setStackTrace(new StackTraceElement[]{
        new StackTraceElement("com.myapp.SomePlugin", "doSomething", "SomePlugin.java", 5),
    });

    GwtMockito.initMocks(new Object()); // open GWT bridge before mockStatic intercepts openMocks
    try (MockedStatic<MockitoAnnotations> mockedStatic = mockStatic(MockitoAnnotations.class)) {
      mockedStatic.when(() -> MockitoAnnotations.openMocks(owner)).thenThrow(unrelated);
      try {
        callOpenMocksWithObjectFieldFix(owner);
        fail("CCE with TypeVariableImpl message but no Mockito frame must be rethrown");
      } catch (ClassCastException e) {
        if (e != unrelated) fail("A different exception was thrown: " + e);
      }
    } finally {
      GwtMockito.tearDown();
      mocks.close();
    }
  }

  @Test
  public void classCastException_unrelated_isRethrown() throws Exception {
    SimpleOwner owner = new SimpleOwner();
    AutoCloseable mocks = MockitoAnnotations.openMocks(owner);

    ClassCastException unrelated = new ClassCastException("String cannot be cast to Integer");
    GwtMockito.initMocks(new Object()); // open GWT bridge before mockStatic intercepts openMocks
    try (MockedStatic<MockitoAnnotations> mockedStatic = mockStatic(MockitoAnnotations.class)) {
      mockedStatic.when(() -> MockitoAnnotations.openMocks(owner)).thenThrow(unrelated);
      try {
        callOpenMocksWithObjectFieldFix(owner);
        fail("Expected unrelated ClassCastException to propagate");
      } catch (ClassCastException e) {
        if (e != unrelated) fail("A different exception was thrown: " + e);
      }
    } finally {
      GwtMockito.tearDown();
      mocks.close();
    }
  }

  // ── non-ambiguity MockitoException re-throw ───────────────────────────────

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
      String msg = e.getMessage();
      if (msg != null && msg.contains("there were multiple matching mocks")) {
        fail("This exception should NOT be the ambiguity one — got: " + msg);
      }
    } finally {
      GwtMockito.tearDown();
    }
  }

  // ── plain-user-class ambiguity: hasInjectMocksTargetExtendingGwtBase guard ─

  static class PlainTarget {
    Object collaborator;
  }

  static class SingleMockOwner {
    @Mock Object collaborator;
    @InjectMocks PlainTarget target;
  }

  @Test
  public void ambiguityOnPlainUserClass_isRethrown() throws Exception {
    SingleMockOwner prePopOwner = new SingleMockOwner();
    AutoCloseable mocks = MockitoAnnotations.openMocks(prePopOwner);

    MockitoException ambiguityEx = new MockitoException(
        "there were multiple matching mocks of type Object");
    GwtMockito.initMocks(new Object()); // open GWT bridge before mockStatic intercepts openMocks
    try (MockedStatic<MockitoAnnotations> mockedStatic = mockStatic(MockitoAnnotations.class)) {
      mockedStatic.when(() -> MockitoAnnotations.openMocks(prePopOwner)).thenThrow(ambiguityEx);
      try {
        callOpenMocksWithObjectFieldFix(prePopOwner);
        fail("Expected the ambiguity MockitoException to be rethrown for a plain user class");
      } catch (MockitoException e) {
        if (e != ambiguityEx) fail("A different exception was thrown: " + e);
      }
    } finally {
      GwtMockito.tearDown();
      mocks.close();
    }
  }

  // ── recoverInjection lambda body — closeOnDemand() called ─────────────────

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
    GwtMockito.initMocks(owner);
    GwtMockito.tearDown();
    assertNotNull("view must not be null after recovery", owner.view);
    assertSame("label must be injected by name", owner.label, owner.view.label);
    assertSame("textBox must be injected by name", owner.textBox, owner.view.textBox);
  }

  static class OwnerWithScopedMockAndInjectMocks {
    @Mock org.mockito.ScopedMock scoped;
    @InjectMocks SimpleTarget target;
  }

  @Test
  public void recoverInjectionLambda_closeOnDemandCalledForScopedMock() throws Exception {
    OwnerWithScopedMockAndInjectMocks owner = new OwnerWithScopedMockAndInjectMocks();
    AutoCloseable mocks = MockitoAnnotations.openMocks(owner);

    org.mockito.ScopedMock scopedSpy = org.mockito.Mockito.mock(org.mockito.ScopedMock.class);
    java.lang.reflect.Field f =
        OwnerWithScopedMockAndInjectMocks.class.getDeclaredField("scoped");
    f.setAccessible(true);
    f.set(owner, scopedSpy);

    // Call recoverInjection directly — the JVM overwrites setStackTrace frames when
    // re-throwing via mockStatic, so we bypass the guard predicate (tested separately above).
    Method recoverInjection = GwtMockito.class.getDeclaredMethod(
        "recoverInjection", Object.class, RuntimeException.class);
    recoverInjection.setAccessible(true);
    try {
      AutoCloseable closeable = (AutoCloseable) recoverInjection.invoke(
          null, owner, realTypeVariableImplCce());
      closeable.close();
      org.mockito.Mockito.verify(scopedSpy).closeOnDemand();
    } finally {
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
