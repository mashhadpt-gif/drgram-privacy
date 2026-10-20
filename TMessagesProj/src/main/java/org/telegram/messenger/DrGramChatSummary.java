/*
 * This is the source code of DrGram, a Telegram fork.
 * It is licensed under GNU GPL v. 2 or later.
 *
 * DrGram Chat Summary (Fix 80) — Persian AI summarizer on top of the
 * existing AI plumbing (DrGramAIConfig: user's own endpoint/model/key,
 * encrypted at rest). Collects the currently loaded text messages of the
 * open chat and asks the configured model for a short Persian brief.
 *
 * Everything runs off the main thread; every failure lands in a friendly
 * dialog/toast instead of a crash.
 */
package org.telegram.messenger;

import android.app.Activity;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import javax.net.ssl.HttpsURLConnection;

public final class DrGramChatSummary {

    private static final int MAX_MESSAGES = 80;
    private static final int MAX_CHARS = 12000;
    private static final int MAX_RESPONSE_BYTES = 256 * 1024;

    private DrGramChatSummary() {
    }

    /** Entry from ChatActivity three-dot menu. messages = ChatActivity.messages (public field). */
    public static void summarize(final BaseFragment fragment, final java.util.ArrayList<org.telegram.messenger.MessageObject> messages) {
        try {
            if (fragment == null || fragment.getParentActivity() == null) {
                return;
            }
            if (TextUtils.isEmpty(DrGramAIConfig.getApiKey())) {
                android.widget.Toast.makeText(fragment.getParentActivity(),
                        LocaleController.getString(R.string.DrGramAiNoKey), android.widget.Toast.LENGTH_LONG).show();
                return;
            }
            final String transcript = buildTranscript(messages);
            if (TextUtils.isEmpty(transcript)) {
                android.widget.Toast.makeText(fragment.getParentActivity(),
                        LocaleController.getString(R.string.DrGramAiSummaryEmpty), android.widget.Toast.LENGTH_SHORT).show();
                return;
            }
            AndroidUtilities.runOnUIThread(() -> {
                try {
                    android.widget.Toast.makeText(fragment.getParentActivity(),
                            LocaleController.getString(R.string.DrGramAiWorking), android.widget.Toast.LENGTH_SHORT).show();
                } catch (Throwable ignore) {
                }
            });
            Utilities.globalQueue.postRunnable(() -> {
                String answer = request(transcript);
                String finalAnswer = answer;
                AndroidUtilities.runOnUIThread(() -> showResult(fragment, finalAnswer));
            });
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }

    private static String buildTranscript(java.util.ArrayList<MessageObject> messages) {
        if (messages == null || messages.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        int taken = 0;
        for (int i = messages.size() - 1; i >= 0 && taken < MAX_MESSAGES && sb.length() < MAX_CHARS; i--) {
            try {
                MessageObject m = messages.get(i);
                if (m == null || m.messageOwner == null || m.messageOwner.action != null) {
                    continue;
                }
                String text = m.messageOwner.message;
                if (TextUtils.isEmpty(text) || text.length() > 1500) {
                    if (TextUtils.isEmpty(text)) {
                        continue;
                    }
                    text = text.substring(0, 1500);
                }
                String name;
                if (m.isOutOwner()) {
                    name = "من";
                } else {
                    TLRPC.User sender = MessagesController.getInstance(UserConfig.selectedAccount).getUser(m.getSenderId());
                    name = sender == null ? "?" : UserObject.getFirstName(sender);
                }
                sb.insert(0, name + ": " + text.replace('\n', ' ') + "\n");
                taken++;
            } catch (Throwable ignore) {
            }
        }
        return sb.toString().trim();
    }

    private static String request(String transcript) {
        try {
            String endpoint = DrGramAIConfig.getEndpoint();
            String model = DrGramAIConfig.getModel();
            String apiKey = DrGramAIConfig.getApiKey();
            URL url = new URL(endpoint);
            if (!"https".equalsIgnoreCase(url.getProtocol())) {
                return LocaleController.getString(R.string.DrGramAiRequestFailed);
            }
            HttpsURLConnection connection = (HttpsURLConnection) url.openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(45000);
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Authorization", "Bearer " + apiKey);

            JSONArray messages = new JSONArray();
            messages.put(new JSONObject()
                    .put("role", "system")
                    .put("content", "تو خلاصه‌ساز فارسی دکترگرام هستی. گفتگوی زیر را در ۳ تا ۵ نکتهٔ کوتاه فارسی خلاصه کن؛ نام گوینده‌ها را حفظ کن، تصحیح املایی جزئی مجاز است و هیچ اطلاعات جدید نساز."));
            messages.put(new JSONObject()
                    .put("role", "user")
                    .put("content", transcript));

            byte[] body = new JSONObject()
                    .put("model", model)
                    .put("messages", messages)
                    .put("max_completion_tokens", 700)
                    .toString().getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(body.length);
            try (OutputStream stream = connection.getOutputStream()) {
                stream.write(body);
            }

            int status = connection.getResponseCode();
            InputStream responseStream = status >= 200 && status < 300
                    ? connection.getInputStream() : connection.getErrorStream();
            String response = responseStream == null ? "" : readLimited(responseStream);
            connection.disconnect();

            if (status < 200 || status >= 300) {
                return LocaleController.getString(R.string.DrGramAiRequestFailed) + " (HTTP " + status + ")";
            }
            JSONObject json = new JSONObject(response);
            JSONArray choices = json.optJSONArray("choices");
            if (choices == null || choices.length() == 0) {
                return LocaleController.getString(R.string.DrGramAiNoResult);
            }
            JSONObject message = choices.getJSONObject(0).optJSONObject("message");
            String output = message == null ? "" : message.optString("content", "").trim();
            return TextUtils.isEmpty(output) ? LocaleController.getString(R.string.DrGramAiNoResult) : output;
        } catch (Throwable t) {
            FileLog.e(t);
            return LocaleController.getString(R.string.DrGramAiRequestFailed);
        }
    }

    private static String readLimited(InputStream stream) throws Exception {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        int read;
        while ((read = stream.read(buffer)) > 0 && total < MAX_RESPONSE_BYTES) {
            out.write(buffer, 0, read);
            total += read;
        }
        try {
            stream.close();
        } catch (Throwable ignore) {
        }
        return out.toString("UTF-8");
    }

    private static void showResult(BaseFragment fragment, String answer) {
        try {
            Activity context = fragment.getParentActivity();
            if (context == null || context.isFinishing()) {
                return;
            }
            AlertDialog.Builder builder = new AlertDialog.Builder(context, fragment.getResourceProvider());
            builder.setTitle(LocaleController.getString(R.string.DrGramAiSummaryTitle));
            builder.setMessage(answer);
            builder.setPositiveButton(LocaleController.getString(R.string.Copy), (dialog, which) -> {
                AndroidUtilities.addToClipboard(answer);
                try {
                    android.widget.Toast.makeText(context, LocaleController.getString(R.string.DrGramCopied), android.widget.Toast.LENGTH_SHORT).show();
                } catch (Throwable ignore) {
                }
            });
            builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
            builder.show();
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }
}
