package com.limelight;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import com.limelight.nova.NovaApi;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Calendar;

/**
 * Nova Play: pantalla principal propia. Sin sesion: iniciar sesion con la cuenta de novawebcolombia.com (con verificacion en dos
 * pasos si la tiene). Con sesion: saludo, saldo y tiempo de juego, y la consola del jugador con «Encender mi consola» / «Jugar».
 * «Mis consolas» abre la lista de equipos (PcView). Toda la interfaz se arma en codigo, con los colores de la marca.
 */
public class NovaHome extends Activity {
    private static final int NIGHT = Color.parseColor("#06070F");
    private static final int INK = Color.parseColor("#0D1022");
    private static final int CARD = Color.parseColor("#121530");
    private static final int FROST = Color.parseColor("#EEF0FA");
    private static final int MIST = Color.parseColor("#8D93B0");
    private static final int VIOLET = Color.parseColor("#9B4DFF");
    private static final int LILAC = Color.parseColor("#B98AFF");
    private static final int OK = Color.parseColor("#3EE08F");
    private static final int ELECTRIC = Color.parseColor("#2E8BFF");
    private static final int WARN = Color.parseColor("#FFC24A");
    private static final int BAD = Color.parseColor("#FF8A8A");

    private NovaApi api;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private ScrollView root;

    // inicio de sesion
    private boolean twoFactor;
    private EditText emailField, passwordField, codeField;
    private TextView loginError, loginTitle, loginSub;
    private Button loginButton;
    private ProgressBar loginSpinner;

    // inicio
    private String accountId = "";
    private JSONObject play;
    private JSONObject myHost;
    private boolean waking;
    private String wakeError = "";
    private LinearLayout homeBox;

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            refresh();
            boolean fast = waking || (myHost != null && myHost.optBoolean("starting"));
            ui.postDelayed(this, fast ? 5000 : 30000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        api = NovaApi.get(this);
        getWindow().setStatusBarColor(NIGHT);
        getWindow().setNavigationBarColor(NIGHT);
        root = new ScrollView(this);
        root.setFillViewport(true);
        root.setBackgroundColor(NIGHT);
        setContentView(root);
        show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (api.isLoggedIn()) {
            ui.removeCallbacks(poll);
            ui.post(poll);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(poll);
    }

    // ------------------------------------------------------------------ utilidades de interfaz
    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private TextView text(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }

    private GradientDrawable box(int color, float radiusDp, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        if (stroke != 0) g.setStroke(dp(1), stroke);
        return g;
    }

    private Button button(String label, boolean primary) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        b.setTextColor(primary ? Color.WHITE : FROST);
        b.setBackground(primary ? box(VIOLET, 26, 0) : box(Color.TRANSPARENT, 26, Color.parseColor("#33FFFFFF")));
        b.setPadding(dp(22), 0, dp(22), 0);
        b.setMinHeight(dp(52));
        b.setStateListAnimator(null);
        return b;
    }

    private EditText field(String hint, int type) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setHintTextColor(MIST);
        e.setTextColor(FROST);
        e.setInputType(type);
        e.setSingleLine(true);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        e.setBackground(box(CARD, 14, Color.parseColor("#26FFFFFF")));
        e.setPadding(dp(16), dp(14), dp(16), dp(14));
        return e;
    }

    private LinearLayout.LayoutParams lp(int w, int h, int top) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.topMargin = dp(top);
        return p;
    }

    private void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception ignored) {
        }
    }

    private void show() {
        root.removeAllViews();
        if (api.isLoggedIn()) showHome();
        else showLogin();
    }

    // ------------------------------------------------------------------ inicio de sesion
    private void showLogin() {
        ui.removeCallbacks(poll);
        LinearLayout wrap = new LinearLayout(this);
        wrap.setGravity(Gravity.CENTER);
        wrap.setPadding(dp(20), dp(40), dp(20), dp(40));
        root.addView(wrap, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(26), dp(30), dp(26), dp(26));
        card.setBackground(box(INK, 24, Color.parseColor("#14FFFFFF")));
        wrap.addView(card, new LinearLayout.LayoutParams(Math.min(dp(440), getResources().getDisplayMetrics().widthPixels - dp(40)), ViewGroup.LayoutParams.WRAP_CONTENT));

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.novaplay_logo);
        logo.setAdjustViewBounds(true);
        LinearLayout.LayoutParams lpLogo = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(34));
        lpLogo.gravity = Gravity.CENTER_HORIZONTAL;
        card.addView(logo, lpLogo);

        loginTitle = text("Inicia sesión", 24, FROST, true);
        loginTitle.setGravity(Gravity.CENTER);
        card.addView(loginTitle, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 18));
        loginSub = text("Usa la misma cuenta de novawebcolombia.com.", 14, MIST, false);
        loginSub.setGravity(Gravity.CENTER);
        card.addView(loginSub, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 6));

        emailField = field("Correo", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        emailField.setText(api.getEmail());
        card.addView(emailField, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 20));
        passwordField = field("Contraseña", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        passwordField.setImeOptions(EditorInfo.IME_ACTION_DONE);
        passwordField.setOnEditorActionListener((v, actionId, event) -> { submit(); return true; });
        card.addView(passwordField, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 12));
        codeField = field("Código de verificación", InputType.TYPE_CLASS_TEXT);
        codeField.setVisibility(View.GONE);
        codeField.setImeOptions(EditorInfo.IME_ACTION_DONE);
        codeField.setOnEditorActionListener((v, actionId, event) -> { submit(); return true; });
        card.addView(codeField, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 20));

        loginError = text("", 14, BAD, false);
        loginError.setVisibility(View.GONE);
        card.addView(loginError, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 12));

        loginButton = button("Entrar", true);
        loginButton.setOnClickListener(v -> submit());
        card.addView(loginButton, lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(52), 18));
        loginSpinner = new ProgressBar(this);
        loginSpinner.setVisibility(View.GONE);
        LinearLayout.LayoutParams lpSpin = new LinearLayout.LayoutParams(dp(30), dp(30));
        lpSpin.gravity = Gravity.CENTER_HORIZONTAL;
        lpSpin.topMargin = dp(10);
        card.addView(loginSpinner, lpSpin);

        LinearLayout links = new LinearLayout(this);
        links.setGravity(Gravity.CENTER);
        TextView forgot = text("¿Olvidaste tu contraseña?", 14, MIST, false);
        forgot.setOnClickListener(v -> openUrl("https://panel.novawebcolombia.com/forgot-password"));
        TextView create = text("Crear cuenta", 14, LILAC, true);
        create.setPadding(dp(18), 0, 0, 0);
        create.setOnClickListener(v -> openUrl("https://panel.novawebcolombia.com/register"));
        links.addView(forgot);
        links.addView(create);
        card.addView(links, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 16));
    }

    private void setBusy(boolean busy) {
        loginButton.setEnabled(!busy);
        loginButton.setAlpha(busy ? 0.5f : 1f);
        loginSpinner.setVisibility(busy ? View.VISIBLE : View.GONE);
    }

    private void loginResult(boolean ok, boolean need2fa, String msg) {
        ui.post(() -> {
            setBusy(false);
            if (!ok) {
                loginError.setText(msg);
                loginError.setVisibility(View.VISIBLE);
            } else if (need2fa) {
                twoFactor = true;
                loginTitle.setText("Verificación en dos pasos");
                loginSub.setText("Escribe el código de 6 dígitos de tu app de autenticación (o un código de recuperación).");
                emailField.setVisibility(View.GONE);
                passwordField.setVisibility(View.GONE);
                codeField.setVisibility(View.VISIBLE);
                codeField.requestFocus();
                loginButton.setText("Verificar");
            } else {
                twoFactor = false;
                show();
                ui.post(poll);
            }
        });
    }

    private void submit() {
        loginError.setVisibility(View.GONE);
        setBusy(true);
        if (twoFactor) {
            api.loginTwoFactor(codeField.getText().toString(), this::loginResult);
        } else {
            api.login(emailField.getText().toString(), passwordField.getText().toString(), this::loginResult);
        }
    }

    // ------------------------------------------------------------------ inicio (con sesion)
    private static String money(long minor) {
        long pesos = Math.max(0, Math.round(minor / 100.0));
        String s = Long.toString(pesos);
        StringBuilder out = new StringBuilder();
        int n = 0;
        for (int i = s.length() - 1; i >= 0; i--) {
            out.insert(0, s.charAt(i));
            if (++n % 3 == 0 && i > 0) out.insert(0, '.');
        }
        return "$ " + out;
    }

    private static String hm(long m) {
        m = Math.max(0, m);
        return m >= 60 ? (m / 60) + " h " + (m % 60) + " min" : m + " min";
    }

    private String greeting() {
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        String n = api.getFirstName();
        return (h < 12 ? "Buenos días" : h < 19 ? "Buenas tardes" : "Buenas noches") + (n.isEmpty() ? "" : ", " + n);
    }

    private JSONObject pickHost(JSONObject p) {
        JSONArray hosts = p.optJSONArray("hosts");
        if (hosts == null || hosts.length() == 0) return null;
        JSONArray devices = p.optJSONArray("devices");
        if (devices != null) {
            for (int i = 0; i < hosts.length(); i++) {
                JSONObject h = hosts.optJSONObject(i);
                for (int j = 0; j < devices.length(); j++) {
                    JSONObject d = devices.optJSONObject(j);
                    if (h != null && d != null && h.optString("id").equals(d.optString("hostId")) && "PAIRED".equals(d.optString("status"))) return h;
                }
            }
        }
        for (int k = 0; k < hosts.length(); k++) {
            JSONObject h = hosts.optJSONObject(k);
            if (h != null && (h.optBoolean("online") || h.optBoolean("asleep") || h.optBoolean("starting")) && h.optInt("busy") < h.optInt("maxSessions", 1)) return h;
        }
        return hosts.optJSONObject(0);
    }

    private void refresh() {
        if (!api.isLoggedIn()) {
            ui.post(this::show);
            return;
        }
        if (accountId.isEmpty()) {
            api.get("/api/v1/accounts", (status, body) -> {
                if (status == 200 && body instanceof JSONArray && ((JSONArray) body).length() > 0) {
                    accountId = ((JSONArray) body).optJSONObject(0).optString("id");
                    refresh();
                } else if (status == 401) {
                    ui.post(this::show);
                }
            });
            return;
        }
        api.get("/api/v1/accounts/" + accountId + "/play", (status, body) -> {
            if (status == 200 && body instanceof JSONObject) {
                play = (JSONObject) body;
                myHost = pickHost(play);
                if (myHost != null && myHost.optBoolean("online")) waking = false;
                ui.post(this::renderHome);
            } else if (status == 401) {
                ui.post(this::show);
            }
        });
    }

    private void wake() {
        if (myHost == null) return;
        waking = true;
        wakeError = "";
        renderHome();
        api.post("/api/v1/accounts/" + accountId + "/play/hosts/" + myHost.optString("id") + "/wake", null, (status, body) -> {
            if (status != 200) {
                waking = false;
                wakeError = NovaApi.errorMessage(status, body);
            }
            refresh();
            ui.post(this::renderHome);
        });
    }

    private void showHome() {
        homeBox = new LinearLayout(this);
        homeBox.setOrientation(LinearLayout.VERTICAL);
        homeBox.setPadding(dp(22), dp(28), dp(22), dp(28));
        root.addView(homeBox, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        renderHome();
    }

    private LinearLayout stat(String k, String v) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(18), dp(14), dp(18), dp(14));
        c.setBackground(box(INK, 18, Color.parseColor("#14FFFFFF")));
        c.addView(text(k, 13, MIST, false));
        TextView val = text(v, 22, FROST, true);
        c.addView(val, lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 4));
        return c;
    }

    private void renderHome() {
        if (homeBox == null || !api.isLoggedIn()) return;
        homeBox.removeAllViews();

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.novaplay_logo);
        logo.setAdjustViewBounds(true);
        homeBox.addView(logo, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(26)));

        homeBox.addView(text(greeting(), 28, FROST, true), lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 24));
        homeBox.addView(text("Tu PC gamer en la nube, lista cuando la necesites.", 14, MIST, false), lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 4));

        if (play == null) {
            ProgressBar p = new ProgressBar(this);
            LinearLayout.LayoutParams lpP = new LinearLayout.LayoutParams(dp(40), dp(40));
            lpP.gravity = Gravity.CENTER_HORIZONTAL;
            lpP.topMargin = dp(40);
            homeBox.addView(p, lpP);
        } else {
            long pkg = play.optLong("packageMinutesLeft");
            homeBox.addView(stat("Saldo", money(play.optLong("balanceMinor"))), lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 20));
            homeBox.addView(stat("Tiempo de juego disponible", hm(play.optLong("minutesAffordable"))), lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 10));
            homeBox.addView(stat("Horas de paquete", pkg > 0 ? hm(pkg) : "Sin paquete"), lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 10));

            // la consola del jugador
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setGravity(Gravity.CENTER_HORIZONTAL);
            card.setPadding(dp(22), dp(24), dp(22), dp(24));
            card.setBackground(box(INK, 24, Color.parseColor("#14FFFFFF")));
            homeBox.addView(card, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 18));

            ImageView n = new ImageView(this);
            n.setImageResource(R.drawable.novaplay_n);
            card.addView(n, new LinearLayout.LayoutParams(dp(120), dp(120)));

            boolean starting = waking || (myHost != null && myHost.optBoolean("starting"));
            boolean online = myHost != null && myHost.optBoolean("online");
            boolean asleep = myHost != null && myHost.optBoolean("asleep");
            boolean canStart = play.optBoolean("canStart");

            TextView label = text(myHost != null ? "Tu consola" : "Sin consola disponible", 13, MIST, false);
            label.setGravity(Gravity.CENTER);
            card.addView(label, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 14));
            TextView name = text(myHost != null ? myHost.optString("name") : "Nova Play", 26, FROST, true);
            name.setGravity(Gravity.CENTER);
            card.addView(name, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 2));

            LinearLayout state = new LinearLayout(this);
            state.setGravity(Gravity.CENTER);
            if (starting) {
                state.addView(new ProgressBar(this), new LinearLayout.LayoutParams(dp(22), dp(22)));
            } else {
                View dot = new View(this);
                GradientDrawable d = new GradientDrawable();
                d.setShape(GradientDrawable.OVAL);
                d.setColor(online ? OK : asleep ? ELECTRIC : MIST);
                dot.setBackground(d);
                state.addView(dot, new LinearLayout.LayoutParams(dp(10), dp(10)));
            }
            TextView st = text(myHost == null ? "Escríbenos a soporte para asignarte una consola."
                    : starting ? "Encendiendo tu consola… estará lista en 20 a 30 segundos."
                    : online ? "Encendida y lista para jugar"
                    : asleep ? "Apagada para ahorrar energía" : "Sin conexión en este momento", 14, FROST, false);
            st.setPadding(dp(10), 0, 0, 0);
            state.addView(st);
            card.addView(state, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 10));

            if (!canStart) {
                TextView w = text("Necesitas tiempo de juego: recarga tu saldo o compra un paquete en novawebcolombia.com.", 13, WARN, false);
                w.setGravity(Gravity.CENTER);
                card.addView(w, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 10));
            }
            if (!wakeError.isEmpty()) {
                TextView e = text(wakeError, 13, BAD, false);
                e.setGravity(Gravity.CENTER);
                card.addView(e, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 10));
            }

            Button main = button(online ? "Jugar" : "Encender mi consola", true);
            boolean enabled = myHost != null && canStart && !starting && (online || asleep);
            main.setEnabled(enabled);
            main.setAlpha(enabled ? 1f : 0.45f);
            main.setOnClickListener(v -> {
                if (myHost != null && myHost.optBoolean("online")) startActivity(new Intent(this, PcView.class));
                else wake();
            });
            card.addView(main, lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(54), 18));

            Button consoles = button("Mis consolas", false);
            consoles.setOnClickListener(v -> startActivity(new Intent(this, PcView.class)));
            card.addView(consoles, lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(50), 10));
        }

        LinearLayout foot = new LinearLayout(this);
        foot.setGravity(Gravity.CENTER_VERTICAL);
        TextView who = text(api.getEmail().isEmpty() ? "" : "Conectado como " + api.getEmail(), 13, MIST, false);
        foot.addView(who, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView out = text("Cerrar sesión", 14, LILAC, true);
        out.setOnClickListener(v -> {
            api.logout();
            accountId = "";
            play = null;
            myHost = null;
            root.removeAllViews();
            showLogin();
        });
        foot.addView(out);
        homeBox.addView(foot, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 22));
    }
}
