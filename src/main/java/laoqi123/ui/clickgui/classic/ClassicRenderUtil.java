package laoqi123.ui.clickgui.classic;

import laoqi123.Myau;
import laoqi123.module.modules.render.HUD;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import org.joml.Matrix4f;

/**
 * 经典 ClickGUI 的 DrawContext 绘制辅助。
 * 矩形 / 渐变实现移植自 Lyasim 的 util.render.RenderUtil（原版 GUI 渲染路径，不依赖 Skia）。
 */
public final class ClassicRenderUtil {

    private ClassicRenderUtil() {
    }

    /**
     * HUD 强调色。
     *
     * @param offset 颜色循环的时间偏移（ms），对应 Lyasim 的 getColor() / getColor(4)
     */
    public static int accent(long offset) {
        HUD hud = (HUD) Myau.moduleManager.modules.get(HUD.class);
        return hud != null ? hud.getColor(System.currentTimeMillis(), offset).getRGB() : 0xFFFFFFFF;
    }

    public static void drawRect(DrawContext context, float x, float y, float width, float height, int color) {
        context.getMatrices().push();
        context.getMatrices().translate(x, y, 0);

        Matrix4f matrix = context.getMatrices().peek().getPositionMatrix();
        context.draw(vertexConsumers -> {
            VertexConsumer buffer = vertexConsumers.getBuffer(RenderLayer.getGui());
            buffer.vertex(matrix, 0, 0, 0).color(color);
            buffer.vertex(matrix, 0, height, 0).color(color);
            buffer.vertex(matrix, width, height, 0).color(color);
            buffer.vertex(matrix, width, 0, 0).color(color);
        });

        context.getMatrices().pop();
    }

    public static void drawGradientRect(DrawContext context, float x, float y, float width, float height,
                                        int startColor, int endColor, boolean horizontal) {
        context.getMatrices().push();
        context.getMatrices().translate(x, y, 0);

        Matrix4f matrix = context.getMatrices().peek().getPositionMatrix();
        context.draw(vertexConsumers -> {
            VertexConsumer buffer = vertexConsumers.getBuffer(RenderLayer.getGui());
            if (horizontal) {
                buffer.vertex(matrix, 0, 0, 0).color(startColor);
                buffer.vertex(matrix, 0, height, 0).color(startColor);
                buffer.vertex(matrix, width, height, 0).color(endColor);
                buffer.vertex(matrix, width, 0, 0).color(endColor);
            } else {
                buffer.vertex(matrix, 0, 0, 0).color(startColor);
                buffer.vertex(matrix, 0, height, 0).color(endColor);
                buffer.vertex(matrix, width, height, 0).color(endColor);
                buffer.vertex(matrix, width, 0, 0).color(startColor);
            }
        });

        context.getMatrices().pop();
    }
}
