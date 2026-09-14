package com.example.dwpreviewer;

import android.graphics.Bitmap;
import java.util.ArrayList;
import java.util.List;

/* JADX INFO: loaded from: classes3.dex */
final class SkinPreviewData {
    static final int SOURCE_DUOWAN = 0;
    static final int SOURCE_INTERNATIONAL_DUOWAN = 1;
    final boolean encrypted;
    final List<Part> parts;
    final boolean slimArms;
    final String sourceName;
    final int sourceType;
    final boolean standardSkin;
    final Bitmap texture;
    final int textureHeight;
    final int textureWidth;

    SkinPreviewData(Bitmap texture, List<Part> parts, String sourceName, boolean encrypted) {
        this(texture, parts, sourceName, encrypted, false, false, SOURCE_DUOWAN);
    }

    SkinPreviewData(Bitmap texture, List<Part> parts, String sourceName, boolean encrypted, boolean standardSkin, boolean slimArms) {
        this(texture, parts, sourceName, encrypted, standardSkin, slimArms, SOURCE_DUOWAN);
    }

    SkinPreviewData(Bitmap texture, List<Part> parts, String sourceName, boolean encrypted, boolean standardSkin, boolean slimArms, int sourceType) {
        this.texture = texture;
        this.textureWidth = texture.getWidth();
        this.textureHeight = texture.getHeight();
        this.parts = parts;
        this.sourceName = sourceName;
        this.encrypted = encrypted;
        this.standardSkin = standardSkin;
        this.slimArms = slimArms;
        this.sourceType = sourceType;
    }

    static final class Part {
        final List<Cube> cubes = new ArrayList();
        final String name;
        final String parent;
        final float[] pivot;

        Part(String name, String parent, float[] pivot) {
            this.name = name == null ? "" : name;
            this.parent = parent != null ? parent : "";
            this.pivot = pivot == null ? new float[]{SkinPreviewData.SOURCE_DUOWAN, SkinPreviewData.SOURCE_DUOWAN, SkinPreviewData.SOURCE_DUOWAN} : pivot;
        }
    }

    static final class Cube {
        final float inflate;
        final boolean mirror;
        final float[] origin;
        final float[] size;
        final int[] uv;

        Cube(float[] origin, float[] size, int[] uv, float inflate, boolean mirror) {
            this.origin = origin;
            this.size = size;
            this.uv = uv;
            this.inflate = inflate;
            this.mirror = mirror;
        }
    }
}
