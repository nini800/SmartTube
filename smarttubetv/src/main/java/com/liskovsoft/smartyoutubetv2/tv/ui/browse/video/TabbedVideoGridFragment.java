package com.liskovsoft.smartyoutubetv2.tv.ui.browse.video;

import android.graphics.Rect;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.leanback.widget.ArrayObjectAdapter;
import androidx.leanback.widget.BrowseFrameLayout;
import androidx.leanback.widget.HorizontalGridView;
import androidx.leanback.widget.ItemBridgeAdapter;
import androidx.leanback.widget.OnChildViewHolderSelectedListener;
import androidx.leanback.widget.OnItemViewSelectedListener;
import androidx.leanback.widget.Presenter;
import androidx.leanback.widget.Row;
import androidx.leanback.widget.RowPresenter;
import androidx.leanback.widget.VerticalGridPresenter;
import androidx.leanback.widget.VerticalGridView;
import androidx.recyclerview.widget.RecyclerView;

import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.VideoGroup;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.BrowsePresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.interfaces.VideoGroupPresenter;
import com.liskovsoft.smartyoutubetv2.common.prefs.MainUIData;
import com.liskovsoft.smartyoutubetv2.common.utils.Utils;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.liskovsoft.smartyoutubetv2.tv.adapter.VideoGroupObjectAdapter;
import com.liskovsoft.smartyoutubetv2.tv.presenter.CustomVerticalGridPresenter;
import com.liskovsoft.smartyoutubetv2.tv.presenter.ShortsCardPresenter;
import com.liskovsoft.smartyoutubetv2.tv.presenter.VideoCardPresenter;
import com.liskovsoft.smartyoutubetv2.tv.presenter.base.OnItemLongPressedListener;
import com.liskovsoft.smartyoutubetv2.tv.ui.browse.interfaces.VideoSection;
import com.liskovsoft.smartyoutubetv2.tv.ui.common.LeanbackActivity;
import com.liskovsoft.smartyoutubetv2.tv.ui.common.UriBackgroundManager;
import com.liskovsoft.smartyoutubetv2.tv.ui.mod.fragments.GridFragment;
import com.liskovsoft.smartyoutubetv2.tv.util.ViewUtil;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Replacement for {@link VideoRowsFragment} in the browse sections.<br/>
 * Every row YouTube returns becomes a tab on top. The selected row is shown below as a 2D grid
 * that continues loading while scrolling down.
 */
public class TabbedVideoGridFragment extends GridFragment implements VideoSection {
    private static final String TAG = TabbedVideoGridFragment.class.getSimpleName();
    private static final int NO_TAB = -1;
    private static final long TAB_SWITCH_DELAY_MS = 150;
    private static final long TABS_ANIMATION_MS = 200;
    /**
     * Keep loading until the tab has at least this many rows of cards.
     */
    private static final int MIN_FILLED_ROWS = 3;
    private final List<VideoGroup> mPendingUpdates = new ArrayList<>();
    private final List<Tab> mTabs = new ArrayList<>();
    private final Map<Integer, VideoGroupObjectAdapter> mVideoGroupAdapters = new HashMap<>();
    private final Map<Integer, Integer> mTabPositions = new HashMap<>();
    // Adapter size at the time of the last continuation request. Prevents duplicate requests for the same page.
    private final Map<VideoGroupObjectAdapter, Integer> mContinueRequestedSizes = new IdentityHashMap<>();
    private ArrayObjectAdapter mTabsAdapter;
    private HorizontalGridView mTabsView;
    private View mTabsContent;
    private int mTabsCollapseDistance;
    private boolean mTabsHidden;
    private UriBackgroundManager mBackgroundManager;
    private VideoGroupPresenter mMainPresenter;
    private VideoCardPresenter mCardPresenter;
    private ShortsCardPresenter mShortsPresenter;
    private VideoGroupObjectAdapter mEmptyAdapter;
    private int mCurrentTabId = NO_TAB;
    private int mPendingTabIndex = -1;
    private int mVideoColsNum;
    private int mShortsColsNum;
    private int mFocusedTabIndex = -1;
    private boolean mLastFocusInTabs;
    private final Runnable mSwitchToFocusedTab = this::switchToFocusedTab;
    private final ViewTreeObserver.OnGlobalFocusChangeListener mFocusChangeListener = (oldFocus, newFocus) -> updateTabsVisibility();

    private static final class Tab {
        final int id;
        final String title;

        Tab(int id, String title) {
            this.id = id;
            this.title = title;
        }
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (getActivity() == null) {
            throw new IllegalStateException("TabbedVideoGridFragment doesn't attached to the activity");
        }

        mMainPresenter = getMainPresenter();
        mCardPresenter = new VideoCardPresenter();
        mShortsPresenter = new ShortsCardPresenter();
        mBackgroundManager = ((LeanbackActivity) getActivity()).getBackgroundManager();
        float videoGridScale = MainUIData.instance(getActivity()).getVideoGridScale();
        mVideoColsNum = Math.max(GridFragmentHelper.getMaxColsNum(getContext(), R.dimen.card_width, videoGridScale), 1);
        mShortsColsNum = Math.max(GridFragmentHelper.getMaxColsNum(getContext(), R.dimen.shorts_card_width, videoGridScale), 1);

        setupAdapter();
        setupEventListeners();
        applyPendingUpdates();

        if (getMainFragmentAdapter().getFragmentHost() != null) {
            getMainFragmentAdapter().getFragmentHost().notifyDataReady(getMainFragmentAdapter());
        }
    }

    @Override
    protected int getLayoutResId() {
        return R.layout.fragment_tabbed_grid;
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        VerticalGridView grid = getBrowseGrid();
        if (grid != null) {
            // The tabs take the place of the usual top padding
            grid.setPadding(grid.getPaddingLeft(), getResources().getDimensionPixelSize(R.dimen.tabs_grid_padding_top),
                    grid.getPaddingRight(), grid.getPaddingBottom());
        }

        mTabsView = view.findViewById(R.id.tabs_row);
        mTabsView.setHorizontalSpacing(getResources().getDimensionPixelSize(R.dimen.tab_item_spacing));
        mTabsView.setAdapter(new ItemBridgeAdapter(mTabsAdapter));
        mTabsView.setOnChildViewHolderSelectedListener(new OnChildViewHolderSelectedListener() {
            @Override
            public void onChildViewHolderSelected(RecyclerView parent, RecyclerView.ViewHolder child, int position, int subposition) {
                onTabFocused(position);
            }
        });
        syncTabsSelection();

        setupFocus((BrowseFrameLayout) view.findViewById(R.id.grid_frame));
        setupTabsHiding(view);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        Utils.removeCallbacks(mSwitchToFocusedTab);
        if (getView() != null) {
            getView().getViewTreeObserver().removeOnGlobalFocusChangeListener(mFocusChangeListener);
        }
        mTabsView = null;
        mTabsContent = null;
        mTabsHidden = false;
    }

    /**
     * The tabs slide away while scrolling down the grid, like the title bar.<br/>
     * They come back on the first grid row, or when the focus leaves the grid (tabs, sidebar).
     */
    private void setupTabsHiding(View root) {
        mTabsContent = root.findViewById(R.id.tabs_content);
        mTabsCollapseDistance = getResources().getDimensionPixelSize(R.dimen.tabs_row_margin_top)
                + getResources().getDimensionPixelSize(R.dimen.tabs_row_height);

        // Make the content taller than the screen by the tabs area, so the grid fills the screen once the tabs slide up
        root.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            int height = bottom - top + mTabsCollapseDistance;
            ViewGroup.LayoutParams params = mTabsContent != null ? mTabsContent.getLayoutParams() : null;

            if (params != null && params.height != height) {
                params.height = height;
                v.post(() -> {
                    if (mTabsContent != null) {
                        mTabsContent.setLayoutParams(params);
                    }
                });
            }
        });

        root.getViewTreeObserver().addOnGlobalFocusChangeListener(mFocusChangeListener);
    }

    private void updateTabsVisibility() {
        VerticalGridView grid = getBrowseGrid();

        setTabsHidden(grid != null && grid.hasFocus() && grid.getSelectedPosition() >= getCurrentColsNum());
    }

    private void setTabsHidden(boolean hidden) {
        if (mTabsContent == null || mTabsView == null || hidden == mTabsHidden) {
            return;
        }

        mTabsHidden = hidden;
        mTabsContent.animate().translationY(hidden ? -mTabsCollapseDistance : 0).setDuration(TABS_ANIMATION_MS).start();
        mTabsView.animate().alpha(hidden ? 0 : 1).setDuration(TABS_ANIMATION_MS).start();
    }

    protected VideoGroupPresenter getMainPresenter() {
        return BrowsePresenter.instance(getContext());
    }

    private void setupAdapter() {
        VerticalGridPresenter presenter = new CustomVerticalGridPresenter();
        presenter.setNumberOfColumns(mVideoColsNum);
        presenter.enableChildRoundedCorners(getMainUIData().isUiTweakEnabled(MainUIData.UI_TWEAK_ROUNDED_CORNERS));
        setGridPresenter(presenter);

        if (mTabsAdapter == null) {
            mTabsAdapter = new ArrayObjectAdapter(new TabPresenter());
        }

        if (mEmptyAdapter == null) {
            mEmptyAdapter = new VideoGroupObjectAdapter(mCardPresenter);
            setAdapter(mEmptyAdapter);
        }
    }

    private void setupEventListeners() {
        setOnItemViewClickedListener(new ItemViewClickedListener());
        setOnItemViewSelectedListener(new ItemViewSelectedListener());
        mCardPresenter.setOnItemViewLongPressedListener(new ItemViewLongPressedListener());
        mShortsPresenter.setOnItemViewLongPressedListener(new ItemViewLongPressedListener());
    }

    /**
     * Up from the first grid row goes to the tabs (not to the title bar), down from the tabs goes to the grid.
     */
    private void setupFocus(BrowseFrameLayout frame) {
        frame.setOnFocusSearchListener((focused, direction) -> {
            VerticalGridView grid = getBrowseGrid();

            if (grid == null || mTabsView == null) {
                return null;
            }

            if (direction == View.FOCUS_UP && isDescendant(grid, focused) && grid.getSelectedPosition() < getCurrentColsNum()
                    && mTabsAdapter.size() > 0) {
                return mTabsView;
            }

            if (direction == View.FOCUS_DOWN && isDescendant(mTabsView, focused) && hasGridItems()) {
                return grid;
            }

            return null;
        });

        frame.setOnChildFocusListener(new BrowseFrameLayout.OnChildFocusListener() {
            @Override
            public boolean onRequestFocusInDescendants(int direction, Rect previouslyFocusedRect) {
                // Coming from the title bar: land on the tabs. Coming from the sidebar: back to where the focus was.
                boolean toTabs = direction == View.FOCUS_DOWN || mLastFocusInTabs || !hasGridItems();

                if (toTabs && mTabsView != null && mTabsAdapter.size() > 0) {
                    return mTabsView.requestFocus();
                }

                return getBrowseGrid() != null && getBrowseGrid().requestFocus();
            }

            @Override
            public void onRequestChildFocus(View child, View focused) {
                mLastFocusInTabs = mTabsView != null && isDescendant(mTabsView, focused);
            }
        });
    }

    private static boolean isDescendant(ViewGroup parent, View view) {
        while (view != null) {
            if (view == parent) {
                return true;
            }
            view = view.getParent() instanceof View ? (View) view.getParent() : null;
        }

        return false;
    }

    private void applyPendingUpdates() {
        // prevent modification within update method
        List<VideoGroup> copyArray = new ArrayList<>(mPendingUpdates);

        mPendingUpdates.clear();

        for (VideoGroup group : copyArray) {
            update(group);
        }
    }

    @Override
    public void update(VideoGroup group) {
        if (group == null) {
            return;
        }

        if (mTabsAdapter == null) {
            mPendingUpdates.add(group);
            return;
        }

        // Fix: IllegalStateException: Cannot call this method while RecyclerView is computing a layout or scrolling
        if ((getBrowseGrid() != null && getBrowseGrid().isComputingLayout()) || (mTabsView != null && mTabsView.isComputingLayout())) {
            Utils.post(() -> update(group));
            return;
        }

        int action = group.getAction();

        if (action == VideoGroup.ACTION_REPLACE) {
            if (group.getPosition() == -1) {
                clear();
            } else {
                removeTab(group.getId());
            }
        } else if (action == VideoGroup.ACTION_REMOVE) {
            VideoGroupObjectAdapter adapter = mVideoGroupAdapters.get(group.getId());
            if (adapter != null) {
                adapter.remove(group);
            }
            return;
        } else if (action == VideoGroup.ACTION_REMOVE_AUTHOR) {
            if (group.getVideos() != null && !group.getVideos().isEmpty()) {
                for (VideoGroupObjectAdapter adapter : mVideoGroupAdapters.values()) {
                    adapter.removeAuthor(group);
                }
            }
            return;
        } else if (action == VideoGroup.ACTION_SYNC) {
            VideoGroupObjectAdapter adapter = mVideoGroupAdapters.get(group.getId());
            if (adapter != null) {
                freeze(adapter, true);
                adapter.sync(group);
                freeze(adapter, false);
            }
            return;
        }

        if (group.isEmpty()) {
            return;
        }

        VideoGroupObjectAdapter existingAdapter = GridFragmentHelper.findRelatedAdapter(mVideoGroupAdapters, group, freeze -> freeze(getCurrentAdapter(), freeze));

        if (existingAdapter == null) {
            addTab(group);
        } else {
            Log.d(TAG, "Continue tab %s", group.getTitle());

            freeze(existingAdapter, true);
            existingAdapter.add(group); // continue
            freeze(existingAdapter, false);

            if (existingAdapter == getCurrentAdapter()) {
                fillIfNeeded();
            }
        }
    }

    private void addTab(VideoGroup group) {
        int id = group.getId();

        VideoGroupObjectAdapter adapter = new VideoGroupObjectAdapter(group, group.isShorts() ? mShortsPresenter : mCardPresenter);
        mVideoGroupAdapters.put(id, adapter);

        String title = group.getTitle();
        if (TextUtils.isEmpty(title)) {
            title = getString(R.string.suggestions);
        }

        Tab tab = new Tab(id, title);
        int position = group.getPosition();

        if (position < 0 || position > mTabs.size()) {
            position = mTabs.size();
        }

        mTabs.add(position, tab);
        mTabsAdapter.add(position, tab);

        if (mCurrentTabId == NO_TAB) {
            showTab(mTabs.get(0).id); // show the content as soon as the first row arrives
        }

        if (mPendingTabIndex >= 0 && mPendingTabIndex < mTabs.size()) {
            showTab(mTabs.get(mPendingTabIndex).id); // the tab requested before it was loaded
            mPendingTabIndex = -1;
        }

        syncTabsSelection();
    }

    private void removeTab(int id) {
        int index = findTabIndex(id);

        if (index == -1) {
            return;
        }

        mTabs.remove(index);
        mTabsAdapter.removeItems(index, 1);
        VideoGroupObjectAdapter adapter = mVideoGroupAdapters.remove(id);
        mContinueRequestedSizes.remove(adapter);
        mTabPositions.remove(id);

        if (id == mCurrentTabId) {
            mCurrentTabId = NO_TAB;
            if (mTabs.isEmpty()) {
                setAdapter(mEmptyAdapter, -1);
            } else {
                showTab(mTabs.get(Math.min(index, mTabs.size() - 1)).id);
            }
        }

        syncTabsSelection();
    }

    private int findTabIndex(int id) {
        for (int i = 0; i < mTabs.size(); i++) {
            if (mTabs.get(i).id == id) {
                return i;
            }
        }

        return -1;
    }

    private void onTabFocused(int position) {
        // Selection changes made by the code (inserts, sync) are not user navigation
        if (mTabsView == null || !mTabsView.hasFocus()) {
            return;
        }

        mFocusedTabIndex = position;

        // Don't swap the grid on every tab passed while moving fast along the tabs
        Utils.removeCallbacks(mSwitchToFocusedTab);
        Utils.postDelayed(mSwitchToFocusedTab, TAB_SWITCH_DELAY_MS);
    }

    private void switchToFocusedTab() {
        if (mFocusedTabIndex >= 0 && mFocusedTabIndex < mTabs.size()) {
            mPendingTabIndex = -1; // the user picked a tab
            showTab(mTabs.get(mFocusedTabIndex).id);
        }
    }

    private void showTab(int id) {
        if (id == mCurrentTabId) {
            return;
        }

        VideoGroupObjectAdapter adapter = mVideoGroupAdapters.get(id);

        if (adapter == null) {
            return;
        }

        // Remember where we were in the tab we're leaving
        if (mCurrentTabId != NO_TAB && getBrowseGrid() != null) {
            mTabPositions.put(mCurrentTabId, getBrowseGrid().getSelectedPosition());
        }

        mCurrentTabId = id;

        Integer savedPosition = mTabPositions.get(id);
        int position = savedPosition != null ? Math.min(savedPosition, adapter.size() - 1) : 0;

        if (getBrowseGrid() != null) {
            getBrowseGrid().setNumColumns(isShortsTab(adapter) ? mShortsColsNum : mVideoColsNum);
        }

        setAdapter(adapter, Math.max(position, 0));
        updateTabsActivatedState();
        fillIfNeeded();
    }

    private void updateTabsActivatedState() {
        if (mTabsView == null) {
            return;
        }

        for (int i = 0; i < mTabsView.getChildCount(); i++) {
            View child = mTabsView.getChildAt(i);
            int position = mTabsView.getChildAdapterPosition(child);
            child.setActivated(position >= 0 && position < mTabs.size() && mTabs.get(position).id == mCurrentTabId);
        }
    }

    /**
     * Keep the tabs strip selection on the shown tab, so focus comes back to it.
     */
    private void syncTabsSelection() {
        if (mTabsView == null) {
            return;
        }

        int index = findTabIndex(mCurrentTabId);

        if (index != -1 && !mTabsView.hasFocus()) {
            mTabsView.setSelectedPosition(index);
        }

        updateTabsActivatedState();
    }

    private VideoGroupObjectAdapter getCurrentAdapter() {
        return mVideoGroupAdapters.get(mCurrentTabId);
    }

    private boolean isShortsTab(VideoGroupObjectAdapter adapter) {
        if (adapter == null || adapter.isEmpty()) {
            return false;
        }

        VideoGroup group = ((Video) adapter.get(0)).getGroup();

        return group != null && group.isShorts();
    }

    private int getCurrentColsNum() {
        return isShortsTab(getCurrentAdapter()) ? mShortsColsNum : mVideoColsNum;
    }

    private boolean hasGridItems() {
        VideoGroupObjectAdapter adapter = getCurrentAdapter();
        return adapter != null && !adapter.isEmpty();
    }

    /**
     * A tab with less than a few rows of cards can't be scrolled. Fetch the next page right away.
     */
    private void fillIfNeeded() {
        VideoGroupObjectAdapter adapter = getCurrentAdapter();

        if (adapter != null && adapter.size() < getCurrentColsNum() * MIN_FILLED_ROWS) {
            continueTab(adapter);
        }
    }

    private void continueTab(VideoGroupObjectAdapter adapter) {
        if (adapter == null || adapter.isEmpty()) {
            return;
        }

        int size = adapter.size();
        Integer requestedSize = mContinueRequestedSizes.get(adapter);

        if (requestedSize != null && requestedSize >= size) {
            return; // the page is already requested (or the tab has no more pages)
        }

        mContinueRequestedSizes.put(adapter, size);
        mMainPresenter.onScrollEnd((Video) adapter.get(size - 1));
    }

    @Override
    public int getPosition() {
        return findTabIndex(mCurrentTabId);
    }

    @Override
    public void setPosition(int index) {
        if (index < 0) {
            return;
        }

        if (index < mTabs.size()) {
            showTab(mTabs.get(index).id);
            syncTabsSelection();
            mPendingTabIndex = -1;
        } else {
            mPendingTabIndex = index;
        }
    }

    @Override
    public void selectItem(Video item) {
        // NOP (same as the rows layout)
    }

    @Override
    public void clear() {
        Utils.removeCallbacks(mSwitchToFocusedTab);

        if (mTabsAdapter != null) {
            setAdapter(mEmptyAdapter, -1);
            mTabsAdapter.clear();
        }

        mTabs.clear();
        mVideoGroupAdapters.clear();
        mTabPositions.clear();
        mContinueRequestedSizes.clear();
        mCurrentTabId = NO_TAB;
        mFocusedTabIndex = -1;
        mLastFocusInTabs = false;
    }

    @Override
    public boolean isEmpty() {
        if (mTabsAdapter == null) {
            return mPendingUpdates.isEmpty();
        }

        return mTabs.isEmpty();
    }

    /**
     * Disable scrolling on partially updated grid. This prevent cards from misbehaving.
     */
    private void freeze(VideoGroupObjectAdapter adapter, boolean freeze) {
        if (adapter != null && adapter == getCurrentAdapter() && getBrowseGrid() != null) {
            getBrowseGrid().setScrollEnabled(!freeze);
            getBrowseGrid().setAnimateChildLayout(!freeze);
        }
    }

    private MainUIData getMainUIData() {
        return MainUIData.instance(getContext());
    }

    private final class TabPresenter extends Presenter {
        @Override
        public ViewHolder onCreateViewHolder(ViewGroup parent) {
            TextView view = (TextView) LayoutInflater.from(parent.getContext()).inflate(R.layout.tab_item, parent, false);
            view.setOnClickListener(v -> {
                // OK on a tab: show it now and jump into its videos
                Utils.removeCallbacks(mSwitchToFocusedTab);
                if (mTabsView != null) {
                    int position = mTabsView.getChildAdapterPosition(v);
                    if (position >= 0 && position < mTabs.size()) {
                        showTab(mTabs.get(position).id);
                    }
                }
                if (hasGridItems() && getBrowseGrid() != null) {
                    getBrowseGrid().requestFocus();
                }
            });
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(ViewHolder viewHolder, Object item) {
            Tab tab = (Tab) item;
            ((TextView) viewHolder.view).setText(tab.title);
            viewHolder.view.setActivated(tab.id == mCurrentTabId);
        }

        @Override
        public void onUnbindViewHolder(ViewHolder viewHolder) {
            // NOP
        }
    }

    private final class ItemViewLongPressedListener implements OnItemLongPressedListener {
        @Override
        public void onItemLongPressed(Presenter.ViewHolder itemViewHolder, Object item) {
            if (item instanceof Video) {
                mMainPresenter.onVideoItemLongClicked((Video) item);
            } else {
                Toast.makeText(getActivity(), item.toString(), Toast.LENGTH_SHORT).show();
            }
        }
    }

    private final class ItemViewClickedListener implements androidx.leanback.widget.OnItemViewClickedListener {
        @Override
        public void onItemClicked(Presenter.ViewHolder itemViewHolder, Object item,
                                  RowPresenter.ViewHolder rowViewHolder, Row row) {
            if (item instanceof Video) {
                mMainPresenter.onVideoItemClicked((Video) item);
            } else {
                Toast.makeText(getActivity(), item.toString(), Toast.LENGTH_SHORT).show();
            }
        }
    }

    private final class ItemViewSelectedListener implements OnItemViewSelectedListener {
        @Override
        public void onItemSelected(Presenter.ViewHolder itemViewHolder, Object item,
                                   RowPresenter.ViewHolder rowViewHolder, Row row) {
            if (item instanceof Video) {
                mBackgroundManager.setBackgroundFrom((Video) item);

                mMainPresenter.onVideoItemSelected((Video) item);

                checkScrollEnd((Video) item);
            }

            updateTabsVisibility();
        }

        private void checkScrollEnd(Video item) {
            VideoGroupObjectAdapter adapter = getCurrentAdapter();

            if (adapter == null) {
                return;
            }

            int size = adapter.size();
            int index = adapter.indexOf(item);
            int threshold = Math.max(ViewUtil.GRID_SCROLL_CONTINUE_NUM, getCurrentColsNum() * 2);

            if (index != -1 && index > (size - threshold)) {
                continueTab(adapter);
            }
        }
    }
}
