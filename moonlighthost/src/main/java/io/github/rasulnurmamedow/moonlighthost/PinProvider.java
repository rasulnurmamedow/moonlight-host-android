package io.github.rasulnurmamedow.moonlighthost;

/**
 * Asks the user for the 4-digit PIN that Moonlight shows during pairing.
 * {@link PinDialog} is a ready-made implementation.
 */
public interface PinProvider {
    /** Called on a background thread; answer through {@code callback} from any thread. */
    void requestPin(String deviceName, PinCallback callback);

    interface PinCallback {
        /** The PIN typed by the user, or {@code null} if pairing was cancelled. */
        void onPin(String pin);
    }
}
