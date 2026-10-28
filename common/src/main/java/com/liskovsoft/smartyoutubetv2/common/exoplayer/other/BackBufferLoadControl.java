package com.liskovsoft.smartyoutubetv2.common.exoplayer.other;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.DefaultLoadControl;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.Renderer;
import com.google.android.exoplayer2.source.TrackGroupArray;
import com.google.android.exoplayer2.trackselection.TrackSelection;
import com.google.android.exoplayer2.trackselection.TrackSelectionArray;
import com.google.android.exoplayer2.upstream.DefaultAllocator;
import com.liskovsoft.sharedutils.mylogger.Log;

/**
 * Keeps already played media in memory, so seeking back doesn't download it again.<br/>
 * The kept duration follows the bitrate of the selected tracks: up to {@link #MAX_BACK_BUFFER_MS},
 * but never more than {@link #getMemoryBudgetBytes()} (the buffer lives in the Java heap).
 */
public class BackBufferLoadControl extends DefaultLoadControl {
    private static final String TAG = BackBufferLoadControl.class.getSimpleName();
    public static final int MAX_BACK_BUFFER_MS = 120_000;
    private static final long MAX_BUDGET_BYTES = 160_000_000;
    private static final int UNKNOWN_BITRATE = 8_000_000; // bits per second, typical 1080p60
    private final int mMaxBackBufferMs;
    private volatile long mBackBufferDurationUs;

    public BackBufferLoadControl(int minBufferMs, int maxBufferMs, int bufferForPlaybackMs, int bufferForPlaybackAfterRebufferMs,
                                 int targetBufferBytes, int maxBackBufferMs) {
        super(new DefaultAllocator(/* trimOnReset= */ true, C.DEFAULT_BUFFER_SEGMENT_SIZE),
                minBufferMs, minBufferMs, maxBufferMs, bufferForPlaybackMs, bufferForPlaybackAfterRebufferMs,
                targetBufferBytes, DEFAULT_PRIORITIZE_TIME_OVER_SIZE_THRESHOLDS, maxBackBufferMs, /* retainBackBufferFromKeyframe= */ true);
        mMaxBackBufferMs = maxBackBufferMs;
        mBackBufferDurationUs = C.msToUs(maxBackBufferMs);
    }

    @Override
    public void onTracksSelected(Renderer[] renderers, TrackGroupArray trackGroups, TrackSelectionArray trackSelections) {
        super.onTracksSelected(renderers, trackGroups, trackSelections);

        long bitrate = 0;

        for (int i = 0; i < trackSelections.length; i++) {
            TrackSelection selection = trackSelections.get(i);

            if (selection == null) {
                continue;
            }

            // Adaptive selections may switch up: plan for their highest format
            int max = Format.NO_VALUE;
            for (int j = 0; j < selection.length(); j++) {
                max = Math.max(max, selection.getFormat(j).bitrate);
            }

            int trackType = renderers[i].getTrackType();
            if (max > 0) {
                bitrate += max;
            } else if (trackType == C.TRACK_TYPE_VIDEO) {
                bitrate += UNKNOWN_BITRATE;
            }
        }

        long backBufferMs = mMaxBackBufferMs;

        if (bitrate > 0) {
            backBufferMs = Math.min(backBufferMs, getMemoryBudgetBytes() * 8 * 1_000 / bitrate);
        }

        mBackBufferDurationUs = C.msToUs(backBufferMs);

        Log.d(TAG, "Back buffer: %s s for %s kbit/s", backBufferMs / 1_000, bitrate / 1_000);
    }

    @Override
    public long getBackBufferDurationUs() {
        return mBackBufferDurationUs;
    }

    /**
     * A third of the app's heap at most: room is left for the forward buffer and the app itself.
     */
    private static long getMemoryBudgetBytes() {
        return Math.min(MAX_BUDGET_BYTES, Runtime.getRuntime().maxMemory() / 3);
    }
}
