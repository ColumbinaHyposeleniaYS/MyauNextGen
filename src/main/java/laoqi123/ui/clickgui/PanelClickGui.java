package laoqi123.ui.clickgui;

import laoqi123.Myau;
import laoqi123.event.EventManager;
import laoqi123.event.EventTarget;
import laoqi123.event.impl.RenderSkiaEvent;
import laoqi123.module.Category;
import laoqi123.module.Module;
import laoqi123.module.modules.render.HUD;
import laoqi123.ui.font.FontManager;
import laoqi123.ui.font.base.SkiaFont;
import laoqi123.util.KeyBindUtil;
import laoqi123.util.config.Choice;
import laoqi123.util.config.NamedChoice;
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
import org.jetbrains.skija.Canvas;
import org.jetbrains.skija.ClipMode;
import org.jetbrains.skija.ImageFilter;
import org.jetbrains.skija.Paint;
import org.jetbrains.skija.RRect;
import org.jetbrains.skija.Rect;
import org.jetbrains.skija.Shader;
import org.lwjgl.glfw.GLFW;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 布局：左侧模块列表 + 右侧设置面板 + 顶部头像/分类 + 底部搜索框；
 * 交互：打开缩放动画、双栏独立滚动、Toast 提示、模块中键绑定、色相条取色。
 *
 * 关键点：Skia 的 canvas 只在 SkiaManager.render() 的 beginFrame()~endFrame() 之间有效，
 * 并通过 RenderSkiaEvent 广播。所有 Skia 绘制都在 {@link #onRenderSkia} 里完成，
 * Screen 自身只负责接收鼠标/键盘输入；render 与 click 的坐标/高度推进必须完全一致，
 * 统一由 {@link #renderValue} / {@link #clickSetting}（共享 {@link #valueHeight}）给出。
 */
public final class PanelClickGui extends Screen {
    private static final float PANEL_WIDTH = 600.0f;
    private static final float PANEL_HEIGHT = 400.0f;
    private static final float MODULE_X = 12.0f;
    private static final float CONTENT_Y = 43.0f;
    private static final float MODULE_WIDTH = 160.0f;
    private static final float SETTINGS_X = 183.0f;
    private static final float SETTINGS_WIDTH = 405.0f;
    private static final float CONTENT_HEIGHT = 340.0f;
    private static final float MODULE_ROW_HEIGHT = 22.0f;
    private static final int PANEL_COLOR = 0xF218181D;
    private static final int SURFACE_COLOR = 0x14FFFFFF;

    private Category selectedCategory = Category.COMBAT;
    private Module selectedModule;
    private final Set<MultiEnumChoiceValue<?>> expandedMulti = new HashSet<>();
    private final Map<ColorValue, float[]> colorHSB = new HashMap<>(); // 取色 HSB 状态缓存
    private float moduleScroll;
    private float settingsScroll;
    private Value<?> draggingNumber;                       // 正在拖动的数值滑条
    private float numberMin, numberMax;                    // 滑条范围
    private boolean numberIntegral;                        // 整数滑条
    private float numberTrackX, numberTrackWidth;          // 滑条轨道几何
    private Value<?> draggingRange;                        // 正在拖动的区间滑条（min 或 max 一端）
    private boolean draggingRangeMin, draggingRangeIntegral;
    private float rangeBoundMin, rangeBoundMax, rangeOther; // 区间边界与另一端当前值
    private float rangeTrackX, rangeTrackWidth;
    private ColorValue draggingHueColor;                   // 正在拖动的色相条
    private float hueBarX;
    private TextValue focusedString;                       // 当前聚焦的文本输入框
    private Module bindingModule;                          // 中键进入绑定模式的模块
    private boolean searchFocused;
    private String searchQuery = "";
    private float openProgress;
    private long lastFrame;
    private int mouseX;
    private int mouseY;
    private float originX;
    private float originY;
    private float uiScale = 1.0f;
    private String toast;
    private long toastCreated;
    private int accent = 0xFFFFFFFF; // HUD 强调色，每帧从 HUD 模块刷新

    public PanelClickGui() {
        super(Text.literal("Panel ClickGUI"));
    }

    @Override
    protected void init() {
        super.init();
        openProgress = 0.0f;
        lastFrame = System.nanoTime();
        moduleScroll = 0.0f;
        settingsScroll = 0.0f;
        searchFocused = false;
        focusedString = null;
        draggingNumber = null;
        draggingRange = null;
        draggingHueColor = null;
        if (selectedModule == null || !modulesForView().contains(selectedModule)) {
            selectedModule = modulesForView().stream().findFirst().orElse(null);
        }
        // 先注销再注册，保证幂等：EventManager.register 不去重，
        // 若上次 removed() 未可靠触发会重复注册，导致每帧多画一遍。
        EventManager.unregister(this);
        EventManager.register(this);
    }

    @Override
    public void removed() {
        bindingModule = null;
        EventManager.unregister(this);
        super.removed();
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        this.mouseX = mouseX;
        this.mouseY = mouseY;
        updateGeometry();
        if (draggingNumber != null) updateNumber(logicalX(mouseX));
        if (draggingRange != null) updateRange(logicalX(mouseX));
        if (draggingHueColor != null) updateHue(logicalX(mouseX));
    }

    @EventTarget
    public void onRenderSkia(RenderSkiaEvent event) {
        if (MinecraftClient.getInstance().currentScreen != this) return;
        HUD hud = (HUD) Myau.moduleManager.modules.get(HUD.class);
        accent = hud != null ? hud.getColor(System.currentTimeMillis()).getRGB() : 0xFFFFFFFF;

        long now = System.nanoTime();
        float delta = lastFrame == 0L ? 1.0f / 60.0f : Math.min(0.05f, (now - lastFrame) / 1_000_000_000.0f);
        lastFrame = now;
        openProgress = approach(openProgress, 1.0f, delta, 9.0f);
        updateGeometry();

        Canvas canvas = event.getCanvas();
        try (Paint paint = new Paint().setAntiAlias(true)) {
            paint.setColor(withAlpha(0x50000000, openProgress));
            canvas.drawRect(Rect.makeXYWH(0, 0, width, height), paint);
        }

        float eased = 1.0f - (float) Math.pow(1.0f - openProgress, 3.0f);
        float openingScale = 0.98f + 0.02f * eased;
        canvas.save();
        canvas.translate(originX, originY);
        canvas.scale(uiScale, uiScale);
        canvas.translate(PANEL_WIDTH / 2.0f, PANEL_HEIGHT / 2.0f);
        canvas.scale(openingScale, openingScale);
        canvas.translate(-PANEL_WIDTH / 2.0f, -PANEL_HEIGHT / 2.0f);
        renderPanel(canvas, logicalX(mouseX), logicalY(mouseY), eased);
        canvas.restore();
    }

    private void renderPanel(Canvas canvas, float mouseX, float mouseY, float alpha) {
        try (Paint paint = new Paint().setAntiAlias(true)) {
            paint.setColor(withAlpha(0xB0000000, alpha));
            paint.setImageFilter(ImageFilter.makeDropShadow(0, 3, 14, 14, withAlpha(0xB0000000, alpha)));
            canvas.drawRRect(RRect.makeXYWH(0, 0, PANEL_WIDTH, PANEL_HEIGHT, 12.0f), paint);
            paint.setImageFilter(null);
            paint.setColor(withAlpha(PANEL_COLOR, alpha));
            canvas.drawRRect(RRect.makeXYWH(0, 0, PANEL_WIDTH, PANEL_HEIGHT, 12.0f), paint);

            renderProfile(canvas, paint, alpha);
            renderCategories(canvas, paint, mouseX, mouseY, alpha);
            renderModuleList(canvas, paint, mouseX, mouseY, alpha);
            renderSettings(canvas, paint, mouseX, mouseY, alpha);
            renderSearch(canvas, paint, alpha);
            renderBinding(canvas, paint, alpha);
            renderToast(canvas, paint, alpha);
        }
    }

    private void renderProfile(Canvas canvas, Paint paint, float alpha) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player != null) {
            SkiaRenderUtil.drawPlayerHeadRoundedSkia(canvas, mc.player, 15.0f, 12.0f, 20.0f, 20.0f, alpha, 6.0f);
        }
        String name = mc.player == null ? mc.getSession().getUsername() : mc.player.getName().getString();
        text(canvas, name, 40.0f, 15.0f, 8, false, withAlpha(0xFFFFFFFF, alpha), true);
        float nameWidth = font(8, false).getStringWidth(name);
        paint.setColor(withAlpha(0xFF6C757D, alpha));
        canvas.drawRRect(RRect.makeXYWH(46.0f + nameWidth, 14.0f, 27.0f, 11.0f, 4.0f), paint);
        centered(canvas, "User", 59.5f + nameWidth, 17.0f, 6, true, withAlpha(0xFFFFFFFF, alpha));
    }

    private void renderCategories(Canvas canvas, Paint paint, float mouseX, float mouseY, float alpha) {
        Category[] categories = Category.values();
        float startX = 420.0f;
        for (int i = 0; i < categories.length; i++) {
            Category category = categories[i];
            float x = startX + i * 27.0f;
            boolean selected = category == selectedCategory && searchQuery.isEmpty();
            boolean hovered = inside(mouseX, mouseY, x - 3.0f, 11.0f, 23.0f, 22.0f);
            if (selected || hovered) {
                paint.setColor(withAlpha(0x18FFFFFF, alpha));
                canvas.drawRRect(RRect.makeXYWH(x - 3.0f, 11.0f, 23.0f, 22.0f, 5.0f), paint);
            }
            String label = category.getName().substring(0, 1).toUpperCase(Locale.ROOT);
            centered(canvas, label, x + 8.0f, 17.0f, 8, true,
                    withAlpha(selected ? 0xFFFFFFFF : hovered ? 0xFFCCCCCC : 0xFF888888, alpha));
        }
    }

    private void renderModuleList(Canvas canvas, Paint paint, float mouseX, float mouseY, float alpha) {
        paint.setColor(withAlpha(SURFACE_COLOR, alpha));
        canvas.drawRRect(RRect.makeXYWH(MODULE_X, CONTENT_Y, MODULE_WIDTH, CONTENT_HEIGHT, 4.0f), paint);
        String title = searchQuery.isEmpty() ? selectedCategory.getName() : "Search";
        List<Module> modules = modulesForView();
        text(canvas, title, MODULE_X + 10.0f, CONTENT_Y + 11.0f, 10, true, withAlpha(0xFFFFFFFF, alpha), false);
        String count = modules.size() + " modules";
        text(canvas, count, MODULE_X + MODULE_WIDTH - 10.0f - font(6, false).getStringWidth(count), CONTENT_Y + 14.0f,
                6, false, withAlpha(0xFFAAAAAA, alpha), false);

        float visibleHeight = CONTENT_HEIGHT - 38.0f;
        float contentHeight = modules.size() * MODULE_ROW_HEIGHT;
        moduleScroll = clamp(moduleScroll, 0.0f, Math.max(0.0f, contentHeight - visibleHeight));
        canvas.save();
        canvas.clipRect(Rect.makeXYWH(MODULE_X, CONTENT_Y + 30.0f, MODULE_WIDTH, visibleHeight), ClipMode.INTERSECT, true);
        float rowY = CONTENT_Y + 38.0f - moduleScroll;
        for (Module module : modules) {
            boolean hovered = inside(mouseX, mouseY, MODULE_X, rowY - 4.0f, MODULE_WIDTH, MODULE_ROW_HEIGHT);
            if (hovered || module == selectedModule) {
                paint.setColor(withAlpha(0x12FFFFFF, alpha));
                canvas.drawRRect(RRect.makeXYWH(MODULE_X + 4.0f, rowY - 4.0f, MODULE_WIDTH - 8.0f, MODULE_ROW_HEIGHT, 3.0f), paint);
            }
            int color = module.isEnabled() ? accent : hovered ? 0xFFCCCCCC : 0xFF888888;
            text(canvas, module.getName(), MODULE_X + 10.0f, rowY + 2.0f, 7, module.isEnabled(), withAlpha(color, alpha), module.isEnabled());
            if (module.getKey() > 0) {
                String key = KeyBindUtil.getKeyName(module.getKey());
                if (key != null && !key.isEmpty()) {
                    text(canvas, key.toUpperCase(Locale.ROOT), MODULE_X + MODULE_WIDTH - 10.0f - font(6, true).getStringWidth(key), rowY + 3.0f,
                            6, true, withAlpha(0xFF777777, alpha), false);
                }
            }
            rowY += MODULE_ROW_HEIGHT;
        }
        canvas.restore();
        drawScrollbar(canvas, paint, MODULE_X + MODULE_WIDTH - 5.0f, CONTENT_Y + 32.0f, visibleHeight, contentHeight, moduleScroll, alpha);
    }

    private void renderSettings(Canvas canvas, Paint paint, float mouseX, float mouseY, float alpha) {
        paint.setColor(withAlpha(SURFACE_COLOR, alpha));
        canvas.drawRRect(RRect.makeXYWH(SETTINGS_X, CONTENT_Y, SETTINGS_WIDTH, CONTENT_HEIGHT, 4.0f), paint);
        if (selectedModule == null) {
            centered(canvas, "Select a module", SETTINGS_X + SETTINGS_WIDTH / 2.0f, CONTENT_Y + 165.0f, 9, false,
                    withAlpha(0xFF777777, alpha));
            return;
        }

        text(canvas, selectedModule.getName(), SETTINGS_X + 10.0f, CONTENT_Y + 11.0f, 10, true,
                withAlpha(selectedModule.isEnabled() ? accent : 0xFFFFFFFF, alpha), selectedModule.isEnabled());
        drawToggle(canvas, paint, SETTINGS_X + SETTINGS_WIDTH - 39.0f, CONTENT_Y + 10.0f, selectedModule.isEnabled(), alpha);
        float contentHeight = settingsContentHeight();
        float visibleHeight = CONTENT_HEIGHT - 40.0f;
        settingsScroll = clamp(settingsScroll, 0.0f, Math.max(0.0f, contentHeight - visibleHeight));
        canvas.save();
        canvas.clipRect(Rect.makeXYWH(SETTINGS_X, CONTENT_Y + 31.0f, SETTINGS_WIDTH, visibleHeight), ClipMode.INTERSECT, true);
        float settingY = CONTENT_Y + 40.0f - settingsScroll;
        for (Value<?> value : moduleValues(selectedModule)) {
            if (!value.isVisible()) continue;
            renderValue(canvas, paint, value, settingY, mouseX, mouseY, alpha);
            settingY += valueHeight(value);
        }
        if (moduleValues(selectedModule).stream().noneMatch(Value::isVisible)) {
            text(canvas, "No configurable settings", SETTINGS_X + 12.0f, settingY + 5.0f, 7, false,
                    withAlpha(0xFF999999, alpha), false);
        }
        canvas.restore();
        drawScrollbar(canvas, paint, SETTINGS_X + SETTINGS_WIDTH - 5.0f, CONTENT_Y + 32.0f, visibleHeight, contentHeight, settingsScroll, alpha);
    }

    private void renderValue(Canvas canvas, Paint paint, Value<?> value, float y, float mouseX, float mouseY, float alpha) {
        float x = SETTINGS_X + 12.0f;
        float w = SETTINGS_WIDTH - 24.0f;
        text(canvas, value.getName(), x, y + 6.0f, 7, false, withAlpha(0xFFCCCCCC, alpha), false);

        if (value instanceof BooleanValue bool) {
            drawToggle(canvas, paint, x + w - 29.0f, y + 4.0f, bool.getValue(), alpha);
        } else if (value instanceof ModeValue mode) {
            float boxW = 112.0f;
            paint.setColor(withAlpha(0xFF24242A, alpha));
            canvas.drawRRect(RRect.makeXYWH(x + w - boxW, y + 1.0f, boxW, 23.0f, 4.0f), paint);
            centered(canvas, mode.getModeString(), x + w - boxW / 2.0f, y + 8.0f, 7, true, withAlpha(accent, alpha));
            text(canvas, ">", x + w - 12.0f, y + 8.0f, 7, true, withAlpha(0xFF888888, alpha), false);
        } else if (value instanceof EnumChoiceValue<?> enumChoice) {
            Object cur = enumChoice.getValue();
            String label = cur instanceof NamedChoice nc ? nc.getChoiceName() : String.valueOf(cur);
            renderChoiceBox(canvas, paint, label, x, w, y, alpha);
        } else if (value instanceof IntChoiceValue intChoice) {
            List<Choice> choices = intChoice.getConfigurable().getChoices();
            Choice active = choices.isEmpty() ? null : intChoice.getConfigurable().getActiveChoice();
            renderChoiceBox(canvas, paint, active == null ? "?" : active.getChoiceName(), x, w, y, alpha);
        } else if (value instanceof FloatValue num) {
            renderNumber(canvas, paint, x, w, y, alpha, num.getValue(), num.getMinimum(), num.getMaximum(), false, false);
        } else if (value instanceof IntValue num) {
            renderNumber(canvas, paint, x, w, y, alpha, num.getValue(), num.getMinimum(), num.getMaximum(), true, false);
        } else if (value instanceof PercentValue num) {
            renderNumber(canvas, paint, x, w, y, alpha, num.getValue(), num.getMinimum(), num.getMaximum(), true, true);
        } else if (value instanceof FloatRangeValue range) {
            renderRange(canvas, paint, x, w, y, alpha, range.getMin(), range.getMax(), range.getBoundMin(), range.getBoundMax(), false);
        } else if (value instanceof IntRangeValue range) {
            renderRange(canvas, paint, x, w, y, alpha, range.getMin(), range.getMax(), range.getBoundMin(), range.getBoundMax(), true);
        } else if (value instanceof MultiEnumChoiceValue<?> multi) {
            boolean expanded = expandedMulti.contains(multi);
            text(canvas, expanded ? "^" : "v", x + w - 10.0f, y + 6.0f, 7, true, withAlpha(0xFFAAAAAA, alpha), false);
            if (expanded) {
                float childY = y + 28.0f;
                for (Object ev : multi.getValues()) {
                    String childName = ev instanceof NamedChoice nc ? nc.getChoiceName() : String.valueOf(ev);
                    boolean on = multi.getValue() != null && multi.getValue().contains(ev);
                    text(canvas, childName, x + 8.0f, childY + 5.0f, 7, false, withAlpha(on ? accent : 0xFFAAAAAA, alpha), false);
                    drawToggle(canvas, paint, x + w - 29.0f, childY + 2.0f, on, alpha);
                    childY += 24.0f;
                }
            }
        } else if (value instanceof ColorValue color) {
            float barX = x + w - 150.0f;
            try (Shader shader = Shader.makeLinearGradient(barX, y + 4.0f, barX + 120.0f, y + 4.0f,
                    new int[]{0xFFFF0000, 0xFFFFFF00, 0xFF00FF00, 0xFF00FFFF, 0xFF0000FF, 0xFFFF00FF, 0xFFFF0000})) {
                paint.setShader(shader);
                canvas.drawRRect(RRect.makeXYWH(barX, y + 4.0f, 120.0f, 15.0f, 3.0f), paint);
                paint.setShader(null);
            }
            paint.setColor(color.getValue());
            canvas.drawRRect(RRect.makeXYWH(x + w - 22.0f, y + 3.0f, 22.0f, 17.0f, 4.0f), paint);
        } else if (value instanceof TextValue string) {
            float boxX = x + w - 155.0f;
            paint.setColor(withAlpha(focusedString == string ? 0xFF303038 : 0xFF24242A, alpha));
            canvas.drawRRect(RRect.makeXYWH(boxX, y + 1.0f, 155.0f, 23.0f, 4.0f), paint);
            String current = string.getValue();
            String display = current == null ? "" : current;
            while (!display.isEmpty() && font(7, false).getStringWidth(display) > 140.0f) display = display.substring(1);
            text(canvas, display, boxX + 7.0f, y + 8.0f, 7, false, withAlpha(0xFFFFFFFF, alpha), false);
        }
    }

    /** Mode / Enum / IntChoice 共用的右侧选择框样式。 */
    private void renderChoiceBox(Canvas canvas, Paint paint, String label, float x, float w, float y, float alpha) {
        float boxW = 112.0f;
        paint.setColor(withAlpha(0xFF24242A, alpha));
        canvas.drawRRect(RRect.makeXYWH(x + w - boxW, y + 1.0f, boxW, 23.0f, 4.0f), paint);
        centered(canvas, label, x + w - boxW / 2.0f, y + 8.0f, 7, true, withAlpha(accent, alpha));
        text(canvas, ">", x + w - 12.0f, y + 8.0f, 7, true, withAlpha(0xFF888888, alpha), false);
    }

    /** 数值滑条（FloatValue / IntValue / PercentValue）：数值 + 轨道 + 圆点。 */
    private void renderNumber(Canvas canvas, Paint paint, float x, float w, float y, float alpha,
                              Number cur, Number min, Number max, boolean integral, boolean percent) {
        String display = percent ? cur.intValue() + "%"
                : integral ? String.valueOf(cur.intValue())
                : String.format(Locale.US, "%.2f", cur.floatValue());
        text(canvas, display, x + w - font(7, true).getStringWidth(display), y + 5.0f, 7, true, withAlpha(accent, alpha), false);
        float trackY = y + 25.0f;
        float range = max.floatValue() - min.floatValue();
        float progress = range == 0 ? 0 : clamp((cur.floatValue() - min.floatValue()) / range, 0, 1);
        paint.setColor(withAlpha(0xFF3C3C42, alpha));
        canvas.drawRRect(RRect.makeXYWH(x, trackY, w, 4.0f, 2.0f), paint);
        paint.setColor(withAlpha(accent, alpha));
        canvas.drawRRect(RRect.makeXYWH(x, trackY, w * progress, 4.0f, 2.0f), paint);
        canvas.drawCircle(x + w * progress, trackY + 2.0f, 3.5f, paint);
    }

    /** 区间滑条（FloatRangeValue / IntRangeValue）：min..max 文本 + 双圆点轨道。 */
    private void renderRange(Canvas canvas, Paint paint, float x, float w, float y, float alpha,
                             float min, float max, float boundMin, float boundMax, boolean integral) {
        String display = integral
                ? String.format(Locale.US, "%d..%d", (int) min, (int) max)
                : String.format(Locale.US, "%.1f..%.1f", min, max);
        text(canvas, display, x + w - font(7, true).getStringWidth(display), y + 5.0f, 7, true, withAlpha(accent, alpha), false);
        float trackY = y + 25.0f;
        float span = boundMax - boundMin;
        float minP = span == 0 ? 0 : clamp((min - boundMin) / span, 0, 1);
        float maxP = span == 0 ? 0 : clamp((max - boundMin) / span, 0, 1);
        paint.setColor(withAlpha(0xFF3C3C42, alpha));
        canvas.drawRRect(RRect.makeXYWH(x, trackY, w, 4.0f, 2.0f), paint);
        paint.setColor(withAlpha(accent, alpha));
        canvas.drawRRect(RRect.makeXYWH(x + w * minP, trackY, w * (maxP - minP), 4.0f, 2.0f), paint);
        canvas.drawCircle(x + w * minP, trackY + 2.0f, 3.5f, paint);
        canvas.drawCircle(x + w * maxP, trackY + 2.0f, 3.5f, paint);
    }

    private void renderSearch(Canvas canvas, Paint paint, float alpha) {
        float x = 200.0f;
        float y = PANEL_HEIGHT + 15.0f;
        paint.setColor(withAlpha(searchFocused ? 0xEE24242A : 0xDD1A1A1F, alpha));
        paint.setImageFilter(ImageFilter.makeDropShadow(0, 2, 8, 8, withAlpha(0x70000000, alpha)));
        canvas.drawRRect(RRect.makeXYWH(x, y, 200.0f, 20.0f, 6.0f), paint);
        paint.setImageFilter(null);
        String display = searchQuery.isEmpty() && !searchFocused ? "Search modules" : searchQuery;
        text(canvas, display, x + 10.0f, y + 7.0f, 7, false,
                withAlpha(searchQuery.isEmpty() ? 0xFF777777 : 0xFFFFFFFF, alpha), false);
        if (searchFocused && System.currentTimeMillis() % 1000L > 500L) {
            float cursorX = x + 10.0f + font(7, false).getStringWidth(searchQuery);
            paint.setColor(withAlpha(0xFFFFFFFF, alpha));
            canvas.drawRect(Rect.makeXYWH(cursorX, y + 5.0f, 1.0f, 10.0f), paint);
        }
    }

    private void renderBinding(Canvas canvas, Paint paint, float alpha) {
        if (bindingModule == null) return;
        String l1 = "Press a key to bind " + bindingModule.getName();
        String l2 = "(Press ESC to remove/cancel key bind)";
        SkiaFont f1 = font(8, true);
        SkiaFont f2 = font(7, false);
        float w1 = f1.getStringWidth(l1);
        float w2 = f2.getStringWidth(l2);
        float boxW = Math.max(w1, w2) + 24.0f;
        float boxH = 36.0f;
        float bx = (PANEL_WIDTH - boxW) / 2.0f;
        float by = (PANEL_HEIGHT - boxH) / 2.0f;
        paint.setColor(withAlpha(0xAA000000, alpha));
        paint.setImageFilter(ImageFilter.makeDropShadow(0, 2, 8, 8, withAlpha(0x60000000, alpha)));
        canvas.drawRRect(RRect.makeXYWH(bx, by, boxW, boxH, 6.0f), paint);
        paint.setImageFilter(null);
        f1.drawShadowString(canvas, l1, PANEL_WIDTH / 2.0f - w1 / 2.0f, by + 8.0f, withAlpha(0xFFFFFFFF, alpha));
        f2.drawShadowString(canvas, l2, PANEL_WIDTH / 2.0f - w2 / 2.0f, by + 21.0f, withAlpha(0xFFAAAAAA, alpha));
    }

    private void renderToast(Canvas canvas, Paint paint, float alpha) {
        if (toast == null) return;
        long age = System.currentTimeMillis() - toastCreated;
        if (age > 1800L) {
            toast = null;
            return;
        }
        float toastAlpha = age < 200L ? age / 200.0f : age > 1500L ? (1800L - age) / 300.0f : 1.0f;
        float toastWidth = font(7, false).getStringWidth(toast) + 18.0f;
        paint.setColor(withAlpha(0xF026262C, alpha * toastAlpha));
        canvas.drawRRect(RRect.makeXYWH(PANEL_WIDTH - toastWidth - 12.0f, PANEL_HEIGHT - 28.0f, toastWidth, 18.0f, 5.0f), paint);
        text(canvas, toast, PANEL_WIDTH - toastWidth - 3.0f, PANEL_HEIGHT - 22.0f, 7, false,
                withAlpha(0xFFFFFFFF, alpha * toastAlpha), false);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        float mx = logicalX(mouseX);
        float my = logicalY(mouseY);
        if (inside(mx, my, 200.0f, PANEL_HEIGHT + 15.0f, 200.0f, 20.0f)) {
            searchFocused = true;
            focusedString = null;
            return true;
        }
        searchFocused = false;

        for (int i = 0; i < Category.values().length; i++) {
            if (inside(mx, my, 417.0f + i * 27.0f, 11.0f, 23.0f, 22.0f)) {
                selectedCategory = Category.values()[i];
                searchQuery = "";
                moduleScroll = 0;
                selectedModule = modulesForView().stream().findFirst().orElse(null);
                settingsScroll = 0;
                return true;
            }
        }

        if (inside(mx, my, MODULE_X, CONTENT_Y + 30.0f, MODULE_WIDTH, CONTENT_HEIGHT - 30.0f)) {
            float rowY = CONTENT_Y + 38.0f - moduleScroll;
            for (Module module : modulesForView()) {
                if (inside(mx, my, MODULE_X, rowY - 4.0f, MODULE_WIDTH, MODULE_ROW_HEIGHT)) {
                    if (button == 0) {
                        module.toggle();
                        showToast(module.getName() + (module.isEnabled() ? " enabled" : " disabled"));
                    } else if (button == 1) {
                        selectedModule = module;
                        settingsScroll = 0;
                        focusedString = null;
                    } else if (button == 2) {
                        bindingModule = module;
                    }
                    return true;
                }
                rowY += MODULE_ROW_HEIGHT;
            }
            return true;
        }

        if (selectedModule != null && inside(mx, my, SETTINGS_X, CONTENT_Y, SETTINGS_WIDTH, CONTENT_HEIGHT)) {
            if (inside(mx, my, SETTINGS_X + SETTINGS_WIDTH - 42.0f, CONTENT_Y + 5.0f, 34.0f, 24.0f)) {
                selectedModule.toggle();
                showToast(selectedModule.getName() + (selectedModule.isEnabled() ? " enabled" : " disabled"));
                return true;
            }
            clickSetting(mx, my, button);
            return true;
        }
        focusedString = null;
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private void clickSetting(float mouseX, float mouseY, int button) {
        float y = CONTENT_Y + 40.0f - settingsScroll;
        float x = SETTINGS_X + 12.0f;
        float w = SETTINGS_WIDTH - 24.0f;
        for (Value<?> value : moduleValues(selectedModule)) {
            if (!value.isVisible()) continue;
            float h = valueHeight(value);
            if (inside(mouseX, mouseY, SETTINGS_X, y, SETTINGS_WIDTH, h)) {
                if (value instanceof BooleanValue bool && button == 0) {
                    bool.setValue(!bool.getValue());
                } else if (value instanceof ModeValue mode) {
                    if (mode.getModes().length == 0) return;
                    if (button == 1) mode.previousMode();
                    else mode.nextMode();
                } else if (value instanceof EnumChoiceValue<?> enumChoice) {
                    cycleEnumChoice(enumChoice, button == 0);
                } else if (value instanceof IntChoiceValue intChoice) {
                    int n = intChoice.getConfigurable().getChoices().size();
                    if (n > 0) {
                        int idx = intChoice.getConfigurable().getActiveIndex();
                        int next = button == 1 ? (idx - 1 + n) % n : (idx + 1) % n;
                        intChoice.setValue(next);
                    }
                } else if (value instanceof FloatValue num && button == 0 && mouseY >= y + 18.0f) {
                    startNumberDrag(num, num.getMinimum(), num.getMaximum(), x, w, mouseX, false);
                } else if (value instanceof IntValue num && button == 0 && mouseY >= y + 18.0f) {
                    startNumberDrag(num, num.getMinimum(), num.getMaximum(), x, w, mouseX, true);
                } else if (value instanceof PercentValue num && button == 0 && mouseY >= y + 18.0f) {
                    startNumberDrag(num, num.getMinimum(), num.getMaximum(), x, w, mouseX, true);
                } else if (value instanceof FloatRangeValue range && button == 0 && mouseY >= y + 18.0f) {
                    startRangeDrag(range, range.getMin(), range.getMax(), range.getBoundMin(), range.getBoundMax(), x, w, mouseX, false);
                } else if (value instanceof IntRangeValue range && button == 0 && mouseY >= y + 18.0f) {
                    startRangeDrag(range, range.getMin(), range.getMax(), range.getBoundMin(), range.getBoundMax(), x, w, mouseX, true);
                } else if (value instanceof MultiEnumChoiceValue<?> multi) {
                    if (mouseY < y + 28.0f) {
                        if (!expandedMulti.remove(multi)) expandedMulti.add(multi);
                    } else if (expandedMulti.contains(multi)) {
                        Object[] values = multi.getValues();
                        int child = (int) ((mouseY - y - 28.0f) / 24.0f);
                        if (child >= 0 && child < values.length) toggleMultiChoice(multi, values[child]);
                    }
                } else if (value instanceof ColorValue color && button == 0) {
                    float barX = x + w - 150.0f;
                    if (inside(mouseX, mouseY, barX, y + 1.0f, 120.0f, 22.0f)) {
                        draggingHueColor = color;
                        hueBarX = barX;
                        updateHue(mouseX);
                    }
                } else if (value instanceof TextValue string && button == 0) {
                    focusedString = string;
                    searchFocused = false;
                }
                return;
            }
            y += h;
        }
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
        if (button == 0) {
            boolean dragging = draggingNumber != null || draggingRange != null || draggingHueColor != null;
            if (draggingNumber != null) updateNumber(logicalX(mouseX));
            if (draggingRange != null) updateRange(logicalX(mouseX));
            if (draggingHueColor != null) updateHue(logicalX(mouseX));
            if (dragging) return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0) {
            draggingNumber = null;
            draggingRange = null;
            draggingHueColor = null;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        float mx = logicalX(mouseX);
        float my = logicalY(mouseY);
        if (inside(mx, my, MODULE_X, CONTENT_Y, MODULE_WIDTH, CONTENT_HEIGHT)) {
            moduleScroll -= (float) verticalAmount * 18.0f;
            return true;
        }
        if (inside(mx, my, SETTINGS_X, CONTENT_Y, SETTINGS_WIDTH, CONTENT_HEIGHT)) {
            settingsScroll -= (float) verticalAmount * 18.0f;
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // 绑定模式：任意键绑定到模块，Esc 清除绑定（同时退出绑定模式）
        if (bindingModule != null) {
            bindingModule.setKey(keyCode == GLFW.GLFW_KEY_ESCAPE ? 0 : keyCode);
            bindingModule = null;
            return true;
        }
        if (searchFocused) {
            if (keyCode == GLFW.GLFW_KEY_BACKSPACE && !searchQuery.isEmpty()) searchQuery = searchQuery.substring(0, searchQuery.length() - 1);
            else if (keyCode == GLFW.GLFW_KEY_ESCAPE || keyCode == GLFW.GLFW_KEY_ENTER) searchFocused = false;
            refreshSearchSelection();
            return true;
        }
        if (focusedString != null) {
            String s = focusedString.getValue();
            if (keyCode == GLFW.GLFW_KEY_BACKSPACE && s != null && !s.isEmpty()) {
                focusedString.setValue(s.substring(0, s.length() - 1));
            } else if (keyCode == GLFW.GLFW_KEY_ESCAPE || keyCode == GLFW.GLFW_KEY_ENTER) {
                focusedString = null;
            }
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char chr, int modifiers) {
        if (chr >= 32 && chr != 127) {
            if (searchFocused) {
                searchQuery += chr;
                refreshSearchSelection();
                return true;
            }
            if (focusedString != null) {
                String s = focusedString.getValue();
                focusedString.setValue((s == null ? "" : s) + chr);
                return true;
            }
        }
        return super.charTyped(chr, modifiers);
    }

    private void refreshSearchSelection() {
        moduleScroll = 0;
        if (selectedModule == null || !modulesForView().contains(selectedModule)) selectedModule = modulesForView().stream().findFirst().orElse(null);
    }

    // ---------- 拖拽更新 ----------

    private void startNumberDrag(Value<?> value, Number min, Number max, float x, float w, float mouseX, boolean integral) {
        draggingNumber = value;
        numberMin = min.floatValue();
        numberMax = max.floatValue();
        numberIntegral = integral;
        numberTrackX = x;
        numberTrackWidth = w;
        updateNumber(mouseX);
    }

    private void updateNumber(float mouseX) {
        if (draggingNumber == null) return;
        float ratio = clamp((mouseX - numberTrackX) / numberTrackWidth, 0, 1);
        float raw = numberMin + (numberMax - numberMin) * ratio;
        if (numberIntegral) draggingNumber.setValue(Math.round(raw));
        else draggingNumber.setValue(raw);
    }

    private void startRangeDrag(Value<?> value, float min, float max, float boundMin, float boundMax,
                                float x, float w, float mouseX, boolean integral) {
        draggingRange = value;
        draggingRangeIntegral = integral;
        rangeBoundMin = boundMin;
        rangeBoundMax = boundMax;
        rangeTrackX = x;
        rangeTrackWidth = w;
        float span = boundMax - boundMin;
        float minP = span == 0 ? 0 : clamp((min - boundMin) / span, 0, 1);
        float maxP = span == 0 ? 0 : clamp((max - boundMin) / span, 0, 1);
        // 点在选中区间中点左侧拖 min 端，右侧拖 max 端
        draggingRangeMin = mouseX <= x + w * (minP + maxP) / 2.0f;
        rangeOther = draggingRangeMin ? max : min;
        updateRange(mouseX);
    }

    private void updateRange(float mouseX) {
        if (draggingRange == null) return;
        float ratio = clamp((mouseX - rangeTrackX) / rangeTrackWidth, 0, 1);
        float raw = rangeBoundMin + (rangeBoundMax - rangeBoundMin) * ratio;
        if (draggingRangeIntegral) raw = Math.round(raw);
        if (draggingRangeMin) {
            float newMin = Math.min(raw, rangeOther);
            applyRangeValue(newMin, rangeOther);
        } else {
            float newMax = Math.max(raw, rangeOther);
            applyRangeValue(rangeOther, newMax);
        }
    }

    private void applyRangeValue(float min, float max) {
        if (draggingRange instanceof FloatRangeValue frv) {
            frv.setValue(new float[]{min, max});
        } else if (draggingRange instanceof IntRangeValue irv) {
            irv.setValue(new int[]{(int) min, (int) max});
        }
    }

    private void updateHue(float mouseX) {
        if (draggingHueColor == null) return;
        float hue = clamp((mouseX - hueBarX) / 120.0f, 0, 1);
        float[] hsb = hsbOf(draggingHueColor);
        applyHSB(draggingHueColor, hue, hsb[1], hsb[2]);
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
    private void cycleEnumChoice(EnumChoiceValue raw, boolean forward) {
        Object[] vals = raw.getValues();
        if (vals == null || vals.length == 0) return;
        Object cur = raw.getValue();
        int idx = 0;
        for (int i = 0; i < vals.length; i++) {
            if (vals[i] == cur) {
                idx = i;
                break;
            }
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

    // ---------- 数据获取 ----------

    private List<Module> modulesForView() {
        List<Module> result = new ArrayList<>();
        String query = searchQuery.toLowerCase(Locale.ROOT);
        for (Module module : Myau.moduleManager.modules.values()) {
            if (module.isHidden()) continue;
            if (query.isEmpty() ? module.getCategory() == selectedCategory : module.getName().toLowerCase(Locale.ROOT).contains(query)) result.add(module);
        }
        return result;
    }

    private List<Value<?>> moduleValues(Module module) {
        List<Value<?>> values = Myau.valueManager.properties.get(module.getClass());
        return values == null ? Collections.emptyList() : values;
    }

    private float settingsContentHeight() {
        if (selectedModule == null) return 0;
        float height = 0;
        for (Value<?> value : moduleValues(selectedModule)) if (value.isVisible()) height += valueHeight(value);
        return height;
    }

    /** 单个设置项高度（render / click / contentHeight 三处共用）。 */
    private float valueHeight(Value<?> value) {
        if (value instanceof FloatValue || value instanceof IntValue || value instanceof PercentValue
                || value instanceof FloatRangeValue || value instanceof IntRangeValue) return 38.0f;
        if (value instanceof MultiEnumChoiceValue<?> multi) return 30.0f + (expandedMulti.contains(multi) ? multi.getValues().length * 24.0f : 0.0f);
        return 30.0f;
    }

    // ---------- 绘制小件 ----------

    private void drawToggle(Canvas canvas, Paint paint, float x, float y, boolean enabled, float alpha) {
        paint.setColor(withAlpha(enabled ? accent : 0xFF4B4B52, alpha));
        canvas.drawRRect(RRect.makeXYWH(x, y, 24.0f, 12.0f, 6.0f), paint);
        paint.setColor(withAlpha(0xFFFFFFFF, alpha));
        canvas.drawCircle(x + (enabled ? 18.0f : 6.0f), y + 6.0f, 4.0f, paint);
    }

    private void drawScrollbar(Canvas canvas, Paint paint, float x, float y, float visible, float content, float scroll, float alpha) {
        if (content <= visible || content <= 0) return;
        float thumb = Math.max(20.0f, visible * visible / content);
        float max = content - visible;
        float thumbY = y + (visible - thumb) * (scroll / max);
        paint.setColor(withAlpha(0x70FFFFFF, alpha));
        canvas.drawRRect(RRect.makeXYWH(x, thumbY, 3.0f, thumb, 1.5f), paint);
    }

    private void showToast(String message) {
        toast = message;
        toastCreated = System.currentTimeMillis();
    }

    // ---------- 几何 / 工具 ----------

    private void updateGeometry() {
        float maxWidth = Math.max(0.5f, (width - 24.0f) / PANEL_WIDTH);
        float maxHeight = Math.max(0.5f, (height - 55.0f) / (PANEL_HEIGHT + 35.0f));
        uiScale = Math.min(1.0f, Math.min(maxWidth, maxHeight));
        originX = (width - PANEL_WIDTH * uiScale) / 2.0f;
        originY = (height - (PANEL_HEIGHT + 35.0f) * uiScale) / 2.0f;
    }

    private float logicalX(double screenX) { return ((float) screenX - originX) / uiScale; }
    private float logicalY(double screenY) { return ((float) screenY - originY) / uiScale; }
    private static boolean inside(float px, float py, float x, float y, float w, float h) { return px >= x && px <= x + w && py >= y && py <= y + h; }
    private static float clamp(float value, float min, float max) { return Math.max(min, Math.min(max, value)); }
    private static float approach(float value, float target, float delta, float speed) { return target + (value - target) * (float) Math.exp(-speed * delta); }
    private static int withAlpha(int color, float alpha) { return (color & 0xFFFFFF) | (Math.round(((color >>> 24) & 0xFF) * clamp(alpha, 0, 1)) << 24); }

    private static SkiaFont font(int size, boolean bold) {
        FontManager fonts = FontManager.getInstance();
        return bold ? fonts.getBoldFont(size) : fonts.getFont(size);
    }

    private static void text(Canvas canvas, String text, float x, float y, int size, boolean bold, int color, boolean shadow) {
        if (text == null || text.isEmpty()) return;
        if (shadow) font(size, bold).drawShadowString(canvas, text, x, y, color);
        else font(size, bold).drawString(canvas, text, x, y, color);
    }

    private static void centered(Canvas canvas, String text, float x, float y, int size, boolean bold, int color) {
        text(canvas, text, x - font(size, bold).getStringWidth(text) / 2.0f, y, size, bold, color, false);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
