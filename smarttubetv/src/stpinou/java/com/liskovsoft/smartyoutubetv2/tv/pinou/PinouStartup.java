package com.liskovsoft.smartyoutubetv2.tv.pinou;

import android.app.Activity;
import android.app.Application;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build.VERSION;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.liskovsoft.sharedutils.helpers.MessageHelpers;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.manager.PlayerUI;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.AppDialogPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.PlaybackPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.views.PlaybackView;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.FormatItem;
import com.liskovsoft.smartyoutubetv2.common.prefs.PlayerData;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.liskovsoft.smartyoutubetv2.tv.ui.playback.PlaybackActivity;

import java.util.List;

/**
 * PinouTube additions that need no change to SmartTube's classes, started with the app (providers are created
 * before the application): while the player is on screen, the remote's mic turns the subtitles on and off,
 * exactly like the player's subtitles button.
 */
public class PinouStartup extends ContentProvider implements Application.ActivityLifecycleCallbacks {
    private MicButton mMicButton;

    @Override
    public boolean onCreate() {
        Context context = getContext();

        if (context != null && context.getApplicationContext() instanceof Application) {
            mMicButton = new MicButton(context, this::toggleSubtitles);
            ((Application) context.getApplicationContext()).registerActivityLifecycleCallbacks(this);
        }

        return true;
    }

    @Override
    public void onActivityResumed(@NonNull Activity activity) {
        if (activity instanceof PlaybackActivity) {
            mMicButton.listen(true);
        }
    }

    @Override
    public void onActivityPaused(@NonNull Activity activity) {
        // In picture-in-picture another app is in front: the mic is the assistant again
        if (activity instanceof PlaybackActivity && VERSION.SDK_INT >= 24 && activity.isInPictureInPictureMode()) {
            mMicButton.listen(false);
        }
    }

    @Override
    public void onActivityStopped(@NonNull Activity activity) {
        if (activity instanceof PlaybackActivity) {
            mMicButton.listen(false);
        }
    }

    private void toggleSubtitles() {
        Context context = getContext();
        PlaybackPresenter presenter = PlaybackPresenter.instance(context);
        PlaybackView player = presenter.getPlayer();

        if (player == null) {
            return;
        }

        List<FormatItem> subtitles = player.getSubtitleFormats();
        if (subtitles == null || subtitles.size() <= 1) { // only "off" in the list
            MessageHelpers.showMessage(context, context.getString(R.string.pinou_no_subtitles));
            return;
        }

        presenter.onButtonClicked(R.id.lb_control_closed_captioning, isShown(subtitles) ? PlayerUI.BUTTON_ON : PlayerUI.BUTTON_OFF);

        // The first time, or when no recent language exists in this video, the button opens the subtitles menu instead
        if (AppDialogPresenter.instance(context).isDialogShown()) {
            return;
        }

        FormatItem format = PlayerData.instance(context).getFormat(FormatItem.TYPE_SUBTITLE);
        MessageHelpers.showMessage(context, format == null || format.isDefault() ?
                context.getString(R.string.pinou_subtitles_off) : context.getString(R.string.pinou_subtitles_on, format.getTitle()));
    }

    private static boolean isShown(List<FormatItem> subtitles) {
        for (FormatItem subtitle : subtitles) {
            if (subtitle.isSelected() && !subtitle.isDefault()) {
                return true;
            }
        }

        return false;
    }

    @Override
    public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle savedInstanceState) {
    }

    @Override
    public void onActivityStarted(@NonNull Activity activity) {
    }

    @Override
    public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle outState) {
    }

    @Override
    public void onActivityDestroyed(@NonNull Activity activity) {
    }

    @Nullable
    @Override
    public Cursor query(@NonNull Uri uri, @Nullable String[] projection, @Nullable String selection, @Nullable String[] selectionArgs, @Nullable String sortOrder) {
        return null;
    }

    @Nullable
    @Override
    public String getType(@NonNull Uri uri) {
        return null;
    }

    @Nullable
    @Override
    public Uri insert(@NonNull Uri uri, @Nullable ContentValues values) {
        return null;
    }

    @Override
    public int delete(@NonNull Uri uri, @Nullable String selection, @Nullable String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(@NonNull Uri uri, @Nullable ContentValues values, @Nullable String selection, @Nullable String[] selectionArgs) {
        return 0;
    }
}
