package com.extgram;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import javax.mail.Authenticator;
import javax.mail.Flags;
import javax.mail.Folder;
import javax.mail.Message;
import javax.mail.PasswordAuthentication;
import javax.mail.Session;
import javax.mail.Store;
import javax.mail.Transport;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;
import javax.mail.search.FlagTerm;
import javax.mail.search.SubjectTerm;

public class MainActivity extends Activity {

    /* ─── Конфигурация (устанавливается из JS через configure()) ─── */
    private volatile String senderEmail    = "";
    private volatile String appPassword    = "";
    private volatile String receiverEmail  = "";
    private volatile int    pollIntervalMs = 10000;
    private volatile String subjectPrefix  = "[TG_DATA]";

    /* ─── Внутренние ─── */
    private WebView  webView;
    private Handler  pollingHandler;
    private Runnable pollingRunnable;
    private volatile boolean pollingActive = false;

    /* ══════════════════════════════════════════════════════════════
       onCreate
       ══════════════════════════════════════════════════════════════ */
    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        /* Полноэкранный режим */
        getWindow().setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        );
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            | View.SYSTEM_UI_FLAG_FULLSCREEN
            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        );

        /* WebView */
        webView = new WebView(this);
        webView.setLayoutParams(new android.view.ViewGroup.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.MATCH_PARENT
        ));
        setContentView(webView);

        /* Настройки WebView */
        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setDatabaseEnabled(true);
        ws.setCacheMode(WebSettings.LOAD_DEFAULT);
        ws.setAllowFileAccess(true);
        ws.setAllowFileAccessFromFileURLs(true);
        ws.setAllowUniversalAccessFromFileURLs(true);
        ws.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        ws.setMediaPlaybackRequiresUserGesture(false);
        ws.setBuiltInZoomControls(false);
        ws.setDisplayZoomControls(false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ws.setSafeBrowsingEnabled(false);
        }

        /* Ссылки открываем внутри WebView, не во внешнем браузере */
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                view.loadUrl(url);
                return true;
            }
        });

        /* JavaScript Bridge */
        webView.addJavascriptInterface(new WebAppInterface(), "Android");

        /* Загружаем index.html из assets */
        webView.loadUrl("file:///android_asset/index.html");

        /* Handler для polling (работает в главном Looper, сами задачи — в фоне) */
        pollingHandler = new Handler(Looper.getMainLooper());
    }

    /* ══════════════════════════════════════════════════════════════
       JavaScript Interface
       ══════════════════════════════════════════════════════════════ */
    private class WebAppInterface {

        /* ── Принимает зашифрованный JSON от JS и отправляет письмо через SMTP ── */
        @JavascriptInterface
        public void sendEmail(final String encryptedPayload) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        Properties props = new Properties();
                        props.put("mail.smtp.host",            "smtp.gmail.com");
                        props.put("mail.smtp.port",            "465");
                        props.put("mail.smtp.auth",            "true");
                        props.put("mail.smtp.socketFactory.port",  "465");
                        props.put("mail.smtp.socketFactory.class", "javax.net.ssl.SSLSocketFactory");
                        props.put("mail.smtp.socketFactory.fallback", "false");
                        props.put("mail.smtp.ssl.trust",       "smtp.gmail.com");

                        final String user = senderEmail;
                        final String pass = appPassword;

                        Session session = Session.getInstance(props, new Authenticator() {
                            @Override
                            protected PasswordAuthentication getPasswordAuthentication() {
                                return new PasswordAuthentication(user, pass);
                            }
                        });

                        MimeMessage msg = new MimeMessage(session);
                        msg.setFrom(new InternetAddress(user));
                        msg.addRecipient(Message.RecipientType.TO, new InternetAddress(receiverEmail));
                        msg.setSubject(subjectPrefix + " MSG");
                        /* Первая строка тела = JSON-полезная нагрузка (сервер читает только её) */
                        msg.setText(encryptedPayload + "\r\n", "UTF-8", "plain");

                        Transport.send(msg);

                    } catch (Exception e) {
                        notifyJS("extgram_error", "{\"msg\":\"SMTP: " + escapeJson(e.getMessage()) + "\"}");
                    }
                }
            }).start();
        }

        /* ── Принимает конфиг из JS (после сохранения настроек), запускает polling ── */
        @JavascriptInterface
        public void configure(final String configJson) {
            try {
                JSONObject obj = new JSONObject(configJson);
                senderEmail    = obj.optString("senderEmail",    senderEmail);
                appPassword    = obj.optString("appPassword",    appPassword);
                receiverEmail  = obj.optString("receiverEmail",  receiverEmail);
                pollIntervalMs = obj.optInt("pollIntervalMs",    pollIntervalMs);
                subjectPrefix  = obj.optString("subjectPrefix",  subjectPrefix);
            } catch (Exception e) {
                notifyJS("extgram_error", "{\"msg\":\"configure parse error: " + escapeJson(e.getMessage()) + "\"}");
                return;
            }

            /* Перезапускаем polling с новым интервалом */
            stopPolling();
            if (!senderEmail.isEmpty() && !appPassword.isEmpty()) {
                startPolling();
            }
        }

        /* ── Возвращает JSON с информацией об устройстве ── */
        @JavascriptInterface
        public String getDeviceInfo() {
            try {
                JSONObject info = new JSONObject();
                info.put("platform",  "android");
                info.put("model",     Build.MODEL);
                info.put("brand",     Build.BRAND);
                info.put("sdk",       Build.VERSION.SDK_INT);
                info.put("release",   Build.VERSION.RELEASE);
                return info.toString();
            } catch (Exception e) {
                return "{\"platform\":\"android\"}";
            }
        }
    }

    /* ══════════════════════════════════════════════════════════════
       IMAP Polling — Java сам опрашивает ящик и толкает данные в JS
       ══════════════════════════════════════════════════════════════ */
    private void startPolling() {
        if (pollingActive) return;
        pollingActive = true;

        pollingRunnable = new Runnable() {
            @Override
            public void run() {
                if (!pollingActive) return;
                fetchAndPush();
                pollingHandler.postDelayed(this, pollIntervalMs);
            }
        };
        pollingHandler.postDelayed(pollingRunnable, pollIntervalMs);
    }

    private void stopPolling() {
        pollingActive = false;
        if (pollingHandler != null && pollingRunnable != null) {
            pollingHandler.removeCallbacks(pollingRunnable);
        }
    }

    /* Выполняется в фоновом потоке, по завершении вызывает JS */
    private void fetchAndPush() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                String json = fetchNewEmailsBlocking();
                if (json != null && !json.equals("[]")) {
                    final String safeJson = json;
                    webView.post(new Runnable() {
                        @Override
                        public void run() {
                            webView.evaluateJavascript(
                                "window.ExteraGram&&window.ExteraGram.onEmailReceived(" + safeJson + ")",
                                null
                            );
                        }
                    });
                }
            }
        }).start();
    }

    /* ══════════════════════════════════════════════════════════════
       Блокирующее IMAP-получение (вызывается только из фонового потока)
       Возвращает JSON-массив расшифровывать будет JS.
       ══════════════════════════════════════════════════════════════ */
    private String fetchNewEmailsBlocking() {
        Store store = null;
        Folder inbox = null;
        try {
            Properties props = new Properties();
            props.put("mail.imap.host",                  "imap.gmail.com");
            props.put("mail.imap.port",                  "993");
            props.put("mail.imap.ssl.enable",            "true");
            props.put("mail.imap.ssl.trust",             "imap.gmail.com");
            props.put("mail.imap.connectiontimeout",     "15000");
            props.put("mail.imap.timeout",               "15000");
            props.put("mail.imap.partialfetch",          "false");

            final String user = senderEmail;
            final String pass = appPassword;

            Session session = Session.getInstance(props, new Authenticator() {
                @Override
                protected PasswordAuthentication getPasswordAuthentication() {
                    return new PasswordAuthentication(user, pass);
                }
            });

            store = session.getStore("imap");
            store.connect("imap.gmail.com", user, pass);

            inbox = store.getFolder("INBOX");
            inbox.open(Folder.READ_WRITE);

            /* Ищем только непрочитанные письма с нашей темой */
            Message[] messages = inbox.search(
                new javax.mail.search.AndTerm(
                    new FlagTerm(new Flags(Flags.Flag.SEEN), false),
                    new SubjectTerm(subjectPrefix)
                )
            );

            if (messages.length == 0) return "[]";

            List<String> payloads = new ArrayList<>();

            for (Message message : messages) {
                try {
                    /* Читаем только первую строку тела (экономия памяти/трафика) */
                    Object content = message.getContent();
                    String body;
                    if (content instanceof String) {
                        body = (String) content;
                    } else if (content instanceof java.io.InputStream) {
                        BufferedReader br = new BufferedReader(new InputStreamReader((java.io.InputStream) content, "UTF-8"));
                        body = br.readLine();
                        br.close();
                    } else {
                        body = content.toString();
                    }

                    if (body == null || body.trim().isEmpty()) continue;

                    /* Берём только первую строку, обрезаем Gmail-подписи */
                    String firstLine = body.split("\\r?\\n")[0].trim();
                    if (firstLine.isEmpty()) continue;

                    payloads.add(firstLine);

                    /* Помечаем письмо как прочитанное */
                    message.setFlag(Flags.Flag.SEEN, true);

                } catch (Exception e) {
                    /* Повреждённое письмо пропускаем, не падаем */
                }
            }

            inbox.close(true);
            store.close();

            if (payloads.isEmpty()) return "[]";

            /* Возвращаем JSON-массив строк (каждая — зашифрованный пакет) */
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < payloads.size(); i++) {
                if (i > 0) sb.append(",");
                sb.append(payloads.get(i));
            }
            sb.append("]");
            return sb.toString();

        } catch (Exception e) {
            notifyJS("extgram_error", "{\"msg\":\"IMAP: " + escapeJson(e.getMessage()) + "\"}");
            try { if (inbox != null && inbox.isOpen()) inbox.close(false); } catch (Exception ignored) {}
            try { if (store != null && store.isConnected()) store.close(); } catch (Exception ignored) {}
            return "[]";
        }
    }

    /* ══════════════════════════════════════════════════════════════
       Вспомогательные
       ══════════════════════════════════════════════════════════════ */

    /* Отправка произвольного JS-события в WebView из любого потока */
    private void notifyJS(final String event, final String jsonDetail) {
        webView.post(new Runnable() {
            @Override
            public void run() {
                webView.evaluateJavascript(
                    "window.dispatchEvent(new CustomEvent('" + event + "',{detail:" + jsonDetail + "}))",
                    null
                );
            }
        });
    }

    private static String escapeJson(String s) {
        if (s == null) return "null";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }

    /* ── Lifecycle ── */
    @Override
    protected void onPause() {
        super.onPause();
        webView.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        webView.onResume();
        /* При возврате дренируем офлайн-очередь */
        webView.post(new Runnable() {
            @Override
            public void run() {
                webView.evaluateJavascript(
                    "window.ExteraGram&&window.ExteraGram.drainQueue()",
                    null
                );
            }
        });
    }

    @Override
    protected void onDestroy() {
        stopPolling();
        webView.stopLoading();
        webView.destroy();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
