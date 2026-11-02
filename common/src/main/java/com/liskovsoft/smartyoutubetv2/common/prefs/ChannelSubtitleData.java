package com.liskovsoft.smartyoutubetv2.common.prefs;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.Nullable;

import com.liskovsoft.sharedutils.helpers.Helpers;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.other.SubtitleManager.SubtitleStyle;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.ExoFormatItem;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.FormatItem;

import java.util.List;

/**
 * Subtitle settings remembered per channel: on/off, language, style, size and position.<br/>
 * A channel gets its own settings the first time its subtitles are changed in the player.
 * Channels without them keep the last used settings, as before.
 */
public class ChannelSubtitleData {
    private static final String PREFS_NAME = "pinou_channel_subtitles";
    private static final String DELIM = "|";
    // enabled|style index|scale|position|language format (last: it contains its own separators)
    private static final int FIELD_COUNT = 5;
    @SuppressLint("StaticFieldLeak")
    private static ChannelSubtitleData sInstance;
    private final SharedPreferences mPrefs;

    private ChannelSubtitleData(Context context) {
        mPrefs = context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public static ChannelSubtitleData instance(Context context) {
        if (sInstance == null) {
            sInstance = new ChannelSubtitleData(context);
        }

        return sInstance;
    }

    /**
     * Puts the channel's settings into the player data.
     * @return the subtitle format to select, or null when the channel has no settings of its own
     */
    @Nullable
    public FormatItem apply(@Nullable String channelId, PlayerData playerData) {
        String[] fields = load(channelId);

        if (fields == null) {
            return null;
        }

        List<SubtitleStyle> styles = playerData.getSubtitleStyles();
        int styleIndex = Helpers.parseInt(fields[1]);
        if (styleIndex >= 0 && styleIndex < styles.size()) {
            playerData.setSubtitleStyle(styles.get(styleIndex));
        }
        float scale = Helpers.parseFloat(fields[2]); // -1 if unreadable
        if (scale > 0) {
            playerData.setSubtitleScale(scale);
        }
        float position = Helpers.parseFloat(fields[3]);
        if (position >= 0) {
            playerData.setSubtitlePosition(position);
        }

        FormatItem language = ExoFormatItem.from(fields[4]);
        boolean hasLanguage = language != null && !language.isDefault();
        if (hasLanguage) {
            // First in the recent languages, so the subtitles button turns this one back on
            playerData.setFormat(language);
        }

        boolean enabled = hasLanguage && "1".equals(fields[0]);
        if (!enabled) {
            playerData.setFormat(FormatItem.SUBTITLE_NONE);
        }

        // Keep SmartTube's own "subtitles per channel" list in step, it decides the subtitles button state
        if (playerData.isSubtitlesPerChannelEnabled() && playerData.isSubtitlesPerChannelEnabled(channelId) != enabled) {
            if (enabled) {
                playerData.enableSubtitlesPerChannel(channelId);
            } else {
                playerData.disableSubtitlesPerChannel(channelId);
            }
        }

        return enabled ? language : FormatItem.SUBTITLE_NONE;
    }

    /**
     * The current subtitle settings, in the form they are saved for a channel.
     * @param shown the subtitles on screen, or null when they are off
     */
    public String describe(@Nullable String channelId, @Nullable FormatItem shown, PlayerData playerData) {
        boolean enabled = shown != null && !shown.isDefault();
        FormatItem language = enabled ? shown : null;

        if (language == null) { // off: keep the language that comes back when they are turned on
            String[] previous = load(channelId);
            language = previous != null ? ExoFormatItem.from(previous[4]) : null;
        }

        if (language == null || language.isDefault()) {
            language = playerData.getLastSubtitleFormat();
        }

        int styleIndex = playerData.getSubtitleStyles().indexOf(playerData.getSubtitleStyle());

        return (enabled ? "1" : "0") + DELIM + styleIndex + DELIM + playerData.getSubtitleScale() + DELIM +
                playerData.getSubtitlePosition() + DELIM + (language != null && !language.isDefault() ? language : "");
    }

    public void save(@Nullable String channelId, String description) {
        if (channelId == null) {
            return;
        }

        mPrefs.edit().putString(channelId, description).apply();
    }

    @Nullable
    private String[] load(@Nullable String channelId) {
        String value = channelId != null ? mPrefs.getString(channelId, null) : null;

        if (value == null) {
            return null;
        }

        String[] fields = value.split("\\" + DELIM, FIELD_COUNT);

        return fields.length == FIELD_COUNT ? fields : null;
    }
}
