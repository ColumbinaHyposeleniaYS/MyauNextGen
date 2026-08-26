package laoqi123.ui;

import laoqi123.Myau;
import laoqi123.event.EventManager;
import laoqi123.event.EventTarget;
import laoqi123.event.impl.RenderSkiaEvent;
import laoqi123.module.Category;
import laoqi123.module.Module;
import laoqi123.module.modules.render.HUD;
import laoqi123.ui.font.FontManager;
import laoqi123.util.animation.Easing;
import laoqi123.util.animation.EasingAnimation;
import laoqi123.util.render.SkiaRenderUtil;
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
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;
import org.jetbrains.skija.*;
import org.lwjgl.glfw.GLFW;

import java.awt.Color;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Skia-rendered ClickGUI（从 Lyasim 的 NewClickGUIScreen 移植，适配 Myau 的模块 / 值系统）。
 * 支持模块设置项（Bool / Mode / Enum / Number / Color / Text / MultiChoice / Range）、
 * 窗口拖动、右栏滚轮滚动、内容裁剪。
 *
 * 关键点：Skia 的 canvas 只在 SkiaManager.render() 的 beginFrame()~endFrame() 之间有效，
 * 并通过 RenderSkiaEvent 广播。所以所有 Skia 绘制都在 {@link #onRenderSkia} 里完成，
 * 而 Screen 自身只负责接收鼠标/键盘输入。render 与 click 的坐标/高度推进必须完全一致，
 * 因此都走 {@link #drawValue}/{@link #clickValue}（render 时画、click 时判断命中），
 * 行高统一由 {@link #valueHeight} 给出。
 */
public class ClickGui extends Screen {

    // 窗口尺寸（所有坐标都从 posX/posY 推导）
    private static final float GUI_W = 560, GUI_H = 320;
    private static final float SIDEBAR_W = 4;   // 左栏宽
    private static final float HEADER_H = 40;    // 顶部标题栏高（同时是拖动手柄）
    private static final float CAT_H = 18;        // 每个分类条目高
    private static final float ROW_H = 20;        // 每个模块行高
    private static final float ROWG_H = 5;        // 模块行间距
    private static final float ROWSPA_H = 35;     // 模块基础高度
    private static final float VAL_H = 16;        // 每个设置项行高
    private static final float VAL_SPACING = 2;   // 设置项之间间距
    private static final float SB_H = 50;         // 取色盘饱和度/明度方块高
    private static final float HUE_H = 10;        // 取色盘色相条高
    private static final float NUM_H = 3;         // Number 滑条高
    private static final int FONT_SIZE = 8;      // Skia 字体大小
    private static final float SCROLL_SPEED = 18; // 每格滚轮滚动像素

    private Category selected = Category.COMBAT;           // 当前选中分类
    private final Set<ColorValue> expandedColors = new HashSet<>(); // 展开了取色盘的颜色项
    private final Set<MultiEnumChoiceValue<?>> expandedMultiBools = new HashSet<>(); // 展开了的 MultiChoice
    private final Map<ColorValue, float[]> colorHSB = new HashMap<>(); // 取色盘 HSB 状态
    private Value<?> draggingNumber;                       // 正在拖动的滑条
    private float draggingMin, draggingMax;                 // 滑条范围
    private boolean draggingIntegral;                       // 整数滑条
    private float draggingColX, draggingColW;               // 拖动滑条时所在的列
    private ColorValue draggingSBColor, draggingHueColor;  // 正在拖动的取色盘
    private float dragColorX, dragColorW, dragColorPickerY; // 拖动开始时记录的取色盘几何
    private TextValue focusedString;                        // 当前聚焦的文本输入框（null 表示无）

    private final EasingAnimation dbwAnim = new EasingAnimation(Easing.LINEAR, 150);
    private Category prevSelected; // 跟踪分类切换，用于重播 dbw 动画

    // 窗口位置（可拖动）
    private float posX, posY;
    private boolean initialized;
    private boolean draggingWindow;
    private float dragOffX, dragOffY;

    // 右栏滚动
    private float scroll;       // 当前滚动偏移（>=0，向下滚为正）
    private float maxScroll;    // 由内容高度算出的滚动上限
    private boolean draggingScrollbar;   // 正在拖动右侧滚动条
    private float scrollbarDragOffset;   // 拖动滚动条时鼠标相对滑块顶部的偏移

    private int mouseX, mouseY; // 给 Skia 渲染用的悬停坐标

    private Module bindingModule; // 中键进入绑定模式的模块（null 表示未在绑定）

    public ClickGui() {
        super(Text.literal("ClickGui"));
    }

    // ---------- 坐标工具 ----------

    private float listX() {
        return posX + SIDEBAR_W;
    }

    private float listW() {
        return GUI_W - SIDEBAR_W;
    }

    private float listTop() {
        return posY + HEADER_H * 2;
    }

    private List<Module> modulesInSelected() {
        return Myau.moduleManager.getModulesInCategory(selected).stream()
                .filter(m -> !m.isHidden())
                .toList();
    }

    private List<Value<?>> moduleValues(Module module) {
        List<Value<?>> values = Myau.valueManager.properties.get(module.getClass());
        return values == null ? Collections.emptyList() : values;
    }

    private boolean inside(double px, double py, float x, float y, float w, float h) {
        return px >= x && px <= x + w && py >= y && py <= y + h;
    }

    private int argb(int a, int r, int g, int b) {
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    /** 单个设置项的高度（颜色项展开时含取色盘）。render / click / contentHeight 三处都用它。 */
    private float valueHeight(Value<?> v) {
        float h = VAL_H + VAL_SPACING;
        if (v instanceof ColorValue cv && expandedColors.contains(cv)) h += SB_H + HUE_H;
        if (v instanceof FloatValue || v instanceof IntValue || v instanceof PercentValue) h += NUM_H;
        if (v instanceof MultiEnumChoiceValue<?> mv && expandedMultiBools.contains(mv))
            h += mv.getValues().length * (VAL_H + VAL_SPACING);
        return h;
    }

    /** 右栏全部内容（模块行 + 展开设置项）的总高度。 */
    private float contentHeight() {
        List<Module> all = modulesInSelected();
        int half = (all.size() + 1) / 2;
        float leftH = 0, rightH = 0;
        for (int i = 0; i < half; i++) leftH += moduleHeight(all.get(i));
        for (int i = half; i < all.size(); i++) rightH += moduleHeight(all.get(i));
        return Math.max(leftH, rightH);
    }

    /** 重新计算滚动上限并把 scroll 夹回合法范围（内容/分类变化后调用）。 */
    private void clampScroll() {
        float visible = GUI_H - HEADER_H * 2;
        maxScroll = Math.max(0, contentHeight() - visible);
        scroll = MathHelper.clamp(scroll, 0, maxScroll);
    }

    /** 滚动条几何（渲染与拖拽共用）：{barY, barH, trackH}；没有滚动条时返回 null。 */
    private float[] scrollbarGeom() {
        if (maxScroll <= 0) return null;
        float trackH = GUI_H - HEADER_H * 2;
        float barH = Math.max(20, trackH * (trackH / contentHeight()));
        float barY = listTop() + (trackH - barH) * (scroll / maxScroll);
        return new float[]{barY, barH, trackH};
    }

    /** 分类条布局：返回每个分类名字的起始 x（渲染与点击共用，保证命中区域一致）。 */
    private float[] categoryLayout() {
        Category[] cats = Category.values();
        FontManager fonts = FontManager.getInstance();
        float gap = 18;
        float total = (cats.length - 1) * gap;
        for (Category cat : cats) total += fonts.getMediumFont(9).getStringWidth(cat.getName());
        float x = posX + (GUI_W - total) / 2f;
        float[] xs = new float[cats.length];
        for (int i = 0; i < cats.length; i++) {
            xs[i] = x;
            x += fonts.getMediumFont(9).getStringWidth(cats[i].getName()) + gap;
        }
        return xs;
    }

    // ---------- 事件注册（Screen 生命周期） ----------

    @Override
    protected void init() {
        super.init();
        posX = (MinecraftClient.getInstance().getWindow().getScaledWidth() - GUI_W) / 2;
        posY = (MinecraftClient.getInstance().getWindow().getScaledHeight() - GUI_H) / 2;
        initialized = true;
        // 先注销再注册，保证幂等：EventManager.register 不去重，
        // 若上次 removed() 未可靠触发，重复注册会让 onRenderSkia 每帧多跑一遍，
        // 半透明背景被叠加多次 → 表现为框外闪烁。
        EventManager.unregister(this);
        EventManager.register(this);
    }

    @Override
    public void removed() {
        bindingModule = null;
        EventManager.unregister(this);
        super.removed();
    }

    // ---------- Skia 绘制（唯一画图的地方） ----------

    @EventTarget
    public void onRenderSkia(RenderSkiaEvent event) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.currentScreen != this) return;
        if (mc.player == null) return;

        Canvas canvas = event.getCanvas();
        HUD hud = (HUD) Myau.moduleManager.modules.get(HUD.class);
        int accent = hud != null ? hud.getColor(System.currentTimeMillis()).getRGB() : 0xFFFFFFFF;
        clampScroll(); // 内容可能因展开/折叠/切分类而变，渲染前先夹一次

        try (Paint paint = new Paint().setAntiAlias(true)) {
            // 整体背景（调亮过的 Myau 亮色调）
            paint.setColor(argb(150, 18, 18, 18));
            paint.setImageFilter(ImageFilter.makeDropShadow(0, 0, 10, 10, argb(70, 0, 0, 0)));
            canvas.drawRRect(RRect.makeXYWH(posX, posY, GUI_W, GUI_H, 8), paint);
            paint.setColor(argb(185, 24, 24, 24));
            paint.setImageFilter(null);
            canvas.save();

            canvas.clipRRect(RRect.makeXYWH(posX, posY, GUI_W, GUI_H, 8), ClipMode.INTERSECT, true);
            canvas.clipRect(Rect.makeXYWH(posX, posY, GUI_W, 40), ClipMode.INTERSECT, true);
            canvas.drawRRect(RRect.makeXYWH(posX, posY, GUI_W, GUI_H, 8), paint);

            // left information
            paint.setColor(accent);
            float logooffset = 15;
            canvas.drawCircle(posX + 17, posY + 20, 3, paint);
            FontManager fonts = FontManager.getInstance();
            fonts.getBoldFont((int) (FONT_SIZE * 1.3))
                    .drawShadowString(canvas, "yau", posX + 12 + fonts.getBoldFont((int) (FONT_SIZE * 1.3)).getStringWidth("M") + logooffset, posY + 11, 0xFFFFFFFF);
            fonts.getBoldFont((int) (FONT_SIZE * 1.3))
                    .drawShadowString(canvas, "M", posX + 12 + logooffset, posY + 11, accent);
            fonts.getMediumFont((int) ((FONT_SIZE) * 0.8))
                    .drawShadowString(canvas, Myau.version, posX + 12 + logooffset, posY + 23, 0xFFAAAAAA);

            // right information
            String playerName = mc.player.getName().getString();
            String userName = mc.getSession().getUsername();
            float namefontsize = 1.1F;
            fonts.getSemiboldFont((int) ((FONT_SIZE) * namefontsize))
                    .drawShadowString(canvas, playerName,
                            posX - fonts.getSemiboldFont((int) ((FONT_SIZE) * namefontsize)).getStringWidth(playerName) + GUI_W - 50,
                            posY + 12, 0xFFFFFFFF);
            fonts.getMediumFont((int) ((FONT_SIZE) * 0.8))
                    .drawShadowString(canvas, "(" + userName + ")",
                            posX - fonts.getSemiboldFont((int) ((FONT_SIZE) * namefontsize)).getStringWidth("(" + userName + ")") + GUI_W - 33,
                            posY + 23, 0xFFAAAAAA);
            SkiaRenderUtil.drawPlayerHeadSkia(canvas, mc.player, posX - 40 + GUI_W, posY, 40, 40, 1f);
            canvas.restore();

            // ---- 分类条（名字平铺，选中项带下划线动画）----
            Category[] cats = Category.values();
            float[] catXs = categoryLayout();
            float catY = posY + HEADER_H + 15;
            // 分类切换时重播 dbw 动画
            if (prevSelected != selected) {
                prevSelected = selected;
                dbwAnim.setValue(0);
                dbwAnim.reset();
            }
            for (int i = 0; i < cats.length; i++) {
                boolean active = cats[i] == selected;
                if (active) {
                    dbwAnim.run(fonts.getMediumFont(9).getStringWidth(cats[i].getName()));
                    float dbwa = (float) dbwAnim.getValue();
                    paint.setColor(accent);
                    canvas.drawRRect(RRect.makeXYWH(catXs[i], catY - 2 + 20, dbwa, 1, 3), paint);
                }
                fonts.getMediumFont(9)
                        .drawShadowString(canvas, cats[i].getName(), catXs[i], catY,
                                active ? accent : 0xFFAAAAAA);
            }

            // ---- 下栏：模块 + 展开设置项 ----
            // 闪屏只发生在下边界。所以：上边界用 clipRect 裁掉超出顶部的部分（保留半露、顺滑），
            // clipRect 的下边界故意放到屏幕外（不参与裁剪）；下边界改用 culling——
            // 会骑下边界的行整行不画，形状永不骑下裁剪边 → 不闪。
            float listX = listX(), listW = listW();
            float bandTop = listTop();
            float bandBottom = posY + GUI_H;

            canvas.save();
            // 用 clipRRect 直接裁剪圆角矩形区域，半径与 GUI 背景一致
            canvas.clipRRect(RRect.makeLTRB(posX, bandTop, posX + GUI_W, bandBottom, 8), ClipMode.INTERSECT, true);
            paint.setColor(argb(255, 255, 255, 255));

            // 两列布局
            float colW = (listW - 12 - 8) / 2f;
            float col1X = listX + 4;
            float col2X = col1X + colW + 8;

            List<Module> all = modulesInSelected();
            int half = (all.size() + 1) / 2;

            // 左列
            float rowY = listTop() - scroll;
            for (int i = 0; i < half; i++) {
                rowY = drawModule(canvas, paint, all.get(i), col1X, colW, rowY, bandBottom, accent);
                rowY += ROWSPA_H - ROW_H;
            }
            // 右列
            rowY = listTop() - scroll;
            for (int i = half; i < all.size(); i++) {
                rowY = drawModule(canvas, paint, all.get(i), col2X, colW, rowY, bandBottom, accent);
                rowY += ROWSPA_H - ROW_H;
            }

            // 滚动条（内容超出时才画）
            float[] sb = scrollbarGeom();
            if (sb != null) {
                paint.setColor(argb(120, 255, 255, 255));
                canvas.drawRRect(RRect.makeXYWH(posX + GUI_W - 4, sb[0], 2, sb[1], 1), paint);
            }

            canvas.restore();

            // 绑定模式提示：画在 GUI 最上层
            if (bindingModule != null) {
                String l1 = "Press a key to bind " + bindingModule.getName();
                String l2 = "(Press ESC to remove/cancel key bind)";
                var f1 = fonts.getFont(FONT_SIZE);
                var f2 = fonts.getFont(FONT_SIZE - 1); // 第二行字体小一点
                float w1 = f1.getStringWidth(l1);
                float w2 = f2.getStringWidth(l2);
                float boxW = Math.max(w1, w2) + 24;
                float boxH = 36;
                float bx = posX + (GUI_W - boxW) / 2;
                float by = posY + (GUI_H - boxH) / 2;
                paint.setColor(argb(170, 0, 0, 0));
                paint.setImageFilter(ImageFilter.makeDropShadow(0, 0, 8, 8, argb(90, 0, 0, 0)));
                canvas.drawRRect(RRect.makeXYWH(bx, by, boxW, boxH, 6), paint);
                paint.setImageFilter(null);
                f1.drawShadowString(canvas, l1, posX + GUI_W / 2 - w1 / 2, by + 8, 0xFFFFFFFF);
                f2.drawShadowString(canvas, l2, posX + GUI_W / 2 - w2 / 2, by + 21, 0xFFAAAAAA);
            }
        } catch (Exception ignored) {
        }
    }

    /** 单个模块的总高度（名称行 + 开关 + 所有可见设置项 + 间距）。 */
    private float moduleHeight(Module module) {
        float h = ROWSPA_H + VAL_H;
        for (Value<?> value : moduleValues(module)) {
            if (value.isVisible()) h += valueHeight(value);
        }
        return h;
    }

    /** 画单个模块（名称 + 开关 + 设置项），返回推进后的 rowY。 */
    private float drawModule(Canvas canvas, Paint paint, Module module, float colX, float colW, float rowY, float bandBottom, int accent) {
        float rowYY = rowY;
        // 模块名称行
        if (rowY + ROW_H <= bandBottom) {
            boolean hovered = inside(mouseX, mouseY, colX, rowY, colW, ROW_H);
        }
        rowY += ROW_H;

        // 展开背景
        float expandedH = ROW_H + VAL_H;
        for (Value<?> value : moduleValues(module)) {
            if (value.isVisible()) expandedH += valueHeight(value);
        }
        float bgTop = rowY - ROW_H;
        expandedH = Math.min(expandedH, bandBottom - bgTop);
        paint.setColor(argb(90, 20, 20, 20));
        paint.setImageFilter(ImageFilter.makeDropShadow(0, 0, 10, 10, argb(70, 0, 0, 0)));
        canvas.drawRRect(RRect.makeXYWH(colX, bgTop + ROWG_H / 2, colW, expandedH + ROWG_H / 2, 3), paint);
        paint.setImageFilter(null);
        canvas.save();
        canvas.clipRRect(RRect.makeXYWH(colX, rowYY, colW, ROW_H + ROWG_H, 3));
        paint.setColor(argb(80, 8, 8, 8));
        canvas.drawRRect(RRect.makeXYWH(colX, bgTop + ROWG_H / 2, colW, expandedH + ROWG_H / 2, 3), paint);
        canvas.restore();

        // 模块名（展开背景内）
        FontManager fonts = FontManager.getInstance();
        fonts.getBoldFont(FONT_SIZE)
                .drawShadowString(canvas, module.getName(), colX + 6, rowY - ROW_H + 5 + ROWG_H / 2,
                        module.isEnabled() ? accent : 0xFFCCCCCC);

        // 模块开关
        var font = fonts.getFont(FONT_SIZE);
        font.drawShadowString(canvas, "Enabled", colX + 8, rowY + 2 + ROWG_H,
                module.isEnabled() ? accent : 0xFFAAAAAA);
        paint.setColor(module.isEnabled() ? accent : argb(255, 90, 90, 90));
        canvas.drawRRect(RRect.makeXYWH(colX + colW - 16, rowY + 3 + ROWG_H, 8, 8, 2), paint);
        rowY += VAL_H;

        // 设置项
        for (Value<?> value : moduleValues(module)) {
            if (!value.isVisible()) continue;
            float vh = valueHeight(value);
            try {
                drawValue(canvas, paint, value, colX, rowY + ROWG_H, colW, accent);
            } catch (Exception ignored) {
            }
            rowY += vh;
        }
        return rowY;
    }

    /** 画单个设置项（坐标与 {@link #clickValue} 完全一致）。 */
    private void drawValue(Canvas canvas, Paint paint, Value<?> value, float x, float y, float w, int accent) {
        FontManager fonts = FontManager.getInstance();
        var font = fonts.getFont(FONT_SIZE);

        if (value instanceof BooleanValue bool) {
            font.drawShadowString(canvas, value.getName(), x + 12, y + 2,
                    bool.getValue() ? accent : 0xFFAAAAAA);
            // 开关点：圆角半径必须远小于半边长（8x8 用半径 2），避免 AA 路径在裁剪边界处闪烁。
            paint.setColor(bool.getValue() ? accent : argb(255, 90, 90, 90));
            canvas.drawRRect(RRect.makeXYWH(x + w - 22, y + 3, 8, 8, 2), paint);

        } else if (value instanceof ModeValue mode) {
            font.drawShadowString(canvas, value.getName(), x + 12, y + 2, 0xFFAAAAAA);
            String v = mode.getModeString();
            float vw = font.getStringWidth(v);
            font.drawShadowString(canvas, v, x + w - 16 - vw, y + 2, accent);

        } else if (value instanceof EnumChoiceValue<?> enumChoice) {
            font.drawShadowString(canvas, value.getName(), x + 12, y + 2, 0xFFAAAAAA);
            Object cur = enumChoice.getValue();
            String v = cur instanceof laoqi123.util.config.NamedChoice nc ? nc.getChoiceName() : "?";
            float vw = font.getStringWidth(v);
            font.drawShadowString(canvas, v, x + w - 16 - vw, y + 2, accent);

        } else if (value instanceof IntChoiceValue intChoice) {
            font.drawShadowString(canvas, value.getName(), x + 12, y + 2, 0xFFAAAAAA);
            laoqi123.util.config.Choice active = intChoice.getConfigurable().getActiveChoice();
            String v = active == null ? "?" : active.getChoiceName();
            float vw = font.getStringWidth(v);
            font.drawShadowString(canvas, v, x + w - 16 - vw, y + 2, accent);

        } else if (value instanceof FloatValue num) {
            drawSlider(canvas, paint, font, value, num.getValue(), num.getMinimum(), num.getMaximum(), x, y, w, accent, false);

        } else if (value instanceof IntValue num) {
            drawSlider(canvas, paint, font, value, num.getValue(), num.getMinimum(), num.getMaximum(), x, y, w, accent, true);

        } else if (value instanceof PercentValue num) {
            drawSlider(canvas, paint, font, value, num.getValue(), num.getMinimum(), num.getMaximum(), x, y, w, accent, true);

        } else if (value instanceof ColorValue color) {
            font.drawShadowString(canvas, value.getName(), x + 12, y + 2, 0xFFAAAAAA);
            // 当前颜色色块：圆角半径 2 严格小于半高 4，安全
            paint.setColor(color.getValue());
            canvas.drawRRect(RRect.makeXYWH(x + w - 26, y + 3, 14, 8, 2), paint);

            if (expandedColors.contains(color)) {
                float pickerX = x + 12, pickerW = w - 24;
                float pickerY = y + VAL_H;
                float[] hsb = hsbOf(color);

                // 饱和度/明度方块：横向 白→纯色相，叠加 纵向 透明→黑
                int pureHue = Color.getHSBColor(hsb[0], 1f, 1f).getRGB();
                drawHGradient(canvas, paint, pickerX, pickerY, pickerW, SB_H, 0xFFFFFFFF, pureHue);
                drawVGradient(canvas, paint, pickerX, pickerY, pickerW, SB_H, 0x00000000, 0xFF000000);
                // SB 选取点：3x3 太小，用直角
                float pX = pickerX + hsb[1] * pickerW;
                float pY = pickerY + (1f - hsb[2]) * SB_H;
                paint.setColor(0xFFFFFFFF);
                canvas.drawRRect(RRect.makeXYWH(pX - 1.5f, pY - 1.5f, 3, 3, 0), paint);

                // 色相条
                float hueY = pickerY + SB_H;
                drawHGradient(canvas, paint, pickerX, hueY, pickerW, HUE_H,
                        0xFFFF0000, 0xFFFFFF00, 0xFF00FF00, 0xFF00FFFF, 0xFF0000FF, 0xFFFF00FF, 0xFFFF0000);
                // 色相游标
                paint.setColor(0xFFFFFFFF);
                canvas.drawRRect(RRect.makeXYWH(pickerX + hsb[0] * pickerW - 1, hueY, 2, HUE_H, 0), paint);
            }

        } else if (value instanceof TextValue str) {
            boolean focused = focusedString == str;
            String text = str.getValue();
            float textX = x + 12;
            float availW = (w - 16) - 6 - 4; // 框宽 - 右内边距 - 左内边距余量

            // 手动截断：文字过长时丢弃开头字符、前置省略号，保证尾部（光标处）始终可见。
            String label = value.getName() + ": ";
            String display = label + text;
            if (font.getStringWidth(display) > availW) {
                while (display.length() > 1 && font.getStringWidth("…" + display.substring(1)) > availW) {
                    display = display.substring(1);
                }
                display = "…" + display.substring(1);
            }
            font.drawShadowString(canvas, display, textX, y + 2, focused ? 0xFFFFFFFF : 0xFFAAAAAA);

            // 聚焦时画闪烁光标，跟在已显示文字末尾
            if (focused && System.currentTimeMillis() % 1000 > 500) {
                float cursorX = textX + font.getStringWidth(display);
                paint.setColor(0xFFFFFFFF);
                canvas.drawRRect(RRect.makeXYWH(cursorX, y + 2, 1, VAL_H - 4, 0), paint);
            }

        } else if (value instanceof MultiEnumChoiceValue<?> multi) {
            boolean expanded = expandedMultiBools.contains(multi);
            font.drawShadowString(canvas, multi.getName() + (expanded ? " −" : " +"), x + 12, y + 2, 0xFFAAAAAA);
            if (expanded) {
                float cy = y + VAL_H;
                for (Object ev : multi.getValues()) {
                    String childName = ev instanceof laoqi123.util.config.NamedChoice nc ? nc.getChoiceName() : String.valueOf(ev);
                    boolean on = multi.getValue() != null && multi.getValue().contains(ev);
                    font.drawShadowString(canvas, childName, x + 22, cy + 2, on ? accent : 0xFFAAAAAA);
                    paint.setColor(on ? accent : argb(255, 90, 90, 90));
                    canvas.drawRRect(RRect.makeXYWH(x + w - 22, cy + 3, 8, 8, 2), paint);
                    cy += VAL_H + VAL_SPACING;
                }
            }

        } else if (value instanceof FloatRangeValue range) {
            font.drawShadowString(canvas, value.getName(), x + 12, y + 2, 0xFFAAAAAA);
            String v = String.format("%.1f..%.1f", range.getMin(), range.getMax());
            float vw = font.getStringWidth(v);
            font.drawShadowString(canvas, v, x + w - 16 - vw, y + 2, accent);

        } else if (value instanceof IntRangeValue range) {
            font.drawShadowString(canvas, value.getName(), x + 12, y + 2, 0xFFAAAAAA);
            String v = range.getMin() + ".." + range.getMax();
            float vw = font.getStringWidth(v);
            font.drawShadowString(canvas, v, x + w - 16 - vw, y + 2, accent);
        }
    }

    private void drawSlider(Canvas canvas, Paint paint, laoqi123.ui.font.base.SkiaFont font, Value<?> value,
                            Number cur, Number min, Number max, float x, float y, float w, int accent, boolean integral) {
        float percent = (cur.floatValue() - min.floatValue()) / (max.floatValue() - min.floatValue());
        // 滑条高度仅 2px，用小圆角
        paint.setColor(argb(120, 60, 60, 70));
        canvas.drawRRect(RRect.makeXYWH(x + 12, y + VAL_H - 2, w - 28, 2, 2), paint);
        paint.setColor(accent);
        canvas.drawRRect(RRect.makeXYWH(x + 12, y + VAL_H - 2, (w - 28) * percent, 2, 2), paint);
        String valStr;
        if (integral) {
            valStr = value instanceof PercentValue ? cur.intValue() + "%" : String.valueOf(cur.intValue());
        } else {
            valStr = String.format("%.2f", cur.floatValue()).replace(".00", "");
        }
        font.drawShadowString(canvas, value.getName() + ": " + valStr, x + 12, y + 1, 0xFFCCCCCC);
    }

    /** 取色盘的 HSB 状态（首次访问时从当前 RGB 反推）。 */
    private float[] hsbOf(ColorValue cv) {
        return colorHSB.computeIfAbsent(cv, v -> {
            int rgb = v.getValue();
            float[] hsb = Color.RGBtoHSB((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF, null);
            return hsb;
        });
    }

    private void applyHSB(ColorValue cv, float h, float s, float b) {
        float[] hsb = hsbOf(cv);
        hsb[0] = h;
        hsb[1] = s;
        hsb[2] = b;
        cv.setValue(Color.HSBtoRGB(h, s, b) | 0xFF000000);
    }

    /** 横向线性渐变填充。注意用完要清掉 shader，paint 是复用的。 */
    private void drawHGradient(Canvas canvas, Paint paint, float x, float y, float w, float h, int... colors) {
        try (Shader shader = Shader.makeLinearGradient(x, y, x + w, y, colors)) {
            paint.setShader(shader);
            canvas.drawRect(Rect.makeXYWH(x, y, w, h), paint);
        } finally {
            paint.setShader(null);
        }
    }

    /** 纵向线性渐变填充。 */
    private void drawVGradient(Canvas canvas, Paint paint, float x, float y, float w, float h, int... colors) {
        try (Shader shader = Shader.makeLinearGradient(x, y, x, y + h, colors)) {
            paint.setShader(shader);
            canvas.drawRect(Rect.makeXYWH(x, y, w, h), paint);
        } finally {
            paint.setShader(null);
        }
    }

    // ---------- 输入处理 ----------

    @Override
    public void render(DrawContext context, int mx, int my, float delta) {
        this.mouseX = mx;
        this.mouseY = my;

        // 拖动中的滑条：按当前鼠标 x 实时更新（坐标与 drawValue 的滑条一致）
        if (draggingNumber != null) {
            float trackX = draggingColX + 12;
            float trackW = draggingColW - 28;
            float percent = MathHelper.clamp((mx - trackX) / trackW, 0f, 1f);
            float val = draggingMin + (draggingMax - draggingMin) * percent;
            if (draggingIntegral) {
                draggingNumber.setValue(Math.round(val));
            } else {
                draggingNumber.setValue(val);
            }
        }

        // 拖动中的取色盘：几何在拖动开始时已记录
        if (draggingSBColor != null) {
            float s = MathHelper.clamp((mx - dragColorX) / dragColorW, 0f, 1f);
            float b = 1f - MathHelper.clamp((my - dragColorPickerY) / SB_H, 0f, 1f);
            float[] hsb = hsbOf(draggingSBColor);
            applyHSB(draggingSBColor, hsb[0], s, b);
        }
        if (draggingHueColor != null) {
            float h = MathHelper.clamp((mx - dragColorX) / dragColorW, 0f, 1f);
            float[] hsb = hsbOf(draggingHueColor);
            applyHSB(draggingHueColor, h, hsb[1], hsb[2]);
        }
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        // 顶部标题栏：开始拖动窗口
        if (inside(mx, my, posX, posY, GUI_W, HEADER_H)) {
            draggingWindow = true;
            dragOffX = (float) mx - posX;
            dragOffY = (float) my - posY;
            return true;
        }

        // 分类条（与渲染坐标同步）
        Category[] cats = Category.values();
        FontManager fonts = FontManager.getInstance();
        float[] catXs = categoryLayout();
        float clickY = posY + HEADER_H + 15;
        for (int i = 0; i < cats.length; i++) {
            float nameW = fonts.getMediumFont(9).getStringWidth(cats[i].getName());
            if (inside(mx, my, catXs[i] - 4, clickY - 2, nameW + 8, CAT_H + 4)) {
                selected = cats[i];
                scroll = 0;
                return true;
            }
        }

        // 右侧滚动条：点击滑块开始拖动；点轨道空白处先跳转再拖
        clampScroll();
        float[] sb = scrollbarGeom();
        if (sb != null && inside(mx, my, posX + GUI_W - 8, listTop(), 8, sb[2])) {
            float trackH = sb[2], barH = sb[1];
            if (my < sb[0] || my > sb[0] + barH) {
                // 点在轨道空白处：滑块中心跳到鼠标位置
                float t = (float) ((my - listTop() - barH / 2f) / (trackH - barH));
                scroll = MathHelper.clamp(t, 0f, 1f) * maxScroll;
            }
            draggingScrollbar = true;
            scrollbarDragOffset = (float) my - (listTop() + (trackH - barH) * (scroll / maxScroll));
            return true;
        }

        // 右栏：只处理可见区域内的点击
        float listX = listX(), listW = listW();
        // 两列布局（与渲染一致）
        float colW = (listW - 12 - 8) / 2f;
        float col1X = listX + 4;
        float col2X = col1X + colW + 8;
        List<Module> all = modulesInSelected();
        int half = (all.size() + 1) / 2;

        if (inside(mx, my, listX, listTop(), listW, GUI_H - HEADER_H * 2)) {
            // 左列
            float rowY = listTop() - scroll;
            for (int i = 0; i < half; i++) {
                if (clickModule(all.get(i), button, mx, my, col1X, colW, rowY)) return true;
                rowY += moduleHeight(all.get(i));
            }
            // 右列
            rowY = listTop() - scroll;
            for (int i = half; i < all.size(); i++) {
                if (clickModule(all.get(i), button, mx, my, col2X, colW, rowY)) return true;
                rowY += moduleHeight(all.get(i));
            }
            return true; // 点在右栏空白处也吃掉，避免穿透
        }

        return super.mouseClicked(mx, my, button);
    }

    /** 处理单个模块的点击（名称行 / 开关 / 设置项），与 {@link #drawModule} 坐标一致。返回 true 表示命中。 */
    private boolean clickModule(Module module, int button, double mx, double my, float colX, float colW, float rowY) {
        // 模块名称行：左键开关，中键进入按键绑定模式
        if (inside(mx, my, colX, rowY, colW, ROW_H)) {
            if (button == 0) module.toggle();
            else if (button == 2) bindingModule = module;
            return true;
        }
        rowY += ROW_H;

        // 模块开关
        if (inside(mx, my, colX + 8, rowY, colW - 16, VAL_H)) {
            if (button == 0) module.toggle();
            return true;
        }
        rowY += VAL_H;

        // 设置项
        for (Value<?> value : moduleValues(module)) {
            if (!value.isVisible()) continue;
            float vh = valueHeight(value);
            if (inside(mx, my, colX + 8, rowY, colW - 16, vh)) {
                clickValue(value, button, colX, rowY, colW, my);
                return true;
            }
            rowY += vh;
        }
        return false;
    }

    /** 处理单个设置项的点击（坐标与 {@link #drawValue} 完全一致）。 */
    private void clickValue(Value<?> value, int button, float x, float y, float w, double mouseY) {
        if (value instanceof BooleanValue bool) {
            if (button == 0) bool.setValue(!bool.getValue());

        } else if (value instanceof ModeValue mode) {
            if (button == 0) mode.nextMode();
            else mode.previousMode();

        } else if (value instanceof EnumChoiceValue<?> enumChoice) {
            cycleEnumChoice(enumChoice, button == 0);

        } else if (value instanceof IntChoiceValue intChoice) {
            int n = intChoice.getConfigurable().getChoices().size();
            int idx = intChoice.getConfigurable().getActiveIndex();
            int next = button == 0 ? (idx + 1) % n : (idx - 1 + n) % n;
            intChoice.setValue(next);

        } else if (value instanceof FloatValue num) {
            if (button == 0) {
                draggingNumber = num;
                draggingMin = num.getMinimum();
                draggingMax = num.getMaximum();
                draggingIntegral = false;
                draggingColX = x;
                draggingColW = w;
            }

        } else if (value instanceof IntValue num) {
            if (button == 0) {
                draggingNumber = num;
                draggingMin = num.getMinimum();
                draggingMax = num.getMaximum();
                draggingIntegral = true;
                draggingColX = x;
                draggingColW = w;
            }

        } else if (value instanceof PercentValue num) {
            if (button == 0) {
                draggingNumber = num;
                draggingMin = num.getMinimum();
                draggingMax = num.getMaximum();
                draggingIntegral = true;
                draggingColX = x;
                draggingColW = w;
            }

        } else if (value instanceof TextValue str) {
            // 左键聚焦该输入框，并取消其它聚焦
            if (button == 0) {
                clampScroll();
            }
            focusedString = str;

        } else if (value instanceof ColorValue color) {
            float pickerY = y + VAL_H;
            if (mouseY < pickerY) {
                // 点在颜色行头部：右键展开/收起取色盘
                if (button == 1) {
                    if (!expandedColors.remove(color)) {
                        expandedColors.add(color);
                        colorHSB.remove(color); // 重新展开时从当前颜色反推 HSB
                    }
                    clampScroll();
                }
            } else if (expandedColors.contains(color) && button == 0) {
                // 点在取色盘内：开始拖动 SB 或 色相
                float pickerX = x + 12, pickerW = w - 24;
                if (mouseY < pickerY + SB_H) {
                    draggingSBColor = color;
                } else {
                    draggingHueColor = color;
                }
                dragColorX = pickerX;
                dragColorW = pickerW;
                dragColorPickerY = pickerY;
            }

        } else if (value instanceof MultiEnumChoiceValue<?> multi) {
            if (button == 1) {
                // 右键：展开/收起
                if (!expandedMultiBools.remove(multi)) expandedMultiBools.add(multi);
                clampScroll();
            } else if (button == 0 && expandedMultiBools.contains(multi)) {
                // 左键：如果点在某个子项上则切换
                float cy = y + VAL_H;
                for (Object ev : multi.getValues()) {
                    if (mouseY >= cy && mouseY < cy + VAL_H + VAL_SPACING) {
                        toggleMultiChoice(multi, ev);
                        return;
                    }
                    cy += VAL_H + VAL_SPACING;
                }
            }
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void cycleEnumChoice(EnumChoiceValue raw, boolean forward) {
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
    private void toggleMultiChoice(MultiEnumChoiceValue raw, Object ev) {
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

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (draggingWindow) {
            posX = (float) mx - dragOffX;
            posY = (float) my - dragOffY;
            return true;
        }
        if (draggingScrollbar) {
            clampScroll();
            float[] sb = scrollbarGeom();
            if (sb != null) {
                float trackH = sb[2], barH = sb[1];
                float t = (float) ((my - scrollbarDragOffset - listTop()) / (trackH - barH));
                scroll = MathHelper.clamp(t, 0f, 1f) * maxScroll;
            }
            return true;
        }
        return super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        if (button == 0) {
            draggingNumber = null;
            draggingColX = 0;
            draggingColW = 0;
            draggingWindow = false;
            draggingScrollbar = false;
            draggingSBColor = null;
            draggingHueColor = null;
        }
        return super.mouseReleased(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double horizontal, double vertical) {
        if (inside(mx, my, listX(), listTop(), listW(), GUI_H - HEADER_H * 2)) {
            scroll = MathHelper.clamp(scroll - (float) vertical * SCROLL_SPEED, 0, maxScroll);
            return true;
        }
        return super.mouseScrolled(mx, my, horizontal, vertical);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // 绑定模式：任意键绑定到模块，Esc 清除绑定（同时退出绑定模式）
        if (bindingModule != null) {
            bindingModule.setKey(keyCode == GLFW.GLFW_KEY_ESCAPE ? 0 : keyCode);
            bindingModule = null;
            return true;
        }
        // 有聚焦的输入框时拦截按键：退格删字符，回车/Esc 取消聚焦（避免 Esc 直接关界面）
        if (focusedString != null) {
            if (keyCode == GLFW.GLFW_KEY_BACKSPACE) {
                String s = focusedString.getValue();
                if (s != null && !s.isEmpty()) focusedString.setValue(s.substring(0, s.length() - 1));
            } else if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_ESCAPE) {
                focusedString = null;
            }
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char chr, int modifiers) {
        if (focusedString != null && chr >= 32 && chr != 127) {
            focusedString.setValue(focusedString.getValue() + chr);
            return true;
        }
        return super.charTyped(chr, modifiers);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
