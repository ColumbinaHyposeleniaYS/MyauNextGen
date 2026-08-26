package laoqi123.ui.skia;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.MinecraftClient;
import org.jetbrains.skija.*;
import org.lwjgl.opengl.*;

public class Skia {
    public BackendRenderTarget renderTarget;
    public DirectContext context;
    public Surface surface;
    public Canvas canvas;

    private int lastWidth = -1;
    private int lastHeight = -1;
    private int lastFbId = -1;

    public void initSkia() {
        if (context == null) {
            context = DirectContext.makeGL();
        }
        createSurface();
    }

    private void createSurface() {
        MinecraftClient mc = MinecraftClient.getInstance();

        if (surface != null) {
            surface.close();
        }

        if (renderTarget != null) {
            renderTarget.close();
        }

        int width = mc.getFramebuffer().textureWidth;
        int height = mc.getFramebuffer().textureHeight;
        int fbId = mc.getFramebuffer().fbo;

        renderTarget = BackendRenderTarget.makeGL(width, height, 0, 8, fbId, 0x8058);
        surface = Surface.makeFromBackendRenderTarget(context, renderTarget, SurfaceOrigin.BOTTOM_LEFT, SurfaceColorFormat.RGBA_8888, ColorSpace.getSRGB());
        canvas = surface.getCanvas();

        lastWidth = width;
        lastHeight = height;
        lastFbId = fbId;
    }

    public void beginFrame() {
        RenderSystem.assertOnRenderThread();

        // 设置 pixelStore
        GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, 0);
        GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, 0);
        GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, 0);
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);

        // 解绑 PBO
        GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);


        if (context != null) {
            context.resetGLAll();
        }
    }

    public void endFrame() {
        RenderSystem.assertOnRenderThread();

        if (surface != null) {
            surface.flushAndSubmit();
        }


        GL33.glBindSampler(0, 0);
    }

    public void checkAndUpdateSurface() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (lastWidth != mc.getFramebuffer().textureWidth || lastHeight != mc.getFramebuffer().textureHeight || lastFbId != mc.getFramebuffer().fbo) {
            createSurface();
        }
    }

    public void cleanup() {
        releaseBackground();
        if (surface != null) {
            surface.close();
        }
        if (renderTarget != null) {
            renderTarget.close();
        }
        if (context != null) {
            context.abandon();
        }

        surface = null;
        renderTarget = null;
        context = null;
        canvas = null;
    }


    private Image backgroundSnapshot;


    private boolean backdropWanted;

    private boolean backdropWantedLastFrame;

    public void captureBackground() {
        if (backgroundSnapshot != null) {
            backgroundSnapshot.close();
        }
        backgroundSnapshot = surface.makeImageSnapshot();
    }

    public Image captureFullScreen() {
        backdropWanted = true;
        return backgroundSnapshot;
    }


    public boolean shouldCaptureBackdrop() {
        return backdropWantedLastFrame;
    }


    public void rollBackdropDemand() {
        backdropWantedLastFrame = backdropWanted;
        backdropWanted = false;
    }

    public void releaseBackground() {
        if (backgroundSnapshot != null) {
            backgroundSnapshot.close();
            backgroundSnapshot = null;
        }
    }
}
