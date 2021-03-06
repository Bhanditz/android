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

import com.android.tools.adtui.FilterComponent;
import com.android.tools.adtui.FlatTabbedPane;
import com.android.tools.adtui.RangeTimeScrollBar;
import com.android.tools.adtui.TabularLayout;
import com.android.tools.adtui.chart.hchart.HTreeChart;
import com.android.tools.adtui.chart.hchart.HTreeChartVerticalScrollBar;
import com.android.tools.adtui.common.ColumnTreeBuilder;
import com.android.tools.adtui.flat.FlatSeparator;
import com.android.tools.adtui.flat.FlatToggleButton;
import com.android.tools.adtui.instructions.InstructionsPanel;
import com.android.tools.adtui.instructions.TextInstruction;
import com.android.tools.adtui.model.AspectObserver;
import com.android.tools.adtui.model.Range;
import com.android.tools.perflib.vmtrace.ClockType;
import com.android.tools.profilers.*;
import com.android.tools.profilers.analytics.FeatureTracker;
import com.android.tools.profilers.cpu.nodemodel.CaptureNodeModel;
import com.android.tools.profilers.cpu.nodemodel.CppFunctionModel;
import com.android.tools.profilers.cpu.nodemodel.JavaMethodModel;
import com.android.tools.profilers.stacktrace.CodeLocation;
import com.android.tools.profilers.stacktrace.CodeNavigator;
import com.google.common.collect.ImmutableMap;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.ui.*;
import com.intellij.util.PlatformIcons;
import com.intellij.util.ui.tree.TreeModelAdapter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.event.ChangeEvent;
import javax.swing.event.TreeExpansionEvent;
import javax.swing.event.TreeModelEvent;
import javax.swing.event.TreeWillExpandListener;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.ExpandVetoException;
import javax.swing.tree.TreePath;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.Collections;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;

import static com.android.tools.adtui.common.AdtUiUtils.DEFAULT_BOTTOM_BORDER;
import static com.android.tools.adtui.common.AdtUiUtils.DEFAULT_TOP_BORDER;
import static com.android.tools.profilers.ProfilerLayout.*;

class CpuCaptureView {
  // Note the order of the values in the map defines the order of the tabs in UI.
  private static final Map<CaptureModel.Details.Type, String> TABS = ImmutableMap.of(
    CaptureModel.Details.Type.CALL_CHART, "Call Chart",
    CaptureModel.Details.Type.FLAME_CHART, "Flame Chart",
    CaptureModel.Details.Type.TOP_DOWN, "Top Down",
    CaptureModel.Details.Type.BOTTOM_UP, "Bottom Up");

  private static final Map<CaptureModel.Details.Type, Consumer<FeatureTracker>> CAPTURE_TRACKERS = ImmutableMap.of(
    CaptureModel.Details.Type.TOP_DOWN, FeatureTracker::trackSelectCaptureTopDown,
    CaptureModel.Details.Type.BOTTOM_UP, FeatureTracker::trackSelectCaptureBottomUp,
    CaptureModel.Details.Type.CALL_CHART, FeatureTracker::trackSelectCaptureCallChart,
    CaptureModel.Details.Type.FLAME_CHART, FeatureTracker::trackSelectCaptureFlameChart
  );

  private static final Comparator<DefaultMutableTreeNode> DEFAULT_SORT_ORDER =
    Collections.reverseOrder(new DoubleValueNodeComparator(CpuTreeNode::getTotal));

  @NotNull
  private final CpuProfilerStageView myView;

  private final JPanel myPanel;

  private final FlatTabbedPane myTabsPanel;

  @NotNull
  private FilterComponent myFilterComponent;

  // Intentionally local field, to prevent GC from cleaning it and removing weak listeners.
  // Previously, we were creating a CaptureDetailsView temporarily and grabbing its UI
  // component only. However, in the case of subclass TreeChartView that contains an
  // AspectObserver, which fires events. If that gets cleaned up early, our UI loses some
  // useful events.
  @SuppressWarnings("FieldCanBeLocal")
  @Nullable
  private CpuCaptureView.CaptureDetailsView myDetailsView;

  @NotNull
  private final ViewBinder<CpuProfilerStageView, CaptureModel.Details, CaptureDetailsView> myBinder;

  CpuCaptureView(@NotNull CpuProfilerStageView view) {
    myView = view;
    myTabsPanel = new FlatTabbedPane();

    for (String label : TABS.values()) {
      myTabsPanel.addTab(label, new JPanel(new BorderLayout()));
    }
    myTabsPanel.addChangeListener(this::setCaptureDetailToTab);

    JComboBox<ClockType> clockTypeCombo = new ComboBox<>();
    JComboBoxView clockTypes =
      new JComboBoxView<>(clockTypeCombo, view.getStage().getAspect(), CpuProfilerAspect.CLOCK_TYPE,
                          view.getStage()::getClockTypes, view.getStage()::getClockType, view.getStage()::setClockType);
    clockTypes.bind();
    clockTypeCombo.setRenderer(new ClockTypeCellRenderer());
    CpuCapture capture = myView.getStage().getCapture();
    clockTypeCombo.setEnabled(capture != null && capture.isDualClock());

    myTabsPanel.setOpaque(false);

    myPanel = new JPanel(new TabularLayout("*,Fit", "Fit,*"));
    JPanel toolbar = new JPanel(createToolbarLayout());
    toolbar.add(clockTypeCombo);
    toolbar.add(myView.getSelectionTimeLabel());
    myFilterComponent = new FilterComponent(FILTER_TEXT_FIELD_WIDTH, FILTER_TEXT_HISTORY_SIZE, FILTER_TEXT_FIELD_TRIGGER_DELAY_MS);
    if (view.getStage().getStudioProfilers().getIdeServices().getFeatureConfig().isCpuCaptureFilterEnabled()) {
      FlatToggleButton filterButton = FilterComponent.createFilterToggleButton();
      toolbar.add(new FlatSeparator());
      toolbar.add(filterButton);

      myFilterComponent.addOnFilterChange((pattern, model) -> myView.getStage().setCaptureFilter(pattern));
      myFilterComponent.setVisible(false);
      myFilterComponent.setBorder(DEFAULT_BOTTOM_BORDER);
      myFilterComponent.configureKeyBindingAndFocusBehaviors(myPanel, myFilterComponent, filterButton);
    }

    myPanel.add(toolbar, new TabularLayout.Constraint(0, 1));
    myPanel.add(myTabsPanel, new TabularLayout.Constraint(0, 0, 2, 2));

    myBinder = new ViewBinder<>();
    myBinder.bind(CaptureModel.TopDown.class, TopDownView::new);
    myBinder.bind(CaptureModel.BottomUp.class, BottomUpView::new);
    myBinder.bind(CaptureModel.CallChart.class, CallChartView::new);
    myBinder.bind(CaptureModel.FlameChart.class, FlameChartView::new);
    updateView();
  }

  void updateView() {
    // Clear the content of all the tabs
    for (Component tab : myTabsPanel.getComponents()) {
      // In the constructor, we make sure to use JPanel as root components of the tabs.
      assert tab instanceof JPanel;
      ((JPanel)tab).removeAll();
    }
    JComponent filterComponent = myFilterComponent;
    boolean searchHasFocus = filterComponent.isAncestorOf(KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner());
    if (filterComponent.getParent() != null) {
      filterComponent.getParent().remove(filterComponent);
    }

    CaptureModel.Details details = myView.getStage().getCaptureDetails();
    if (details == null) {
      return;
    }

    // Update the current selected tab
    String detailsTypeString = TABS.get(details.getType());
    int currentTabIndex = myTabsPanel.getSelectedIndex();
    if (currentTabIndex < 0 || !myTabsPanel.getTitleAt(currentTabIndex).equals(detailsTypeString)) {
      for (int i = 0; i < myTabsPanel.getTabCount(); ++i) {
        if (myTabsPanel.getTitleAt(i).equals(detailsTypeString)) {
          myTabsPanel.setSelectedIndex(i);
          break;
        }
      }
    }

    // Update selected tab content. As we need to update the content of the tabs dynamically,
    // we use a JPanel (set on the constructor) to wrap the content of each tab's content.
    // This is required because JBTabsImpl doesn't behave consistently when setting tab's component dynamically.
    JPanel selectedTab = (JPanel)myTabsPanel.getSelectedComponent();
    myDetailsView = myBinder.build(myView, details);
    selectedTab.add(filterComponent, BorderLayout.NORTH);
    selectedTab.add(myDetailsView.getComponent(), BorderLayout.CENTER);
    // We're replacing the content by removing and adding a new component.
    // JComponent#removeAll doc says that we should revalidate if it is already visible.
    selectedTab.revalidate();

    // the searchComponent gets re-added to the selected tab component after filtering changes, so reset the focus here.
    if (searchHasFocus) {
      myFilterComponent.requestFocusInWindow();
    }
  }

  private void setCaptureDetailToTab(ChangeEvent event) {
    CaptureModel.Details.Type type = null;
    if (myTabsPanel.getSelectedIndex() >= 0) {
      String tabTitle = myTabsPanel.getTitleAt(myTabsPanel.getSelectedIndex());
      for (Map.Entry<CaptureModel.Details.Type, String> entry : TABS.entrySet()) {
        if (tabTitle.equals(entry.getValue())) {
          type = entry.getKey();
        }
      }
    }
    myView.getStage().setCaptureDetails(type);

    // TODO: Move this logic into setCaptureDetails later. Right now, if we do it, we track the
    // event several times instead of just once after taking a capture. setCaptureDetails should
    // probably have a guard condition.
    FeatureTracker tracker = myView.getStage().getStudioProfilers().getIdeServices().getFeatureTracker();
    CAPTURE_TRACKERS.getOrDefault(type, featureTracker -> {
    }).accept(tracker);
  }

  private static Logger getLog() {
    return Logger.getInstance(CpuCaptureView.class);
  }


  @Nullable
  private static CodeLocation getCodeLocation(@NotNull JTree tree) {
    if (tree.getSelectionPath() == null) {
      return null;
    }
    DefaultMutableTreeNode node = (DefaultMutableTreeNode)tree.getSelectionPath().getLastPathComponent();
    return modelToCodeLocation(((CpuTreeNode)node.getUserObject()).getMethodModel());
  }

  /**
   * Expands a few nodes in order to improve the visual feedback of the list.
   */
  private static void expandTreeNodes(JTree tree) {
    int maxRowsToExpand = 8; // TODO: adjust this value if necessary.
    int i = 0;
    while (i < tree.getRowCount() && i < maxRowsToExpand) {
      tree.expandRow(i++);
    }
  }

  public JComponent getComponent() {
    return myPanel;
  }

  private static CpuTreeNode getNode(Object value) {
    DefaultMutableTreeNode node = (DefaultMutableTreeNode)value;
    return (CpuTreeNode)node.getUserObject();
  }

  private static HTreeChart<CaptureNode> setUpChart(@NotNull CaptureModel.Details.Type type,
                                                    @NotNull Range globalRange,
                                                    @NotNull Range range,
                                                    @Nullable CaptureNode node,
                                                    @NotNull CpuProfilerStageView stageView) {
    HTreeChart.Orientation orientation;
    if (type == CaptureModel.Details.Type.CALL_CHART) {
      orientation = HTreeChart.Orientation.TOP_DOWN;
    }
    else {
      orientation = HTreeChart.Orientation.BOTTOM_UP;
    }
    HTreeChart<CaptureNode> chart = new HTreeChart<>(globalRange, range, orientation);
    chart.setHRenderer(new CaptureNodeHRenderer(type));
    chart.setRootVisible(false);

    chart.setHTree(node);
    CodeNavigator navigator = stageView.getStage().getStudioProfilers().getIdeServices().getCodeNavigator();
    TreeChartNavigationHandler handler = new TreeChartNavigationHandler(chart, navigator);
    chart.addMouseListener(handler);
    stageView.getIdeComponents().createContextMenuInstaller().installNavigationContextMenu(chart, navigator, handler::getCodeLocation);
    CpuChartTooltipView.install(chart, stageView);

    return chart;
  }

  private static abstract class CaptureDetailsView {
    protected static final String CARD_EMPTY_INFO = "Empty content";
    protected static final String CARD_CONTENT = "Content";

    @NotNull
    abstract JComponent getComponent();

    protected static void switchCardLayout(@NotNull JPanel panel, boolean isEmpty) {
      CardLayout cardLayout = (CardLayout)panel.getLayout();
      cardLayout.show(panel, isEmpty ? CARD_EMPTY_INFO : CARD_CONTENT);
    }

    protected static JPanel getNoDataForThread() {
      String message = "No data available for the selected thread.";
      JPanel panel = new JPanel(new BorderLayout());
      InstructionsPanel info = new InstructionsPanel.Builder(new TextInstruction(INFO_MESSAGE_HEADER_FONT, message))
        .setColors(JBColor.foreground(), null)
        .build();
      panel.add(info, BorderLayout.CENTER);
      return panel;
    }

    protected static JComponent getNoDataForRange() {
      String message = "No data available for the selected time frame.";
      JPanel panel = new JPanel(new BorderLayout());
      InstructionsPanel info = new InstructionsPanel.Builder(new TextInstruction(INFO_MESSAGE_HEADER_FONT, message))
        .setColors(JBColor.foreground(), null)
        .build();
      panel.add(info, BorderLayout.CENTER);
      return panel;
    }
  }

  private static class ClockTypeCellRenderer extends ListCellRendererWrapper<ClockType> {
    @Override
    public void customize(JList list,
                          ClockType value,
                          int index,
                          boolean selected,
                          boolean hasFocus) {
      switch (value) {
        case GLOBAL:
          setText("Wall Clock Time");
          break;
        case THREAD:
          setText("Thread Time");
          break;
        default:
          getLog().warn("Unexpected clock type received.");
      }
    }
  }

  /**
   * An abstract view for {@link TopDownView} and {@link BottomUpView}.
   * They are almost similar except a few key differences, e.g bottom-up hides its root or lazy loads its children on expand.
   */
  private static abstract class TreeView extends CaptureDetailsView {
    @NotNull protected final JPanel myPanel;
    @NotNull private final AspectObserver myObserver;
    @Nullable protected final JTree myTree;
    @Nullable private final CpuTraceTreeSorter mySorter;

    protected TreeView(@NotNull CpuProfilerStageView stageView, @Nullable CpuTreeModel model) {
      myObserver = new AspectObserver();
      if (model == null) {
        myPanel = getNoDataForThread();
        myTree = null;
        mySorter = null;
        return;
      }

      myPanel = new JPanel(new CardLayout());
      // Use JTree instead of IJ's tree, because IJ's tree does not happen border's Insets.
      myTree = new JTree();
      int defaultFontHeight = myTree.getFontMetrics(myTree.getFont()).getHeight();
      myTree.setRowHeight(defaultFontHeight + ROW_HEIGHT_PADDING);
      myTree.setBorder(TABLE_ROW_BORDER);
      myTree.setModel(model);
      mySorter = new CpuTraceTreeSorter(myTree);
      mySorter.setModel(model, DEFAULT_SORT_ORDER);

      myPanel.add(createTableTree(), CARD_CONTENT);
      myPanel.add(getNoDataForRange(), CARD_EMPTY_INFO);

      CodeNavigator navigator = stageView.getStage().getStudioProfilers().getIdeServices().getCodeNavigator();
      stageView.getIdeComponents().createContextMenuInstaller().installNavigationContextMenu(myTree, navigator,
                                                                                             () -> getCodeLocation(myTree));

      switchCardLayout(myPanel, model.isEmpty());

      // The structure of the tree changed, so sort with the previous sorting order.
      model.getAspect().addDependency(myObserver).onChange(CpuTreeModel.Aspect.TREE_MODEL, () -> mySorter.sort());
    }

    @NotNull
    @Override
    public JComponent getComponent() {
      return myPanel;
    }

    @NotNull
    private JComponent createTableTree() {
      assert myTree != null && mySorter != null;

      return new ColumnTreeBuilder(myTree)
        .addColumn(new ColumnTreeBuilder.ColumnBuilder()
                     .setName("Name")
                     .setPreferredWidth(900)
                     .setHeaderBorder(TABLE_COLUMN_HEADER_BORDER)
                     .setHeaderAlignment(SwingConstants.LEFT)
                     .setRenderer(new MethodNameRenderer())
                     .setComparator(new NameValueNodeComparator()))
        .addColumn(new ColumnTreeBuilder.ColumnBuilder()
                     .setName("Total (μs)")
                     .setPreferredWidth(100)
                     .setHeaderBorder(TABLE_COLUMN_RIGHT_ALIGNED_HEADER_BORDER)
                     .setMinWidth(80)
                     .setHeaderAlignment(SwingConstants.RIGHT)
                     .setRenderer(new DoubleValueCellRendererWithSparkline(CpuTreeNode::getTotal, false, SwingConstants.RIGHT))
                     .setSortOrderPreference(SortOrder.DESCENDING)
                     .setComparator(new DoubleValueNodeComparator(CpuTreeNode::getTotal)))
        .addColumn(new ColumnTreeBuilder.ColumnBuilder()
                     .setName("%")
                     .setPreferredWidth(60)
                     .setMinWidth(60)
                     .setHeaderBorder(TABLE_COLUMN_RIGHT_ALIGNED_HEADER_BORDER)
                     .setHeaderAlignment(SwingConstants.RIGHT)
                     .setRenderer(new DoubleValueCellRenderer(CpuTreeNode::getTotal, true, SwingConstants.RIGHT))
                     .setSortOrderPreference(SortOrder.DESCENDING)
                     .setComparator(new DoubleValueNodeComparator(CpuTreeNode::getTotal)))
        .addColumn(new ColumnTreeBuilder.ColumnBuilder()
                     .setName("Self (μs)")
                     .setPreferredWidth(100)
                     .setHeaderBorder(TABLE_COLUMN_RIGHT_ALIGNED_HEADER_BORDER)
                     .setMinWidth(80)
                     .setHeaderAlignment(SwingConstants.RIGHT)
                     .setRenderer(new DoubleValueCellRenderer(CpuTreeNode::getSelf, false, SwingConstants.RIGHT))
                     .setSortOrderPreference(SortOrder.DESCENDING)
                     .setComparator(new DoubleValueNodeComparator(CpuTreeNode::getSelf)))
        .addColumn(new ColumnTreeBuilder.ColumnBuilder()
                     .setName("%")
                     .setPreferredWidth(60)
                     .setMinWidth(60)
                     .setHeaderBorder(TABLE_COLUMN_RIGHT_ALIGNED_HEADER_BORDER)
                     .setHeaderAlignment(SwingConstants.RIGHT)
                     .setRenderer(new DoubleValueCellRenderer(CpuTreeNode::getSelf, true, SwingConstants.RIGHT))
                     .setSortOrderPreference(SortOrder.DESCENDING)
                     .setComparator(new DoubleValueNodeComparator(CpuTreeNode::getSelf)))
        .addColumn(new ColumnTreeBuilder.ColumnBuilder()
                     .setName("Children (μs)")
                     .setPreferredWidth(100)
                     .setHeaderBorder(TABLE_COLUMN_RIGHT_ALIGNED_HEADER_BORDER)
                     .setMinWidth(80)
                     .setHeaderAlignment(SwingConstants.RIGHT)
                     .setRenderer(new DoubleValueCellRenderer(CpuTreeNode::getChildrenTotal, false, SwingConstants.RIGHT))
                     .setSortOrderPreference(SortOrder.DESCENDING)
                     .setComparator(new DoubleValueNodeComparator(CpuTreeNode::getChildrenTotal)))
        .addColumn(new ColumnTreeBuilder.ColumnBuilder()
                     .setName("%")
                     .setPreferredWidth(60)
                     .setMinWidth(60)
                     .setHeaderBorder(TABLE_COLUMN_RIGHT_ALIGNED_HEADER_BORDER)
                     .setHeaderAlignment(SwingConstants.RIGHT)
                     .setRenderer(new DoubleValueCellRenderer(CpuTreeNode::getChildrenTotal, true, SwingConstants.RIGHT))
                     .setSortOrderPreference(SortOrder.DESCENDING)
                     .setComparator(new DoubleValueNodeComparator(CpuTreeNode::getChildrenTotal)))
        .setTreeSorter(mySorter)
        .setBorder(DEFAULT_TOP_BORDER)
        .setBackground(ProfilerColors.DEFAULT_BACKGROUND)
        .setShowVerticalLines(true)
        .setTableIntercellSpacing(new Dimension())
        .build();
    }
  }

  private static class TopDownView extends TreeView {
    TopDownView(@NotNull CpuProfilerStageView view, @NotNull CaptureModel.TopDown topDown) {
      super(view, topDown.getModel());
      TopDownTreeModel model = topDown.getModel();
      if (model == null) {
        return;
      }
      assert myTree != null;

      expandTreeNodes(myTree);

      model.addTreeModelListener(new TreeModelAdapter() {
        @Override
        protected void process(@NotNull TreeModelEvent event, @NotNull EventType type) {
          switchCardLayout(myPanel, model.isEmpty());
        }
      });
    }
  }

  private static class BottomUpView extends TreeView {

    BottomUpView(@NotNull CpuProfilerStageView view, @NotNull CaptureModel.BottomUp bottomUp) {
      super(view, bottomUp.getModel());
      BottomUpTreeModel model = bottomUp.getModel();
      if (model == null) {
        return;
      }
      assert myTree != null;

      myTree.setRootVisible(false);
      myTree.addTreeWillExpandListener(new TreeWillExpandListener() {
        @Override
        public void treeWillExpand(TreeExpansionEvent event) throws ExpandVetoException {
          DefaultMutableTreeNode node = (DefaultMutableTreeNode)event.getPath().getLastPathComponent();
          ((BottomUpTreeModel)myTree.getModel()).expand(node);
        }

        @Override
        public void treeWillCollapse(TreeExpansionEvent event) throws ExpandVetoException {
        }
      });

      model.addTreeModelListener(new TreeModelAdapter() {
        @Override
        protected void process(@NotNull TreeModelEvent event, @NotNull EventType type) {
          // When the root loses all of its children it can't be expanded and when they're added it is still collapsed.
          // As a result, nothing will be visible as the root itself isn't visible. So, expand it if it's the case.
          if (type == EventType.NodesInserted && event.getTreePath().getPathCount() == 1) {
            DefaultMutableTreeNode root = (DefaultMutableTreeNode)model.getRoot();
            Object[] inserted = event.getChildren();
            if (inserted != null && inserted.length == root.getChildCount()) {
              myTree.expandPath(new TreePath(root));
            }
          }
          switchCardLayout(myPanel, model.isEmpty());
        }
      });
    }
  }

  static class CallChartView extends CaptureDetailsView {
    @NotNull private final JPanel myPanel;
    @NotNull private final CaptureModel.CallChart myCallChart;
    @NotNull private final HTreeChart<CaptureNode> myChart;

    private AspectObserver myObserver;

    private CallChartView(@NotNull CpuProfilerStageView stageView,
                          @NotNull CaptureModel.CallChart callChart) {
      myCallChart = callChart;
      // Call Chart model always correlates to the entire capture. CallChartView shows the data corresponding to the selected range in
      // timeline. Users can navigate to other part within the capture by interacting with the call chart UI. When it happens, the timeline
      // selection should be automatically updated.
      Range selectionRange = stageView.getTimeline().getSelectionRange();
      Range captureRange = stageView.getStage().getCapture().getRange();
      myChart = setUpChart(CaptureModel.Details.Type.CALL_CHART, captureRange, selectionRange,
                           myCallChart.getNode(), stageView);

      if (myCallChart.getNode() == null) {
        myPanel = getNoDataForThread();
        return;
      }

      // We use selectionRange here instead of nodeRange, because nodeRange synchronises with selectionRange and vice versa.
      // In other words, there is a constant ratio between them. And the horizontal scrollbar represents selection range within
      // capture range.
      RangeTimeScrollBar horizontalScrollBar = new RangeTimeScrollBar(captureRange, selectionRange, TimeUnit.MICROSECONDS);
      horizontalScrollBar.setPreferredSize(new Dimension(horizontalScrollBar.getPreferredSize().width, 10));

      JPanel contentPanel = new JPanel(new BorderLayout());

      contentPanel.add(myChart, BorderLayout.CENTER);
      contentPanel.add(new HTreeChartVerticalScrollBar<>(myChart), BorderLayout.EAST);
      contentPanel.add(horizontalScrollBar, BorderLayout.SOUTH);

      myPanel = new JPanel(new CardLayout());
      myPanel.add(contentPanel, CARD_CONTENT);
      myPanel.add(getNoDataForRange(), CARD_EMPTY_INFO);

      myObserver = new AspectObserver();
      myCallChart.getRange().addDependency(myObserver).onChange(Range.Aspect.RANGE, this::callChartRangeChanged);
      callChartRangeChanged();
    }

    private void callChartRangeChanged() {
      CaptureNode node = myCallChart.getNode();
      assert node != null;
      Range intersection = myCallChart.getRange().getIntersection(new Range(node.getStart(), node.getEnd()));
      switchCardLayout(myPanel, intersection.isEmpty() || intersection.getLength() == 0);
    }

    @NotNull
    @Override
    public JComponent getComponent() {
      return myPanel;
    }
  }

  static class FlameChartView extends CaptureDetailsView {
    @NotNull private final JPanel myPanel;
    @NotNull private final HTreeChart<CaptureNode> myChart;
    @NotNull private final AspectObserver myObserver;
    @NotNull private final CaptureModel.FlameChart myFlameChart;

    /**
     * The range that is visible to the user. When the user zooms in/out or pans this range will be changed.
     */
    @NotNull private final Range myMasterRange;

    FlameChartView(CpuProfilerStageView stageView, @NotNull CaptureModel.FlameChart flameChart) {
      // Flame Chart model always correlates to the selected range on the timeline, not necessarily the entire capture. Users cannot
      // navigate to other part within the capture by interacting with the flame chart UI (they can do so only from timeline UI).
      // Users can zoom-in and then view only part of the flame chart. Since a part of flame chart may not correspond to a continuous
      // sub-range on timeline, the timeline selection should not be updated while users are interacting with flame chart UI. Therefore,
      // we create new Range object (myMasterRange) to represent the range visible to the user. We cannot just pass flameChart.getRange().
      myFlameChart = flameChart;
      myMasterRange = new Range(flameChart.getRange());
      myChart = setUpChart(CaptureModel.Details.Type.FLAME_CHART, flameChart.getRange(), myMasterRange, myFlameChart.getNode(), stageView);
      myObserver = new AspectObserver();

      if (myFlameChart.getNode() == null) {
        myPanel = getNoDataForThread();
        return;
      }

      RangeTimeScrollBar horizontalScrollBar = new RangeTimeScrollBar(flameChart.getRange(), myMasterRange, TimeUnit.MICROSECONDS);
      horizontalScrollBar.setPreferredSize(new Dimension(horizontalScrollBar.getPreferredSize().width, 10));

      JPanel contentPanel = new JPanel(new BorderLayout());
      contentPanel.add(myChart, BorderLayout.CENTER);
      contentPanel.add(new HTreeChartVerticalScrollBar<>(myChart), BorderLayout.EAST);
      contentPanel.add(horizontalScrollBar, BorderLayout.SOUTH);

      myPanel = new JPanel(new CardLayout());
      myPanel.add(contentPanel, CARD_CONTENT);
      myPanel.add(getNoDataForRange(), CARD_EMPTY_INFO);

      myFlameChart.getAspect().addDependency(myObserver).onChange(CaptureModel.FlameChart.Aspect.NODE, this::nodeChanged);
      nodeChanged();
    }

    private void nodeChanged() {
      switchCardLayout(myPanel, myFlameChart.getNode() == null);
      myChart.setHTree(myFlameChart.getNode());
      myMasterRange.set(myFlameChart.getRange());
    }

    @NotNull
    @Override
    JComponent getComponent() {
      return myPanel;
    }
  }

  private static class TreeChartNavigationHandler extends MouseAdapter {
    @NotNull private final HTreeChart<CaptureNode> myChart;
    private Point myLastPopupPoint;

    TreeChartNavigationHandler(@NotNull HTreeChart<CaptureNode> chart, @NotNull CodeNavigator navigator) {
      myChart = chart;
      new DoubleClickListener() {
        @Override
        protected boolean onDoubleClick(MouseEvent event) {
          setLastPopupPoint(event);
          CodeLocation codeLocation = getCodeLocation();
          if (codeLocation != null && navigator.isNavigatable(codeLocation)) {
            navigator.navigate(codeLocation);
          }
          return false;
        }
      }.installOn(chart);
    }

    @Override
    public void mousePressed(MouseEvent e) {
      handlePopup(e);
    }

    @Override
    public void mouseReleased(MouseEvent e) {
      handlePopup(e);
    }

    private void handlePopup(MouseEvent e) {
      if (e.isPopupTrigger()) {
        setLastPopupPoint(e);
      }
    }

    private void setLastPopupPoint(MouseEvent e) {
      myLastPopupPoint = e.getPoint();
    }

    @Nullable
    private CodeLocation getCodeLocation() {
      CaptureNode n = myChart.getNodeAt(myLastPopupPoint);
      if (n == null) {
        return null;
      }
      return modelToCodeLocation(n.getData());
    }
  }

  /**
   * Produces a {@link CodeLocation} corresponding to a {@link CaptureNodeModel}. Returns null if the model is not navigatable.
   */
  @Nullable
  private static CodeLocation modelToCodeLocation(CaptureNodeModel model) {
    if (model instanceof CppFunctionModel) {
      CppFunctionModel nativeFunction = (CppFunctionModel)model;
      return new CodeLocation.Builder(nativeFunction.getClassOrNamespace())
        .setMethodName(nativeFunction.getName())
        .setMethodParameters(nativeFunction.getParameters())
        .setNativeCode(true)
        .build();
    }
    else if (model instanceof JavaMethodModel) {
      JavaMethodModel javaMethod = (JavaMethodModel)model;
      return new CodeLocation.Builder(javaMethod.getClassName())
        .setMethodName(javaMethod.getName())
        .setMethodSignature(javaMethod.getSignature())
        .setNativeCode(false)
        .build();
    }
    // Code is not navigatable.
    return null;
  }

  private static class NameValueNodeComparator implements Comparator<DefaultMutableTreeNode> {
    @Override
    public int compare(DefaultMutableTreeNode a, DefaultMutableTreeNode b) {
      CpuTreeNode o1 = ((CpuTreeNode)a.getUserObject());
      CpuTreeNode o2 = ((CpuTreeNode)b.getUserObject());
      return o1.getMethodModel().getName().compareTo(o2.getMethodModel().getName());
    }
  }

  private static class DoubleValueNodeComparator implements Comparator<DefaultMutableTreeNode> {
    private final Function<CpuTreeNode, Double> myGetter;

    DoubleValueNodeComparator(Function<CpuTreeNode, Double> getter) {
      myGetter = getter;
    }

    @Override
    public int compare(DefaultMutableTreeNode a, DefaultMutableTreeNode b) {
      CpuTreeNode o1 = ((CpuTreeNode)a.getUserObject());
      CpuTreeNode o2 = ((CpuTreeNode)b.getUserObject());
      return Double.compare(myGetter.apply(o1), myGetter.apply(o2));
    }
  }

  private static abstract class CpuCaptureCellRenderer extends ColoredTreeCellRenderer {

    private static final Map<CaptureNode.FilterType, SimpleTextAttributes> TEXT_ATTRIBUTES =
      ImmutableMap.<CaptureNode.FilterType, SimpleTextAttributes>builder()
        .put(CaptureNode.FilterType.MATCH, SimpleTextAttributes.REGULAR_ATTRIBUTES)
        .put(CaptureNode.FilterType.EXACT_MATCH, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
        .put(CaptureNode.FilterType.UNMATCH, SimpleTextAttributes.GRAY_ATTRIBUTES)
        .build();

    @NotNull
    protected SimpleTextAttributes getTextAttributes(@NotNull CpuTreeNode node) {
      return TEXT_ATTRIBUTES.get(node.getFilterType());
    }
  }

  private static class DoubleValueCellRenderer extends CpuCaptureCellRenderer {
    private final Function<CpuTreeNode, Double> myGetter;
    private final boolean myShowPercentage;
    private final int myAlignment;

    DoubleValueCellRenderer(Function<CpuTreeNode, Double> getter, boolean showPercentage, int alignment) {
      myGetter = getter;
      myShowPercentage = showPercentage;
      myAlignment = alignment;
      setTextAlign(alignment);
    }

    @Override
    public void customizeCellRenderer(@NotNull JTree tree,
                                      Object value,
                                      boolean selected,
                                      boolean expanded,
                                      boolean leaf,
                                      int row,
                                      boolean hasFocus) {
      CpuTreeNode node = getNode(value);
      if (node != null) {
        SimpleTextAttributes attributes = getTextAttributes(node);
        double v = myGetter.apply(node);
        if (myShowPercentage) {
          CpuTreeNode root = getNode(tree.getModel().getRoot());
          append(String.format("%.2f", v / root.getTotal() * 100), attributes);
        }
        else {
          append(String.format("%,.0f", v), attributes);
        }
      }
      else {
        // TODO: We should improve the visual feedback when no data is available.
        append(value.toString());
      }
      if (myAlignment == SwingConstants.LEFT) {
        setIpad(TABLE_COLUMN_CELL_INSETS);
      }
      else {
        setIpad(TABLE_COLUMN_RIGHT_ALIGNED_CELL_INSETS);
      }
    }

    protected Function<CpuTreeNode, Double> getGetter() {
      return myGetter;
    }
  }

  private static class DoubleValueCellRendererWithSparkline extends DoubleValueCellRenderer {
    private Color mySparklineColor;

    /**
     * Stores cell value divided by root cell total in order to compute the sparkline width.
     */
    private double myPercentage;

    DoubleValueCellRendererWithSparkline(Function<CpuTreeNode, Double> getter, boolean showPercentage, int alignment) {
      super(getter, showPercentage, alignment);
      mySparklineColor = ProfilerColors.CPU_CAPTURE_SPARKLINE;
      myPercentage = Double.NEGATIVE_INFINITY;
    }

    @Override
    public void customizeCellRenderer(@NotNull JTree tree,
                                      Object value,
                                      boolean selected,
                                      boolean expanded,
                                      boolean leaf,
                                      int row,
                                      boolean hasFocus) {
      super.customizeCellRenderer(tree, value, selected, expanded, leaf, row, hasFocus);
      CpuTreeNode node = getNode(value);
      if (node != null) {
        myPercentage = getGetter().apply(node) / getNode(tree.getModel().getRoot()).getTotal();
      }
      mySparklineColor = selected ? ProfilerColors.CPU_CAPTURE_SPARKLINE_SELECTED : ProfilerColors.CPU_CAPTURE_SPARKLINE;
    }

    @Override
    protected void paintComponent(Graphics g) {
      if (myPercentage > 0) {
        g.setColor(mySparklineColor);
        // The sparkline starts from the left side of the cell and is proportional to the value, occupying at most half of the cell.
        g.fillRect(TABLE_COLUMN_CELL_SPARKLINE_LEFT_PADDING,
                   TABLE_COLUMN_CELL_SPARKLINE_TOP_BOTTOM_PADDING,
                   (int)(myPercentage * (getWidth() / 2 - TABLE_COLUMN_CELL_SPARKLINE_LEFT_PADDING)),
                   getHeight() - TABLE_COLUMN_CELL_SPARKLINE_TOP_BOTTOM_PADDING * 2);
      }
      super.paintComponent(g);
    }
  }

  private static class MethodNameRenderer extends CpuCaptureCellRenderer {
    @Override
    public void customizeCellRenderer(@NotNull JTree tree,
                                      Object value,
                                      boolean selected,
                                      boolean expanded,
                                      boolean leaf,
                                      int row,
                                      boolean hasFocus) {
      if (value instanceof DefaultMutableTreeNode &&
          ((DefaultMutableTreeNode)value).getUserObject() instanceof CpuTreeNode) {
        CpuTreeNode node = (CpuTreeNode)((DefaultMutableTreeNode)value).getUserObject();
        SimpleTextAttributes attributes = getTextAttributes(node);
        CaptureNodeModel model = node.getMethodModel();
        String classOrNamespace = "";
        if (model instanceof CppFunctionModel) {
          classOrNamespace = ((CppFunctionModel)model).getClassOrNamespace();
        }
        else if (model instanceof JavaMethodModel) {
          classOrNamespace = ((JavaMethodModel)model).getClassName();
        }

        if (model.getName().isEmpty()) {
          setIcon(AllIcons.Debugger.ThreadSuspended);
          append(classOrNamespace, attributes);
        }
        else {
          setIcon(PlatformIcons.METHOD_ICON);
          append(model.getName() + "()", attributes);
          append(" (" + classOrNamespace + ")", SimpleTextAttributes.GRAY_ATTRIBUTES);
        }
      }
      else {
        append(value.toString());
      }
    }
  }
}
