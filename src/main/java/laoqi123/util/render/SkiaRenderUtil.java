package laoqi123.util.render;

import laoqi123.ui.skia.Skia;
import laoqi123.ui.skia.SkiaManager;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.texture.AbstractTexture;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;
import org.jetbrains.skija.*;

import java.util.HashMap;
import java.util.Map;

/**
 * Skia 渲染工具（从 Lyasim RenderUtil 移植：玩家头像 + 背景模糊）。
 */
public class SkiaRenderUtil {

    public static void drawBlur(Canvas canvas, int bx, int by, int bw, int bh, Float blurR, int cornerRadius) {
        Skia skia = SkiaManager.getInstance().skia;
        Image full = skia.captureFullScreen();
        // backdrop 是按需抓的：这次调用会登记需求，下一帧才拿得到图。
        if (full == null) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        float s = (float) mc.getWindow().getScaleFactor();
        try (Paint blurPaint = new Paint().setAntiAlias(false)) {
            blurPaint.setImageFilter(ImageFilter.makeBlur(blurR, blurR, FilterTileMode.CLAMP));
            blurPaint.setBlendMode(BlendMode.SRC);
            float pad = blurR * s;
            // Image 来自 BOTTOM_LEFT Surface，Y 轴需翻转：screen top by*s → image bottom = imgH - by*s
            float imgH = full.getHeight();
            canvas.save();
            canvas.clipRRect(RRect.makeXYWH(bx, by, bw, bh, cornerRadius), ClipMode.INTERSECT, true);
            canvas.drawImageRect(full,
                    Rect.makeXYWH(bx * s - pad, by * s - pad,
                            bw * s + pad * 2, bh * s + pad * 2),
                    Rect.makeXYWH(bx - blurR, by - blurR,
                            bw + blurR * 2, bh + blurR * 2),
                    blurPaint);
            canvas.restore();
        }
        // 注意：Image 生命周期由 Skia.captureBackground()/releaseBackground() 管理，此处不要 close
    }

    public static int argb(int a, int r, int g, int b) {
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    // ---- 玩家头像 ----

    public static void drawPlayerHeadSkia(Canvas canvas, AbstractClientPlayerEntity player, float x, float y, float width, float height, float alpha) {
        HeadImages heads = getOrCreateHeadImages(player);
        if (heads == null) return;

        try (Paint paint = new Paint().setAntiAlias(false)) {
            paint.setAlpha((int) (Math.max(0, Math.min(1, alpha)) * 255));
            canvas.drawImageRect(heads.base, Rect.makeXYWH(x, y, width, height), paint);
            canvas.drawImageRect(heads.hat,  Rect.makeXYWH(x, y, width, height), paint);
        }
    }

    public static void drawPlayerHeadRoundedSkia(Canvas canvas, AbstractClientPlayerEntity player, float x, float y, float width, float height, float alpha, float radius) {
        HeadImages heads = getOrCreateHeadImages(player);
        if (heads == null) return;

        try (Paint paint = new Paint().setAntiAlias(false)) {
            paint.setAlpha((int) (Math.max(0, Math.min(1, alpha)) * 255));
            canvas.save();
            if (radius > 0) {
                try (Path p = new Path()) {
                    p.addRRect(RRect.makeXYWH(x, y, width, height, radius), PathDirection.CLOCKWISE);
                    canvas.clipPath(p, ClipMode.INTERSECT, true);
                }
            }
            canvas.drawImageRect(heads.base, Rect.makeXYWH(x, y, width, height), paint);
            canvas.drawImageRect(heads.hat,  Rect.makeXYWH(x, y, width, height), paint);
            canvas.restore();
        }
    }

    // ---- 头部子图缓存 ----

    private static class HeadImages {
        final Image base, hat;
        HeadImages(Image base, Image hat) { this.base = base; this.hat = hat; }
    }

    private static final Map<Identifier, HeadImages> headCache = new HashMap<>(8);

    private static HeadImages getOrCreateHeadImages(AbstractClientPlayerEntity player) {
        Identifier skinId = player.getSkinTextures().texture();
        if (skinId == null) return null;
        HeadImages cached = headCache.get(skinId);
        if (cached != null) return cached;
        Image skin = getOrCreateSkinImage(skinId);
        if (skin == null) return null;
        float hs = 8f * skin.getWidth() / 64f;
        int h = (int) hs;
        Image base = makeSubImage(skin, h, h, h, h);
        Image hat  = makeSubImage(skin, h * 5, h, h, h);
        if (base == null || hat == null) return null;
        HeadImages hi = new HeadImages(base, hat);
        headCache.put(skinId, hi);
        return hi;
    }

    private static Image makeSubImage(Image src, int x, int y, int w, int h) {
        Bitmap bmp = new Bitmap();
        bmp.allocN32Pixels(w, h);
        if (!src.readPixels(bmp, x, y)) { bmp.close(); return null; }
        Image sub = Image.makeFromBitmap(bmp);
        bmp.close();
        return sub;
    }

    // ---- 缓存 ----

    private static final Map<Identifier, Image> skinImageCache = new HashMap<>(8);

    private static Image getOrCreateSkinImage(Identifier skinId) {
        Image cached = skinImageCache.get(skinId);
        if (cached != null) return cached;

        // 1) 从资源系统直接读 PNG 字节（零 GL）
        Image img = loadSkinFromResource(skinId);
        if (img != null) { skinImageCache.put(skinId, img); return img; }

        // 2) 反射提取 NativeImage 兜底
        img = loadSkinFromTexture(skinId);
        if (img != null) { skinImageCache.put(skinId, img); return img; }

        return null;
    }

    // ---- 路径 1：从 MC 资源系统读 PNG → Image.makeFromEncoded ----

    private static Image loadSkinFromResource(Identifier skinId) {
        try {
            var opt = MinecraftClient.getInstance().getResourceManager().getResource(skinId);
            if (opt.isPresent()) {
                byte[] png = opt.get().getInputStream().readAllBytes();
                Image img = Image.makeFromEncoded(png);
                if (img != null) return img;
            }
        } catch (Exception ignored) {}
        return null;
    }

    // ---- 路径 2：从 TextureManager 取 NativeImage 反射 ----

    private static Image loadSkinFromTexture(Identifier skinId) {
        AbstractTexture tex = MinecraftClient.getInstance().getTextureManager().getTexture(skinId);
        if (tex == null) return null;

        NativeImage ni = null;
        // a) instanceof
        try {
            if (tex instanceof NativeImageBackedTexture nib) {
                ni = nib.getImage();
            }
        } catch (Exception ignored) {}
        // b) 扫描方法
        if (ni == null) {
            try {
                for (java.lang.reflect.Method m : tex.getClass().getMethods()) {
                    if (m.getReturnType() == NativeImage.class
                            && m.getParameterCount() == 0) {
                        Object r = m.invoke(tex);
                        if (r instanceof NativeImage n) { ni = n; break; }
                    }
                }
            } catch (Exception ignored) {}
        }
        // c) 扫描字段
        if (ni == null) {
            try {
                for (java.lang.reflect.Field f : tex.getClass().getDeclaredFields()) {
                    if (f.getType() == NativeImage.class) {
                        f.setAccessible(true);
                        Object v = f.get(tex);
                        if (v instanceof NativeImage n) { ni = n; break; }
                    }
                }
            } catch (Exception ignored) {}
        }

        if (ni == null) return null;

        // NativeImage → Skia
        try {
            int w = ni.getWidth(), h = ni.getHeight();
            int[] argb = ni.copyPixelsArgb();
            byte[] rgba = new byte[w * h * 4];
            for (int i = 0; i < argb.length; i++) {
                int c = argb[i], off = i * 4;
                rgba[off] = (byte) ((c >> 16) & 0xFF);
                rgba[off + 1] = (byte) ((c >> 8) & 0xFF);
                rgba[off + 2] = (byte) (c & 0xFF);
                rgba[off + 3] = (byte) ((c >> 24) & 0xFF);
            }
            return Image.makeRaster(new ImageInfo(w, h, ColorType.RGBA_8888, ColorAlphaType.PREMUL,
                    ColorSpace.getSRGB()), rgba, (long) w * 4);
        } catch (Exception ignored) {
            return null;
        }
    }
}
