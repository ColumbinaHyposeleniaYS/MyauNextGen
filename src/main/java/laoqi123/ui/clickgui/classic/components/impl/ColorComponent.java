package laoqi123.ui.clickgui.classic.components.impl;

import laoqi123.ui.clickgui.classic.ClassicFontUtil;
import laoqi123.ui.clickgui.classic.ClassicRenderUtil;
import laoqi123.ui.clickgui.classic.components.ValueComponent;
import laoqi123.value.properties.ColorValue;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.math.MathHelper;

import java.awt.Color;
import java.util.HashMap;
import java.util.Map;

/**
 * 颜色设置项（右键展开 SB 取色盘 + 色相条）。从 Lyasim 的 ColorComponent 移植。
 * Lyasim ColorValue 持有 Color 对象（getHue/setHSB），
 * Myau ColorValue 是 ARGB int —— HSB 状态用 Map 缓存，读写时经
 * java.awt.Color.RGBtoHSB / HSBtoRGB 转换（同 Myau ClickGui 的 hsbOf/applyHSB 模式）。
 */
public class ColorComponent extends ValueComponent {
    private static final float SB_HEIGHT = 50;
    private static final float HUE_HEIGHT = 10;

    private final Map<ColorValue, float[]> hsbCache = new HashMap<>();
    private boolean expanded, draggingSB, draggingHue;

    public ColorComponent(ColorValue value) {
        super(value);
    }

    @Override
    public float render(DrawContext context, int mouseX, int mouseY, float x, float y, float width) {
        ColorValue color = (ColorValue) getValue();
        float height = 15;

        ClassicRenderUtil.drawRect(context, x, y, width, 15, new Color(0, 0, 0, 120).getRGB());
        ClassicFontUtil.drawStringWithShadow(context, color.getName(), x + 6, y + 4, 0xFFAAAAAA);
        ClassicRenderUtil.drawRect(context, x + width - 16, y + 3, 10, 9, color.getValue());

        if (expanded) {
            float pickerY = y + 15;
            float[] hsb = hsbOf(color);

            if (draggingSB) {
                float s = MathHelper.clamp((mouseX - x) / width, 0f, 1f);
                float b = 1f - MathHelper.clamp((mouseY - pickerY) / SB_HEIGHT, 0f, 1f);
                applyHSB(color, hsb[0], s, b);
            }
            if (draggingHue) {
                float h = MathHelper.clamp((mouseX - x) / width, 0f, 1f);
                applyHSB(color, h, hsb[1], hsb[2]);
            }

            int pureHue = Color.getHSBColor(hsb[0], 1f, 1f).getRGB();
            ClassicRenderUtil.drawGradientRect(context, x, pickerY, width, SB_HEIGHT, 0xFFFFFFFF, pureHue, true);
            ClassicRenderUtil.drawGradientRect(context, x, pickerY, width, SB_HEIGHT, 0x00000000, 0xFF000000, false);

            float pX = x + hsb[1] * width;
            float pY = pickerY + (1f - hsb[2]) * SB_HEIGHT;
            ClassicRenderUtil.drawRect(context, pX - 1.5f, pY - 1.5f, 3, 3, 0xFFFFFFFF);

            float hueY = pickerY + SB_HEIGHT;
            float seg = width / 6f;
            int[] hues = {0xFFFF0000, 0xFFFFFF00, 0xFF00FF00, 0xFF00FFFF, 0xFF0000FF, 0xFFFF00FF, 0xFFFF0000};

            for (int i = 0; i < 6; i++) {
                float segX = x + i * seg;
                float nextX = x + (i + 1) * seg;
                ClassicRenderUtil.drawGradientRect(context, segX, hueY, nextX - segX, HUE_HEIGHT, hues[i], hues[i + 1], true);
            }

            ClassicRenderUtil.drawRect(context, x + hsb[0] * width - 1, hueY, 2, HUE_HEIGHT, 0xFFFFFFFF);
            height += SB_HEIGHT + HUE_HEIGHT;
        }

        return lastHeight = height;
    }

    @Override
    public void mouseClicked(int mouseX, int mouseY, int button, float x, float y, float width) {
        if (hovered(mouseX, mouseY, x, y, width, 15)) {
            if (button == 1) {
                expanded = !expanded;
                if (expanded) hsbCache.remove(getValue()); // 重新展开时从当前颜色反推 HSB
            }
        } else if (expanded) {
            float pickerY = y + 15;
            if (button == 0) {
                if (hovered(mouseX, mouseY, x, pickerY, width, SB_HEIGHT)) {
                    draggingSB = true;
                } else if (hovered(mouseX, mouseY, x, pickerY + SB_HEIGHT, width, HUE_HEIGHT)) {
                    draggingHue = true;
                }
            }
        }
    }

    @Override
    public void mouseReleased(int mouseX, int mouseY, int button) {
        draggingSB = draggingHue = false;
    }

    private float[] hsbOf(ColorValue cv) {
        return hsbCache.computeIfAbsent(cv, v -> {
            int rgb = v.getValue();
            return Color.RGBtoHSB((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF, null);
        });
    }

    private void applyHSB(ColorValue cv, float h, float s, float b) {
        float[] hsb = hsbOf(cv);
        hsb[0] = h;
        hsb[1] = s;
        hsb[2] = b;
        cv.setValue(Color.HSBtoRGB(h, s, b) | 0xFF000000);
    }
}
