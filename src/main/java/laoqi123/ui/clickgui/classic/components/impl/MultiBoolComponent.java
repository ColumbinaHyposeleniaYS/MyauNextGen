package laoqi123.ui.clickgui.classic.components.impl;

import laoqi123.ui.clickgui.classic.ClassicFontUtil;
import laoqi123.ui.clickgui.classic.ClassicRenderUtil;
import laoqi123.ui.clickgui.classic.components.ValueComponent;
import laoqi123.util.config.NamedChoice;
import laoqi123.value.properties.MultiEnumChoiceValue;
import net.minecraft.client.gui.DrawContext;

import java.awt.Color;
import java.util.EnumSet;

/**
 * 多选设置项（右键展开子项列表，左键切换）。从 Lyasim 的 MultiBoolComponent 移植：
 * Lyasim MultiBoolValue（BoolValue[] getValues()）→ Myau MultiEnumChoiceValue&lt;?&gt;
 * （getValues() Object[] 枚举、getValue() EnumSet），切换走 EnumSet 复制 + 增删（同 Myau ClickGui 的 toggleMultiChoice）。
 */
public class MultiBoolComponent extends ValueComponent {
    private boolean expanded;

    public MultiBoolComponent(MultiEnumChoiceValue<?> value) {
        super(value);
    }

    @Override
    public float render(DrawContext context, int mouseX, int mouseY, float x, float y, float width) {
        float height = 15;
        MultiEnumChoiceValue<?> multi = (MultiEnumChoiceValue<?>) getValue();
        ClassicRenderUtil.drawRect(context, x, y, width, 15, new Color(0, 0, 0, 120).getRGB());
        ClassicFontUtil.drawStringWithShadow(context, multi.getName() + (expanded ? " -" : " +"), x + 6, y + 4, 0xFFAAAAAA);

        if (expanded) {
            for (Object ev : multi.getValues()) {
                ClassicRenderUtil.drawRect(context, x, y + height, width, 15, new Color(0, 0, 0, 120).getRGB());
                String childName = ev instanceof NamedChoice nc ? nc.getChoiceName() : String.valueOf(ev);
                boolean on = multi.getValue() != null && multi.getValue().contains(ev);
                ClassicFontUtil.drawStringWithShadow(context, childName, x + 14, y + height + 4,
                        on ? ClassicRenderUtil.accent(0) : 0xFFAAAAAA);
                ClassicRenderUtil.drawRect(context, x + width - 13, y + height + 4, 7, 7,
                        on ? ClassicRenderUtil.accent(0) : 0xFF555555);
                height += 15;
            }
        }
        return lastHeight = height;
    }

    @Override
    public void mouseClicked(int mouseX, int mouseY, int button, float x, float y, float width) {
        if (hovered(mouseX, mouseY, x, y, width, 15)) {
            if (button == 1) expanded = !expanded;
        } else if (expanded) {
            float offsetY = 15;
            for (Object ev : ((MultiEnumChoiceValue<?>) getValue()).getValues()) {
                if (button == 0 && hovered(mouseX, mouseY, x, y + offsetY, width, 15)) {
                    toggleMultiChoice((MultiEnumChoiceValue<?>) getValue(), ev);
                    return;
                }
                offsetY += 15;
            }
        }
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
}
