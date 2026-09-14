package com.litl.leveldb;

import java.io.Closeable;

abstract class NativeObject implements Closeable {
    protected long mPtr;
    private int refCount;

    protected NativeObject() {
        ref();
    }

    protected NativeObject(long ptr) {
        this();
        if (ptr == 0) {
            throw new OutOfMemoryError("Failed to allocate native object");
        }
        mPtr = ptr;
    }

    protected void assertOpen(String message) {
        if (getPtr() == 0) {
            throw new IllegalStateException(message);
        }
    }

    @Override
    public synchronized void close() {
        if (mPtr != 0) {
            unref();
        }
    }

    protected abstract void closeNativeObject(long ptr);

    protected synchronized long getPtr() {
        return mPtr;
    }

    synchronized void ref() {
        refCount++;
    }

    synchronized void unref() {
        if (refCount <= 0) {
            throw new IllegalStateException("Reference count is already 0");
        }
        refCount--;
        if (refCount == 0) {
            closeNativeObject(mPtr);
            mPtr = 0;
        }
    }
}
