package com.nan0gram;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
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
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.webkit.WebViewAssetLoader;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
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
                requestPermissions(new String[]{
                    android.Manifest.permission.RECORD_AUDIO, 
                    android.Manifest.permission.MODIFY_AUDIO_SETTINGS
                }, 2);
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

        // Плавающая перетаскиваемая кнопка
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
                            if (Math.abs(dx) > 15 || Math.abs(dy) > 15) isDragging = true;
                            if (isDragging) {
                                params.leftMargin = (int) (initialX + dx);
                                params.topMargin = (int) (initialY + dy);
                                params.leftMargin = Math.max(0, Math.min(params.leftMargin, mainLayout.getWidth() - devGearBtn.getWidth()));
                                params.topMargin = Math.max(0, Math.min(params.topMargin, mainLayout.getHeight() - devGearBtn.getHeight()));
                                devGearBtn.setLayoutParams(params);
                            }
                            return true;
                        case MotionEvent.ACTION_UP:
                            devGearBtn.setAlpha(0.7f);
                            if (!isDragging) {
                                loadDevDashboard();
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
                    try {
                        request.grant(request.getResources());
                        addLog("[WEB_RTC] Разрешен доступ: " + request.getResources()[0]);
                    } catch (Exception e) {
                        addLog("[WEB_RTC] Ошибка доступа: " + e.getMessage());
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

        loadApp();

        pollingHandler = new Handler(Looper.getMainLooper());
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == 2) {
            loadApp();
        }
    }

    private void loadApp() {
        if (BuildConfig.DEBUG) {
            if (localHtmlFile.exists()) {
                // Если файлы оригиналы есть, готовим рантайм-копии и запускаем
                prepareRuntimeAndApplyPatches();
                webView.loadUrl("https://appassets.androidplatform.net/files/runtime/index.html");
            } else {
                loadDevDashboard();
            }
        } else {
            if (!localHtmlFile.exists()) {
                unpackFactoryHTML();
            }
            prepareRuntimeAndApplyPatches();
            webView.loadUrl("https://appassets.androidplatform.net/files/runtime/index.html");
        }
    }

    /* ══════════════════════════════════════════════════════════════
       СПАСАТЕЛЬНЫЙ КРУГ — ЗАЩИТА ОТ КНОПКИ НАЗАД
       ══════════════════════════════════════════════════════════════ */
    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            new AlertDialog.Builder(this)
                .setTitle("Выйти из nan0gram?")
                .setMessage("Вы действительно хотите закрыть приложение?")
                .setPositiveButton("Да, выйти", (dialog, which) -> {
                    stopPolling();
                    finish();
                })
                .setNegativeButton("Отмена", null)
                .show();
        }
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
        if (systemLogs.size() > 300) systemLogs.remove(0);
    }

    private void loadDevDashboard() {
        webView.loadUrl("https://appassets.androidplatform.net/assets/dev_dashboard.html");
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
            loadDevDashboard();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            triggerSafeMode();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private void triggerSafeMode() {
        File folder = getFilesDir();
        File[] files = folder.listFiles();
        if (files != null) {
            for (File f : files) f.delete();
        }
        stopPolling();
        loadDevDashboard();
        Toast.makeText(this, "Safe Mode: Сброс выполнен!", Toast.LENGTH_SHORT).show();
    }

    /* ══════════════════════════════════════════════════════════════
       РАНТАЙМ-ПАТЧЕР: КОПИРОВАНИЕ И ИЗМЕНЕНИЕ ФАЙЛОВ КОНТЕЙНЕРА НА ЛЕТУ
       ══════════════════════════════════════════════════════════════ */
    private void prepareRuntimeAndApplyPatches() {
        File filesDir = getFilesDir();
        File runtimeDir = new File(filesDir, "runtime");

        // 1. Очищаем старую папку запуска
        deleteRecursive(runtimeDir);
        runtimeDir.mkdirs();

        // 2. Копируем все ваши оригиналы из files в files/runtime
        File[] originals = filesDir.listFiles();
        if (originals != null) {
            for (File f : originals) {
                // Игнорируем вложенные папки самого рантайма и папку патчей при копировании
                if (!f.getName().equals("runtime") && !f.getName().equals("patches")) {
                    copyRecursive(f, new File(runtimeDir, f.getName()));
                }
            }
        }

        // 3. Считываем и применяем JSON-патч к копиям в runtime
        applyPatchesToRuntime(runtimeDir);
    }

    private void copyRecursive(File src, File dest) {
        if (src.isDirectory()) {
            dest.mkdirs();
            File[] children = src.listFiles();
            if (children != null) {
                for (File c : children) {
                    copyRecursive(c, new File(dest, c.getName()));
                }
            }
        } else {
            try (InputStream is = new FileInputStream(src);
                 OutputStream os = new FileOutputStream(dest)) {
                byte[] buf = new byte[1024];
                int len;
                while ((len = is.read(buf)) > 0) {
                    os.write(buf, 0, len);
                }
            } catch (IOException e) {
                addLog("[PATCH_ERR] Не удалось скопировать " + src.getName() + ": " + e.getMessage());
            }
        }
    }

    private void applyPatchesToRuntime(File runtimeDir) {
        File patchFile = new File(getFilesDir(), "patches/patches.json");
        if (!patchFile.exists()) return;

        try {
            // Читаем patches.json
            BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(patchFile), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            br.close();

            JSONArray patches = new JSONArray(sb.toString());
            for (int i = 0; i < patches.length(); i++) {
                JSONObject patch = patches.getJSONObject(i);
                String targetFileName = patch.getString("file");
                String findText = patch.getString("find");
                String replaceText = patch.getString("replace");

                File targetFile = new File(runtimeDir, targetFileName);
                // Мы применяем патчи к файлам, которые лежат в корне или в подпапках runtime
                if (targetFile.exists() && !targetFile.isDirectory()) {
                    // Считываем текущую копию
                    BufferedReader fileReader = new BufferedReader(new InputStreamReader(new FileInputStream(targetFile), "UTF-8"));
                    StringBuilder fileContent = new StringBuilder();
                    String fileLine;
                    while ((fileLine = fileReader.readLine()) != null) {
                        fileContent.append(fileLine).append("\n");
                    }
                    fileReader.close();

                    // Осуществляем замену в рантайм-копии
                    String modified = fileContent.toString().replace(findText, replaceText);

                    // Перезаписываем временную копию
                    try (FileWriter fw = new FileWriter(targetFile)) {
                        fw.write(modified);
                    }
                    addLog("[PATCH_ENGINE] Успешно наложен патч на копию: " + targetFileName);
                }
            }
        } catch (Exception e) {
            addLog("[PATCH_ENGINE_ERR] Ошибка наложения патчей: " + e.getMessage());
        }
    }

    /* ══════════════════════════════════════════════════════════════
       JavaScript Interface (JS Мост для Файлов, Папок и Логов)
       ══════════════════════════════════════════════════════════════ */
    private class WebAppInterface {

        @JavascriptInterface
        public String getFileTree() {
            try {
                JSONArray arr = new JSONArray();
                traverse(getFilesDir(), arr, "");
                return arr.toString();
            } catch (Exception e) {
                return "[]";
            }
        }

        private void traverse(File dir, JSONArray arr, String relativePath) {
            File[] files = dir.listFiles();
            if (files != null) {
                for (File f : files) {
                    try {
                        JSONObject obj = new JSONObject();
                        String path = relativePath.isEmpty() ? f.getName() : relativePath + "/" + f.getName();
                        obj.put("name", f.getName());
                        obj.put("path", path);
                        obj.put("isDirectory", f.isDirectory());
                        obj.put("size", f.length());
                        arr.put(obj);
                        if (f.isDirectory()) {
                            traverse(f, arr, path);
                        }
                    } catch (Exception ignored) {}
                }
            }
        }

        @JavascriptInterface
        public boolean saveFile(String path, String content) {
            File file = new File(getFilesDir(), path);
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            try (FileWriter fw = new FileWriter(file)) {
                fw.write(content);
                return true;
            } catch (IOException e) {
                addLog("[FILES_ERR] Не удалось записать " + path + ": " + e.getMessage());
                return false;
            }
        }

        @JavascriptInterface
        public String readFile(String path) {
            File file = new File(getFilesDir(), path);
            if (!file.exists() || file.isDirectory()) return "";
            try {
                BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(file), "UTF-8"));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) {
                    sb.append(line).append("\n");
                }
                br.close();
                return sb.toString();
            } catch (Exception e) {
                return "";
            }
        }

        @JavascriptInterface
        public boolean createFolder(String path) {
            File folder = new File(getFilesDir(), path);
            return folder.mkdirs();
        }

        @JavascriptInterface
        public boolean deletePath(String path) {
            File file = new File(getFilesDir(), path);
            return deleteRecursive(file);
        }

        private boolean deleteRecursive(File f) {
            if (f.isDirectory()) {
                File[] children = f.listFiles();
                if (children != null) {
                    for (File c : children) deleteRecursive(c);
                }
            }
            return f.delete();
        }

        @JavascriptInterface
        public String getLogs() {
            JSONArray arr = new JSONArray();
            for (String log : systemLogs) {
                arr.put(log);
            }
            return arr.toString();
        }

        @JavascriptInterface
        public void clearLogs() {
            systemLogs.clear();
        }

        @JavascriptInterface
        public void launchMessenger() {
            runOnUiThread(() -> {
                if (localHtmlFile.exists()) {
                    prepareRuntimeAndApplyPatches();
                    webView.loadUrl("https://appassets.androidplatform.net/files/runtime/index.html");
                } else {
                    Toast.makeText(MainActivity.this, "Файл index.html не найден!", Toast.LENGTH_SHORT).show();
                }
            });
        }

        @JavascriptInterface
        public void sendEmail(final String encryptedPayload) {
            new Thread(() -> {
                try {
                    Properties props = new Properties();
                    props.put("mail.smtp.host", "smtp.gmail.com");
                    props.put("mail.smtp.port", "465");
                    props.put("mail.smtp.auth", "true");
                    props.put("mail.smtp.socketFactory.port", "465");
                    props.put("mail.smtp.socketFactory.class", "javax.net.ssl.SSLSocketFactory");
                    Session session = Session.getInstance(props, new Authenticator() {
                        protected PasswordAuthentication getPasswordAuthentication() {
                            return new PasswordAuthentication(senderEmail, appPassword);
                        }
                    });
                    MimeMessage msg = new MimeMessage(session);
                    msg.setFrom(new InternetAddress(senderEmail));
                    msg.addRecipient(Message.RecipientType.TO, new InternetAddress(receiverEmail));
                    msg.setSubject(subjectPrefix + " MSG");
                    msg.setText(encryptedPayload + "\r\n", "UTF-8", "plain");
                    Transport.send(msg);
                } catch (Exception e) {
                    notifyJS("extgram_error", "{\"msg\":\"SMTP: " + escapeJson(e.getMessage()) + "\"}");
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
                notifyJS("extgram_error", "{\"msg\":\"configure err\"}");
                return;
            }
            stopPolling();
            if (!senderEmail.isEmpty() && !appPassword.isEmpty()) startPolling();
        }

        @JavascriptInterface
        public String getDeviceInfo() {
            try {
                JSONObject info = new JSONObject();
                info.put("platform", "android");
                info.put("model", Build.MODEL);
                return info.toString();
            } catch (Exception e) { return "{}"; }
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
        new Thread(() -> {
            String json = fetchNewEmailsBlocking();
            if (json != null && !json.equals("[]")) {
                webView.post(() -> webView.evaluateJavascript("window.ExteraGram&&window.ExteraGram.onEmailReceived(" + json + ")", null));
            }
        }).start();
    }

    private String fetchNewEmailsBlocking() {
        Store store = null; Folder inbox = null;
        try {
            Properties props = new Properties();
            props.put("mail.imap.host", "imap.gmail.com");
            props.put("mail.imap.port", "993");
            props.put("mail.imap.ssl.enable", "true");
            Session session = Session.getInstance(props, new Authenticator() {
                protected PasswordAuthentication getPasswordAuthentication() {
                    return new PasswordAuthentication(senderEmail, appPassword);
                }
            });
            store = session.getStore("imap");
            store.connect("imap.gmail.com", senderEmail, appPassword);
            inbox = store.getFolder("INBOX");
            inbox.open(Folder.READ_WRITE);

            Message[] messages = inbox.search(new javax.mail.search.AndTerm(
                new FlagTerm(new Flags(Flags.Flag.SEEN), false),
                new SubjectTerm(subjectPrefix)
            ));
            if (messages.length == 0) return "[]";

            List<String> payloads = new ArrayList<>();
            for (Message message : messages) {
                try {
                    Object content = message.getContent();
                    String body = content instanceof String ? (String) content :
                            (content instanceof java.io.InputStream ? new BufferedReader(new InputStreamReader((java.io.InputStream) content, "UTF-8")).readLine() : content.toString());
                    if (body == null || body.trim().isEmpty()) continue;
                    String firstLine = body.split("\r?\n")[0].trim();
                    if (!firstLine.isEmpty()) { payloads.add(firstLine); message.setFlag(Flags.Flag.SEEN, true); }
                } catch (Exception ignored) {}
            }
            inbox.close(true); store.close();
            if (payloads.isEmpty()) return "[]";
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < payloads.size(); i++) {
                if (i > 0) sb.append(","); sb.append(payloads.get(i));
            }
            return sb.append("]").toString();
        } catch (Exception e) {
            try { if (inbox != null && inbox.isOpen()) inbox.close(false); } catch (Exception ignored) {}
            try { if (store != null && store.isConnected()) store.close(); } catch (Exception ignored) {}
            return "[]";
        }
    }

    private void notifyJS(final String event, final String jsonDetail) {
        webView.post(() -> {
            if (event.equals("extgram_drain")) {
                webView.evaluateJavascript("window.ExteraGram && typeof window.ExteraGram.drainQueue === 'function' && window.ExteraGram.drainQueue()", null);
            } else {
                webView.evaluateJavascript("window.dispatchEvent(new CustomEvent('" + event + "',{detail:" + jsonDetail + "}))", null);
            }
        });
    }

    private static String escapeJson(String s) {
        if (s == null) return "null";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }

    @Override
    protected void onPause() { super.onPause(); webView.onPause(); }

    @Override
    protected void onResume() { super.onResume(); webView.onResume(); webView.post(() -> notifyJS("extgram_drain", null)); }

    @Override
    protected void onDestroy() { stopPolling(); webView.stopLoading(); webView.destroy(); super.onDestroy(); }

    @Override
    public void onBackPressed() { if (webView.canGoBack()) webView.goBack(); else super.onBackPressed(); }
}
