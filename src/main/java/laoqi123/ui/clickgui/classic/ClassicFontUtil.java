package laoqi123.ui.clickgui.classic;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;

/**
 * 经典 ClickGUI 的文字绘制辅助。
 * 移植自 Lyasim 的 util.render.FontUtil：原版 TextRenderer + context.drawText（画两遍加重）。
 */
public final class ClassicFontUtil {

    private ClassicFontUtil() {
    }

    public static void drawString(DrawContext context, String text, float x, float y, int color, boolean shadow) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.textRenderer == null) return;

        context.getMatrices().push();
        context.getMatrices().translate(x, y, 0);
        context.drawText(mc.textRenderer, text, 0, 0, color, shadow);
        context.drawText(mc.textRenderer, text, 0, 0, color, shadow);
        context.getMatrices().pop();
    }

    public static void drawStringWithShadow(DrawContext context, String text, float x, float y, int color) {
        drawString(context, text, x, y, color, true);
    }

    public static float getStringWidth(String text) {
        return MinecraftClient.getInstance().textRenderer.getWidth(text);
    }

    public static float getHeight() {
        return MinecraftClient.getInstance().textRenderer.fontHeight;
    }
}
