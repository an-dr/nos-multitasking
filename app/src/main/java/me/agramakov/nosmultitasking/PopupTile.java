package me.agramakov.nosmultitasking;

import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.widget.Toast;

public class PopupTile extends TileService {
    @Override
    public void onStartListening() {
        Tile t = getQsTile();
        if (t != null) {
            t.setState(Tile.STATE_INACTIVE);
            t.updateTile();
        }
    }

    @Override
    public void onClick() {
        if (!MultitaskService.run(MultitaskService.ACTION_POPUP)) {
            Toast.makeText(this, "Enable the accessibility service first", Toast.LENGTH_LONG).show();
        }
    }
}
