package laoqi123.ui.font.base;

import net.minecraft.util.Formatting;
import org.jetbrains.skija.TextBlob;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class FontCache {
    private final Map<CacheKey, TextRecord> cache;

    public FontCache(int maxCapacity) {
        this.cache = new LinkedHashMap<>(maxCapacity, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<CacheKey, TextRecord> eldest) {
                if (size() > maxCapacity) {
                    eldest.getValue().close();
                    return true;
                }
                return false;
            }
        };
    }

    public TextRecord get(String text, int color, SkiaFont font) {
        return cache.computeIfAbsent(new CacheKey(text, color), b -> new TextRecord(text, color, font));
    }

    public void clear() {
        cache.values().forEach(TextRecord::close);
        cache.clear();
    }

    public static class TextRecord {
        public final Segment[] segments;
        public final float width;

        public TextRecord(String text, int baseColor, SkiaFont font) {
            List<Segment> segmentList = new ArrayList<>();
            int color = baseColor;
            float width = 0;

            StringBuilder buffer = new StringBuilder();

            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c == '§' && i + 1 < text.length()) {
                    if (!buffer.isEmpty()) {
                        width += flushBuffer(buffer, segmentList, color, font);
                    }

                    Formatting format = Formatting.byCode(text.charAt(++i));
                    if (format != null) {
                        if (format == Formatting.RESET) {
                            color = baseColor;
                        } else if (format.getColorValue() != null) {
                            color = (((baseColor >> 24) & 0xFF) << 24) | format.getColorValue();
                        }
                    }
                } else {
                    buffer.append(c);
                }
            }

            if (!buffer.isEmpty()) {
                width += flushBuffer(buffer, segmentList, color, font);
            }

            this.segments = segmentList.toArray(new Segment[0]);
            this.width = width;
        }

        private float flushBuffer(StringBuilder buffer, List<Segment> list, int color, SkiaFont font) {
            String str = buffer.toString();
            buffer.setLength(0);
            TextBlob blob = font.getShaper().shape(str, font.getFont());
            float width = font.getFont().measureTextWidth(str);
            list.add(new Segment(blob, color, width));
            return width;
        }

        public void close() {
            for (Segment seg : segments) {
                if (seg.blob() != null) {
                    seg.blob().close();
                }
            }
        }
    }

    private record CacheKey(String text, int color) {}
    public record Segment(TextBlob blob, int color, float width) {}
}
