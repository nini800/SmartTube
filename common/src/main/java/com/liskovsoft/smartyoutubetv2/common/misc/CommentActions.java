package com.liskovsoft.smartyoutubetv2.common.misc;

import android.content.Context;
import android.text.InputType;
import android.util.Base64;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import com.liskovsoft.googlecommon.common.helpers.RetrofitOkHttpHelper;
import com.liskovsoft.mediaserviceinterfaces.data.CommentItem;
import com.liskovsoft.sharedutils.helpers.KeyHelpers;
import com.liskovsoft.sharedutils.helpers.MessageHelpers;
import com.liskovsoft.sharedutils.rx.RxHelper;
import com.liskovsoft.smartyoutubetv2.common.R;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.UiOptionItem;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.AppDialogPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.ChannelPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.views.ViewManager;
import com.liskovsoft.youtubeapi.common.helpers.PostDataHelper;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;

import io.reactivex.disposables.Disposable;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * PinouTube: the menu shown when holding OK on a comment: Like/Unlike, Reply, Show profile.
 * <p>
 * The TV comments API has no author channel and no reply button, so:
 * <ul>
 * <li>Show profile resolves the author's @handle (comments show handles) to a channel with navigation/resolve_url.</li>
 * <li>Reply posts with comment/create_comment_reply, building the reply params YouTube's web app gets from its
 * reply button: protobuf {2: video id, 4: parent comment id, 5: {1: 0}, 10: 7}.</li>
 * </ul>
 * Requests go through the app's YouTube client, which adds the signed-in account.
 */
public final class CommentActions {
    private static final String TAG = "PinouComments";
    private static final String RESOLVE_URL_API = "https://www.youtube.com/youtubei/v1/navigation/resolve_url";
    private static final String CREATE_REPLY_API = "https://www.youtube.com/youtubei/v1/comment/create_comment_reply";
    private static final MediaType JSON_TYPE = MediaType.parse("application/json; charset=utf-8");
    private static Disposable sReplyAction;
    private static Disposable sProfileAction;

    private CommentActions() {
    }

    /**
     * @param videoId    the video the comments belong to (needed to reply)
     * @param isSigned   liking and replying need an account
     * @param toggleLike likes or unlikes the comment and updates the list
     */
    public static void showMenu(Context context, AppDialogPresenter dialog, CommentItem comment, @Nullable String videoId,
                                boolean isSigned, Runnable toggleLike) {
        if (comment == null || comment.getId() == null) {
            return;
        }

        dialog.appendSingleButton(UiOptionItem.from(
                context.getString(comment.isLiked() ? R.string.comment_unlike : R.string.action_like),
                option -> {
                    dialog.goBack();
                    if (isSigned) {
                        toggleLike.run();
                    } else {
                        MessageHelpers.showMessage(context, R.string.msg_signed_users_only);
                    }
                }));

        dialog.appendSingleButton(UiOptionItem.from(
                context.getString(R.string.comment_reply),
                option -> {
                    dialog.goBack();
                    if (!isSigned) {
                        MessageHelpers.showMessage(context, R.string.msg_signed_users_only);
                    } else if (videoId == null) {
                        MessageHelpers.showMessage(context, context.getString(R.string.comment_reply_failed, "no video id"));
                    } else {
                        showReplyDialog(context, dialog, comment, videoId, null);
                    }
                }));

        dialog.appendSingleButton(UiOptionItem.from(
                context.getString(R.string.comment_show_profile),
                option -> openProfile(context, dialog, comment)));

        dialog.showDialog(comment.getAuthorName());
    }

    private static void openProfile(Context context, AppDialogPresenter dialog, CommentItem comment) {
        String handle = comment.getAuthorName() != null ? comment.getAuthorName().trim() : null;

        if (handle == null || !handle.startsWith("@") || handle.length() < 2) {
            MessageHelpers.showMessage(context, R.string.comment_profile_failed);
            return;
        }

        if (RxHelper.isAnyActionRunning(sProfileAction)) {
            return;
        }

        sProfileAction = RxHelper.fromCallable(() -> resolveChannelId(handle))
                .subscribe(
                        channelId -> {
                            dialog.closeDialog();
                            ChannelPresenter.instance(context).openChannel(channelId);
                        },
                        error -> {
                            Log.e(TAG, "Can't open the profile of " + handle + ": " + error.getMessage());
                            MessageHelpers.showMessage(context, R.string.comment_profile_failed);
                        });
    }

    /**
     * Like SimpleEditDialog, with a sentence keyboard, over the comments when they're open.
     */
    private static void showReplyDialog(Context context, AppDialogPresenter dialog, CommentItem comment, String videoId,
                                        @Nullable String text) {
        // The comments are in their own activity, on top of the one that opened them
        Context dialogContext = ViewManager.getMotherActivity(dialog.getView());
        if (dialogContext == null) {
            dialogContext = context;
        }

        View contentView = LayoutInflater.from(dialogContext).inflate(R.layout.simple_edit_dialog, null);
        EditText editField = contentView.findViewById(R.id.simple_edit_value);
        editField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                | InputType.TYPE_TEXT_FLAG_AUTO_CORRECT);
        editField.setImeOptions(EditorInfo.IME_ACTION_DONE);
        editField.setHint(R.string.comment_reply_hint);
        KeyHelpers.fixShowKeyboard(editField);

        // Like YouTube: a reply to a reply goes to the thread and starts with the author's handle
        if (text == null && isReply(comment) && comment.getAuthorName() != null) {
            text = comment.getAuthorName().trim() + " ";
        }

        if (text != null) {
            editField.setText(text);
            editField.setSelection(text.length());
        }

        AlertDialog replyDialog = new AlertDialog.Builder(dialogContext, R.style.AppDialog)
                .setTitle(context.getString(R.string.comment_reply_to, comment.getAuthorName()))
                .setView(contentView)
                .setPositiveButton(android.R.string.ok, (d, which) -> { })
                .setNegativeButton(android.R.string.cancel, (d, which) -> { })
                .create();

        editField.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_NEXT) {
                replyDialog.getButton(AlertDialog.BUTTON_POSITIVE).requestFocus();
                return true;
            }
            return false;
        });

        try {
            replyDialog.show();
        } catch (RuntimeException e) {
            // BadTokenException: the activity is gone
            Log.e(TAG, "Can't show the reply dialog: " + e.getMessage());
            return;
        }

        replyDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            String reply = editField.getText().toString().trim();

            if (reply.isEmpty() || (isReply(comment) && reply.equals(String.valueOf(comment.getAuthorName()).trim()))) {
                editField.setHint(R.string.enter_value);
                return;
            }

            replyDialog.dismiss();
            postReply(context, dialog, comment, videoId, reply);
        });
        replyDialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(view -> replyDialog.dismiss());
    }

    private static void postReply(Context context, AppDialogPresenter dialog, CommentItem comment, String videoId, String text) {
        if (RxHelper.isAnyActionRunning(sReplyAction)) {
            return;
        }

        MessageHelpers.showMessage(context, R.string.comment_reply_posting);

        sReplyAction = RxHelper.fromCallable(() -> createReply(videoId, getThreadId(comment.getId()), text))
                .subscribe(
                        posted -> MessageHelpers.showMessage(context, R.string.comment_reply_posted),
                        error -> {
                            Log.e(TAG, "Reply failed: " + error.getMessage());
                            MessageHelpers.showLongMessage(context, context.getString(R.string.comment_reply_failed, error.getMessage()));
                            showReplyDialog(context, dialog, comment, videoId, text); // keep what was typed
                        });
    }

    private static String resolveChannelId(String handle) throws IOException {
        String url = "https://www.youtube.com/@" + URLEncoder.encode(handle.substring(1), "UTF-8");
        Result result = post(RESOLVE_URL_API, PostDataHelper.createQueryTV("\"url\":" + JSONObject.quote(url)));
        JSONObject endpoint = result.json != null ? result.json.optJSONObject("endpoint") : null;
        JSONObject browse = endpoint != null ? endpoint.optJSONObject("browseEndpoint") : null;
        String browseId = browse != null ? browse.optString("browseId") : "";

        if (!browseId.startsWith("UC")) {
            throw new IOException("no channel (" + result.describe() + ")");
        }

        return browseId;
    }

    private static Boolean createReply(String videoId, String threadId, String text) throws IOException {
        String data = "\"createReplyParams\":\"" + createReplyParams(videoId, threadId) + "\",\"commentText\":" + JSONObject.quote(text);
        Result result = post(CREATE_REPLY_API, PostDataHelper.createQueryTV(data));

        if (result.code >= 400 && result.code < 500) {
            // Refused before anything was posted: the TV client may not be allowed to comment, try the web one
            Log.w(TAG, "TV client reply refused: " + result.describe());
            Result webResult = post(CREATE_REPLY_API, PostDataHelper.createQueryWeb(data));

            if (webResult.isSuccess()) {
                return true;
            }

            Log.w(TAG, "Web client reply refused: " + webResult.describe());
        }

        if (!result.isSuccess()) {
            throw new IOException(result.describe());
        }

        return true;
    }

    /**
     * Replies are flat: a reply to a reply goes to the thread (reply ids are "threadId.replyId").
     */
    private static String getThreadId(String commentId) {
        int dot = commentId.indexOf('.');
        return dot > 0 ? commentId.substring(0, dot) : commentId;
    }

    private static boolean isReply(CommentItem comment) {
        return comment.getId() != null && comment.getId().indexOf('.') > 0;
    }

    static String createReplyParams(String videoId, String threadId) throws UnsupportedEncodingException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeString(out, 2, videoId);
        writeString(out, 4, threadId);
        out.write(0x2A); // field 5, length-delimited
        out.write(0x02);
        out.write(0x08); // field 1, varint: 0
        out.write(0x00);
        out.write(0x50); // field 10, varint: 7
        out.write(0x07);
        return URLEncoder.encode(Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP), "UTF-8");
    }

    private static void writeString(ByteArrayOutputStream out, int field, String value) throws UnsupportedEncodingException {
        byte[] bytes = value.getBytes("UTF-8");
        out.write((field << 3) | 2);
        int length = bytes.length;
        while ((length & ~0x7F) != 0) {
            out.write((length & 0x7F) | 0x80);
            length >>>= 7;
        }
        out.write(length);
        out.write(bytes, 0, bytes.length);
    }

    private static Result post(String url, String body) throws IOException {
        Request request = new Request.Builder()
                .url(url)
                .post(RequestBody.create(JSON_TYPE, body))
                .build();

        try (Response response = RetrofitOkHttpHelper.getClient().newCall(request).execute()) {
            ResponseBody responseBody = response.body();
            return new Result(response.code(), responseBody != null ? responseBody.string() : null);
        }
    }

    private static final class Result {
        final int code;
        final JSONObject json;

        Result(int code, String body) {
            this.code = code;
            JSONObject json = null;
            try {
                json = body != null ? new JSONObject(body) : null;
            } catch (Exception e) {
                // not json
            }
            this.json = json;
        }

        boolean isSuccess() {
            if (code < 200 || code >= 300 || json == null || json.has("error")) {
                return false;
            }

            JSONObject actionResult = json.optJSONObject("actionResult");
            String status = actionResult != null ? actionResult.optString("status") : "";
            return status.isEmpty() || "STATUS_SUCCEEDED".equals(status);
        }

        String describe() {
            JSONObject error = json != null ? json.optJSONObject("error") : null;

            if (error != null && !error.optString("message").isEmpty()) {
                return "HTTP " + code + ": " + error.optString("message");
            }

            JSONObject actionResult = json != null ? json.optJSONObject("actionResult") : null;

            if (actionResult != null && !actionResult.optString("status").isEmpty()) {
                return actionResult.optString("status") + " " + actionResult.optString("feedback");
            }

            return "HTTP " + code;
        }
    }
}
