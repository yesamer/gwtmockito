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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.JUnitCore;
import org.junit.runner.Result;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.InjectMocks;
import org.mockito.Mock;

/**
 * Tests for the {@code withAfters} override in {@link GwtMockitoTestRunner} and for
 * {@code collectOwnerMocks} subclass-priority behaviour, both added in the Mockito 5
 * compatibility upgrade.
 *
 * <p>Tests that require inspecting runner-level behaviour (paths 1 and 2) drive fake inner test
 * classes through a real {@link JUnitCore} run and inspect the {@link Result}.
 */
@RunWith(JUnit4.class)
public class GwtMockitoWithAftersTest {

  // -------------------------------------------------------------------------
  // Path 1a: withAfters addSuppressed — the primary test-failure AssertionError
  // must survive as the reported failure when tearDown succeeds.
  // -------------------------------------------------------------------------

  /**
   * Fake test that always throws an {@link AssertionError}.  The runner's
   * {@code withAfters} will call {@code tearDown} in its finally block; the
   * original error must be what is reported to the notifier.
   */
  @RunWith(GwtMockitoTestRunner.class)
  public static class FailingTest {
    @Mock Object mock;

    @Test
    public void alwaysFails() {
      throw new AssertionError("primary failure");
    }
  }

  @Test
  public void primaryFailureIsPreservedWhenTestFails() {
    Result result = JUnitCore.runClasses(FailingTest.class);

    assertEquals("Expected exactly one failure", 1, result.getFailureCount());
    Throwable thrown = result.getFailures().get(0).getException();
    // The primary AssertionError must be the top-level reported failure.
    assertTrue("Expected AssertionError as top-level failure, got: " + thrown.getClass(),
        thrown instanceof AssertionError);
    assertEquals("primary failure", thrown.getMessage());
  }

  // -------------------------------------------------------------------------
  // Path 1b: withAfters addSuppressed — when BOTH the test body and tearDown
  // throw, the primary failure must remain top-level and the teardown
  // exception must appear in getSuppressed(), not replace the primary.
  // -------------------------------------------------------------------------

  /**
   * Fake test that:
   * <ol>
   *   <li>Replaces {@code GwtMockito.openMocksCloseable} with a lambda that
   *       throws, so that the runner's {@code tearDown()} call propagates a
   *       {@code RuntimeException} after the test body fails.</li>
   *   <li>Throws an {@link AssertionError} as the primary failure.</li>
   * </ol>
   * The runner must attach the teardown exception as a suppressed cause of the
   * primary {@code AssertionError}, not replace it.
   */
  @RunWith(GwtMockitoTestRunner.class)
  public static class FailingTestWithTearDownFailure {
    @Mock Object mock;

    @Test
    public void failsWithBrokenTearDown() throws Exception {
      // Inject a closeable that throws into the static field that tearDown() will close.
      // We are running inside the runner's classloader so this GwtMockito class is the
      // same instance the runner uses — no cross-classloader gap.
      java.lang.reflect.Field f = GwtMockito.class.getDeclaredField("openMocksCloseable");
      f.setAccessible(true);
      f.set(null, (AutoCloseable) () -> { throw new Exception("teardown bang"); });

      throw new AssertionError("primary failure");
    }
  }

  @Test
  public void tearDownExceptionIsSuppressedWhenTestAlsoFails() {
    Result result = JUnitCore.runClasses(FailingTestWithTearDownFailure.class);

    assertEquals("Expected exactly one reported failure", 1, result.getFailureCount());
    Throwable thrown = result.getFailures().get(0).getException();

    // Primary failure must be the AssertionError, not the teardown RuntimeException.
    assertTrue("Primary failure must be AssertionError, got: " + thrown.getClass(),
        thrown instanceof AssertionError);
    assertEquals("primary failure", thrown.getMessage());

    // Teardown exception must appear as a suppressed cause.
    Throwable[] suppressed = thrown.getSuppressed();
    assertEquals("Exactly one suppressed exception expected", 1, suppressed.length);
    // withAfters wraps the tearDown RuntimeException in another RuntimeException.
    assertTrue("Suppressed cause must be RuntimeException wrapping the teardown failure",
        suppressed[0] instanceof RuntimeException);
    // Verify the teardown failure is reachable in the cause chain.
    Throwable cause = suppressed[0];
    boolean found = false;
    while (cause != null) {
      if ("teardown bang".equals(cause.getMessage())) { found = true; break; }
      cause = cause.getCause();
    }
    assertTrue("'teardown bang' must be reachable in the suppressed cause chain", found);
  }

  // -------------------------------------------------------------------------
  // Path 2: collectOwnerMocks putIfAbsent — when a subclass and a superclass
  // both declare an @Mock field with the same name, the subclass instance must
  // be used for injection (Mockito's own name-priority).
  //
  // Strategy: run a fake inner test class through JUnitCore and assert it
  // passes cleanly.  The inner test injects into a target whose field name
  // matches only the subclass mock; if the superclass mock were used instead
  // the assertSame inside the inner test would fail.
  // -------------------------------------------------------------------------

  /** Unique type so name-based injection is unambiguous even across classloaders. */
  interface NamedCollaborator {
    void act();
  }

  /** View target with one field named {@code namedCollab}. */
  static class TargetView {
    NamedCollaborator namedCollab;
  }

  /**
   * Base class declaring a {@code @Mock} field named {@code baseCollab} — a different name from
   * the subclass field so that Mockito does not raise its own "multiple fields of same type"
   * error. The subclass field named {@code namedCollab} must win when injecting into TargetView
   * because collectOwnerMocks uses putIfAbsent (subclass-first walk).
   */
  @RunWith(GwtMockitoTestRunner.class)
  public static abstract class BaseWithMock {
    /** Different name — ensures Mockito does not confuse this with the subclass mock. */
    @Mock NamedCollaborator baseCollab;
  }

  /**
   * Subclass that declares its own mock for the same type with the name that matches
   * {@code TargetView.namedCollab}. collectOwnerMocks must put this entry first (putIfAbsent),
   * so injection into TargetView uses this instance rather than the superclass one.
   */
  @RunWith(GwtMockitoTestRunner.class)
  public static class ConcreteWithMock extends BaseWithMock {
    /** Name matches TargetView.namedCollab — this instance must be injected. */
    @Mock NamedCollaborator namedCollab;

    @InjectMocks TargetView targetView;

    @Test
    public void subclassMockIsUsedForInjection() {
      // collectOwnerMocks walks subclass → superclass with putIfAbsent.
      // "namedCollab" from ConcreteWithMock is seen first and wins.
      // TargetView.namedCollab must be the subclass instance, not baseCollab.
      assertNotNull("targetView must not be null", targetView);
      assertSame("subclass namedCollab must be injected", namedCollab, targetView.namedCollab);
    }
  }

  @Test
  public void subclassMockTakesPriorityOverSameNamedSuperclassMock() {
    // If collectOwnerMocks used plain put() instead of putIfAbsent(), the
    // superclass mock would overwrite the subclass entry in the map and the
    // inner test's assertSame would fail.
    Result result = JUnitCore.runClasses(ConcreteWithMock.class);
    assertEquals("Inner test must pass: subclass mock must win over superclass mock",
        0, result.getFailureCount());
  }
}
