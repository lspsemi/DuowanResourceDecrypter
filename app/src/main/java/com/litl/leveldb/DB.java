package com.litl.leveldb;

import java.io.File;
import java.nio.ByteBuffer;

public class DB extends NativeObject {
    private final File path;
    private boolean destroyOnClose;

    public abstract class Snapshot extends NativeObject {
        Snapshot(long ptr) {
            super(ptr);
        }
    }

    public DB(File file) {
        System.loadLibrary("leveldbjni");
        if (file == null) {
            throw new NullPointerException();
        }
        path = file;
    }

    public static void destroy(File file) {
        nativeDestroy(file.getAbsolutePath());
    }

    private static native void nativeClose(long ptr);

    private static native void nativeDelete(long ptr, byte[] key);

    private static native void nativeDestroy(String path);

    private static native byte[] nativeGet(long ptr, long snapshot, ByteBuffer key);

    private static native byte[] nativeGet(long ptr, long snapshot, byte[] key);

    private static native long nativeGetSnapshot(long ptr);

    private static native long nativeIterator(long ptr, long snapshot);

    private static native long nativeOpen(String path);

    private static native void nativePut(long ptr, byte[] key, byte[] value);

    private static native void nativeReleaseSnapshot(long ptr, long snapshot);

    private static native void nativeWrite(long ptr, long batch);

    public static native String stringFromJNI();

    @Override
    protected void closeNativeObject(long ptr) {
        nativeClose(ptr);
        if (destroyOnClose) {
            destroy(path);
        }
    }

    public void delete(byte[] key) {
        assertOpen("Database is closed");
        if (key == null) {
            throw new NullPointerException();
        }
        nativeDelete(mPtr, key);
    }

    public void destroy() {
        destroyOnClose = true;
        if (getPtr() == 0) {
            destroy(path);
        }
    }

    public byte[] get(Snapshot snapshot, ByteBuffer key) {
        assertOpen("Database is closed");
        if (key == null) {
            throw new NullPointerException();
        }
        return nativeGet(mPtr, snapshot != null ? snapshot.getPtr() : 0, key);
    }

    public byte[] get(Snapshot snapshot, byte[] key) {
        assertOpen("Database is closed");
        if (key == null) {
            throw new NullPointerException();
        }
        return nativeGet(mPtr, snapshot != null ? snapshot.getPtr() : 0, key);
    }

    public byte[] get(byte[] key) {
        return get((Snapshot) null, key);
    }

    public Snapshot getSnapshot() {
        assertOpen("Database is closed");
        ref();
        return new Snapshot(nativeGetSnapshot(mPtr)) {
            @Override
            protected void closeNativeObject(long ptr) {
                DB.nativeReleaseSnapshot(DB.this.getPtr(), getPtr());
                DB.this.unref();
            }
        };
    }

    public Iterator iterator() {
        return iterator(null);
    }

    public Iterator iterator(final Snapshot snapshot) {
        assertOpen("Database is closed");
        ref();
        if (snapshot != null) {
            snapshot.ref();
        }
        return new Iterator(nativeIterator(mPtr, snapshot != null ? snapshot.getPtr() : 0)) {
            @Override
            protected void closeNativeObject(long ptr) {
                super.closeNativeObject(ptr);
                if (snapshot != null) {
                    snapshot.unref();
                }
                DB.this.unref();
            }
        };
    }

    public void open() {
        mPtr = nativeOpen(path.getAbsolutePath());
    }

    public void put(byte[] key, byte[] value) {
        assertOpen("Database is closed");
        if (key == null) {
            throw new NullPointerException("key");
        }
        if (value == null) {
            throw new NullPointerException("value");
        }
        nativePut(mPtr, key, value);
    }
}
