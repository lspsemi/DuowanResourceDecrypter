package com.example.dwpreviewer;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public final class MainActivity extends Activity {
    private static final int REQ_OPEN_SKIN = 2001;
    private static final int REQ_PREVIEW_SKIN = 2002;
    private static final int REQ_OPEN_FOLDER = 2003;
    private static final String MIME_DIR = "vnd.android.document/directory";
    private static final String EXTRA_INITIAL_URI = "android.provider.extra.INITIAL_URI";

    private LinearLayout rootView;
    private LinearLayout settingsPanel;
    private Button settingsToggle;
    private TextView folderPath;
    private Button backButton;
    private Button forwardButton;
    private SkinListAdapter skinListAdapter;
    private Uri treeUri;
    private String currentDocumentId;
    private final ArrayList<String> backStack = new ArrayList<>();
    private final ArrayList<String> forwardStack = new ArrayList<>();
    private boolean previewInFront;

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleUtil.attach(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        rootView = root;
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(32, 32, 32, 32);

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText(R.string.main_title);
        title.setTextSize(22);
        titleRow.addView(title, new LinearLayout.LayoutParams(0, -2, 1.0f));
        titleRow.addView(createLanguageControl(), new LinearLayout.LayoutParams(-2, dp(44)));
        root.addView(titleRow, new LinearLayout.LayoutParams(-1, -2));

        TextView hint = new TextView(this);
        hint.setText(R.string.main_hint);
        hint.setPadding(0, 12, 0, 8);
        root.addView(hint, new LinearLayout.LayoutParams(-1, -2));

        settingsToggle = new Button(this);
        settingsToggle.setOnClickListener(v -> setSettingsExpanded(settingsPanel.getVisibility() != View.VISIBLE));
        root.addView(settingsToggle, new LinearLayout.LayoutParams(-1, dp(42)));

        settingsPanel = new LinearLayout(this);
        settingsPanel.setOrientation(LinearLayout.VERTICAL);
        settingsPanel.addView(createTransparencyControl(), new LinearLayout.LayoutParams(-1, -2));
        settingsPanel.addView(createSwitchControl(R.string.hide_background_controls,
                PreviewSettings.hideMainBehindPreview(this),
                checked -> PreviewSettings.setHideMainBehindPreview(this, checked)), new LinearLayout.LayoutParams(-1, dp(44)));
        settingsPanel.addView(createSwitchControl(R.string.folder_preview_navigation,
                PreviewSettings.folderPreviewNavigation(this),
                checked -> PreviewSettings.setFolderPreviewNavigation(this, checked)), new LinearLayout.LayoutParams(-1, dp(44)));
        settingsPanel.addView(createSwitchControl(R.string.show_file_name,
                PreviewSettings.showFileNameInPreview(this),
                checked -> PreviewSettings.setShowFileNameInPreview(this, checked)), new LinearLayout.LayoutParams(-1, dp(44)));
        root.addView(settingsPanel, new LinearLayout.LayoutParams(-1, -2));
        setSettingsExpanded(PreviewSettings.mainSettingsExpanded(this));

        LinearLayout actionRow = new LinearLayout(this);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        Button pickButton = new Button(this);
        pickButton.setText(R.string.select_skin);
        pickButton.setOnClickListener(v -> openSkin());
        actionRow.addView(pickButton, new LinearLayout.LayoutParams(0, dp(48), 1.0f));
        Button folderButton = new Button(this);
        folderButton.setText(R.string.select_folder);
        folderButton.setOnClickListener(v -> openFolder());
        LinearLayout.LayoutParams folderParams = new LinearLayout.LayoutParams(0, dp(48), 1.0f);
        folderParams.leftMargin = dp(8);
        actionRow.addView(folderButton, folderParams);
        LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(-1, -2);
        actionParams.topMargin = dp(10);
        root.addView(actionRow, actionParams);

        LinearLayout navRow = new LinearLayout(this);
        navRow.setOrientation(LinearLayout.HORIZONTAL);
        navRow.setPadding(0, dp(8), 0, dp(4));
        backButton = new Button(this);
        backButton.setText(R.string.nav_back);
        backButton.setOnClickListener(v -> navigateBack());
        navRow.addView(backButton, new LinearLayout.LayoutParams(0, dp(42), 1.0f));
        forwardButton = new Button(this);
        forwardButton.setText(R.string.nav_forward);
        forwardButton.setOnClickListener(v -> navigateForward());
        LinearLayout.LayoutParams forwardParams = new LinearLayout.LayoutParams(0, dp(42), 1.0f);
        forwardParams.leftMargin = dp(8);
        navRow.addView(forwardButton, forwardParams);
        root.addView(navRow, new LinearLayout.LayoutParams(-1, -2));

        folderPath = new TextView(this);
        folderPath.setTextSize(13);
        folderPath.setSingleLine(true);
        root.addView(folderPath, new LinearLayout.LayoutParams(-1, -2));

        ListView skinList = new ListView(this);
        skinListAdapter = new SkinListAdapter();
        skinList.setAdapter(skinListAdapter);
        skinList.setOnItemClickListener((parent, view, position, id) -> openEntry(skinListAdapter.entry(position)));
        LinearLayout.LayoutParams listParams = new LinearLayout.LayoutParams(-1, 0, 1.0f);
        listParams.topMargin = dp(8);
        root.addView(skinList, listParams);

        setContentView(root);
        restoreFolder();
    }

    private LinearLayout createLanguageControl() {
        LinearLayout control = new LinearLayout(this);
        control.setOrientation(LinearLayout.HORIZONTAL);
        control.setGravity(Gravity.CENTER_VERTICAL);
        control.setPadding(dp(8), 0, 0, 0);
        TextView label = new TextView(this);
        boolean chinese = LocaleUtil.isChinese(this);
        label.setText(chinese ? R.string.language_zh_short : R.string.language_en_short);
        label.setTextSize(14);
        label.setGravity(Gravity.CENTER);
        control.addView(label, new LinearLayout.LayoutParams(dp(32), -1));
        Switch language = new Switch(this);
        language.setShowText(false);
        language.setText("");
        language.setChecked(chinese);
        language.setOnCheckedChangeListener((buttonView, isChecked) -> {
            LocaleUtil.setChinese(this, isChecked);
            recreate();
        });
        control.addView(language, new LinearLayout.LayoutParams(dp(56), -1));
        return control;
    }

    private LinearLayout createTransparencyControl() {
        LinearLayout control = new LinearLayout(this);
        control.setOrientation(LinearLayout.VERTICAL);
        control.setPadding(0, dp(8), 0, dp(2));
        TextView label = new TextView(this);
        label.setTextSize(15);
        int initial = PreviewSettings.backgroundTransparency(this);
        label.setText(getString(R.string.background_transparency, initial));
        control.addView(label, new LinearLayout.LayoutParams(-1, -2));
        SeekBar seekBar = new SeekBar(this);
        seekBar.setMax(100);
        seekBar.setProgress(initial);
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                PreviewSettings.setBackgroundTransparency(MainActivity.this, progress);
                label.setText(getString(R.string.background_transparency, progress));
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        control.addView(seekBar, new LinearLayout.LayoutParams(-1, dp(44)));
        return control;
    }

    private LinearLayout createSwitchControl(int labelRes, boolean checked, BoolConsumer consumer) {
        LinearLayout control = new LinearLayout(this);
        control.setOrientation(LinearLayout.HORIZONTAL);
        control.setGravity(Gravity.CENTER_VERTICAL);
        TextView label = new TextView(this);
        label.setText(labelRes);
        label.setTextSize(15);
        label.setGravity(Gravity.CENTER_VERTICAL);
        control.addView(label, new LinearLayout.LayoutParams(0, -1, 1.0f));
        Switch sw = new Switch(this);
        sw.setShowText(false);
        sw.setText("");
        sw.setChecked(checked);
        sw.setOnCheckedChangeListener((buttonView, isChecked) -> consumer.accept(isChecked));
        control.addView(sw, new LinearLayout.LayoutParams(dp(64), -1));
        return control;
    }

    private void setSettingsExpanded(boolean expanded) {
        if (settingsPanel != null) {
            settingsPanel.setVisibility(expanded ? View.VISIBLE : View.GONE);
        }
        if (settingsToggle != null) {
            settingsToggle.setText(expanded ? R.string.hide_settings : R.string.show_settings);
        }
        PreviewSettings.setMainSettingsExpanded(this, expanded);
    }

    private void openSkin() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        addInitialUri(intent);
        startActivityForResult(intent, REQ_OPEN_SKIN);
    }

    private void openFolder() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        addInitialUri(intent);
        startActivityForResult(intent, REQ_OPEN_FOLDER);
    }

    private void addInitialUri(Intent intent) {
        String saved = PreviewSettings.lastTreeUri(this);
        if (saved != null) {
            intent.putExtra(EXTRA_INITIAL_URI, Uri.parse(saved));
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PREVIEW_SKIN) {
            previewInFront = false;
            setMainContentVisible(true);
            return;
        }
        if (requestCode == REQ_OPEN_FOLDER) {
            handleFolderResult(resultCode, data);
            return;
        }
        if (requestCode != REQ_OPEN_SKIN || resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        Uri uri = data.getData();
        getContentResolver().takePersistableUriPermission(uri, data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION);
        launchPreview(previewIntent(uri));
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!previewInFront) {
            setMainContentVisible(true);
        }
    }

    private void setMainContentVisible(boolean visible) {
        if (rootView != null) {
            rootView.setVisibility(visible ? View.VISIBLE : View.INVISIBLE);
        }
    }

    private void handleFolderResult(int resultCode, Intent data) {
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        Uri uri = data.getData();
        getContentResolver().takePersistableUriPermission(uri,
                data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION));
        treeUri = uri;
        currentDocumentId = DocumentsContract.getTreeDocumentId(uri);
        backStack.clear();
        forwardStack.clear();
        PreviewSettings.setLastFolder(this, treeUri.toString(), currentDocumentId);
        loadCurrentFolder();
    }

    private void restoreFolder() {
        String savedTree = PreviewSettings.lastTreeUri(this);
        String savedDoc = PreviewSettings.lastDocumentId(this);
        if (savedTree == null) {
            updateFolderUi(getString(R.string.no_folder_selected), new ArrayList<>());
            return;
        }
        treeUri = Uri.parse(savedTree);
        currentDocumentId = savedDoc == null ? DocumentsContract.getTreeDocumentId(treeUri) : savedDoc;
        loadCurrentFolder();
    }

    private void openEntry(BrowserEntry entry) {
        if (entry == null) {
            return;
        }
        if (entry.directory) {
            backStack.add(currentDocumentId);
            forwardStack.clear();
            currentDocumentId = entry.documentId;
            PreviewSettings.setLastFolder(this, treeUri.toString(), currentDocumentId);
            loadCurrentFolder();
            return;
        }
        Intent intent = previewIntent(entry.uri);
        intent.putExtra(FourDSkinPreviewActivity.EXTRA_FILE_NAME, entry.name);
        if (PreviewSettings.folderPreviewNavigation(this)) {
            ArrayList<String> skinUris = skinListAdapter.skinUris();
            ArrayList<String> skinNames = skinListAdapter.skinNames();
            if (skinUris.size() > 1) {
                intent.putStringArrayListExtra(FourDSkinPreviewActivity.EXTRA_FOLDER_SKIN_URIS, skinUris);
                intent.putStringArrayListExtra(FourDSkinPreviewActivity.EXTRA_FOLDER_SKIN_NAMES, skinNames);
                intent.putExtra(FourDSkinPreviewActivity.EXTRA_FOLDER_SKIN_INDEX, skinUris.indexOf(entry.uri.toString()));
            }
        }
        launchPreview(intent);
    }

    private void navigateBack() {
        if (backStack.isEmpty()) return;
        forwardStack.add(currentDocumentId);
        currentDocumentId = backStack.remove(backStack.size() - 1);
        PreviewSettings.setLastFolder(this, treeUri.toString(), currentDocumentId);
        loadCurrentFolder();
    }

    private void navigateForward() {
        if (forwardStack.isEmpty()) return;
        backStack.add(currentDocumentId);
        currentDocumentId = forwardStack.remove(forwardStack.size() - 1);
        PreviewSettings.setLastFolder(this, treeUri.toString(), currentDocumentId);
        loadCurrentFolder();
    }

    private void loadCurrentFolder() {
        if (treeUri == null || currentDocumentId == null) {
            updateFolderUi(getString(R.string.no_folder_selected), new ArrayList<>());
            return;
        }
        ArrayList<BrowserEntry> entries = new ArrayList<>();
        Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, currentDocumentId);
        String[] projection = {DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE};
        try (Cursor cursor = getContentResolver().query(childrenUri, projection, null, null, null)) {
            if (cursor != null) {
                int idCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID);
                int nameCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
                int mimeCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE);
                while (cursor.moveToNext()) {
                    String documentId = cursor.getString(idCol);
                    String name = cursor.getString(nameCol);
                    String mime = cursor.getString(mimeCol);
                    boolean directory = MIME_DIR.equals(mime);
                    if (!directory && !isSupportedSkinFile(name)) continue;
                    entries.add(new BrowserEntry(name == null ? "" : name, documentId,
                            DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId), directory));
                }
            }
            Collections.sort(entries, (left, right) -> {
                if (left.directory != right.directory) return left.directory ? -1 : 1;
                return left.name.compareToIgnoreCase(right.name);
            });
            updateFolderUi(displayNameForCurrentFolder(), entries);
        } catch (Throwable t) {
            updateFolderUi(getString(R.string.folder_load_failed, t.getMessage()), new ArrayList<>());
        }
    }

    private boolean isSupportedSkinFile(String name) {
        if (name == null) return false;
        String lower = name.toLowerCase(Locale.US);
        return lower.endsWith(".png") || lower.endsWith(".zip") || lower.endsWith(".jpg") || lower.endsWith(".jpeg");
    }

    private String displayNameForCurrentFolder() {
        String treeDoc = DocumentsContract.getTreeDocumentId(treeUri);
        if (treeDoc.equals(currentDocumentId)) return getString(R.string.current_folder_root);
        int index = Math.max(currentDocumentId.lastIndexOf('/'), currentDocumentId.lastIndexOf(':'));
        return index >= 0 && index + 1 < currentDocumentId.length() ? currentDocumentId.substring(index + 1) : currentDocumentId;
    }

    private void updateFolderUi(String path, List<BrowserEntry> entries) {
        if (folderPath != null) folderPath.setText(getString(R.string.current_folder, path));
        if (backButton != null) backButton.setEnabled(!backStack.isEmpty());
        if (forwardButton != null) forwardButton.setEnabled(!forwardStack.isEmpty());
        if (skinListAdapter != null) skinListAdapter.setEntries(entries);
    }

    private Intent previewIntent(Uri uri) {
        Intent intent = new Intent(this, FourDSkinPreviewActivity.class);
        intent.setData(uri);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        return intent;
    }

    private void launchPreview(Intent intent) {
        previewInFront = true;
        setMainContentVisible(!PreviewSettings.hideMainBehindPreview(this));
        startActivityForResult(intent, REQ_PREVIEW_SKIN);
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private interface BoolConsumer {
        void accept(boolean value);
    }

    private static final class BrowserEntry {
        final String name;
        final String documentId;
        final Uri uri;
        final boolean directory;

        BrowserEntry(String name, String documentId, Uri uri, boolean directory) {
            this.name = name;
            this.documentId = documentId;
            this.uri = uri;
            this.directory = directory;
        }
    }

    private final class SkinListAdapter extends BaseAdapter {
        private final ArrayList<BrowserEntry> entries = new ArrayList<>();

        void setEntries(List<BrowserEntry> newEntries) {
            entries.clear();
            entries.addAll(newEntries);
            notifyDataSetChanged();
        }

        BrowserEntry entry(int position) {
            return position >= 0 && position < entries.size() ? entries.get(position) : null;
        }

        ArrayList<String> skinUris() {
            ArrayList<String> uris = new ArrayList<>();
            for (BrowserEntry entry : entries) if (!entry.directory) uris.add(entry.uri.toString());
            return uris;
        }

        ArrayList<String> skinNames() {
            ArrayList<String> names = new ArrayList<>();
            for (BrowserEntry entry : entries) if (!entry.directory) names.add(entry.name);
            return names;
        }

        @Override public int getCount() { return entries.size(); }
        @Override public Object getItem(int position) { return entries.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            TextView row = convertView instanceof TextView ? (TextView) convertView : new TextView(MainActivity.this);
            BrowserEntry entry = entries.get(position);
            row.setText(getString(entry.directory ? R.string.folder_entry : R.string.skin_entry, entry.name));
            row.setTextSize(15);
            row.setSingleLine(true);
            row.setPadding(dp(8), 0, dp(8), 0);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setTextColor(entry.directory ? 0xff0f766e : 0xff374151);
            row.setBackgroundColor(0x00000000);
            row.setLayoutParams(new ListView.LayoutParams(-1, dp(44)));
            return row;
        }
    }
}
