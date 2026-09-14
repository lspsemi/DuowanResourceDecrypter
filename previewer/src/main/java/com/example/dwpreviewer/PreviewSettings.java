package com.example.dwpreviewer;

import android.content.Context;
import android.content.SharedPreferences;

/* JADX INFO: loaded from: classes3.dex */
final class PreviewSettings {
    private static final int DEFAULT_BACKGROUND_TRANSPARENCY = 60;
    private static final String KEY_BACKGROUND_TRANSPARENCY = "background_transparency";
    private static final String KEY_FOLDER_PREVIEW_NAVIGATION = "folder_preview_navigation";
    private static final String KEY_HIDE_MAIN_BEHIND_PREVIEW = "hide_main_behind_preview";
    private static final String KEY_LAST_DOCUMENT_ID = "last_document_id";
    private static final String KEY_LAST_TREE_URI = "last_tree_uri";
    private static final String KEY_MAIN_SETTINGS_EXPANDED = "main_settings_expanded";
    private static final String KEY_SHOW_FILE_NAME_IN_PREVIEW = "show_file_name_in_preview";
    private static final String PREFS = "preview_settings";

    private PreviewSettings() {
    }

    static int backgroundTransparency(Context context) {
        return clamp(context.getSharedPreferences(PREFS, 0).getInt(KEY_BACKGROUND_TRANSPARENCY, DEFAULT_BACKGROUND_TRANSPARENCY));
    }

    static void setBackgroundTransparency(Context context, int transparency) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, 0);
        prefs.edit().putInt(KEY_BACKGROUND_TRANSPARENCY, clamp(transparency)).apply();
    }

    static boolean hideMainBehindPreview(Context context) {
        return context.getSharedPreferences(PREFS, 0).getBoolean(KEY_HIDE_MAIN_BEHIND_PREVIEW, true);
    }

    static void setHideMainBehindPreview(Context context, boolean hide) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, 0);
        prefs.edit().putBoolean(KEY_HIDE_MAIN_BEHIND_PREVIEW, hide).apply();
    }

    static boolean folderPreviewNavigation(Context context) {
        return context.getSharedPreferences(PREFS, 0).getBoolean(KEY_FOLDER_PREVIEW_NAVIGATION, true);
    }

    static void setFolderPreviewNavigation(Context context, boolean enabled) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, 0);
        prefs.edit().putBoolean(KEY_FOLDER_PREVIEW_NAVIGATION, enabled).apply();
    }

    static boolean showFileNameInPreview(Context context) {
        return context.getSharedPreferences(PREFS, 0).getBoolean(KEY_SHOW_FILE_NAME_IN_PREVIEW, true);
    }

    static void setShowFileNameInPreview(Context context, boolean enabled) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, 0);
        prefs.edit().putBoolean(KEY_SHOW_FILE_NAME_IN_PREVIEW, enabled).apply();
    }

    static boolean mainSettingsExpanded(Context context) {
        return context.getSharedPreferences(PREFS, 0).getBoolean(KEY_MAIN_SETTINGS_EXPANDED, false);
    }

    static void setMainSettingsExpanded(Context context, boolean expanded) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, 0);
        prefs.edit().putBoolean(KEY_MAIN_SETTINGS_EXPANDED, expanded).apply();
    }

    static String lastTreeUri(Context context) {
        return context.getSharedPreferences(PREFS, 0).getString(KEY_LAST_TREE_URI, null);
    }

    static String lastDocumentId(Context context) {
        return context.getSharedPreferences(PREFS, 0).getString(KEY_LAST_DOCUMENT_ID, null);
    }

    static void setLastFolder(Context context, String treeUri, String documentId) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, 0);
        prefs.edit().putString(KEY_LAST_TREE_URI, treeUri).putString(KEY_LAST_DOCUMENT_ID, documentId).apply();
    }

    static int overlayColor(Context context) {
        return backgroundAlpha(context) << 24;
    }

    static int backgroundAlpha(Context context) {
        int transparency = backgroundTransparency(context);
        return Math.round(((100 - transparency) * 255.0f) / 100.0f);
    }

    static float backgroundAlphaFloat(Context context) {
        return backgroundAlpha(context) / 255.0f;
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(100, value));
    }
}
