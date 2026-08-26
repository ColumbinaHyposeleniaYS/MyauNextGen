package laoqi123.ui.clickgui.classic.components.impl;

import laoqi123.ui.clickgui.classic.ClassicFontUtil;
import laoqi123.ui.clickgui.classic.ClassicRenderUtil;
import laoqi123.ui.clickgui.classic.components.ValueComponent;
import laoqi123.value.properties.BooleanValue;
import net.minecraft.client.gui.DrawContext;

import java.awt.Color;

/**
 * 布尔设置项。从 Lyasim 的 BoolComponent 移植，BoolValue → BooleanValue。
 */
public class BoolComponent extends ValueComponent {

    public BoolComponent(BooleanValue value) {
        super(value);
    }

    @Override
    public float render(DrawContext context, int mouseX, int mouseY, float x, float y, float width) {
        ClassicRenderUtil.drawRect(context, x, y, width, 15, new Color(0, 0, 0, 120).getRGB());
        ClassicFontUtil.drawStringWithShadow(context, getValue().getName(), x + 6, y + 4,
                ((BooleanValue) getValue()).getValue() ? ClassicRenderUtil.accent(0) : 0xFFAAAAAA);
        return lastHeight = 15;
    }

    @Override
    public void mouseClicked(int mouseX, int mouseY, int button, float x, float y, float width) {
        if (button == 0 && hovered(mouseX, mouseY, x, y, width, 15)) {
            BooleanValue bool = (BooleanValue) getValue();
            bool.setValue(!bool.getValue());
        }
    }
}
