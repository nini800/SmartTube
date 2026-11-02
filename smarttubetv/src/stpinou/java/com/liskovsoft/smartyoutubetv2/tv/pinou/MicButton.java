package com.liskovsoft.smartyoutubetv2.tv.pinou;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;

import com.liskovsoft.sharedutils.mylogger.Log;
import com.tananaev.adblib.AdbConnection;
import com.tananaev.adblib.AdbCrypto;
import com.tananaev.adblib.AdbStream;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The remote's mic while the player is open. Android hands this key to the assistant before any app sees it,
 * so it is read below that, from the input devices, which takes shell rights: this opens a shell on the Shield's
 * own ADB port (network debugging) for as long as the player is open. The first time, the Shield asks to allow
 * this app's key ("Always allow"). Until then, or without network debugging, the mic stays the assistant.<br/>
 * Same mechanism as PinouPlex's MicButton (home.pinou.plex), on purpose with the same shell and owner file:
 * whichever app opened the newest shell is the one that puts the assistant back.
 */
final class MicButton {
    private static final String TAG = MicButton.class.getSimpleName();
    private static final int ADB_PORT = 5555;
    private static final int CONNECT_MS = 2000;
    private static final int RETRY_MS = 5000;
    private static final Pattern KEY = Pattern.compile("EV_KEY\\s+KEY_SEARCH\\s+(DOWN|UP)\\b");
    /**
     * Blanks the assistant so the key does nothing else, then reports the input devices. A shell is not told when
     * its connection dies, so the "alive" lines are what notice it: the next one fails and the trap puts the assistant
     * back, even if the app crashed. Only the newest shell, named in the owner file, restores. If the saved value is
     * a blank left behind by a power cut, the voice interaction service is restored instead.
     */
    private static final String SHELL = "shell:owner=/data/local/tmp/pinouplex_mic; "
            + "saved=$(settings get secure assistant); "
            + "case \"$saved\" in none|null|'') saved=$(settings get secure voice_interaction_service);; esac; "
            + "echo $$ > $owner; "
            + "stop() { if [ \"$(cat $owner 2>/dev/null)\" = $$ ]; then settings put secure assistant \"$saved\"; rm -f $owner; fi; kill $pid 2>/dev/null; }; "
            + "trap 'stop; exit' HUP INT TERM PIPE; trap stop EXIT; "
            + "settings put secure assistant none; "
            + "getevent -ql & pid=$!; while echo alive; do sleep 2; done";

    private final Context mContext;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Runnable mOnPress;
    private volatile Thread mWorker;
    private volatile Socket mSocket;

    /**
     * @param onPress runs on the main thread each time the mic goes down
     */
    MicButton(Context context, Runnable onPress) {
        mContext = context.getApplicationContext();
        mOnPress = onPress;
    }

    /**
     * The shell only lives while wanted.
     */
    void listen(boolean wanted) {
        if (wanted == (mWorker != null)) {
            return;
        }

        if (wanted) {
            Thread thread = new Thread(this::run, "mic-button");
            thread.setDaemon(true);
            mWorker = thread;
            thread.start();
            return;
        }

        Thread thread = mWorker;
        mWorker = null;
        thread.interrupt();
        Socket socket = mSocket;
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    private void run() {
        Thread self = Thread.currentThread();
        boolean allowed = false;

        while (mWorker == self) {
            try (Socket adb = new Socket()) {
                mSocket = adb;
                adb.connect(new InetSocketAddress("127.0.0.1", ADB_PORT), CONNECT_MS);
                AdbConnection connection = AdbConnection.create(adb, loadKeys());
                connection.connect(); // waits for the Shield to allow the key
                allowed = true;
                AdbStream stream = connection.open(SHELL);
                StringBuilder pending = new StringBuilder();
                while (mWorker == self && !stream.isClosed()) {
                    pending.append(new String(stream.read(), StandardCharsets.UTF_8));
                    for (int end = pending.indexOf("\n"); end >= 0; end = pending.indexOf("\n")) {
                        Boolean down = isKeyDown(pending.substring(0, end));
                        pending.delete(0, end + 1);
                        if (Boolean.TRUE.equals(down)) {
                            mHandler.post(mOnPress);
                        }
                    }
                }
            } catch (InterruptedException stopped) {
                return;
            } catch (Exception error) {
                Log.i(TAG, "No shell for the mic: " + error);
            }

            // Refused or unavailable: asking again every few seconds would only nag
            if (!allowed) {
                if (mWorker == self) {
                    mWorker = null;
                }
                return;
            }

            try {
                Thread.sleep(RETRY_MS);
            } catch (InterruptedException stopped) {
                return;
            }
        }
    }

    private AdbCrypto loadKeys() throws Exception {
        File privateKey = new File(mContext.getFilesDir(), "adb_key");
        File publicKey = new File(mContext.getFilesDir(), "adb_key.pub");

        if (privateKey.isFile() && publicKey.isFile()) {
            return AdbCrypto.loadAdbKeyPair(MicButton::toBase64, privateKey, publicKey);
        }

        AdbCrypto generated = AdbCrypto.generateAdbKeyPair(MicButton::toBase64);
        generated.saveAdbKeyPair(privateKey, publicKey);
        return generated;
    }

    private static String toBase64(byte[] data) {
        return Base64.encodeToString(data, Base64.NO_WRAP);
    }

    /**
     * True for the mic going down, false for its release, null for any other line.
     */
    static Boolean isKeyDown(String line) {
        Matcher key = KEY.matcher(line);
        return key.find() ? "DOWN".equals(key.group(1)) : null;
    }
}
