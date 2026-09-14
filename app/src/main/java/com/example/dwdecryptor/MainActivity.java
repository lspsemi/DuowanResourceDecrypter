package com.example.dwdecryptor;

import android.app.Activity;
import android.content.SharedPreferences;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;

import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final int REQ_OPEN_TREE = 1001;
    private static final int REQ_CREATE_LOG = 1002;
    private static final int LOG_FLUSH_DELAY_MS = 200;
    private static final String PREFS = "dw_decryptor";
    private static final String PREF_CHINESE = "chinese";

    private TextView titleView;
    private TextView hintView;
    private TextView logView;
    private ScrollView logScroll;
    private Button pickButton;
    private Button saveLogButton;
    private Switch languageSwitch;
    private CheckBox repairMapDbBox;
    private Spinner repairModeSpinner;
    private ArrayAdapter<String> repairModeAdapter;
    private CheckBox keepRepairArtifactsBox;
    private CheckBox analyzeMapWalBox;
    private CheckBox analyzeUserKeyBox;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final StringBuilder logBuffer = new StringBuilder();
    private final StringBuilder pendingLog = new StringBuilder();
    private boolean logFlushScheduled;
    private boolean autoFollowLog = true;
    private boolean programmaticLogScroll;
    private boolean chinese;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
    }

    private void buildUi() {
        chinese = loadInitialLanguage();
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(32, 32, 32, 32);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        titleView = new TextView(this);
        titleView.setTextSize(22);
        titleView.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, -2);
        titleParams.weight = 1;
        header.addView(titleView, titleParams);

        languageSwitch = new Switch(this);
        languageSwitch.setChecked(chinese);
        languageSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            chinese = isChecked;
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(PREF_CHINESE, chinese).apply();
            updateTexts();
            append(chinese ? "语言已切换为中文" : "Language switched to English");
        });
        header.addView(languageSwitch, new LinearLayout.LayoutParams(-2, -2));
        root.addView(header, new LinearLayout.LayoutParams(-1, -2));

        hintView = new TextView(this);
        hintView.setPadding(0, 18, 0, 18);
        root.addView(hintView, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);

        pickButton = new Button(this);
        pickButton.setOnClickListener(v -> openTree());
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(0, -2);
        buttonParams.weight = 1;
        actions.addView(pickButton, buttonParams);

        saveLogButton = new Button(this);
        saveLogButton.setOnClickListener(v -> createLogFile());
        LinearLayout.LayoutParams saveParams = new LinearLayout.LayoutParams(0, -2);
        saveParams.weight = 1;
        saveParams.leftMargin = 16;
        actions.addView(saveLogButton, saveParams);

        root.addView(actions, new LinearLayout.LayoutParams(-1, -2));

        repairMapDbBox = new CheckBox(this);
        repairMapDbBox.setChecked(true);
        repairMapDbBox.setPadding(0, 12, 0, 0);
        root.addView(repairMapDbBox, new LinearLayout.LayoutParams(-1, -2));

        repairModeSpinner = new Spinner(this);
        repairModeAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, repairModeItems());
        repairModeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        repairModeSpinner.setAdapter(repairModeAdapter);
        repairModeSpinner.setSelection(0);
        repairModeSpinner.setEnabled(repairMapDbBox.isChecked());
        root.addView(repairModeSpinner, new LinearLayout.LayoutParams(-1, -2));
        repairMapDbBox.setOnCheckedChangeListener((buttonView, isChecked) ->
                repairModeSpinner.setEnabled(isChecked));

        keepRepairArtifactsBox = new CheckBox(this);
        keepRepairArtifactsBox.setChecked(false);
        keepRepairArtifactsBox.setPadding(0, 4, 0, 0);
        root.addView(keepRepairArtifactsBox, new LinearLayout.LayoutParams(-1, -2));

        analyzeMapWalBox = new CheckBox(this);
        analyzeMapWalBox.setChecked(false);
        analyzeMapWalBox.setPadding(0, 4, 0, 0);
        root.addView(analyzeMapWalBox, new LinearLayout.LayoutParams(-1, -2));

        analyzeUserKeyBox = new CheckBox(this);
        analyzeUserKeyBox.setChecked(false);
        analyzeUserKeyBox.setPadding(0, 4, 0, 0);
        root.addView(analyzeUserKeyBox, new LinearLayout.LayoutParams(-1, -2));

        logScroll = new ScrollView(this) {
            @Override
            protected void onScrollChanged(int l, int t, int oldl, int oldt) {
                super.onScrollChanged(l, t, oldl, oldt);
                if (!programmaticLogScroll && isLogAtBottom()) {
                    autoFollowLog = true;
                }
            }
        };
        logScroll.setOnTouchListener((view, event) -> {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_MOVE) {
                autoFollowLog = false;
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                if (isLogAtBottom()) {
                    autoFollowLog = true;
                }
            }
            return false;
        });
        logView = new TextView(this);
        logView.setTextIsSelectable(false);
        logScroll.addView(logView);
        LinearLayout.LayoutParams logParams = new LinearLayout.LayoutParams(-1, 0);
        logParams.weight = 1;
        logParams.topMargin = 24;
        root.addView(logScroll, logParams);

        setContentView(root);
        updateTexts();
        logView.setText(t("Ready") + "\n");
    }

    private void openTree() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        startActivityForResult(intent, REQ_OPEN_TREE);
    }

    private void createLogFile() {
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_TITLE, "dw_decrypt_log_" + stamp + ".txt");
        startActivityForResult(intent, REQ_CREATE_LOG);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) {
            return;
        }
        Uri uri = data.getData();
        if (uri == null) {
            return;
        }
        if (requestCode == REQ_CREATE_LOG) {
            saveLog(uri);
            return;
        }
        if (requestCode != REQ_OPEN_TREE) {
            return;
        }
        getContentResolver().takePersistableUriPermission(uri,
                data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION));
        boolean repairMapDb = repairMapDbBox.isChecked();
        DwDecryptor.MapDbRepairMode repairMode = repairModeSpinner.getSelectedItemPosition() == 1
                ? DwDecryptor.MapDbRepairMode.DECRYPT_MVILLAGES
                : DwDecryptor.MapDbRepairMode.DELETE_MVILLAGES;
        boolean keepRepairArtifacts = keepRepairArtifactsBox.isChecked();
        boolean analyzeMapWal = analyzeMapWalBox.isChecked();
        boolean analyzeUserKey = analyzeUserKeyBox.isChecked();
        pickButton.setEnabled(false);
        append(t("Selected") + ": " + uri);
        new Thread(() -> runDecrypt(uri, repairMapDb, repairMode, keepRepairArtifacts,
                analyzeMapWal, analyzeUserKey)).start();
    }

    private void saveLog(Uri uri) {
        try (OutputStream stream = getContentResolver().openOutputStream(uri)) {
            if (stream == null) {
                append(t("Failed to save log") + ": " + t("cannot open output"));
                return;
            }
            stream.write(currentLogText().getBytes(StandardCharsets.UTF_8));
            append(t("Log saved") + ": " + uri);
        } catch (Exception e) {
            append(t("Failed to save log") + ": " + e.getMessage());
        }
    }

    private void runDecrypt(Uri uri, boolean repairMapDb, DwDecryptor.MapDbRepairMode repairMode,
                            boolean keepRepairArtifacts, boolean analyzeMapWal,
                            boolean analyzeUserKey) {
        try {
            new DwDecryptor(this, this::append, repairMapDb, repairMode,
                    keepRepairArtifacts, analyzeMapWal, analyzeUserKey, chinese).decryptTree(uri);
        } catch (Throwable t) {
            StringWriter sw = new StringWriter();
            t.printStackTrace(new PrintWriter(sw));
            append(t("ERROR") + "\n" + sw);
        } finally {
            runOnUiThread(() -> pickButton.setEnabled(true));
        }
    }

    private void append(String text) {
        synchronized (pendingLog) {
            pendingLog.append(text).append('\n');
            if (logFlushScheduled) {
                return;
            }
            logFlushScheduled = true;
        }
        mainHandler.postDelayed(this::flushLog, LOG_FLUSH_DELAY_MS);
    }

    private void flushLog() {
        String chunk;
        synchronized (pendingLog) {
            chunk = pendingLog.toString();
            pendingLog.setLength(0);
            logFlushScheduled = false;
        }
        if (chunk.length() == 0) {
            return;
        }

        boolean followBottom = autoFollowLog;
        logBuffer.append(chunk);
        logView.append(chunk);
        if (followBottom) {
            scrollLogToBottom();
        }
    }

    private void scrollLogToBottom() {
        if (logScroll == null) {
            return;
        }
        programmaticLogScroll = true;
        logScroll.post(() -> {
            int scrollY = Math.max(0, logView.getMeasuredHeight() - logScroll.getHeight());
            logScroll.scrollTo(0, scrollY);
            programmaticLogScroll = false;
            autoFollowLog = isLogAtBottom();
        });
    }

    private boolean isLogAtBottom() {
        if (logScroll == null || logScroll.getChildCount() == 0) {
            return true;
        }
        View child = logScroll.getChildAt(0);
        int distance = child.getBottom() - (logScroll.getScrollY() + logScroll.getHeight());
        return distance <= 64;
    }

    private String currentLogText() {
        synchronized (pendingLog) {
            return logBuffer.toString() + pendingLog;
        }
    }

    private boolean loadInitialLanguage() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (prefs.contains(PREF_CHINESE)) {
            return prefs.getBoolean(PREF_CHINESE, true);
        }
        return Locale.getDefault().getLanguage().toLowerCase(Locale.US).startsWith("zh");
    }

    private void updateTexts() {
        titleView.setText(t("DW Resource Decryptor"));
        hintView.setText(t("Select a folder that contains your purchased Duowan resources. Output is written into a new _dw_decrypted_* folder."));
        languageSwitch.setText(chinese ? "中文" : "EN");
        pickButton.setText(t("Select Folder"));
        saveLogButton.setText(t("Save Log"));
        repairMapDbBox.setText(t("Repair Map DB"));
        keepRepairArtifactsBox.setText(t("Keep Repair Artifacts"));
        analyzeMapWalBox.setText(t("Analyze Map Log Decryption"));
        analyzeUserKeyBox.setText(t("Analyze User Key"));
        int selected = Math.max(0, repairModeSpinner.getSelectedItemPosition());
        repairModeAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, repairModeItems());
        repairModeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        repairModeSpinner.setAdapter(repairModeAdapter);
        repairModeSpinner.setSelection(selected);
    }

    private String[] repairModeItems() {
        return new String[]{
                t("Repair Mode: Delete encrypted mVillages"),
                t("Repair Mode: Decrypt mVillages with .level.key")
        };
    }

    private String t(String english) {
        if (!chinese) {
            return english;
        }
        switch (english) {
            case "DW Resource Decryptor":
                return "多玩资源解密器";
            case "Select a folder that contains your purchased Duowan resources. Output is written into a new _dw_decrypted_* folder.":
                return "选择包含你已购买多玩资源的文件夹。输出会写入新的 _dw_decrypted_* 文件夹。";
            case "Select Folder":
                return "选择文件夹";
            case "Save Log":
                return "保存日志";
            case "Repair Map DB":
                return "修复地图数据库";
            case "Repair Mode: Delete encrypted mVillages":
                return "修复模式：删除加密的 mVillages";
            case "Repair Mode: Decrypt mVillages with .level.key":
                return "修复模式：用 .level.key 解密 mVillages";
            case "Keep Repair Artifacts":
                return "保留修复调试文件";
            case "Analyze Map Log Decryption":
                return "分析地图 Log 解密";
            case "Analyze User Key":
                return "分析 User Key";
            case "Ready":
                return "准备就绪";
            case "Selected":
                return "已选择";
            case "Log saved":
                return "日志已保存";
            case "Failed to save log":
                return "保存日志失败";
            case "cannot open output":
                return "无法打开输出文件";
            case "ERROR":
                return "错误";
            default:
                return english;
        }
    }
}
