package io.github.rasulnurmamedow.moonlighthost;

/**
 * Input coming from the Moonlight client, already decoded. Called on a network thread;
 * implementations forward it into the emulator. All methods are optional.
 */
public interface InputListener {
    /**
     * Keyboard key.
     * @param windowsVk Windows virtual-key code (0x41 = 'A', 0x10 = Shift, 0x25 = Left ...)
     * @param modifiers Moonlight modifier bits: 0x01 Shift, 0x02 Ctrl, 0x04 Alt, 0x08 Meta
     */
    default void onKey(int windowsVk, boolean down, int modifiers) {}

    /** Text typed on the client's on-screen keyboard or pasted (UTF-8 decoded). */
    default void onText(String text) {}

    /** Relative mouse movement (games / captured cursor). */
    default void onMouseMove(int dx, int dy) {}

    /** Absolute pointer position inside a reference area of refWidth x refHeight. */
    default void onMousePosition(int x, int y, int refWidth, int refHeight) {}

    /** Mouse button: 1 left, 2 middle, 3 right, 4 back (X1), 5 forward (X2). */
    default void onMouseButton(int button, boolean down) {}

    /** Vertical wheel, 120 units per notch, positive = up. High-resolution wheels send less. */
    default void onScroll(int amount) {}

    /** Horizontal wheel, 120 units per notch, positive = right. */
    default void onHScroll(int amount) {}

    /** Gamepad state changed. The same object is reused; copy fields you keep. */
    default void onGamepad(GamepadState state) {}

    /** The client unplugged the gamepad with this index. */
    default void onGamepadRemoved(int index) {}
}
