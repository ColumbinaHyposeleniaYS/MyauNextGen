package laoqi123.ui.font;

import laoqi123.ui.font.base.SkiaFont;
import org.jetbrains.skija.Data;
import org.jetbrains.skija.Typeface;

import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Skia 字体管理
 */
public class FontManager {
    private static final FontManager INSTANCE = new FontManager();

    public static FontManager getInstance() {
        return INSTANCE;
    }

    private final Map<String, SkiaFont> cache = new ConcurrentHashMap<>();

    private FontManager() {
    }

    public SkiaFont getFont(int size) {
        return getFont("MiSans-Normal.ttf", size);
    }

    public SkiaFont getMediumFont(int size) {
        return getFont("MiSans-Medium.ttf", size);
    }

    public SkiaFont getSemiboldFont(int size) {
        return getFont("MiSans-Semibold.ttf", size);
    }

    public SkiaFont getBoldFont(int size) {
        return getFont("MiSans-Bold.ttf", size);
    }

    public SkiaFont getIconFont(int size) {
        return getFont("icon.ttf", size);
    }

    private SkiaFont getFont(String name, int size) {
        return cache.computeIfAbsent(name + ":" + size, k -> create(name, size));
    }

    public SkiaFont create(String name, int size) {
        try (InputStream is = SkiaFont.class.getResourceAsStream("/assets/myaunextgen/fonts/" + name)) {
            if (is == null) {
                throw new RuntimeException("Font resources not found: " + name);
            }

            try (Data fontData = Data.makeFromBytes(is.readAllBytes())) {
                return new SkiaFont(Typeface.makeFromData(fontData), size);
            }
        } catch (Exception ignored) {
            return new SkiaFont(Typeface.makeDefault(), size);
        }
    }

    public void destroy() {
        cache.values().forEach(SkiaFont::close);
        cache.clear();
    }
}
