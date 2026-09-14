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

import static org.mockito.Mockito.mock;

import com.google.gwt.core.client.GWT;
import com.google.gwt.core.client.GWTBridge;
import com.google.gwt.i18n.client.Messages;
import com.google.gwt.i18n.client.constants.NumberConstantsImpl;
import com.google.gwt.i18n.client.impl.LocaleInfoImpl;
import com.google.gwt.resources.client.ClientBundle;
import com.google.gwt.resources.client.CssResource;
import com.google.gwt.safehtml.client.SafeHtmlTemplates;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.user.client.rpc.RemoteService;
import com.google.gwtmockito.fakes.FakeClientBundleProvider;
import com.google.gwtmockito.fakes.FakeLocaleInfoImplProvider;
import com.google.gwtmockito.fakes.FakeMessagesProvider;
import com.google.gwtmockito.fakes.FakeNumberConstantsImplProvider;
import com.google.gwtmockito.fakes.FakeProvider;
import com.google.gwtmockito.fakes.FakeUiBinderProvider;
import com.google.gwtmockito.impl.ReturnsCustomMocks;

import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

/**
 * A library to make Mockito-based testing of GWT applications easier. Most
 * users won't have to reference this class directly and should instead use
 * {@link GwtMockitoTestRunner}. Users who cannot use that class (e.g. tests
 * using JUnit3) can invoke {@link #initMocks} directly in their setUp and
 * {@link #tearDown} in their tearDown methods.
 * <p>
 * Note that calling {@link #initMocks} and {@link #tearDown} directly does
 * <i>not</i> implement {@link GwtMockitoTestRunner}'s behavior of implementing
 * native methods and making final methods mockable. The only way to get this
 * behavior is by using {@link GwtMockitoTestRunner}.
 * <p>
 * Once {@link #initMocks} has been invoked, test code can safely call
 * GWT.create without exceptions. Doing so will return either a mock object
 * registered with {@link GwtMock}, a fake object specified by a call to
 * {@link #useProviderForType}, or a new mock instance if no other binding
 * exists. Fakes for types extending the following are provided by default:
 * <ul>
 *   <li> UiBinder: uses a fake that populates all UiFields with GWT.create'd
 *        widgets, allowing them to be mocked like other calls to GWT.create.
 *        See {@link FakeUiBinderProvider} for details.
 *   <li> ClientBundle: Uses a fake that will return fake CssResources as
 *        defined below, and will return fake versions of other resources that
 *        return unique strings for getText and getSafeUri. See
 *        {@link FakeClientBundleProvider} for details.
 *   <li> Messages, CssResource, and SafeHtmlTemplates: uses a fake that
 *        implements each method by returning a String of SafeHtml based on the
 *        name of the method and any arguments passed to it. The exact format is
 *        undefined. See {@link FakeMessagesProvider} for details.
 * </ul>
 * <p>
 * The type returned from GWT.create will generally be the same as the type
 * passed in. The exception is when GWT.create'ing a subclass of
 * {@link RemoteService} - in this case, the result of GWT.create will be the
 * Async version of that interface as defined by gwt-rpc.
 * <p>
 * If {@link #initMocks} is called manually, it is important to invoke
 * {@link #tearDown} once the test has been completed. Failure to do so can
 * cause state to leak between tests.
 *
 * @see GwtMockitoTestRunner
 * @see GwtMock
 * @author ekuefler@google.com (Erik Kuefler)
 */
public class GwtMockito {

  /**
   * Package prefixes of GWT base classes whose private fields may cause Mockito 5's ambiguity
   * error. Recovery (placeholder injection) is restricted to fields declared on classes within
   * these packages so that ambiguity errors from user-defined classes are still rethrown.
   */
  private static final java.util.Set<String> GWT_BASE_PACKAGES = new java.util.HashSet<>(
      java.util.Arrays.asList(
          "com.google.gwt.",
          "org.gwtproject."
      ));

  private static final Map<Class<?>, FakeProvider<?>> DEFAULT_FAKE_PROVIDERS =
      new HashMap<Class<?>, FakeProvider<?>>();
  static {
    DEFAULT_FAKE_PROVIDERS.put(ClientBundle.class, new FakeClientBundleProvider());
    DEFAULT_FAKE_PROVIDERS.put(CssResource.class, new FakeMessagesProvider<CssResource>());
    DEFAULT_FAKE_PROVIDERS.put(LocaleInfoImpl.class, new FakeLocaleInfoImplProvider());
    DEFAULT_FAKE_PROVIDERS.put(Messages.class, new FakeMessagesProvider<Messages>());
    DEFAULT_FAKE_PROVIDERS.put(NumberConstantsImpl.class, new FakeNumberConstantsImplProvider());
    DEFAULT_FAKE_PROVIDERS.put(
        SafeHtmlTemplates.class, new FakeMessagesProvider<SafeHtmlTemplates>());
    DEFAULT_FAKE_PROVIDERS.put(UiBinder.class, new FakeUiBinderProvider());
  }

  private static Bridge bridge;
  private static AutoCloseable openMocksCloseable;

  /**
   * Causes all calls to GWT.create to be intercepted to return a mock or fake
   * object, and populates any {@link GwtMock}-annotated fields with mockito
   * mocks. This method should be usually be called during the setUp method of a
   * test case. Note that it explicitly calls
   * {@link MockitoAnnotations#initMocks}, so there is no need to call that
   * method separately. See the class description for more details.
   *
   * @param owner class to scan for {@link GwtMock}-annotated fields - almost
   *              always "this" in unit tests
   */
  public static void initMocks(Object owner) {
    // If a session is already open (e.g. a runner-managed test that calls initMocks
    // again manually), close it now so the old AutoCloseable is not overwritten and
    // leaked. tearDown() is idempotent and also resets the bridge.
    if (openMocksCloseable != null) {
      tearDown();
    }

    // Create a new bridge and register built-in type providers
    bridge = new Bridge();
    for (Entry<Class<?>, FakeProvider<?>> entry : DEFAULT_FAKE_PROVIDERS.entrySet()) {
      useProviderForType(entry.getKey(), entry.getValue());
    }

    // Install the bridge and populate mock fields
    boolean success = false;
    try {
      setGwtBridge(bridge);
      registerGwtMocks(owner);
      openMocksCloseable = openMocksWithObjectFieldFix(owner);
      success = true;
    } finally {
      if (!success) {
        tearDown();
      }
    }
  }

  /**
   * Calls {@link MockitoAnnotations#openMocks(Object)} and, if it fails because
   * Mockito 5's {@code TypeBasedCandidateFilter} throws {@code moreThanOneMockCandidate}
   * for a private field inherited from a GWT base class (e.g. {@code Widget.layoutData},
   * {@code Composite.widget}), completes the injection manually and returns a
   * proper {@code AutoCloseable} via a second session limited to {@code @Mock}
   * creation only (no {@code @InjectMocks} processing).
   *
   * <p>Background: Mockito 3 silently skipped fields with multiple type-compatible
   * candidates. Mockito 5 throws instead. GWT base classes ({@code Widget},
   * {@code Composite}) carry private fields typed {@code Object} or {@code Widget}
   * that match every test mock. This fix restores the Mockito 3 behavior: inject
   * uniquely-named mocks where possible, and fill ambiguous GWT-internal fields
   * with a fresh placeholder mock.
   *
   * <p>The fix is applied only when the exception message contains
   * {@code "there were multiple matching mocks"}, so all other
   * {@code MockitoException}s are rethrown unchanged.
   */
  private static AutoCloseable openMocksWithObjectFieldFix(Object owner) {
    try {
      AutoCloseable closeable = MockitoAnnotations.openMocks(owner);
      // Mockito 5 short-circuits after constructor injection and never runs
      // property/setter injection. Fill any still-null fields on @InjectMocks
      // targets from the owner's mocks (same logic used in the catch branch).
      // Skip the scan entirely when the owner has no @InjectMocks field — the
      // common case for tests that only use @Mock — to avoid unnecessary
      // reflective hierarchy walks on every test.
      if (hasInjectMocksField(owner)) {
        injectIntoAllTargets(owner, collectOwnerMocks(owner));
      }
      return closeable;
    } catch (org.mockito.exceptions.base.MockitoException firstException) {
      if (!firstException.getMessage().contains("there were multiple matching mocks")) {
        throw firstException;
      }
      // Additional guard: only recover when at least one @InjectMocks target actually extends
      // a GWT base class. If the ambiguity comes from a plain user class (no GWT base class
      // in the hierarchy) the exception is a real misconfiguration — rethrow it.
      if (!hasInjectMocksTargetExtendingGwtBase(owner)) {
        throw firstException;
      }

      // At this point:
      // - All @Mock fields on the owner ARE set (IndependentAnnotationEngine ran first).
      // - @InjectMocks targets were created and assigned (FieldInitializer ran before
      //   PropertyAndSetterInjection threw).
      // - Only the property-injection step failed due to ambiguous inherited GWT fields.
      //
      // Step 1: collect the owner's mocks (already created by the failed openMocks call).
      java.util.Map<String, Object> ownerMocks = collectOwnerMocks(owner);

      // Step 2: for each @InjectMocks target, perform injection:
      //   - unique-by-type-and-name  →  inject the matching owner mock
      //   - ambiguous (multiple type-compatible mocks, GWT internal field)  →  fresh placeholder
      injectIntoAllTargets(owner, ownerMocks);

      // Step 3: IndependentAnnotationEngine already set every @Mock/@GwtMock field on the owner
      // before the injection step threw. Any @MockedStatic or @MockedConstruction instances
      // (ScopedMock) are now assigned to those fields. The AutoCloseable that would normally
      // track them was never returned from the failed openMocks() call, so we build a
      // replacement by scanning the owner's fields for ScopedMock instances and closing them
      // via closeOnDemand(), exactly as IndependentAnnotationEngine's own lambda would do.
      java.util.List<org.mockito.ScopedMock> scopedMocks = collectScopedMocks(owner);
      return () -> {
        for (org.mockito.ScopedMock sm : scopedMocks) {
          sm.closeOnDemand();
        }
      };
    }
  }

  /**
   * Collects all {@link org.mockito.ScopedMock} instances (i.e. {@code @MockedStatic} /
   * {@code @MockedConstruction} fields) already set on the owner's {@code @Mock}-annotated
   * fields. Used to build a replacement {@code AutoCloseable} when the normal one was lost
   * because {@code openMocks()} threw before returning.
   */
  private static java.util.List<org.mockito.ScopedMock> collectScopedMocks(Object owner) {
    java.util.List<org.mockito.ScopedMock> result = new java.util.ArrayList<>();
    Class<?> clazz = owner.getClass();
    while (clazz != null && clazz != Object.class) {
      for (Field f : clazz.getDeclaredFields()) {
        if (hasAnnotation(f, "org.mockito.Mock") || hasAnnotation(f, "com.google.gwtmockito.GwtMock")) {
          f.setAccessible(true);
          try {
            Object val = f.get(owner);
            if (val instanceof org.mockito.ScopedMock) {
              result.add((org.mockito.ScopedMock) val);
            }
          } catch (IllegalAccessException ignored) {}
        }
      }
      clazz = clazz.getSuperclass();
    }
    return result;
  }

  /**
   * Collects all mock objects from {@code @Mock}- and {@code @GwtMock}-annotated
   * fields in the owner's class hierarchy, keyed by field name.
   */
  private static java.util.Map<String, Object> collectOwnerMocks(Object owner) {
    java.util.Map<String, Object> mocks = new java.util.LinkedHashMap<>();
    Class<?> clazz = owner.getClass();
    while (clazz != null && clazz != Object.class) {
      for (Field f : clazz.getDeclaredFields()) {
        if (hasAnnotation(f, "org.mockito.Mock") || hasAnnotation(f, "com.google.gwtmockito.GwtMock")) {
          f.setAccessible(true);
          try {
            Object mock = f.get(owner);
            if (mock != null) {
              mocks.putIfAbsent(f.getName(), mock); // subclass fields take priority over superclass
            }
          } catch (IllegalAccessException ignored) {}
        }
      }
      clazz = clazz.getSuperclass();
    }
    return mocks;
  }

  /**
   * Scans all {@code @InjectMocks}-annotated fields on the owner's class hierarchy.
   * For each target that already exists, injects owner mocks into the target's null
   * fields using name-then-type disambiguation, and fills any still-ambiguous fields
   * (typically private GWT base-class fields) with a fresh placeholder mock.
   */
  private static void injectIntoAllTargets(Object owner,
      java.util.Map<String, Object> ownerMocks) {
    Class<?> clazz = owner.getClass();
    while (clazz != null && clazz != Object.class) {
      for (Field f : clazz.getDeclaredFields()) {
        if (hasAnnotation(f, "org.mockito.InjectMocks")) {
          f.setAccessible(true);
          Object target;
          try {
            target = f.get(owner);
          } catch (IllegalAccessException ex) {
            continue;
          }
          if (target == null) continue;
          injectMocksIntoTarget(target, ownerMocks);
        }
      }
      clazz = clazz.getSuperclass();
    }
  }

  /**
   * Injects {@code ownerMocks} into null instance fields of {@code target}'s full
   * class hierarchy using the same priority as Mockito's own injector:
   * <ol>
   *   <li>Name match beats type match: if a mock named identically to the field
   *       exists and is type-compatible, use it.</li>
   *   <li>Unique type match: if exactly one mock is type-compatible, use it.</li>
   *   <li>Ambiguous (multiple type-compatible, no name match): fill with a fresh
   *       placeholder mock so the target is never left with a null GWT-internal
   *       field that would cause a {@code NullPointerException} at test time.</li>
   * </ol>
   * Fields that already have a non-null value are left untouched.
   */
  private static void injectMocksIntoTarget(Object target,
      java.util.Map<String, Object> ownerMocks) {
    Class<?> clazz = target.getClass();
    while (clazz != null && clazz != Object.class) {
      for (Field f : clazz.getDeclaredFields()) {
        if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
        f.setAccessible(true);
        try {
          if (f.get(target) != null) continue; // already set — leave untouched

          // When the field is declared with a generic type parameter (e.g. "protected P
          // presenter"), f.getType() returns the erasure Object.class, which matches every
          // mock and causes incorrect ambiguous-placeholder injection. Resolve the type
          // variable to its concrete bound in this target's class hierarchy first.
          Class<?> effectiveType = f.getType();
          java.lang.reflect.Type genericType = f.getGenericType();
          if (genericType instanceof java.lang.reflect.TypeVariable) {
            Class<?> resolved = resolveTypeVariable(
                (java.lang.reflect.TypeVariable<?>) genericType, target.getClass());
            if (resolved != null) {
              effectiveType = resolved;
            }
          }

          // Priority 1: name+type match (mirrors Mockito's NameBasedCandidateFilter).
          Object byName = ownerMocks.get(f.getName());
          if (byName != null && effectiveType.isInstance(byName)) {
            f.set(target, byName);
            continue;
          }

          // Priority 2: unique type match (mirrors TypeBasedCandidateFilter).
          java.util.List<Object> compatible = new java.util.ArrayList<>();
          for (Object mock : ownerMocks.values()) {
            if (effectiveType.isInstance(mock)) {
              compatible.add(mock);
            }
          }
          if (compatible.size() == 1) {
            f.set(target, compatible.get(0));
          } else if (compatible.size() > 1) {
            // Ambiguous: no owner mock resolves uniquely.
            // Only fill with a placeholder if this field is declared on a GWT base class
            // (e.g. Composite.widget, Widget.layoutData). For fields on user-defined classes,
            // leave null so the ambiguity surfaces rather than being silently masked.
            if (isGwtBaseClass(clazz)) {
              f.set(target, Mockito.mock(effectiveType));
            }
          }
          // compatible.size() == 0 → no mock applies; leave null.
        } catch (IllegalAccessException | org.mockito.exceptions.base.MockitoException ignored) {}
      }
      clazz = clazz.getSuperclass();
    }
  }

  /**
   * Resolves a {@link java.lang.reflect.TypeVariable} to its concrete {@link Class} by walking
   * the generic superclass chain of {@code concreteClass}.
   *
   * <p>Example: given {@code protected P presenter} declared in
   * {@code AbstractWorkbenchPanelView<P>} and a concrete class
   * {@code MultiListWorkbenchPanelView extends AbstractMultiPartWorkbenchPanelView<MultiListWorkbenchPanelPresenter>},
   * this method returns {@code MultiListWorkbenchPanelPresenter.class}.
   *
   * @param tv            the type variable to resolve
   * @param concreteClass the runtime class of the injection target
   * @return the resolved {@link Class}, or {@code null} if resolution is not possible
   */
  private static Class<?> resolveTypeVariable(
      java.lang.reflect.TypeVariable<?> tv, Class<?> concreteClass) {
    // The generic declaration is the class/interface that introduced this type parameter.
    // We only handle class-level type variables (not method-level ones).
    if (!(tv.getGenericDeclaration() instanceof Class)) {
      return null;
    }
    Class<?> declaringClass = (Class<?>) tv.getGenericDeclaration();

    // Walk up the superclass chain from concreteClass until we find a ParameterizedType
    // whose raw type is the class immediately below declaringClass in the hierarchy.
    // At that point the actual type arguments tell us what declaringClass's parameters
    // are bound to.
    Class<?> child = concreteClass;
    while (child != null && child != Object.class) {
      java.lang.reflect.Type genericSuper = child.getGenericSuperclass();
      if (!(genericSuper instanceof java.lang.reflect.ParameterizedType)) {
        child = child.getSuperclass();
        continue;
      }
      java.lang.reflect.ParameterizedType pt = (java.lang.reflect.ParameterizedType) genericSuper;
      Class<?> rawSuper = (Class<?>) pt.getRawType();

      if (rawSuper.equals(declaringClass)) {
        // Found the parameterized supertype that directly binds declaringClass's parameters.
        java.lang.reflect.TypeVariable<?>[] params = declaringClass.getTypeParameters();
        java.lang.reflect.Type[] args = pt.getActualTypeArguments();
        for (int i = 0; i < params.length; i++) {
          if (params[i].equals(tv)) { // object equality: compares both name and declaring class
            if (args[i] instanceof Class) {
              return (Class<?>) args[i];
            }
            // The slot is itself a TypeVariable — recurse with the child's context.
            if (args[i] instanceof java.lang.reflect.TypeVariable) {
              return resolveTypeVariable(
                  (java.lang.reflect.TypeVariable<?>) args[i], child);
            }
            return null; // wildcard or parameterized type — not injectable
          }
        }
        return null;
      }
      child = child.getSuperclass();
    }
    return null;
  }

  /**
   * Resets GWT.create to its default behavior. This method should be called
   * after any test that called initMocks completes, usually in your test's
   * tearDown method. Failure to do so can introduce unexpected ordering
   * dependencies in tests.
   */
  public static void tearDown() {
    setGwtBridge(null);
    if (openMocksCloseable != null) {
      try {
        openMocksCloseable.close();
      } catch (Exception e) {
        throw new RuntimeException("Failed to close Mockito mocks", e);
      } finally {
        openMocksCloseable = null;
      }
    }
  }

  /**
   * Specifies that the given provider should be used to GWT.create instances of
   * the given type and its subclasses. If multiple providers could produce a
   * given class (for example, if a provide is registered for a type and its
   * supertype), the provider for the more specific type is chosen. An exception
   * is thrown if this type is ambiguous. Note that if you just want to return a
   * Mockito mock from GWT.create, it's probably easier to use {@link GwtMock}
   * instead.
   */
  public static void useProviderForType(Class<?> type, FakeProvider<?> provider) {
    if (bridge == null) {
      throw new IllegalStateException("Must call initMocks() before calling useProviderForType()");
    }
    if (bridge.registeredMocks.containsKey(type)) {
      throw new IllegalArgumentException(
          "Can't use a provider for a type that already has a @GwtMock declared");
    }
    bridge.registeredProviders.put(type, provider);
  }

  /**
   * Returns a new fake object of the given type assuming a fake provider is
   * available for that type. Additional fake providers can be registered via
   * {@link #useProviderForType}.
   *
   * @param type type to get a fake object for
   * @return a fake of the given type, as returned by an applicable provider
   * @throws IllegalArgumentException if no provider for the given type (or one
   *                                  of its superclasses) has been registered
   */
  public static <T> T getFake(Class<T> type) {
    // If initMocks hasn't been called, read from the default fake provider map. This allows static
    // fields to be initialized with fakes in tests that don't use the GwtMockito test runner.
    T fake = getFakeFromProviderMap(
        type,
        bridge != null ? bridge.registeredProviders : DEFAULT_FAKE_PROVIDERS);
    if (fake == null) {
      throw new IllegalArgumentException("No fake provider has been registered "
          + "for " + type.getSimpleName() + ". Call useProviderForType to "
          + "register a provider before calling getFake.");
    }
    return fake;
  }

  /** Returns true when {@code clazz} is a GWT framework class (not user code). */
  private static boolean isGwtBaseClass(Class<?> clazz) {
    String name = clazz.getName();
    for (String prefix : GWT_BASE_PACKAGES) {
      if (name.startsWith(prefix)) return true;
    }
    return false;
  }

  /**
   * Returns true when at least one {@code @InjectMocks} field on {@code owner}'s class hierarchy
   * has a target type that extends a GWT base class. Used to distinguish GWT-specific ambiguity
   * (recoverable) from user-code ambiguity (should rethrow).
   */
  private static boolean hasInjectMocksTargetExtendingGwtBase(Object owner) {
    Class<?> clazz = owner.getClass();
    while (clazz != null && clazz != Object.class) {
      for (Field f : clazz.getDeclaredFields()) {
        if (hasAnnotation(f, "org.mockito.InjectMocks")) {
          Class<?> targetType = f.getType();
          while (targetType != null && targetType != Object.class) {
            if (isGwtBaseClass(targetType)) return true;
            targetType = targetType.getSuperclass();
          }
        }
      }
      clazz = clazz.getSuperclass();
    }
    return false;
  }

  private static boolean hasAnnotation(Field field, String annotationClassName) {
    for (Annotation a : field.getAnnotations()) {
      if (a.annotationType().getName().equals(annotationClassName)) {
        return true;
      }
    }
    return false;
  }

  /** Returns true when any field in {@code owner}'s class hierarchy carries {@code @InjectMocks}. */
  private static boolean hasInjectMocksField(Object owner) {
    Class<?> clazz = owner.getClass();
    while (clazz != null && clazz != Object.class) {
      for (Field f : clazz.getDeclaredFields()) {
        if (hasAnnotation(f, "org.mockito.InjectMocks")) return true;
      }
      clazz = clazz.getSuperclass();
    }
    return false;
  }

  private static void registerGwtMocks(Object owner) {
    Class<? extends Object> clazz = owner.getClass();

    while (!"java.lang.Object".equals(clazz.getName())) {
      for (Field field : clazz.getDeclaredFields()) {
        if (field.isAnnotationPresent(GwtMock.class)) {
          Object mock = Mockito.mock(field.getType());
          if (bridge.registeredMocks.containsKey(field.getType())) {
            throw new IllegalArgumentException("Owner declares multiple @GwtMocks for type "
                + field.getType().getSimpleName() + "; only one is allowed. Did you mean to "
                + "use a standard @Mock?");
          }
          bridge.registeredMocks.put(field.getType(), mock);
          field.setAccessible(true);
          try {
            field.set(owner, mock);
          } catch (IllegalAccessException e) {
            throw new IllegalStateException("Failed to make field accessible: " + field);
          }
        }
      }

      clazz = clazz.getSuperclass();
    }
  }

  private static void setGwtBridge(GWTBridge bridge) {
    try {
      Method setBridge = GWT.class.getDeclaredMethod("setBridge", GWTBridge.class);
      setBridge.setAccessible(true);
      setBridge.invoke(null, bridge);
    } catch (SecurityException e) {
      throw new RuntimeException(e);
    } catch (InvocationTargetException e) {
      throw new RuntimeException(e.getCause());
    } catch (IllegalAccessException e) {
      throw new AssertionError("Impossible since setBridge was made accessible");
    } catch (NoSuchMethodException e) {
      throw new AssertionError("Impossible since setBridge is known to exist");
    }
  }

  private static <T> T getFakeFromProviderMap(Class<T> type, Map<Class<?>, FakeProvider<?>> map) {
      // See if we have any providers for this type or its supertypes.
      Map<Class<?>, FakeProvider<?>> legalProviders = new HashMap<Class<?>, FakeProvider<?>>();
      for (Entry<Class<?>, FakeProvider<?>> entry : map.entrySet()) {
        if (entry.getKey().isAssignableFrom(type)) {
          legalProviders.put(entry.getKey(), entry.getValue());
        }
      }

      // Filter the set of legal providers to the most specific type.
      Map<Class<?>, FakeProvider<?>> filteredProviders = new HashMap<Class<?>, FakeProvider<?>>();
      for (Entry<Class<?>, FakeProvider<?>> candidate : legalProviders.entrySet()) {
        boolean isSpecific = true;
        for (Entry<Class<?>, FakeProvider<?>> other : legalProviders.entrySet()) {
          if (candidate != other && candidate.getKey().isAssignableFrom(other.getKey())) {
            isSpecific = false;
            break;
          }
        }
        if (isSpecific) {
          filteredProviders.put(candidate.getKey(), candidate.getValue());
        }
      }

      // If exactly one provider remains, use it.
      if (filteredProviders.size() == 1) {
        // We know this is safe since we checked that the types are assignable
        @SuppressWarnings({"rawtypes", "cast"})
        Class rawType = (Class) type;
        return (T) filteredProviders.values().iterator().next().getFake(rawType);
      } else if (filteredProviders.isEmpty()) {
        return null;
      } else {
        throw new IllegalArgumentException("Can't decide which provider to use for " +
            type.getSimpleName() +
            ", it could be provided as any of the following: " +
            mapToSimpleNames(filteredProviders.keySet()) +
            ". Add a provider for " +
            type.getSimpleName() +
            " to resolve this ambiguity.");
      }
  }

    private static Set<String> mapToSimpleNames(Set<Class<?>> classes) {
      Set<String> simpleNames = new HashSet<String>();
      for (Class<?> clazz : classes) {
        simpleNames.add(clazz.getSimpleName());
      }
      return simpleNames;
    }

  private static class Bridge extends GWTBridge {
    private final Map<Class<?>, FakeProvider<?>> registeredProviders =
        new HashMap<Class<?>, FakeProvider<?>>();
    private final Map<Class<?>, Object> registeredMocks = new HashMap<Class<?>, Object>();

    @Override
    @SuppressWarnings("unchecked") // safe since we check whether the type is assignable
    public <T> T create(Class<?> createdType) {
      // If we're creating a RemoteService, assume that the result of GWT.create is being assigned
      // to the async version of that service. Otherwise, assume it's being assigned to the same
      // type we're creating.
      Class<?> assignedType = RemoteService.class.isAssignableFrom(createdType)
          ? getAsyncType((Class<? extends RemoteService>) createdType)
          : createdType;

      // First check if we have a GwtMock for this exact being assigned to and use it if so.
      if (registeredMocks.containsKey(assignedType)) {
        return (T) registeredMocks.get(assignedType);
      }

      // Next check if we have a fake provider that can provide a fake for the type being created.
      T fake = (T) getFakeFromProviderMap(createdType, registeredProviders);
      if (fake != null) {
        return fake;
      }

      // If nothing has been registered, just return a new mock for the type being assigned.
      return (T) mock(assignedType, new ReturnsCustomMocks());
    }

    @Override
    public String getVersion() {
      return getClass().getName();
    }

    @Override
    public boolean isClient() {
      return false;
    }

    @Override
    public void log(String message, Throwable e) {
      System.err.println(message + "\n");
      if (e != null) {
        e.printStackTrace();
      }
    }

    /** Returns the corresponding async service type for the given remote service type. */
    private Class<?> getAsyncType(Class<? extends RemoteService> type) {
      Class<?> asyncType;
      try {
        asyncType = Class.forName(type.getCanonicalName() + "Async");
      } catch (ClassNotFoundException e) {
        throw new IllegalArgumentException(
            type.getCanonicalName() + " does not have a corresponding async interface", e);
      }
      return asyncType;
    }

  }
}
