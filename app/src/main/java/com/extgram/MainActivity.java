package com.extgram;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.webkit.WebViewAssetLoader;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
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

    private volatile String senderEmail    = "";
    private volatile String appPassword    = "";
    private volatile String receiverEmail  = "";
    private volatile int    pollIntervalMs = 10000;
    private volatile String subjectPrefix  = "[TG_DATA]";

    private WebView  webView;
    private FrameLayout mainLayout;
    private Handler  pollingHandler;
    private Runnable pollingRunnable;
    private volatile boolean pollingActive = false;

    private File localHtmlFile;
    private final List<String> systemLogs = new ArrayList<>();

    private ValueCallback<Uri[]> uploadMessage;
    private final static int FILECHOOSER_RESULTCODE = 1;

    private WebViewAssetLoader assetLoader;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface", "ClickableViewAccessibility"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, 2);
            }
        }

        mainLayout = new FrameLayout(this);
        mainLayout.setLayoutParams(new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ));

        webView = new WebView(this);
        webView.setLayoutParams(new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ));
        mainLayout.addView(webView);

        assetLoader = new WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
            .addPathHandler("/files/", new WebViewAssetLoader.InternalStoragePathHandler(this, getFilesDir()))
            .build();

        if (BuildConfig.DEBUG) {
            Button devGearBtn = new Button(this);
            devGearBtn.setText("⚙");
            devGearBtn.setTextColor(Color.WHITE);
            devGearBtn.setTextSize(22);
            
            GradientDrawable shape = new GradientDrawable();
            shape.setShape(GradientDrawable.OVAL);
            shape.setColor(Color.parseColor("#90a773d1"));
            devGearBtn.setBackground(shape);

            int screenWidth = getResources().getDisplayMetrics().widthPixels;
            int screenHeight = getResources().getDisplayMetrics().heightPixels;

            FrameLayout.LayoutParams btnParams = new FrameLayout.LayoutParams(110, 110);
            btnParams.gravity = Gravity.TOP | Gravity.START;
            btnParams.leftMargin = screenWidth - 140;
            btnParams.topMargin = screenHeight - 240;
            mainLayout.addView(devGearBtn, btnParams);

            devGearBtn.setOnTouchListener(new View.OnTouchListener() {
                private int initialX;
                private int initialY;
                private float initialTouchX;
                private float initialTouchY;
                private boolean isDragging = false;

                @Override
                public boolean onTouch(View v, MotionEvent event) {
                    FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) devGearBtn.getLayoutParams();
                    switch (event.getAction()) {
                        case MotionEvent.ACTION_DOWN:
                            initialX = params.leftMargin;
                            initialY = params.topMargin;
                            initialTouchX = event.getRawX();
                            initialTouchY = event.getRawY();
                            isDragging = false;
                            devGearBtn.setAlpha(1.0f);
                            return true;
                        case MotionEvent.ACTION_MOVE:
                            float dx = event.getRawX() - initialTouchX;
                            float dy = event.getRawY() - initialTouchY;
                            if (Math.abs(dx) > 15 || Math.abs(dy) > 15) {
                                isDragging = true;
                            }
                            if (isDragging) {
                                params.leftMargin = (int) (initialX + dx);
                                params.topMargin = (int) (initialY + dy);
                                
                                params.leftMargin = Math.max(0, Math.min(params.leftMargin, getResources().getDisplayMetrics().widthPixels - devGearBtn.getWidth()));
                                params.topMargin = Math.max(0, Math.min(params.topMargin, getResources().getDisplayMetrics().heightPixels - devGearBtn.getHeight()));
                                
                                devGearBtn.setLayoutParams(params);
                            }
                            return true;
                        case MotionEvent.ACTION_UP:
                            devGearBtn.setAlpha(0.7f);
                            if (!isDragging) {
                                showAdminMenu();
                            }
                            return true;
                    }
                    return false;
                }
            });
        }

        setContentView(mainLayout);

        localHtmlFile = new File(getFilesDir(), "index.html");

        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setDatabaseEnabled(true);
        ws.setCacheMode(WebSettings.LOAD_DEFAULT);
        ws.setAllowFileAccess(true);
        ws.setAllowContentAccess(true);
        ws.setAllowFileAccessFromFileURLs(true);
        ws.setAllowUniversalAccessFromFileURLs(true);
        ws.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        ws.setMediaPlaybackRequiresUserGesture(false);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return assetLoader.shouldInterceptRequest(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                view.loadUrl(url);
                return true;
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage consoleMessage) {
                String log = "[" + consoleMessage.messageLevel() + "] "
                        + consoleMessage.message() + " (строка: "
                        + consoleMessage.lineNumber() + ")";
                addLog(log);
                return true;
            }

            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                runOnUiThread(() -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        request.grant(request.getResources());
                    }
                });
            }

            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback, WebChromeClient.FileChooserParams fileChooserParams) {
                if (uploadMessage != null) {
                    uploadMessage.onReceiveValue(null);
                    uploadMessage = null;
                }
                uploadMessage = filePathCallback;

                Intent intent = fileChooserParams.createIntent();
                try {
                    startActivityForResult(intent, FILECHOOSER_RESULTCODE);
                } catch (ActivityNotFoundException e) {
                    uploadMessage = null;
                    Toast.makeText(MainActivity.this, "Не удалось открыть выбор файлов", Toast.LENGTH_LONG).show();
                    return false;
                }
                return true;
            }
        });

        webView.addJavascriptInterface(new WebAppInterface(), "Android");

        if (BuildConfig.DEBUG) {
            if (localHtmlFile.exists()) {
                webView.loadUrl("https://appassets.androidplatform.net/files/index.html");
            } else {
                loadDevDashboard();
            }
        } else {
            if (!localHtmlFile.exists()) {
                unpackFactoryHTML();
            }
            webView.loadUrl("https://appassets.androidplatform.net/files/index.html");
        }

        pollingHandler = new Handler(Looper.getMainLooper());
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == FILECHOOSER_RESULTCODE) {
            if (uploadMessage == null) return;
            Uri[] results = null;
            if (resultCode == Activity.RESULT_OK && data != null) {
                String dataString = data.getDataString();
                ClipData clipData = data.getClipData();
                if (clipData != null) {
                    results = new Uri[clipData.getItemCount()];
                    for (int i = 0; i < clipData.getItemCount(); i++) {
                        results[i] = clipData.getItemAt(i).getUri();
                    }
                }
                if (dataString != null) {
                    results = new Uri[]{Uri.parse(dataString)};
                }
            }
            uploadMessage.onReceiveValue(results);
            uploadMessage = null;
        } else {
            super.onActivityResult(requestCode, resultCode, data);
        }
    }

    private void addLog(String message) {
        systemLogs.add(message);
        if (systemLogs.size() > 150) systemLogs.remove(0);
    }

    private void showAdminMenu() {
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setBackgroundColor(Color.parseColor("#1c1524"));
        container.setPadding(40, 50, 40, 50);

        TextView title = new TextView(this);
        title.setText("ExteraGram Pro — Панель");
        title.setTextColor(Color.parseColor("#a773d1"));
        title.setTextSize(18);
        title.setTypeface(null, Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 0, 0, 40);
        container.addView(title);

        String[] labels = {
            "📋 Посмотреть дебаг-логи",
            "📁 Проводник файлов",
            "🧹 Сбросить скрипты (Safe Mode)",
            "🔄 Перезагрузить страницу"
        };

        AlertDialog dialog = new AlertDialog.Builder(this)
            .setView(container)
            .create();

        for (int i = 0; i < labels.length; i++) {
            final int index = i;
            Button btn = new Button(this);
            btn.setText(labels[i]);
            btn.setTextColor(Color.WHITE);
            btn.setTextSize(14);
            btn.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            btn.setPadding(40, 20, 40, 20);
            
            GradientDrawable btnShape = new GradientDrawable();
            btnShape.setColor(Color.parseColor("#2d2238"));
            btnShape.setCornerRadius(16);
            btn.setBackground(btnShape);

            LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            );
            btnParams.setMargins(0, 10, 0, 10);
            btn.setLayoutParams(btnParams);

            btn.setOnClickListener(v -> {
                dialog.dismiss();
                switch (index) {
                    case 0: showLogsOverlay(); break;
                    case 1: showFilesManager(); break;
                    case 2: triggerSafeMode(); break;
                    case 3: webView.reload(); break;
                }
            });
            container.addView(btn);
        }

        dialog.show();
    }

    private void showFilesManager() {
        File folder = getFilesDir();
        File[] files = folder.listFiles();
        List<String> fileNames = new ArrayList<>();
        if (files != null) {
            for (File f : files) {
                fileNames.add(f.getName() + " (" + (f.length() / 1024) + " KB)");
            }
        }

        if (fileNames.isEmpty()) {
            new AlertDialog.Builder(this)
                .setTitle("Файловый менеджер")
                .setMessage("Внутри контейнера пока нет загруженных файлов.")
                .setPositiveButton("Ок", null)
                .show();
            return;
        }

        String[] items = fileNames.toArray(new String[0]);
        new AlertDialog.Builder(this)
            .setTitle("Файлы в контейнере")
            .setItems(items, (dialog, which) -> {
                File selected = files[which];
                new AlertDialog.Builder(MainActivity.this)
                    .setTitle("Управление файлом")
                    .setMessage("Удалить файл: " + selected.getName() + "?")
                    .setPositiveButton("Да, удалить", (d, w) -> {
                        selected.delete();
                        Toast.makeText(MainActivity.this, "Файл удален!", Toast.LENGTH_SHORT).show();
                        if (selected.getName().equals("index.html")) loadDevDashboard();
                    })
                    .setNegativeButton("Отмена", null)
                    .show();
            })
            .setPositiveButton("Назад", (dialog, which) -> showAdminMenu())
            .show();
    }

    private void triggerSafeMode() {
        if (localHtmlFile.exists()) {
            localHtmlFile.delete();
        }
        File folder = getFilesDir();
        File[] files = folder.listFiles();
        if (files != null) {
            for (File f : files) f.delete();
        }
        stopPolling();
        loadDevDashboard();
        Toast.makeText(this, "Контейнер полностью очищен до заводского состояния!", Toast.LENGTH_SHORT).show();
    }

    private void loadDevDashboard() {
        String dashboardHtml = "<!DOCTYPE html><html><head><meta charset='UTF-8'>" +
                "<meta name='viewport' content='width=device-width, initial-scale=1.0'>" +
                "<style>body { background:#130e19; color:#fff; font-family:sans-serif; padding:20px; text-align:center; }" +
                "h2 { color:#a773d1; } .card { background:#1e1525; border:1px solid #382b46; padding:20px; border-radius:12px; max-width:480px; margin:20px auto; text-align:left; }" +
                "button, input[type=file] { background:#a773d1; color:#fff; border:none; padding:12px; border-radius:8px; width:100%; font-size:14px; cursor:pointer; margin-top:10px; box-sizing:border-box; }" +
                "textarea { width:100%; height:120px; background:#2d2238; border:1px solid #382b46; border-radius:8px; color:#fff; padding:10px; margin-top:10px; box-sizing:border-box; resize:none; }</style></head>" +
                "<body><h2>❄ ExteraGram Pro Dev-Panel</h2><p>Пустышка ожидает ваши файлы...</p>" +
                "<div class='card' style='border-color: #4caf50;'><h3>Запуск:</h3>" +
                "<p style='font-size:12px;color:#8b7d98;'>Запуск основного файла (index.html) после загрузки всех скриптов:</p>" +
                "<button onclick='Android.launchMessenger()' style='background:#4caf50;'>🚀 Запустить index.html</button></div>" +
                "<div class='card'><h3>Загрузить любой файл в контейнер:</h3>" +
                "<p style='font-size:12px;color:#8b7d98;'>Вы можете загрузить index.html, style.css, bridge.js и др. по отдельности:</p>" +
                "<input type='file' id='filePicker'>" +
                "<textarea id='codePaste' placeholder='Или вставьте код файла сюда...'></textarea>" +
                "<input type='text' id='fileName' placeholder='Имя файла (например, index.html)' style='width:100%;padding:10px;background:#2d2238;color:#fff;border:1px solid #382b46;border-radius:8px;box-sizing:border-box;margin-top:10px;'>" +
                "<button onclick='save()'>Записать файл</button></div>" +
                "<script>" +
                "var chosenName = '';" +
                "document.getElementById('filePicker').onchange = function(e) {" +
                "  var file = e.target.files[0]; if(!file) return;" +
                "  chosenName = file.name;" +
                "  document.getElementById('fileName').value = file.name;" +
                "  var reader = new FileReader();" +
                "  reader.onload = function(evt) { document.getElementById('codePaste').value = evt.target.result; };" +
                "  reader.readAsText(file);" +
                "};" +
                "function save() {" +
                "  var code = document.getElementById('codePaste').value;" +
                "  var name = document.getElementById('fileName').value.trim();" +
                "  if(!code.trim() || !name) { alert('Код или имя файла пусто!'); return; }" +
                "  Android.saveFile(name, code);" +
                "}" +
                "</script></body></html>";

        webView.loadDataWithBaseURL("https://appassets.androidplatform.net/", dashboardHtml, "text/html", "UTF-8", null);
    }

    private void unpackFactoryHTML() {
        try (InputStream is = getAssets().open("index.html");
             OutputStream os = new FileOutputStream(localHtmlFile)) {
            byte[] buffer = new byte[1024];
            int length;
            while ((length = is.read(buffer)) > 0) {
                os.write(buffer, 0, length);
            }
        } catch (IOException e) {
            addLog("Ошибка распаковки заводского HTML: " + e.getMessage());
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            showLogsOverlay();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            triggerSafeMode();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private void showLogsOverlay() {
        runOnUiThread(() -> {
            StringBuilder sb = new StringBuilder();
            for (String log : systemLogs) {
                sb.append(log).append("\n\n");
            }
            new AlertDialog.Builder(this)
                .setTitle("Системный дебаг-лог")
                .setMessage(sb.length() == 0 ? "Логи пусты. Работаем в штатном режиме." : sb.toString())
                .setPositiveButton("Закрыть", null)
                .setNeutralButton("Очистить", (dialog, which) -> systemLogs.clear())
                .show();
        });
    }

    private class WebAppInterface {

        @JavascriptInterface
        public void launchMessenger() {
            runOnUiThread(() -> {
                if (localHtmlFile.exists()) {
                    webView.loadUrl("https://appassets.androidplatform.net/files/index.html");
                } else {
                    Toast.makeText(MainActivity.this, "Файл index.html не найден! Переименуйте ваш HTML в 'index.html' при записи.", Toast.LENGTH_LONG).show();
                }
            });
        }

        @JavascriptInterface
        public void saveFile(final String fileName, final String fileContent) {
            runOnUiThread(() -> {
                File targetFile = new File(getFilesDir(), fileName);
                try (FileWriter writer = new FileWriter(targetFile)) {
                    writer.write(fileContent);
                    Toast.makeText(MainActivity.this, "Файл " + fileName + " сохранен в контейнер!", Toast.LENGTH_SHORT).show();
                    if (fileName.equals("index.html")) {
                        webView.loadUrl("https://appassets.androidplatform.net/files/index.html");
                    }
                } catch (IOException e) {
                    Toast.makeText(MainActivity.this, "Ошибка записи файла " + fileName + ": " + e.getMessage(), Toast.LENGTH_LONG).show();
                }
            });
        }

        @JavascriptInterface
        public void saveHtml(final String htmlContent) {
            saveFile("index.html", htmlContent);
        }

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
                        msg.setText(encryptedPayload + "\r\n", "UTF-8", "plain");

                        Transport.send(msg);

                    } catch (Exception e) {
                        notifyJS("extgram_error", "{\"msg\":\"SMTP: " + escapeJson(e.getMessage()) + "\"}");
                    }
                }
            }).start();
        }

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

            stopPolling();
            if (!senderEmail.isEmpty() && !appPassword.isEmpty()) {
                startPolling();
            }
        }

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

                    String firstLine = body.split("\\r?\\n")[0].trim();
                    if (firstLine.isEmpty()) continue;

                    payloads.add(firstLine);
                    message.setFlag(Flags.Flag.SEEN, true);

                } catch (Exception e) {
                    addLog("Ошибка парсинга письма: " + e.getMessage());
                }
            }

            inbox.close(true);
            store.close();

            if (payloads.isEmpty()) return "[]";

            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < payloads.size(); i++) {
                if (i > 0) sb.append(",");
                sb.append(payloads.get(i));
            }
            sb.append("]");
            return sb.toString();

        } catch (Exception e) {
            addLog("IMAP Polling Error: " + e.getMessage());
            try { if (inbox != null && inbox.isOpen()) inbox.close(false); } catch (Exception ignored) {}
            try { if (store != null && store.isConnected()) store.close(); } catch (Exception ignored) {}
            return "[]";
        }
    }

    private void notifyJS(final String event, final String jsonDetail) {
        webView.post(new Runnable() {
            @Override
            public void run() {
                if (event.equals("extgram_drain")) {
                    webView.evaluateJavascript(
                        "window.ExteraGram && typeof window.ExteraGram.drainQueue === 'function' && window.ExteraGram.drainQueue()",
                        null
                    );
                } else {
                    webView.evaluateJavascript(
                        "window.dispatchEvent(new CustomEvent('" + event + "',{detail:" + jsonDetail + "}))",
                        null
                    );
                }
            }
        });
    }

    private static String escapeJson(String s) {
        if (s == null) return "null";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }

    @Override
    protected void onPause() {
        super.onPause();
        webView.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        webView.onResume();
        webView.post(new Runnable() {
            @Override
            public void run() {
                notifyJS("extgram_drain", null);
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
