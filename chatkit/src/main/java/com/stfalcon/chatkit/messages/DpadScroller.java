package com.stfalcon.chatkit.messages;

import android.content.Context;
import android.os.SystemClock;
import android.view.Choreographer;
import android.view.KeyEvent;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.ViewCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

/**
 * PinouTube: D-pad scrolling for lists whose messages can be taller than the screen (comments).
 * <ul>
 * <li>A press moves to the next or previous message. A message that doesn't fit on screen is scrolled through
 * first, most of a screen per press, so a long message can be read as one piece.</li>
 * <li>Holding the key scrolls continuously and smoothly, faster the longer it is held, instead of stepping from
 * message to message. The focus follows the message under the reading line and stays there on release.</li>
 * </ul>
 * Enabled with {@link MessagesList#enableDpadScroll(boolean)}.
 */
final class DpadScroller implements Choreographer.FrameCallback {
    /** Share of the list height that one press scrolls inside a message taller than the list. */
    private static final float PAGE_STEP = 0.75f;
    /** Hold speed in list heights per second: when the hold starts, and after {@link #RAMP_SECONDS}. */
    private static final float START_SPEED = 1.5f;
    private static final float MAX_SPEED = 5f;
    private static final float RAMP_SECONDS = 2f;
    /** While holding, the focused message is the one under this line (share of the list height from the top). */
    private static final float READING_LINE = 0.4f;
    /**
     * Repeats arrive every ~50 ms while a key is held: a longer silence means its release went elsewhere.
     * Generous, so a slow frame never stops a hold.
     */
    private static final long RELEASE_TIMEOUT_MS = 500;
    private static final float MAX_FRAME_SECONDS = 0.05f;
    private static final int SLACK_PX = 4;

    private final RecyclerView mList;
    private int mDirection; // 1 = holding down, -1 = holding up, 0 = not holding
    private long mHoldStartNanos;
    private long mLastFrameNanos;
    private long mLastRepeatMs;
    private float mPendingPx;
    private boolean mFrameScheduled;
    private int mConsumedKeyCode; // key whose ACTION_DOWN was consumed here, so its ACTION_UP is consumed too

    /**
     * A vertical LinearLayoutManager that can stop focus changes from scrolling the list,
     * so the focus can follow a scroll without fighting it.
     */
    static final class LayoutManager extends LinearLayoutManager {
        private boolean mFocusScrollSuppressed;

        LayoutManager(Context context, boolean reverseLayout) {
            super(context, VERTICAL, reverseLayout);
        }

        void setFocusScrollSuppressed(boolean suppressed) {
            mFocusScrollSuppressed = suppressed;
        }

        @Override
        public boolean onRequestChildFocus(@NonNull RecyclerView parent, @NonNull RecyclerView.State state,
                                           @NonNull View child, @Nullable View focused) {
            // true = handled: RecyclerView won't scroll the newly focused message into view
            return mFocusScrollSuppressed || super.onRequestChildFocus(parent, state, child, focused);
        }
    }

    DpadScroller(RecyclerView list) {
        mList = list;
    }

    /**
     * @return true if the event was handled here
     */
    boolean onKeyEvent(KeyEvent event) {
        int keyCode = event.getKeyCode();
        int action = event.getAction();

        if (keyCode != KeyEvent.KEYCODE_DPAD_DOWN && keyCode != KeyEvent.KEYCODE_DPAD_UP) {
            if (action == KeyEvent.ACTION_DOWN) {
                cancel(); // any other key stops a hold scroll
            }
            return false;
        }

        int direction = keyCode == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : -1;

        if (action == KeyEvent.ACTION_DOWN) {
            if (event.getRepeatCount() == 0) {
                cancel();
                boolean consumed = scrollInsideFocused(direction);
                mConsumedKeyCode = consumed ? keyCode : 0;
                return consumed;
            }

            // The key is held down
            mLastRepeatMs = SystemClock.uptimeMillis();
            if (mDirection != direction) {
                startHold(direction);
            }
            mConsumedKeyCode = keyCode;
            return true;
        }

        if (action == KeyEvent.ACTION_UP) {
            boolean consumed = mConsumedKeyCode == keyCode || mDirection != 0;
            mConsumedKeyCode = 0;
            stopHold(true);
            return consumed;
        }

        return false;
    }

    /**
     * Stops a hold scroll, leaving the focus where it is.
     */
    void cancel() {
        stopHold(false);
    }

    /**
     * A press inside a message that doesn't fit: scroll through it instead of leaving it.
     */
    private boolean scrollInsideFocused(int direction) {
        View focused = mList.getFocusedChild();

        if (focused == null) {
            return false;
        }

        int top = mList.getPaddingTop();
        int bottom = mList.getHeight() - mList.getPaddingBottom();
        int step = Math.max(1, (int) ((bottom - top) * PAGE_STEP));

        if (direction > 0 && focused.getBottom() > bottom + SLACK_PX) {
            mList.smoothScrollBy(0, Math.min(focused.getBottom() - bottom, step));
            return true;
        }

        if (direction < 0 && focused.getTop() < top - SLACK_PX) {
            mList.smoothScrollBy(0, -Math.min(top - focused.getTop(), step));
            return true;
        }

        return false; // the message is fully visible on that side: the normal focus move takes over
    }

    private void startHold(int direction) {
        mDirection = direction;
        mLastFrameNanos = 0;
        mPendingPx = 0;
        mList.stopScroll(); // the smooth scroll started by the first press
        setFocusScrollSuppressed(true);
        scheduleFrame();
    }

    private void stopHold(boolean keepReadingLineFocus) {
        if (mDirection == 0) {
            return;
        }

        mDirection = 0;

        if (mFrameScheduled) {
            Choreographer.getInstance().removeFrameCallback(this);
            mFrameScheduled = false;
        }

        if (keepReadingLineFocus) {
            focusReadingLine(); // the list stays where it is
        }

        setFocusScrollSuppressed(false);
    }

    @Override
    public void doFrame(long frameTimeNanos) {
        mFrameScheduled = false;

        if (mDirection == 0) {
            return;
        }

        if (!ViewCompat.isAttachedToWindow(mList) || !mList.hasFocus()
                || SystemClock.uptimeMillis() - mLastRepeatMs > RELEASE_TIMEOUT_MS) {
            stopHold(ViewCompat.isAttachedToWindow(mList) && mList.hasFocus());
            return;
        }

        if (mLastFrameNanos == 0) {
            mHoldStartNanos = frameTimeNanos;
        } else {
            float frameSeconds = Math.min((frameTimeNanos - mLastFrameNanos) / 1_000_000_000f, MAX_FRAME_SECONDS);
            float ramp = Math.min((frameTimeNanos - mHoldStartNanos) / 1_000_000_000f / RAMP_SECONDS, 1f);
            ramp = ramp * ramp * (3 - 2 * ramp); // ease in
            float speed = (START_SPEED + (MAX_SPEED - START_SPEED) * ramp) * getListHeight();

            mPendingPx += speed * frameSeconds * mDirection;
            int dy = (int) mPendingPx;

            if (dy != 0) {
                mPendingPx -= dy;
                mList.scrollBy(0, dy); // stops by itself at the ends; new comments extend the list while held
            }

            focusReadingLine();
        }

        mLastFrameNanos = frameTimeNanos;
        scheduleFrame();
    }

    private void scheduleFrame() {
        if (!mFrameScheduled) {
            mFrameScheduled = true;
            Choreographer.getInstance().postFrameCallback(this);
        }
    }

    /**
     * Focus the message under the reading line (or the nearest one), without scrolling.
     */
    private void focusReadingLine() {
        int top = mList.getPaddingTop();
        int line = top + (int) (getListHeight() * READING_LINE);
        View nearest = null;
        int nearestDistance = Integer.MAX_VALUE;

        for (int i = 0; i < mList.getChildCount(); i++) {
            View child = mList.getChildAt(i);

            if (!child.isFocusable() || child.getVisibility() != View.VISIBLE) {
                continue;
            }

            int distance = child.getTop() > line ? child.getTop() - line : Math.max(0, line - child.getBottom());

            if (distance < nearestDistance) {
                nearest = child;
                nearestDistance = distance;
            }
        }

        if (nearest != null && !nearest.hasFocus()) {
            nearest.requestFocus();
        }
    }

    private int getListHeight() {
        return Math.max(1, mList.getHeight() - mList.getPaddingTop() - mList.getPaddingBottom());
    }

    private void setFocusScrollSuppressed(boolean suppressed) {
        if (mList.getLayoutManager() instanceof LayoutManager) {
            ((LayoutManager) mList.getLayoutManager()).setFocusScrollSuppressed(suppressed);
        }
    }
}
