package com.tvhome.v2;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Apple TV style home screen: clock top-left, pills top-right, the focused dock app's name over an
 * ambient glow, a dock that fills itself with the apps you open, and every other app in a grid that
 * scrolls up on Down. Plain framework views only (no AndroidX), no network, no background work.
 */
public class MainActivity extends Activity {

    // Tamanho do dock, sugestoes, apps escondidos e largura da grade vem de res/values/config.xml.
    private int dockMax;
    private final Set<String> hidden = new HashSet<>();
    private final Set<String> dockIgnore = new HashSet<>();
    private int cols;

    static final class App {
        String pkg, label;
        ComponentName component;
        Drawable art;
        boolean isBanner, tv;
        int color;
    }

    /** Pacotes do dock, na ordem escolhida pela pessoa. Salvo em prefs ("order"). */
    private final List<String> dock = new ArrayList<>();
    /** Tirados do dock a mao: nao voltam sozinhos quando abertos de novo. */
    private final Set<String> dockRemoved = new HashSet<>();
    /** Todos os apps abriveis instalados, por pacote. */
    private final Map<String, App> installed = new HashMap<>();
    private SharedPreferences prefs;
    private boolean dockDirty;
    /** Posicao (em dock) do app sendo movido com as setas, ou -1. */
    private int moving = -1;
    private TextView moveHint;
    private FrameLayout optionsPanel;
    private View optionsAnchor;
    private static final long DAY_MS = 24 * 60 * 60 * 1000L;
    private final boolean isFireTv = "Amazon".equalsIgnoreCase(android.os.Build.MANUFACTURER);

    private final Map<String, Integer> colorCache = new HashMap<>();
    private final List<View> dockCards = new ArrayList<>();
    private final List<View> gridCards = new ArrayList<>();
    private final List<View> pills = new ArrayList<>();

    private HomeScrollView scroll;
    private LinearLayout content, dockRow;
    private FrameLayout hero;
    private LinearLayout grid;
    private View glow;
    private ImageView wallBack, wallFront;
    private View gridShade;
    private String[] wallpapers = new String[0];
    private int wallIndex = -1;
    /** Quando (elapsedRealtime) a proxima foto entra. */
    private long wallDueAt;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable rotateWallpaper = this::nextWallpaper;
    private TextView photoCredit;
    private boolean onlineWallpapers;
    /** Credito ("autor · licenca") das fotos que vem na APK, por arquivo. */
    private final Map<String, String> bundledCredits = new HashMap<>();
    private final java.util.Random random = new java.util.Random();
    private boolean fetching;
    static final long WALLPAPER_INTERVAL_MS = 10 * 60 * 1000;
    private TextView greeting, clock, ampm, date, title, wifiPill, appsLabel;
    /** Pasta com fotos enviadas pelo usuario; quando tem fotos, substitui as da APK. */
    private File userWallDir;
    private int glowColor = 0xFF3A3A3A;
    private ValueAnimator glowAnim;
    private int lastFocusedId = View.NO_ID;
    private boolean firstShow = true;
    private PopupWindow inputsPopup;

    private final BroadcastReceiver tick = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { updateClock(); updateWifi(); }
    };
    private final BroadcastReceiver packages = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { buildApps(); }
    };

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        dockMax = getResources().getInteger(R.integer.dock_max);
        hidden.addAll(Arrays.asList(getResources().getStringArray(R.array.hidden_apps)));
        hidden.add(getPackageName());
        dockIgnore.addAll(Arrays.asList(getResources().getStringArray(R.array.dock_ignore)));
        cols = getResources().getInteger(R.integer.columns);
        onlineWallpapers = getResources().getBoolean(R.bool.online_wallpapers);
        prefs = getSharedPreferences("dock", MODE_PRIVATE);
        loadDock();
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        setContentView(buildUi());
        buildApps();
        if (getIntent().getBooleanExtra(EXTRA_OPEN_APPS_MENU, false)) openAppsMenu();

        IntentFilter pf = new IntentFilter();
        pf.addAction(Intent.ACTION_PACKAGE_ADDED);
        pf.addAction(Intent.ACTION_PACKAGE_REMOVED);
        pf.addAction(Intent.ACTION_PACKAGE_CHANGED);
        pf.addDataScheme("package");
        registerReceiver(packages, pf);
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter tf = new IntentFilter(Intent.ACTION_TIME_TICK);
        tf.addAction(Intent.ACTION_TIME_CHANGED);
        tf.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        tf.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        registerReceiver(tick, tf);
        updateClock();
        updateWifi();
        View last = lastFocusedId == View.NO_ID ? null : findViewById(lastFocusedId);
        if (last == null && !dockCards.isEmpty()) last = dockCards.get(0);
        if (last != null) last.requestFocusFromTouch();
        // Instalacoes e remocoes chegam pelo receiver "packages", que fica ativo ate o onDestroy.
        // Apps abertos enquanto a tela inicial estava escondida entram no dock.
        if (syncUsage() | dockDirty) { dockDirty = false; buildApps(); }
        if (firstShow) { firstShow = false; playEntrance(); }
        // New photo on return if the current one has been up for the full interval.
        if (wallIndex < 0 || SystemClock.elapsedRealtime() >= wallDueAt) nextWallpaper();
        else scheduleWallpaper(wallDueAt - SystemClock.elapsedRealtime());
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(rotateWallpaper);
        unregisterReceiver(tick);
        if (inputsPopup != null) inputsPopup.dismiss();
        if (moving >= 0) finishMove();
        closeOptions(false);
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(packages);
        super.onDestroy();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Home pressed while already home: jump back to the first dock app.
        if (inputsPopup != null) inputsPopup.dismiss();
        if (moving >= 0) finishMove();
        closeOptions(false);
        closeAppsMenu(false);
        if (!dockCards.isEmpty()) dockCards.get(0).requestFocusFromTouch();
        scroll.smoothScrollTo(0, 0);
        if (intent.getBooleanExtra(EXTRA_OPEN_APPS_MENU, false)) openAppsMenu();
    }

    @Override
    public void onBackPressed() {
        // Voltar fecha primeiro as opcoes do app, depois o menu "Mais apps".
        if (optionsPanel != null) { closeOptions(true); return; }
        if (appsMenu != null) { closeAppsMenu(true); return; }
        // Home has nowhere to go back to; Back from the grid returns to the dock.
        if (scroll.getScrollY() > 0 && !dockCards.isEmpty()) dockCards.get(0).requestFocus();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        if (moving >= 0) {
            // Movendo um app do dock: setas trocam de lugar, OK ou Voltar soltam. Nada mais passa
            // (o OK nao pode abrir o app).
            if (e.getAction() == KeyEvent.ACTION_DOWN) {
                if (k == KeyEvent.KEYCODE_DPAD_LEFT && moving > 0) moveBy(-1);
                else if (k == KeyEvent.KEYCODE_DPAD_RIGHT && moving < dock.size() - 1) moveBy(1);
            } else if (e.getAction() == KeyEvent.ACTION_UP && (k == KeyEvent.KEYCODE_DPAD_CENTER
                    || k == KeyEvent.KEYCODE_ENTER || k == KeyEvent.KEYCODE_NUMPAD_ENTER
                    || k == KeyEvent.KEYCODE_BACK)) {
                finishMove();
            }
            return true;
        }
        // Botao Menu do controle num app: mesmas opcoes de segurar OK.
        if (k == KeyEvent.KEYCODE_MENU && optionsPanel == null) {
            View f = getCurrentFocus();
            if (f != null && f.getTag() instanceof App) {
                if (e.getAction() == KeyEvent.ACTION_UP) showOptions((App) f.getTag());
                return true;
            }
        }
        return super.dispatchKeyEvent(e);
    }

    // ---------------------------------------------------------------- layout

    private View buildUi() {
        FrameLayout root = new FrameLayout(this);
        rootView = root;
        root.setBackgroundColor(Color.BLACK);

        // Wallpaper: two stacked layers so a new photo can crossfade in over the old one.
        wallBack = wallpaperLayer();
        wallFront = wallpaperLayer();
        root.addView(wallBack, new FrameLayout.LayoutParams(-1, -1));
        root.addView(wallFront, new FrameLayout.LayoutParams(-1, -1));
        // Scrim: darker at the top (clock, pills) and bottom (dock) so text stays readable.
        View scrim = new View(this);
        scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xB3000000, 0x40000000, 0x26000000, 0x8C000000, 0xE6000000}));
        root.addView(scrim, new FrameLayout.LayoutParams(-1, -1));
        gridShade = new View(this);
        gridShade.setBackgroundColor(0xB3000000);
        gridShade.setAlpha(0f);
        root.addView(gridShade, new FrameLayout.LayoutParams(-1, -1));
        loadWallpaperList();

        glow = new View(this);
        root.addView(glow, new FrameLayout.LayoutParams(-1, -1));
        setGlow(glowColor);

        scroll = new HomeScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setFillViewport(true);
        root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setClipChildren(false);
        content.setClipToPadding(false);
        scroll.addView(content, new FrameLayout.LayoutParams(-1, -2));

        // Hero: exactly one screen tall, header on top, title + dock at the bottom.
        hero = new FrameLayout(this);
        hero.setClipChildren(false);
        int screenH = getResources().getDisplayMetrics().heightPixels;
        content.addView(hero, new LinearLayout.LayoutParams(-1, screenH));

        hero.addView(buildHeader(), lp(-1, -2, Gravity.TOP, dp(48), dp(36), dp(48), 0));

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setClipChildren(false);
        bottom.setClipToPadding(false);
        hero.addView(bottom, lp(-1, -2, Gravity.BOTTOM, dp(48), 0, dp(48), dp(30)));

        // Rodape discreto, abaixo do dock: credito da foto a esquerda, assinatura a direita.
        photoCredit = text(9, "sans-serif", 0x80FFFFFF);
        photoCredit.setSingleLine(true);
        photoCredit.setEllipsize(android.text.TextUtils.TruncateAt.END);
        photoCredit.setShadowLayer(dp(6), 0, dp(1), 0xB3000000);
        hero.addView(photoCredit, lp(dp(560), -2, Gravity.BOTTOM | Gravity.START, dp(60), 0, 0, dp(9)));
        TextView signature = text(9, "sans-serif-medium", 0x66FFFFFF);
        signature.setText(R.string.signature);
        signature.setLetterSpacing(0.08f);
        signature.setShadowLayer(dp(6), 0, dp(1), 0xB3000000);
        hero.addView(signature, lp(-2, -2, Gravity.BOTTOM | Gravity.END, 0, 0, dp(60), dp(9)));

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        title = text(34, "sans-serif-light", Color.WHITE);
        title.setShadowLayer(dp(18), 0, 0, 0x66000000);
        title.setSingleLine(true);
        titleRow.addView(title, new LinearLayout.LayoutParams(-2, -2));
        // Aparece so enquanto um app do dock esta sendo movido.
        moveHint = text(13, "sans-serif-medium", 0xE6FFFFFF);
        moveHint.setText("◀ ▶  mover     OK  soltar");
        moveHint.setShadowLayer(dp(10), 0, dp(1), 0xB3000000);
        moveHint.setVisibility(View.GONE);
        titleRow.addView(moveHint, llp(-2, -2, dp(20), dp(6), 0, 0));
        bottom.addView(titleRow, llp(-2, -2, dp(8), 0, 0, dp(14)));

        dockRow = new LinearLayout(this);
        dockRow.setOrientation(LinearLayout.HORIZONTAL);
        dockRow.setGravity(Gravity.CENTER);
        dockRow.setClipChildren(false);
        dockRow.setClipToPadding(false);
        dockRow.setPadding(dp(20), dp(22), dp(20), dp(22));
        GradientDrawable band = new GradientDrawable();
        band.setCornerRadius(dp(26));
        band.setColor(0x1FFFFFFF);
        band.setStroke(dp(1), 0x14FFFFFF);
        dockRow.setBackground(band);
        bottom.addView(dockRow, new LinearLayout.LayoutParams(-1, -2));

        appsLabel = text(11, "sans-serif-medium", 0x99FFFFFF);
        appsLabel.setText("TODOS OS APPS");
        appsLabel.setLetterSpacing(0.22f);
        content.addView(appsLabel, llp(-2, -2, dp(68), dp(8), 0, dp(18)));

        grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setClipChildren(false);
        grid.setClipToPadding(false);
        content.addView(grid, llp(-1, -2, dp(68), 0, dp(68), dp(160)));
        return root;
    }

    private View buildHeader() {
        FrameLayout header = new FrameLayout(this);
        header.setClipChildren(false);

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        // Bloco central (estilo tela de bloqueio): saudacao, relogio grande e data, todos centralizados.
        left.setGravity(Gravity.CENTER_HORIZONTAL);
        greeting = text(18, "sans-serif-light", 0xE6FFFFFF);
        greeting.setLetterSpacing(0.02f);
        greeting.setSingleLine(true);
        greeting.setGravity(Gravity.CENTER);
        left.addView(greeting, llp(-1, -2, 0, 0, 0, 0));

        LinearLayout clockRow = new LinearLayout(this);
        clockRow.setGravity(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        clock = text(84, "sans-serif-light", Color.WHITE);
        clock.setLetterSpacing(-0.02f);
        clock.setIncludeFontPadding(false);
        clockRow.addView(clock);
        ampm = text(12, "sans-serif-light", 0xCCFFFFFF);
        clockRow.addView(ampm, llp(-2, -2, dp(6), 0, 0, dp(8)));
        left.addView(clockRow, llp(-2, -2, 0, dp(2), 0, dp(2)));

        date = text(11, "sans-serif-medium", 0xB3FFFFFF);
        date.setSingleLine(true);
        date.setGravity(Gravity.CENTER);
        date.setLetterSpacing(0.22f);
        // Largura total do bloco: o Android 9 mede mal texto com letterSpacing e cortaria o fim da data.
        left.addView(date, llp(-1, -2, 0, 0, 0, 0));
        for (TextView t : new TextView[]{greeting, clock, ampm, date}) t.setShadowLayer(dp(10), 0, dp(1), 0xB3000000);
        header.addView(left, new FrameLayout.LayoutParams(dp(560), -2, Gravity.CENTER_HORIZONTAL | Gravity.TOP));

        LinearLayout right = new LinearLayout(this);
        right.setGravity(Gravity.CENTER_VERTICAL);
        right.setClipChildren(false);
        wifiPill = pill("Tela original", "⌂");
        wifiPill.setOnClickListener(v -> openStockHome());
        TextView settings = pill("Configurações", "⚙");
        settings.setOnClickListener(v -> launch(new Intent(Settings.ACTION_SETTINGS)));
        for (TextView p : new TextView[]{wifiPill, settings}) {
            right.addView(p, llp(-2, dp(30), dp(10), 0, 0, 0));
            pills.add(p);
        }
        header.addView(right, new FrameLayout.LayoutParams(-2, -2, Gravity.END | Gravity.TOP));
        return header;
    }

    private TextView pill(String label, String glyph) {
        TextView p = text(11, "sans-serif-medium", 0xE6FFFFFF);
        p.setText(glyph + "  " + label);
        p.setGravity(Gravity.CENTER);
        p.setPadding(dp(14), 0, dp(14), 0);
        p.setFocusable(true);
        p.setId(View.generateViewId());
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(15));
        bg.setColor(0x66000000);
        bg.setStroke(dp(1), 0x33FFFFFF);
        p.setBackground(bg);
        p.setOnFocusChangeListener((v, has) -> {
            bg.setColor(has ? 0xF2FFFFFF : 0x66000000);
            ((TextView) v).setTextColor(has ? 0xFF111111 : 0xE6FFFFFF);
            v.animate().scaleX(has ? 1.08f : 1f).scaleY(has ? 1.08f : 1f)
                    .setInterpolator(new OvershootInterpolator(2f)).setDuration(220).start();
            if (has) { lastFocusedId = v.getId(); scroll.smoothScrollTo(0, 0); fadeGlow(1f); }
        });
        return p;
    }

    // ---------------------------------------------------------------- apps

    private void buildApps() {
        // Lembra o app em foco: ao remover os cards, o Android passaria o foco para outro botao da tela.
        View focused = getCurrentFocus();
        String keep = focused != null && focused.getTag() instanceof App ? ((App) focused.getTag()).pkg : null;
        installed.clear();
        installed.putAll(loadApps(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER));
        // Desinstalados saem do dock.
        if (dock.retainAll(installed.keySet())) saveDock();

        // A grade mostra os apps de TV que nao estao no dock.
        List<App> rest = new ArrayList<>();
        for (App a : installed.values()) if (a.tv && !dock.contains(a.pkg)) rest.add(a);
        Collections.sort(rest, (x, y) -> x.label.compareToIgnoreCase(y.label));

        grid.removeAllViews();
        gridCards.clear();
        renderDock();

        LinearLayout row = null;
        for (int i = 0; i < rest.size(); i++) {
            if (i % cols == 0) {
                row = new LinearLayout(this);
                row.setClipChildren(false);
                row.setClipToPadding(false);
                grid.addView(row, llp(-1, -2, 0, 0, 0, dp(26)));
            }
            View tile = tile(rest.get(i), false);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1f);
            p.setMargins(i % cols == 0 ? 0 : dp(10), 0, i % cols == cols - 1 ? 0 : dp(10), 0);
            row.addView(tile, p);
        }
        // Pad the last row so tiles keep their width.
        if (row != null) for (int i = rest.size() % cols; i != 0 && i < cols; i++) {
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, 1, 1f);
            p.setMargins(dp(10), 0, i == cols - 1 ? 0 : dp(10), 0);
            row.addView(new View(this), p);
        }
        appsLabel.setVisibility(rest.isEmpty() ? View.GONE : View.VISIBLE);
        wireFocus();

        App first = (App) dockCards.get(0).getTag();
        title.setText(first.label);
        setGlow(first.color);
        View target = null;
        if (keep != null) {
            for (View c : dockCards) if (keep.equals(((App) c.getTag()).pkg)) target = c;
            for (View c : gridCards) if (target == null && keep.equals(((App) c.getTag()).pkg)) target = c;
        } else if (lastFocusedId != View.NO_ID) {
            target = findViewById(lastFocusedId);
        }
        (target != null ? target : dockCards.get(0)).requestFocusFromTouch();
    }

    /** Apps abriveis, um por pacote. A primeira categoria ganha (a de TV traz o banner). */
    private Map<String, App> loadApps(String... categories) {
        PackageManager pm = getPackageManager();
        Map<String, App> byPkg = new HashMap<>();
        for (String cat : categories) {
            for (ResolveInfo ri : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(cat), 0)) {
                ActivityInfo ai = ri.activityInfo;
                if (hidden.contains(ai.packageName) || byPkg.containsKey(ai.packageName)) continue;
                App a = new App();
                a.pkg = ai.packageName;
                a.label = String.valueOf(ri.loadLabel(pm));
                a.component = new ComponentName(ai.packageName, ai.name);
                // No Fire TV muitos apps de TV so se registram como LAUNCHER: la, todos vao para a grade.
                a.tv = isFireTv || Intent.CATEGORY_LEANBACK_LAUNCHER.equals(cat);
                Drawable banner = ai.loadBanner(pm);
                if (banner == null) banner = ai.applicationInfo.loadBanner(pm);
                a.isBanner = banner != null;
                a.art = banner != null ? banner : ri.loadIcon(pm);
                a.color = sampleColor(a.pkg, a.art);
                byPkg.put(a.pkg, a);
            }
        }
        return byPkg;
    }

    /** Refaz so a faixa do dock (apps na ordem de dock + "Mais apps"). Chamar wireFocus() depois. */
    private void renderDock() {
        List<App> apps = new ArrayList<>();
        for (String p : dock) apps.add(installed.get(p));
        apps.add(moreAppsTile());
        dockRow.removeAllViews();
        dockCards.clear();
        // Com poucos apps, cada card ocupa 1/4 da faixa (centralizado) em vez de esticar ate a borda.
        int bandInner = getResources().getDisplayMetrics().widthPixels - dp(48) * 2 - dp(20) * 2;
        int fixedW = (bandInner - dp(20) * 3) / 4;
        for (int i = 0; i < apps.size(); i++) {
            View tile = tile(apps.get(i), true);
            LinearLayout.LayoutParams p = apps.size() < 4
                    ? new LinearLayout.LayoutParams(fixedW, -2)
                    : new LinearLayout.LayoutParams(0, -2, 1f);
            p.setMargins(i == 0 ? 0 : dp(10), 0, i == apps.size() - 1 ? 0 : dp(10), 0);
            dockRow.addView(tile, p);
        }
    }

    // ---------------------------------------------------------------- dock que aprende

    private void loadDock() {
        dock.clear();
        String saved = prefs.getString("order", null);
        if (saved == null) {
            // Primeira vez: as sugestoes instaladas, e os apps usados na ultima semana (se houver acesso a uso).
            for (String p : getResources().getStringArray(R.array.dock_suggestions))
                if (isInstalled(p) && !hidden.contains(p) && dock.size() < dockMax) dock.add(p);
            prefs.edit().putLong("usageSince", System.currentTimeMillis() - 7 * DAY_MS).apply();
            saveDock();
        } else if (!saved.isEmpty()) {
            dock.addAll(Arrays.asList(saved.split(",")));
        }
        dockRemoved.addAll(prefs.getStringSet("removed", Collections.emptySet()));
    }

    private void saveDock() {
        prefs.edit().putString("order", android.text.TextUtils.join(",", dock))
                .putStringSet("removed", new HashSet<>(dockRemoved)).apply();
    }

    /**
     * Um app foi aberto: entra no fim do dock. Com o dock cheio, sai o usado ha mais tempo.
     * Devolve true se o dock mudou.
     */
    private boolean noteOpened(String pkg, long when) {
        if (pkg == null || hidden.contains(pkg) || !installed.containsKey(pkg)) return false;
        prefs.edit().putLong("used:" + pkg, when).apply();
        if (dock.contains(pkg) || dockRemoved.contains(pkg) || dockIgnore.contains(pkg)) return false;
        addToDock(pkg);
        return true;
    }

    private void addToDock(String pkg) {
        if (dock.contains(pkg)) return;
        if (dock.size() >= dockMax) {
            String oldest = dock.get(0);
            for (String p : dock) if (prefs.getLong("used:" + p, 0) < prefs.getLong("used:" + oldest, 0)) oldest = p;
            dock.remove(oldest);
        }
        dock.add(pkg);
        saveDock();
    }

    /**
     * Le os apps abertos desde a ultima vez, inclusive pelos botoes do controle. Precisa do acesso a uso
     * (o install-launcher.sh libera); sem ele o dock aprende so com o que e aberto pela tela inicial.
     */
    private boolean syncUsage() {
        long now = System.currentTimeMillis();
        long since = prefs.getLong("usageSince", now);
        prefs.edit().putLong("usageSince", now).apply();
        if (since >= now) return false;
        boolean changed = false;
        try {
            UsageStatsManager usm = (UsageStatsManager) getSystemService(USAGE_STATS_SERVICE);
            UsageEvents events = usm == null ? null : usm.queryEvents(since, now);
            UsageEvents.Event e = new UsageEvents.Event();
            while (events != null && events.hasNextEvent()) {
                events.getNextEvent(e);
                if (e.getEventType() == UsageEvents.Event.MOVE_TO_FOREGROUND)
                    changed |= noteOpened(e.getPackageName(), e.getTimeStamp());
            }
        } catch (RuntimeException ignored) {
            // Sem permissao: segue sem.
        }
        return changed;
    }

    // ---------------------------------------------------------------- opcoes do app

    /** Segurar OK (ou o botao Menu) num app: mover, tirar ou colocar no dock. */
    private void showOptions(App app) {
        if (MORE_APPS.equals(app.pkg) || optionsPanel != null || moving >= 0) return;
        optionsAnchor = getCurrentFocus();
        int index = dock.indexOf(app.pkg);

        optionsPanel = new FrameLayout(this);
        optionsPanel.setBackgroundColor(0xB3000000);
        optionsPanel.setClickable(true);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(28), dp(24), dp(28), dp(24));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(20));
        bg.setColor(0xF21C1C1E);
        bg.setStroke(dp(1), 0x1FFFFFFF);
        box.setBackground(bg);
        optionsPanel.addView(box, new FrameLayout.LayoutParams(dp(340), -2, Gravity.CENTER));

        TextView head = text(20, "sans-serif-light", Color.WHITE);
        head.setText(app.label);
        head.setSingleLine(true);
        head.setEllipsize(android.text.TextUtils.TruncateAt.END);
        box.addView(head, llp(-1, -2, 0, 0, 0, dp(16)));

        List<TextView> buttons = new ArrayList<>();
        if (index >= 0) {
            // Mover so na tela inicial, onde o dock esta visivel.
            if (appsMenu == null) buttons.add(optionButton("Mover", () -> { closeOptions(false); startMove(index); }));
            buttons.add(optionButton("Tirar do menu de baixo", () -> {
                dock.remove(app.pkg);
                dockRemoved.add(app.pkg);
                saveDock();
                closeOptions(appsMenu != null);
                buildApps();
                if (appsMenu == null) dockCards.get(Math.min(index, dockCards.size() - 1)).requestFocus();
            }));
        } else {
            buttons.add(optionButton("Colocar no menu de baixo", () -> {
                dockRemoved.remove(app.pkg);
                addToDock(app.pkg);
                closeOptions(appsMenu != null);
                buildApps();
                if (appsMenu == null) dockCards.get(dock.indexOf(app.pkg)).requestFocus();
            }));
        }
        buttons.add(optionButton("Cancelar", () -> closeOptions(true)));
        for (TextView b : buttons) box.addView(b, llp(-1, dp(40), 0, dp(6), 0, 0));

        // Foco preso no painel, inclusive por cima do menu "Mais apps".
        scroll.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        if (appsMenu != null) appsMenu.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        rootView.addView(optionsPanel, new FrameLayout.LayoutParams(-1, -1));
        optionsPanel.setAlpha(0f);
        optionsPanel.animate().alpha(1f).setDuration(160).start();
        buttons.get(0).requestFocus();
    }

    private TextView optionButton(String label, Runnable action) {
        TextView b = text(14, "sans-serif-medium", 0xE6FFFFFF);
        b.setText(label);
        b.setGravity(Gravity.CENTER_VERTICAL);
        b.setPadding(dp(16), 0, dp(16), 0);
        b.setFocusable(true);
        b.setClickable(true);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(12));
        bg.setColor(0x14FFFFFF);
        b.setBackground(bg);
        b.setOnFocusChangeListener((v, has) -> {
            bg.setColor(has ? 0xF2FFFFFF : 0x14FFFFFF);
            b.setTextColor(has ? 0xFF111111 : 0xE6FFFFFF);
        });
        b.setOnClickListener(v -> action.run());
        return b;
    }

    private void closeOptions(boolean restoreFocus) {
        if (optionsPanel == null) return;
        rootView.removeView(optionsPanel);
        optionsPanel = null;
        if (appsMenu != null) appsMenu.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        else scroll.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        if (restoreFocus && optionsAnchor != null) optionsAnchor.requestFocus();
        optionsAnchor = null;
    }

    // ---------------------------------------------------------------- mover no dock

    private void startMove(int index) {
        moving = index;
        moveHint.setVisibility(View.VISIBLE);
        showMove();
    }

    private void moveBy(int delta) {
        Collections.swap(dock, moving, moving + delta);
        moving += delta;
        renderDock();
        wireFocus();
        showMove();
    }

    /** O app em movimento fica em foco e aceso; os outros do dock ficam apagados. */
    private void showMove() {
        dockCards.get(moving).requestFocus();
        for (int i = 0; i < dockCards.size(); i++) dockCards.get(i).setAlpha(i == moving ? 1f : 0.4f);
    }

    private void finishMove() {
        moving = -1;
        saveDock();
        moveHint.setVisibility(View.GONE);
        for (View c : dockCards) c.setAlpha(1f);
    }

    /** A 16:9 card with the app's banner; the name shows under it while focused. */
    private View tile(App app, boolean inDock) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setClipChildren(false);
        box.setClipToPadding(false);

        FrameLayout card = new AspectFrame(this);
        card.setId(View.generateViewId());
        card.setFocusable(true);
        card.setClickable(true);
        card.setTag(app);
        card.setClipToOutline(true);
        card.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(10));
            }
        });
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(app.isBanner ? 0xFF1C1C1E : darken(app.color, 0.45f));
        card.setBackground(bg);

        ImageView img = new ImageView(this);
        img.setImageDrawable(app.art);
        if (app.isBanner) {
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            card.addView(img, new FrameLayout.LayoutParams(-1, -1));
        } else {
            img.setScaleType(ImageView.ScaleType.FIT_CENTER);
            card.addView(img, new FrameLayout.LayoutParams(-1, -1));
            img.setPadding(dp(22), dp(14), dp(22), dp(14));
        }

        // Focus ring and sheen, drawn above the art.
        GradientDrawable ring = new GradientDrawable();
        ring.setCornerRadius(dp(10));
        ring.setStroke(dp(2), 0xE6FFFFFF);
        ring.setOrientation(GradientDrawable.Orientation.TL_BR);
        ring.setColors(new int[]{0x26FFFFFF, 0x00FFFFFF, 0x00FFFFFF});
        View shine = new View(this);
        shine.setBackground(ring);
        shine.setAlpha(0f);
        card.addView(shine, new FrameLayout.LayoutParams(-1, -1));

        box.addView(card, new LinearLayout.LayoutParams(-1, -2));

        TextView label = text(11, "sans-serif-medium", Color.WHITE);
        label.setText(app.label);
        label.setSingleLine(true);
        label.setGravity(Gravity.CENTER);
        label.setAlpha(0f);
        if (!inDock) box.addView(label, llp(-1, -2, 0, dp(10), 0, 0));

        card.setOnClickListener(v -> {
            if (MORE_APPS.equals(app.pkg)) { openAppsMenu(); return; }
            boolean ok = launch(new Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                    .setComponent(app.component)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
            if (ok && noteOpened(app.pkg, System.currentTimeMillis())) dockDirty = true;
        });
        card.setOnLongClickListener(v -> { showOptions(app); return true; });
        card.setOnFocusChangeListener((v, has) -> {
            float s = has ? (inDock ? 1.12f : 1.1f) : 1f;
            v.animate().scaleX(s).scaleY(s).translationZ(has ? dp(14) : 0)
                    .setInterpolator(has ? new OvershootInterpolator(1.8f) : new DecelerateInterpolator())
                    .setDuration(has ? 320 : 200).start();
            shine.animate().alpha(has ? 1f : 0f).setDuration(160).start();
            label.animate().alpha(has ? 1f : 0f).translationY(has ? dp(4) : 0).setDuration(200).start();
            if (!has) return;
            lastFocusedId = v.getId();
            if (inDock) {
                title.setText(app.label);
                title.animate().alpha(1f).setDuration(200).start();
                animateGlow(app.color);
                fadeGlow(1f);
                scroll.smoothScrollTo(0, 0);
            } else {
                fadeGlow(0.35f);
                title.animate().alpha(0f).setDuration(200).start();
                scrollToRow(box);
            }
        });

        (inDock ? dockCards : gridCards).add(card);
        return box;
    }

    /** Explicit D-pad targets: one row per press, ends of rows and of the top bar don't wrap. */
    private void wireFocus() {
        int d = dockCards.size(), g = gridCards.size();
        for (int i = 0; i < pills.size(); i++) {
            View p = pills.get(i);
            p.setNextFocusLeftId(i == 0 ? p.getId() : pills.get(i - 1).getId());
            p.setNextFocusRightId(i == pills.size() - 1 ? p.getId() : pills.get(i + 1).getId());
            p.setNextFocusUpId(p.getId());
            p.setNextFocusDownId(d > 0 ? dockCards.get(Math.min(d - 1, d / 2)).getId() : p.getId());
        }
        int topId = pills.get(0).getId();
        for (int i = 0; i < d; i++) {
            View c = dockCards.get(i);
            c.setNextFocusUpId(topId);
            c.setNextFocusLeftId(i == 0 ? c.getId() : dockCards.get(i - 1).getId());
            c.setNextFocusRightId(i == d - 1 ? c.getId() : dockCards.get(i + 1).getId());
            c.setNextFocusDownId(g > 0 ? gridCards.get(Math.min(i, g - 1)).getId() : c.getId());
        }
        int lastRow = (g - 1) / cols;
        for (int j = 0; j < g; j++) {
            View c = gridCards.get(j);
            int r = j / cols, col = j % cols;
            c.setNextFocusUpId(r == 0
                    ? (d > 0 ? dockCards.get(Math.min(col, d - 1)).getId() : topId)
                    : gridCards.get(j - cols).getId());
            c.setNextFocusDownId(j + cols < g ? gridCards.get(j + cols).getId()
                    : r < lastRow ? gridCards.get(g - 1).getId() : c.getId());
            c.setNextFocusLeftId(col == 0 ? c.getId() : gridCards.get(j - 1).getId());
            c.setNextFocusRightId(col == cols - 1 || j == g - 1 ? c.getId() : gridCards.get(j + 1).getId());
        }
    }

    private void scrollToRow(View tileBox) {
        View row = (View) tileBox.getParent();
        int y = grid.getTop() + row.getTop();
        int screenH = getResources().getDisplayMetrics().heightPixels;
        // First grid row: slide the dock band up to the top edge; later rows centre on screen.
        View band = (View) dockRow.getParent();
        int target = grid.indexOfChild(row) == 0
                ? hero.getTop() + band.getTop() + dockRow.getTop() - dp(22)
                : y - (int) (screenH * 0.42f);
        int max = Math.max(0, content.getHeight() - scroll.getHeight());
        scroll.smoothScrollTo(0, Math.max(0, Math.min(target, max)));
    }

    // ---------------------------------------------------------------- mais apps

    static final String MORE_APPS = "__mais_apps__";
    /** Enviado pelo RemoteButtonsService quando o botao Apps (⋮⋮⋮) do controle e apertado. */
    static final String EXTRA_OPEN_APPS_MENU = "open_apps_menu";
    private FrameLayout rootView;
    private FrameLayout appsMenu;
    private View moreCard;

    /** Tile do dock que abre o menu com todos os apps instalados. */
    private App moreAppsTile() {
        App a = new App();
        a.pkg = MORE_APPS;
        a.label = "Mais apps";
        a.art = new MoreAppsGlyph();
        a.isBanner = false;
        a.color = 0xFF5A6B85;
        return a;
    }

    /** Menu em tela cheia (vidro escuro) com todos os apps abriveis, em ordem alfabetica. */
    private void openAppsMenu() {
        if (appsMenu != null) return;
        moreCard = getCurrentFocus();
        List<App> apps = new ArrayList<>(installed.values());
        Collections.sort(apps, (x, y) -> x.label.compareToIgnoreCase(y.label));

        appsMenu = new FrameLayout(this);
        appsMenu.setBackgroundColor(0xF5070A12);
        appsMenu.setClickable(true);

        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        sv.setClipToPadding(false);
        // Sem isso o card focado (que cresce 8%) e cortado nas bordas da grade.
        sv.setClipChildren(false);
        sv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        sv.setPadding(dp(64), dp(40), dp(64), dp(48));
        appsMenu.addView(sv, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setClipChildren(false);
        sv.addView(col, new FrameLayout.LayoutParams(-1, -2));

        TextView head = text(26, "sans-serif-light", Color.WHITE);
        head.setText("Todos os apps");
        col.addView(head, llp(-2, -2, 0, 0, 0, dp(4)));
        TextView sub = text(12, "sans-serif", 0x99FFFFFF);
        sub.setText(apps.size() + " apps instalados  ·  Segure OK para colocar no menu de baixo  ·  Voltar para fechar");
        col.addView(sub, llp(-2, -2, 0, 0, 0, dp(24)));

        int menuCols = 5;
        List<View> cards = new ArrayList<>();
        LinearLayout row = null;
        for (int i = 0; i < apps.size(); i++) {
            if (i % menuCols == 0) {
                row = new LinearLayout(this);
                row.setClipChildren(false);
                row.setClipToPadding(false);
                col.addView(row, llp(-1, -2, 0, 0, 0, dp(22)));
            }
            View box = menuTile(apps.get(i), cards);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1f);
            p.setMargins(i % menuCols == 0 ? 0 : dp(10), 0, i % menuCols == menuCols - 1 ? 0 : dp(10), 0);
            row.addView(box, p);
        }
        // Completa a ultima linha para os cards manterem a largura.
        if (row != null) for (int i = apps.size() % menuCols; i != 0 && i < menuCols; i++) {
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, 1, 1f);
            p.setMargins(dp(10), 0, i == menuCols - 1 ? 0 : dp(10), 0);
            row.addView(new View(this), p);
        }

        // Foco preso no menu: as setas nao escapam para a tela de tras.
        scroll.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        rootView.addView(appsMenu, new FrameLayout.LayoutParams(-1, -1));
        appsMenu.setAlpha(0f);
        appsMenu.animate().alpha(1f).setDuration(220).start();
        if (!cards.isEmpty()) cards.get(0).requestFocusFromTouch();
    }

    private void closeAppsMenu(boolean restoreFocus) {
        if (appsMenu == null) return;
        rootView.removeView(appsMenu);
        appsMenu = null;
        scroll.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        if (restoreFocus && moreCard != null) moreCard.requestFocus();
    }

    /** Card do menu: banner (ou icone) com o nome sempre visivel embaixo. */
    private View menuTile(App app, List<View> cards) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setClipChildren(false);

        FrameLayout card = new AspectFrame(this);
        card.setFocusable(true);
        card.setClickable(true);
        card.setTag(app);
        card.setClipToOutline(true);
        card.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(10));
            }
        });
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(app.isBanner ? 0xFF1C1C1E : darken(app.color, 0.45f));
        card.setBackground(bg);
        ImageView img = new ImageView(this);
        img.setImageDrawable(app.art);
        img.setScaleType(app.isBanner ? ImageView.ScaleType.CENTER_CROP : ImageView.ScaleType.FIT_CENTER);
        if (!app.isBanner) img.setPadding(dp(22), dp(14), dp(22), dp(14));
        card.addView(img, new FrameLayout.LayoutParams(-1, -1));
        GradientDrawable ring = new GradientDrawable();
        ring.setCornerRadius(dp(10));
        ring.setStroke(dp(2), 0xE6FFFFFF);
        View shine = new View(this);
        shine.setBackground(ring);
        shine.setAlpha(0f);
        card.addView(shine, new FrameLayout.LayoutParams(-1, -1));
        box.addView(card, new LinearLayout.LayoutParams(-1, -2));

        TextView label = text(12, "sans-serif", 0xCCFFFFFF);
        label.setText(app.label);
        label.setSingleLine(true);
        label.setEllipsize(android.text.TextUtils.TruncateAt.END);
        label.setGravity(Gravity.CENTER);
        box.addView(label, llp(-1, -2, 0, dp(10), 0, 0));

        card.setOnClickListener(v -> {
            closeAppsMenu(false);
            boolean ok = launch(new Intent(Intent.ACTION_MAIN).setComponent(app.component)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
            if (ok && noteOpened(app.pkg, System.currentTimeMillis())) dockDirty = true;
        });
        card.setOnLongClickListener(v -> { showOptions(app); return true; });
        card.setOnFocusChangeListener((v, has) -> {
            v.animate().scaleX(has ? 1.08f : 1f).scaleY(has ? 1.08f : 1f).translationZ(has ? dp(12) : 0)
                    .setInterpolator(has ? new OvershootInterpolator(1.6f) : new DecelerateInterpolator())
                    .setDuration(has ? 260 : 180).start();
            shine.animate().alpha(has ? 1f : 0f).setDuration(150).start();
            label.setTextColor(has ? Color.WHITE : 0xCCFFFFFF);
        });
        cards.add(card);
        return box;
    }

    /** Icone do tile "Mais apps": grade 3x3 de quadradinhos arredondados + o texto. */
    static final class MoreAppsGlyph extends Drawable {
        private final android.graphics.Paint fill = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Paint txt = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);

        MoreAppsGlyph() {
            fill.setColor(0xE6FFFFFF);
            txt.setColor(Color.WHITE);
            txt.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            txt.setTextAlign(android.graphics.Paint.Align.CENTER);
        }

        @Override public void draw(Canvas c) {
            android.graphics.Rect b = getBounds();
            float s = b.height() * 0.46f;
            float cell = s / 3f, gap = cell * 0.28f, r = cell * 0.22f;
            float left = b.exactCenterX() - s / 2f, top = b.top + b.height() * 0.12f;
            for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) {
                float x = left + j * cell, y = top + i * cell;
                c.drawRoundRect(new android.graphics.RectF(x + gap / 2, y + gap / 2, x + cell - gap / 2, y + cell - gap / 2), r, r, fill);
            }
            txt.setTextSize(b.height() * 0.17f);
            c.drawText("Mais apps", b.exactCenterX(), b.bottom - b.height() * 0.1f, txt);
        }
        @Override public void setAlpha(int a) { }
        @Override public void setColorFilter(android.graphics.ColorFilter f) { }
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }

    // ---------------------------------------------------------------- status

    private void updateClock() {
        Calendar now = Calendar.getInstance();
        int h = now.get(Calendar.HOUR_OF_DAY);
        greeting.setText(h < 5 ? "Boa noite" : h < 12 ? "Bom dia" : h < 18 ? "Boa tarde" : "Boa noite");
        boolean is24 = android.text.format.DateFormat.is24HourFormat(this);
        clock.setText(new SimpleDateFormat(is24 ? "H:mm" : "h:mm", Locale.getDefault()).format(now.getTime()));
        ampm.setText(is24 ? "" : (h < 12 ? "AM" : "PM"));
        // Formato do idioma da TV ("quinta-feira, 29 de outubro" em portugues, "Thursday, October 29" em ingles).
        String pattern = android.text.format.DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEEdMMMM");
        date.setText(new SimpleDateFormat(pattern, Locale.getDefault()).format(now.getTime()).toUpperCase(Locale.getDefault()));
    }

    /** O atalho so aparece quando o launcher original esta ativo; um botao que nao abre nada confunde. */
    private void updateWifi() {
        wifiPill.setVisibility(stockHome() != null ? View.VISIBLE : View.GONE);
    }

    /** Telas iniciais de fabrica: Google TV, Android TV, Android TV 5-7 e Fire TV. */
    private static final String[] STOCK_HOMES = {
            "com.google.android.apps.tv.launcherx", "com.google.android.tvlauncher",
            "com.google.android.leanbacklauncher", "com.amazon.tv.launcher"};

    /** A tela inicial de fabrica, se estiver instalada e ativa. */
    private Intent stockHome() {
        for (String pkg : STOCK_HOMES) {
            Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(pkg);
            ResolveInfo ri = getPackageManager().resolveActivity(i, 0);
            if (ri != null) return i.setComponent(new ComponentName(pkg, ri.activityInfo.name));
        }
        return null;
    }

    /** Abre a tela inicial de fabrica. */
    private void openStockHome() {
        Intent i = stockHome();
        if (i != null) launch(i);
    }

    // ---------------------------------------------------------------- glow

    private void setGlow(int color) {
        glowColor = color;
        GradientDrawable g = new GradientDrawable();
        g.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        g.setGradientCenter(0.5f, 0.78f);
        g.setGradientRadius(getResources().getDisplayMetrics().widthPixels * 0.62f);
        // Brilho discreto: forte demais ele tinge o papel de parede inteiro com a cor do app focado.
        g.setColors(new int[]{withAlpha(color, 0.16f), withAlpha(color, 0.05f), Color.TRANSPARENT});
        glow.setBackground(g);
    }

    private void animateGlow(int to) {
        if (glowAnim != null) glowAnim.cancel();
        glowAnim = ValueAnimator.ofObject(new ArgbEvaluator(), glowColor, to);
        glowAnim.setDuration(450);
        glowAnim.addUpdateListener(a -> setGlow((int) a.getAnimatedValue()));
        glowAnim.start();
    }

    private void fadeGlow(float alpha) {
        glow.animate().alpha(alpha).setDuration(300).start();
        // The grid sits over the photo's busiest middle part, so dim it while browsing apps.
        gridShade.animate().alpha(alpha < 1f ? 1f : 0f).setDuration(300).start();
    }

    // ---------------------------------------------------------------- wallpaper

    /** Usa as fotos de files/wallpapers/ (enviadas por adb) se houver; senao, as que vem na APK. */
    private void loadWallpaperList() {
        userWallDir = null;
        File dir = getExternalFilesDir("wallpapers");
        String[] mine = dir == null ? null : dir.list((d, n) -> n.toLowerCase(Locale.ROOT).matches(".*\\.(jpe?g|png|webp)"));
        if (mine != null && mine.length > 0) {
            Arrays.sort(mine);
            wallpapers = mine;
            userWallDir = dir;
            return;
        }
        try {
            wallpapers = getAssets().list("wallpapers");
            try (java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(getAssets().open("credits.txt"), "UTF-8"))) {
                for (String line; (line = r.readLine()) != null; ) {
                    String[] f = line.split("\t");
                    if (f.length == 3) bundledCredits.put(f[0], "Foto: " + f[1] + " · " + f[2] + " · Wikimedia Commons");
                }
            }
        } catch (java.io.IOException ignored) { }
    }

    private java.io.InputStream openWallpaper(String name) throws java.io.IOException {
        return userWallDir != null ? new java.io.FileInputStream(new File(userWallDir, name))
                : getAssets().open("wallpapers/" + name);
    }

    /** Fotos grandes do celular sao reduzidas para ~1920px (o stick tem 1 GB de RAM). */
    private Bitmap decodeWallpaper(String name) {
        try {
            BitmapFactory.Options b = new BitmapFactory.Options();
            b.inJustDecodeBounds = true;
            try (java.io.InputStream in = openWallpaper(name)) { BitmapFactory.decodeStream(in, null, b); }
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            int sample = 1;
            while (b.outWidth / (sample * 2) >= 1920) sample *= 2;
            o.inSampleSize = sample;
            try (java.io.InputStream in = openWallpaper(name)) { return BitmapFactory.decodeStream(in, null, o); }
        } catch (java.io.IOException | RuntimeException | OutOfMemoryError e) {
            // Aparelhos com pouca memoria: fica sem foto em vez de fechar a tela inicial.
            return null;
        }
    }

    private static Bitmap decodeBytes(byte[] data) {
        BitmapFactory.Options b = new BitmapFactory.Options();
        b.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, b);
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inPreferredConfig = Bitmap.Config.RGB_565;
        int sample = 1;
        while (b.outWidth / (sample * 2) >= 1920) sample *= 2;
        o.inSampleSize = sample;
        return BitmapFactory.decodeByteArray(data, 0, data.length, o);
    }

    private ImageView wallpaperLayer() {
        ImageView v = new ImageView(this);
        v.setScaleType(ImageView.ScaleType.CENTER_CROP);
        return v;
    }

    /**
     * Proxima foto, sorteada: uma paisagem do Wikimedia Commons quando ha internet, senao uma das locais.
     * Ao ligar mostra logo uma local e busca a primeira da internet 30 s depois.
     */
    private void nextWallpaper() {
        handler.removeCallbacks(rotateWallpaper);
        boolean first = wallIndex < 0;
        long delay = first && onlineWallpapers ? 30_000 : WALLPAPER_INTERVAL_MS;
        wallDueAt = SystemClock.elapsedRealtime() + delay;
        scheduleWallpaper(delay);
        if (fetching) return;
        fetching = true;
        boolean online = onlineWallpapers && userWallDir == null && !first;
        String local = null;
        if (wallpapers.length > 0) {
            int i = random.nextInt(wallpapers.length);
            if (wallpapers.length > 1 && i == wallIndex) i = (i + 1) % wallpapers.length;
            wallIndex = i;
            local = wallpapers[i];
        }
        String localName = local;
        new Thread(() -> {
            Photo p = online ? onlinePhoto() : null;
            if (p == null && localName != null) {
                p = new Photo();
                p.bitmap = decodeWallpaper(localName);
                p.credit = userWallDir == null ? bundledCredits.get(localName) : null;
            }
            Photo shown = p;
            runOnUiThread(() -> {
                fetching = false;
                if (shown != null) showWallpaper(shown.bitmap, shown.credit);
            });
        }).start();
    }

    private void showWallpaper(Bitmap bm, String credit) {
        if (bm == null || isDestroyed()) return;
        photoCredit.animate().alpha(0f).setDuration(400).withEndAction(() -> {
            photoCredit.setText(credit == null ? "" : credit);
            photoCredit.animate().alpha(1f).setDuration(1200).start();
        }).start();
        // Swap roles: the old front becomes the back, the new photo fades in on top.
        ImageView old = wallFront;
        wallFront = wallBack;
        wallBack = old;
        ViewGroup root = (ViewGroup) wallFront.getParent();
        root.removeView(wallFront);
        root.addView(wallFront, 1, new FrameLayout.LayoutParams(-1, -1));
        wallFront.setImageBitmap(bm);
        wallFront.setAlpha(0f);
        wallFront.animate().alpha(1f).setDuration(firstWallpaper ? 600 : 2000).withEndAction(() -> {
            wallBack.setImageDrawable(null); // release the previous photo
        }).start();
        firstWallpaper = false;
    }

    private boolean firstWallpaper = true;

    private void scheduleWallpaper(long delay) {
        handler.removeCallbacks(rotateWallpaper);
        handler.postDelayed(rotateWallpaper, Math.max(1000, delay));
    }

    // ---------------------------------------------------------------- fotos da internet

    /** Paisagens destacadas do Wikimedia Commons: licenca livre e escolhidas pela comunidade. */
    private static final String COMMONS_API = "https://commons.wikimedia.org/w/api.php?format=json&action=query";
    private static final String COMMONS_CATEGORY = "Category:Featured pictures of landscapes";
    /** O Wikimedia pede um User-Agent que identifique o app. */
    private static final String USER_AGENT = "AndroidTV-V2/2.1 (https://github.com/ogabrielmoreira/Android-TV-V2)";

    static final class Photo {
        Bitmap bitmap;
        String credit;
    }

    /** Uma paisagem aleatoria, ou null sem internet. Roda fora da thread principal. */
    private Photo onlinePhoto() {
        try {
            List<String> titles = commonsTitles();
            for (int tries = 0; tries < 4 && !titles.isEmpty(); tries++) {
                org.json.JSONObject info = commonsInfo(titles.get(random.nextInt(titles.size())));
                // So fotos deitadas: em pe, o corte para a TV perderia quase tudo.
                if (info == null || info.optInt("width") < info.optInt("height") * 1.3) continue;
                Bitmap bm = decodeBytes(httpGet(info.getString("thumburl")));
                if (bm == null) continue;
                Photo p = new Photo();
                p.bitmap = bm;
                p.credit = commonsCredit(info);
                return p;
            }
        } catch (Exception | OutOfMemoryError ignored) {
            // Sem internet, Wikimedia fora do ar ou pouca memoria: fica a foto local.
        }
        return null;
    }

    /** Nomes dos arquivos da categoria, guardados por uma semana. */
    private List<String> commonsTitles() throws Exception {
        File cache = new File(getFilesDir(), "commons-landscapes.txt");
        if (cache.exists() && System.currentTimeMillis() - cache.lastModified() < 7 * DAY_MS) return readLines(cache);
        List<String> titles = new ArrayList<>();
        try {
            String cont = "";
            while (cont != null && titles.size() < 3000) {
                org.json.JSONObject r = new org.json.JSONObject(new String(httpGet(COMMONS_API
                        + "&list=categorymembers&cmtype=file&cmlimit=500&cmtitle=" + enc(COMMONS_CATEGORY) + cont), "UTF-8"));
                org.json.JSONArray m = r.getJSONObject("query").getJSONArray("categorymembers");
                for (int i = 0; i < m.length(); i++) titles.add(m.getJSONObject(i).getString("title"));
                org.json.JSONObject next = r.optJSONObject("continue");
                cont = next == null ? null : "&cmcontinue=" + enc(next.getString("cmcontinue"));
            }
        } catch (Exception e) {
            if (cache.exists()) return readLines(cache);
            throw e;
        }
        try (java.io.Writer w = new java.io.OutputStreamWriter(new java.io.FileOutputStream(cache), "UTF-8")) {
            for (String t : titles) w.write(t + "\n");
        }
        return titles;
    }

    /** Tamanho, miniatura de 1920 px, autor e licenca de um arquivo. */
    private org.json.JSONObject commonsInfo(String title) throws Exception {
        org.json.JSONObject r = new org.json.JSONObject(new String(httpGet(COMMONS_API
                + "&prop=imageinfo&iiprop=url%7Csize%7Cuser%7Cextmetadata&iiurlwidth=1920"
                + "&iiextmetadatafilter=Artist%7CLicenseShortName&titles=" + enc(title)), "UTF-8"));
        org.json.JSONObject pages = r.getJSONObject("query").getJSONObject("pages");
        org.json.JSONArray info = pages.getJSONObject(pages.keys().next()).optJSONArray("imageinfo");
        return info == null ? null : info.getJSONObject(0);
    }

    /** "Foto: autor · licenca · Wikimedia Commons". As licencas CC BY pedem o nome do autor. */
    private static String commonsCredit(org.json.JSONObject info) {
        org.json.JSONObject ext = info.optJSONObject("extmetadata");
        String artist = ext == null ? "" : android.text.Html.fromHtml(
                ext.optJSONObject("Artist") == null ? "" : ext.optJSONObject("Artist").optString("value")).toString();
        artist = artist.replaceAll("\\s+", " ").trim();
        // Alguns autores escrevem um recado no lugar do nome: ai vale o usuario que enviou a foto.
        if (artist.isEmpty() || artist.length() > 40 || artist.contains("@")) artist = info.optString("user");
        String license = ext == null || ext.optJSONObject("LicenseShortName") == null ? ""
                : ext.optJSONObject("LicenseShortName").optString("value");
        return "Foto: " + artist + (license.isEmpty() ? "" : " · " + license) + " · Wikimedia Commons";
    }

    private static byte[] httpGet(String url) throws java.io.IOException {
        java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(15000);
        c.setRequestProperty("User-Agent", USER_AGENT);
        try (java.io.InputStream in = c.getInputStream()) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            for (int n; (n = in.read(buf)) > 0; ) {
                out.write(buf, 0, n);
                if (out.size() > 12 * 1024 * 1024) throw new java.io.IOException("arquivo grande demais");
            }
            return out.toByteArray();
        } finally {
            c.disconnect();
        }
    }

    private static String enc(String s) throws java.io.UnsupportedEncodingException {
        return java.net.URLEncoder.encode(s, "UTF-8");
    }

    private static List<String> readLines(File f) throws java.io.IOException {
        List<String> lines = new ArrayList<>();
        try (java.io.BufferedReader r = new java.io.BufferedReader(
                new java.io.InputStreamReader(new java.io.FileInputStream(f), "UTF-8"))) {
            for (String line; (line = r.readLine()) != null; ) if (!line.isEmpty()) lines.add(line);
        }
        return lines;
    }

    /** Average the banner's vivid pixels, then push saturation so the glow reads as a colour. */
    private int sampleColor(String pkg, Drawable d) {
        Integer cached = colorCache.get(pkg);
        if (cached != null) return cached;
        int color = 0xFF3A3A3A;
        try {
            Bitmap bm = Bitmap.createBitmap(24, 14, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bm);
            d.setBounds(0, 0, 24, 14);
            d.draw(c);
            float[] hsv = new float[3];
            double r = 0, g = 0, b = 0, w = 0;
            for (int y = 0; y < 14; y++) for (int x = 0; x < 24; x++) {
                int p = bm.getPixel(x, y);
                if (Color.alpha(p) < 128) continue;
                Color.colorToHSV(p, hsv);
                double weight = 0.05 + hsv[1] * hsv[2];
                r += Color.red(p) * weight; g += Color.green(p) * weight; b += Color.blue(p) * weight; w += weight;
            }
            bm.recycle();
            if (w > 0) {
                Color.colorToHSV(Color.rgb((int) (r / w), (int) (g / w), (int) (b / w)), hsv);
                if (hsv[1] > 0.12f) { hsv[1] = Math.max(hsv[1], 0.75f); hsv[2] = 0.85f; }
                else { hsv[1] = 0f; hsv[2] = 0.45f; }
                color = Color.HSVToColor(hsv);
            }
        } catch (RuntimeException ignored) { }
        colorCache.put(pkg, color);
        return color;
    }

    // ---------------------------------------------------------------- entrance

    private void playEntrance() {
        View header = hero.getChildAt(0);
        header.setAlpha(0f);
        header.setTranslationY(-dp(12));
        header.animate().alpha(1f).translationY(0).setDuration(500).setStartDelay(80).start();
        title.setAlpha(0f);
        title.animate().alpha(1f).setDuration(500).setStartDelay(200).start();
        for (int i = 0; i < dockRow.getChildCount(); i++) {
            View t = dockRow.getChildAt(i);
            t.setAlpha(0f);
            t.setTranslationY(dp(24));
            t.animate().alpha(1f).translationY(0).setInterpolator(new DecelerateInterpolator(2f))
                    .setDuration(450).setStartDelay(160 + i * 60L).start();
        }
    }

    // ---------------------------------------------------------------- helpers

    private boolean launch(Intent i) {
        try {
            if (!i.hasCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private boolean isInstalled(String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        try {
            getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private void openPackage(String pkg) {
        Intent i = getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = getPackageManager().getLaunchIntentForPackage(pkg);
        if (i != null) launch(i);
    }

    private TextView text(float sp, String family, int color) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTypeface(Typeface.create(family, Typeface.NORMAL));
        t.setTextColor(color);
        return t;
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private static FrameLayout.LayoutParams lp(int w, int h, int gravity, int l, int t, int r, int b) {
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(w, h, gravity);
        p.setMargins(l, t, r, b);
        return p;
    }

    private static LinearLayout.LayoutParams llp(int w, int h, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.setMargins(l, t, r, b);
        return p;
    }

    private static int withAlpha(int c, float a) {
        return Color.argb(Math.round(a * 255), Color.red(c), Color.green(c), Color.blue(c));
    }

    private static int darken(int c, float f) {
        return Color.rgb((int) (Color.red(c) * f), (int) (Color.green(c) * f), (int) (Color.blue(c) * f));
    }

    /** 16:9 container. */
    static final class AspectFrame extends FrameLayout {
        AspectFrame(Context c) { super(c); }
        @Override protected void onMeasure(int w, int h) {
            int width = MeasureSpec.getSize(w);
            super.onMeasure(w, MeasureSpec.makeMeasureSpec(width * 9 / 16, MeasureSpec.EXACTLY));
        }
    }

    /** ScrollView that never auto-scrolls to the focused child; the activity scrolls it by row. */
    static final class HomeScrollView extends android.widget.ScrollView {
        HomeScrollView(Context c) { super(c); }
        @Override protected int computeScrollDeltaToGetChildRectOnScreen(android.graphics.Rect r) { return 0; }
    }
}
