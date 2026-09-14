package com.example.dwpreviewer;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.database.Cursor;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.TextUtils;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.animation.AnimationUtils;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;

public final class FourDSkinPreviewActivity extends Activity {
    public static final String EXTRA_FOLDER_SKIN_URIS = "com.example.dwpreviewer.FOLDER_SKIN_URIS";
    public static final String EXTRA_FOLDER_SKIN_NAMES = "com.example.dwpreviewer.FOLDER_SKIN_NAMES";
    public static final String EXTRA_FOLDER_SKIN_INDEX = "com.example.dwpreviewer.FOLDER_SKIN_INDEX";
    public static final String EXTRA_FILE_NAME = "com.example.dwpreviewer.FILE_NAME";

    private PreviewSurface surface;
    private FourDSkinRenderer renderer;
    private ArrayList<String> folderSkinUris;
    private ArrayList<String> folderSkinNames;
    private int folderSkinIndex = -1;
    private int loadGeneration;
    private String currentFileName;
    private float lastPinchDistance;
    private float lastPinchCenterX;
    private float lastPinchCenterY;

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleUtil.attach(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        folderSkinUris = getIntent().getStringArrayListExtra(EXTRA_FOLDER_SKIN_URIS);
        folderSkinNames = getIntent().getStringArrayListExtra(EXTRA_FOLDER_SKIN_NAMES);
        folderSkinIndex = getIntent().getIntExtra(EXTRA_FOLDER_SKIN_INDEX, -1);
        Uri uri = getIntent().getData();
        if (uri == null) {
            showError(getString(R.string.error_no_skin_file));
            return;
        }
        currentFileName = initialFileName(uri);
        loadAsync(uri);
    }

    private void loadAsync(Uri uri) {
        int generation = ++loadGeneration;
        showLoading();
        new Thread(() -> load(uri, generation)).start();
    }

    private void load(Uri uri, int generation) {
        try {
            SkinPreviewData data = SkinPackageLoader.load(this, uri);
            runOnUiThread(() -> {
                if (generation == loadGeneration) showPreview(data);
            });
        } catch (Throwable t) {
            runOnUiThread(() -> {
                if (generation == loadGeneration) showError(t.getMessage() == null ? t.toString() : t.getMessage());
            });
        }
    }

    private void showLoading() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(PreviewSettings.overlayColor(this));
        root.setClickable(true);
        ProgressBar progress = new ProgressBar(this);
        FrameLayout.LayoutParams progressParams = new FrameLayout.LayoutParams(-2, -2);
        progressParams.gravity = Gravity.CENTER;
        root.addView(progress, progressParams);
        setContentView(root);
    }

    private void showPreview(SkinPreviewData data) {
        renderer = new FourDSkinRenderer(data, PreviewSettings.backgroundAlphaFloat(this));
        surface = new PreviewSurface(this);
        surface.setEGLContextClientVersion(1);
        surface.setEGLConfigChooser(8, 8, 8, 8, 16, 0);
        surface.getHolder().setFormat(PixelFormat.TRANSLUCENT);
        surface.setRenderer(renderer);
        surface.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0x00000000);
        root.setClickable(true);
        root.startAnimation(AnimationUtils.loadAnimation(this, R.anim.scale_in_activity));
        root.addView(surface, new FrameLayout.LayoutParams(-1, -1));

        TextView status = new TextView(this);
        status.setText(statusTextRes(data));
        status.setTextColor(0xffffffff);
        status.setTextSize(13);
        status.setGravity(Gravity.CENTER);
        status.setSingleLine(true);
        status.setEllipsize(TextUtils.TruncateAt.END);
        status.setMaxWidth(dp(260));
        status.setBackground(roundedLabel(statusColor(data)));
        status.setPadding(dp(14), dp(7), dp(14), dp(7));
        FrameLayout.LayoutParams statusParams = new FrameLayout.LayoutParams(-2, dp(34));
        statusParams.gravity = Gravity.TOP | Gravity.LEFT;
        statusParams.leftMargin = dp(12);
        statusParams.topMargin = dp(12);
        root.addView(status, statusParams);

        if (data.standardSkin) addModelSwitch(root);
        if (canSwitchFolderSkins()) addFolderSwitchButtons(root);
        if (PreviewSettings.showFileNameInPreview(this) && !TextUtils.isEmpty(currentFileName)) {
            TextView fileName = createFileNameLabel(currentFileName);
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(-2, dp(34));
            params.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            params.topMargin = dp(54);
            params.leftMargin = dp(18);
            params.rightMargin = dp(18);
            root.addView(fileName, params);
        }

        LinearLayout language = createLanguageControl();
        language.setBackground(roundedControl(0x8a111827, dp(18)));
        FrameLayout.LayoutParams languageParams = new FrameLayout.LayoutParams(dp(84), dp(38));
        languageParams.gravity = Gravity.TOP | Gravity.RIGHT;
        languageParams.topMargin = dp(12);
        languageParams.rightMargin = dp(54);
        root.addView(language, languageParams);

        TextView close = new TextView(this);
        close.setText(R.string.close);
        close.setTextColor(0xffffffff);
        close.setTextSize(24);
        close.setGravity(Gravity.CENTER);
        close.setBackground(roundedControl(0xcc0f766e, dp(22)));
        close.setOnClickListener(v -> finish());
        FrameLayout.LayoutParams closeParams = new FrameLayout.LayoutParams(dp(44), dp(44));
        closeParams.gravity = Gravity.TOP | Gravity.RIGHT;
        closeParams.topMargin = dp(9);
        closeParams.rightMargin = dp(8);
        root.addView(close, closeParams);
        setContentView(root);
    }

    private void addModelSwitch(FrameLayout root) {
        LinearLayout modelSwitch = new LinearLayout(this);
        modelSwitch.setOrientation(LinearLayout.HORIZONTAL);
        modelSwitch.setGravity(Gravity.CENTER);
        modelSwitch.setBackground(roundedControl(0x8a111827, dp(6)));
        modelSwitch.setPadding(dp(6), dp(6), dp(6), dp(6));
        TextView steve = createModelButton(R.string.model_steve);
        TextView alex = createModelButton(R.string.model_alex);
        modelSwitch.addView(steve, new LinearLayout.LayoutParams(dp(104), dp(40)));
        LinearLayout.LayoutParams alexParams = new LinearLayout.LayoutParams(dp(104), dp(40));
        alexParams.leftMargin = dp(4);
        modelSwitch.addView(alex, alexParams);
        updateModelButtons(steve, alex, false);
        steve.setOnClickListener(v -> {
            updateModelButtons(steve, alex, false);
            surface.queueEvent(() -> renderer.useStandardModel(false));
        });
        alex.setOnClickListener(v -> {
            updateModelButtons(steve, alex, true);
            surface.queueEvent(() -> renderer.useStandardModel(true));
        });
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(-2, -2);
        params.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        params.bottomMargin = dp(18);
        root.addView(modelSwitch, params);
    }

    private void addFolderSwitchButtons(FrameLayout root) {
        TextView previous = createSkinSwitchButton(R.string.previous_skin);
        previous.setOnClickListener(v -> switchFolderSkin(-1));
        FrameLayout.LayoutParams previousParams = new FrameLayout.LayoutParams(dp(56), dp(56));
        previousParams.gravity = Gravity.LEFT | Gravity.CENTER_VERTICAL;
        previousParams.leftMargin = dp(10);
        root.addView(previous, previousParams);
        TextView next = createSkinSwitchButton(R.string.next_skin);
        next.setOnClickListener(v -> switchFolderSkin(1));
        FrameLayout.LayoutParams nextParams = new FrameLayout.LayoutParams(dp(56), dp(56));
        nextParams.gravity = Gravity.RIGHT | Gravity.CENTER_VERTICAL;
        nextParams.rightMargin = dp(10);
        root.addView(next, nextParams);
    }

    private void showError(String message) {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(PreviewSettings.overlayColor(this));
        root.setClickable(true);
        TextView error = new TextView(this);
        error.setText(getString(R.string.preview_failed) + "\n" + message);
        error.setTextColor(0xffffffff);
        error.setTextSize(16);
        error.setGravity(Gravity.CENTER);
        error.setPadding(dp(24), dp(24), dp(24), dp(24));
        error.setOnClickListener(v -> finish());
        root.addView(error, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout language = createLanguageControl();
        language.setBackground(roundedControl(0x8a111827, dp(18)));
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(dp(84), dp(38));
        params.gravity = Gravity.TOP | Gravity.RIGHT;
        params.topMargin = dp(12);
        params.rightMargin = dp(12);
        root.addView(language, params);
        setContentView(root);
    }

    private int statusTextRes(SkinPreviewData data) {
        if (data.sourceType == SkinPreviewData.SOURCE_INTERNATIONAL_DUOWAN) return R.string.status_international_4d;
        if (data.standardSkin) return data.encrypted ? R.string.status_encrypted_standard : R.string.status_plain_standard;
        return data.encrypted ? R.string.status_encrypted_4d : R.string.status_plain_4d;
    }

    private int statusColor(SkinPreviewData data) {
        if (data.sourceType == SkinPreviewData.SOURCE_INTERNATIONAL_DUOWAN) return 0xdd0891b2;
        if (data.standardSkin && data.encrypted) return 0xdd7c3aed;
        if (data.standardSkin) return 0xdd0f9f8e;
        if (data.encrypted) return 0xdd2563eb;
        return 0xdd16a34a;
    }

    private TextView createModelButton(int textRes) {
        TextView button = new TextView(this);
        button.setText(textRes);
        button.setGravity(Gravity.CENTER);
        button.setTextSize(14);
        button.setSingleLine(true);
        button.setEllipsize(TextUtils.TruncateAt.END);
        button.setClickable(true);
        return button;
    }

    private void updateModelButtons(TextView steve, TextView alex, boolean slimArms) {
        styleModelButton(steve, !slimArms);
        styleModelButton(alex, slimArms);
    }

    private void styleModelButton(TextView button, boolean selected) {
        button.setTextColor(selected ? 0xffffffff : 0xffd1d5db);
        button.setBackground(roundedControl(selected ? 0xcc0f766e : 0x00000000, dp(4)));
    }

    private TextView createSkinSwitchButton(int textRes) {
        TextView button = new TextView(this);
        button.setText(textRes);
        button.setTextColor(0xffffffff);
        button.setTextSize(32);
        button.setGravity(Gravity.CENTER);
        button.setClickable(true);
        button.setBackground(roundedControl(0x99111827, dp(28)));
        return button;
    }

    private TextView createFileNameLabel(String name) {
        TextView label = new TextView(this);
        label.setText(name);
        label.setTextColor(0xffffffff);
        label.setTextSize(13);
        label.setGravity(Gravity.CENTER);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        label.setMaxWidth(dp(280));
        label.setPadding(dp(14), dp(7), dp(14), dp(7));
        label.setBackground(roundedControl(0x99111827, dp(17)));
        label.setClickable(true);
        label.setOnClickListener(v -> copyFileName(name));
        return label;
    }

    private void copyFileName(String name) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("skin file", name));
            Toast.makeText(this, R.string.file_name_copied, Toast.LENGTH_SHORT).show();
        }
    }

    private boolean canSwitchFolderSkins() {
        return folderSkinUris != null && folderSkinUris.size() > 1
                && folderSkinIndex >= 0 && folderSkinIndex < folderSkinUris.size();
    }

    private void switchFolderSkin(int direction) {
        if (!canSwitchFolderSkins()) return;
        folderSkinIndex = (folderSkinIndex + direction + folderSkinUris.size()) % folderSkinUris.size();
        Uri uri = Uri.parse(folderSkinUris.get(folderSkinIndex));
        currentFileName = folderSkinName(folderSkinIndex, uri);
        loadAsync(uri);
    }

    private String initialFileName(Uri uri) {
        String extra = getIntent().getStringExtra(EXTRA_FILE_NAME);
        return TextUtils.isEmpty(extra) ? displayNameForUri(uri) : extra;
    }

    private String folderSkinName(int index, Uri uri) {
        if (folderSkinNames != null && index >= 0 && index < folderSkinNames.size()
                && !TextUtils.isEmpty(folderSkinNames.get(index))) {
            return folderSkinNames.get(index);
        }
        return displayNameForUri(uri);
    }

    private String displayNameForUri(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                String name = cursor.getString(0);
                if (!TextUtils.isEmpty(name)) return name;
            }
        } catch (Throwable ignored) {
        }
        String path = uri.getLastPathSegment();
        if (path == null) return "";
        int index = Math.max(path.lastIndexOf('/'), path.lastIndexOf(':'));
        return index >= 0 && index + 1 < path.length() ? path.substring(index + 1) : path;
    }

    private GradientDrawable roundedLabel(int color) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(18));
        drawable.setStroke(dp(1), 0x33ffffff);
        return drawable;
    }

    private GradientDrawable roundedControl(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        drawable.setStroke(dp(1), 0x44ffffff);
        return drawable;
    }

    private LinearLayout createLanguageControl() {
        LinearLayout control = new LinearLayout(this);
        control.setOrientation(LinearLayout.HORIZONTAL);
        control.setGravity(Gravity.CENTER_VERTICAL);
        control.setPadding(dp(4), 0, dp(4), 0);
        TextView label = new TextView(this);
        boolean chinese = LocaleUtil.isChinese(this);
        label.setText(chinese ? R.string.language_zh_short : R.string.language_en_short);
        label.setTextColor(0xffffffff);
        label.setTextSize(14);
        label.setGravity(Gravity.CENTER);
        control.addView(label, new LinearLayout.LayoutParams(dp(26), -1));
        Switch language = new Switch(this);
        language.setShowText(false);
        language.setText("");
        language.setChecked(chinese);
        language.setOnCheckedChangeListener((buttonView, isChecked) -> {
            LocaleUtil.setChinese(this, isChecked);
            recreate();
        });
        control.addView(language, new LinearLayout.LayoutParams(dp(50), -1));
        return control;
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (surface != null) surface.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (surface != null) surface.onResume();
    }

    @Override
    public void finish() {
        super.finish();
        overridePendingTransition(0, R.anim.scale_out_activity);
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private final class PreviewSurface extends GLSurfaceView implements GestureDetector.OnDoubleTapListener, GestureDetector.OnGestureListener {
        private final GestureDetector gestures;
        private float lastX;
        private float lastY;

        PreviewSurface(Activity activity) {
            super(activity);
            gestures = new GestureDetector(activity, this);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            gestures.onTouchEvent(event);
            if (renderer == null) return false;
            int action = event.getActionMasked();
            int pointers = event.getPointerCount();
            if (action == MotionEvent.ACTION_DOWN) {
                lastX = event.getX();
                lastY = event.getY();
            } else if (action == MotionEvent.ACTION_POINTER_DOWN && pointers == 2) {
                lastPinchDistance = distance(event);
                lastPinchCenterX = centerX(event);
                lastPinchCenterY = centerY(event);
            } else if (action == MotionEvent.ACTION_MOVE) {
                if (pointers == 1) {
                    float x = event.getX();
                    float y = event.getY();
                    float dx = Math.abs(x - lastX) > 1.0f ? (x > lastX ? 4.0f : -4.0f) : 0.0f;
                    float dy = Math.abs(y - lastY) > 1.0f ? (y > lastY ? 4.0f : -4.0f) : 0.0f;
                    lastX = x;
                    lastY = y;
                    queueEvent(() -> renderer.rotateBy(dx, dy));
                } else if (pointers == 2) {
                    float now = distance(event);
                    float cx = centerX(event);
                    float cy = centerY(event);
                    if (lastPinchDistance > 0.0f) {
                        float factor = Math.max(0.5f, Math.min(1.5f, now / lastPinchDistance));
                        float panDx = cx - lastPinchCenterX;
                        float panDy = cy - lastPinchCenterY;
                        queueEvent(() -> {
                            renderer.zoomBy(factor);
                            renderer.panBy(panDx, panDy);
                        });
                    }
                    lastPinchDistance = now;
                    lastPinchCenterX = cx;
                    lastPinchCenterY = cy;
                }
            }
            return true;
        }

        private float distance(MotionEvent event) {
            float x = event.getX(0) - event.getX(1);
            float y = event.getY(0) - event.getY(1);
            return (float) Math.sqrt((x * x) + (y * y));
        }

        private float centerX(MotionEvent event) {
            return (event.getX(0) + event.getX(1)) * 0.5f;
        }

        private float centerY(MotionEvent event) {
            return (event.getY(0) + event.getY(1)) * 0.5f;
        }

        @Override public boolean onDoubleTap(MotionEvent e) {
            queueEvent(() -> renderer.resetView());
            return true;
        }
        @Override public boolean onDoubleTapEvent(MotionEvent e) { return false; }
        @Override public boolean onSingleTapConfirmed(MotionEvent e) { return false; }
        @Override public boolean onDown(MotionEvent e) { return true; }
        @Override public void onShowPress(MotionEvent e) {}
        @Override public boolean onSingleTapUp(MotionEvent e) { return false; }
        @Override public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) { return false; }
        @Override public void onLongPress(MotionEvent e) {}
        @Override public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) { return false; }
    }
}
