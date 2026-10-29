package com.tvhome.v2;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * Botao Apps (⋮⋮⋮, KEYCODE_ALL_APPS) do controle -> menu "Todos os apps" do launcher.
 * Todos os outros botoes (Netflix, Prime Video, YouTube...) seguem com o comportamento de fabrica.
 * Nao le o conteudo da tela.
 */
public class RemoteButtonsService extends AccessibilityService {

    private static final String TAG = "RemoteButtons";

    @Override
    protected boolean onKeyEvent(KeyEvent e) {
        boolean apps = e.getScanCode() == 580 || e.getKeyCode() == KeyEvent.KEYCODE_ALL_APPS;
        if (!apps) return false;
        // Consome o apertar e o soltar; age so ao soltar.
        if (e.getAction() == KeyEvent.ACTION_UP) {
            Log.i(TAG, "botao Apps -> menu de apps");
            Intent i = new Intent(this, MainActivity.class)
                    .putExtra(MainActivity.EXTRA_OPEN_APPS_MENU, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                startActivity(i);
            } catch (RuntimeException ex) {
                Log.w(TAG, "nao abriu o menu", ex);
            }
        }
        return true;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }
}
