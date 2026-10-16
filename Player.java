package com.mygame;

import com.jme3.audio.AudioNode;
import com.jme3.input.InputManager;
import com.jme3.input.KeyInput;
import com.jme3.input.MouseInput;
import com.jme3.input.controls.ActionListener;
import com.jme3.input.controls.AnalogListener;
import com.jme3.input.controls.KeyTrigger;
import com.jme3.input.controls.MouseAxisTrigger;
import com.jme3.math.FastMath;
import com.jme3.math.Quaternion;
import com.jme3.math.Vector3f;
import com.jme3.renderer.Camera;
import com.jme3.scene.Node;

import java.util.Random;

public class Player implements ActionListener, AnalogListener {

    public static final String CONTROL_FORWARD = "PlayerForward";
    public static final String CONTROL_BACKWARD = "PlayerBackward";
    public static final String CONTROL_LEFT = "PlayerLeft";
    public static final String CONTROL_RIGHT = "PlayerRight";
    public static final String CONTROL_JUMP = "PlayerJump";
    public static final String CONTROL_SPRINT = "PlayerSprint";

    private final Node node = new Node("Player");

    private final InputManager inputManager;
    private final Camera camera;
    private final World world;
    private final AudioNode[] walkSounds;
    private final Random random = new Random();
    private int lastWalkSound = -1;

    private final Vector3f position = new Vector3f();
    private final Vector3f velocity = new Vector3f();

    private boolean forwardPressed;
    private boolean backwardPressed;
    private boolean leftPressed;
    private boolean rightPressed;
    private boolean sprintPressed;

    // Touch input (set every frame by Main when touch controls are enabled)
    private float touchMoveX = 0f; // right
    private float touchMoveY = 0f; // forward
    private boolean mouseLookEnabled = true;

    private boolean grounded;
    private boolean movingHorizontally;
    private boolean inputEnabled = true;
    private float walkSoundTimer = 0f;

    private boolean viewBobbingEnabled = true;
    private float bobTime = 0f;
    private float bobVertical = 0f;
    private float bobRoll = 0f;
    private float bobPitch = 0f;

    private static final float FOOTSTEP_INTERVAL = 0.38f;

    private float yaw = 0f;
    private float pitch = 0f;

    // ==============================
    // MOVEMENT
    // ==============================

    private static final float WALK_SPEED = 4.3f;
    private static final float SPRINT_SPEED = 5.6f;

    private static final float BOB_WALK_FREQUENCY = 8.2f;
    private static final float BOB_SPRINT_FREQUENCY = 10.0f;
    private static final float BOB_VERTICAL_AMOUNT = 0.040f;
    private static final float BOB_ROLL_AMOUNT = 0.010f;
    private static final float BOB_PITCH_AMOUNT = 0.006f;

    // ==============================
    // JUMP / GRAVITY
    // ==============================

    private static final float GRAVITY = 22f;
    private static final float JUMP_SPEED = 8f;

    // ==============================
    // PLAYER SIZE
    // ==============================

    private static final float PLAYER_WIDTH = 0.6f;
    private static final float PLAYER_HALF_WIDTH = PLAYER_WIDTH / 2f;
    private static final float PLAYER_HEIGHT = 1.8f;
    private static final float EYE_HEIGHT = 1.62f;

    // ==============================
    // MOUSE
    // ==============================

    private float mouseSensitivity = 0.25f;

    // ==============================
    // KEY BINDINGS
    // ==============================

    private int forwardKey = KeyInput.KEY_W;
    private int backwardKey = KeyInput.KEY_S;
    private int leftKey = KeyInput.KEY_A;
    private int rightKey = KeyInput.KEY_D;
    private int jumpKey = KeyInput.KEY_SPACE;
    private int sprintKey = KeyInput.KEY_LCONTROL;

    public Player(
            InputManager inputManager,
            Camera camera,
            World world,
            AudioNode[] walkSounds
    ) {
        this.inputManager = inputManager;
        this.camera = camera;
        this.world = world;
        this.walkSounds = walkSounds;

        setupInput();

        updateCamera();
    }

    private void setupInput() {

        inputManager.addMapping(CONTROL_FORWARD, new KeyTrigger(KeyInput.KEY_W));
        inputManager.addMapping(CONTROL_BACKWARD, new KeyTrigger(KeyInput.KEY_S));
        inputManager.addMapping(CONTROL_LEFT, new KeyTrigger(KeyInput.KEY_A));
        inputManager.addMapping(CONTROL_RIGHT, new KeyTrigger(KeyInput.KEY_D));
        inputManager.addMapping(CONTROL_JUMP, new KeyTrigger(KeyInput.KEY_SPACE));
        inputManager.addMapping(CONTROL_SPRINT, new KeyTrigger(KeyInput.KEY_LCONTROL));

        inputManager.addMapping("PlayerMouseX", new MouseAxisTrigger(MouseInput.AXIS_X, false));
        inputManager.addMapping("PlayerMouseXNegative", new MouseAxisTrigger(MouseInput.AXIS_X, true));
        inputManager.addMapping("PlayerMouseY", new MouseAxisTrigger(MouseInput.AXIS_Y, false));
        inputManager.addMapping("PlayerMouseYNegative", new MouseAxisTrigger(MouseInput.AXIS_Y, true));

        inputManager.addListener(
                this,
                CONTROL_FORWARD,
                CONTROL_BACKWARD,
                CONTROL_LEFT,
                CONTROL_RIGHT,
                CONTROL_JUMP,
                CONTROL_SPRINT
        );

        inputManager.addListener(
                this,
                "PlayerMouseX",
                "PlayerMouseXNegative",
                "PlayerMouseY",
                "PlayerMouseYNegative"
        );
    }

    // ==============================
    // KEYBOARD INPUT
    // ==============================

    @Override
    public void onAction(String name, boolean isPressed, float tpf) {

        if (!inputEnabled) {
            return;
        }

        switch (name) {

            case CONTROL_FORWARD:
                forwardPressed = isPressed;
                break;

            case CONTROL_BACKWARD:
                backwardPressed = isPressed;
                break;

            case CONTROL_LEFT:
                leftPressed = isPressed;
                break;

            case CONTROL_RIGHT:
                rightPressed = isPressed;
                break;

            case CONTROL_JUMP:
                if (isPressed) {
                    jump();
                }
                break;

            case CONTROL_SPRINT:
                sprintPressed = isPressed;
                break;
        }
    }

    // ==============================
    // MOUSE INPUT
    // ==============================

    @Override
    public void onAnalog(String name, float value, float tpf) {

        // Ignored while the mouse is unlocked (dev touch testing) or on touch devices.
        if (!inputEnabled || !mouseLookEnabled) {
            return;
        }

        float amount = value * mouseSensitivity;

        switch (name) {

            case "PlayerMouseX":
                yaw -= amount;
                break;

            case "PlayerMouseXNegative":
                yaw += amount;
                break;

            case "PlayerMouseY":
                pitch -= amount;
                break;

            case "PlayerMouseYNegative":
                pitch += amount;
                break;
        }

        clampPitch();
    }

    private void clampPitch() {
        float limit = FastMath.HALF_PI - 0.05f;
        pitch = FastMath.clamp(pitch, -limit, limit);
    }

    // ==============================
    // TOUCH API
    // ==============================

    /** x = right (-1..1), y = forward (-1..1). */
    public void setTouchMove(float x, float y) {
        touchMoveX = x;
        touchMoveY = y;
    }

    /** dx / dy in screen pixels dragged (y positive = up). */
    public void addLook(float dx, float dy) {
        if (!inputEnabled) {
            return;
        }

        float k = 0.004f;
        yaw -= dx * k;
        pitch -= dy * k;
        clampPitch();
    }

    public void setMouseLookEnabled(boolean enabled) {
        mouseLookEnabled = enabled;
    }

    public void jump() {
        if (inputEnabled && grounded) {
            velocity.y = JUMP_SPEED;
            grounded = false;
            walkSoundTimer = FOOTSTEP_INTERVAL;
        }
    }

    private boolean isSprinting() {
        return (sprintPressed && forwardPressed && !backwardPressed)
                || touchMoveY > 0.9f;
    }

    // ==============================
    // PLAYER UPDATE
    // ==============================

    public void update(float tpf) {

        // Wait until the chunk under the player exists, otherwise the player
        // would fall through the (not yet generated) world.
        if (!world.isLoadedAt(position.x, position.z)) {
            velocity.y = 0f;
            updateCamera();
            return;
        }

        updateHorizontalMovement(tpf);

        updateVerticalMovement(tpf);

        updateFootsteps(tpf);

        updateViewBobbing(tpf);

        updateCamera();
    }

    // ==============================
    // HORIZONTAL MOVEMENT
    // ==============================

    private void updateHorizontalMovement(float tpf) {

        Vector3f forward = camera.getDirection().clone();
        forward.y = 0f;

        if (forward.lengthSquared() > 0f) {
            forward.normalizeLocal();
        }

        Vector3f right = camera.getLeft().clone();
        right.y = 0f;

        if (right.lengthSquared() > 0f) {
            right.normalizeLocal();
            right.negateLocal();
        }

        Vector3f movement = new Vector3f();

        // W
        if (forwardPressed) {
            movement.addLocal(forward);
        }

        // S
        if (backwardPressed) {
            movement.subtractLocal(forward);
        }

        // A
        if (leftPressed) {
            movement.subtractLocal(right);
        }

        // D
        if (rightPressed) {
            movement.addLocal(right);
        }

        // Touch joystick (analog)
        if (touchMoveX != 0f || touchMoveY != 0f) {
            movement.addLocal(forward.mult(touchMoveY));
            movement.addLocal(right.mult(touchMoveX));
        }

        if (movement.lengthSquared() == 0f) {
            movingHorizontally = false;
            return;
        }

        // Prevent diagonal movement from being faster (keeps analog speed below 1).
        if (movement.lengthSquared() > 1f) {
            movement.normalizeLocal();
        }

        float speed = isSprinting() ? SPRINT_SPEED : WALK_SPEED;

        float distance = speed * tpf;

        float moveX = movement.x * distance;
        float moveZ = movement.z * distance;

        float oldX = position.x;
        float oldZ = position.z;

        moveHorizontal(moveX, moveZ);

        float movedX = position.x - oldX;
        float movedZ = position.z - oldZ;

        movingHorizontally = (movedX * movedX + movedZ * movedZ) > 0.000001f;
    }

    private void moveHorizontal(float moveX, float moveZ) {

        if (moveX != 0f) {
            float newX = position.x + moveX;

            if (!collidesAt(newX, position.y, position.z)) {
                position.x = newX;
            }
        }

        if (moveZ != 0f) {
            float newZ = position.z + moveZ;

            if (!collidesAt(position.x, position.y, newZ)) {
                position.z = newZ;
            }
        }
    }

    // ==============================
    // VERTICAL MOVEMENT
    // ==============================

    private void updateVerticalMovement(float tpf) {

        velocity.y -= GRAVITY * tpf;

        float oldY = position.y;
        float newY = position.y + velocity.y * tpf;

        // FALLING
        if (velocity.y <= 0f) {

            if (collidesAt(position.x, newY, position.z)) {

                position.y = findGroundY(position.x, position.z, oldY);

                velocity.y = 0f;

                grounded = true;

                return;
            }

            position.y = newY;

            grounded = false;

            return;
        }

        // JUMPING UP
        if (collidesAt(position.x, newY, position.z)) {

            // Hit a ceiling.
            velocity.y = 0f;

            return;
        }

        position.y = newY;

        grounded = false;
    }

    // ==============================
    // FOOTSTEP SOUND
    // ==============================

    private void updateFootsteps(float tpf) {

        if (!grounded || !movingHorizontally) {
            walkSoundTimer = 0f;
            return;
        }

        walkSoundTimer -= tpf;

        if (walkSoundTimer <= 0f) {

            playRandomWalkSound();

            walkSoundTimer = FOOTSTEP_INTERVAL;
        }
    }

    private void playRandomWalkSound() {

        if (walkSounds == null || walkSounds.length == 0) {
            return;
        }

        int index;

        if (walkSounds.length == 1) {
            index = 0;
        } else {
            do {
                index = random.nextInt(walkSounds.length);
            } while (index == lastWalkSound);
        }

        AudioNode sound = walkSounds[index];

        if (sound != null) {
            sound.playInstance();
        }

        lastWalkSound = index;
    }

    // ==============================
    // COLLISION
    // ==============================

    private boolean collidesAt(float px, float py, float pz) {

        float minX = px - PLAYER_HALF_WIDTH;
        float maxX = px + PLAYER_HALF_WIDTH;

        float minY = py;
        float maxY = py + PLAYER_HEIGHT;

        float minZ = pz - PLAYER_HALF_WIDTH;
        float maxZ = pz + PLAYER_HALF_WIDTH;

        int startX = (int) Math.floor(minX);
        int endX = (int) Math.floor(maxX - 0.001f);

        int startY = (int) Math.floor(minY);
        int endY = (int) Math.floor(maxY - 0.001f);

        int startZ = (int) Math.floor(minZ);
        int endZ = (int) Math.floor(maxZ - 0.001f);

        for (int x = startX; x <= endX; x++) {
            for (int y = startY; y <= endY; y++) {
                for (int z = startZ; z <= endZ; z++) {

                    if (world.getBlock(x, y, z) != Chunk.AIR) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    // ==============================
    // FIND GROUND
    // ==============================

    private float findGroundY(float px, float pz, float oldY) {

        float minX = px - PLAYER_HALF_WIDTH;
        float maxX = px + PLAYER_HALF_WIDTH;

        float minZ = pz - PLAYER_HALF_WIDTH;
        float maxZ = pz + PLAYER_HALF_WIDTH;

        int startX = (int) Math.floor(minX);
        int endX = (int) Math.floor(maxX - 0.001f);

        int startZ = (int) Math.floor(minZ);
        int endZ = (int) Math.floor(maxZ - 0.001f);

        int startY = (int) Math.floor(oldY + 0.01f);

        float highestGround = -Float.MAX_VALUE;

        for (int x = startX; x <= endX; x++) {
            for (int z = startZ; z <= endZ; z++) {
                for (int y = startY; y >= 0; y--) {

                    if (world.getBlock(x, y, z) != Chunk.AIR) {

                        float top = y + 1f;

                        if (top <= oldY + 0.01f && top > highestGround) {
                            highestGround = top;
                        }

                        break;
                    }
                }
            }
        }

        if (highestGround == -Float.MAX_VALUE) {
            return oldY;
        }

        return highestGround;
    }

    // ==============================
    // VIEW BOBBING
    // ==============================

    private void updateViewBobbing(float tpf) {

        boolean shouldBob = viewBobbingEnabled
                && inputEnabled
                && grounded
                && movingHorizontally;

        if (shouldBob) {
            boolean sprinting = isSprinting();

            float frequency = sprinting ? BOB_SPRINT_FREQUENCY : BOB_WALK_FREQUENCY;

            float strength = sprinting ? 0.90f : 0.75f;
            bobTime += tpf * frequency;

            bobVertical = -FastMath.abs(FastMath.sin(bobTime)) * BOB_VERTICAL_AMOUNT * strength;

            bobRoll = FastMath.cos(bobTime) * BOB_ROLL_AMOUNT * strength;

            bobPitch = FastMath.sin(bobTime * 2f) * BOB_PITCH_AMOUNT * strength;
        } else {
            float recovery = Math.min(1f, tpf * 12f);
            bobVertical += (0f - bobVertical) * recovery;
            bobRoll += (0f - bobRoll) * recovery;
            bobPitch += (0f - bobPitch) * recovery;
        }
    }

    public void setViewBobbingEnabled(boolean enabled) {
        viewBobbingEnabled = enabled;
        if (!enabled) {
            bobVertical = 0f;
            bobRoll = 0f;
            bobPitch = 0f;
        }
    }

    public boolean isViewBobbingEnabled() {
        return viewBobbingEnabled;
    }

    // ==============================
    // CAMERA
    // ==============================

    private void updateCamera() {

        Quaternion rotation = new Quaternion();

        rotation.fromAngles(pitch + bobPitch, yaw, bobRoll);

        camera.setRotation(rotation);

        camera.setLocation(
                new Vector3f(
                        position.x,
                        position.y + EYE_HEIGHT + bobVertical,
                        position.z
                )
        );
    }

    // ==============================
    // POSITION
    // ==============================

    public void setPosition(Vector3f newPosition) {

        position.set(newPosition);

        velocity.set(Vector3f.ZERO);

        grounded = false;
        bobTime = 0f;
        bobVertical = 0f;
        bobRoll = 0f;
        bobPitch = 0f;

        updateCamera();
    }

    public Vector3f getPosition() {
        return position;
    }

    public Node getNode() {
        return node;
    }

    public void setMouseSensitivity(float sensitivity) {
        mouseSensitivity = Math.max(0.01f, Math.min(3.0f, sensitivity));
    }

    public float getMouseSensitivity() {
        return mouseSensitivity;
    }

    public void setInputEnabled(boolean enabled) {
        inputEnabled = enabled;
        if (!enabled) {
            clearMovementInput();
        }
    }

    public void clearMovementInput() {
        forwardPressed = false;
        backwardPressed = false;
        leftPressed = false;
        rightPressed = false;
        sprintPressed = false;
        touchMoveX = 0f;
        touchMoveY = 0f;
        movingHorizontally = false;
        walkSoundTimer = 0f;
    }

    // ==============================
    // REBINDING
    // ==============================

    public void rebindControl(String action, int keyCode) {
        if (action == null) return;
        if (keyCode < 0) return;

        String[] actions = {
                CONTROL_FORWARD,
                CONTROL_BACKWARD,
                CONTROL_LEFT,
                CONTROL_RIGHT,
                CONTROL_JUMP,
                CONTROL_SPRINT
        };

        for (String other : actions) {
            if (other.equals(action)) continue;
            if (getControlKey(other) == keyCode) {
                int oldKey = getControlKey(action);
                setControlKeyInternal(other, oldKey);
                break;
            }
        }

        setControlKeyInternal(action, keyCode);
    }

    private void setControlKeyInternal(String action, int keyCode) {
        if (CONTROL_FORWARD.equals(action)) forwardKey = keyCode;
        if (CONTROL_BACKWARD.equals(action)) backwardKey = keyCode;
        if (CONTROL_LEFT.equals(action)) leftKey = keyCode;
        if (CONTROL_RIGHT.equals(action)) rightKey = keyCode;
        if (CONTROL_JUMP.equals(action)) jumpKey = keyCode;
        if (CONTROL_SPRINT.equals(action)) sprintKey = keyCode;
        inputManager.deleteMapping(action);
        inputManager.addMapping(action, new KeyTrigger(keyCode));
        inputManager.addListener(this, action);
    }

    public int getControlKey(String action) {
        if (CONTROL_FORWARD.equals(action)) return forwardKey;
        if (CONTROL_BACKWARD.equals(action)) return backwardKey;
        if (CONTROL_LEFT.equals(action)) return leftKey;
        if (CONTROL_RIGHT.equals(action)) return rightKey;
        if (CONTROL_JUMP.equals(action)) return jumpKey;
        if (CONTROL_SPRINT.equals(action)) return sprintKey;
        return -1;
    }
}