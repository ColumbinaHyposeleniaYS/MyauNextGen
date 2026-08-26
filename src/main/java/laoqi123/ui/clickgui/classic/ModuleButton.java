package laoqi123.ui.clickgui.classic;

import laoqi123.Myau;
import laoqi123.module.Module;
import laoqi123.util.KeyBindUtil;
import laoqi123.util.config.Choice;
import laoqi123.util.config.NamedChoice;
import laoqi123.value.Value;
import laoqi123.value.properties.BooleanValue;
import laoqi123.value.properties.ColorValue;
import laoqi123.value.properties.EnumChoiceValue;
import laoqi123.value.properties.FloatRangeValue;
import laoqi123.value.properties.FloatValue;
import laoqi123.value.properties.IntChoiceValue;
import laoqi123.value.properties.IntRangeValue;
import laoqi123.value.properties.IntValue;
import laoqi123.value.properties.ModeValue;
import laoqi123.value.properties.MultiEnumChoiceValue;
import laoqi123.value.properties.PercentValue;
import laoqi123.value.properties.TextValue;
import net.minecraft.client.gui.DrawContext;
import org.lwjgl.glfw.GLFW;

import java.awt.Color;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 模块按钮：左键开关、右键展开设置、中键进入按键绑定模式（Esc 清除绑定）。
 * 从 Lyasim 的 ModuleButton 移植，值渲染 / 点击全部映射到 Myau 的值系统：
 * BooleanValue / ModeValue / EnumChoiceValue / IntChoiceValue / FloatValue / IntValue /
 * PercentValue / FloatRangeValue / IntRangeValue / ColorValue / TextValue / MultiEnumChoiceValue。
 */
public class ModuleButton {
    private final Module module;
    private final Set<MultiEnumChoiceValue<?>> expandedMulti = new HashSet<>();
    private final Map<ColorValue, float[]> colorHSB = new HashMap<>();
    private boolean expanded;
    private boolean binding;
    private float height = 15.0f;
    private Value<?> draggingNumber;
    private boolean draggingIntegral;
    private float dragMin, dragMax;
    private TextValue focusedString;
    private float lastX;
    private float lastWidth;

    public ModuleButton(Module module) {
        this.module = module;
    }

    public float getHeight() {
        return height;
    }

    public float render(DrawContext context, int mouseX, int mouseY, float x, float y, float width) {
        lastX = x;
        lastWidth = width;
        if (draggingNumber != null) updateNumber(draggingNumber, mouseX);

        boolean hovered = inside(mouseX, mouseY, x, y, width, 15.0f);
        ClassicRenderUtil.drawRect(context, x, y, width, 15.0f, new Color(0, 0, 0, hovered ? 100 : 80).getRGB());
        int enabledColor = ClassicRenderUtil.accent(1);
        String name = binding ? "Press a key..." : module.getName();
        ClassicFontUtil.drawString(context, name, x + 4.0f, y + 4.0f,
                binding ? 0xFFFFFFFF : (module.isEnabled() ? enabledColor : 0xFFAAAAAA), true);
        if (!binding && module.getKey() != 0) {
            String keyName = KeyBindUtil.getKeyName(module.getKey());
            ClassicFontUtil.drawString(context, keyName,
                    x + width - 4.0f - ClassicFontUtil.getStringWidth(keyName), y + 4.0f, 0xFF888888, false);
        }

        float offsetY = 15.0f;
        if (expanded) {
            for (Value<?> value : moduleValues()) {
                if (!value.isVisible()) continue;
                float rowHeight = valueHeight(value);
                renderValue(context, value, mouseX, mouseY, x, y + offsetY, width, rowHeight, enabledColor);
                offsetY += rowHeight;
            }
        }
        height = offsetY;
        return height;
    }

    private java.util.List<Value<?>> moduleValues() {
        java.util.List<Value<?>> values = Myau.valueManager.properties.get(module.getClass());
        return values == null ? Collections.emptyList() : values;
    }

    private void renderValue(DrawContext context, Value<?> value, int mouseX, int mouseY,
                             float x, float y, float width, float rowHeight, int accent) {
        boolean hovered = inside(mouseX, mouseY, x, y, width, rowHeight);
        ClassicRenderUtil.drawRect(context, x, y, width, rowHeight,
                new Color(10, 10, 10, hovered ? 105 : 90).getRGB());
        ClassicFontUtil.drawString(context, value.getName(), x + 7.0f, y + 4.0f, 0xFFCCCCCC, false);

        if (value instanceof BooleanValue bool) {
            int color = bool.getValue() ? accent : 0xFF555555;
            ClassicRenderUtil.drawRect(context, x + width - 13.0f, y + 4.0f, 7.0f, 7.0f, color);
        } else if (value instanceof ModeValue mode) {
            String text = mode.getModeString();
            ClassicFontUtil.drawString(context, text, x + width - 6.0f - ClassicFontUtil.getStringWidth(text), y + 4.0f, accent, false);
        } else if (value instanceof EnumChoiceValue<?> enumChoice) {
            Object cur = enumChoice.getValue();
            String text = cur instanceof NamedChoice nc ? nc.getChoiceName() : String.valueOf(cur);
            ClassicFontUtil.drawString(context, text, x + width - 6.0f - ClassicFontUtil.getStringWidth(text), y + 4.0f, accent, false);
        } else if (value instanceof IntChoiceValue intChoice) {
            Choice active = intChoice.getConfigurable().getActiveChoice();
            String text = active == null ? "?" : active.getChoiceName();
            ClassicFontUtil.drawString(context, text, x + width - 6.0f - ClassicFontUtil.getStringWidth(text), y + 4.0f, accent, false);
        } else if (value instanceof FloatValue num) {
            renderSlider(context, num.getValue(), num.getMinimum(), num.getMaximum(),
                    String.format(Locale.US, "%.2f", num.getValue()), x, y, width, accent);
        } else if (value instanceof IntValue num) {
            renderSlider(context, num.getValue(), num.getMinimum(), num.getMaximum(),
                    String.valueOf(num.getValue()), x, y, width, accent);
        } else if (value instanceof PercentValue num) {
            renderSlider(context, num.getValue(), num.getMinimum(), num.getMaximum(),
                    num.getValue() + "%", x, y, width, accent);
        } else if (value instanceof FloatRangeValue range) {
            String text = String.format(Locale.US, "%.1f..%.1f", range.getMin(), range.getMax());
            ClassicFontUtil.drawString(context, text, x + width - 6.0f - ClassicFontUtil.getStringWidth(text), y + 4.0f, 0xFFAAAAAA, false);
        } else if (value instanceof IntRangeValue range) {
            String text = range.getMin() + ".." + range.getMax();
            ClassicFontUtil.drawString(context, text, x + width - 6.0f - ClassicFontUtil.getStringWidth(text), y + 4.0f, 0xFFAAAAAA, false);
        } else if (value instanceof ColorValue color) {
            ClassicRenderUtil.drawRect(context, x + width - 21.0f, y + 3.0f, 15.0f, 9.0f, color.getValue());
        } else if (value instanceof TextValue string) {
            String text = string.getValue();
            while (text != null && !text.isEmpty() && ClassicFontUtil.getStringWidth(text) > width / 2.0f) text = text.substring(1);
            int color = focusedString == string ? 0xFFFFFFFF : 0xFFAAAAAA;
            ClassicFontUtil.drawString(context, text, x + width - 6.0f - ClassicFontUtil.getStringWidth(text), y + 4.0f, color, false);
        } else if (value instanceof MultiEnumChoiceValue<?> multi) {
            ClassicFontUtil.drawString(context, expandedMulti.contains(multi) ? "^" : "v", x + width - 12.0f, y + 4.0f, 0xFFAAAAAA, false);
            if (expandedMulti.contains(multi)) {
                float childY = y + 14.0f;
                for (Object ev : multi.getValues()) {
                    String childName = ev instanceof NamedChoice nc ? nc.getChoiceName() : String.valueOf(ev);
                    boolean on = multi.getValue() != null && multi.getValue().contains(ev);
                    ClassicFontUtil.drawString(context, childName, x + 12.0f, childY + 3.0f,
                            on ? accent : 0xFF999999, false);
                    ClassicRenderUtil.drawRect(context, x + width - 13.0f, childY + 3.0f, 7.0f, 7.0f,
                            on ? accent : 0xFF555555);
                    childY += 14.0f;
                }
            }
        }
    }

    private void renderSlider(DrawContext context, Number current, Number min, Number max,
                              String text, float x, float y, float width, int accent) {
        ClassicFontUtil.drawString(context, text, x + width - 6.0f - ClassicFontUtil.getStringWidth(text), y + 3.0f, 0xFFAAAAAA, false);
        float range = max.floatValue() - min.floatValue();
        float progress = range == 0.0f ? 0.0f : clamp((current.floatValue() - min.floatValue()) / range, 0.0f, 1.0f);
        ClassicRenderUtil.drawRect(context, x + 7.0f, y + 15.0f, width - 14.0f, 2.0f, 0xFF444444);
        ClassicRenderUtil.drawRect(context, x + 7.0f, y + 15.0f, (width - 14.0f) * progress, 2.0f, accent);
    }

    public void mouseClicked(int mouseX, int mouseY, int button, float x, float y, float width) {
        if (inside(mouseX, mouseY, x, y, width, 15.0f)) {
            if (button == 0) module.toggle();
            else if (button == 1) expanded = !expanded;
            else if (button == 2) binding = !binding;
            return;
        }
        if (!expanded) return;

        float offsetY = 15.0f;
        for (Value<?> value : moduleValues()) {
            if (!value.isVisible()) continue;
            float rowHeight = valueHeight(value);
            if (inside(mouseX, mouseY, x, y + offsetY, width, rowHeight)) {
                clickValue(value, mouseX, mouseY, button, x, y + offsetY, width);
                return;
            }
            offsetY += rowHeight;
        }
    }

    private void clickValue(Value<?> value, int mouseX, int mouseY, int button, float x, float y, float width) {
        if (value instanceof BooleanValue bool) {
            if (button == 0) bool.setValue(!bool.getValue());
        } else if (value instanceof ModeValue mode) {
            if (mode.getModes().length == 0) return;
            if (button == 1) mode.previousMode();
            else mode.nextMode();
        } else if (value instanceof EnumChoiceValue<?> enumChoice) {
            cycleEnumChoice(enumChoice, button == 0);
        } else if (value instanceof IntChoiceValue intChoice) {
            int n = intChoice.getConfigurable().getChoices().size();
            if (n == 0) return;
            int idx = intChoice.getConfigurable().getActiveIndex();
            int next = button == 1 ? (idx - 1 + n) % n : (idx + 1) % n;
            intChoice.setValue(next);
        } else if (value instanceof FloatValue num) {
            beginDrag(num, num.getMinimum(), num.getMaximum(), false, mouseX, mouseY, y, x, width);
        } else if (value instanceof IntValue num) {
            beginDrag(num, num.getMinimum(), num.getMaximum(), true, mouseX, mouseY, y, x, width);
        } else if (value instanceof PercentValue num) {
            beginDrag(num, num.getMinimum(), num.getMaximum(), true, mouseX, mouseY, y, x, width);
        } else if (value instanceof ColorValue color) {
            if (button == 0) {
                float[] hsb = hsbOf(color);
                float hue = hsb[0] + 0.05f;
                applyHSB(color, hue > 1.0f ? hue - 1.0f : hue, hsb[1], hsb[2]);
            }
        } else if (value instanceof TextValue string) {
            if (button == 0) focusedString = string;
        } else if (value instanceof MultiEnumChoiceValue<?> multi) {
            if (mouseY < y + 14.0f) {
                if (!expandedMulti.remove(multi)) expandedMulti.add(multi);
            } else if (expandedMulti.contains(multi)) {
                Object[] vals = multi.getValues();
                int child = (int) ((mouseY - y - 14.0f) / 14.0f);
                if (child >= 0 && child < vals.length) toggleMultiChoice(multi, vals[child]);
            }
        }
    }

    private void beginDrag(Value<?> value, Number min, Number max, boolean integral,
                           int mouseX, int mouseY, float y, float x, float width) {
        if (mouseY >= y + 11.0f) {
            draggingNumber = value;
            draggingIntegral = integral;
            dragMin = min.floatValue();
            dragMax = max.floatValue();
            lastX = x;
            lastWidth = width;
            updateNumber(value, mouseX);
        }
    }

    public void mouseReleased(int mouseX, int mouseY, int button) {
        if (button == 0) draggingNumber = null;
    }

    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (binding) {
            module.setKey(keyCode == GLFW.GLFW_KEY_ESCAPE ? 0 : keyCode);
            binding = false;
            return true;
        }
        if (focusedString == null) return false;
        if (keyCode == GLFW.GLFW_KEY_BACKSPACE) {
            String text = focusedString.getValue();
            if (text != null && !text.isEmpty()) focusedString.setValue(text.substring(0, text.length() - 1));
        } else if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_ESCAPE) {
            focusedString = null;
        }
        return true;
    }

    public boolean charTyped(char chr, int modifiers) {
        if (focusedString != null && chr >= 32 && chr != 127) {
            focusedString.setValue(focusedString.getValue() + chr);
            return true;
        }
        return false;
    }

    private void updateNumber(Value<?> value, int mouseX) {
        float trackX = lastX + 7.0f;
        float trackWidth = lastWidth - 14.0f;
        float ratio = trackWidth <= 0.0f ? 0.0f : clamp((mouseX - trackX) / trackWidth, 0.0f, 1.0f);
        float raw = dragMin + (dragMax - dragMin) * ratio;
        // 必须分支赋值：三元里 int/float 会数值提升成 float，整型值会收到 Float 导致 validator ClassCastException
        if (draggingIntegral) {
            value.setValue(Math.round(raw));
        } else {
            value.setValue(raw);
        }
    }

    private float valueHeight(Value<?> value) {
        if (value instanceof FloatValue || value instanceof IntValue || value instanceof PercentValue) return 20.0f;
        if (value instanceof MultiEnumChoiceValue<?> multi && expandedMulti.contains(multi)) {
            return 14.0f + multi.getValues().length * 14.0f;
        }
        return value instanceof ColorValue || value instanceof TextValue ? 16.0f : 14.0f;
    }

    /** 取色盘的 HSB 状态（首次访问时从当前 RGB 反推）。 */
    private float[] hsbOf(ColorValue cv) {
        return colorHSB.computeIfAbsent(cv, v -> {
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

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void cycleEnumChoice(EnumChoiceValue raw, boolean forward) {
        Object[] vals = raw.getValues();
        if (vals == null || vals.length == 0) return;
        Object cur = raw.getValue();
        int idx = 0;
        for (int i = 0; i < vals.length; i++) {
            if (vals[i] == cur) { idx = i; break; }
        }
        int next = forward ? (idx + 1) % vals.length : (idx - 1 + vals.length) % vals.length;
        raw.setValue(vals[next]);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void toggleMultiChoice(MultiEnumChoiceValue raw, Object ev) {
        if (!(ev instanceof Enum)) return;
        Enum enumVal = (Enum) ev;
        EnumSet set;
        EnumSet current = (EnumSet) raw.getValue();
        if (current == null || current.isEmpty()) {
            set = EnumSet.noneOf(enumVal.getDeclaringClass());
        } else {
            set = EnumSet.copyOf(current);
        }
        if (set.contains(enumVal)) {
            set.remove(enumVal);
        } else {
            set.add(enumVal);
        }
        raw.setValue(set);
    }

    private static boolean inside(float mouseX, float mouseY, float x, float y, float width, float height) {
        return mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + height;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
