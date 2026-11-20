# moonlight-host-android

Turn an Android app (an emulator, a game launcher) into a **[Moonlight](https://moonlight-stream.org) host**. The game runs on the phone, and the picture, sound and controls go to Moonlight on a TV, PC, tablet or another phone. The phone itself stays as a touch controller.

- Hardware **HEVC / H.264** encoding at the client's resolution, FPS and bitrate
- **Reed-Solomon FEC** for Wi-Fi losses, no periodic key frames, Wi-Fi low-latency lock while streaming
- **Opus** audio (5 ms frames), optional audio encryption
- Input from the client: keyboard, mouse (incl. side buttons and wheel), text, up to 16 gamepads, with **rumble** sent back
- **PIN pairing** and mDNS discovery: the phone shows up in Moonlight automatically
- **No root**, no MediaProjection prompt: only the app's own game view is streamed

It speaks the GameStream protocol the way [Sunshine](https://github.com/LizardByte/Sunshine) does, so any Moonlight client works (Android/Android TV, Windows, macOS, Linux, iOS/tvOS, Steam Deck, ...).

## How it works

```
 game view (SurfaceView)  ──► private VirtualDisplay ──► MediaCodec (HEVC/H.264) ──► RTP + FEC ──┐
 your audio (PCM 48 kHz)  ──► libopus ───────────────────────────────────────────► RTP ─────────┤──► Moonlight
 your input handlers  ◄── InputListener ◄── encrypted ENet control stream ◄────────────────────────┘
```

Your game view is moved onto a private virtual display that feeds the hardware encoder. Your activity, with its touch controls and menus, stays on the phone's screen.

## Usage

```java
MoonlightHost host = new MoonlightHost.Builder(activity)
        .setHostName("MyEmulator " + Build.MODEL)   // name in Moonlight's host list
        .setAppTitle("MyEmulator")                  // the app tile inside the host
        .setInputListener(new InputListener() {
            @Override public void onKey(int windowsVk, boolean down, int modifiers) { /* ... */ }
            @Override public void onMouseMove(int dx, int dy) { /* ... */ }
            @Override public void onMouseButton(int button, boolean down) { /* ... */ }
            @Override public void onGamepad(GamepadState pad) { /* pad.buttons, sticks, triggers */ }
        })
        .build();

host.start();                 // UI thread: encoder, virtual display, servers, mDNS
host.attachView(gameView);    // show the game view on the stream instead of the phone

host.pushAudio(pcm, len);     // whenever you have sound: stereo s16le, 48 kHz, any chunk size
host.sendRumble(0, low, high);// when the game vibrates gamepad 0

// status for your UI
host.isStreaming(); host.getClientAddress(); host.getFps(); host.getBitrateMbps();

host.stop();                  // UI thread
```

### Rendering into the encoder yourself

If your renderer can draw into a second `Surface` (a second Vulkan swapchain, an EGL window surface), skip `attachView` and pass a `SurfaceListener`. You get the encoder's input surface only while a client is watching, so nothing is rendered for an empty stream:

```java
new MoonlightHost.Builder(activity)
        .setSurfaceListener(new SurfaceListener() {
            @Override public void onSurfaceCreated(Surface surface, int width, int height) {
                // start rendering the game into surface, width x height
            }
            @Override public void onSurfaceDestroyed(Surface surface) {
                // stop rendering into it before returning; it's released right after
            }
        })
        ...
```

Both calls come on the UI thread. A client that asks for another resolution or codec gets a new surface: `onSurfaceDestroyed` for the old one, then `onSurfaceCreated`.

### Pairing

The first time a client connects, Moonlight shows a PIN. By default `PinDialog` asks for it on the phone. You can pass your own `PinProvider`.

### Input

`InputListener` gets decoded events:

| Method | Meaning |
|---|---|
| `onKey(windowsVk, down, modifiers)` | Windows virtual-key codes (what Moonlight sends) |
| `onText(text)` | the client's on-screen keyboard or paste |
| `onMouseMove(dx, dy)` / `onMousePosition(x, y, w, h)` | relative or absolute pointer |
| `onMouseButton(1..5, down)` | left, middle, right, back, forward |
| `onScroll(amount)` / `onHScroll(amount)` | 120 per notch |
| `onGamepad(GamepadState)` / `onGamepadRemoved(index)` | XInput layout, Y-up sticks, `GamepadState.A`, `.LB`, ... |

## Adding it to your project

The library builds native code (ENet, libopus, nanors). The simplest way is a git submodule:

```bash
git submodule add https://github.com/rasulnurmamedow/moonlight-host-android.git external/moonlight-host-android
git submodule update --init --recursive
```

`settings.gradle`:

```groovy
include ':moonlighthost'
project(':moonlighthost').projectDir = file('external/moonlight-host-android/moonlighthost')
```

`app/build.gradle`:

```groovy
dependencies { implementation project(':moonlighthost') }
```

Requirements:
- Android 5.0+ (API 21); low-latency encoder mode is used on Android 11+
- NDK 29 and CMake 3.22.1
- ABIs: arm64-v8a, armeabi-v7a, x86_64

## Status

- Tested on a POCO F3 (Snapdragon 870), Android 16, with Moonlight 6.2 on Windows: 1080p60 HEVC, audio, keyboard, mouse, gamepads with rumble, and resolution switching from the client.
- Other encoders (MediaTek, Exynos, Tensor) are untested. Reports and fixes are welcome.
- Not supported yet: AV1, HDR, multiple apps per host, more than one simultaneous client.

First used in [Winlator Ludashi](https://github.com/StevenMXZ/Winlator-Ludashi) ("Stream to TV", [PR #765](https://github.com/StevenMXZ/Winlator-Ludashi/pull/765)).

## Credits

- Author: **Rasul Nurmamedow** ([@rasulnurmamedow](https://github.com/rasulnurmamedow))
- The protocol behaviour follows [Sunshine](https://github.com/LizardByte/Sunshine) and [moonlight-common-c](https://github.com/moonlight-stream/moonlight-common-c) (GPL-3.0)
- [ENet](https://github.com/cgutman/enet) (MIT), [libopus](https://opus-codec.org) (BSD-3-Clause), [nanors](https://github.com/sleepybishop/nanors) (MIT), included as submodules with their licenses
- Moonlight and GameStream are trademarks of their respective owners. This project is not affiliated with NVIDIA or the Moonlight project.

## License

[GPL-3.0](LICENSE)
