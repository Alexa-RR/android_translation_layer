/*
 * Copyright (C) 2010 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.speech;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.Executor;

/**
 * This class provides access to the speech recognition service (AOSP API).
 *
 * teslalinux: AOSP binds the RecognitionService selected in Settings.Secure
 * VOICE_RECOGNITION_SERVICE (normally Google's). This device has no microphone and no recognition
 * service of its own: the default recognizer is the car page's browser, reached through
 * {@link TlSpeechChannel} (the browser listens with its own microphone and speech recognition and
 * sends back the text). It is available while such a page is connected; without one this is AOSP's
 * path without a service: {@link #isRecognitionAvailable} is false, and {@link #startListening}
 * reports {@link RecognitionListener#onError} with {@link #ERROR_CLIENT} on the main thread ("no
 * selected voice recognition service"). An explicitly named service component is bound through
 * Context.bindService; one that cannot be bound ends the same way, as in AOSP ("bind to
 * recognition service failed").
 */
public class SpeechRecognizer {
    private static final String TAG = "SpeechRecognizer";

    public static final String RESULTS_RECOGNITION = "results_recognition";
    public static final String RESULTS_ALTERNATIVES = "results_alternatives";
    public static final String CONFIDENCE_SCORES = "confidence_scores";
    public static final String DETECTED_LANGUAGE = "detected_language";
    public static final String LANGUAGE_DETECTION_CONFIDENCE_LEVEL = "language_detection_confidence_level";
    public static final String TOP_LOCALE_ALTERNATIVES = "top_locale_alternatives";
    public static final String LANGUAGE_SWITCH_RESULT = "language_switch_result";

    public static final int ERROR_NETWORK_TIMEOUT = 1;
    public static final int ERROR_NETWORK = 2;
    public static final int ERROR_AUDIO = 3;
    public static final int ERROR_SERVER = 4;
    public static final int ERROR_CLIENT = 5;
    public static final int ERROR_SPEECH_TIMEOUT = 6;
    public static final int ERROR_NO_MATCH = 7;
    public static final int ERROR_RECOGNIZER_BUSY = 8;
    public static final int ERROR_INSUFFICIENT_PERMISSIONS = 9;
    public static final int ERROR_TOO_MANY_REQUESTS = 10;
    public static final int ERROR_SERVER_DISCONNECTED = 11;
    public static final int ERROR_LANGUAGE_NOT_SUPPORTED = 12;
    public static final int ERROR_LANGUAGE_UNAVAILABLE = 13;
    public static final int ERROR_CANNOT_CHECK_SUPPORT = 14;
    public static final int ERROR_CANNOT_LISTEN_TO_DOWNLOAD_EVENTS = 15;

    /** The RecognitionService intent action (RecognitionService.SERVICE_INTERFACE). */
    private static final String SERVICE_INTERFACE = "android.speech.RecognitionService";

    private final Context mContext;
    private final ComponentName mServiceComponent;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private RecognitionListener mListener;
    private ServiceConnection mConnection;
    private int mSession; /* the car page's session (TlSpeechChannel), 0: none */

    private SpeechRecognizer(Context context, ComponentName serviceComponent) {
        mContext = context;
        mServiceComponent = serviceComponent;
    }

    /** Whether the car page's recognizer is there (a page that can recognise speech is connected). */
    public static boolean isRecognitionAvailable(@NonNull final Context context) {
        TlSpeechChannel channel = TlSpeechChannel.get();
        return channel != null && channel.available();
    }

    /** @hide teslalinux: the car page's channel starts with the app (its page connects before the first request) */
    public static void tlStartChannel() {
        TlSpeechChannel.get();
    }

    /** No on-device recognition service is installed on this device. */
    public static boolean isOnDeviceRecognitionAvailable(@NonNull final Context context) {
        return false;
    }

    @NonNull
    public static SpeechRecognizer createSpeechRecognizer(final Context context) {
        return createSpeechRecognizer(context, null);
    }

    @NonNull
    public static SpeechRecognizer createSpeechRecognizer(final Context context,
            final ComponentName serviceComponent) {
        if (context == null) {
            throw new IllegalArgumentException("Context cannot be null");
        }
        checkIsCalledFromMainThread();
        return new SpeechRecognizer(context, serviceComponent);
    }

    @NonNull
    public static SpeechRecognizer createOnDeviceSpeechRecognizer(@NonNull final Context context) {
        if (!isOnDeviceRecognitionAvailable(context)) {
            throw new UnsupportedOperationException("On-device recognition is not supported");
        }
        return createSpeechRecognizer(context, null);
    }

    public void setRecognitionListener(RecognitionListener listener) {
        checkIsCalledFromMainThread();
        mListener = listener;
    }

    public void startListening(final Intent recognizerIntent) {
        if (recognizerIntent == null) {
            throw new IllegalArgumentException("intent must not be null");
        }
        checkIsCalledFromMainThread();
        if (mServiceComponent == null) {
            startPageSession(recognizerIntent);
            return;
        }
        Intent serviceIntent = new Intent(SERVICE_INTERFACE);
        serviceIntent.setComponent(mServiceComponent);
        mConnection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                // no IRecognitionService protocol behind the binder: the request cannot be sent
                Log.e(TAG, "recognition service " + name + " does not answer the recognition protocol");
                disconnect();
                postError(ERROR_CLIENT);
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                mConnection = null;
            }
        };
        boolean bound = false;
        try {
            bound = mContext.bindService(serviceIntent, mConnection, 1 /* Context.BIND_AUTO_CREATE */);
        } catch (Exception e) {
            Log.e(TAG, "bind to recognition service failed: " + e);
        }
        if (!bound) {
            Log.e(TAG, "bind to recognition service failed");
            mConnection = null;
            postError(ERROR_CLIENT);
        }
    }

    /** The end of the audio: the recognizer delivers what it heard (onResults). */
    public void stopListening() {
        checkIsCalledFromMainThread();
        TlSpeechChannel channel = TlSpeechChannel.get();
        if (mSession != 0 && channel != null)
            channel.stop(mSession);
    }

    public void cancel() {
        checkIsCalledFromMainThread();
        cancelPageSession();
        mHandler.removeCallbacksAndMessages(null);
    }

    public void destroy() {
        cancelPageSession();
        disconnect();
        mHandler.removeCallbacksAndMessages(null);
        mListener = null;
    }

    /* ---- the car page's recognizer (TlSpeechChannel) ---- */

    private void startPageSession(Intent intent) {
        TlSpeechChannel channel = TlSpeechChannel.get();
        if (channel == null || !channel.available()) {
            Log.e(TAG, "no selected voice recognition service");
            postError(ERROR_CLIENT);
            return;
        }
        if (mSession != 0) { /* AOSP: one session at a time per recognizer */
            postError(ERROR_RECOGNIZER_BUSY);
            return;
        }
        if (intent.hasExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE)) {
            /* the caller's own audio (Android 13): the page listens with its microphone only */
            Log.e(TAG, "audio from the caller (EXTRA_AUDIO_SOURCE) is not supported by this recognizer");
            postError(ERROR_CLIENT);
            return;
        }
        String lang = intent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE);
        String tag = webLanguageTag(lang != null && !lang.isEmpty() ? lang : Locale.getDefault().toLanguageTag());
        boolean partial = intent.getBooleanExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false);
        int max = intent.getIntExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5);
        /* AOSP segmented session: the results come as one segment, then the end of the session */
        final boolean segmented = intent.getBooleanExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, false);
        final int[] id = new int[1];
        id[0] = channel.listen(tag, partial, max > 0 ? max : 5, new TlSpeechChannel.Session() {
            private boolean current() {
                return mSession == id[0] && mListener != null;
            }

            public void onReady() {
                if (current())
                    mListener.onReadyForSpeech(new Bundle());
            }

            public void onSpeechStart() {
                if (current())
                    mListener.onBeginningOfSpeech();
            }

            public void onSpeechEnd() {
                if (current())
                    mListener.onEndOfSpeech();
            }

            public void onPartial(ArrayList<String> texts) {
                if (!current())
                    return;
                Bundle b = new Bundle();
                b.putStringArrayList(RESULTS_RECOGNITION, texts);
                mListener.onPartialResults(b);
            }

            public void onResults(ArrayList<String> texts, float[] confidences) {
                if (mSession != id[0])
                    return;
                mSession = 0;
                if (mListener == null)
                    return;
                Bundle b = new Bundle();
                b.putStringArrayList(RESULTS_RECOGNITION, texts);
                b.putFloatArray(CONFIDENCE_SCORES, confidences);
                if (segmented) {
                    mListener.onSegmentResults(b);
                    if (mListener != null)
                        mListener.onEndOfSegmentedSession();
                } else {
                    mListener.onResults(b);
                }
            }

            public void onError(int error) {
                if (mSession != id[0])
                    return;
                mSession = 0;
                if (mListener != null)
                    mListener.onError(error);
            }
        });
        mSession = id[0];
        if (mSession == 0)
            postError(ERROR_CLIENT);
    }

    private void cancelPageSession() {
        if (mSession == 0)
            return;
        TlSpeechChannel channel = TlSpeechChannel.get();
        if (channel != null)
            channel.cancel(mSession);
        mSession = 0;
    }

    /**
     * A language tag as the Web Speech API takes it (BCP 47): Java's and Android's old ISO 639
     * codes (iw, in, ji, as RecognizerIntent callers may pass them) become the current ones.
     */
    static String webLanguageTag(String tag) {
        String t = tag.replace('_', '-');
        String lower = t.toLowerCase(Locale.ROOT);
        if (lower.equals("iw") || lower.startsWith("iw-"))
            return "he" + t.substring(2);
        if (lower.equals("in") || lower.startsWith("in-"))
            return "id" + t.substring(2);
        if (lower.equals("ji") || lower.startsWith("ji-"))
            return "yi" + t.substring(2);
        return t;
    }

    private void disconnect() {
        if (mConnection != null) {
            try {
                mContext.unbindService(mConnection);
            } catch (Exception e) {
            }
            mConnection = null;
        }
    }

    private void postError(final int error) {
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                RecognitionListener l = mListener;
                if (l != null)
                    l.onError(error);
            }
        });
    }

    private static void checkIsCalledFromMainThread() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            throw new RuntimeException(
                    "SpeechRecognizer should be used only from the application's main thread");
        }
    }
}
