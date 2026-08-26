package laoqi123.ui.clickgui.classic.components.impl;

import laoqi123.ui.clickgui.classic.ClassicFontUtil;
import laoqi123.ui.clickgui.classic.ClassicRenderUtil;
import laoqi123.ui.clickgui.classic.components.ValueComponent;
import laoqi123.util.config.Choice;
import laoqi123.util.config.NamedChoice;
import laoqi123.value.Value;
import laoqi123.value.properties.EnumChoiceValue;
import laoqi123.value.properties.IntChoiceValue;
import laoqi123.value.properties.ModeValue;
import net.minecraft.client.gui.DrawContext;

import java.awt.Color;

/**
 * 单选设置项。从 Lyasim 的 ModeComponent 移植：
 * Lyasim ModeValue(String) → Myau ModeValue(Integer 索引，getModeString/nextMode/previousMode)，
 * 并扩展支持 Myau 独有的 EnumChoiceValue（枚举单选）与 IntChoiceValue（Choice 配置单选）。
 */
public class ModeComponent extends ValueComponent {

    public ModeComponent(ModeValue value) {
        super(value);
    }

    public ModeComponent(EnumChoiceValue<?> value) {
        super(value);
    }

    public ModeComponent(IntChoiceValue value) {
        super(value);
    }

    @Override
    public float render(DrawContext context, int mouseX, int mouseY, float x, float y, float width) {
        ClassicRenderUtil.drawRect(context, x, y, width, 15, new Color(0, 0, 0, 120).getRGB());
        ClassicFontUtil.drawStringWithShadow(context, getValue().getName() + ": " + currentText(), x + 6, y + 4, 0xFFAAAAAA);
        return lastHeight = 15;
    }

    @Override
    public void mouseClicked(int mouseX, int mouseY, int button, float x, float y, float width) {
        if (button != 0 || !hovered(mouseX, mouseY, x, y, width, 15)) return;
        Value<?> v = getValue();
        if (v instanceof ModeValue mode) {
            if (mode.getModes().length == 0) return;
            mode.nextMode();
        } else if (v instanceof EnumChoiceValue<?> enumChoice) {
            cycleEnumChoice(enumChoice, true);
        } else if (v instanceof IntChoiceValue intChoice) {
            int n = intChoice.getConfigurable().getChoices().size();
            if (n == 0) return;
            intChoice.setValue((intChoice.getConfigurable().getActiveIndex() + 1) % n);
        }
    }

    private String currentText() {
        Value<?> v = getValue();
        if (v instanceof ModeValue mode) return mode.getModeString();
        if (v instanceof EnumChoiceValue<?> enumChoice) {
            Object cur = enumChoice.getValue();
            return cur instanceof NamedChoice nc ? nc.getChoiceName() : String.valueOf(cur);
        }
        if (v instanceof IntChoiceValue intChoice) {
            Choice active = intChoice.getConfigurable().getActiveChoice();
            return active == null ? "?" : active.getChoiceName();
        }
        return "?";
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
}
