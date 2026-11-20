package io.github.rasulnurmamedow.moonlighthost;

import android.view.Surface;

/**
 * For apps that render into a {@link Surface} themselves (a second swapchain, an EGL window
 * surface) instead of moving a view onto the stream with {@link MoonlightHost#attachView}.
 *
 * <p>The encoder's input surface is handed out only while a client is watching, and replaced
 * when a client asks for another resolution or codec. Both calls come on the UI thread.
 */
public interface SurfaceListener {
    /** Start rendering the game into {@code surface}, at {@code width} x {@code height}. */
    void onSurfaceCreated(Surface surface, int width, int height);

    /** Stop rendering into {@code surface}. It is released as soon as this returns. */
    void onSurfaceDestroyed(Surface surface);
}
