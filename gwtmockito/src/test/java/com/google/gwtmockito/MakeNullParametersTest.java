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

import com.google.gwtmockito.MakeNullParametersTest.PrimitivesRunner;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.model.InitializationError;

import java.util.Collection;

/**
 * Exercises the {@code makeNullParameters} switch in
 * {@link GwtMockitoTestRunner.GwtMockitoClassLoader} for every primitive type
 * ({@code byte}, {@code char}, {@code double}, {@code float}, {@code long},
 * {@code short}) that was previously uncovered.
 *
 * <p>The constructor-stubbing path in
 * {@link GwtMockitoTestRunner.GwtMockitoClassLoader#onLoad} rewrites every
 * constructor of a class in {@code getClassesToStub()} to call
 * {@code super(makeNullParameters(superConstructorParamTypes))}. To exercise
 * each primitive branch we need a stub target whose superclass constructor
 * takes parameters of those types.
 *
 * <p>{@link AllPrimitivesBase} declares a constructor that takes all six
 * remaining primitives in one shot. {@link AllPrimitivesSubclass} extends it
 * and is registered via a custom runner ({@link PrimitivesRunner}) so that the
 * Javassist translator stubs its constructor — triggering every primitive case.
 */
@RunWith(PrimitivesRunner.class)
public class MakeNullParametersTest {

  /**
   * Base class whose constructor covers all primitive types not previously exercised:
   * {@code boolean}, {@code byte}, {@code char}, {@code double}, {@code float},
   * {@code long}, {@code short}.
   */
  public static class AllPrimitivesBase {
    public AllPrimitivesBase(boolean z, byte b, char c, double d, float f, long l, short s) {}
  }

  /** Stub target: its constructor will be rewritten to {@code super(false,(byte)0,...)}. */
  public static class AllPrimitivesSubclass extends AllPrimitivesBase {
    public AllPrimitivesSubclass() {
      super(false, (byte) 0, (char) 0, 0.0, 0.0f, 0L, (short) 0);
    }
  }

  /** Custom runner that adds {@link AllPrimitivesSubclass} to the stub list. */
  public static class PrimitivesRunner extends GwtMockitoTestRunner {
    public PrimitivesRunner(Class<?> unitTestClass) throws InitializationError {
      super(unitTestClass);
    }

    @Override
    protected Collection<Class<?>> getClassesToStub() {
      Collection<Class<?>> classes = super.getClassesToStub();
      classes.add(AllPrimitivesSubclass.class);
      return classes;
    }
  }

  /**
   * Instantiating {@link AllPrimitivesSubclass} forces the Javassist translator to rewrite
   * its constructor body using {@code makeNullParameters} for all six primitive types.
   * The test passes if no exception is thrown and the instance is non-null.
   */
  @Test
  public void makeNullParameters_coversAllPrimitiveTypes() {
    AllPrimitivesSubclass instance = new AllPrimitivesSubclass();
    assertNotNull("AllPrimitivesSubclass must be instantiable after constructor stubbing", instance);
  }
}
