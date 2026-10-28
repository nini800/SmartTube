package com.liskovsoft.smartyoutubetv2.common.app.presenters;

import android.annotation.SuppressLint;
import android.content.Context;
import android.text.TextUtils;

import com.liskovsoft.mediaserviceinterfaces.data.MediaGroup;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItem;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemMetadata;
import com.liskovsoft.sharedutils.helpers.Helpers;
import com.liskovsoft.sharedutils.helpers.MessageHelpers;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.sharedutils.rx.RxHelper;
import com.liskovsoft.smartyoutubetv2.common.R;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.SimpleMediaItem;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.VideoGroup;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.base.BasePresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.dialogs.VideoActionPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.dialogs.menu.VideoMenuPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.dialogs.menu.VideoMenuPresenter.VideoMenuCallback;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.interfaces.VideoGroupPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.views.ChannelUploadsView;
import com.liskovsoft.smartyoutubetv2.common.misc.BrowseProcessorManager;
import com.liskovsoft.smartyoutubetv2.common.misc.MediaServiceManager;
import com.liskovsoft.smartyoutubetv2.common.misc.MediaServiceManager.OnComplete;
import com.liskovsoft.smartyoutubetv2.common.misc.MediaServiceManager.OnError;
import com.liskovsoft.smartyoutubetv2.common.misc.MediaServiceManager.OnMediaGroup;
import com.liskovsoft.smartyoutubetv2.common.misc.PlaylistSorter;

import io.reactivex.Observable;
import io.reactivex.disposables.Disposable;

import java.util.List;

public class ChannelUploadsPresenter extends BasePresenter<ChannelUploadsView> implements VideoGroupPresenter {
    private static final String TAG = ChannelUploadsPresenter.class.getSimpleName();
    @SuppressLint("StaticFieldLeak")
    private static ChannelUploadsPresenter sInstance;
    private final BrowseProcessorManager mBrowseProcessor;
    private Disposable mUpdateAction;
    private Disposable mScrollAction;
    private Video mChannel;
    private MediaGroup mPendingGroup;
    private VideoGroup mBaseGroup;
    private boolean mAllLoaded; // sorted playlist: every page is already shown

    public ChannelUploadsPresenter(Context context) {
        super(context);
        mBrowseProcessor = new BrowseProcessorManager(getContext(), this::syncItem);
    }

    public static ChannelUploadsPresenter instance(Context context) {
        if (sInstance == null) {
            sInstance = new ChannelUploadsPresenter(context);
        }

        sInstance.setContext(context);

        return sInstance;
    }

    @Override
    public void onViewInitialized() {
        super.onViewInitialized();

        refresh();
    }

    @Override
    public void onViewDestroyed() {
        super.onViewDestroyed();
        disposeActions();
    }

    @Override
    public void onFinish() {
        super.onFinish();

        // Destroy the cache only (!) when user pressed back (e.g. wants to explicitly kill the activity)
        // Otherwise keep the cache to easily restore in case activity is killed by the system.
        disposeActions();
        mChannel = null;
        mPendingGroup = null;
        mBaseGroup = null;
        mAllLoaded = false;
    }

    @Override
    public void onVideoItemSelected(Video item) {
        // NOP
    }

    @Override
    public void onVideoItemClicked(Video item) {
        VideoActionPresenter.instance(getContext()).apply(item);
    }

    @Override
    public void onVideoItemLongClicked(Video item) {
        VideoMenuPresenter.instance(getContext()).showMenu(item, (videoItem, action) -> {
            if (action == VideoMenuCallback.ACTION_REMOVE_FROM_PLAYLIST) {
                removeItem(videoItem);
            } else if (action == VideoMenuCallback.ACTION_UNSUBSCRIBE) {
                MessageHelpers.showMessage(getContext(), R.string.unsubscribed_from_channel);
            }
        });
    }

    @Override
    public void onScrollEnd(Video item) {
        if (item == null) {
            Log.e(TAG, "Can't scroll. Video is null.");
            return;
        }

        VideoGroup group = item.getGroup();

        if (group == null) {
            Log.e(TAG, "Can't scroll. VideoGroup is null.");
            return;
        }

        Log.d(TAG, "onScrollEnd: Group title: " + group.getTitle());

        if (mAllLoaded) {
            return;
        }

        boolean scrollInProgress = mScrollAction != null && !mScrollAction.isDisposed();

        if (!scrollInProgress) {
            continueGroup(group);
        }
    }

    @Override
    public boolean hasPendingActions() {
        return RxHelper.isAnyActionRunning(mScrollAction, mUpdateAction);
    }

    public void openChannel(Video item) {
        // Working with uploads or playlists
        if (item == null || (!item.hasNestedItems() && !item.hasPlaylist())) {
            return;
        }

        clear();

        mChannel = item;

        getViewManager().startView(ChannelUploadsView.class);

        if (getView() != null) {
            getView().updateSortHeader();
            update(item);
        }
    }

    public void obtainGroup(Video item, OnMediaGroup callback) {
        obtainGroup(item, callback, null, null);
    }

    public void obtainGroup(Video item, OnMediaGroup callback, OnError onError, OnComplete onComplete) {
        if (item != null && item.mediaItem != null) {
            obtainGroup(item.mediaItem, callback, onError, onComplete);
        }
    }

    public Observable<MediaGroup> obtainUploadsObservable(Video item) {
        if (item == null) {
            return null;
        }

        if (item.mediaItem == null) {
            item.mediaItem = SimpleMediaItem.from(item);
        }

        disposeActions();

        return item.hasNestedItems() || item.isChannel() ?
               getContentService().getGroupObserve(item.mediaItem != null ? item.mediaItem : SimpleMediaItem.from(item)) :
               item.hasReloadPageKey() ?
               getContentService().getGroupObserve(item.getReloadPageKey()) :
               getMediaItemService().getMetadataObserve(item.videoId, item.playlistId, 0, item.playlistParams)
                       .flatMap(mediaItemMetadata -> Observable.just(findPlaylistRow(mediaItemMetadata)));
    }

    public Video getChannel() {
        return mChannel;
    }

    public void setChannel(Video channel) {
        mChannel = channel;
    }

    private void disposeActions() {
        RxHelper.disposeActions(mUpdateAction, mScrollAction);
        MediaServiceManager.instance().disposeActions();
        mBrowseProcessor.dispose();
    }

    private void continueGroup(VideoGroup group) {
        disposeActions();

        if (getView() == null) {
            Log.e(TAG, "Can't continue group. The view is null.");
            return;
        }

        if (group == null) {
            Log.e(TAG, "Can't continue group. The group is null.");
            return;
        }

        Log.d(TAG, "continueGroup: start continue group: " + group.getTitle());

        getView().showProgressBar(true);

        MediaGroup mediaGroup = group.getMediaGroup();

        Observable<MediaGroup> continuation;

        continuation = getContentService().continueGroupObserve(mediaGroup);

        mScrollAction = continuation
                .subscribe(
                        continueMediaGroup -> {
                            VideoGroup newGroup = VideoGroup.from(group, continueMediaGroup);
                            getView().update(newGroup);
                            mBrowseProcessor.process(newGroup);
                        },
                        error -> {
                            Log.e(TAG, "continueGroup error: %s", error.getMessage());
                            if (getView() != null) {
                                getView().showProgressBar(false);
                            }
                        },
                        () -> getView().showProgressBar(false)
                );
    }

    private void update(Video item) {
        mAllLoaded = false;

        if (isSortable() && !getSort().isDefault()) {
            updateSorted(obtainFirstPage(item), item.getTitle());
            return;
        }

        // Playlists from the Playlists section: loaded like MediaServiceManager.chooseChannelPresenter does
        if (isPlaylistChannel(item)) {
            update(obtainFirstPage(item));
            return;
        }

        // Liked music fix - not all videos displayed. The behavior with other playlists is buggy.
        if (Helpers.equals(item.playlistId, Video.PLAYLIST_LIKED_MUSIC)) {
            update(item.getGroup());
        } else {
            update(obtainUploadsObservable(item));
        }
    }

    private void update(Observable<MediaGroup> group) {
        Log.d(TAG, "update: Start loading a group...");

        disposeActions();

        getView().showProgressBar(true);

        mUpdateAction = group
                .subscribe(
                        this::update,
                        error -> {
                            Log.e(TAG, "update error: %s", error.getMessage());
                            getView().showProgressBar(false);
                        },
                        () -> getView().showProgressBar(false)
                );
    }

    public void update(MediaGroup mediaGroup) {
        // The view could be running in the background
        getViewManager().startView(ChannelUploadsView.class);

        if (getView() == null) { // starting from outside (e.g. MediaServiceManager)
            mPendingGroup = mediaGroup; // start loading from this group
            return;
        }

        // First page from outside (MediaServiceManager): a sorted playlist needs every page first
        if (mBaseGroup == null && isSortable() && !getSort().isDefault()) {
            getView().updateSortHeader();
            updateSorted(Observable.just(mediaGroup), mChannel.getTitle());
            return;
        }

        if (mBaseGroup == null) {
            getView().updateSortHeader();
        }

        mBaseGroup = mBaseGroup != null ? VideoGroup.from(mBaseGroup, mediaGroup) : VideoGroup.from(mediaGroup);
        if (mChannel != null && TextUtils.isEmpty(mBaseGroup.getTitle())) {
            mBaseGroup.setTitle(mChannel.getTitle());
        }
        update(mBaseGroup);
    }

    private void update(VideoGroup group) {
        disposeActions();

        if (getView() == null || group == null) {
            return;
        }

        getView().update(group);
        mBrowseProcessor.process(group);

        // Hide loading as long as first group received
        if (!group.isEmpty()) {
            getView().showProgressBar(false);
        }
    }

    private void obtainGroup(MediaItem mediaItem, OnMediaGroup callback, OnError onError, OnComplete onComplete) {
        Log.d(TAG, "obtainGroup: Start loading group...");

        disposeActions();

        mUpdateAction = obtainUploadsObservable(Video.from(mediaItem))
                .subscribe(
                        callback::onMediaGroup,
                        error -> {
                            Log.e(TAG, "obtainGroup error: %s", error.getMessage());
                            if (onError != null) {
                                onError.onError(error);
                            }
                        },
                        () -> {
                            if (onComplete != null) {
                                onComplete.onComplete();
                            }
                        }
                );
    }

    /**
     * Playlist usually is the first row with media items.<br/>
     * NOTE: before playlist may be the video description row
     */
    private MediaGroup findPlaylistRow(MediaItemMetadata mediaItemMetadata) {
        if (mediaItemMetadata == null || mediaItemMetadata.getSuggestions() == null) {
            return null;
        }

        for (MediaGroup group : mediaItemMetadata.getSuggestions()) {
            List<MediaItem> mediaItems = group.getMediaItems();
            if (mediaItems != null && !mediaItems.isEmpty()) {
                return group;
            }
        }

        return null;
    }

    public void clear() {
        disposeActions();
        if (getView() != null) {
            getView().clear();
        }
        mChannel = null;
        mPendingGroup = null;
        mBaseGroup = null;
        mAllLoaded = false;
    }

    /**
     * The opened item is a playlist (not a channel): its sort can be changed.
     */
    public boolean isSortable() {
        return getSortKey() != null;
    }

    public PlaylistSorter.Sort getSort() {
        String key = getSortKey();
        return key != null ? PlaylistSorter.getSort(getContext(), key) : new PlaylistSorter.Sort(PlaylistSorter.SORT_PLAYLIST_ORDER, false);
    }

    public void setSort(PlaylistSorter.Sort sort) {
        String key = getSortKey();

        if (key == null || getView() == null) {
            return;
        }

        PlaylistSorter.setSort(getContext(), key, sort);
        getView().updateSortHeader();

        // Reload from YouTube: the playlist could have changed since it was opened
        disposeActions();
        getView().clear();
        mBaseGroup = null;
        mPendingGroup = null;
        update(mChannel);
    }

    private String getSortKey() {
        if (mChannel == null) {
            return null;
        }

        if (!TextUtils.isEmpty(mChannel.playlistId)) {
            return mChannel.playlistId;
        }

        // Playlists section: the playlist comes as a "channel" whose id is VL + playlist id
        if (isPlaylistChannel(mChannel)) {
            return mChannel.channelId.substring(2);
        }

        if (mChannel.isChannel()) {
            return null;
        }

        boolean isPlaylist = mChannel.mediaItem != null && mChannel.mediaItem.getType() == MediaItem.TYPE_PLAYLIST;

        return isPlaylist && !TextUtils.isEmpty(mChannel.getTitle()) ? "title:" + mChannel.getTitle() : null;
    }

    private static boolean isPlaylistChannel(Video item) {
        return item != null && TextUtils.isEmpty(item.playlistId) && item.channelId != null
                && item.channelId.startsWith("VL") && item.channelId.length() > 2;
    }

    /**
     * First page of the opened item. A Playlists section playlist loads through the channel api, like
     * {@link MediaServiceManager#chooseChannelPresenter}, because that's the call known to work for it.
     */
    private Observable<MediaGroup> obtainFirstPage(Video item) {
        if (!isPlaylistChannel(item)) {
            return obtainUploadsObservable(item);
        }

        disposeActions();

        Observable<List<MediaGroup>> rows = item.mediaItem != null ?
                getContentService().getChannelObserve(item.mediaItem) : getContentService().getChannelObserve(item.channelId);

        return rows
                .filter(groups -> groups != null && !groups.isEmpty())
                .map(groups -> groups.get(0));
    }

    /**
     * Loads every remaining page after the last shown item, then runs onLoaded (the view scrolls to the end).
     */
    public void loadToEnd(Video lastItem, Runnable onLoaded) {
        VideoGroup start = lastItem != null ? lastItem.getGroup() : null;

        if (mAllLoaded || start == null || start.getMediaGroup() == null || getView() == null) {
            onLoaded.run();
            return;
        }

        disposeActions();
        getView().showProgressBar(true);

        VideoGroup[] last = {start};

        mScrollAction = RxHelper.<MediaGroup>create(emitter -> {
                    MediaGroup page = start.getMediaGroup();

                    for (int i = 0; i < PlaylistSorter.MAX_PAGES && page != null && !emitter.isDisposed(); i++) {
                        page = getContentService().continueGroup(page);

                        if (page == null || page.isEmpty()) {
                            break;
                        }

                        emitter.onNext(page);
                    }

                    emitter.onComplete();
                })
                .subscribe(
                        page -> {
                            if (getView() == null) {
                                return;
                            }
                            last[0] = VideoGroup.from(last[0], page);
                            getView().update(last[0]);
                            mBrowseProcessor.process(last[0]);
                        },
                        error -> {
                            Log.e(TAG, "loadToEnd error: %s", error.getMessage());
                            if (getView() != null) {
                                getView().showProgressBar(false);
                                onLoaded.run();
                            }
                        },
                        () -> {
                            mAllLoaded = true;
                            if (getView() != null) {
                                getView().showProgressBar(false);
                                onLoaded.run();
                            }
                        }
                );
    }

    /**
     * YouTube can't sort playlists on TV: load every page, then show them sorted.
     */
    private void updateSorted(Observable<MediaGroup> firstPage, String title) {
        if (firstPage == null || getView() == null) {
            return;
        }

        PlaylistSorter.Sort sort = getSort();

        disposeActions();
        getView().showProgressBar(true);

        mUpdateAction = firstPage
                .flatMap(group -> RxHelper.fromCallable(() -> PlaylistSorter.loadAllSorted(getContentService(), group, title, sort)))
                .subscribe(
                        sorted -> {
                            if (getView() == null) {
                                return;
                            }
                            mAllLoaded = true;
                            mBaseGroup = sorted;
                            getView().clear();
                            update(sorted);
                            getView().showProgressBar(false);
                        },
                        error -> {
                            Log.e(TAG, "updateSorted error: %s", error.getMessage());
                            if (getView() != null) {
                                getView().showProgressBar(false);
                            }
                        },
                        () -> {
                            if (getView() != null) {
                                getView().showProgressBar(false);
                            }
                        }
                );
    }

    public void refresh() {
        if (getView() == null) {
            return;
        }

        getView().updateSortHeader();
        mBaseGroup = null; // the view is cleared below
        mAllLoaded = false;

        if (mPendingGroup != null) {
            getView().clear();
            update(mPendingGroup);
        } else if (mChannel != null) {
            getView().clear();
            update(mChannel);
        }
    }
}
