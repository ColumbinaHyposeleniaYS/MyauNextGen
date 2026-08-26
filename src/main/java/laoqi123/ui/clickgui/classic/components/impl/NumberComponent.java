package laoqi123.ui.clickgui.classic.components.impl;

import laoqi123.ui.clickgui.classic.ClassicFontUtil;
import laoqi123.ui.clickgui.classic.ClassicRenderUtil;
import laoqi123.ui.clickgui.classic.components.ValueComponent;
import laoqi123.value.Value;
import laoqi123.value.properties.FloatRangeValue;
import laoqi123.value.properties.FloatValue;
import laoqi123.value.properties.IntRangeValue;
import laoqi123.value.properties.IntValue;
import laoqi123.value.properties.PercentValue;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.math.MathHelper;

import java.awt.Color;
import java.util.Locale;

/**
 * 数值滑条。从 Lyasim 的 NumberComponent 移植：
 * Lyasim NumberValue → Myau FloatValue（连续）/ IntValue / PercentValue（整数步进），
 * 并扩展支持 Myau 独有的 FloatRangeValue / IntRangeValue（只显示 "min..max"，不拖动）。
 */
public class NumberComponent extends ValueComponent {
    private boolean dragging;

    public NumberComponent(FloatValue value) {
        super(value);
    }

    public NumberComponent(IntValue value) {
        super(value);
    }

    public NumberComponent(PercentValue value) {
        super(value);
    }

    public NumberComponent(FloatRangeValue value) {
        super(value);
    }

    public NumberComponent(IntRangeValue value) {
        super(value);
    }

    @Override
    public float render(DrawContext context, int mouseX, int mouseY, float x, float y, float width) {
        Value<?> v = getValue();
        if (dragging && !isRange(v)) {
            updateFromMouse(v, mouseX, x, width);
        }

        ClassicRenderUtil.drawRect(context, x, y, width, 15, new Color(0, 0, 0, 120).getRGB());
        if (!isRange(v)) {
            float percent = sliderPercent(v);
            ClassicRenderUtil.drawGradientRect(context, x, y + 13, width * percent, 2,
                    ClassicRenderUtil.accent(0), ClassicRenderUtil.accent(4), false);
        }
        ClassicFontUtil.drawStringWithShadow(context, valueText(v), x + 6, y + 3, 0xFFAAAAAA);
        return lastHeight = 15;
    }

    @Override
    public void mouseClicked(int mouseX, int mouseY, int button, float x, float y, float width) {
        if (button == 0 && hovered(mouseX, mouseY, x, y, width, 15) && !isRange(getValue())) {
            dragging = true;
            updateFromMouse(getValue(), mouseX, x, width);
        }
    }

    @Override
    public void mouseReleased(int mouseX, int mouseY, int button) {
        dragging = false;
    }

    private void updateFromMouse(Value<?> v, int mouseX, float x, float width) {
        float min = minOf(v);
        float max = maxOf(v);
        float percent = MathHelper.clamp((mouseX - x) / width, 0.0f, 1.0f);
        float val = min + (max - min) * percent;
        if (v instanceof IntValue || v instanceof PercentValue) {
            v.setValue(Math.round(val));
        } else {
            v.setValue(val);
        }
    }

    private static boolean isRange(Value<?> v) {
        return v instanceof FloatRangeValue || v instanceof IntRangeValue;
    }

    private static float minOf(Value<?> v) {
        if (v instanceof FloatValue num) return num.getMinimum();
        if (v instanceof IntValue num) return num.getMinimum();
        if (v instanceof PercentValue num) return num.getMinimum();
        return 0.0f;
    }

    private static float maxOf(Value<?> v) {
        if (v instanceof FloatValue num) return num.getMaximum();
        if (v instanceof IntValue num) return num.getMaximum();
        if (v instanceof PercentValue num) return num.getMaximum();
        return 0.0f;
    }

    private static float sliderPercent(Value<?> v) {
        float range = maxOf(v) - minOf(v);
        if (range == 0.0f) return 0.0f;
        if (v instanceof FloatValue num) {
            return MathHelper.clamp((num.getValue() - num.getMinimum()) / range, 0.0f, 1.0f);
        }
        if (v instanceof IntValue num) {
            return MathHelper.clamp((num.getValue() - num.getMinimum()) / range, 0.0f, 1.0f);
        }
        if (v instanceof PercentValue num) {
            return MathHelper.clamp((num.getValue() - num.getMinimum()) / range, 0.0f, 1.0f);
        }
        return 0.0f;
    }

    private static String valueText(Value<?> v) {
        if (v instanceof FloatValue num) {
            return num.getName() + " " + String.format(Locale.US, "%.2f", num.getValue()).replace(".00", "");
        }
        if (v instanceof IntValue num) {
            return num.getName() + " " + num.getValue();
        }
        if (v instanceof PercentValue num) {
            return num.getName() + " " + num.getValue() + "%";
        }
        if (v instanceof FloatRangeValue range) {
            return range.getName() + " " + String.format(Locale.US, "%.1f..%.1f", range.getMin(), range.getMax());
        }
        if (v instanceof IntRangeValue range) {
            return range.getName() + " " + range.getMin() + ".." + range.getMax();
        }
        return v.getName();
    }
}
