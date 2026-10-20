/*
 * This is the source code of LuxGram, a Telegram fork.
 * It is licensed under GNU GPL v. 2 or later.
 *
 * LuxGram Fix 36: Secretary Quick Tile — toggles the offline auto-responder
 * straight from the Android quick settings shade. The class only loads on
 * API 24+ (the system binds it), so older devices never verify it.
 */
package org.telegram.messenger;

import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

public class LuxGramSecretaryTile extends TileService {

    @Override
    public void onStartListening() {
        refresh();
    }

    @Override
    public void onClick() {
        try {
            int account = UserConfig.selectedAccount;
            LuxGramSecretary secretary = LuxGramSecretary.getInstance(account);
            boolean newState = !secretary.isEnabled();
            secretary.setEnabled(newState);
            LuxGramToolsWidget.notifyStateChanged(ApplicationLoader.applicationContext);
        } catch (Throwable ignore) {
        }
        refresh();
    }

    private void refresh() {
        try {
            Tile tile = getQsTile();
            if (tile == null) {
                return;
            }
            boolean on = LuxGramSecretary.getInstance(UserConfig.selectedAccount).isEnabled();
            tile.setState(on ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
            tile.updateTile();
        } catch (Throwable ignore) {
        }
    }
}
