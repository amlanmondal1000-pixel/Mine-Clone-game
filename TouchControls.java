package com.mygame;

import com.jme3.app.SimpleApplication;
import com.jme3.font.BitmapFont;
import com.jme3.font.BitmapText;
import com.jme3.input.InputManager;
import com.jme3.input.KeyInput;
import com.jme3.input.RawInputListener;
import com.jme3.input.controls.ActionListener;
import com.jme3.input.controls.KeyTrigger;
import com.jme3.input.event.JoyAxisEvent;
import com.jme3.input.event.JoyButtonEvent;
import com.jme3.input.event.KeyInputEvent;
import com.jme3.input.event.MouseButtonEvent;
import com.jme3.input.event.MouseMotionEvent;
import com.jme3.input.event.TouchEvent;
import com.jme3.material.Material;
import com.jme3.material.RenderState;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Vector2f;
import com.jme3.scene.Geometry;
import com.jme3.scene.Node;
import com.jme3.scene.Spatial;
import com.jme3.scene.shape.Quad;
import com.jme3.system.JmeSystem;

import java.util.ArrayList;
import java.util.List;
import java.util.prefs.Preferences;

/**
 * Optional, customizable touch overlay. Does nothing on PC unless turned on.
 *
 * Android : enabled automatically.
 * PC      : disabled by default, keyboard + mouse behave exactly as before.
 *
 * CUSTOMIZING (works with mouse on PC and with fingers on a phone):
 *   Pause menu -> "Touch Controls" opens the layout editor.
 *   - Drag any control to move it.
 *   - Tap a control to select it (yellow), then SIZE- / SIZE+ to resize it.
 *     With nothing selected, SIZE- / SIZE+ resize every control.
 *   - ALPHA- / ALPHA+ change transparency of all controls.
 *   - MIRROR flips the layout for left-handed play.
 *   - RESET restores the defaults. DONE (or ESC) saves and returns.
 *   Layout is stored as screen fractions, so it adapts to any resolution.
 *
 * DEV KEYS (only when DEV_TOOLS is true):
 *   F4 - show / hide the touch overlay
 *   F5 - unlock the mouse and use the left button as a "finger"
 */
public class TouchControls implements ActionListener, RawInputListener {

    public static final boolean DEV_TOOLS = true;

    public static final String TOGGLE_TOUCH = "ToggleTouchControls";
    public static final String TOGGLE_MOUSE_UNLOCK = "DevToggleMouseUnlock";

    private static final int MOUSE_POINTER = -100;
    private static final float REPEAT_DELAY = 0.25f;

    private static final float DEFAULT_OPACITY = 0.28f;
    private static final float MIN_OPACITY = 0.10f;
    private static final float MAX_OPACITY = 0.80f;
    private static final float MIN_SCALE = 0.5f;
    private static final float MAX_SCALE = 2.0f;
    private static final float SCALE_STEP = 0.1f;
    private static final float OPACITY_STEP = 0.05f;

    private final SimpleApplication app;
    private final InputManager inputManager;
    private final Node guiNode;
    private final Node root = new Node("TouchControls");
    private final Node editNode = new Node("TouchEditor");
    private final BitmapFont font;
    private final boolean android;
    private Preferences prefs;

    private boolean enabled;
    private boolean mouseUnlocked;
    private boolean playing;

    // ---- elements (joystick + buttons) ----
    private final List<Element> elements = new ArrayList<>();
    private Element stick, jumpPad, breakPad, placePad, pausePad, prevPad, nextPad;
    private Geometry stickKnob;
    private BitmapText devLabel;

    private int lookPointer = -1;
    private float lastLookX, lastLookY;

    private final Vector2f movement = new Vector2f();
    private float lookX, lookY;
    private boolean breakPending, placePending, pausePending;
    private int hotbarDelta;

    // ---- layout ----
    private int screenW = 1280, screenH = 720;
    private float uiScale = 1f;
    private float opacity = DEFAULT_OPACITY;

    // ---- editor ----
    private boolean editMode;
    private Runnable onEditDone;
    private Element selected;
    private int dragPointer = -1;
    private float dragOffX, dragOffY;
    private final List<Tool> tools = new ArrayList<>();
    private Tool activeTool;
    private int toolPointer = -1;
    private BitmapText editHint;
    private long editIgnoreUntil;

    private static class Element {
        final String name;
        final float baseSize, defNx, defNy;
        float nx, ny, scale = 1f;
        float cx, cy, size;
        Geometry bg;
        BitmapText label;
        Material mat;
        int pointer = -1;
        float timer;

        Element(String name, float baseSize, float defNx, float defNy) {
            this.name = name;
            this.baseSize = baseSize;
            this.defNx = defNx;
            this.defNy = defNy;
            this.nx = defNx;
            this.ny = defNy;
        }

        boolean held() {
            return pointer != -1;
        }

        boolean contains(float x, float y) {
            float h = size / 2f;
            return x >= cx - h && x <= cx + h && y >= cy - h && y <= cy + h;
        }
    }

    private static class Tool {
        Geometry bg;
        BitmapText label;
        Material mat;
        float x, y, w, h;
        Runnable action;

        boolean contains(float px, float py) {
            return px >= x && px <= x + w && py >= y && py <= y + h;
        }
    }

    public TouchControls(SimpleApplication app) {
        this.app = app;
        this.inputManager = app.getInputManager();
        this.guiNode = app.getGuiNode();
        this.font = app.getAssetManager().loadFont("Interface/Fonts/Default.fnt");
        this.android = detectAndroid();

        try {
            prefs = Preferences.userNodeForPackage(TouchControls.class).node("Mine-clone-touch");
        } catch (Throwable t) {
            prefs = null; // e.g. Android without java.util.prefs: layout just won't persist
        }

        if (DEV_TOOLS) {
            inputManager.addMapping(TOGGLE_TOUCH, new KeyTrigger(KeyInput.KEY_F4));
            inputManager.addMapping(TOGGLE_MOUSE_UNLOCK, new KeyTrigger(KeyInput.KEY_F5));
            inputManager.addListener(this, TOGGLE_TOUCH, TOGGLE_MOUSE_UNLOCK);
        }
        inputManager.addRawInputListener(this);

        createUI();
        loadLayout();
        guiNode.attachChild(root);

        enabled = android;
        layout(app.getCamera().getWidth(), app.getCamera().getHeight());
        applyVisibility();
    }

    private static boolean detectAndroid() {
        try {
            return String.valueOf(JmeSystem.getPlatform()).toLowerCase().contains("android");
        } catch (Throwable t) {
            return false;
        }
    }

    // ==============================
    // UI CREATION
    // ==============================

    private void createUI() {
        // Default positions are screen fractions (center of each control).
        stick = makeElement("stick", null, 170f, 0.098f, 0.174f);
        stickKnob = new Geometry("StickKnob", new Quad(1f, 1f));
        stickKnob.setMaterial(flatMaterial(ColorRGBA.White));
        root.attachChild(stickKnob);

        jumpPad = makeElement("jump", "JUMP", 100f, 0.9375f, 0.2917f);
        breakPad = makeElement("break", "BREAK", 100f, 0.84375f, 0.1944f);
        placePad = makeElement("place", "PLACE", 100f, 0.9375f, 0.125f);
        pausePad = makeElement("pause", "II", 60f, 0.961f, 0.9306f);
        prevPad = makeElement("prev", "<", 60f, 0.4609f, 0.9306f);
        nextPad = makeElement("next", ">", 60f, 0.5391f, 0.9306f);

        devLabel = new BitmapText(font);
        devLabel.setSize(font.getCharSet().getRenderedSize() * 0.8f);
        devLabel.setColor(ColorRGBA.Yellow);
        root.attachChild(devLabel);

        // ---- editor toolbar ----
        root.attachChild(editNode);

        makeTool("SIZE-", () -> changeScale(-SCALE_STEP));
        makeTool("SIZE+", () -> changeScale(SCALE_STEP));
        makeTool("ALPHA-", () -> changeOpacity(-OPACITY_STEP));
        makeTool("ALPHA+", () -> changeOpacity(OPACITY_STEP));
        makeTool("MIRROR", this::mirrorLayout);
        makeTool("RESET", this::resetLayout);
        makeTool("DONE", () -> {
            endEdit();
            if (onEditDone != null) onEditDone.run();
        });

        editHint = new BitmapText(font);
        editHint.setSize(font.getCharSet().getRenderedSize() * 0.9f);
        editHint.setColor(ColorRGBA.White);
        editHint.setText("Drag controls to move. Tap one, then SIZE-/SIZE+ to resize it.");
        editNode.attachChild(editHint);
    }

    private Material flatMaterial(ColorRGBA color) {
        Material m = new Material(app.getAssetManager(), "Common/MatDefs/Misc/Unshaded.j3md");
        m.setColor("Color", color.clone());
        m.getAdditionalRenderState().setBlendMode(RenderState.BlendMode.Alpha);
        return m;
    }

    private Element makeElement(String name, String text, float baseSize, float nx, float ny) {
        Element e = new Element(name, baseSize, nx, ny);
        e.mat = flatMaterial(ColorRGBA.White);
        e.bg = new Geometry("Touch_" + name, new Quad(1f, 1f));
        e.bg.setMaterial(e.mat);
        root.attachChild(e.bg);

        if (text != null) {
            e.label = new BitmapText(font);
            e.label.setText(text);
            e.label.setSize(font.getCharSet().getRenderedSize());
            e.label.setColor(ColorRGBA.White);
            root.attachChild(e.label);
        }

        elements.add(e);
        return e;
    }

    private void makeTool(String text, Runnable action) {
        Tool t = new Tool();
        t.mat = flatMaterial(new ColorRGBA(0.08f, 0.08f, 0.08f, 0.80f));
        t.bg = new Geometry("Tool_" + text, new Quad(1f, 1f));
        t.bg.setMaterial(t.mat);
        t.label = new BitmapText(font);
        t.label.setText(text);
        t.label.setSize(font.getCharSet().getRenderedSize() * 0.85f);
        t.label.setColor(ColorRGBA.White);
        t.action = action;
        editNode.attachChild(t.bg);
        editNode.attachChild(t.label);
        tools.add(t);
    }

    // ==============================
    // LAYOUT
    // ==============================

    /** Call from Main.reshape() as well. */
    public void layout(int width, int height) {
        screenW = width;
        screenH = height;
        uiScale = Math.max(0.6f, height / 720f);

        for (Element e : elements) {
            positionElement(e);
        }

        layoutTools();
        devLabel.setLocalTranslation(10f, height - 10f, 3f);
        refreshAllColors();
    }

    private void positionElement(Element e) {
        e.size = e.baseSize * uiScale * e.scale;
        float half = e.size / 2f;

        e.cx = clamp(e.nx * screenW, half, Math.max(half, screenW - half));
        e.cy = clamp(e.ny * screenH, half, Math.max(half, screenH - half));

        e.bg.setLocalScale(e.size, e.size, 1f);
        e.bg.setLocalTranslation(e.cx - half, e.cy - half, 0f);

        if (e.label != null) {
            float base = font.getCharSet().getRenderedSize();
            e.label.setSize(base * clamp(e.size / 100f, 0.7f, 1.6f));
            e.label.setLocalTranslation(
                    e.cx - e.label.getLineWidth() / 2f,
                    e.cy + e.label.getLineHeight() / 2f,
                    2f
            );
        }

        if (e == stick) {
            updateKnob(stick.held() ? movement.x : 0f, stick.held() ? movement.y : 0f);
        }
    }

    private void layoutTools() {
        int n = tools.size();
        float bw = Math.min(130f * uiScale, (screenW - 20f) / n - 8f);
        float bh = 48f * uiScale;
        float total = n * (bw + 8f) - 8f;
        float startX = (screenW - total) / 2f;
        float y = screenH * 0.5f - bh / 2f;

        for (int i = 0; i < n; i++) {
            Tool t = tools.get(i);
            t.x = startX + i * (bw + 8f);
            t.y = y;
            t.w = bw;
            t.h = bh;
            t.bg.setLocalScale(bw, bh, 1f);
            t.bg.setLocalTranslation(t.x, t.y, 3f);
            t.label.setLocalTranslation(
                    t.x + (bw - t.label.getLineWidth()) / 2f,
                    t.y + (bh + t.label.getLineHeight()) / 2f,
                    4f
            );
        }

        editHint.setLocalTranslation(
                (screenW - editHint.getLineWidth()) / 2f,
                y + bh + 40f * uiScale,
                4f
        );
    }

    private void updateKnob(float nx, float ny) {
        float knobSize = stick.size * 0.41f;
        float radius = stick.size / 2f;
        stickKnob.setLocalScale(knobSize, knobSize, 1f);
        stickKnob.setLocalTranslation(
                stick.cx + nx * radius - knobSize / 2f,
                stick.cy + ny * radius - knobSize / 2f,
                1f
        );
    }

    // ==============================
    // COLORS
    // ==============================

    private ColorRGBA colorFor(Element e) {
        if (editMode && e == selected) {
            return new ColorRGBA(1f, 0.9f, 0.2f, Math.min(0.9f, opacity + 0.35f));
        }
        if (e.held()) {
            return new ColorRGBA(1f, 1f, 1f, Math.min(0.9f, opacity + 0.27f));
        }
        if (e == stick) {
            return new ColorRGBA(0f, 0f, 0f, Math.min(0.9f, opacity * 1.1f));
        }
        return new ColorRGBA(1f, 1f, 1f, opacity);
    }

    private void refreshColor(Element e) {
        e.mat.setColor("Color", colorFor(e));
    }

    private void refreshAllColors() {
        for (Element e : elements) refreshColor(e);
        stickKnob.getMaterial().setColor("Color",
                new ColorRGBA(1f, 1f, 1f, Math.min(0.9f, opacity + 0.27f)));
    }

    // ==============================
    // STATE / QUERIES
    // ==============================

    public void setEnabled(boolean value) {
        enabled = value;
        if (!enabled) releaseAll();
        applyVisibility();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isMouseUnlocked() {
        return mouseUnlocked;
    }

    public boolean isEditing() {
        return editMode;
    }

    /**
     * True when real mouse button / mouse-look must be ignored by the game:
     * touch overlay is on AND (we're on Android OR the dev mouse-unlock is on).
     * On a normal PC run this is always false, so nothing changes.
     */
    public boolean blocksMouse() {
        return enabled && (mouseUnlocked || android);
    }

    private boolean isActive() {
        return enabled && playing && !editMode;
    }

    private void applyVisibility() {
        root.setCullHint((editMode || isActive()) ? Spatial.CullHint.Never : Spatial.CullHint.Always);
        editNode.setCullHint(editMode ? Spatial.CullHint.Never : Spatial.CullHint.Always);
        devLabel.setText(mouseUnlocked && !editMode ? "DEV: mouse unlocked (click = finger)" : "");
    }

    public Vector2f getMovement() {
        return movement;
    }

    public boolean isJumpHeld() {
        return jumpPad.held();
    }

    public float takeLookX() {
        float v = lookX;
        lookX = 0f;
        return v;
    }

    public float takeLookY() {
        float v = lookY;
        lookY = 0f;
        return v;
    }

    public boolean consumeBreak() {
        boolean v = breakPending;
        breakPending = false;
        return v;
    }

    public boolean consumePlace() {
        boolean v = placePending;
        placePending = false;
        return v;
    }

    public boolean consumePause() {
        boolean v = pausePending;
        pausePending = false;
        return v;
    }

    /** +1 = next slot, -1 = previous slot. */
    public int consumeHotbarDelta() {
        int v = hotbarDelta;
        hotbarDelta = 0;
        return v;
    }

    // ==============================
    // EDITOR
    // ==============================

    /** Opens the layout editor. onDone runs when the user presses DONE. */
    public void beginEdit(Runnable onDone) {
        onEditDone = onDone;
        editMode = true;
        selected = null;
        dragPointer = -1;
        activeTool = null;
        toolPointer = -1;
        // ignore the click that opened the editor so it can't hit a tool
        editIgnoreUntil = System.currentTimeMillis() + 250L;
        releaseAll();
        layout(screenW, screenH);
        applyVisibility();
    }

    public void endEdit() {
        if (!editMode) return;
        editMode = false;
        selected = null;
        dragPointer = -1;
        activeTool = null;
        toolPointer = -1;
        saveLayout();
        refreshAllColors();
        applyVisibility();
    }

    private void editDown(int id, float x, float y) {
        if (System.currentTimeMillis() < editIgnoreUntil) return;

        // toolbar buttons fire on release (see editUp)
        for (Tool t : tools) {
            if (t.contains(x, y)) {
                activeTool = t;
                toolPointer = id;
                t.mat.setColor("Color", new ColorRGBA(0.35f, 0.35f, 0.35f, 0.9f));
                return;
            }
        }

        // topmost element under the pointer (later = drawn on top)
        Element hit = null;
        for (int i = elements.size() - 1; i >= 0; i--) {
            if (elements.get(i).contains(x, y)) {
                hit = elements.get(i);
                break;
            }
        }

        selected = hit;
        if (hit != null && dragPointer == -1) {
            dragPointer = id;
            dragOffX = x - hit.cx;
            dragOffY = y - hit.cy;
        }
        refreshAllColors();
    }

    private void editMove(int id, float x, float y) {
        if (id != dragPointer || selected == null) return;

        float half = selected.size / 2f;
        float cx = clamp(x - dragOffX, half, screenW - half);
        float cy = clamp(y - dragOffY, half, screenH - half);

        selected.nx = cx / screenW;
        selected.ny = cy / screenH;
        positionElement(selected);
    }

    private void editUp(int id, float x, float y) {
        if (id == toolPointer && activeTool != null) {
            Tool t = activeTool;
            activeTool = null;
            toolPointer = -1;
            t.mat.setColor("Color", new ColorRGBA(0.08f, 0.08f, 0.08f, 0.80f));
            if (t.contains(x, y)) {
                t.action.run();
            }
            return;
        }

        if (id == dragPointer) {
            dragPointer = -1;
            saveLayout();
        }
    }

    private void changeScale(float delta) {
        if (selected != null) {
            selected.scale = clamp(selected.scale + delta, MIN_SCALE, MAX_SCALE);
            positionElement(selected);
        } else {
            for (Element e : elements) {
                e.scale = clamp(e.scale + delta, MIN_SCALE, MAX_SCALE);
                positionElement(e);
            }
        }
        saveLayout();
    }

    private void changeOpacity(float delta) {
        opacity = clamp(opacity + delta, MIN_OPACITY, MAX_OPACITY);
        refreshAllColors();
        saveLayout();
    }

    private void mirrorLayout() {
        for (Element e : elements) {
            e.nx = 1f - e.nx;
            positionElement(e);
        }
        saveLayout();
    }

    private void resetLayout() {
        for (Element e : elements) {
            e.nx = e.defNx;
            e.ny = e.defNy;
            e.scale = 1f;
            positionElement(e);
        }
        opacity = DEFAULT_OPACITY;
        selected = null;
        refreshAllColors();
        saveLayout();
    }

    // ==============================
    // SAVE / LOAD
    // ==============================

    private void loadLayout() {
        if (prefs == null) return;
        try {
            opacity = clamp(prefs.getFloat("opacity", DEFAULT_OPACITY), MIN_OPACITY, MAX_OPACITY);
            for (Element e : elements) {
                e.nx = clamp(prefs.getFloat(e.name + "_x", e.defNx), 0f, 1f);
                e.ny = clamp(prefs.getFloat(e.name + "_y", e.defNy), 0f, 1f);
                e.scale = clamp(prefs.getFloat(e.name + "_s", 1f), MIN_SCALE, MAX_SCALE);
            }
        } catch (Throwable ignored) {
            // keep defaults
        }
    }

    private void saveLayout() {
        if (prefs == null) return;
        try {
            prefs.putFloat("opacity", opacity);
            for (Element e : elements) {
                prefs.putFloat(e.name + "_x", e.nx);
                prefs.putFloat(e.name + "_y", e.ny);
                prefs.putFloat(e.name + "_s", e.scale);
            }
            prefs.flush();
        } catch (Throwable ignored) {
            // layout stays in memory for this session
        }
    }

    // ==============================
    // PER-FRAME UPDATE
    // ==============================

    /** Call every frame from Main.simpleUpdate(). */
    public void update(float tpf, boolean isPlaying) {
        if (playing != isPlaying) {
            playing = isPlaying;
            releaseAll();
            applyVisibility();
        }
        if (!isActive()) return;

        // hold-to-repeat for break / place
        if (breakPad.held()) {
            breakPad.timer += tpf;
            if (breakPad.timer >= REPEAT_DELAY) {
                breakPad.timer -= REPEAT_DELAY;
                breakPending = true;
            }
        }
        if (placePad.held()) {
            placePad.timer += tpf;
            if (placePad.timer >= REPEAT_DELAY) {
                placePad.timer -= REPEAT_DELAY;
                placePending = true;
            }
        }
    }

    // ==============================
    // POINTER HANDLING (gameplay)
    // ==============================

    private void handleDown(int id, float x, float y) {
        for (Element e : elements) {
            if (e != stick && e.pointer == -1 && e.contains(x, y)) {
                press(e, id);
                return;
            }
        }

        float radius = stick.size / 2f;
        if (stick.pointer == -1 && Math.hypot(x - stick.cx, y - stick.cy) <= radius * 1.6f) {
            stick.pointer = id;
            refreshColor(stick);
            updateStick(x, y);
            return;
        }

        if (lookPointer == -1) {
            lookPointer = id;
            lastLookX = x;
            lastLookY = y;
        }
    }

    private void handleMove(int id, float x, float y) {
        if (id == stick.pointer) {
            updateStick(x, y);
        } else if (id == lookPointer) {
            lookX += x - lastLookX;
            lookY += y - lastLookY;
            lastLookX = x;
            lastLookY = y;
        }
    }

    private void handleUp(int id) {
        for (Element e : elements) {
            if (e != stick && e.pointer == id) release(e);
        }
        if (id == stick.pointer) {
            stick.pointer = -1;
            movement.set(0f, 0f);
            updateKnob(0f, 0f);
            refreshColor(stick);
        }
        if (id == lookPointer) {
            lookPointer = -1;
        }
    }

    private void press(Element e, int id) {
        e.pointer = id;
        e.timer = 0f;
        refreshColor(e);

        if (e == breakPad) breakPending = true;
        else if (e == placePad) placePending = true;
        else if (e == pausePad) pausePending = true;
        else if (e == prevPad) hotbarDelta--;
        else if (e == nextPad) hotbarDelta++;
    }

    private void release(Element e) {
        e.pointer = -1;
        refreshColor(e);
    }

    private void releaseAll() {
        for (Element e : elements) {
            e.pointer = -1;
        }
        lookPointer = -1;
        movement.set(0f, 0f);
        lookX = 0f;
        lookY = 0f;
        breakPending = false;
        placePending = false;
        pausePending = false;
        hotbarDelta = 0;
        if (stick != null && stickKnob != null) {
            updateKnob(0f, 0f);
            refreshAllColors();
        }
    }

    private void updateStick(float x, float y) {
        float radius = stick.size / 2f;
        float dx = (x - stick.cx) / radius;
        float dy = (y - stick.cy) / radius;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len > 1f) {
            dx /= len;
            dy /= len;
            len = 1f;
        }
        updateKnob(dx, dy);
        if (len < 0.12f) {
            movement.set(0f, 0f); // dead zone
        } else {
            movement.set(dx, dy); // x = right, y = forward
        }
    }

    // ==============================
    // POINTER DISPATCH
    // ==============================

    private void dispatchDown(int id, float x, float y) {
        if (editMode) editDown(id, x, y);
        else handleDown(id, x, y);
    }

    private void dispatchMove(int id, float x, float y) {
        if (editMode) editMove(id, x, y);
        else handleMove(id, x, y);
    }

    private void dispatchUp(int id, float x, float y) {
        if (editMode) editUp(id, x, y);
        else handleUp(id);
    }

    // ==============================
    // ACTION LISTENER (dev keys)
    // ==============================

    @Override
    public void onAction(String name, boolean pressed, float tpf) {
        if (!pressed) return;

        if (TOGGLE_TOUCH.equals(name)) {
            if (mouseUnlocked && enabled) {
                mouseUnlocked = false;
            }
            setEnabled(!enabled);
            System.out.println("[DEV] Touch controls: " + (enabled ? "ON" : "OFF"));
        } else if (TOGGLE_MOUSE_UNLOCK.equals(name)) {
            mouseUnlocked = !mouseUnlocked;
            if (mouseUnlocked) enabled = true;
            releaseAll();
            applyVisibility();
            System.out.println("[DEV] Mouse unlocked (touch emulation): " + mouseUnlocked);
        }
    }

    // ==============================
    // RAW INPUT
    // ==============================

    @Override
    public void onTouchEvent(TouchEvent evt) {
        if (evt == null || !(editMode || isActive())) return;

        // NOTE: if your Android build reports Y from the top, use
        // (app.getCamera().getHeight() - evt.getY()) here instead.
        switch (evt.getType()) {
            case DOWN:
                dispatchDown(evt.getPointerId(), evt.getX(), evt.getY());
                break;
            case MOVE:
                dispatchMove(evt.getPointerId(), evt.getX(), evt.getY());
                break;
            case UP:
                dispatchUp(evt.getPointerId(), evt.getX(), evt.getY());
                break;
            default:
                break;
        }
    }

    private boolean mouseEmulation() {
        // The editor always accepts the mouse so you can customize on PC.
        return editMode || (isActive() && mouseUnlocked);
    }

    @Override
    public void onMouseButtonEvent(MouseButtonEvent evt) {
        if (!mouseEmulation()) return;
        if (evt.getButtonIndex() != 0) return; // left button only

        if (evt.isPressed()) {
            dispatchDown(MOUSE_POINTER, evt.getX(), evt.getY());
        } else {
            dispatchUp(MOUSE_POINTER, evt.getX(), evt.getY());
        }
    }

    @Override
    public void onMouseMotionEvent(MouseMotionEvent evt) {
        if (!mouseEmulation()) return;
        dispatchMove(MOUSE_POINTER, evt.getX(), evt.getY());
    }

    @Override public void beginInput() {}
    @Override public void endInput() {}
    @Override public void onJoyAxisEvent(JoyAxisEvent evt) {}
    @Override public void onJoyButtonEvent(JoyButtonEvent evt) {}
    @Override public void onKeyEvent(KeyInputEvent evt) {}

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }

    public void cleanup() {
        inputManager.removeRawInputListener(this);
        inputManager.removeListener(this);
        root.removeFromParent();
    }
}