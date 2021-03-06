/*
 * Copyright (C) 2017 The Android Open Source Project
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
package com.android.tools.idea.profilers;

import com.android.tools.idea.profilers.actions.NavigateToCodeAction;
import com.android.tools.profilers.ContextMenuInstaller;
import com.android.tools.profilers.stacktrace.CodeLocation;
import com.android.tools.profilers.stacktrace.CodeNavigator;
import com.android.tools.profilers.stacktrace.ContextMenuItem;
import com.intellij.openapi.actionSystem.*;
import com.intellij.ui.PopupHandler;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;
import java.util.function.Supplier;

public class IntellijContextMenuInstaller implements ContextMenuInstaller {
  private static final String COMPONENT_CONTEXT_MENU = "ComponentContextMenu";

  @Override
  public void installGenericContextMenu(@NotNull JComponent component, @NotNull ContextMenuItem contextMenuItem) {
    DefaultActionGroup popupGroup = createOrGetActionGroup(component);
    popupGroup.add(new AnAction(null, null, contextMenuItem.getIcon()) {
      @Override
      public void update(@NotNull AnActionEvent e) {
        super.update(e);

        Presentation presentation = e.getPresentation();
        presentation.setText(contextMenuItem.getText());
        presentation.setEnabled(contextMenuItem.isEnabled());
      }

      @Override
      public void actionPerformed(@NotNull AnActionEvent e) {
        contextMenuItem.run();
      }
    });
  }

  @Override
  public void installNavigationContextMenu(@NotNull JComponent component,
                                           @NotNull CodeNavigator navigator,
                                           @NotNull Supplier<CodeLocation> codeLocationSupplier) {
    DefaultActionGroup popupGroup = createOrGetActionGroup(component);
    popupGroup.add(new NavigateToCodeAction(codeLocationSupplier, navigator));
  }

  @NotNull
  private static DefaultActionGroup createOrGetActionGroup(@NotNull JComponent component) {
    DefaultActionGroup actionGroup = (DefaultActionGroup)component.getClientProperty(COMPONENT_CONTEXT_MENU);
    if (actionGroup == null) {
      final DefaultActionGroup newActionGroup = new DefaultActionGroup();
      component.putClientProperty(COMPONENT_CONTEXT_MENU, newActionGroup);
      component.addMouseListener(new PopupHandler() {
        @Override
        public void invokePopup(Component comp, int x, int y) {
          ActionManager.getInstance().createActionPopupMenu(ActionPlaces.UNKNOWN, newActionGroup).getComponent().show(comp, x, y);
        }
      });
      actionGroup = newActionGroup;
    }

    return actionGroup;
  }
}
