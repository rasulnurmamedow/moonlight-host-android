package io.github.rasulnurmamedow.moonlighthost;

import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/** Decodes Moonlight input packets (moonlight-common-c Input.h) into {@link InputListener} calls. */
final class InputDecoder {
    private static final String TAG = "MoonlightHost";

    private static final int KEY_DOWN = 0x03, KEY_UP = 0x04;
    private static final int MOUSE_ABS = 0x05, MOUSE_REL = 0x06, MOUSE_REL_GEN5 = 0x07;
    private static final int MOUSE_DOWN_GEN5 = 0x08, MOUSE_UP_GEN5 = 0x09;
    private static final int SCROLL_GEN5 = 0x0A, MULTI_CONTROLLER_GEN5 = 0x0C, HAPTICS = 0x0D;
    private static final int UTF8_TEXT = 0x17;
    private static final int SS_HSCROLL = 0x55000001;

    private final InputListener listener;
    private final GamepadState pad = new GamepadState();
    private int activeMask;

    InputDecoder(InputListener listener) {
        this.listener = listener;
    }

    /** data[off..off+len) starts with NV_INPUT_HEADER (size BE32, magic LE32). */
    void handle(byte[] data, int off, int len) {
        if (listener == null || len < 8) return;
        ByteBuffer be = ByteBuffer.wrap(data, off, len).order(ByteOrder.BIG_ENDIAN);
        ByteBuffer le = ByteBuffer.wrap(data, off, len).order(ByteOrder.LITTLE_ENDIAN);
        int magic = le.getInt(off + 4);
        int p = off + 8;
        try {
            switch (magic) {
                case KEY_DOWN:
                case KEY_UP:
                    listener.onKey(le.getShort(p + 1) & 0xFF, magic == KEY_DOWN, data[p + 3] & 0xFF);
                    break;
                case UTF8_TEXT: {
                    int textLen = be.getInt(off) - 4; // header size counts the magic
                    if (textLen > 0 && p + textLen <= off + len)
                        listener.onText(new String(data, p, textLen, StandardCharsets.UTF_8));
                    break;
                }
                case MOUSE_REL:
                case MOUSE_REL_GEN5:
                    listener.onMouseMove(be.getShort(p), be.getShort(p + 2));
                    break;
                case MOUSE_ABS:
                    listener.onMousePosition(be.getShort(p), be.getShort(p + 2),
                            (be.getShort(p + 6) & 0xFFFF) + 1, (be.getShort(p + 8) & 0xFFFF) + 1);
                    break;
                case MOUSE_DOWN_GEN5:
                case MOUSE_UP_GEN5:
                    listener.onMouseButton(data[p] & 0xFF, magic == MOUSE_DOWN_GEN5);
                    break;
                case SCROLL_GEN5:
                    listener.onScroll(be.getShort(p));
                    break;
                case SS_HSCROLL:
                    listener.onHScroll(be.getShort(p));
                    break;
                case MULTI_CONTROLLER_GEN5:
                    controller(le, p);
                    break;
                case HAPTICS: // client says it can rumble; nothing to do
                default:
                    break;
            }
        } catch (IndexOutOfBoundsException e) {
            Log.w(TAG, "short input packet, magic " + Integer.toHexString(magic));
        }
    }

    private void controller(ByteBuffer le, int p) {
        int number = le.getShort(p + 2) & 0x0F;
        int mask = le.getShort(p + 4) & 0xFFFF;
        int gone = activeMask & ~mask;
        for (int i = 0; i < 16; i++) if ((gone & (1 << i)) != 0) listener.onGamepadRemoved(i);
        activeMask = mask;
        if ((mask & (1 << number)) == 0) return;
        pad.index = number;
        pad.buttons = (le.getShort(p + 8) & 0xFFFF) | ((le.getShort(p + 22) & 0xFFFF) << 16);
        pad.leftTrigger = le.get(p + 10) & 0xFF;
        pad.rightTrigger = le.get(p + 11) & 0xFF;
        pad.leftStickX = le.getShort(p + 12);
        pad.leftStickY = le.getShort(p + 14);
        pad.rightStickX = le.getShort(p + 16);
        pad.rightStickY = le.getShort(p + 18);
        listener.onGamepad(pad);
    }

    /** Session ended: report every known pad as removed. */
    void release() {
        for (int i = 0; i < 16; i++) if ((activeMask & (1 << i)) != 0) listener.onGamepadRemoved(i);
        activeMask = 0;
    }
}
