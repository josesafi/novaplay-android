package com.limelight.nova;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;
import android.preference.PreferenceManager;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Display;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Nova Play: panel flotante «Ajustes de la transmisión» (como el de Parsec), sin salir del juego. Lo abre la «N» de la barra flotante o
 * Select+Start del mando durante 1 segundo (18-nov-2026, pedido del dueno).
 *  - Video (calidad, FPS, bitrate, codigo): el protocolo los fija al conectar, asi que se marcan «reconecta» y se aplican con
 *    «Aplicar y reconectar» (vuelve a conectar con la MISMA consola; el juego sigue abierto alla).
 *  - Sonido, entrada y estadisticas: se aplican al instante.
 * En horizontal es una hoja a la derecha; en vertical, una hoja abajo. Se cierra tocando fuera, con «Cerrar» o con B del mando.
 */
public class NovaStreamPanel {

    /** Lo que el panel necesita de la pantalla del juego (Game). */
    public interface Host {
        boolean isOscVisible();
        void toggleOsc();
        boolean isTrackpadMode();
        void setTrackpadMode(boolean trackpad);
        boolean isStatsVisible();
        void setStatsVisible(boolean visible);
        void showKeyboard();
        /** Guarda la nueva calidad ya escrita en las preferencias y vuelve a conectar con la misma consola. */
        void reconnect();
        NovaAudioControl audio();
    }

    /** Control del sonido del juego en vivo (lo implementa el reproductor de audio). */
    public interface NovaAudioControl {
        void setPreferredDevice(AudioDeviceInfo device);
        AudioDeviceInfo getPreferredDevice();
        void setGameVolume(float volume);
        float getGameVolume();
        void setMuted(boolean muted);
        boolean isMuted();
    }

    public static final String NET_RECOMMENDED_PREF = "nova_red_recomendada"; // «1920x1080|60|15000» (lo guarda la Prueba de red)

    private static final int VIOLET = Color.parseColor("#9B4DFF");
    private static final int CYAN = Color.parseColor("#22D3EE");
    private static final int FROST = Color.parseColor("#F2F4FF");
    private static final int MIST = Color.parseColor("#A9B4D6");

    private final Activity act;
    private final Host host;
    private final SharedPreferences prefs;
    private FrameLayout root;
    private LinearLayout content;
    private TextView applyBtn;
    private View firstFocus;

    // video elegido en el panel (se escribe al aplicar)
    private String res;
    private int fps;
    private int kbps;
    private String codec;
    private String origRes, origCodec;
    private int origFps, origKbps;
    private TextView kbpsLabel;
    private SeekBar kbpsBar;

    public NovaStreamPanel(Activity act, Host host) {
        this.act = act;
        this.host = host;
        this.prefs = PreferenceManager.getDefaultSharedPreferences(act);
    }

    public boolean isShowing() {
        return root != null && root.getParent() != null;
    }

    public void toggle(FrameLayout parent) {
        if (isShowing()) close();
        else open(parent);
    }

    /** Con el mando, el foco tiene que estar DENTRO del panel (si se queda en el video, las teclas irian al juego). */
    public void ensureFocus() {
        if (!isShowing() || firstFocus == null) return;
        View f = act.getCurrentFocus();
        for (View v = f; v != null; v = v.getParent() instanceof View ? (View) v.getParent() : null) if (v == root) return;
        firstFocus.requestFocusFromTouch();
    }

    public void close() {
        if (root != null && root.getParent() != null) ((ViewGroup) root.getParent()).removeView(root);
        root = null;
    }

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, act.getResources().getDisplayMetrics());
    }

    // ------------------------------------------------------------------------------------------------ armado

    public void open(FrameLayout parent) {
        close();
        res = prefs.getString("list_resolution", "1280x720");
        fps = parseInt(prefs.getString("list_fps", "60"), 60);
        kbps = prefs.getInt("seekbar_bitrate_kbps", 10000);
        codec = prefs.getString("video_format", "auto");
        if ("forceav1".equals(codec)) codec = "auto"; // la GPU de las consolas no codifica AV1
        origRes = res; origFps = fps; origKbps = kbps; origCodec = codec;

        root = new FrameLayout(act);
        root.setBackgroundColor(Color.parseColor("#66000000"));
        root.setClickable(true);
        root.setOnClickListener(v -> close()); // tocar fuera cierra

        boolean landscape = act.getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        LinearLayout sheet = new LinearLayout(act);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setClickable(true); // los toques dentro no cierran
        GradientDrawable sb = new GradientDrawable();
        sb.setColor(Color.parseColor("#F20B1030"));
        float r = dp(22);
        sb.setCornerRadii(landscape ? new float[]{r, r, 0, 0, 0, 0, r, r} : new float[]{r, r, r, r, 0, 0, 0, 0});
        sb.setStroke(dp(1), Color.parseColor("#669B4DFF"));
        sheet.setBackground(sb);
        sheet.setPadding(dp(18), dp(16), dp(18), dp(12));
        FrameLayout.LayoutParams sp;
        if (landscape) {
            DisplayMetrics m = act.getResources().getDisplayMetrics();
            int w = Math.min(dp(400), (int) (m.widthPixels * 0.48f));
            sp = new FrameLayout.LayoutParams(w, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END);
        } else {
            DisplayMetrics m = act.getResources().getDisplayMetrics();
            sp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) (m.heightPixels * 0.72f), Gravity.BOTTOM);
        }
        root.addView(sheet, sp);

        // cabecera
        LinearLayout head = new LinearLayout(act);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text("Ajustes de la transmisión", 18, FROST, true);
        head.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView x = chip("Cerrar", false);
        x.setOnClickListener(v -> close());
        head.addView(x, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)));
        sheet.addView(head);

        ScrollView scroll = new ScrollView(act);
        scroll.setFillViewport(true);
        content = new LinearLayout(act);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, dp(6), 0, dp(10));
        scroll.addView(content);
        sheet.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        buildVideo();
        buildSound();
        buildInput();
        buildStats();

        // pie: aplicar video
        applyBtn = chip("Aplicar y reconectar", true);
        applyBtn.setOnClickListener(v -> apply());
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46));
        ap.topMargin = dp(8);
        sheet.addView(applyBtn, ap);
        refreshApply();

        parent.addView(root, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.bringToFront();
        firstFocus = x;
        x.requestFocus(); // el mando empieza arriba
    }

    private void buildVideo() {
        section("VIDEO", true);
        // calidad segun la pantalla del equipo (y 720p siempre)
        int shortSide = screenShortSide();
        String[][] all = {{"1280x720", "720p"}, {"1920x1080", "1080p"}, {"2560x1440", "1440p"}, {"3840x2160", "4K"}};
        List<String[]> ress = new ArrayList<>();
        for (String[] o : all) {
            int h = parseInt(o[0].split("x")[1], 720);
            if (h <= 720 || h <= Math.max(1080, shortSide)) ress.add(o);
        }
        if (!containsKey(ress, res)) ress.add(new String[]{res, res.replace('x', '×')});
        choices("Calidad", ress.toArray(new String[0][]), () -> res, v -> { res = v; refreshApply(); });

        List<String[]> fpss = new ArrayList<>();
        float maxHz = maxRefresh();
        for (int f : new int[]{30, 60, 90, 120}) if (f <= 60 || f <= maxHz + 1) fpss.add(new String[]{String.valueOf(f), f + " FPS"});
        choices("Cuadros por segundo", fpss.toArray(new String[0][]), () -> String.valueOf(fps), v -> { fps = parseInt(v, 60); refreshApply(); });

        // bitrate 5-80 Mbps
        label("Bitrate");
        kbpsLabel = text("", 14, FROST, true);
        content.addView(kbpsLabel);
        kbpsBar = new SeekBar(act);
        kbpsBar.setMax(75);
        kbpsBar.setProgress(Math.max(0, Math.min(75, Math.round(kbps / 1000f) - 5)));
        kbpsBar.setFocusable(true);
        kbpsBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean user) { kbps = (p + 5) * 1000; showKbps(); refreshApply(); }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        });
        content.addView(kbpsBar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(36)));
        LinearLayout marks = row();
        String[][] presets = {{"10", "Ahorro de datos"}, {"rec", "Recomendado"}, {"80", "Máxima"}};
        for (String[] pr : presets) {
            TextView c = chip(pr[1], false);
            c.setOnClickListener(v -> {
                int mbps = "rec".equals(pr[0]) ? Math.max(5, Math.min(80, recommendedKbps() / 1000)) : parseInt(pr[0], 10);
                kbpsBar.setProgress(mbps - 5);
            });
            marks.addView(c, chipLp());
        }
        content.addView(marks);
        showKbps();

        choices("Códec", new String[][]{{"auto", "Automático"}, {"neverh265", "H.264"}, {"forceh265", "H.265 (HEVC)"}},
                () -> codec, v -> { codec = v; refreshApply(); });

        // recomendado por la ultima prueba de red
        String net = prefs.getString(NET_RECOMMENDED_PREF, "");
        String[] p = net.split("\\|");
        TextView rec = chip(p.length == 3 ? "Usar lo recomendado por mi red (" + p[0].split("x")[1] + "p · " + p[1] + " FPS · "
                + (parseInt(p[2], 0) / 1000) + " Mbps)" : "Haz la «Prueba de red» del inicio para ver tu recomendación", false);
        if (p.length == 3) {
            rec.setOnClickListener(v -> {
                res = p[0]; fps = parseInt(p[1], 60);
                kbps = Math.max(5000, Math.min(80000, parseInt(p[2], 15000)));
                // se aplica de una vez (con su rueda «Reconectando…»); si ya era lo mismo no hace nada
                if (videoChanged()) apply(); else close();
            });
        } else {
            rec.setEnabled(false);
            rec.setAlpha(0.6f);
        }
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rl.topMargin = dp(10);
        content.addView(rec, rl);
    }

    private void buildSound() {
        section("SONIDO", false);
        NovaAudioControl a = host.audio();
        if (a == null) {
            content.addView(text("El sonido aparece cuando termina de conectar.", 13, MIST, false));
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            AudioManager am = (AudioManager) act.getSystemService(Context.AUDIO_SERVICE);
            List<String[]> opts = new ArrayList<>();
            List<AudioDeviceInfo> devs = new ArrayList<>();
            opts.add(new String[]{"auto", "Automático"});
            devs.add(null);
            for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                String n = deviceName(d);
                if (n == null) continue;
                boolean dup = false;
                for (String[] o : opts) if (o[1].equals(n)) { dup = true; break; }
                if (dup) continue;
                opts.add(new String[]{String.valueOf(d.getId()), n});
                devs.add(d);
            }
            AudioDeviceInfo cur = a.getPreferredDevice();
            choices("Salida de audio", opts.toArray(new String[0][]), () -> cur == null ? "auto" : String.valueOf(currentDeviceId(a)), v -> {
                AudioDeviceInfo chosen = null;
                for (int i = 0; i < opts.size(); i++) if (opts.get(i)[0].equals(v)) chosen = devs.get(i);
                a.setPreferredDevice(chosen);
            });
        }
        label("Volumen del juego");
        SeekBar vol = new SeekBar(act);
        vol.setMax(100);
        vol.setProgress(Math.round(a.getGameVolume() * 100));
        vol.setFocusable(true);
        vol.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean user) { a.setGameVolume(p / 100f); }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        });
        content.addView(vol, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(36)));
        toggleRow("Silenciar el juego", a.isMuted(), a::setMuted);
    }

    private int currentDeviceId(NovaAudioControl a) {
        AudioDeviceInfo d = a.getPreferredDevice();
        return d == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.M ? -1 : d.getId();
    }

    private static String deviceName(AudioDeviceInfo d) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null;
        String product = d.getProductName() == null ? "" : d.getProductName().toString();
        switch (d.getType()) {
            case AudioDeviceInfo.TYPE_BUILTIN_SPEAKER: return "Altavoz del teléfono";
            case AudioDeviceInfo.TYPE_WIRED_HEADSET:
            case AudioDeviceInfo.TYPE_WIRED_HEADPHONES: return "Auriculares con cable";
            case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP: return "Bluetooth" + (product.isEmpty() ? "" : ": " + product);
            case AudioDeviceInfo.TYPE_USB_HEADSET:
            case AudioDeviceInfo.TYPE_USB_DEVICE: return "USB" + (product.isEmpty() ? "" : ": " + product);
            case AudioDeviceInfo.TYPE_HDMI: return "HDMI (TV)";
            default: return null; // auricular de llamadas, salidas internas, etc.
        }
    }

    private void buildInput() {
        section("ENTRADA", false);
        label("Mandos conectados");
        StringBuilder pads = new StringBuilder();
        for (int id : InputDevice.getDeviceIds()) {
            InputDevice d = InputDevice.getDevice(id);
            if (d == null || d.isVirtual()) continue;
            int s = d.getSources();
            if ((s & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD || (s & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK) {
                if (pads.length() > 0) pads.append("\n");
                pads.append("🎮  ").append(d.getName());
            }
        }
        content.addView(text(pads.length() == 0 ? "Ninguno: conecta un mando por Bluetooth o USB, o usa los controles en pantalla." : pads.toString(),
                14, pads.length() == 0 ? MIST : FROST, false));
        toggleRow("Controles en pantalla", host.isOscVisible(), on -> { if (on != host.isOscVisible()) host.toggleOsc(); });
        choices("Modo del dedo", new String[][]{{"trackpad", "Panel táctil"}, {"direct", "Toque directo"}},
                () -> host.isTrackpadMode() ? "trackpad" : "direct", v -> host.setTrackpadMode("trackpad".equals(v)));
        TextView kb = chip("⌨  Mostrar teclado", false);
        kb.setOnClickListener(v -> { close(); host.showKeyboard(); });
        LinearLayout.LayoutParams kl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(38));
        kl.topMargin = dp(10);
        content.addView(kb, kl);
    }

    private void buildStats() {
        section("ESTADÍSTICAS", false);
        toggleRow("Mostrar latencia, FPS y bitrate en pantalla", host.isStatsVisible(), host::setStatsVisible);
    }

    // ------------------------------------------------------------------------------------------------ aplicar

    private boolean videoChanged() {
        return !res.equals(origRes) || fps != origFps || Math.abs(kbps - origKbps) >= 1000 || !codec.equals(origCodec);
    }

    private void refreshApply() {
        if (applyBtn == null) return;
        boolean ch = videoChanged();
        applyBtn.setEnabled(ch);
        applyBtn.setAlpha(ch ? 1f : 0.45f);
        applyBtn.setText(ch ? "↻  Aplicar y reconectar" : "Sin cambios de video");
    }

    private void apply() {
        if (!videoChanged()) return;
        prefs.edit().putString("list_resolution", res).putString("list_fps", String.valueOf(fps))
                .putInt("seekbar_bitrate_kbps", kbps).putString("video_format", codec).apply();
        // rueda y estado durante la espera (regla del dueno: nunca una espera sin aviso)
        content.removeAllViews();
        LinearLayout w = new LinearLayout(act);
        w.setOrientation(LinearLayout.VERTICAL);
        w.setGravity(Gravity.CENTER);
        ProgressBar pb = new ProgressBar(act);
        w.addView(pb, new LinearLayout.LayoutParams(dp(48), dp(48)));
        TextView t = text("Reconectando… tu juego sigue abierto", 16, FROST, true);
        t.setGravity(Gravity.CENTER);
        w.addView(t);
        TextView t2 = text("Aplicamos " + res.split("x")[1] + "p · " + fps + " FPS · " + (kbps / 1000) + " Mbps. Tarda unos segundos.", 13, MIST, false);
        t2.setGravity(Gravity.CENTER);
        w.addView(t2);
        content.addView(w, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(220)));
        applyBtn.setEnabled(false);
        applyBtn.setText("Reconectando…");
        root.postDelayed(host::reconnect, 350);
    }

    private int recommendedKbps() {
        String net = prefs.getString(NET_RECOMMENDED_PREF, "");
        String[] p = net.split("\\|");
        int byRes = com.limelight.preferences.PreferenceConfiguration.getDefaultBitrate(res, String.valueOf(fps));
        if (p.length == 3) return Math.min(byRes, parseInt(p[2], byRes));
        return byRes;
    }

    private void showKbps() {
        if (kbpsLabel != null) kbpsLabel.setText(String.format(Locale.US, "%d Mbps", kbps / 1000));
    }

    // ------------------------------------------------------------------------------------------------ piezas

    private interface Getter { String get(); }
    private interface Setter { void set(String v); }
    private interface Toggle { void set(boolean on); }

    private void section(String name, boolean reconnects) {
        LinearLayout r = row();
        TextView t = text(name, 12, CYAN, true);
        t.setLetterSpacing(0.12f);
        r.addView(t);
        if (reconnects) {
            TextView b = text("  ↻ reconecta", 11, Color.parseColor("#FACC15"), true);
            r.addView(b);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(18);
        content.addView(r, lp);
    }

    private void label(String s) {
        TextView t = text(s, 13, MIST, false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        lp.bottomMargin = dp(4);
        content.addView(t, lp);
    }

    private void choices(String name, String[][] opts, Getter get, Setter set) {
        label(name);
        LinearLayout r = row();
        r.setOrientation(LinearLayout.HORIZONTAL);
        List<TextView> chips = new ArrayList<>();
        for (String[] o : opts) {
            TextView c = chip(o[1], o[0].equals(get.get()));
            c.setOnClickListener(v -> {
                set.set(o[0]);
                for (TextView x : chips) x.setSelected(x == c);
            });
            chips.add(c);
            r.addView(c, chipLp());
        }
        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(act);
        hs.setHorizontalScrollBarEnabled(false);
        hs.addView(r);
        content.addView(hs);
    }

    private void toggleRow(String name, boolean on, Toggle t) {
        LinearLayout r = row();
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.addView(text(name, 14, FROST, false), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView c = chip(on ? "Sí" : "No", on);
        c.setOnClickListener(v -> {
            boolean nv = !c.isSelected();
            c.setSelected(nv);
            c.setText(nv ? "Sí" : "No");
            t.set(nv);
        });
        r.addView(c, new LinearLayout.LayoutParams(dp(64), dp(36)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        content.addView(r, lp);
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(act);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        return r;
    }

    private LinearLayout.LayoutParams chipLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36));
        lp.rightMargin = dp(8);
        lp.topMargin = dp(4);
        return lp;
    }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(act);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    /** Boton/opcion redondeada: elegido = violeta, con foco del mando = borde cian. */
    private TextView chip(String label, boolean selected) {
        TextView c = text(label, 14, FROST, true);
        c.setGravity(Gravity.CENTER);
        c.setPadding(dp(14), 0, dp(14), 0);
        c.setFocusable(true);
        c.setClickable(true);
        StateListDrawable st = new StateListDrawable();
        st.addState(new int[]{android.R.attr.state_focused}, pill(Color.parseColor("#33FFFFFF"), CYAN));
        st.addState(new int[]{android.R.attr.state_selected}, pill(VIOLET, Color.TRANSPARENT));
        st.addState(new int[]{}, pill(Color.parseColor("#1FFFFFFF"), Color.TRANSPARENT));
        c.setBackground(st);
        c.setSelected(selected);
        return c;
    }

    private GradientDrawable pill(int fill, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(18));
        if (stroke != Color.TRANSPARENT) g.setStroke(dp(2), stroke);
        return g;
    }

    private int screenShortSide() {
        DisplayMetrics m = new DisplayMetrics();
        act.getWindowManager().getDefaultDisplay().getRealMetrics(m);
        return Math.min(m.widthPixels, m.heightPixels);
    }

    private float maxRefresh() {
        Display d = act.getWindowManager().getDefaultDisplay();
        float max = d.getRefreshRate();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            for (Display.Mode mo : d.getSupportedModes()) max = Math.max(max, mo.getRefreshRate());
        }
        return max;
    }

    private static boolean containsKey(List<String[]> l, String k) {
        for (String[] o : l) if (o[0].equals(k)) return true;
        return false;
    }

    private static int parseInt(String s, int def) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return def; }
    }
}
