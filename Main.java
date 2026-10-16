package com.mygame;

import com.jme3.app.SimpleApplication;
import com.jme3.audio.AudioNode;
import com.jme3.font.BitmapFont;
import com.jme3.font.BitmapText;
import com.jme3.input.KeyInput;
import com.jme3.input.MouseInput;
import com.jme3.input.RawInputListener;
import com.jme3.input.controls.ActionListener;
import com.jme3.input.controls.KeyTrigger;
import com.jme3.input.controls.MouseButtonTrigger;
import com.jme3.input.event.JoyAxisEvent;
import com.jme3.input.event.JoyButtonEvent;
import com.jme3.input.event.KeyInputEvent;
import com.jme3.input.event.MouseButtonEvent;
import com.jme3.input.event.MouseMotionEvent;
import com.jme3.input.event.TouchEvent;
import com.jme3.material.Material;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Vector2f;
import com.jme3.math.Vector3f;
import com.jme3.scene.Geometry;
import com.jme3.scene.Node;
import com.jme3.scene.shape.Quad;
import com.jme3.texture.Texture;
import com.jme3.texture.Texture2D;
import com.jme3.texture.image.ColorSpace;
import com.jme3.util.BufferUtils;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

public class Main extends SimpleApplication
        implements ActionListener, RawInputListener {

    private static final float REACH = 6f;

    private static final int HOTBAR_SLOT_COUNT = 9;
    private static final float HOTBAR_BOTTOM_MARGIN = 20f;

    private static final float SETTINGS_MIN_FOV = 30f;
    private static final float SETTINGS_MAX_FOV = 110f;
    private static final float SETTINGS_MIN_SENS = 0.05f;
    private static final float SETTINGS_MAX_SENS = 1.10f;

    private static final int SETTINGS_MIN_RENDER_DISTANCE = 2;
    private static final int SETTINGS_MAX_RENDER_DISTANCE = World.RENDER_DISTANCE;

    private static final float UI_BUTTON_WIDTH = 300f;
    private static final float UI_BUTTON_HEIGHT = 78f;
    private static final float UI_BUTTON_GAP = 12f;
    private static final float SLIDER_WIDTH = 360f;
    private static final float SLIDER_HEIGHT = 12f;

    private TouchControls touchControls;

    private enum GameState {
        MENU,
        PLAYING,
        PAUSED,
        SETTINGS,
        CONTROLS,
        TOUCH_EDIT
    }

    private GameState gameState = GameState.MENU;

    // Settings are stored per user so they survive game restarts.
    private final Preferences preferences =
            Preferences.userNodeForPackage(Main.class).node("Mine-clone");

    private static final String PREF_FOV = "fov";
    private static final String PREF_SENSITIVITY = "sensitivity";
    private static final String PREF_RENDER_DISTANCE = "renderDistance";
    private static final String PREF_VIEW_BOBBING = "viewBobbing";
    private static final String PREF_CONTROL_PREFIX = "control_";

    private Material grassTopMaterial;
    private Material grassSideMaterial;
    private Material dirtMaterial;
    private Material stoneMaterial;
    private Material woodMaterial;
    private Material woodTopMaterial;
    private Material leavesMaterial;
    private Texture buttonBackgroundTexture;

    private Player player;
    private World world;
    private AudioNode[] grassWalkSounds;
    private AudioNode[] grassBreakSounds;
    private AudioNode[] stoneBreakSounds;
    private AudioNode[] woodBreakSounds;
    private AudioNode uiClickSound;

    private BitmapText crosshair;

    private Node hotbarNode;

    private int selectedSlot = 0;

    private final byte[] hotbarBlocks = {
            Chunk.GRASS,
            Chunk.DIRT,
            Chunk.STONE,
            Chunk.WOOD,
            Chunk.LEAVES,
            Chunk.AIR,
            Chunk.AIR,
            Chunk.AIR,
            Chunk.AIR
    };

    private BitmapFont menuFont;
    private Node menuNode;
    private Geometry logoGeometry;
    private float logoDisplayWidth;
    private float logoDisplayHeight;
    private Texture logoTexture;

    private final List<MenuButton> menuButtons = new ArrayList<>();

    private Node pauseNode;
    private Node settingsNode;
    private Node controlsNode;
    private Geometry pauseOverlay;

    private final List<MenuButton> pauseButtons = new ArrayList<>();
    private final List<MenuButton> settingsButtons = new ArrayList<>();
    private final List<MenuButton> controlButtons = new ArrayList<>();

    private BitmapText fovValueText;
    private BitmapText sensitivityValueText;
    private Geometry fovSliderBar;
    private Geometry fovSliderFill;
    private Geometry fovSliderKnob;
    private Geometry sensitivitySliderBar;
    private Geometry sensitivitySliderFill;
    private Geometry sensitivitySliderKnob;

    private BitmapText renderDistanceValueText;
    private Geometry renderDistanceSliderBar;
    private Geometry renderDistanceSliderFill;
    private Geometry renderDistanceSliderKnob;
    private MenuButton viewBobbingButton;

    private SliderControl draggingSlider;

    private BitmapText controlsHintText;
    private String rebindingControl = null;

    private float currentFov = 90f;
    private float currentSensitivity = 0.90f;
    private int currentRenderDistance = World.RENDER_DISTANCE;
    private boolean viewBobbingEnabled = true;

    @Override
    public void destroy() {
        saveSettings();
        saveControlBindings();
        if (touchControls != null) touchControls.cleanup();
        super.destroy();
    }

    public static void main(String[] args) {
        Main app = new Main();

        com.jme3.system.AppSettings settings =
                new com.jme3.system.AppSettings(true);

        settings.setTitle("Mine-clone");
        settings.setResolution(1280, 720);
        settings.setResizable(true);
        settings.setFullscreen(false);

        app.setSettings(settings);
        app.setShowSettings(false);

        app.start();
    }

    @Override
    public void simpleInitApp() {

        // Touch overlay: off on PC (F4 / F5 dev keys), automatic on Android.
        touchControls = new TouchControls(this);

        // Load saved settings before the camera/UI are initialized.
        loadSavedSettings();

        // Replace jME's default Escape-to-exit mapping with our in-game pause menu.
        inputManager.deleteMapping(SimpleApplication.INPUT_MAPPING_EXIT);

        cam.setFrustumPerspective(
                currentFov,
                (float) cam.getWidth() / cam.getHeight(),
                0.05f,
                1000f
        );

        viewPort.setBackgroundColor(
                new ColorRGBA(0.45f, 0.68f, 0.95f, 1f)
        );

        // ==========================================
        // TEXTURES
        // ==========================================
        Texture grassTop = assetManager.loadTexture("Textures/grass_top.png");
        Texture grassSide = assetManager.loadTexture("Textures/grass_side.png");
        Texture dirt = assetManager.loadTexture("Textures/dirt.png");
        Texture stone = assetManager.loadTexture("Textures/stone.png");
        Texture wood = assetManager.loadTexture("Textures/wood_Oak.png");
        Texture woodTop = assetManager.loadTexture("Textures/wood_Oak_top.png");
        Texture leaves = assetManager.loadTexture("Textures/Leaves.Oak.png");
        buttonBackgroundTexture = assetManager.loadTexture("Textures/ui_button_transparent.png");
        buttonBackgroundTexture.setMagFilter(Texture.MagFilter.Nearest);
        buttonBackgroundTexture.setMinFilter(Texture.MinFilter.NearestNoMipMaps);
        buttonBackgroundTexture.setWrap(Texture.WrapMode.Clamp);

        logoTexture = assetManager.loadTexture("Textures/logos/Mine-clone.png");
        logoTexture.setMagFilter(Texture.MagFilter.Bilinear);
        logoTexture.setMinFilter(Texture.MinFilter.BilinearNoMipMaps);
        logoTexture.setWrap(Texture.WrapMode.EdgeClamp);

        // grass_top.png is grayscale, so tint ONLY the top surface green.
        grassTopMaterial = createTintedMaterial(
                grassTop,
                new ColorRGBA(0.40f, 0.88f, 0.16f, 1f)
        );

        grassSideMaterial = createMaterial(grassSide);
        dirtMaterial = createMaterial(dirt);
        stoneMaterial = createMaterial(stone);
        woodMaterial = createMaterial(wood);
        woodTopMaterial = createMaterial(woodTop);

        // Leaves.Oak.png is grayscale, so tint ONLY the leaves green.
        leavesMaterial = createLeafMaterial(
                leaves,
                new ColorRGBA(0.16f, 0.78f, 0.12f, 1f)
        );

        // ==========================================
        // WALK SOUNDS
        // ==========================================
        grassWalkSounds = new AudioNode[]{
                new AudioNode(assetManager, "Sounds/step_grass2.ogg"),
                new AudioNode(assetManager, "Sounds/step_grass3.ogg"),
                new AudioNode(assetManager, "Sounds/step_grass4.ogg")
        };

        for (AudioNode sound : grassWalkSounds) {
            sound.setLooping(false);
            sound.setPositional(false);
            sound.setVolume(0.75f);
            rootNode.attachChild(sound);
        }

        // ==========================================
        // BLOCK BREAK SOUNDS
        // ==========================================
        grassBreakSounds = loadSoundGroup(
                "Sounds/dig/grass1.ogg",
                "Sounds/dig/grass2.ogg",
                "Sounds/dig/grass3.ogg",
                "Sounds/dig/grass4.ogg"
        );

        stoneBreakSounds = loadSoundGroup(
                "Sounds/dig/stone1.ogg",
                "Sounds/dig/stone2.ogg",
                "Sounds/dig/stone3.ogg",
                "Sounds/dig/stone4.ogg"
        );

        woodBreakSounds = loadSoundGroup(
                "Sounds/dig/wood1.ogg",
                "Sounds/dig/wood2.ogg",
                "Sounds/dig/wood3.ogg",
                "Sounds/dig/wood4.ogg"
        );

        uiClickSound = new AudioNode(assetManager, "Sounds/ui_click.ogg");
        uiClickSound.setLooping(false);
        uiClickSound.setPositional(false);
        uiClickSound.setVolume(0.65f);
        rootNode.attachChild(uiClickSound);

        flyCam.setEnabled(false);

        inputManager.addMapping("BreakBlock", new MouseButtonTrigger(MouseInput.BUTTON_LEFT));
        inputManager.addMapping("PlaceBlock", new MouseButtonTrigger(MouseInput.BUTTON_RIGHT));
        inputManager.addListener(this, "BreakBlock", "PlaceBlock");

        int[] numberKeys = {
                KeyInput.KEY_1, KeyInput.KEY_2, KeyInput.KEY_3,
                KeyInput.KEY_4, KeyInput.KEY_5, KeyInput.KEY_6,
                KeyInput.KEY_7, KeyInput.KEY_8, KeyInput.KEY_9
        };

        for (int i = 0; i < numberKeys.length; i++) {
            String mappingName = "HotbarSlot" + (i + 1);
            inputManager.addMapping(mappingName, new KeyTrigger(numberKeys[i]));
            inputManager.addListener(this, mappingName);
        }

        inputManager.addMapping("UILeftClick", new MouseButtonTrigger(MouseInput.BUTTON_LEFT));
        inputManager.addListener(this, "UILeftClick");
        inputManager.addRawInputListener(this);

        menuFont = assetManager.loadFont("Interface/Fonts/Default.fnt");
        createMenu();
        showMenu();
    }

    // ==========================================
    // MENUS
    // ==========================================

    private void createMenu() {
        menuNode = new Node("Menu");
        guiNode.attachChild(menuNode);

        logoDisplayWidth = 480f;
        float logoAspect = (float) logoTexture.getImage().getWidth()
                / (float) logoTexture.getImage().getHeight();
        logoDisplayHeight = logoDisplayWidth / logoAspect;

        Quad logoQuad = new Quad(logoDisplayWidth, logoDisplayHeight);
        logoGeometry = new Geometry("Logo", logoQuad);

        Material logoMaterial = new Material(
                assetManager,
                "Common/MatDefs/Misc/Unshaded.j3md"
        );
        logoMaterial.setTexture("ColorMap", logoTexture);
        logoMaterial.getAdditionalRenderState().setBlendMode(
                com.jme3.material.RenderState.BlendMode.Alpha
        );
        logoGeometry.setQueueBucket(
                com.jme3.renderer.queue.RenderQueue.Bucket.Gui
        );
        logoGeometry.setMaterial(logoMaterial);
        menuNode.attachChild(logoGeometry);

        MenuButton startButton = createButton(menuNode, "Start", new ColorRGBA(0.2f, 0.7f, 0.2f, 1f), this::startGame);
        MenuButton quitButton = createButton(menuNode, "Quit", new ColorRGBA(0.7f, 0.2f, 0.2f, 1f), this::stop);

        menuButtons.clear();
        menuButtons.add(startButton);
        menuButtons.add(quitButton);

        createPauseMenu();
        createSettingsMenu();
        createControlsMenu();
        createPauseOverlay();
        updateAllUiLayouts();
    }

    private MenuButton createButton(Node parent, String text, ColorRGBA color, Runnable action) {
        Quad quad = new Quad(UI_BUTTON_WIDTH, UI_BUTTON_HEIGHT);
        Geometry background = new Geometry("Button_" + parent.getName() + "_" + text, quad);
        Material material = new Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md");

        if (buttonBackgroundTexture != null) {
            material.setTexture("ColorMap", buttonBackgroundTexture);
            material.getAdditionalRenderState().setBlendMode(
                    com.jme3.material.RenderState.BlendMode.Alpha
            );
        } else {
            material.setColor("Color", color);
        }

        background.setMaterial(material);
        parent.attachChild(background);

        BitmapText shadow = new BitmapText(menuFont);
        shadow.setSize(menuFont.getCharSet().getRenderedSize() * 1.08f);
        shadow.setText(text);
        shadow.setColor(new ColorRGBA(0f, 0f, 0f, 1f));
        parent.attachChild(shadow);

        BitmapText label = new BitmapText(menuFont);
        label.setSize(menuFont.getCharSet().getRenderedSize() * 1.08f);
        label.setText(text);
        label.setColor(ColorRGBA.White);
        parent.attachChild(label);

        MenuButton button = new MenuButton();
        button.background = background;
        button.label = label;
        button.labelShadow = shadow;
        button.action = () -> {
            playUiClick();
            action.run();
        };
        button.width = UI_BUTTON_WIDTH;
        button.height = UI_BUTTON_HEIGHT;
        button.displayName = text;
        return button;
    }

    private AudioNode[] loadSoundGroup(String... assetPaths) {
        AudioNode[] sounds = new AudioNode[assetPaths.length];

        for (int i = 0; i < assetPaths.length; i++) {
            AudioNode sound = new AudioNode(assetManager, assetPaths[i]);
            sound.setLooping(false);
            sound.setPositional(false);
            sound.setVolume(0.85f);
            rootNode.attachChild(sound);
            sounds[i] = sound;
        }

        return sounds;
    }

    private void playRandomSound(AudioNode[] sounds) {
        if (sounds == null || sounds.length == 0) {
            return;
        }

        int index = (int) (Math.random() * sounds.length);
        sounds[index].playInstance();
    }

    private void playBlockBreakSound(byte block) {
        if (block == Chunk.STONE) {
            playRandomSound(stoneBreakSounds);
        } else if (block == Chunk.WOOD) {
            playRandomSound(woodBreakSounds);
        } else if (block == Chunk.GRASS
                || block == Chunk.DIRT
                || block == Chunk.LEAVES) {
            playRandomSound(grassBreakSounds);
        }
    }

    private void playBlockPlaceSound(byte block) {
        playBlockBreakSound(block);
    }

    private void playUiClick() {
        if (uiClickSound != null) {
            uiClickSound.playInstance();
        }
    }

    private void createPauseMenu() {
        pauseNode = new Node("PauseMenu");
        guiNode.attachChild(pauseNode);

        BitmapText title = new BitmapText(menuFont);
        title.setSize(menuFont.getCharSet().getRenderedSize() * 1.7f);
        title.setText("Game Menu");
        title.setName("PauseTitle");
        pauseNode.attachChild(title);

        pauseButtons.clear();
        pauseButtons.add(createButton(pauseNode, "Resume", new ColorRGBA(0.2f, 0.7f, 0.2f, 1f), this::resumeGame));
        pauseButtons.add(createButton(pauseNode, "Options", new ColorRGBA(0.25f, 0.45f, 0.75f, 1f), this::showSettings));
        pauseButtons.add(createButton(pauseNode, "Controls", new ColorRGBA(0.35f, 0.35f, 0.65f, 1f), this::showControls));
        pauseButtons.add(createButton(pauseNode, "Touch Controls", new ColorRGBA(0.30f, 0.50f, 0.50f, 1f), this::showTouchEditor));
        pauseButtons.add(createButton(pauseNode, "Back to Title Screen", new ColorRGBA(0.65f, 0.4f, 0.2f, 1f), this::returnToTitle));
    }

    private void createSettingsMenu() {
        settingsNode = new Node("SettingsMenu");
        guiNode.attachChild(settingsNode);

        BitmapText title = new BitmapText(menuFont);
        title.setSize(menuFont.getCharSet().getRenderedSize() * 1.7f);
        title.setText("Options");
        title.setName("SettingsTitle");
        settingsNode.attachChild(title);

        BitmapText fovLabel = new BitmapText(menuFont);
        fovLabel.setText("Field of View");
        fovLabel.setSize(menuFont.getCharSet().getRenderedSize());
        fovLabel.setName("FovLabel");
        settingsNode.attachChild(fovLabel);

        fovValueText = new BitmapText(menuFont);
        fovValueText.setSize(menuFont.getCharSet().getRenderedSize());
        settingsNode.attachChild(fovValueText);

        fovSliderBar = createSliderBar("FovBar");
        fovSliderFill = createSliderFill("FovFill");
        fovSliderKnob = createSliderKnob("FovKnob");
        settingsNode.attachChild(fovSliderBar);
        settingsNode.attachChild(fovSliderFill);
        settingsNode.attachChild(fovSliderKnob);

        BitmapText sensLabel = new BitmapText(menuFont);
        sensLabel.setText("Mouse Sensitivity");
        sensLabel.setSize(menuFont.getCharSet().getRenderedSize());
        sensLabel.setName("SensLabel");
        settingsNode.attachChild(sensLabel);

        sensitivityValueText = new BitmapText(menuFont);
        sensitivityValueText.setSize(menuFont.getCharSet().getRenderedSize());
        settingsNode.attachChild(sensitivityValueText);

        sensitivitySliderBar = createSliderBar("SensBar");
        sensitivitySliderFill = createSliderFill("SensFill");
        sensitivitySliderKnob = createSliderKnob("SensKnob");
        settingsNode.attachChild(sensitivitySliderBar);
        settingsNode.attachChild(sensitivitySliderFill);
        settingsNode.attachChild(sensitivitySliderKnob);

        BitmapText renderDistanceLabel = new BitmapText(menuFont);
        renderDistanceLabel.setText("Render Distance");
        renderDistanceLabel.setSize(menuFont.getCharSet().getRenderedSize());
        renderDistanceLabel.setName("RenderDistanceLabel");
        settingsNode.attachChild(renderDistanceLabel);

        renderDistanceValueText = new BitmapText(menuFont);
        renderDistanceValueText.setSize(menuFont.getCharSet().getRenderedSize());
        settingsNode.attachChild(renderDistanceValueText);

        renderDistanceSliderBar = createSliderBar("RenderDistanceBar");
        renderDistanceSliderFill = createSliderFill("RenderDistanceFill");
        renderDistanceSliderKnob = createSliderKnob("RenderDistanceKnob");
        settingsNode.attachChild(renderDistanceSliderBar);
        settingsNode.attachChild(renderDistanceSliderFill);
        settingsNode.attachChild(renderDistanceSliderKnob);

        viewBobbingButton = createButton(
                settingsNode,
                "View Bobbing: ON",
                new ColorRGBA(0.22f, 0.42f, 0.62f, 1f),
                this::toggleViewBobbing
        );

        settingsButtons.clear();
        settingsButtons.add(viewBobbingButton);
        settingsButtons.add(createButton(settingsNode, "Back", new ColorRGBA(0.35f, 0.35f, 0.35f, 1f), this::showPauseMenu));
        updateSettingsVisuals();
    }

    private void createControlsMenu() {
        controlsNode = new Node("ControlsMenu");
        guiNode.attachChild(controlsNode);

        BitmapText title = new BitmapText(menuFont);
        title.setSize(menuFont.getCharSet().getRenderedSize() * 1.7f);
        title.setText("Controls");
        title.setName("ControlsTitle");
        controlsNode.attachChild(title);

        String[] names = {
                "Move Forward",
                "Move Backward",
                "Move Left",
                "Move Right",
                "Jump"
        };
        String[] actions = {
                Player.CONTROL_FORWARD,
                Player.CONTROL_BACKWARD,
                Player.CONTROL_LEFT,
                Player.CONTROL_RIGHT,
                Player.CONTROL_JUMP
        };

        controlButtons.clear();
        for (int i = 0; i < names.length; i++) {
            String action = actions[i];
            MenuButton row = createButton(controlsNode, names[i] + ": " + keyName(action), new ColorRGBA(0.23f, 0.32f, 0.43f, 1f), () -> beginRebind(action));
            row.controlAction = action;
            controlButtons.add(row);
        }

        BitmapText mouseInfo = new BitmapText(menuFont);
        mouseInfo.setText("Break Block: Left Mouse   |   Place Block: Right Mouse");
        mouseInfo.setSize(menuFont.getCharSet().getRenderedSize() * 0.8f);
        mouseInfo.setName("MouseInfo");
        controlsNode.attachChild(mouseInfo);

        controlsHintText = new BitmapText(menuFont);
        controlsHintText.setSize(menuFont.getCharSet().getRenderedSize() * 0.9f);
        controlsHintText.setText("Click a control, then press a key. Press ESC to cancel.");
        controlsNode.attachChild(controlsHintText);

        controlButtons.add(createButton(controlsNode, "Back", new ColorRGBA(0.35f, 0.35f, 0.35f, 1f), this::showPauseMenu));
    }

    private Geometry createSliderBar(String name) {
        Quad quad = new Quad(SLIDER_WIDTH, SLIDER_HEIGHT);
        Geometry g = new Geometry(name, quad);
        Material m = new Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md");
        m.setColor("Color", new ColorRGBA(0.12f, 0.12f, 0.12f, 1f));
        g.setMaterial(m);
        return g;
    }

    private Geometry createSliderFill(String name) {
        Quad quad = new Quad(1f, SLIDER_HEIGHT);
        Geometry g = new Geometry(name, quad);
        Material m = new Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md");
        m.setColor("Color", new ColorRGBA(0.25f, 0.75f, 0.3f, 1f));
        g.setMaterial(m);
        return g;
    }

    private Geometry createSliderKnob(String name) {
        Quad quad = new Quad(14f, 24f);
        Geometry g = new Geometry(name, quad);
        Material m = new Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md");
        m.setColor("Color", new ColorRGBA(0.85f, 0.85f, 0.85f, 1f));
        g.setMaterial(m);
        return g;
    }

    private void createPauseOverlay() {
        Quad quad = new Quad(1f, 1f);
        pauseOverlay = new Geometry("PauseOverlay", quad);
        Material material = new Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md");
        material.setColor("Color", new ColorRGBA(0f, 0f, 0f, 0.45f));
        material.getAdditionalRenderState().setBlendMode(com.jme3.material.RenderState.BlendMode.Alpha);
        pauseOverlay.setMaterial(material);
        pauseOverlay.setLocalTranslation(0, 0, -1);
        guiNode.attachChild(pauseOverlay);
    }

    // ==========================================
    // UI LAYOUT
    // ==========================================

    private void updateMenuLayout() {
        if (menuNode == null) return;
        float width = settings.getWidth();
        float height = settings.getHeight();

        float logoX = (width - logoDisplayWidth) / 2f;
        float logoY = height - 40f - logoDisplayHeight;
        logoGeometry.setLocalTranslation(logoX, logoY, 2);

        layoutButtons(menuButtons, width / 2f, height / 2f, UI_BUTTON_WIDTH, UI_BUTTON_HEIGHT, UI_BUTTON_GAP, 0);
    }

    private void layoutButtons(List<MenuButton> buttons, float centerX, float centerY, float buttonWidth, float buttonHeight, float gap, float startOffset) {
        int count = buttons.size();
        float totalHeight = (buttonHeight * count) + (gap * Math.max(0, count - 1));
        float startY = centerY + totalHeight / 2f + startOffset;
        for (int i = 0; i < count; i++) {
            MenuButton button = buttons.get(i);
            float y = startY - i * (buttonHeight + gap) - buttonHeight;
            float x = centerX - buttonWidth / 2f;
            button.x = x;
            button.y = y;
            button.width = buttonWidth;
            button.height = buttonHeight;
            button.background.setLocalScale(buttonWidth / UI_BUTTON_WIDTH, buttonHeight / UI_BUTTON_HEIGHT, 1f);
            button.background.setLocalTranslation(x, y, 0);
            float labelX = x + (buttonWidth - button.label.getLineWidth()) / 2f;
            float labelY = y + (buttonHeight + button.label.getLineHeight()) / 2f;
            button.labelShadow.setLocalTranslation(labelX + 2f, labelY - 2f, 1);
            button.label.setLocalTranslation(labelX, labelY, 2);
        }
    }

    private void updatePauseLayout() {
        if (pauseNode == null) return;
        float width = settings.getWidth();
        float height = settings.getHeight();
        BitmapText title = (BitmapText) pauseNode.getChild("PauseTitle");
        title.setLocalTranslation((width - title.getLineWidth()) / 2f, height - 90f, 2);
        layoutButtons(pauseButtons, width / 2f, height / 2f - 15f, UI_BUTTON_WIDTH, UI_BUTTON_HEIGHT, UI_BUTTON_GAP, 0);
    }

    private void updateSettingsLayout() {
        if (settingsNode == null) return;
        float width = settings.getWidth();
        float height = settings.getHeight();
        BitmapText title = (BitmapText) settingsNode.getChild("SettingsTitle");
        title.setLocalTranslation((width - title.getLineWidth()) / 2f, height - 90f, 2);

        float centerX = width / 2f;
        float barX = centerX - SLIDER_WIDTH / 2f;

        float fovBarY = height / 2f + 95f;
        BitmapText fovLabel = (BitmapText) settingsNode.getChild("FovLabel");
        fovLabel.setLocalTranslation(barX, fovBarY + 30f, 2);
        fovValueText.setLocalTranslation(centerX + SLIDER_WIDTH / 2f - fovValueText.getLineWidth(), fovBarY + 30f, 2);
        fovSliderBar.setLocalTranslation(barX, fovBarY, 1);
        updateSliderGraphics(fovSliderFill, fovSliderKnob, barX, fovBarY, fovProgress(), 2);

        float sensBarY = height / 2f - 10f;
        BitmapText sensLabel = (BitmapText) settingsNode.getChild("SensLabel");
        sensLabel.setLocalTranslation(barX, sensBarY + 30f, 2);
        sensitivityValueText.setLocalTranslation(centerX + SLIDER_WIDTH / 2f - sensitivityValueText.getLineWidth(), sensBarY + 30f, 2);
        sensitivitySliderBar.setLocalTranslation(barX, sensBarY, 1);
        updateSliderGraphics(sensitivitySliderFill, sensitivitySliderKnob, barX, sensBarY, sensitivityProgress(), 2);

        float renderBarY = height / 2f - 115f;
        BitmapText renderDistanceLabel = (BitmapText) settingsNode.getChild("RenderDistanceLabel");
        renderDistanceLabel.setLocalTranslation(barX, renderBarY + 30f, 2);
        renderDistanceValueText.setLocalTranslation(centerX + SLIDER_WIDTH / 2f - renderDistanceValueText.getLineWidth(), renderBarY + 30f, 2);
        renderDistanceSliderBar.setLocalTranslation(barX, renderBarY, 1);
        updateSliderGraphics(renderDistanceSliderFill, renderDistanceSliderKnob, barX, renderBarY, renderDistanceProgress(), 2);

        MenuButton bob = settingsButtons.get(0);
        bob.width = UI_BUTTON_WIDTH;
        bob.height = UI_BUTTON_HEIGHT;
        bob.x = centerX - UI_BUTTON_WIDTH / 2f;
        bob.y = 120f;
        bob.background.setLocalScale(1f, 1f, 1f);
        bob.background.setLocalTranslation(bob.x, bob.y, 0);
        float bobLabelX = bob.x + (UI_BUTTON_WIDTH - bob.label.getLineWidth()) / 2f;
        float bobLabelY = bob.y + (UI_BUTTON_HEIGHT + bob.label.getLineHeight()) / 2f;
        bob.labelShadow.setLocalTranslation(bobLabelX + 2f, bobLabelY - 2f, 1);
        bob.label.setLocalTranslation(bobLabelX, bobLabelY, 2);

        MenuButton back = settingsButtons.get(1);
        back.width = UI_BUTTON_WIDTH;
        back.height = UI_BUTTON_HEIGHT;
        back.x = centerX - UI_BUTTON_WIDTH / 2f;
        back.y = 25f;
        back.background.setLocalScale(1f, 1f, 1f);
        back.background.setLocalTranslation(back.x, back.y, 0);
        float backLabelX = back.x + (UI_BUTTON_WIDTH - back.label.getLineWidth()) / 2f;
        float backLabelY = back.y + (UI_BUTTON_HEIGHT + back.label.getLineHeight()) / 2f;
        back.labelShadow.setLocalTranslation(backLabelX + 2f, backLabelY - 2f, 1);
        back.label.setLocalTranslation(backLabelX, backLabelY, 2);
    }

    private void updateControlsLayout() {
        if (controlsNode == null) return;
        float width = settings.getWidth();
        float height = settings.getHeight();
        BitmapText title = (BitmapText) controlsNode.getChild("ControlsTitle");
        title.setLocalTranslation((width - title.getLineWidth()) / 2f, height - 90f, 2);

        float x = width / 2f - UI_BUTTON_WIDTH / 2f;
        float startY = height - 165f;
        for (int i = 0; i < 5; i++) {
            MenuButton b = controlButtons.get(i);
            b.x = x;
            b.y = startY - i * (UI_BUTTON_HEIGHT + 10f);
            b.width = UI_BUTTON_WIDTH;
            b.height = UI_BUTTON_HEIGHT;
            b.background.setLocalTranslation(b.x, b.y, 0);
            String controlText = controlLabel(b);
            b.labelShadow.setText(controlText);
            b.label.setText(controlText);
            float labelX = b.x + (UI_BUTTON_WIDTH - b.label.getLineWidth()) / 2f;
            float labelY = b.y + (UI_BUTTON_HEIGHT + b.label.getLineHeight()) / 2f;
            b.labelShadow.setLocalTranslation(labelX + 2f, labelY - 2f, 1);
            b.label.setLocalTranslation(labelX, labelY, 2);
        }
        BitmapText mouseInfo = (BitmapText) controlsNode.getChild("MouseInfo");
        mouseInfo.setLocalTranslation((width - mouseInfo.getLineWidth()) / 2f, 95f, 2);
        controlsHintText.setLocalTranslation((width - controlsHintText.getLineWidth()) / 2f, 70f, 2);
        MenuButton back = controlButtons.get(controlButtons.size() - 1);
        back.x = width / 2f - UI_BUTTON_WIDTH / 2f;
        back.y = 15f;
        back.background.setLocalTranslation(back.x, back.y, 0);
        float backLabelX = back.x + (UI_BUTTON_WIDTH - back.label.getLineWidth()) / 2f;
        float backLabelY = back.y + (UI_BUTTON_HEIGHT + back.label.getLineHeight()) / 2f;
        back.labelShadow.setLocalTranslation(backLabelX + 2f, backLabelY - 2f, 1);
        back.label.setLocalTranslation(backLabelX, backLabelY, 2);
    }

    private String controlLabel(MenuButton button) {
        String name = button.displayName != null ? button.displayName : button.label.getText();
        int colon = name.indexOf(':');
        if (colon >= 0) name = name.substring(0, colon);
        return name + ": " + (rebindingControl != null && rebindingControl.equals(button.controlAction) ? "Press a key..." : keyName(button.controlAction));
    }

    private String keyName(String action) {
        int key = player == null ? defaultKey(action) : player.getControlKey(action);

        String name = inputManager.getKeyName(key);

        if (name == null || name.trim().isEmpty()) {
            return "Unknown Key";
        }

        name = name.trim().toUpperCase();

        switch (name) {
            case "RETURN":
                return "ENTER";
            case "LSHIFT":
                return "LEFT SHIFT";
            case "RSHIFT":
                return "RIGHT SHIFT";
            case "LCONTROL":
                return "LEFT CTRL";
            case "RCONTROL":
                return "RIGHT CTRL";
            case "LMENU":
                return "LEFT ALT";
            case "RMENU":
                return "RIGHT ALT";
            case "BACK":
                return "BACKSPACE";
            default:
                return name;
        }
    }

    private int defaultKey(String action) {
        if (Player.CONTROL_FORWARD.equals(action)) return KeyInput.KEY_W;
        if (Player.CONTROL_BACKWARD.equals(action)) return KeyInput.KEY_S;
        if (Player.CONTROL_LEFT.equals(action)) return KeyInput.KEY_A;
        if (Player.CONTROL_RIGHT.equals(action)) return KeyInput.KEY_D;
        return KeyInput.KEY_SPACE;
    }

    private void updateAllUiLayouts() {
        updateMenuLayout();
        updatePauseLayout();
        updateSettingsLayout();
        updateControlsLayout();
        if (pauseOverlay != null) {
            pauseOverlay.setLocalScale(settings.getWidth(), settings.getHeight(), 1f);
        }
    }

    // ==========================================
    // SETTINGS
    // ==========================================

    private float fovProgress() {
        return (currentFov - SETTINGS_MIN_FOV) / (SETTINGS_MAX_FOV - SETTINGS_MIN_FOV);
    }

    private float sensitivityProgress() {
        return (currentSensitivity - SETTINGS_MIN_SENS) / (SETTINGS_MAX_SENS - SETTINGS_MIN_SENS);
    }

    private float renderDistanceProgress() {
        return (currentRenderDistance - SETTINGS_MIN_RENDER_DISTANCE)
                / (float) (SETTINGS_MAX_RENDER_DISTANCE - SETTINGS_MIN_RENDER_DISTANCE);
    }

    private void updateSliderGraphics(Geometry fill, Geometry knob, float x, float y, float progress, float z) {
        progress = Math.max(0f, Math.min(1f, progress));
        float fillWidth = Math.max(1f, SLIDER_WIDTH * progress);
        fill.setLocalScale(fillWidth, 1f, 1f);
        fill.setLocalTranslation(x, y, z);
        knob.setLocalTranslation(x + SLIDER_WIDTH * progress - 7f, y - 6f, z + 1f);
    }

    private void updateSettingsVisuals() {
        if (fovValueText == null) return;
        fovValueText.setText(String.format("%.0f", currentFov));
        sensitivityValueText.setText(String.format("%.2f", currentSensitivity));
        if (renderDistanceValueText != null) {
            renderDistanceValueText.setText(currentRenderDistance + " chunks");
        }
        if (viewBobbingButton != null) {
            String text = viewBobbingEnabled ? "View Bobbing: ON" : "View Bobbing: OFF";
            viewBobbingButton.displayName = text;
            viewBobbingButton.labelShadow.setText(text);
            viewBobbingButton.label.setText(text);
        }
        if (settingsNode != null) updateSettingsLayout();
    }

    private void setFovFromCursor() {
        float width = settings.getWidth();
        float x = inputManager.getCursorPosition().x;
        float barX = width / 2f - SLIDER_WIDTH / 2f;
        float p = (x - barX) / SLIDER_WIDTH;
        p = Math.max(0f, Math.min(1f, p));
        currentFov = SETTINGS_MIN_FOV + p * (SETTINGS_MAX_FOV - SETTINGS_MIN_FOV);
        applyFov();
        saveSettings();
        updateSettingsVisuals();
    }

    private void setSensitivityFromCursor() {
        float width = settings.getWidth();
        float x = inputManager.getCursorPosition().x;
        float barX = width / 2f - SLIDER_WIDTH / 2f;
        float p = (x - barX) / SLIDER_WIDTH;
        p = Math.max(0f, Math.min(1f, p));
        currentSensitivity = SETTINGS_MIN_SENS + p * (SETTINGS_MAX_SENS - SETTINGS_MIN_SENS);
        if (player != null) player.setMouseSensitivity(currentSensitivity);
        saveSettings();
        updateSettingsVisuals();
    }

    private void setRenderDistanceFromCursor() {
        float width = settings.getWidth();
        float x = inputManager.getCursorPosition().x;
        float barX = width / 2f - SLIDER_WIDTH / 2f;
        float p = (x - barX) / SLIDER_WIDTH;
        p = Math.max(0f, Math.min(1f, p));

        currentRenderDistance = Math.round(
                SETTINGS_MIN_RENDER_DISTANCE
                        + p * (SETTINGS_MAX_RENDER_DISTANCE - SETTINGS_MIN_RENDER_DISTANCE)
        );

        currentRenderDistance = Math.max(
                SETTINGS_MIN_RENDER_DISTANCE,
                Math.min(SETTINGS_MAX_RENDER_DISTANCE, currentRenderDistance)
        );

        if (world != null && player != null) {
            world.setRenderDistance(currentRenderDistance);
            world.refreshRenderDistance(player.getPosition());
        }

        saveSettings();
        updateSettingsVisuals();
    }

    private void toggleViewBobbing() {
        viewBobbingEnabled = !viewBobbingEnabled;
        if (player != null) {
            player.setViewBobbingEnabled(viewBobbingEnabled);
        }
        saveSettings();
        updateSettingsVisuals();
    }

    private void loadSavedSettings() {
        currentFov = clamp(
                preferences.getFloat(PREF_FOV, currentFov),
                SETTINGS_MIN_FOV,
                SETTINGS_MAX_FOV
        );

        currentSensitivity = clamp(
                preferences.getFloat(PREF_SENSITIVITY, currentSensitivity),
                SETTINGS_MIN_SENS,
                SETTINGS_MAX_SENS
        );

        currentRenderDistance = preferences.getInt(
                PREF_RENDER_DISTANCE,
                currentRenderDistance
        );
        currentRenderDistance = Math.max(
                SETTINGS_MIN_RENDER_DISTANCE,
                Math.min(SETTINGS_MAX_RENDER_DISTANCE, currentRenderDistance)
        );

        viewBobbingEnabled = preferences.getBoolean(
                PREF_VIEW_BOBBING,
                viewBobbingEnabled
        );
    }

    private void saveSettings() {
        preferences.putFloat(PREF_FOV, currentFov);
        preferences.putFloat(PREF_SENSITIVITY, currentSensitivity);
        preferences.putInt(PREF_RENDER_DISTANCE, currentRenderDistance);
        preferences.putBoolean(PREF_VIEW_BOBBING, viewBobbingEnabled);
        flushPreferences();
    }

    private void loadSavedControlBindings() {
        if (player == null) return;

        String[] actions = {
                Player.CONTROL_FORWARD,
                Player.CONTROL_BACKWARD,
                Player.CONTROL_LEFT,
                Player.CONTROL_RIGHT,
                Player.CONTROL_JUMP,
                Player.CONTROL_SPRINT
        };

        for (String action : actions) {
            String key = PREF_CONTROL_PREFIX + action;
            int savedKey = preferences.getInt(key, Integer.MIN_VALUE);
            if (savedKey != Integer.MIN_VALUE) {
                player.rebindControl(action, savedKey);
            }
        }
    }

    private void saveControlBindings() {
        if (player == null) return;

        String[] actions = {
                Player.CONTROL_FORWARD,
                Player.CONTROL_BACKWARD,
                Player.CONTROL_LEFT,
                Player.CONTROL_RIGHT,
                Player.CONTROL_JUMP,
                Player.CONTROL_SPRINT
        };

        for (String action : actions) {
            preferences.putInt(
                    PREF_CONTROL_PREFIX + action,
                    player.getControlKey(action)
            );
        }

        flushPreferences();
    }

    private void flushPreferences() {
        try {
            preferences.flush();
        } catch (BackingStoreException ignored) {
            // Still kept in the Preferences cache.
        }
    }

    private float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private void applyFov() {
        cam.setFrustumPerspective(currentFov, (float) cam.getWidth() / cam.getHeight(), 0.05f, 1000f);
    }

    private void beginRebind(String action) {
        rebindingControl = action;
        if (controlsHintText != null) controlsHintText.setText("Press a key for this control. ESC cancels.");
        updateControlsLayout();
    }

    private void finishRebind(int keyCode) {
        if (rebindingControl == null || player == null) return;
        if (keyCode == KeyInput.KEY_ESCAPE) {
            rebindingControl = null;
            updateControlsLayout();
            return;
        }
        player.rebindControl(rebindingControl, keyCode);
        saveControlBindings();
        rebindingControl = null;
        controlsHintText.setText("Click a control, then press a key. Press ESC to cancel.");
        updateControlsLayout();
    }

    // ==========================================
    // STATE CHANGES
    // ==========================================

    private void showMenu() {
        saveSettings();
        gameState = GameState.MENU;
        menuNode.setCullHint(com.jme3.scene.Spatial.CullHint.Never);
        pauseNode.setCullHint(com.jme3.scene.Spatial.CullHint.Always);
        settingsNode.setCullHint(com.jme3.scene.Spatial.CullHint.Always);
        controlsNode.setCullHint(com.jme3.scene.Spatial.CullHint.Always);
        pauseOverlay.setCullHint(com.jme3.scene.Spatial.CullHint.Always);
        if (world != null) world.setVisible(false);
        if (player != null) player.getNode().setCullHint(com.jme3.scene.Spatial.CullHint.Always);
        if (hotbarNode != null) hotbarNode.setCullHint(com.jme3.scene.Spatial.CullHint.Always);
        if (crosshair != null) crosshair.setCullHint(com.jme3.scene.Spatial.CullHint.Always);
        inputManager.setCursorVisible(true);
    }

    private void showPauseMenu() {
        saveSettings();
        gameState = GameState.PAUSED;
        menuNode.setCullHint(com.jme3.scene.Spatial.CullHint.Always);
        pauseNode.setCullHint(com.jme3.scene.Spatial.CullHint.Never);
        settingsNode.setCullHint(com.jme3.scene.Spatial.CullHint.Always);
        controlsNode.setCullHint(com.jme3.scene.Spatial.CullHint.Always);
        pauseOverlay.setCullHint(com.jme3.scene.Spatial.CullHint.Never);
        if (world != null) world.setVisible(true);
        if (player != null) {
            player.getNode().setCullHint(com.jme3.scene.Spatial.CullHint.Never);
            player.setInputEnabled(false);
            player.clearMovementInput();
        }
        inputManager.setCursorVisible(true);
    }

    private void resumeGame() {
        gameState = GameState.PLAYING;
        menuNode.setCullHint(com.jme3.scene.Spatial.CullHint.Always);
        pauseNode.setCullHint(com.jme3.scene.Spatial.CullHint.Always);
        settingsNode.setCullHint(com.jme3.scene.Spatial.CullHint.Always);
        controlsNode.setCullHint(com.jme3.scene.Spatial.CullHint.Always);
        pauseOverlay.setCullHint(com.jme3.scene.Spatial.CullHint.Always);
        if (world != null) world.setVisible(true);
        if (player != null) player.setInputEnabled(true);
        inputManager.setCursorVisible(touchControls != null && touchControls.isMouseUnlocked());
    }

    private void showSettings() {
        loadSavedSettings();
        gameState = GameState.SETTINGS;
        pauseNode.setCullHint(com.jme3.scene.Spatial.CullHint.Always);
        settingsNode.setCullHint(com.jme3.scene.Spatial.CullHint.Never);
        controlsNode.setCullHint(com.jme3.scene.Spatial.CullHint.Always);
        pauseOverlay.setCullHint(com.jme3.scene.Spatial.CullHint.Never);
        inputManager.setCursorVisible(true);
        draggingSlider = null;
        updateSettingsVisuals();
    }

    private void showControls() {
        gameState = GameState.CONTROLS;
        pauseNode.setCullHint(com.jme3.scene.Spatial.CullHint.Always);
        settingsNode.setCullHint(com.jme3.scene.Spatial.CullHint.Always);
        controlsNode.setCullHint(com.jme3.scene.Spatial.CullHint.Never);
        pauseOverlay.setCullHint(com.jme3.scene.Spatial.CullHint.Never);
        inputManager.setCursorVisible(true);
        rebindingControl = null;
        updateControlsLayout();
    }

    private void showTouchEditor() {
        gameState = GameState.TOUCH_EDIT;
        menuNode.setCullHint(com.jme3.scene.Spatial.CullHint.Always);
        pauseNode.setCullHint(com.jme3.scene.Spatial.CullHint.Always);
        settingsNode.setCullHint(com.jme3.scene.Spatial.CullHint.Always);
        controlsNode.setCullHint(com.jme3.scene.Spatial.CullHint.Always);
        pauseOverlay.setCullHint(com.jme3.scene.Spatial.CullHint.Always);
        if (world != null) world.setVisible(true);
        inputManager.setCursorVisible(true);
        // DONE in the editor saves the layout and returns to the pause menu.
        touchControls.beginEdit(this::showPauseMenu);
    }

    private void returnToTitle() {
        rebindingControl = null;
        draggingSlider = null;
        showMenu();
    }

    private void handlePauseClick() {
        Vector2f cursor = inputManager.getCursorPosition();
        for (MenuButton button : pauseButtons) {
            if (isInsideButton(button, cursor)) {
                button.action.run();
                return;
            }
        }
    }

    private void handleSettingsClick() {
        Vector2f cursor = inputManager.getCursorPosition();
        float barLeft = settings.getWidth() / 2f - SLIDER_WIDTH / 2f;
        float barRight = settings.getWidth() / 2f + SLIDER_WIDTH / 2f;

        if (cursor.x >= barLeft && cursor.x <= barRight) {
            float fovBaseY = settings.getHeight() / 2f + 95f;
            float sensBaseY = settings.getHeight() / 2f - 10f;
            float renderBaseY = settings.getHeight() / 2f - 115f;

            if (cursor.y >= fovBaseY - 10f && cursor.y <= fovBaseY + 25f) {
                draggingSlider = SliderControl.FOV;
                setFovFromCursor();
                return;
            }

            if (cursor.y >= sensBaseY - 10f && cursor.y <= sensBaseY + 25f) {
                draggingSlider = SliderControl.SENSITIVITY;
                setSensitivityFromCursor();
                return;
            }

            if (cursor.y >= renderBaseY - 10f && cursor.y <= renderBaseY + 25f) {
                draggingSlider = SliderControl.RENDER_DISTANCE;
                setRenderDistanceFromCursor();
                return;
            }
        }

        for (MenuButton button : settingsButtons) {
            if (isInsideButton(button, cursor)) {
                button.action.run();
                return;
            }
        }
    }

    private void handleControlsClick() {
        Vector2f cursor = inputManager.getCursorPosition();
        for (MenuButton button : controlButtons) {
            if (isInsideButton(button, cursor)) {
                button.action.run();
                return;
            }
        }
    }

    private void handleMenuClick() {
        Vector2f cursor = inputManager.getCursorPosition();
        for (MenuButton button : menuButtons) {
            if (isInsideButton(button, cursor)) {
                button.action.run();
                return;
            }
        }
    }

    private boolean isInsideButton(MenuButton button, Vector2f point) {
        return point.x >= button.x && point.x <= button.x + button.width
                && point.y >= button.y && point.y <= button.y + button.height;
    }

    private static class MenuButton {
        Geometry background;
        BitmapText label;
        BitmapText labelShadow;
        Runnable action;
        float x;
        float y;
        float width;
        float height;
        String controlAction;
        String displayName;
    }

    private enum SliderControl { FOV, SENSITIVITY, RENDER_DISTANCE }

    private void startGame() {
        gameState = GameState.PLAYING;
        menuNode.setCullHint(com.jme3.scene.Spatial.CullHint.Always);
        pauseNode.setCullHint(com.jme3.scene.Spatial.CullHint.Always);
        settingsNode.setCullHint(com.jme3.scene.Spatial.CullHint.Always);
        controlsNode.setCullHint(com.jme3.scene.Spatial.CullHint.Always);
        pauseOverlay.setCullHint(com.jme3.scene.Spatial.CullHint.Always);
        inputManager.setCursorVisible(touchControls != null && touchControls.isMouseUnlocked());

        if (world == null) {
            world = new World(rootNode, grassTopMaterial, grassSideMaterial, dirtMaterial, stoneMaterial, woodMaterial, woodTopMaterial, leavesMaterial);
            world.setRenderDistance(currentRenderDistance);

            player = new Player(inputManager, cam, world, grassWalkSounds);
            loadSavedControlBindings();
            rootNode.attachChild(player.getNode());
            player.setPosition(new Vector3f(8, 90, 8));
            player.setMouseSensitivity(currentSensitivity);
            player.setViewBobbingEnabled(viewBobbingEnabled);
            world.update(player.getPosition(), true);
            createCrosshair();
            createHotbar();
        } else {
            world.setRenderDistance(currentRenderDistance);
            player.setViewBobbingEnabled(viewBobbingEnabled);
        }

        world.setVisible(true);
        player.getNode().setCullHint(com.jme3.scene.Spatial.CullHint.Never);
        player.setInputEnabled(true);
        if (hotbarNode != null) {
            hotbarNode.setCullHint(com.jme3.scene.Spatial.CullHint.Never);
        }
        if (crosshair != null) {
            crosshair.setCullHint(com.jme3.scene.Spatial.CullHint.Never);
        }
    }

    // ==========================================
    // HOTBAR
    // ==========================================
    private static final int HOTBAR_PIXEL_SLOT_SIZE = 20;
    private static final int HOTBAR_PIXEL_BORDER = 1;
    private static final int HOTBAR_PIXEL_PADDING = 1;
    private static final int HOTBAR_PIXEL_CORNER_CUT = 2;
    private static final float HOTBAR_SCALE = 3f;

    private Geometry hotbarFrame;
    private Geometry hotbarHighlight;

    private void createHotbar() {
        hotbarNode = new Node("Hotbar");
        guiNode.attachChild(hotbarNode);

        int pixelBackingWidth = (HOTBAR_PIXEL_SLOT_SIZE * HOTBAR_SLOT_COUNT) + (HOTBAR_PIXEL_PADDING * 2);
        int pixelBackingHeight = HOTBAR_PIXEL_SLOT_SIZE + (HOTBAR_PIXEL_PADDING * 2);
        int pixelBarWidth = pixelBackingWidth + (HOTBAR_PIXEL_BORDER * 2);
        int pixelBarHeight = pixelBackingHeight + (HOTBAR_PIXEL_BORDER * 2);

        Texture2D frameTexture = buildHotbarFrameTexture(pixelBarWidth, pixelBarHeight);
        Material frameMaterial = new Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md");
        frameMaterial.setTexture("ColorMap", frameTexture);
        frameMaterial.getAdditionalRenderState().setBlendMode(com.jme3.material.RenderState.BlendMode.Alpha);
        Quad frameQuad = new Quad(pixelBarWidth * HOTBAR_SCALE, pixelBarHeight * HOTBAR_SCALE);
        hotbarFrame = new Geometry("HotbarFrame", frameQuad);
        hotbarFrame.setMaterial(frameMaterial);
        hotbarNode.attachChild(hotbarFrame);

        Material highlightMaterial = new Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md");
        highlightMaterial.setColor("Color", new ColorRGBA(1f, 0.9f, 0.2f, 0.45f));
        highlightMaterial.getAdditionalRenderState().setBlendMode(com.jme3.material.RenderState.BlendMode.Alpha);
        float highlightSize = (HOTBAR_PIXEL_SLOT_SIZE - 2) * HOTBAR_SCALE;
        Quad highlightQuad = new Quad(highlightSize, highlightSize);
        hotbarHighlight = new Geometry("HotbarHighlight", highlightQuad);
        hotbarHighlight.setMaterial(highlightMaterial);
        hotbarNode.attachChild(hotbarHighlight);

        updateHotbarLayout();
    }

    /** Builds the hotbar texture without java.awt (works on desktop AND Android). */
    private Texture2D buildHotbarFrameTexture(int width, int height) {
        int[] px = new int[width * height];

        int transparent = 0x00000000;
        int black = 0xFF000000;
        int lightGray = colorArgb(0.78f, 0.78f, 0.78f);
        int white = 0xFFFFFFFF;
        int slotGray = colorArgb(0.55f, 0.55f, 0.55f);

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                px[y * width + x] = isNotchedCorner(x, y, width, height, HOTBAR_PIXEL_CORNER_CUT)
                        ? transparent : black;
            }
        }

        for (int y = HOTBAR_PIXEL_BORDER; y < height - HOTBAR_PIXEL_BORDER; y++) {
            for (int x = HOTBAR_PIXEL_BORDER; x < width - HOTBAR_PIXEL_BORDER; x++) {
                px[y * width + x] = lightGray;
            }
        }

        for (int i = 0; i < HOTBAR_SLOT_COUNT; i++) {
            int slotX = HOTBAR_PIXEL_BORDER + HOTBAR_PIXEL_PADDING + (i * HOTBAR_PIXEL_SLOT_SIZE);
            int slotY = HOTBAR_PIXEL_BORDER + HOTBAR_PIXEL_PADDING;
            fillRect(px, width, slotX, slotY, HOTBAR_PIXEL_SLOT_SIZE, HOTBAR_PIXEL_SLOT_SIZE, white);
            fillRect(px, width, slotX + 1, slotY + 1, HOTBAR_PIXEL_SLOT_SIZE - 2, HOTBAR_PIXEL_SLOT_SIZE - 2, slotGray);
        }

        // jME image row 0 is the bottom row, so write rows bottom-up.
        ByteBuffer buf = BufferUtils.createByteBuffer(width * height * 4);
        for (int y = height - 1; y >= 0; y--) {
            for (int x = 0; x < width; x++) {
                int c = px[y * width + x];
                buf.put((byte) (c >> 16));
                buf.put((byte) (c >> 8));
                buf.put((byte) c);
                buf.put((byte) (c >>> 24));
            }
        }
        buf.flip();

        Texture2D texture = new Texture2D(
                new com.jme3.texture.Image(
                        com.jme3.texture.Image.Format.RGBA8,
                        width,
                        height,
                        buf,
                        ColorSpace.sRGB
                )
        );
        texture.setMagFilter(Texture.MagFilter.Nearest);
        texture.setMinFilter(Texture.MinFilter.NearestNoMipMaps);
        texture.setWrap(Texture.WrapMode.EdgeClamp);
        return texture;
    }

    private int colorArgb(float r, float g, float b) {
        int ri = Math.round(r * 255f);
        int gi = Math.round(g * 255f);
        int bi = Math.round(b * 255f);
        return (0xFF << 24) | (ri << 16) | (gi << 8) | bi;
    }

    private void fillRect(int[] px, int stride, int startX, int startY, int rectWidth, int rectHeight, int argb) {
        for (int y = startY; y < startY + rectHeight; y++) {
            for (int x = startX; x < startX + rectWidth; x++) {
                px[y * stride + x] = argb;
            }
        }
    }

    private boolean isNotchedCorner(int x, int y, int width, int height, int cut) {
        int distanceFromEdgeX = x < cut ? x : (x >= width - cut ? width - 1 - x : -1);
        int distanceFromEdgeY = y < cut ? y : (y >= height - cut ? height - 1 - y : -1);
        if (distanceFromEdgeX < 0 || distanceFromEdgeY < 0) return false;
        return (distanceFromEdgeX + distanceFromEdgeY) < cut;
    }

    private void updateHotbarLayout() {
        if (hotbarNode == null) return;
        int pixelBackingWidth = (HOTBAR_PIXEL_SLOT_SIZE * HOTBAR_SLOT_COUNT) + (HOTBAR_PIXEL_PADDING * 2);
        int pixelBarWidth = pixelBackingWidth + (HOTBAR_PIXEL_BORDER * 2);
        float screenBarWidth = pixelBarWidth * HOTBAR_SCALE;
        float screenWidth = settings.getWidth();
        float barX = (screenWidth - screenBarWidth) / 2f;
        float barY = HOTBAR_BOTTOM_MARGIN;
        hotbarFrame.setLocalTranslation(barX, barY, 0);
        updateHighlightPosition(barX, barY);
    }

    private void updateHighlightPosition(float barX, float barY) {
        float slotOriginX = barX + ((HOTBAR_PIXEL_BORDER + HOTBAR_PIXEL_PADDING) * HOTBAR_SCALE);
        float slotOriginY = barY + ((HOTBAR_PIXEL_BORDER + HOTBAR_PIXEL_PADDING) * HOTBAR_SCALE);
        float slotStep = HOTBAR_PIXEL_SLOT_SIZE * HOTBAR_SCALE;
        float highlightX = slotOriginX + (selectedSlot * slotStep) + HOTBAR_SCALE;
        float highlightY = slotOriginY + HOTBAR_SCALE;
        hotbarHighlight.setLocalTranslation(highlightX, highlightY, 1);
    }

    private void selectHotbarSlot(int index) {
        if (index < 0 || index >= HOTBAR_SLOT_COUNT) return;
        selectedSlot = index;
        if (hotbarFrame != null) {
            updateHotbarLayout();
        }
    }

    private void scrollHotbar(int wheelDelta) {
        if (wheelDelta == 0 || gameState != GameState.PLAYING || hotbarNode == null) return;

        if (wheelDelta > 0) {
            selectedSlot--;
            if (selectedSlot < 0) selectedSlot = HOTBAR_SLOT_COUNT - 1;
        } else {
            selectedSlot++;
            if (selectedSlot >= HOTBAR_SLOT_COUNT) selectedSlot = 0;
        }

        updateHotbarLayout();
    }

    // ==========================================
    // MATERIALS
    // ==========================================

    private Material createMaterial(Texture texture) {
        texture.setMagFilter(Texture.MagFilter.Nearest);
        texture.setMinFilter(Texture.MinFilter.NearestNoMipMaps);
        texture.setWrap(Texture.WrapMode.EdgeClamp);

        Material material = new Material(
                assetManager,
                "Common/MatDefs/Misc/Unshaded.j3md"
        );

        material.setTexture("ColorMap", texture);
        material.setBoolean("VertexColor", true);
        return material;
    }

    private Material createTintedMaterial(Texture texture, ColorRGBA tint) {
        texture.setMagFilter(Texture.MagFilter.Nearest);
        texture.setMinFilter(Texture.MinFilter.NearestNoMipMaps);
        texture.setWrap(Texture.WrapMode.EdgeClamp);

        Material material = new Material(
                assetManager,
                "Common/MatDefs/Misc/Unshaded.j3md"
        );

        material.setTexture("ColorMap", texture);
        material.setBoolean("VertexColor", true);
        material.setColor("Color", tint);
        return material;
    }

    private Material createLeafMaterial(Texture texture, ColorRGBA tint) {
        texture.setMagFilter(Texture.MagFilter.Nearest);
        texture.setMinFilter(Texture.MinFilter.NearestNoMipMaps);
        texture.setWrap(Texture.WrapMode.EdgeClamp);

        Material material = new Material(
                assetManager,
                "Common/MatDefs/Misc/Unshaded.j3md"
        );

        material.setTexture("ColorMap", texture);
        material.setBoolean("VertexColor", true);
        material.setColor("Color", tint);
        material.setFloat("AlphaDiscardThreshold", 0.5f);

        material.getAdditionalRenderState().setBlendMode(
                com.jme3.material.RenderState.BlendMode.Off
        );
        material.getAdditionalRenderState().setDepthWrite(true);
        material.getAdditionalRenderState().setDepthTest(true);
        material.getAdditionalRenderState().setFaceCullMode(
                com.jme3.material.RenderState.FaceCullMode.Off
        );

        return material;
    }

    // ==========================================
    // CROSSHAIR / RESHAPE / UPDATE
    // ==========================================

    private void createCrosshair() {
        BitmapFont font = assetManager.loadFont("Interface/Fonts/Default.fnt");
        crosshair = new BitmapText(font);
        crosshair.setText("+");
        crosshair.setSize(font.getCharSet().getRenderedSize());
        guiNode.attachChild(crosshair);
        updateCrosshairPosition();
    }

    private void updateCrosshairPosition() {
        if (crosshair == null) return;
        float width = settings.getWidth();
        float height = settings.getHeight();
        crosshair.setLocalTranslation(
                (width - crosshair.getLineWidth()) / 2f,
                (height + crosshair.getLineHeight()) / 2f,
                0
        );
    }

    @Override
    public void reshape(int width, int height) {
        super.reshape(width, height);
        updateCrosshairPosition();
        updateAllUiLayouts();
        updateHotbarLayout();
        if (touchControls != null) touchControls.layout(width, height);
    }

    @Override
    public void simpleUpdate(float tpf) {

        touchControls.update(tpf, gameState == GameState.PLAYING);

        if (gameState == GameState.PLAYING) {

            // Cursor is hidden/locked on PC, unless the dev mouse-unlock (F5) is on.
            inputManager.setCursorVisible(touchControls.isMouseUnlocked());

            // Real mouse-look is ignored while touch emulation / Android is active.
            player.setMouseLookEnabled(!touchControls.blocksMouse());

            if (touchControls.isEnabled()) {
                Vector2f move = touchControls.getMovement();
                player.setTouchMove(move.x, move.y);
                player.addLook(touchControls.takeLookX(), touchControls.takeLookY());

                if (touchControls.isJumpHeld()) player.jump();
                if (touchControls.consumeBreak()) breakBlock();
                if (touchControls.consumePlace()) placeBlock();

                int hotbarDelta = touchControls.consumeHotbarDelta();
                if (hotbarDelta != 0) scrollHotbar(-hotbarDelta);

                if (touchControls.consumePause()) {
                    showPauseMenu();
                    return;
                }
            } else {
                player.setTouchMove(0f, 0f);
            }

            player.update(tpf);
            world.update(player.getPosition(), false);
            updateCrosshairPosition();

        } else {
            inputManager.setCursorVisible(true);

            if (gameState == GameState.SETTINGS && draggingSlider != null) {
                if (draggingSlider == SliderControl.FOV) setFovFromCursor();
                if (draggingSlider == SliderControl.SENSITIVITY) setSensitivityFromCursor();
                if (draggingSlider == SliderControl.RENDER_DISTANCE) setRenderDistanceFromCursor();
            }

            // Keep filling the world while settings / pause are open.
            if ((gameState == GameState.SETTINGS || gameState == GameState.PAUSED)
                    && world != null && player != null) {
                world.update(player.getPosition(), false);
            }
        }
    }

    @Override
    public void onAction(String name, boolean pressed, float tpf) {
        if (name.equals("UILeftClick")) {
            if (pressed) {
                if (gameState == GameState.MENU) {
                    handleMenuClick();
                } else if (gameState == GameState.PAUSED) {
                    handlePauseClick();
                } else if (gameState == GameState.SETTINGS) {
                    handleSettingsClick();
                } else if (gameState == GameState.CONTROLS) {
                    handleControlsClick();
                }
            } else {
                draggingSlider = null;
            }
            return;
        }

        if (!pressed) return;

        if (gameState != GameState.PLAYING) return;

        if (name.equals("BreakBlock") || name.equals("PlaceBlock")) {
            // While touch emulation is active the mouse is a "finger", not a pickaxe.
            if (touchControls.blocksMouse()) return;

            if (name.equals("BreakBlock")) breakBlock();
            else placeBlock();
            return;
        }

        if (name.startsWith("HotbarSlot")) {
            int slotNumber = Integer.parseInt(name.substring("HotbarSlot".length()));
            selectHotbarSlot(slotNumber - 1);
        }
    }

    // ==========================================
    // BLOCK INTERACTION
    // ==========================================

    private BlockHit raycastBlock() {
        Vector3f origin = cam.getLocation().clone();
        Vector3f direction = cam.getDirection().clone();
        direction.normalizeLocal();
        float step = 0.05f;
        for (float distance = 0; distance <= REACH; distance += step) {
            Vector3f point = origin.add(direction.mult(distance));
            int x = (int) Math.floor(point.x);
            int y = (int) Math.floor(point.y);
            int z = (int) Math.floor(point.z);
            if (world.getBlock(x, y, z) != Chunk.AIR) {
                return new BlockHit(x, y, z);
            }
        }
        return null;
    }

    private void breakBlock() {
        BlockHit hit = raycastBlock();
        if (hit == null) return;

        byte block = world.getBlock(hit.x, hit.y, hit.z);

        if (block != Chunk.AIR) {
            world.setBlock(hit.x, hit.y, hit.z, Chunk.AIR);
            playBlockBreakSound(block);
        }
    }

    private void placeBlock() {
        byte blockToPlace = hotbarBlocks[selectedSlot];
        if (blockToPlace == Chunk.AIR) return;
        BlockHit hit = raycastBlock();
        if (hit == null) return;

        Vector3f origin = cam.getLocation().clone();
        Vector3f direction = cam.getDirection().clone();
        direction.normalizeLocal();
        int previousX = (int) Math.floor(origin.x);
        int previousY = (int) Math.floor(origin.y);
        int previousZ = (int) Math.floor(origin.z);
        float step = 0.02f;
        for (float distance = 0; distance <= REACH; distance += step) {
            Vector3f point = origin.add(direction.mult(distance));
            int x = (int) Math.floor(point.x);
            int y = (int) Math.floor(point.y);
            int z = (int) Math.floor(point.z);
            if (x == hit.x && y == hit.y && z == hit.z) {
                if (world.getBlock(previousX, previousY, previousZ) == Chunk.AIR) {
                    if (!insidePlayer(previousX, previousY, previousZ)) {
                        world.setBlock(previousX, previousY, previousZ, blockToPlace);
                        playBlockPlaceSound(blockToPlace);
                    }
                }
                return;
            }
            previousX = x;
            previousY = y;
            previousZ = z;
        }
    }

    private boolean insidePlayer(int x, int y, int z) {
        Vector3f p = player.getPosition();
        float minX = p.x - 0.3f;
        float maxX = p.x + 0.3f;
        float minZ = p.z - 0.3f;
        float maxZ = p.z + 0.3f;
        float minY = p.y;
        float maxY = p.y + 1.8f;
        return x + 1 > minX && x < maxX && z + 1 > minZ && z < maxZ && y + 1 > minY && y < maxY;
    }

    private static class BlockHit {
        final int x;
        final int y;
        final int z;

        BlockHit(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    // ==========================================
    // RAW INPUT
    // ==========================================

    @Override
    public void onKeyEvent(KeyInputEvent evt) {
        if (!evt.isPressed()) return;
        int keyCode = evt.getKeyCode();

        if (rebindingControl != null && gameState == GameState.CONTROLS) {
            finishRebind(keyCode);
            return;
        }

        if (keyCode == KeyInput.KEY_ESCAPE) {
            if (gameState == GameState.PLAYING) {
                showPauseMenu();
            } else if (gameState == GameState.PAUSED) {
                resumeGame();
            } else if (gameState == GameState.SETTINGS || gameState == GameState.CONTROLS) {
                showPauseMenu();
            } else if (gameState == GameState.TOUCH_EDIT) {
                touchControls.endEdit();
                showPauseMenu();
            }
        }
    }

    @Override public void beginInput() {}
    @Override public void endInput() {}
    @Override public void onJoyAxisEvent(JoyAxisEvent evt) {}
    @Override public void onJoyButtonEvent(JoyButtonEvent evt) {}

    @Override
    public void onMouseMotionEvent(MouseMotionEvent evt) {
        if (evt == null || evt.getDeltaWheel() == 0) return;

        if (gameState == GameState.PLAYING) {
            scrollHotbar(evt.getDeltaWheel());
        }
    }

    @Override public void onMouseButtonEvent(MouseButtonEvent evt) {}
    @Override public void onTouchEvent(TouchEvent evt) {}
}