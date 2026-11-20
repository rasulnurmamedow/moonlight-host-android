package io.github.rasulnurmamedow.moonlighthost;

import android.app.Presentation;
import android.content.Context;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.Choreographer;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Inet4Address;
import java.net.NetworkInterface;
import java.nio.ByteBuffer;
import java.util.Collections;

/**
 * Feeds a hardware video encoder and hands its frames to the Moonlight session. The picture
 * comes either from the app's game view, shown on a private virtual display whose surface is
 * the encoder input (the app's own window stays on the phone), or, with a
 * {@link SurfaceListener}, from the app rendering into the encoder input surface itself.
 */
final class StreamDisplay implements MoonlightSession.VideoSource {
    private static final String TAG = "MoonlightHost";

    static final class Config {
        int width = 1920;
        int height = 1080;
        int dpi = 240;
        int fps = 60;
        int bitrateMbps = 30;
        boolean hevc = true;
    }

    private final Context context;
    private final Config config;
    // Null in view mode. Otherwise the app renders into the encoder surface while a client watches.
    private final SurfaceListener surfaceListener;
    private Surface attachedSurface;  // UI thread
    private MediaCodec encoder;
    private Surface inputSurface;
    private VirtualDisplay virtualDisplay;
    private Presentation presentation;
    private FrameLayout presentationRoot;
    private View heartbeat;
    private View rendererView;
    private Thread drainThread;
    private volatile boolean running;
    private volatile byte[] codecConfig;
    private final Object encoderLock = new Object();
    private volatile int bitrateKbps;
    private volatile MoonlightSession.FrameListener frameListener;

    StreamDisplay(Context context, Config config, SurfaceListener surfaceListener) {
        this.context = context;
        this.config = config;
        this.surfaceListener = surfaceListener;
    }

    public Config getConfig() { return config; }

    /**
     * In view mode, creates the encoder, virtual display and presentation. In surface mode the
     * encoder only exists while a client watches, made with the client's settings. UI thread.
     */
    public void start() throws IOException {
        bitrateKbps = config.bitrateMbps * 1000;
        if (surfaceListener == null) {
            createEncoder();
            buildDisplay();
        }
        running = true;
        drainThread = new Thread(this::drainLoop, "MoonlightHost-drain");
        drainThread.start();
        if (surfaceListener == null) {
            Log.i(TAG, "stream started " + config.width + "x" + config.height + "@" + config.fps
                    + " " + (config.hevc ? "HEVC" : "H.264") + " " + config.bitrateMbps + "Mbps");
        }
    }

    /** Builds and starts a new encoder from the current config (sets encoder and inputSurface). */
    private void createEncoder() throws IOException {
        String mime = config.hevc ? MediaFormat.MIMETYPE_VIDEO_HEVC : MediaFormat.MIMETYPE_VIDEO_AVC;
        MediaCodec codec = MediaCodec.createEncoderByType(mime);
        try {
            codec.configure(encoderFormat(mime, true), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        } catch (RuntimeException e) {
            // Some encoders reject optional keys; retry with the bare minimum.
            Log.w(TAG, "encoder rejected low-latency keys, retrying plain config", e);
            codec.release();
            codec = MediaCodec.createEncoderByType(mime);
            codec.configure(encoderFormat(mime, false), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        }
        Surface surface = codec.createInputSurface();
        codec.start();
        // Published only once started: the drain thread picks up whatever encoder is set.
        inputSurface = surface;
        encoder = codec;
    }

    private MediaFormat encoderFormat(String mime, boolean lowLatencyKeys) {
        MediaFormat format = MediaFormat.createVideoFormat(mime, config.width, config.height);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitrateKbps * 1000);
        format.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, config.fps);
        // No periodic key frames (big bursts on Wi-Fi): clients request one when they need it.
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 3600);
        format.setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 1_000_000L / 10);
        if (Build.VERSION.SDK_INT >= 29) format.setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0);
        if (lowLatencyKeys) {
            if (Build.VERSION.SDK_INT >= 23) format.setInteger(MediaFormat.KEY_PRIORITY, 0); // realtime
            if (Build.VERSION.SDK_INT >= 30) format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1);
        }
        return format;
    }

    /** Virtual display (fed into the current encoder surface) + presentation. UI thread. */
    private void buildDisplay() {
        DisplayManager dm = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
        // Flags 0: private display owned by this app; only our presentation is shown on it.
        virtualDisplay = dm.createVirtualDisplay("MoonlightHost", config.width, config.height,
                config.dpi, inputSurface, 0);

        presentation = new Presentation(context, virtualDisplay.getDisplay());
        presentationRoot = new FrameLayout(presentation.getContext());
        presentationRoot.setBackgroundColor(0xFF000000);
        // 1px "heartbeat" view: while a client watches, it changes every vsync so the
        // display is recomposited and the encoder gets a steady frame rate even when the
        // game picture is static (Qualcomm ignores KEY_REPEAT_PREVIOUS_FRAME_AFTER here).
        heartbeat = new View(presentation.getContext());
        presentationRoot.addView(heartbeat, new FrameLayout.LayoutParams(1, 1));
        presentation.setContentView(presentationRoot);
        if (presentation.getWindow() != null)
            presentation.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        presentation.show();
    }

    // ---- MoonlightSession.VideoSource ----

    @Override
    public void configure(int width, int height, int fps, int kbps, boolean hevc) {
        int w = Math.max(640, Math.min(3840, width)) & ~1;
        int h = Math.max(360, Math.min(2160, height)) & ~1;
        synchronized (encoderLock) {
            if (encoder == null) {
                // Surface mode with nobody watching: the encoder is made with these when a client does.
                config.width = w; config.height = h; config.hevc = hevc;
                config.fps = Math.max(10, Math.min(120, fps));
                bitrateKbps = kbps;
                return;
            }
        }
        if (w != config.width || h != config.height || hevc != config.hevc) {
            // New size or codec: new encoder + new virtual display + new presentation.
            runOnUiThreadAndWait(() -> rebuild(w, h, fps, kbps, hevc));
            return;
        }
        synchronized (encoderLock) {
            // Same size and codec: adjust on the fly, no encoder restart.
            config.fps = Math.max(10, Math.min(120, fps));
            bitrateKbps = kbps;
            Bundle b = new Bundle();
            b.putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, kbps * 1000);
            try { encoder.setParameters(b); } catch (IllegalStateException e) { Log.w(TAG, "bitrate change failed", e); }
            requestKeyFrame();
            Log.i(TAG, "encoder updated in place: " + (hevc ? "HEVC" : "H.264") + " " + config.fps
                    + "fps " + kbps + "kbps");
        }
    }

    /** UI thread: swaps encoder, virtual display and presentation, moving the game view over. */
    private void rebuild(int w, int h, int fps, int kbps, boolean hevc) {
        synchronized (encoderLock) {
            MediaCodec oldEnc = encoder;
            Surface oldSurface = inputSurface;
            VirtualDisplay oldVd = virtualDisplay;
            Presentation oldPres = presentation;
            FrameLayout oldRoot = presentationRoot;
            int ow = config.width, oh = config.height, ofps = config.fps, okbps = bitrateKbps;
            boolean ohevc = config.hevc;
            config.width = w; config.height = h; config.hevc = hevc;
            config.fps = Math.max(10, Math.min(120, fps));
            bitrateKbps = kbps;
            try {
                createEncoder();
            } catch (Exception e) {
                Log.e(TAG, "encoder for " + w + "x" + h + " failed, keeping the old stream", e);
                config.width = ow; config.height = oh; config.fps = ofps; config.hevc = ohevc; bitrateKbps = okbps;
                encoder = oldEnc; inputSurface = oldSurface;
                return;
            }
            codecConfig = null;
            if (surfaceListener != null) {
                // The app lets go of the old surface before its encoder is released.
                boolean attached = attachedSurface != null;
                updateAttachedSurface(false);
                if (attached) updateAttachedSurface(true);
            } else {
                if (rendererView != null && oldRoot != null) oldRoot.removeView(rendererView);
                try { oldPres.dismiss(); } catch (RuntimeException ignored) {}
                buildDisplay();
                if (rendererView != null) {
                    presentationRoot.addView(rendererView, 0, new FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                    heartbeat.bringToFront();
                }
                oldVd.release();
            }
            try { oldEnc.stop(); } catch (RuntimeException ignored) {}
            oldEnc.release();
            oldSurface.release();
            Log.i(TAG, "stream rebuilt: " + w + "x" + h + " " + (hevc ? "HEVC" : "H.264") + " "
                    + config.fps + "fps " + kbps + "kbps");
        }
    }

    private void runOnUiThreadAndWait(Runnable r) {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) { r.run(); return; }
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
            try { r.run(); } finally { done.countDown(); }
        });
        try { done.await(5, java.util.concurrent.TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
    }

    @Override
    public void requestIdr() { requestKeyFrame(); }

    @Override
    public void setFrameListener(MoonlightSession.FrameListener listener) {
        frameListener = listener;
        if (surfaceListener != null) {
            runOnUiThreadAndWait(() -> setWatched(listener != null));
        }
        if (listener != null) requestKeyFrame();
    }

    /** Surface mode, UI thread: the encoder and its surface exist only while a client watches. */
    private void setWatched(boolean watched) {
        synchronized (encoderLock) {
            if (watched && encoder == null && running) {
                try {
                    createEncoder();
                } catch (IOException | RuntimeException e) {
                    Log.e(TAG, "cannot start the encoder", e);
                    return;
                }
                Log.i(TAG, "encoder started " + config.width + "x" + config.height + "@" + config.fps
                        + " " + (config.hevc ? "HEVC" : "H.264") + " " + bitrateKbps + "kbps");
            }
            updateAttachedSurface(watched);
            if (!watched && encoder != null) {
                // The app has let go of the surface, so the encoder can go.
                MediaCodec old = encoder;
                Surface oldSurface = inputSurface;
                encoder = null;
                inputSurface = null;
                codecConfig = null;
                try { old.stop(); } catch (RuntimeException ignored) {}
                old.release();
                oldSurface.release();
                Log.i(TAG, "encoder stopped");
            }
        }
    }

    /** Surface mode, UI thread: hands the current encoder surface to the app, or takes it back. */
    private void updateAttachedSurface(boolean attach) {
        Surface target = attach && running ? inputSurface : null;
        if (attachedSurface == target) return;
        if (attachedSurface != null) {
            Surface old = attachedSurface;
            attachedSurface = null;
            surfaceListener.onSurfaceDestroyed(old);
        }
        if (target != null) {
            attachedSurface = target;
            surfaceListener.onSurfaceCreated(target, config.width, config.height);
        }
    }


    // ---- stats for the phone status bar ----
    private long statWindowStart;
    private int statFrames;
    private long statBytes;
    private volatile int lastFps;
    private volatile float lastMbps;

    private void countStats(int bytes) {
        long now = System.nanoTime();
        if (statWindowStart == 0) statWindowStart = now;
        statFrames++;
        statBytes += bytes;
        long dt = now - statWindowStart;
        if (dt >= 1_000_000_000L) {
            lastFps = (int) Math.round(statFrames * 1e9 / dt);
            lastMbps = (float) (statBytes * 8 * 1e3 / dt);
            statFrames = 0;
            statBytes = 0;
            statWindowStart = now;
        }
    }

    public String getLocalIp() {
        String ip = localIpv4();
        return ip != null ? ip : "?";
    }

    public int getFps() { return lastFps; }
    public float getMbps() { return lastMbps; }
    public int getWidth() { return config.width; }
    public int getHeight() { return config.height; }

    /** Shows the game renderer view on the stream display instead of the phone. */
    public void attachRendererView(View rendererView) {
        this.rendererView = rendererView;
        presentationRoot.addView(rendererView, 0, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        // Heartbeat must be above the renderer SurfaceView's hole to be composited.
        heartbeat.bringToFront();
        Choreographer.getInstance().postFrameCallback(heartbeatTick);
    }

    private final Choreographer.FrameCallback heartbeatTick = new Choreographer.FrameCallback() {
        private boolean odd;
        private long last;
        @Override public void doFrame(long frameTimeNanos) {
            if (!running || heartbeat == null) return;
            // The phone panel may run at 120 Hz; pace the heartbeat to the stream fps.
            long interval = 1_000_000_000L / config.fps - 2_000_000L;
            if (frameListener != null && frameTimeNanos - last >= interval) {
                last = frameTimeNanos;
                odd = !odd;
                heartbeat.setBackgroundColor(odd ? 0xFF000001 : 0xFF000000);
            }
            Choreographer.getInstance().postFrameCallback(this);
        }
    };

    private void requestKeyFrame() {
        MediaCodec enc = encoder;
        if (enc == null) return;
        Bundle b = new Bundle();
        b.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
        try { enc.setParameters(b); } catch (IllegalStateException ignored) {}
    }

    private void drainLoop() {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        byte[] chunk = new byte[0];
        while (running) {
            MediaCodec enc = encoder;
            if (enc == null) {
                // Surface mode, nobody watching.
                try { Thread.sleep(5); } catch (InterruptedException e) { break; }
                continue;
            }
            int index;
            try {
                index = enc.dequeueOutputBuffer(info, 20_000);
            } catch (IllegalStateException e) {
                // rebuild() stopped this encoder under us, and the next one is coming. Never give up
                // while running: without draining, the encoder stops taking frames.
                if (!running) break;
                try { Thread.sleep(5); } catch (InterruptedException ie) { break; }
                continue;
            }
            if (index < 0) continue;
            try {
                ByteBuffer buf = enc.getOutputBuffer(index);
                if (buf != null && info.size > 0) {
                    if (chunk.length < info.size) chunk = new byte[info.size];
                    buf.position(info.offset);
                    buf.get(chunk, 0, info.size);
                    boolean isConfig = (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
                    if (isConfig) {
                        byte[] cfg = new byte[info.size];
                        System.arraycopy(chunk, 0, cfg, 0, info.size);
                        codecConfig = cfg;
                    } else {
                        countStats(info.size);
                        boolean isKey = (info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;
                        MoonlightSession.FrameListener fl = frameListener;
                        if (fl != null) fl.onFrame(chunk, info.size, info.presentationTimeUs, isKey, codecConfig);
                    }
                }
                enc.releaseOutputBuffer(index, false);
            } catch (IllegalStateException swapped) {
                // The encoder was stopped by rebuild() while we held this buffer.
            } catch (RuntimeException e) {
                Log.e(TAG, "drain: frame handling failed", e);
            }
        }
    }

    public void stop() {
        if (!running && encoder == null) return;
        running = false;
        if (surfaceListener != null) {
            runOnUiThreadAndWait(() -> updateAttachedSurface(false));
        }
        if (presentation != null) {
            try { presentation.dismiss(); } catch (RuntimeException ignored) {}
            presentation = null;
        }
        if (drainThread != null) {
            try { drainThread.join(500); } catch (InterruptedException ignored) {}
        }
        if (encoder != null) {
            try { encoder.stop(); } catch (RuntimeException ignored) {}
            encoder.release();
            encoder = null;
        }
        if (virtualDisplay != null) { virtualDisplay.release(); virtualDisplay = null; }
        if (inputSurface != null) { inputSurface.release(); inputSurface = null; }
        Log.i(TAG, "stream stopped");
    }

    private static String localIpv4() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                boolean preferred = ni.getName().startsWith("wlan");
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && !a.isLoopbackAddress() && preferred)
                        return a.getHostAddress();
                }
            }
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()) return a.getHostAddress();
                }
            }
        } catch (Exception ignored) {}
        return null;
    }
}
