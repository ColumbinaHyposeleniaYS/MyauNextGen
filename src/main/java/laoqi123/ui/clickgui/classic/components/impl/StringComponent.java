package laoqi123.ui.clickgui.classic.components.impl;

import laoqi123.ui.clickgui.classic.ClassicFontUtil;
import laoqi123.ui.clickgui.classic.ClassicRenderUtil;
import laoqi123.ui.clickgui.classic.components.ValueComponent;
import laoqi123.value.properties.TextValue;
import net.minecraft.client.gui.DrawContext;
import org.lwjgl.glfw.GLFW;

import java.awt.Color;

/**
 * 文本输入项。从 Lyasim 的 StringComponent 移植，StringValue → TextValue
 * （getString/setString → getValue/setValue），含闪烁光标与退格/回车/Esc 处理。
 */
public class StringComponent extends ValueComponent {
    private boolean focused;

    public StringComponent(TextValue value) {
        super(value);
    }

    @Override
    public float render(DrawContext context, int mouseX, int mouseY, float x, float y, float width) {
        TextValue strVal = (TextValue) getValue();

        ClassicRenderUtil.drawRect(context, x, y, width, 15, new Color(0, 0, 0, 120).getRGB());

        String text = strVal.getValue();
        String display = strVal.getName() + ": " + text;
        int color = focused ? 0xFFFFFFFF : 0xFFAAAAAA;
        ClassicFontUtil.drawStringWithShadow(context, display, x + 6, y + 3, color);

        if (focused && System.currentTimeMillis() % 1000 > 500) {
            float labelW = ClassicFontUtil.getStringWidth(strVal.getName() + ": ");
            float cursorX = x + 6 + labelW + ClassicFontUtil.getStringWidth(text);
            ClassicRenderUtil.drawRect(context, cursorX, y + 3, 1, 10, 0xFFFFFFFF);
        }

        return lastHeight = 15;
    }

    @Override
    public void mouseClicked(int mouseX, int mouseY, int button, float x, float y, float width) {
        if (button == 0) {
            focused = hovered(mouseX, mouseY, x, y, width, 15);
        }
    }

    @Override
    public void keyPressed(int keyCode, int scanCode, int modifiers) {
        if (!focused) return;
        TextValue strVal = (TextValue) getValue();

        if (keyCode == GLFW.GLFW_KEY_BACKSPACE) {
            String s = strVal.getValue();
            if (s != null && !s.isEmpty()) {
                strVal.setValue(s.substring(0, s.length() - 1));
            }
        } else if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_ESCAPE) {
            focused = false;
        }
    }

    @Override
    public void charTyped(char chr, int modifiers) {
        if (!focused) return;
        TextValue strVal = (TextValue) getValue();
        if (chr >= 32 && chr != 127) {
            strVal.setValue(strVal.getValue() + chr);
        }
    }
}
