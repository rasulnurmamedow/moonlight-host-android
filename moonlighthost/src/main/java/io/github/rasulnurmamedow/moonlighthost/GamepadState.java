package io.github.rasulnurmamedow.moonlighthost;

/**
 * One gamepad as sent by Moonlight (XInput layout). Sticks use XInput conventions:
 * -32768..32767 with positive Y pointing <b>up</b>; triggers are 0..255.
 */
public final class GamepadState {
    public static final int UP = 0x0001, DOWN = 0x0002, LEFT = 0x0004, RIGHT = 0x0008;
    public static final int START = 0x0010, BACK = 0x0020, LS_CLICK = 0x0040, RS_CLICK = 0x0080;
    public static final int LB = 0x0100, RB = 0x0200, GUIDE = 0x0400;
    public static final int A = 0x1000, B = 0x2000, X = 0x4000, Y = 0x8000;
    /** Sunshine extensions (upper 16 bits). */
    public static final int PADDLE1 = 0x010000, PADDLE2 = 0x020000, PADDLE3 = 0x040000, PADDLE4 = 0x080000;
    public static final int TOUCHPAD = 0x100000, MISC = 0x200000;

    /** Controller number 0..15 as assigned by the client. */
    public int index;
    /** Bitmask of the constants above. */
    public int buttons;
    public int leftTrigger, rightTrigger;
    public short leftStickX, leftStickY, rightStickX, rightStickY;

    public boolean isPressed(int button) { return (buttons & button) != 0; }
}
