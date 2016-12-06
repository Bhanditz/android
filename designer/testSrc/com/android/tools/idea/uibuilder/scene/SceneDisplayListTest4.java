/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.tools.idea.uibuilder.scene;

import com.android.tools.idea.uibuilder.fixtures.ModelBuilder;
import com.android.tools.idea.uibuilder.scene.draw.DisplayList;
import org.jetbrains.annotations.NotNull;

import java.awt.image.BufferedImage;

import static com.android.SdkConstants.CONSTRAINT_LAYOUT;
import static com.android.SdkConstants.TEXT_VIEW;

public class SceneDisplayListTest4 extends SceneTest {
  @Override
  @NotNull
  public ModelBuilder createModel() {
    return model("constraint.xml",
                 component(CONSTRAINT_LAYOUT)
                   .id("@+id/root")
                   .withBounds(0, 0, 1000, 1000)
                   .width("1000dp")
                   .height("1000dp")
                   .withAttribute("android:padding", "20dp")
                   .children(
                     component(TEXT_VIEW)
                       .id("@+id/a")
                       .withBounds(450, 490, 100, 20)
                       .width("100dp")
                       .height("20dp")
                       .withAttribute("app:layout_constraintLeft_toLeftOf", "parent")
                       .withAttribute("app:layout_constraintRight_toLeftOf", "@+id/b")
                       .withAttribute("app:layout_constraintTop_toTopOf", "parent")
                       .withAttribute("app:layout_constraintBottom_toBottomOf", "parent"),
                     component(TEXT_VIEW)
                       .id("@+id/b")
                       .withBounds(450, 490, 100, 20)
                       .width("100dp")
                       .height("20dp")
                       .withAttribute("app:layout_constraintLeft_toRightOf", "@+id/a")
                       .withAttribute("app:layout_constraintRight_toLeftOf", "@+id/c")
                       .withAttribute("app:layout_constraintTop_toTopOf", "@+id/c"),
                     component(TEXT_VIEW)
                       .id("@+id/c")
                       .withBounds(450, 490, 100, 20)
                       .width("100dp")
                       .height("20dp")
                       .withAttribute("app:layout_constraintLeft_toRightOf", "@+id/b")
                       .withAttribute("app:layout_constraintRight_toRightOf", "parent")
                       .withAttribute("app:layout_constraintTop_toBottomOf", "parent")
                       .withAttribute("app:layout_constraintBottom_toBottomOf", "@+id/b")
                   ));
  }

  public void testBasicScene() {
     myScreen.get("@+id/a").expectXml("<TextView\n" +
                                      "    android:id=\"@+id/a\"\n" +
                                      "    android:layout_width=\"100dp\"\n" +
                                      "    android:layout_height=\"20dp\"\n" +
                                      "    app:layout_constraintLeft_toLeftOf=\"parent\"\n" +
                                      "    app:layout_constraintRight_toLeftOf=\"@+id/b\"\n" +
                                      "    app:layout_constraintTop_toTopOf=\"parent\"\n" +
                                      "    app:layout_constraintBottom_toBottomOf=\"parent\"/>");
    String list = myInteraction.getDisplayList().serialize();
    assertEquals(myInteraction.getDisplayList().serialize(), list);
    DisplayList disp = DisplayList.getDisplayList(list);
    assertEquals(list, DisplayList.getDisplayList(list).serialize());
    //noinspection UndesirableClassUsage
    BufferedImage img = new BufferedImage(1000, 1000,BufferedImage.TYPE_INT_ARGB);
    disp.paint(img.createGraphics(), SceneContext.get());
    assertEquals(18, disp.getCommands().size());
    disp.clear();
  }
}