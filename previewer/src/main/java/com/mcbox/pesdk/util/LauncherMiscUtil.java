package com.mcbox.pesdk.util;

/* JADX INFO: loaded from: classes2.dex */
public final class LauncherMiscUtil {
    public static native byte[] decryptFileData(byte[] bArr);

    public static native byte[] decryptFileDataUsekey(byte[] bArr, byte[] bArr2);

    public static native byte[] decryptModule(byte[] bArr, int i);

    public static native byte[] encryptFileData(byte[] bArr);

    public static native byte[] encryptMapData(byte[] bArr, byte[] bArr2);

    public static native byte[] generateAesKey();

    static {
        System.loadLibrary("crypto");
        System.loadLibrary("ssl");
        System.loadLibrary("launcherutil");
    }

    private LauncherMiscUtil() {
    }
}
