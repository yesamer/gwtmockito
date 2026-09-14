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

import com.google.gwt.user.client.ui.Composite;
import com.google.gwt.user.client.ui.Label;
import com.google.gwt.user.client.ui.TextBox;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;

import java.lang.reflect.Field;

/**
 * Verifies that GwtMockito handles {@code @InjectMocks} targets that extend GWT base classes
 * (e.g. {@link Composite}) without throwing {@code moreThanOneMockCandidate} errors that Mockito 5
 * raises when multiple mocks are type-compatible with a private inherited field such as
 * {@code Widget.layoutData} (typed {@code Object}) or {@code Composite.widget} (typed
 * {@code Widget}).
 *
 * <p>The test uses two {@code @Mock Widget}-subtype fields ({@link Label} and {@link TextBox}),
 * which would trigger Mockito 5's ambiguity check on the inherited {@code Composite.widget} field.
 */
@RunWith(GwtMockitoTestRunner.class)
public class GwtMockitoInjectMocksAmbiguityTest {

  /**
   * A simple widget view extending {@link Composite} and holding two collaborators. The no-arg
   * constructor is used by Mockito's {@code @InjectMocks} injection.
   */
  static class MyCompositeView extends Composite {
    Label label;
    TextBox textBox;
  }

  /**
   * A second view type with a different field name to avoid Mockito's own
   * "multiple fields of the same type" guard. Extends {@link Composite} so it
   * also triggers the GWT base-class ambiguity on {@code Composite.widget}.
   */
  static class AnotherCompositeView extends Composite {
    Label anotherLabel;
    TextBox anotherTextBox;
  }

  @Mock Label label;
  @Mock TextBox textBox;
  @Mock Label anotherLabel;
  @Mock TextBox anotherTextBox;

  /**
   * Mockito will construct {@link MyCompositeView} via its no-arg constructor and then inject
   * {@code label} and {@code textBox} by name/type. The fix in
   * {@link GwtMockito#initMocks(Object)} handles the Mockito 5 ambiguity error that arises because
   * both mocks are type-compatible with the private inherited {@code Composite.widget} field.
   */
  @InjectMocks MyCompositeView view;

  /**
   * Second {@code @InjectMocks} target declared after {@code view}. Mockito's injection loop
   * aborts when it encounters the ambiguity on {@code view}, leaving {@code anotherView}
   * unconstructed (null). {@code injectIntoAllTargets} must instantiate it and inject into it.
   */
  @InjectMocks AnotherCompositeView anotherView;

  @Test
  public void testInjectMocksDoesNotFailOnAmbiguousGwtBaseClassFields() {
    // Both named fields must have been injected correctly.
    assertNotNull("view must not be null", view);
    assertSame("label mock should be injected by name", label, view.label);
    assertSame("textBox mock should be injected by name", textBox, view.textBox);
  }

  @Test
  public void testGwtBaseClassFieldsArePopulatedAfterAmbiguityFix() throws Exception {
    // Composite.widget (private, inherited) must be non-null after GwtMockito's fix;
    // a null value would cause NPEs in GWT base-class methods.
    Field widgetField = Composite.class.getDeclaredField("widget");
    widgetField.setAccessible(true);
    Object inheritedWidget = widgetField.get(view);
    assertNotNull("Composite.widget must be set (non-null) by the ambiguity fix", inheritedWidget);
  }

  @Test
  public void testSecondInjectMocksTargetIsConstructedAndInjectedAfterAmbiguityRecovery() {
    // When Mockito aborts at the first @InjectMocks target (view) due to the GWT ambiguity,
    // the second target (anotherView) is left null. injectIntoAllTargets must construct it
    // via no-arg constructor and inject the named mocks into it.
    assertNotNull("anotherView must not be null after recovery", anotherView);
    assertSame("anotherLabel must be injected by name into anotherView",
        anotherLabel, anotherView.anotherLabel);
    assertSame("anotherTextBox must be injected by name into anotherView",
        anotherTextBox, anotherView.anotherTextBox);
  }
}
