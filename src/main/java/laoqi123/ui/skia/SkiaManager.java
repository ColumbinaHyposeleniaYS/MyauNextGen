package laoqi123.ui.skia;

import com.mojang.blaze3d.systems.RenderSystem;
import laoqi123.event.EventManager;
import laoqi123.event.impl.RenderSkiaEvent;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import org.jetbrains.skija.Canvas;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;

import java.nio.ByteBuffer;
import java.util.function.Consumer;

/**
 * Skia 渲染层管理
 */
public class SkiaManager {
    private static SkiaManager instance;

    public static SkiaManager getInstance() {
        if (instance == null) {
            instance = new SkiaManager();
        }
        return instance;
    }

    /**
     * glGetBooleanv
     */
    private static final ByteBuffer COLOR_MASK_BUFFER = ByteBuffer.allocateDirect(16);

    public final Skia skia = new Skia();

    private SkiaManager() {
    }

    private static final class GLState {
        boolean blend, depth, cull, scissor, stencil;
        int depthFunc;
        int blendSrcRgb, blendDstRgb, blendSrcAlpha, blendDstAlpha;
        int blendEqRgb, blendEqAlpha;
        boolean cmR, cmG, cmB, cmA, depthMask;
        int[] vp = new int[4];
        int[] sc = new int[4];
        int program, vao;
        int arrayBuf, elemBuf, unpackBuf;
        int unpAlign, unpRowLen, unpSkipPx, unpSkipRw;
        int activeTex, drawFb, readFb;
        int[] tex2D = new int[8];
    }

    private GLState saveGLState() {
        GLState s = new GLState();

        s.blend   = GL11.glIsEnabled(GL11.GL_BLEND);
        s.depth   = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        s.cull    = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        s.scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        s.stencil = GL11.glIsEnabled(GL11.GL_STENCIL_TEST);

        s.depthFunc = GL11.glGetInteger(GL11.GL_DEPTH_FUNC);

        s.blendSrcRgb   = GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB);
        s.blendDstRgb   = GL11.glGetInteger(GL14.GL_BLEND_DST_RGB);
        s.blendSrcAlpha = GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA);
        s.blendDstAlpha = GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA);
        s.blendEqRgb    = GL11.glGetInteger(GL20.GL_BLEND_EQUATION_RGB);
        s.blendEqAlpha  = GL11.glGetInteger(GL20.GL_BLEND_EQUATION_ALPHA);

        GL11.glGetBooleanv(GL11.GL_COLOR_WRITEMASK, COLOR_MASK_BUFFER);
        s.cmR = COLOR_MASK_BUFFER.get(0) != 0; s.cmG = COLOR_MASK_BUFFER.get(1) != 0;
        s.cmB = COLOR_MASK_BUFFER.get(2) != 0; s.cmA = COLOR_MASK_BUFFER.get(3) != 0;
        s.depthMask = GL11.glGetInteger(GL11.GL_DEPTH_WRITEMASK) != 0;

        GL11.glGetIntegerv(GL11.GL_VIEWPORT, s.vp);
        GL11.glGetIntegerv(GL11.GL_SCISSOR_BOX, s.sc);

        s.program = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        s.vao     = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);

        s.arrayBuf   = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        s.elemBuf    = GL11.glGetInteger(GL15.GL_ELEMENT_ARRAY_BUFFER_BINDING);
        s.unpackBuf  = GL11.glGetInteger(GL21.GL_PIXEL_UNPACK_BUFFER_BINDING);

        s.unpAlign  = GL11.glGetInteger(GL11.GL_UNPACK_ALIGNMENT);
        s.unpRowLen = GL11.glGetInteger(GL11.GL_UNPACK_ROW_LENGTH);
        s.unpSkipPx = GL11.glGetInteger(GL11.GL_UNPACK_SKIP_PIXELS);
        s.unpSkipRw = GL11.glGetInteger(GL11.GL_UNPACK_SKIP_ROWS);

        s.activeTex = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        s.drawFb    = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        s.readFb    = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);

        for (int i = 0; i < 8; i++) {
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + i);
            s.tex2D[i] = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        }
        GL13.glActiveTexture(s.activeTex);

        return s;
    }

    private void restoreGLState(GLState s) {
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, s.drawFb);
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, s.readFb);

        GL11.glViewport(s.vp[0], s.vp[1], s.vp[2], s.vp[3]);
        GL11.glScissor(s.sc[0], s.sc[1], s.sc[2], s.sc[3]);
        if (s.scissor) GL11.glEnable(GL11.GL_SCISSOR_TEST); else GL11.glDisable(GL11.GL_SCISSOR_TEST);

        GL11.glColorMask(s.cmR, s.cmG, s.cmB, s.cmA);
        GL11.glDepthMask(s.depthMask);
        GL11.glDepthFunc(s.depthFunc);

        if (s.blend) GL11.glEnable(GL11.GL_BLEND); else GL11.glDisable(GL11.GL_BLEND);
        GL14.glBlendFuncSeparate(s.blendSrcRgb, s.blendDstRgb, s.blendSrcAlpha, s.blendDstAlpha);
        GL20.glBlendEquationSeparate(s.blendEqRgb, s.blendEqAlpha);

        if (s.depth)   GL11.glEnable(GL11.GL_DEPTH_TEST);   else GL11.glDisable(GL11.GL_DEPTH_TEST);
        if (s.cull)    GL11.glEnable(GL11.GL_CULL_FACE);    else GL11.glDisable(GL11.GL_CULL_FACE);
        if (s.stencil) GL11.glEnable(GL11.GL_STENCIL_TEST); else GL11.glDisable(GL11.GL_STENCIL_TEST);

        GL20.glUseProgram(s.program);
        GL30.glBindVertexArray(s.vao);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, s.arrayBuf);
        GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, s.elemBuf);
        GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, s.unpackBuf);

        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, s.unpAlign);
        GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, s.unpRowLen);
        GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, s.unpSkipPx);
        GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, s.unpSkipRw);

        for (int i = 0; i < 8; i++) {
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + i);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, s.tex2D[i]);
        }
        GL13.glActiveTexture(s.activeTex);
    }

    /** HUD
    public void render(DrawContext context) {
        renderFrame(canvas -> EventManager.call(new RenderSkiaEvent(context, canvas)), true);
    }

    /**
     * 独绘制任务，不触发enderSkiaEvent
     */
    public void renderOverlay(Runnable task, boolean allowBackdrop) {
        renderFrame(canvas -> task.run(), allowBackdrop);
    }

    private void renderFrame(Consumer<Canvas> body, boolean allowBackdrop) {
        if (!RenderSystem.isOnRenderThread()) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.getFramebuffer() == null) return;

        try {
            if (skia.context == null) skia.initSkia();
            skia.checkAndUpdateSurface();

            GLState state = saveGLState();

            skia.beginFrame();

            if (allowBackdrop && skia.shouldCaptureBackdrop()) {
                skia.captureBackground();
            }

            skia.canvas.save();
            float scale = (float) mc.getWindow().getScaleFactor();
            skia.canvas.scale(scale, scale);
            body.accept(skia.canvas);
            skia.canvas.restore();

            skia.endFrame();
            skia.releaseBackground();
            skia.rollBackdropDemand();

            restoreGLState(state);
        } catch (Exception ignored) {
        }
    }

    public void destroy() {
        skia.cleanup();
    }
}
