package com.mcbox.pesdk.util;

public final class LauncherMiscUtil {
    static {
        System.loadLibrary("crypto");
        System.loadLibrary("ssl");
        System.loadLibrary("launcherutil");
    }

    private LauncherMiscUtil() {
    }

    public static native byte[] decryptFileData(byte[] data);

    public static native byte[] decryptFileDataUsekey(byte[] data, byte[] key);

    public static native byte[] decryptModule(byte[] data, int type);

    public static native byte[] encryptFileData(byte[] data);

    public static native byte[] encryptMapData(byte[] data, byte[] key);

    public static native byte[] generateAesKey();
}
