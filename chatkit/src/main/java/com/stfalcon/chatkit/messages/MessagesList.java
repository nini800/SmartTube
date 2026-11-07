/*******************************************************************************
 * Copyright 2016 stfalcon.com
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *******************************************************************************/

package com.stfalcon.chatkit.messages;

import android.content.Context;
import android.util.AttributeSet;
import android.view.KeyEvent;

import androidx.annotation.Nullable;
import androidx.recyclerview.widget.DefaultItemAnimator;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.SimpleItemAnimator;

import com.stfalcon.chatkit.commons.models.IMessage;

/**
 * Component for displaying list of messages
 */
public class MessagesList extends RecyclerView {
    private MessagesListStyle messagesListStyle;
    private DpadScroller dpadScroller; // MOD: PinouTube

    public MessagesList(Context context) {
        super(context);
    }

    public MessagesList(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        parseStyle(context, attrs);
    }

    public MessagesList(Context context, @Nullable AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        parseStyle(context, attrs);
    }

    /**
     * Don't use this method for setting your adapter, otherwise exception will by thrown.
     * Call {@link #setAdapter(MessagesListAdapter)} instead.
     */
    @Override
    public void setAdapter(Adapter adapter) {
        throw new IllegalArgumentException("You can't set adapter to MessagesList. Use #setAdapter(MessagesListAdapter) instead.");
    }

    /**
     * Sets adapter for MessagesList
     *
     * @param adapter   Adapter. Must extend MessagesListAdapter
     * @param <MESSAGE> Message model class
     */
    public <MESSAGE extends IMessage>
    void setAdapter(MessagesListAdapter<MESSAGE> adapter) {
        setAdapter(adapter, true);
    }

    /**
     * Sets adapter for MessagesList
     *
     * @param adapter       Adapter. Must extend MessagesListAdapter
     * @param reverseLayout weather to use reverse layout for layout manager.
     * @param <MESSAGE>     Message model class
     */
    public <MESSAGE extends IMessage>
    void setAdapter(MessagesListAdapter<MESSAGE> adapter, boolean reverseLayout) {
        SimpleItemAnimator itemAnimator = new DefaultItemAnimator();
        itemAnimator.setSupportsChangeAnimations(false);

        // MOD: PinouTube: a layout manager that DpadScroller can stop from scrolling on focus changes
        LinearLayoutManager layoutManager = new DpadScroller.LayoutManager(getContext(), reverseLayout);

        // The solution for aligning items to the top instead of bottom
        // https://stackoverflow.com/questions/46168245/recyclerview-reverse-order
        //layoutManager.setStackFromEnd(true);

        setItemAnimator(itemAnimator);
        setLayoutManager(layoutManager);
        adapter.setLayoutManager(layoutManager);
        adapter.setStyle(messagesListStyle);

        addOnScrollListener(new RecyclerScrollMoreListener(layoutManager, adapter));
        super.setAdapter(adapter);
    }

    @SuppressWarnings("ResourceType")
    private void parseStyle(Context context, AttributeSet attrs) {
        messagesListStyle = MessagesListStyle.parse(context, attrs);
    }

    /**
     * MOD: PinouTube: D-pad up/down scroll through messages taller than the list,
     * and scroll smoothly while held (see {@link DpadScroller}).
     */
    public void enableDpadScroll(boolean enable) {
        if (!enable && dpadScroller != null) {
            dpadScroller.cancel();
        }

        dpadScroller = enable ? (dpadScroller != null ? dpadScroller : new DpadScroller(this)) : null;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (dpadScroller != null && dpadScroller.onKeyEvent(event)) {
            return true;
        }

        return super.dispatchKeyEvent(event);
    }

    @Override
    public void onWindowFocusChanged(boolean hasWindowFocus) {
        super.onWindowFocusChanged(hasWindowFocus);

        if (!hasWindowFocus && dpadScroller != null) {
            dpadScroller.cancel(); // e.g. a dialog or the keyboard opened
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        if (dpadScroller != null) {
            dpadScroller.cancel();
        }

        super.onDetachedFromWindow();
    }
}
