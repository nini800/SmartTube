package com.liskovsoft.smartyoutubetv2.tv.ui.channeluploads;

import android.os.Bundle;
import android.view.Menu;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.PopupMenu;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.leanback.widget.BrowseFrameLayout;
import androidx.leanback.widget.VerticalGridView;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.ChannelUploadsPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.interfaces.VideoGroupPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.views.ChannelUploadsView;
import com.liskovsoft.smartyoutubetv2.common.misc.PlaylistSorter;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.liskovsoft.smartyoutubetv2.tv.ui.browse.video.VideoGridFragment;
import com.liskovsoft.smartyoutubetv2.tv.ui.mod.leanback.misc.ProgressBarManager;

public class ChannelUploadsFragment extends VideoGridFragment implements ChannelUploadsView {
    private static final String SELECTED_ITEM_INDEX = "SelectedItemIndex";
    private ProgressBarManager mProgressBarManager;
    private ChannelUploadsPresenter mChannelUploadsPresenter;
    private boolean mIsFragmentCreated;
    private int mRestoredItemIndex = -1;
    private static final long HEADER_ANIMATION_MS = 200;
    private View mHeader;
    private View mContent;
    private TextView mTitleView;
    private TextView mSortTypeView;
    private TextView mSortOrderView;
    private int mHeaderCollapseDistance;
    private boolean mHeaderHidden;
    private final ViewTreeObserver.OnGlobalFocusChangeListener mFocusChangeListener = (oldFocus, newFocus) -> updateHeaderVisibility();

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(null); // Real restore takes place in the presenter

        mRestoredItemIndex = savedInstanceState != null ? savedInstanceState.getInt(SELECTED_ITEM_INDEX, -1) : -1;
        mIsFragmentCreated = true;
        mChannelUploadsPresenter = ChannelUploadsPresenter.instance(getContext());
        mChannelUploadsPresenter.setView(this);

        mProgressBarManager = new ProgressBarManager();
    }

    @Override
    protected VideoGroupPresenter getMainPresenter() {
        return ChannelUploadsPresenter.instance(getContext());
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);

        // Not robust. Because tab content often changed after reloading.
        outState.putInt(SELECTED_ITEM_INDEX, getPosition());
    }

    @Override
    public void onActivityCreated(@Nullable Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);

        // Don't move to onCreateView
        mProgressBarManager.setRootView((ViewGroup) getActivity().findViewById(android.R.id.content).getRootView());

        mChannelUploadsPresenter.onViewInitialized();

        // Restore state after crash
        setPosition(mRestoredItemIndex);
        mRestoredItemIndex = -1;
    }

    @Override
    protected int getLayoutResId() {
        return R.layout.fragment_playlist_grid;
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        removeTopPadding();
        setupHeader(view);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (getView() != null) {
            getView().getViewTreeObserver().removeOnGlobalFocusChangeListener(mFocusChangeListener);
        }
        mHeader = null;
        mContent = null;
        mTitleView = null;
        mSortTypeView = null;
        mSortOrderView = null;
        mHeaderHidden = false;
    }

    /**
     * Playlist header: title, sort type (dropdown) and sort direction (arrow). Hidden for channels.
     */
    private void setupHeader(View root) {
        mHeader = root.findViewById(R.id.playlist_header);
        mContent = root.findViewById(R.id.playlist_content);
        mTitleView = root.findViewById(R.id.playlist_title);
        mSortTypeView = root.findViewById(R.id.playlist_sort_type);
        mSortOrderView = root.findViewById(R.id.playlist_sort_order);

        mSortTypeView.setOnClickListener(v -> showSortMenu());
        mSortOrderView.setOnClickListener(v -> {
            PlaylistSorter.Sort sort = mChannelUploadsPresenter.getSort();
            mChannelUploadsPresenter.setSort(new PlaylistSorter.Sort(sort.type, !sort.descending));
        });

        // Up from the first grid row goes to the header, down from the header goes to the grid
        ((BrowseFrameLayout) root.findViewById(R.id.grid_frame)).setOnFocusSearchListener((focused, direction) -> {
            VerticalGridView grid = getBrowseGrid();

            if (grid == null || mHeader == null || mHeader.getVisibility() != View.VISIBLE) {
                return null;
            }

            if (direction == View.FOCUS_UP && isDescendant(grid, focused) && grid.getSelectedPosition() < getColsNum()) {
                return mSortTypeView;
            }

            if (direction == View.FOCUS_DOWN && isDescendant((ViewGroup) mHeader, focused) && grid.getAdapter() != null
                    && grid.getAdapter().getItemCount() > 0) {
                return grid;
            }

            return null;
        });

        // Make the content taller than the screen by the header, so the grid fills the screen once the header slides up
        root.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            if (mHeader == null || mContent == null) {
                return;
            }

            mHeaderCollapseDistance = mHeader.getVisibility() == View.VISIBLE ? mHeader.getBottom() : 0;
            int height = bottom - top + mHeaderCollapseDistance;
            ViewGroup.LayoutParams params = mContent.getLayoutParams();

            if (params.height != height) {
                params.height = height;
                v.post(() -> {
                    if (mContent != null) {
                        mContent.setLayoutParams(params);
                    }
                });
            }
        });

        root.getViewTreeObserver().addOnGlobalFocusChangeListener(mFocusChangeListener);

        updateSortHeader();
    }

    @Override
    public void updateSortHeader() {
        if (mHeader == null) {
            return;
        }

        boolean sortable = mChannelUploadsPresenter.isSortable();
        mHeader.setVisibility(sortable ? View.VISIBLE : View.GONE);

        if (!sortable) {
            setHeaderHidden(false);
            return;
        }

        PlaylistSorter.Sort sort = mChannelUploadsPresenter.getSort();
        mTitleView.setText(mChannelUploadsPresenter.getChannel().getTitle());
        mSortTypeView.setText(getString(getSortName(sort.type)) + "  ▾");
        mSortOrderView.setText(sort.descending ? "↓" : "↑");
        mSortOrderView.setContentDescription(getString(sort.descending ? R.string.playlist_sort_descending : R.string.playlist_sort_ascending));
    }

    private void showSortMenu() {
        if (getContext() == null || mSortTypeView == null) {
            return;
        }

        PlaylistSorter.Sort current = mChannelUploadsPresenter.getSort();
        PopupMenu popup = new PopupMenu(getContext(), mSortTypeView);
        Menu menu = popup.getMenu();

        for (int i = 0; i < PlaylistSorter.SORT_TYPES.length; i++) {
            int type = PlaylistSorter.SORT_TYPES[i];
            menu.add(0, type, i, getSortName(type)).setChecked(type == current.type);
        }

        menu.setGroupCheckable(0, true, true);
        popup.setOnMenuItemClickListener(item -> {
            int type = item.getItemId();
            if (type != current.type) {
                mChannelUploadsPresenter.setSort(new PlaylistSorter.Sort(type, PlaylistSorter.isDescendingByDefault(type)));
            }
            return true;
        });
        popup.show();
    }

    private static int getSortName(int type) {
        switch (type) {
            case PlaylistSorter.SORT_UPLOAD_DATE:
                return R.string.playlist_sort_upload_date;
            case PlaylistSorter.SORT_TITLE:
                return R.string.playlist_sort_title;
            case PlaylistSorter.SORT_CHANNEL:
                return R.string.playlist_sort_channel;
            case PlaylistSorter.SORT_DURATION:
                return R.string.playlist_sort_duration;
            case PlaylistSorter.SORT_VIEWS:
                return R.string.playlist_sort_views;
            default:
                return R.string.playlist_sort_playlist_order;
        }
    }

    /**
     * The header slides away while scrolling down, and comes back on the first row or when the focus leaves the grid.
     */
    private void updateHeaderVisibility() {
        VerticalGridView grid = getBrowseGrid();

        setHeaderHidden(grid != null && grid.hasFocus() && grid.getSelectedPosition() >= getColsNum());
    }

    private void setHeaderHidden(boolean hidden) {
        if (mContent == null || hidden == mHeaderHidden) {
            return;
        }

        mHeaderHidden = hidden;
        mContent.animate().translationY(hidden ? -mHeaderCollapseDistance : 0).setDuration(HEADER_ANIMATION_MS).start();
    }

    private int getColsNum() {
        return getGridPresenter() != null ? Math.max(getGridPresenter().getNumberOfColumns(), 1) : 1;
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

    @Override
    public void onDestroy() {
        super.onDestroy();
        mChannelUploadsPresenter.onViewDestroyed();
    }

    public void onFinish() {
        mChannelUploadsPresenter.onFinish();
    }

    @Override
    public void onResume() {
        super.onResume();

        if (!mIsFragmentCreated) {
            mChannelUploadsPresenter.onViewResumed();
        }

        mIsFragmentCreated = false;
    }

    @Override
    public void showProgressBar(boolean show) {
        if (show) {
            mProgressBarManager.show();
        } else {
            mProgressBarManager.hide();
        }
    }

    /**
     * https://stackoverflow.com/questions/9685658/add-padding-on-view-programmatically
     */
    private void removeTopPadding() {
        VerticalGridView browseGrid = getBrowseGrid();

        if (browseGrid == null) {
            return;
        }

        browseGrid.setPadding(browseGrid.getPaddingLeft(), browseGrid.getPaddingTop() / 3, browseGrid.getPaddingRight(), browseGrid.getPaddingBottom());
    }
}
