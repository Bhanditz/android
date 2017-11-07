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
package com.android.tools.profilers.cpu;

import com.android.sdklib.AndroidVersion;
import com.android.tools.adtui.model.AspectObserver;
import com.android.tools.profiler.proto.CpuProfiler;
import com.android.tools.profiler.proto.Profiler;
import com.android.tools.profilers.StudioProfilers;
import com.google.common.collect.Iterables;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * This class is responsible for managing the profiling configuration for the CPU profiler. It is shared between
 * {@link CpuProfilerStage} and CpuProfilingConfigurationsDialog.
 */
public class CpuProfilerConfigModel extends DefaultListModel<CpuThreadsModel.RangedCpuThread> {
  private ProfilingConfiguration myProfilingConfiguration;

  /**
   * The list of all custom configurations. The list is filtered to configurations that are available, however it does not
   * filter on device support.
   */
  private List<ProfilingConfiguration> myCustomProfilingConfigurations;

  /**
   * The list of all default configurations. This list is filtered on device compatibility as well as feature availability.
   */
  private List<ProfilingConfiguration> myDefaultProfilingConfigurations;

  /**
   * {@link ProfilingConfiguration} object that stores the data (profiler type, profiling mode, sampling interval and buffer size limit)
   * that should be used in the next stopProfiling call. This field is required because stopProfiling should receive the same profiler type
   * as the one passed to startProfiling. Also, in stopProfiling we track the configurations used to capture.
   * We can't use {@link #myProfilingConfiguration} because it can be changed when we exit the Stage or change its value using the combobox.
   * Using a separate field, we can retrieve the relevant data from device in {@link #updateProfilingState()}.
   */
  private ProfilingConfiguration myActiveConfig;

  @NotNull
  private final StudioProfilers myProfilers;

  private AspectObserver myAspectObserver;

  public CpuProfilerConfigModel(@NotNull StudioProfilers profilers, CpuProfilerStage profilerStage) {
    myProfilers = profilers;

    myAspectObserver = new AspectObserver();

    profilerStage.getAspect().addDependency(myAspectObserver)
      .onChange(CpuProfilerAspect.PROFILING_CONFIGURATION, this::updateProfilingConfigurations);
  }

  public void setActiveConfig(CpuProfiler.CpuProfilerType profilerType, CpuProfiler.CpuProfilingAppStartRequest.Mode mode,
                              int bufferSizeLimitMb, int samplingIntervalUs) {
    // The configuration name field is not actually used when retrieving the active configuration. The reason behind that is configurations,
    // including their name, can be edited when a capture is still in progress. We only need to store the parameters used when starting
    // the capture (i.e. buffer size, profiler type, profiling mode and sampling interval). The capture name is not used on the server side
    // when starting a capture, so we simply ignore it here as well.
    String anyConfigName = "Current config";
    myActiveConfig = new ProfilingConfiguration(anyConfigName, profilerType, mode);
    myActiveConfig.setProfilingBufferSizeInMb(bufferSizeLimitMb);
    myActiveConfig.setProfilingSamplingIntervalUs(samplingIntervalUs);
  }

  public ProfilingConfiguration getActiveConfig() {
    return myActiveConfig;
  }

  @NotNull
  public ProfilingConfiguration getProfilingConfiguration() {
    return myProfilingConfiguration;
  }

  public void setProfilingConfiguration(@NotNull ProfilingConfiguration mode) {
    myProfilingConfiguration = mode;
  }

  @NotNull
  public List<ProfilingConfiguration> getCustomProfilingConfigurations() {
    return myCustomProfilingConfigurations;
  }

  @NotNull
  public List<ProfilingConfiguration> getDefaultProfilingConfigurations() {
    return myDefaultProfilingConfigurations;
  }

  public void updateProfilingConfigurations() {
    List<ProfilingConfiguration> savedConfigs = myProfilers.getIdeServices().getCpuProfilingConfigurations();
    List<ProfilingConfiguration> defaultConfigs = ProfilingConfiguration.getDefaultProfilingConfigurations();

    // Simpleperf/Atrace profiling is not supported by devices older than O
    Profiler.Device selectedDevice = myProfilers.getDevice();
    boolean isDeviceAtLeastO = selectedDevice != null && selectedDevice.getFeatureLevel() >= AndroidVersion.VersionCodes.O;
    boolean isSimplePerfEnabled = isDeviceAtLeastO && myProfilers.getIdeServices().getFeatureConfig().isSimplePerfEnabled();
    boolean isAtraceEnabled = isDeviceAtLeastO && myProfilers.getIdeServices().getFeatureConfig().isAtraceEnabled();
    Predicate<ProfilingConfiguration> filter = pref -> {
      if (pref.getProfilerType() == CpuProfiler.CpuProfilerType.SIMPLEPERF) {
        return isSimplePerfEnabled;
      }
      if (pref.getProfilerType() == CpuProfiler.CpuProfilerType.ATRACE) {
        return isAtraceEnabled;
      }
      return true;
    };
    myCustomProfilingConfigurations = savedConfigs.stream().filter(filter).collect(Collectors.toList());
    myDefaultProfilingConfigurations = defaultConfigs.stream().filter(filter).collect(Collectors.toList());
    if (myProfilingConfiguration == null) {
      // TODO (b/68691584): Remember the last selected configuration and suggest it instead of default configurations.
      // If there is a preference for a native configuration, we select simpleperf.
      if (myProfilers.getIdeServices().isNativeProfilingConfigurationPreferred() && isSimplePerfEnabled) {
        myProfilingConfiguration =
          Iterables.find(defaultConfigs, pref -> pref != null && pref.getProfilerType() == CpuProfiler.CpuProfilerType.SIMPLEPERF);
      }
      // Otherwise we select ART sampled.
      else {
        myProfilingConfiguration =
          Iterables.find(defaultConfigs, pref -> pref != null && pref.getProfilerType() == CpuProfiler.CpuProfilerType.ART
                                                 && pref.getMode() == CpuProfiler.CpuProfilingAppStartRequest.Mode.SAMPLED);
      }
    }
  }
}