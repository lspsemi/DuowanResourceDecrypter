package com.example.dwpreviewer;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import java.util.Locale;

/* JADX INFO: loaded from: classes3.dex */
final class LocaleUtil {
    private static final String KEY_LANGUAGE = "language";
    static final String LANGUAGE_EN = "en";
    static final String LANGUAGE_ZH = "zh";
    private static final String PREFS = "locale";

    private LocaleUtil() {
    }

    static Context attach(Context context) {
        return apply(context, language(context));
    }

    static String language(Context context) {
        return context.getSharedPreferences(PREFS, 0).getString(KEY_LANGUAGE, LANGUAGE_ZH);
    }

    static boolean isChinese(Context context) {
        return LANGUAGE_ZH.equals(language(context));
    }

    static void toggle(Context context) {
        setLanguage(context, isChinese(context) ? LANGUAGE_EN : LANGUAGE_ZH);
    }

    static void setChinese(Context context, boolean chinese) {
        setLanguage(context, chinese ? LANGUAGE_ZH : LANGUAGE_EN);
    }

    private static void setLanguage(Context context, String language) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, 0);
        prefs.edit().putString(KEY_LANGUAGE, language).apply();
    }

    private static Context apply(Context context, String language) {
        Locale locale = LANGUAGE_EN.equals(language) ? Locale.ENGLISH : Locale.SIMPLIFIED_CHINESE;
        Locale.setDefault(locale);
        Configuration config = new Configuration(context.getResources().getConfiguration());
        config.setLocale(locale);
        config.setLayoutDirection(locale);
        return context.createConfigurationContext(config);
    }
}
