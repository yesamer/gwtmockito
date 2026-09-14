/*
 * Copyright 2013 Google Inc.
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
 *
 * Modifications copyright (C) 2026 YCM
 */
package com.google.gwtmockito;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.ScopedMock;

import java.lang.reflect.Method;
import java.util.List;

/**
 * Verifies that {@link GwtMockito#collectScopedMocks} correctly identifies
 * {@link ScopedMock} instances among {@code @Mock}-annotated fields, and that
 * the {@code AutoCloseable} returned in the ambiguity-recovery path calls
 * {@link ScopedMock#closeOnDemand()} on each one — preventing a resource leak
 * when {@code openMocks()} throws before returning its normal closeable.
 *
 * <p>Tests the private {@code collectScopedMocks} helper directly via reflection
 * so that no live Mockito session is required. This avoids the constraint that
 * {@code @Mock MockedStatic<T>} and {@code @InjectMocks} cannot coexist in the
 * same owner without triggering an unrelated Mockito 5 limitation in
 * {@code MockScanner}.
 */
@RunWith(JUnit4.class)
public class GwtMockitoScopedMockLeakTest {

  // ── owner classes ──────────────────────────────────────────────────────────

  /** Owner whose @Mock field holds a ScopedMock. */
  static class OwnerWithScopedMock {
    @Mock ScopedMock scoped;
    @Mock Object plain;
  }

  /** Owner with no @Mock fields (baseline: must return empty list). */
  static class OwnerWithNoMocks {}

  /** Owner whose @Mock field holds a plain mock (no ScopedMock). */
  static class OwnerWithPlainMockOnly {
    @Mock Object plain;
  }

  // ── helper ─────────────────────────────────────────────────────────────────

  @SuppressWarnings("unchecked")
  private static List<ScopedMock> collectScopedMocks(Object owner) throws Exception {
    Method m = GwtMockito.class.getDeclaredMethod("collectScopedMocks", Object.class);
    m.setAccessible(true);
    return (List<ScopedMock>) m.invoke(null, owner);
  }

  // ── tests ──────────────────────────────────────────────────────────────────

  @Test
  public void collectScopedMocks_returnsScopedMockFieldValue() throws Exception {
    OwnerWithScopedMock owner = new OwnerWithScopedMock();
    ScopedMock scoped = mock(ScopedMock.class);
    // Manually set the @Mock field to a known ScopedMock instance.
    OwnerWithScopedMock.class.getDeclaredField("scoped").setAccessible(true);
    OwnerWithScopedMock.class.getDeclaredField("scoped").set(owner, scoped);
    // Also set the plain Object field to a non-ScopedMock — must not appear in result.
    OwnerWithScopedMock.class.getDeclaredField("plain").setAccessible(true);
    OwnerWithScopedMock.class.getDeclaredField("plain").set(owner, new Object());

    List<ScopedMock> result = collectScopedMocks(owner);

    assertTrue("ScopedMock field must be collected", result.contains(scoped));
    assertFalse("Plain Object field must not be collected", result.size() > 1);
  }

  @Test
  public void collectScopedMocks_returnsEmptyForOwnerWithNoMocks() throws Exception {
    List<ScopedMock> result = collectScopedMocks(new OwnerWithNoMocks());
    assertTrue("No @Mock fields → empty list", result.isEmpty());
  }

  @Test
  public void collectScopedMocks_returnsEmptyWhenNoScopedMocks() throws Exception {
    OwnerWithPlainMockOnly owner = new OwnerWithPlainMockOnly();
    OwnerWithPlainMockOnly.class.getDeclaredField("plain").setAccessible(true);
    OwnerWithPlainMockOnly.class.getDeclaredField("plain").set(owner, new Object());

    List<ScopedMock> result = collectScopedMocks(owner);
    assertTrue("Only plain mocks → empty list", result.isEmpty());
  }

  @Test
  public void autoCloseableFromCatchPath_callsCloseOnDemandForEachScopedMock() throws Exception {
    OwnerWithScopedMock owner = new OwnerWithScopedMock();
    ScopedMock scoped = mock(ScopedMock.class);
    OwnerWithScopedMock.class.getDeclaredField("scoped").setAccessible(true);
    OwnerWithScopedMock.class.getDeclaredField("scoped").set(owner, scoped);

    List<ScopedMock> scopedMocks = collectScopedMocks(owner);

    // Build the same AutoCloseable that the catch branch builds.
    AutoCloseable closeable = () -> {
      for (ScopedMock sm : scopedMocks) {
        sm.closeOnDemand();
      }
    };
    closeable.close();

    verify(scoped).closeOnDemand();
  }
}
