## What is GwtMockito?

Testing GWT applications using `GWTTestCase` can be a pain - it's slower than
using pure Java tests, and you can't use reflection-based tools like mocking
frameworks. But if you've tried to test widgets using normal test cases, you've
probably run into this error:

    ERROR: GWT.create() is only usable in client code!  It cannot be called,
    for example, from server code. If you are running a unit test, check that 
    your test case extends GWTTestCase and that GWT.create() is not called
    from within an initializer or constructor.

GwtMockito solves this and other GWT-related testing problems by allowing you
to call GWT.create from JUnit tests, returning [Mockito][1] mocks.

> **This is the YCM fork** of the original [google/gwtmockito][orig], which is
> no longer maintained. This fork modernizes the project with:
> - **Mockito 5.23.0** compatibility (replaces 1.x)
> - **GWT 2.13.1** (`org.gwtproject`) — replaces the archived `com.google.gwt` 2.8.0
> - **Java 17** source/target
> - Full Mockito session lifecycle management (`openMocks` / `tearDown`)
> - Automatic recovery from Mockito 5's `@InjectMocks` ambiguity errors on GWT base classes

## How do I install it?

Build from source and install to your local Maven repository:

```bash
git clone https://github.com/yesamer/gwtmockito.git
cd gwtmockito
mvn install -DskipTests
```

Then add the dependency to your project:

```xml
<dependency>
  <groupId>com.google.gwt.gwtmockito</groupId>
  <artifactId>gwtmockito</artifactId>
  <version>2.0.0-SNAPSHOT</version>
  <scope>test</scope>
</dependency>
```

## How do I use it?

Getting started with GwtMockito using JUnit 4.5+ is easy. Just annotate your test
with `@RunWith(GwtMockitoTestRunner.class)`, then any calls to `GWT.create`
encountered will return Mockito mocks instead of throwing exceptions:

```java
@RunWith(GwtMockitoTestRunner.class)
public class MyTest {
  @Test
  public void shouldReturnMocksFromGwtCreate() {
    Label myLabel = GWT.create(Label.class);
    when(myLabel.getText()).thenReturn("some text");
    assertEquals("some text", myLabel.getText());
  }
}
```

GwtMockito also creates fake implementations of all UiBinders that automatically
populate `@UiField`s with Mockito mocks. Suppose you have a widget that looks
like this:

```java
public class MyWidget extends Composite {
  interface MyUiBinder extends UiBinder<Widget, MyWidget> {}
  private final MyUiBinder uiBinder = GWT.create(MyUiBinder.class);

  @UiField Label numberLabel;
  private final NumberFormatter formatter;

  public MyWidget(NumberFormatter formatter) {
    this.formatter = formatter;
    initWidget(uiBinder.createAndBindUi(this));
  }

  void setNumber(int number) {
    numberLabel.setText(formatter.format(number));
  }
}
```

When `createAndBindUi` is called, GwtMockito will automatically populate 
`numberLabel` with a mock object. Since `@UiField`s are package-visible, they 
can be read from your unit tests, which lets you test this widget as follows:

```java
@RunWith(GwtMockitoTestRunner.class)
public class MyWidgetTest {

  @Mock NumberFormatter formatter;
  private MyWidget widget;

  @Before
  public void setUp() {
    widget = new MyWidget(formatter);
  }

  @Test
  public void shouldFormatNumber() {
    when(formatter.format(5)).thenReturn("5.00");
    widget.setNumber(5);
    verify(widget.numberLabel).setText("5.00");
  }
}
```

Note that GwtMockito supports the `@Mock` annotation from Mockito, allowing 
standard Mockito mocks to be mixed with mocks created by GwtMockito.

### Accessing the mock returned from GWT.create

You can reference a mock returned by `GWT.create` by annotating a field with
`@GwtMock`. For example:

```java
@RunWith(GwtMockitoTestRunner.class)
public class MyClassTest {
  @GwtMock SomeInterface mockInterface;

  @Test
  public void constructorShouldSetSomething() {
    new MyClass();
    verify(mockInterface).setSomething(true);
  }
}
```

### Returning fake objects

By default, GwtMockito will return fake implementations for any classes extending:

  * UiBinder
  * ClientBundle
  * Messages
  * CssResource
  * SafeHtmlTemplates

You can add fakes for additional types by invoking 
`GwtMockito.useProviderForType(Class, FakeProvider)` in your `setUp` method.

### Mocking final classes and methods

GwtMockito removes all final modifiers from classes and interfaces via its
Javassist classloader, allowing `Element` and other JavaScript overlay types
to be mocked:

```java
@RunWith(GwtMockitoTestRunner.class)
public class MyTest {
  @Mock Element element;

  @Test
  public void shouldMockFinalMethod() {
    when(element.getClassName()).thenReturn("mockClass");
    assertEquals("mockClass", element.getClassName());
  }
}
```

### Dealing with native methods

GwtMockito provides no-op implementations for all native JSNI methods:

  * `void` methods do nothing.
  * Methods returning primitive types return the default value (0, false, etc.)
  * Methods returning `String`s return the empty string.
  * Methods returning other objects return a mock configured with `RETURNS_MOCKS`.

### Support for JUnit 3 and other tests that can't use custom runners

You can use GwtMockito without `GwtMockitoTestRunner` by calling
`GwtMockito.initMocks` and `GwtMockito.tearDown` directly:

```java
public class MyWidgetTest extends TestCase {

  @Override
  public void setUp() {
    super.setUp();
    GwtMockito.initMocks(this);
  }

  @Override
  public void tearDown() {
    super.tearDown();
    GwtMockito.tearDown();
  }

  public void testSomething() {
    // test code
  }
}
```

Note that when testing in this way, mocking final classes/methods and automatic
native method stubs are not available.

## Version history

### 2.0.0 (YCM fork)
  * Upgraded to Mockito 5.23.0.
  * Upgraded to GWT 2.13.1 (`org.gwtproject`).
  * Upgraded to Java 17.
  * `tearDown()` now properly closes the Mockito session opened by `openMocks()`.
  * `GwtMockitoTestRunner` now calls `tearDown()` automatically after each test.
  * Fixed `@InjectMocks` on targets extending GWT base classes (`Composite`, `Widget`)
    that triggered Mockito 5's ambiguity error on inherited private fields.
  * Fixed subclass `@Mock` field priority over same-named superclass fields.
  * Fixed `TypeVariable` field injection in generic base view classes.
  * Replaced deprecated `org.mockito.Matchers` with `org.mockito.ArgumentMatchers`.
  * Dropped PowerMock test dependency (incompatible with Mockito 5).
  * Applied Java 17 API throughout: `instanceof` pattern matching, `Set.of`/`Map.of`
    factory methods, arrow-case `switch`, Stream API — no behaviour changes.
  * Eliminated `LinkedList` in favour of `ArrayList`; deduplicated `@Mock`/`@GwtMock`
    field detection into a shared `isMockField()` helper.
  * Fixed `field.getAnnotations()` → `getDeclaredAnnotations()` and hierarchy-walk
    termination to use identity check (`clazz != Object.class`).
  * Cached `getClassesToStub()` per class load in the Javassist translator to avoid
    repeated allocations during method stubbing.
  * Added unit tests covering `tearDown` idempotency, `isGwtBaseClass`,
    `hasInjectMocksField`, `hasInjectMocksTargetExtendingGwtBase`, and `isMockField`.

### 1.1.9
  * Support ResourcePrototype methods in fake ClientBundles. (Thanks to zbynek)
  * Add a `@WithExperimentalGarbageCollection` annotation. (Thanks to LudoP)
  * Updated Javassist dependency. (Thanks to TimvdLippe)

### 1.1.8
  * Preliminary Java 9 support. (Thanks to benoitf)

### 1.1.7
  * Update GWT to version 2.8.0.
  * Update Javassist to version 3.22.
  * Stubbing for ValueListBox. (Thanks to jschmied)
  * Stubbing for URL encoding. (Thanks to jschmied)
  * Generate hashCode and equals for Messages. (Thanks to zolv)

### 1.1.6 and earlier
  * See the [original project changelog][orig].

[1]: https://site.mockito.org/
[orig]: https://github.com/google/gwtmockito
