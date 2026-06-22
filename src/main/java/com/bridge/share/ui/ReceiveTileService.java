package com.bridge.share.ui;

import android.graphics.drawable.Icon;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/**
 * Quick-settings tile that mirrors and toggles the receive-visibility setting
 * ({@link ReceivePrefs}). It is the last piece of the receive UI: a tap from the
 * notification shade arms/disarms the receiver without opening the app.
 *
 * Behaviour (mirrors the three main-page options Off / Always-on / 10-min):
 *   - Tap CYCLES OFF -> ALWAYS_ON -> TIMED -> OFF.
 *   - OFF:       tile INACTIVE, "Bridge Share: Off"        -> {@link ReceiveService#stop}.
 *   - ALWAYS_ON: tile ACTIVE,   "Bridge Share: Always on"  -> {@link ReceiveService#arm}
 *                (OS-held wake scan only; no persistent foreground service).
 *   - TIMED:     tile ACTIVE,   "Bridge Share: 10 min"     -> {@link ReceiveService#arm}
 *                (active discoverability for the 10-min window).
 * (QS tiles only expose ACTIVE/INACTIVE, so ALWAYS_ON and TIMED both show ACTIVE and are
 *  distinguished by the label.)
 *
 * The tile is refreshed from {@link ReceivePrefs#getMode} in {@link #onStartListening()}
 * so it reflects changes made elsewhere (main page, expiry alarm, etc.).
 */
public final class ReceiveTileService extends TileService {

    @Override
    public void onStartListening() {
        super.onStartListening();
        refreshTile();
    }

    @Override
    public void onClick() {
        super.onClick();
        ReceivePrefs.Mode next = nextMode(ReceivePrefs.getMode(this));
        ReceivePrefs.setMode(this, next);
        if (next == ReceivePrefs.Mode.OFF) {
            ReceiveService.stop(this);
        } else {
            // arm() dispatches: ALWAYS_ON -> OS-held wake scan only; TIMED -> active window.
            ReceiveService.arm(this);
        }
        refreshTile();
    }

    /** Cycle OFF -> ALWAYS_ON -> TIMED -> OFF (the three main-page options). */
    private static ReceivePrefs.Mode nextMode(ReceivePrefs.Mode m) {
        switch (m) {
            case OFF:       return ReceivePrefs.Mode.ALWAYS_ON;
            case ALWAYS_ON: return ReceivePrefs.Mode.TIMED;
            case TIMED:
            default:        return ReceivePrefs.Mode.OFF;
        }
    }

    /** Update the tile's label, state and icon from the persisted mode. */
    private void refreshTile() {
        Tile tile = getQsTile();
        if (tile == null) return;
        ReceivePrefs.Mode mode = ReceivePrefs.getMode(this);
        String label;
        switch (mode) {
            case ALWAYS_ON: label = "Bridge Share: Always on"; break;
            case TIMED:     label = "Bridge Share: 10 min"; break;
            case OFF:
            default:        label = "Bridge Share: Off"; break;
        }
        tile.setLabel(label);
        tile.setState(mode == ReceivePrefs.Mode.OFF ? Tile.STATE_INACTIVE : Tile.STATE_ACTIVE);
        try {
            tile.setIcon(Icon.createWithResource(this, android.R.drawable.stat_sys_upload));
        } catch (Exception ignored) {}
        tile.updateTile();
    }
}
