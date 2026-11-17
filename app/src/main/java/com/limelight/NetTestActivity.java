package com.limelight;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.preference.PreferenceManager;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import com.limelight.nova.NetProbe;

import java.util.Locale;

/**
 * «Prueba de red» de Nova Play: mide ancho de banda, perdida de paquetes, latencia y variacion contra el servidor de pruebas que esta
 * junto a las consolas, muestra si la conexion sirve para jugar y la calidad que conviene (y la aplica con un toque).
 * Extra «host»: IP o nombre publico de la consola (sin puerto).
 */
public class NetTestActivity extends Activity implements NetProbe.Listener {
    public static final String HOST_EXTRA = "host";

    private static final int NIGHT = Color.parseColor("#06070F");
    private static final int INK = Color.parseColor("#0D1022");
    private static final int FROST = Color.parseColor("#EEF0FA");
    private static final int MIST = Color.parseColor("#8D93B0");
    private static final int VIOLET = Color.parseColor("#9B4DFF");
    private static final int LILAC = Color.parseColor("#B98AFF");
    private static final int OK = Color.parseColor("#3EE08F");
    private static final int WARN = Color.parseColor("#FFB547");
    private static final int BAD = Color.parseColor("#FF6B6B");

    private final Handler ui = new Handler(Looper.getMainLooper());
    private ScrollView root;
    private LinearLayout box;
    private NetProbe probe;
    private String host;
    private Typeface sora = Typeface.DEFAULT, soraSemi = Typeface.DEFAULT_BOLD, soraBold = Typeface.DEFAULT_BOLD;

    // pantalla de medicion
    private int phase = -1;
    private final TextView[] stepLabels = new TextView[3];
    private final View[] stepMarks = new View[3];
    private TextView liveValue, liveUnit, liveCaption;
    private ProgressBar bar;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        host = getIntent().getStringExtra(HOST_EXTRA);
        if (host == null || host.isEmpty()) host = "103.212.83.177";
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(NIGHT);
        loadFonts();
        FrameLayout frame = new FrameLayout(this);
        frame.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Color.parseColor("#1B1240"), Color.parseColor("#0B0A1E"), NIGHT, NIGHT}));
        root = new ScrollView(this);
        root.setFillViewport(true);
        root.setClipToPadding(false);
        frame.addView(root, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(), insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        setContentView(frame);
        frame.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
        startTest();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (probe != null) probe.cancel();
    }

    // ------------------------------------------------------------------ utilidades
    private void loadFonts() {
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                sora = new Typeface.Builder(getAssets(), "sora.ttf").setFontVariationSettings("'wght' 400").build();
                soraSemi = new Typeface.Builder(getAssets(), "sora.ttf").setFontVariationSettings("'wght' 600").build();
                soraBold = new Typeface.Builder(getAssets(), "sora.ttf").setFontVariationSettings("'wght' 700").build();
            } else {
                sora = Typeface.createFromAsset(getAssets(), "sora.ttf");
                soraSemi = soraBold = Typeface.create(sora, Typeface.BOLD);
            }
        } catch (Exception ignored) {
        }
    }

    private int dp(float v) { return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()); }

    private TextView text(String s, float sp, int color, Typeface tf) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(tf);
        return t;
    }

    private GradientDrawable box(int color, float radiusDp, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        if (stroke != 0) g.setStroke(dp(1), stroke);
        return g;
    }

    private LinearLayout.LayoutParams lp(int w, int h, int top) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.topMargin = dp(top);
        return p;
    }

    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT, WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    private Button button(String label, boolean primary) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        b.setTypeface(soraSemi);
        b.setTextColor(primary ? Color.WHITE : FROST);
        if (primary) {
            GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{Color.parseColor("#8A3DF5"), Color.parseColor("#A95CFF")});
            g.setCornerRadius(dp(26));
            b.setBackground(g);
        } else {
            b.setBackground(box(Color.TRANSPARENT, 26, Color.parseColor("#33FFFFFF")));
        }
        b.setPadding(dp(18), 0, dp(18), 0);
        b.setMinHeight(dp(50));
        b.setStateListAnimator(null);
        return b;
    }

    private LinearLayout page() {
        root.removeAllViews();
        box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(14), dp(20), dp(24));
        root.addView(box, new ViewGroup.LayoutParams(MATCH, WRAP));
        // cabecera: volver + titulo
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = text("‹", 30, FROST, sora);
        back.setPadding(0, 0, dp(14), dp(4));
        back.setContentDescription("Volver");
        back.setOnClickListener(v -> finish());
        head.addView(back);
        head.addView(text("Prueba de red", 22, FROST, soraBold));
        box.addView(head, lp(MATCH, WRAP, 4));
        TextView sub = text("Medimos tu conexión con las consolas de Nova Play en Colombia, igual que viaja el video de tu juego.", 13, MIST, sora);
        box.addView(sub, lp(MATCH, WRAP, 6));
        return box;
    }

    // ------------------------------------------------------------------ medicion
    private static final String[] STEPS = {"Latencia", "Pérdida de paquetes", "Ancho de banda"};

    private void startTest() {
        if (probe != null) probe.cancel();
        phase = -1;
        page();

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(dp(20), dp(28), dp(20), dp(24));
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{Color.parseColor("#2A1A5E"), Color.parseColor("#141433"), INK});
        bg.setCornerRadius(dp(26));
        bg.setStroke(dp(1), Color.parseColor("#339B4DFF"));
        card.setBackground(bg);
        box.addView(card, lp(MATCH, WRAP, 22));

        liveCaption = text("Preparando la prueba…", 13, LILAC, soraSemi);
        liveCaption.setGravity(Gravity.CENTER);
        card.addView(liveCaption, new LinearLayout.LayoutParams(MATCH, WRAP));
        LinearLayout big = new LinearLayout(this);
        big.setGravity(Gravity.CENTER | Gravity.BOTTOM);
        liveValue = text("—", 56, FROST, soraBold);
        liveUnit = text("", 18, MIST, soraSemi);
        liveUnit.setPadding(dp(6), 0, 0, dp(12));
        big.addView(liveValue);
        big.addView(liveUnit);
        card.addView(big, lp(MATCH, WRAP, 8));

        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        bar.setProgressTintList(android.content.res.ColorStateList.valueOf(VIOLET));
        bar.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.parseColor("#26FFFFFF")));
        card.addView(bar, lp(MATCH, dp(8), 18));

        LinearLayout steps = new LinearLayout(this);
        steps.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i < 3; i++) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            View mark = new View(this);
            GradientDrawable d = new GradientDrawable();
            d.setShape(GradientDrawable.OVAL);
            d.setColor(Color.parseColor("#33FFFFFF"));
            mark.setBackground(d);
            row.addView(mark, new LinearLayout.LayoutParams(dp(10), dp(10)));
            TextView l = text(STEPS[i], 14, MIST, sora);
            l.setPadding(dp(12), 0, 0, 0);
            row.addView(l);
            stepMarks[i] = mark;
            stepLabels[i] = l;
            steps.addView(row, lp(MATCH, WRAP, i == 0 ? 0 : 12));
        }
        card.addView(steps, lp(MATCH, WRAP, 22));

        TextView hint = text("No cierres esta pantalla: tarda unos 15 segundos. Para que sea exacta, pausa las descargas y videos en tus otros equipos.", 12, MIST, sora);
        box.addView(hint, lp(MATCH, WRAP, 16));
        Button cancel = button("Cancelar", false);
        cancel.setOnClickListener(v -> finish());
        box.addView(cancel, lp(MATCH, dp(50), 18));

        probe = new NetProbe(host, this);
        probe.start();
    }

    private void markStep(int i, int state) { // 0 pendiente, 1 en curso, 2 hecho
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(state == 2 ? OK : state == 1 ? VIOLET : Color.parseColor("#33FFFFFF"));
        stepMarks[i].setBackground(d);
        stepLabels[i].setTextColor(state == 0 ? MIST : FROST);
        stepLabels[i].setTypeface(state == 1 ? soraSemi : sora);
        stepLabels[i].setText(STEPS[i] + (state == 1 ? "…" : state == 2 ? "  ✓" : ""));
    }

    @Override
    public void onPhase(int p) {
        ui.post(() -> {
            if (liveCaption == null) return;
            for (int i = 0; i < 3; i++) markStep(i, i < p ? 2 : i == p ? 1 : 0);
            phase = p;
            liveCaption.setText(p == NetProbe.PHASE_LATENCY ? "Midiendo la latencia" : p == NetProbe.PHASE_LOSS ? "Midiendo la pérdida de paquetes" : "Midiendo el ancho de banda");
            liveValue.setText("—");
            liveUnit.setText(p == NetProbe.PHASE_LATENCY ? "ms" : p == NetProbe.PHASE_LOSS ? "%" : "Mbps");
        });
    }

    @Override
    public void onProgress(float f) { ui.post(() -> { if (bar != null) bar.setProgress((int) (f * 1000)); }); }

    @Override
    public void onLive(int p, double v) {
        ui.post(() -> {
            if (liveValue == null || p != phase) return;
            liveValue.setText(p == NetProbe.PHASE_LOSS ? String.format(Locale.US, "%.1f", v) : String.valueOf(Math.round(v)));
        });
    }

    @Override
    public void onError(String message) {
        ui.post(() -> {
            page();
            box.addView(verdict(BAD, "No se pudo hacer la prueba", message), lp(MATCH, WRAP, 22));
            actions(null);
        });
    }

    @Override
    public void onDone(NetProbe.Result r) { ui.post(() -> showResult(r)); }

    // ------------------------------------------------------------------ resultado
    private LinearLayout verdict(int color, String title, String body) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(18), dp(18), dp(18), dp(18));
        c.setBackground(box(Color.argb(30, Color.red(color), Color.green(color), Color.blue(color)), 20, Color.argb(110, Color.red(color), Color.green(color), Color.blue(color))));
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        View dot = new View(this);
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        dot.setBackground(d);
        row.addView(dot, new LinearLayout.LayoutParams(dp(12), dp(12)));
        TextView t = text(title, 18, FROST, soraBold);
        t.setPadding(dp(10), 0, 0, 0);
        row.addView(t, new LinearLayout.LayoutParams(0, WRAP, 1f));
        c.addView(row);
        if (body != null) c.addView(text(body, 13, FROST, sora), lp(MATCH, WRAP, 8));
        return c;
    }

    private int gradeColor(int g) { return g == 2 ? OK : g == 1 ? WARN : BAD; }

    private void section(LinearLayout parent, String title, String measured, int grade, String required, String recommended) {
        parent.addView(text(title, 15, FROST, soraSemi), lp(MATCH, WRAP, 18));
        String[][] rows = {{"Medido", measured}, {"Requerido", required}, {"Recomendado", recommended}};
        for (int i = 0; i < rows.length; i++) {
            LinearLayout row = new LinearLayout(this);
            row.addView(text(rows[i][0], 14, MIST, sora), new LinearLayout.LayoutParams(0, WRAP, 1f));
            TextView v = text(rows[i][1], 14, i == 0 ? gradeColor(grade) : FROST, i == 0 ? soraBold : sora);
            row.addView(v);
            parent.addView(row, lp(MATCH, WRAP, 10));
        }
    }

    private static String fmt1(double v) { return String.format(Locale.US, "%.1f", v); }

    private void showResult(NetProbe.Result r) {
        page();
        int gBw = NetProbe.gradeHigh(r.mbps, NetProbe.BW_REQUIRED, NetProbe.BW_RECOMMENDED);
        int gLoss = NetProbe.gradeLow(r.lossPct, NetProbe.LOSS_REQUIRED, NetProbe.LOSS_RECOMMENDED);
        int gLat = NetProbe.gradeLow(r.latencyMs, NetProbe.LAT_REQUIRED, NetProbe.LAT_RECOMMENDED);
        int gJit = NetProbe.gradeLow(r.jitterMs, NetProbe.JITTER_REQUIRED, NetProbe.JITTER_RECOMMENDED);
        int worst = Math.min(Math.min(gBw, gLoss), Math.min(gLat, gJit));
        int[] q = NetProbe.recommendedQuality(r);
        String what = worst == 2 ? "Tu red está lista para jugar" : worst == 1 ? "Tu red sirve para jugar" : "Tu red no alcanza para jugar bien";
        StringBuilder why = new StringBuilder();
        if (worst == 2) why.append("Excelente conexión: disfruta tus juegos con la mejor calidad.");
        else {
            if (gBw < 2) why.append(gBw == 0 ? "La velocidad es muy baja. " : "La velocidad es justa: bajamos un poco la calidad. ");
            if (gLoss < 2) why.append("Se pierden paquetes: puede verse entrecortado. ");
            if (gLat < 2) why.append(gLat == 0 ? "La demora (latencia) es alta: los controles responderán tarde. " : "La demora es algo alta para juegos rápidos. ");
            if (gJit < 2) why.append("La conexión es inestable. ");
        }
        box.addView(verdict(gradeColor(worst), what, why.toString().trim()), lp(MATCH, WRAP, 22));

        // calidad recomendada
        LinearLayout qual = new LinearLayout(this);
        qual.setOrientation(LinearLayout.VERTICAL);
        qual.setPadding(dp(18), dp(16), dp(18), dp(16));
        qual.setBackground(box(Color.parseColor("#B30D1022"), 20, Color.parseColor("#1AFFFFFF")));
        qual.addView(text("CALIDAD RECOMENDADA", 11, MIST, soraSemi));
        String qText = q == null ? "Sin calidad recomendada" : (q[1] + "p a " + q[2] + " FPS · " + (q[3] / 1000) + " Mbps");
        qual.addView(text(qText, 20, FROST, soraBold), lp(MATCH, WRAP, 6));
        if (q != null) {
            Button apply = button("Usar esta calidad", true);
            apply.setOnClickListener(v -> {
                SharedPreferences.Editor e = PreferenceManager.getDefaultSharedPreferences(this).edit();
                e.putString("list_resolution", q[0] + "x" + q[1]);
                e.putString("list_fps", String.valueOf(q[2]));
                e.putInt("seekbar_bitrate_kbps", q[3]);
                e.apply();
                apply.setText("Calidad aplicada  ✓");
                apply.setEnabled(false);
            });
            qual.addView(apply, lp(MATCH, dp(48), 14));
        }
        box.addView(qual, lp(MATCH, WRAP, 12));

        // consejos
        String tip = tipFor(r, gBw, gLoss, gJit);
        if (tip != null) {
            TextView t = text(tip, 13, MIST, sora);
            box.addView(t, lp(MATCH, WRAP, 14));
        }

        // detalles como en la referencia: medido / requerido / recomendado
        LinearLayout det = new LinearLayout(this);
        det.setOrientation(LinearLayout.VERTICAL);
        det.setPadding(dp(18), dp(6), dp(18), dp(18));
        det.setBackground(box(Color.parseColor("#B30D1022"), 20, Color.parseColor("#1AFFFFFF")));
        det.addView(text("Detalles", 17, FROST, soraBold), lp(MATCH, WRAP, 12));
        section(det, "Ancho de banda (Mbps)", r.mbps >= 100 ? "> 100" : String.valueOf(Math.round(r.mbps)), gBw, "> 15", "> 35");
        section(det, "Pérdida de paquetes (%)", fmt1(r.lossPct), gLoss, "< 2.0", "< 1.0");
        section(det, "Latencia (ms)", String.valueOf(Math.round(r.latencyMs)), gLat, "< 80", "< 40");
        section(det, "Variación (ms)", String.valueOf(Math.round(r.jitterMs)), gJit, "< 30", "< 10");
        det.addView(text("Conexión: " + connectionName(), 12, MIST, sora), lp(MATCH, WRAP, 18));
        box.addView(det, lp(MATCH, WRAP, 14));

        actions(r);
    }

    private void actions(NetProbe.Result r) {
        LinearLayout row = new LinearLayout(this);
        Button close = button("Cerrar", false);
        close.setOnClickListener(v -> finish());
        Button again = button("Inténtalo de nuevo", true);
        again.setOnClickListener(v -> startTest());
        LinearLayout.LayoutParams a = new LinearLayout.LayoutParams(0, dp(50), 1f);
        LinearLayout.LayoutParams b = new LinearLayout.LayoutParams(0, dp(50), 1.4f);
        b.leftMargin = dp(10);
        row.addView(close, a);
        row.addView(again, b);
        box.addView(row, lp(MATCH, WRAP, 22));
    }

    private String tipFor(NetProbe.Result r, int gBw, int gLoss, int gJit) {
        String net = connectionName();
        if (net.startsWith("Wi-Fi") && (gLoss < 2 || gJit < 2 || gBw < 2)) {
            return net.contains("2,4") ? "Consejo: estás en Wi-Fi de 2,4 GHz. Conéctate a la red de 5 GHz de tu router o acércate a él para jugar mejor."
                    : "Consejo: acércate al router o, si puedes, usa un cable de red. Evita descargas en otros equipos mientras juegas.";
        }
        if (net.startsWith("Datos")) return "Consejo: con datos móviles la calidad depende de la señal. En Wi-Fi de 5 GHz jugarás mejor y sin gastar tu plan.";
        if (gLoss < 2 || gJit < 2) return "Consejo: cierra descargas y videos en otros equipos de tu casa mientras juegas.";
        return null;
    }

    private String connectionName() {
        if (Build.VERSION.SDK_INT < 23) return "desconocida";
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkCapabilities c = cm.getNetworkCapabilities(cm.getActiveNetwork());
            if (c == null) return "desconocida";
            if (c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return "Cable de red";
            if (c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                WifiInfo wi = wm != null ? wm.getConnectionInfo() : null;
                int f = wi != null ? wi.getFrequency() : 0;
                return f >= 5900 ? "Wi-Fi 6 GHz" : f >= 4900 ? "Wi-Fi 5 GHz" : f > 0 ? "Wi-Fi 2,4 GHz" : "Wi-Fi";
            }
            if (c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) return "Datos móviles";
        } catch (Exception ignored) {
        }
        return "desconocida";
    }
}
