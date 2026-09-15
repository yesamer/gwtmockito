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
import static org.junit.Assert.assertTrue;

import com.google.gwtmockito.impl.StubGenerator;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/**
 * Unit tests for {@link StubGenerator} covering the lines not exercised by the
 * integration tests.
 *
 * <ul>
 *   <li>{@code ClassAndMethod.equals()} — false branch when the argument is not a
 *       {@code ClassAndMethod} instance (e.g. {@code null} or a {@code String}).</li>
 *   <li>{@code ClassAndMethod.equals()} — true branch: same class+method.</li>
 *   <li>{@code ClassAndMethod.hashCode()} — verified consistent with equals.</li>
 * </ul>
 *
 * <p>{@code ClassAndMethod} is package-private, so it is accessed via reflection.
 */
@RunWith(JUnit4.class)
public class StubGeneratorTest {

  // ── reflection helpers ──────────────────────────────────────────────────

  /** Loads the inner ClassAndMethod class from StubGenerator. */
  private static Class<?> classAndMethodClass() throws ClassNotFoundException {
    return Class.forName("com.google.gwtmockito.impl.StubGenerator$ClassAndMethod");
  }

  /** Instantiates a ClassAndMethod via the (String, String) constructor. */
  private static Object newClassAndMethod(String className, String methodName) throws Exception {
    Class<?> clazz = classAndMethodClass();
    Constructor<?> ctor = clazz.getDeclaredConstructor(String.class, String.class);
    ctor.setAccessible(true);
    return ctor.newInstance(className, methodName);
  }

  private static boolean invokeEquals(Object cam, Object other) throws Exception {
    Method eq = cam.getClass().getDeclaredMethod("equals", Object.class);
    eq.setAccessible(true);
    return (Boolean) eq.invoke(cam, other);
  }

  private static int invokeHashCode(Object cam) throws Exception {
    Method hc = cam.getClass().getDeclaredMethod("hashCode");
    hc.setAccessible(true);
    return (Integer) hc.invoke(cam);
  }

  // ── tests ───────────────────────────────────────────────────────────────

  @Test
  public void classAndMethodEquals_falseForNull() throws Exception {
    Object cam = newClassAndMethod("com.example.Foo", "bar");
    assertFalse("equals(null) must return false", invokeEquals(cam, null));
  }

  @Test
  public void classAndMethodEquals_falseForDifferentType() throws Exception {
    Object cam = newClassAndMethod("com.example.Foo", "bar");
    assertFalse("equals(String) must return false", invokeEquals(cam, "com.example.Foo#bar"));
  }

  @Test
  public void classAndMethodEquals_trueForSameClassAndMethod() throws Exception {
    Object cam1 = newClassAndMethod("com.example.Foo", "bar");
    Object cam2 = newClassAndMethod("com.example.Foo", "bar");
    assertTrue("two identical ClassAndMethod instances must be equal", invokeEquals(cam1, cam2));
  }

  @Test
  public void classAndMethodEquals_falseForDifferentMethod() throws Exception {
    Object cam1 = newClassAndMethod("com.example.Foo", "bar");
    Object cam2 = newClassAndMethod("com.example.Foo", "baz");
    assertFalse("different method names must not be equal", invokeEquals(cam1, cam2));
  }

  @Test
  public void classAndMethodHashCode_consistentWithEquals() throws Exception {
    Object cam1 = newClassAndMethod("com.example.Foo", "bar");
    Object cam2 = newClassAndMethod("com.example.Foo", "bar");
    assertTrue("equal objects must have the same hashCode",
        invokeHashCode(cam1) == invokeHashCode(cam2));
  }

  @Test
  public void shouldStub_returnsFalseForNonStubClass() throws Exception {
    // invoke() for an unknown returnType falls through to Mockito.mock — ensure
    // it returns a non-null value without throwing.
    Object result = StubGenerator.invoke(Runnable.class, "some.Unknown", "unknownMethod");
    assertTrue("invoke on unknown method must return a Runnable mock",
        result instanceof Runnable);
  }
}
