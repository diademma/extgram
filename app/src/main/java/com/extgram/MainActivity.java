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

        if (BuildConfig.DEBUG) {
            Button devGearBtn = new Button(this);
            devGearBtn.setText("⚙");
            devGearBtn.setTextColor(Color.WHITE);
            devGearBtn.setTextSize(22);
            
            GradientDrawable shape = new GradientDrawable();
            shape.setShape(GradientDrawable.OVAL);
            shape.setColor(Color.parseColor("#a0a773d1"));
            devGearBtn.setBackground(shape);

            int screenWidth = getResources().getDisplayMetrics().widthPixels;
            int screenHeight = getResources().getDisplayMetrics().heightPixels;

            FrameLayout.LayoutParams btnParams = new FrameLayout.LayoutParams(120, 120);
            btnParams.gravity = Gravity.TOP | Gravity.START;
            btnParams.leftMargin = screenWidth - 150;
            btnParams.topMargin = screenHeight - 300;
            mainLayout.addView(devGearBtn, btnParams);

            devGearBtn.setOnTouchListener(new View.OnTouchListener() {
                private int initialX, initialY;
                private float initialTouchX, initialTouchY;
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
                            devGearBtn.setAlpha(0.6f);
                            if (!isDragging) showAdminMenu();
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
            public boolean onConsoleMessage(ConsoleMessage cm) {
                addLog("[" + cm.messageLevel() + "] " + cm.message() + " (line " + cm.lineNumber() + ")");
                return true;
            }

            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                runOnUiThread(() -> {
                    try {
                        request.grant(request.getResources());
                        addLog("[SYS] Permission granted: " + request.getResources()[0]);
                    } catch (Exception e) {
                        addLog("[SYS] Permission grant error: " + e.getMessage());
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
                    Toast.makeText(MainActivity.this, "Нет проводника", Toast.LENGTH_LONG).show();
                    return false;
                }
                return true;
            }
        });

        webView.addJavascriptInterface(new WebAppInterface(), "Android");

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, 2);
            } else {
                loadApp();
            }
        } else {
            loadApp();
        }

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
                webView.loadUrl("https://appassets.androidplatform.net/files/index.html");
            } else {
                loadDevDashboard();
            }
        } else {
            if (!localHtmlFile.exists()) unpackFactoryHTML();
            webView.loadUrl("https://appassets.androidplatform.net/files/index.html");
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
                    for (int i = 0; i < clipData.getItemCount(); i++) results[i] = clipData.getItemAt(i).getUri();
                }
                if (dataString != null) results = new Uri[]{Uri.parse(dataString)};
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

    private void showAdminMenu() {
        String[] options = {"📋 Системные логи", "📂 Проводник и Редактор", "🧹 Сбросить Safe Mode", "🔄 Обновить страницу"};
        new AlertDialog.Builder(this).setTitle("Управление контейнером").setItems(options, (dialog, which) -> {
            switch (which) {
                case 0: showLogsFullscreen(); break;
                case 1: showFileManager(); break;
                case 2: triggerSafeMode(); break;
                case 3: webView.reload(); break;
            }
        }).show();
    }

    private void showLogsFullscreen() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_NoActionBar_Fullscreen);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setBackgroundColor(Color.parseColor("#130e19"));

        LinearLayout header = new LinearLayout(this);
        header.setBackgroundColor(Color.parseColor("#1c1524"));
        header.setPadding(30, 40, 30, 40);

        TextView title = new TextView(this);
        title.setText("Отладка (Logs)");
        title.setTextColor(Color.WHITE);
        title.setTextSize(20);
        title.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        Button btnCopy = new Button(this);
        btnCopy.setText("Скопировать");
        btnCopy.setBackgroundColor(Color.parseColor("#4caf50"));
        btnCopy.setTextColor(Color.WHITE);

        Button btnClose = new Button(this);
        btnClose.setText("X");
        btnClose.setBackgroundColor(Color.TRANSPARENT);
        btnClose.setTextColor(Color.WHITE);

        header.addView(title);
        header.addView(btnCopy);
        header.addView(btnClose);
        layout.addView(header);

        TextView logText = new TextView(this);
        StringBuilder sb = new StringBuilder();
        for (String log : systemLogs) sb.append(log).append("\n\n");
        logText.setText(sb.length() == 0 ? "Логи пусты." : sb.toString());
        logText.setTextColor(Color.parseColor("#a773d1"));
        logText.setTypeface(Typeface.MONOSPACE);
        logText.setPadding(20, 20, 20, 20);
        logText.setTextIsSelectable(true);

        ScrollView sv = new ScrollView(this);
        sv.addView(logText);
        layout.addView(sv);

        AlertDialog dialog = builder.setView(layout).create();

        btnCopy.setOnClickListener(v -> {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = ClipData.newPlainText("logs", logText.getText().toString());
            clipboard.setPrimaryClip(clip);
            Toast.makeText(this, "Логи скопированы!", Toast.LENGTH_SHORT).show();
        });

        btnClose.setOnClickListener(v -> dialog.dismiss());
        dialog.show();
    }

    private void showFileManager() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_NoActionBar_Fullscreen);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setBackgroundColor(Color.parseColor("#130e19"));

        LinearLayout header = new LinearLayout(this);
        header.setBackgroundColor(Color.parseColor("#1c1524"));
        header.setPadding(30, 40, 30, 40);

        TextView title = new TextView(this);
        title.setText("Проводник");
        title.setTextColor(Color.WHITE);
        title.setTextSize(20);
        title.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        Button btnNew = new Button(this);
        btnNew.setText("+ Файл");
        btnNew.setBackgroundColor(Color.parseColor("#a773d1"));
        btnNew.setTextColor(Color.WHITE);

        Button btnClose = new Button(this);
        btnClose.setText("X");
        btnClose.setBackgroundColor(Color.TRANSPARENT);
        btnClose.setTextColor(Color.WHITE);

        header.addView(title);
        header.addView(btnNew);
        header.addView(btnClose);
        layout.addView(header);

        ScrollView sv = new ScrollView(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(20, 20, 20, 20);

        File[] files = getFilesDir().listFiles();
        if (files != null) {
            for (File f : files) {
                Button fBtn = new Button(this);
                fBtn.setText("📄 " + f.getName() + " (" + (f.length() / 1024) + " KB)");
                fBtn.setAllCaps(false);
                fBtn.setTextColor(Color.WHITE);
                fBtn.setBackgroundColor(Color.parseColor("#2d2238"));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                lp.setMargins(0, 10, 0, 10);
                fBtn.setLayoutParams(lp);

                fBtn.setOnClickListener(v -> showCodeEditor(f.getName()));
                fBtn.setOnLongClickListener(v -> {
                    new AlertDialog.Builder(this)
                        .setTitle("Удалить " + f.getName() + "?")
                        .setPositiveButton("Удалить", (d, w) -> { f.delete(); showFileManager(); })
                        .setNegativeButton("Отмена", null)
                        .show();
                    return true;
                });
                list.addView(fBtn);
            }
        }
        sv.addView(list);
        layout.addView(sv);

        AlertDialog dialog = builder.setView(layout).create();

        btnNew.setOnClickListener(v -> {
            EditText input = new EditText(this);
            input.setHint("Например: script.js");
            input.setTextColor(Color.BLACK);
            new AlertDialog.Builder(this)
                .setTitle("Имя нового файла")
                .setView(input)
                .setPositiveButton("Создать", (d, w) -> {
                    String name = input.getText().toString().trim();
                    if (!name.isEmpty()) {
                        dialog.dismiss();
                        showCodeEditor(name);
                    }
                }).show();
        });

        btnClose.setOnClickListener(v -> dialog.dismiss());
        dialog.show();
    }

    private void showCodeEditor(String fileName) {
        File file = new File(getFilesDir(), fileName);
        String content = "";
        if (file.exists()) {
            try {
                BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(file)));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line).append("\n");
                br.close();
                content = sb.toString();
            } catch (Exception e) { e.printStackTrace(); }
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_NoActionBar_Fullscreen);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setBackgroundColor(Color.parseColor("#130e19"));

        LinearLayout header = new LinearLayout(this);
        header.setBackgroundColor(Color.parseColor("#1c1524"));
        header.setPadding(30, 40, 30, 40);

        TextView title = new TextView(this);
        title.setText(fileName);
        title.setTextColor(Color.WHITE);
        title.setTextSize(18);
        title.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        Button btnSave = new Button(this);
        btnSave.setText("Сохранить");
        btnSave.setBackgroundColor(Color.parseColor("#4caf50"));
        btnSave.setTextColor(Color.WHITE);

        Button btnClose = new Button(this);
        btnClose.setText("X");
        btnClose.setBackgroundColor(Color.TRANSPARENT);
        btnClose.setTextColor(Color.WHITE);

        header.addView(title);
        header.addView(btnSave);
        header.addView(btnClose);
        layout.addView(header);

        EditText editor = new EditText(this);
        editor.setText(content);
        editor.setTextColor(Color.parseColor("#e0e0e0"));
        editor.setBackgroundColor(Color.TRANSPARENT);
        editor.setGravity(Gravity.TOP | Gravity.START);
        editor.setTypeface(Typeface.MONOSPACE);
        editor.setHorizontallyScrolling(true);

        ScrollView sv = new ScrollView(this);
        HorizontalScrollView hsv = new HorizontalScrollView(this);
        hsv.addView(editor, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT));
        sv.addView(hsv, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT));
        layout.addView(sv);

        AlertDialog dialog = builder.setView(layout).create();

        btnSave.setOnClickListener(v -> {
            try {
                FileWriter fw = new FileWriter(file);
                fw.write(editor.getText().toString());
                fw.close();
                Toast.makeText(this, "Успешно сохранено!", Toast.LENGTH_SHORT).show();
                if (fileName.equals("index.html") || fileName.endsWith(".js") || fileName.endsWith(".css")) {
                    webView.reload();
                }
            } catch (Exception e) {
                Toast.makeText(this, "Ошибка: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        });

        btnClose.setOnClickListener(v -> dialog.dismiss());
        dialog.show();
    }

    private void triggerSafeMode() {
        File folder = getFilesDir();
        File[] files = folder.listFiles();
        if (files != null) for (File f : files) f.delete();
        stopPolling();
        loadDevDashboard();
        Toast.makeText(this, "Safe Mode: Полная очистка!", Toast.LENGTH_SHORT).show();
    }

    private void loadDevDashboard() {
        String dashboardHtml = "<!DOCTYPE html><html><head><meta charset='UTF-8'>" +
                "<meta name='viewport' content='width=device-width, initial-scale=1.0'>" +
                "<style>body{background:#130e19;color:#fff;font-family:sans-serif;padding:20px;text-align:center;} h2{color:#a773d1;} " +
                ".card{background:#1e1525;border:1px solid #382b46;padding:20px;border-radius:12px;margin:20px auto;}</style></head>" +
                "<body><h2>❄ ExteraGram Dev</h2><p>Пустышка ожидает код...</p>" +
                "<div class='card'><p>Нажмите <b>⚙ Шестеренку</b>, выберите <b>Проводник</b> и создайте файл <b>index.html</b>.</p></div></body></html>";
        webView.loadDataWithBaseURL("https://appassets.androidplatform.net/", dashboardHtml, "text/html", "UTF-8", null);
    }

    private void unpackFactoryHTML() {
        try (InputStream is = getAssets().open("index.html");
             OutputStream os = new FileOutputStream(localHtmlFile)) {
            byte[] buffer = new byte[1024];
            int length;
            while ((length = is.read(buffer)) > 0) os.write(buffer, 0, length);
        } catch (IOException e) {
            addLog("Factory unpack error: " + e.getMessage());
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            showLogsFullscreen();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            triggerSafeMode();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private class WebAppInterface {
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
