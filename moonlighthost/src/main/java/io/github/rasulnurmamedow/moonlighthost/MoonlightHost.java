package io.github.rasulnurmamedow.moonlighthost;

import android.app.Activity;
import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.util.Log;
import android.view.View;

import java.net.InetAddress;
import java.util.Map;

/**
 * Makes an Android app a Moonlight (GameStream) host.
 *
 * <pre>
 * MoonlightHost host = new MoonlightHost.Builder(activity)
 *         .setHostName("MyEmulator " + Build.MODEL)
 *         .setAppTitle("MyEmulator")
 *         .setInputListener(myInputListener)
 *         .build();
 * host.start();                 // UI thread
 * host.attachView(gameView);    // the game's SurfaceView/TextureView, shown on the stream
 * host.pushAudio(pcm, len);     // 48 kHz stereo s16le, from any thread
 * ...
 * host.stop();
 * </pre>
 *
 * The game view is moved to a private virtual display that feeds the hardware encoder, so
 * the app's own window (touch controls, menus) stays on the phone.
 */
public final class MoonlightHost {
    private static final String TAG = "MoonlightHost";

    /** Optional callbacks about the client. Called on background threads. */
    public interface StreamListener {
        default void onStreamStarted(String clientAddress, int width, int height, int fps) {}
        default void onStreamEnded() {}
    }

    private final Activity activity;
    private final String hostName;
    private final String appTitle;
    private final PinProvider pinProvider;
    private final InputListener inputListener;
    private final StreamListener streamListener;
    private final StreamDisplay.Config videoConfig;

    private StreamDisplay display;
    private NvHttpServer server;
    private NsdManager nsd;
    private NsdManager.RegistrationListener nsdListener;
    private WifiManager.WifiLock wifiLock;
    private volatile MoonlightSession session;

    private MoonlightHost(Builder b) {
        activity = b.activity;
        hostName = b.hostName != null ? b.hostName : Build.MODEL;
        appTitle = b.appTitle != null ? b.appTitle : "Desktop";
        pinProvider = b.pinProvider != null ? b.pinProvider : new PinDialog(b.activity);
        inputListener = b.inputListener;
        streamListener = b.streamListener;
        videoConfig = new StreamDisplay.Config();
        videoConfig.width = b.width;
        videoConfig.height = b.height;
        videoConfig.fps = b.fps;
        videoConfig.bitrateMbps = b.bitrateMbps;
        videoConfig.hevc = b.hevc;
    }

    /** Starts the encoder, virtual display, HTTP(S) server and mDNS announcement. UI thread. */
    public void start() throws Exception {
        display = new StreamDisplay(activity, videoConfig);
        display.start();
        HostIdentity identity = HostIdentity.loadOrCreate(activity);
        server = new NvHttpServer(identity, pinProvider, hostName, appTitle);
        server.setLaunchHandler(launchHandler);
        server.start();
        advertise();
        Log.i(TAG, "host \"" + hostName + "\" ready on " + display.getLocalIp());
    }

    /** Shows the game view on the stream instead of the phone. UI thread, after {@link #start()}. */
    public void attachView(View gameView) {
        display.attachRendererView(gameView);
    }

    /** Game sound: interleaved stereo signed 16-bit little-endian PCM at 48 kHz, any chunk size. */
    public void pushAudio(byte[] pcm, int length) {
        MoonlightSession s = session;
        if (s != null) s.pushPcm(pcm, length);
    }

    /** Gamepad rumble back to the client (0..65535 for each motor; 0/0 stops). */
    public void sendRumble(int gamepadIndex, int lowFreqMotor, int highFreqMotor) {
        MoonlightSession s = session;
        if (s != null) s.sendRumble(gamepadIndex, lowFreqMotor, highFreqMotor);
    }

    public boolean isStreaming() {
        MoonlightSession s = session;
        return s != null && s.isPlaying();
    }

    public String getClientAddress() {
        MoonlightSession s = session;
        return s != null ? s.clientAddress() : "";
    }

    public String getLocalAddress() { return display != null ? display.getLocalIp() : "?"; }
    public String getHostName() { return hostName; }
    public int getFps() { return display != null ? display.getFps() : 0; }
    public float getBitrateMbps() { return display != null ? display.getMbps() : 0f; }
    public int getWidth() { return display != null ? display.getWidth() : 0; }
    public int getHeight() { return display != null ? display.getHeight() : 0; }

    /** Stops everything. UI thread. */
    public void stop() {
        endSession();
        if (nsd != null && nsdListener != null) {
            try { nsd.unregisterService(nsdListener); } catch (RuntimeException ignored) {}
        }
        nsdListener = null;
        if (server != null) { server.stop(); server = null; }
        if (display != null) { display.stop(); display = null; }
    }

    // ---- sessions ----

    private final NvHttpServer.LaunchHandler launchHandler = new NvHttpServer.LaunchHandler() {
        @Override
        public synchronized String onLaunch(Map<String, String> args, InetAddress local, boolean resume) {
            if (display == null || local == null) return null;
            if (args.get("rikey") == null || args.get("rikeyid") == null) return null;
            endSession();
            InputDecoder decoder = new InputDecoder(inputListener);
            MoonlightSession s = new MoonlightSession(display, new MoonlightSession.Listener() {
                @Override public void onInput(byte[] data, int off, int len) { decoder.handle(data, off, len); }
                @Override public void onSessionEnded() {
                    decoder.release();
                    releaseWifiLock();
                    if (streamListener != null) streamListener.onStreamEnded();
                }
            }, args, null);
            try {
                s.start();
            } catch (Exception e) {
                Log.e(TAG, "cannot start session", e);
                s.stop();
                return null;
            }
            session = s;
            acquireWifiLock();
            if (streamListener != null) {
                new Thread(() -> {
                    // Report once the client has finished the RTSP handshake.
                    for (int i = 0; i < 100 && session == s && !s.isPlaying(); i++) {
                        try { Thread.sleep(100); } catch (InterruptedException ignored) { return; }
                    }
                    if (session == s && s.isPlaying())
                        streamListener.onStreamStarted(s.clientAddress(), getWidth(), getHeight(), videoConfig.fps);
                }, "MoonlightHost-notify").start();
            }
            String host = local.getHostAddress();
            if (host != null && host.startsWith("::ffff:")) host = host.substring(7);
            return "rtsp://" + host + ":" + MoonlightSession.PORT_RTSP;
        }

        @Override public synchronized void onCancel() { endSession(); }

        @Override public synchronized boolean isStreaming() {
            MoonlightSession s = session;
            return s != null && s.isActive();
        }
    };

    private void endSession() {
        MoonlightSession s = session;
        session = null;
        if (s != null) s.stop();
        releaseWifiLock();
    }

    // ---- Wi-Fi: no power-save pauses while streaming ----

    @SuppressWarnings("deprecation")
    private synchronized void acquireWifiLock() {
        if (wifiLock == null) {
            WifiManager wm = (WifiManager) activity.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm == null) return;
            int mode = Build.VERSION.SDK_INT >= 29 ? WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                    : WifiManager.WIFI_MODE_FULL_HIGH_PERF;
            wifiLock = wm.createWifiLock(mode, "MoonlightHost");
            wifiLock.setReferenceCounted(false);
        }
        try { wifiLock.acquire(); } catch (RuntimeException e) { Log.w(TAG, "wifi lock: " + e); }
    }

    private synchronized void releaseWifiLock() {
        if (wifiLock != null && wifiLock.isHeld()) {
            try { wifiLock.release(); } catch (RuntimeException ignored) {}
        }
    }

    // ---- mDNS ----

    private void advertise() {
        nsd = (NsdManager) activity.getSystemService(Context.NSD_SERVICE);
        NsdServiceInfo info = new NsdServiceInfo();
        info.setServiceName(hostName);
        info.setServiceType("_nvstream._tcp");
        info.setPort(NvHttpServer.PORT_HTTP);
        nsdListener = new NsdManager.RegistrationListener() {
            @Override public void onServiceRegistered(NsdServiceInfo s) { Log.i(TAG, "mDNS registered: " + s.getServiceName()); }
            @Override public void onRegistrationFailed(NsdServiceInfo s, int err) { Log.w(TAG, "mDNS registration failed: " + err); }
            @Override public void onServiceUnregistered(NsdServiceInfo s) {}
            @Override public void onUnregistrationFailed(NsdServiceInfo s, int err) {}
        };
        try {
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, nsdListener);
        } catch (RuntimeException e) {
            Log.w(TAG, "mDNS unavailable", e);
            nsdListener = null;
        }
    }

    // ---- builder ----

    public static final class Builder {
        private final Activity activity;
        private String hostName, appTitle;
        private PinProvider pinProvider;
        private InputListener inputListener;
        private StreamListener streamListener;
        private int width = 1920, height = 1080, fps = 60, bitrateMbps = 30;
        private boolean hevc = true;

        public Builder(Activity activity) { this.activity = activity; }

        /** Name shown in Moonlight's host list (default: device model). */
        public Builder setHostName(String name) { hostName = name; return this; }
        /** Name of the single app shown inside the host (default: "Desktop"). */
        public Builder setAppTitle(String title) { appTitle = title; return this; }
        /** Where to ask for the pairing PIN (default: {@link PinDialog}). */
        public Builder setPinProvider(PinProvider provider) { pinProvider = provider; return this; }
        public Builder setInputListener(InputListener listener) { inputListener = listener; return this; }
        public Builder setStreamListener(StreamListener listener) { streamListener = listener; return this; }
        /** Initial video settings; each client's own choice replaces them when it connects. */
        public Builder setInitialVideo(int width, int height, int fps, int bitrateMbps, boolean hevc) {
            this.width = width & ~1; this.height = height & ~1; this.fps = fps;
            this.bitrateMbps = bitrateMbps; this.hevc = hevc;
            return this;
        }

        public MoonlightHost build() { return new MoonlightHost(this); }
    }
}
