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

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

interface Target {
  int getPreferenceLevel();
  boolean layout(int l, int t, int r, int b);
  void addHit(@NotNull ScenePicker picker);
  void setComponent(@NotNull SceneComponent component);
  void render(@NotNull DisplayList list);
  void setOver(boolean over);

  void mouseDown(int x, int y);
  void mouseDrag(int x, int y, @Nullable Target closestTarget);
  void mouseRelease(int x, int y, @Nullable Target closestTarget);
}
