package com.limelight;

import android.app.Activity;
import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Build;
import android.os.IBinder;
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
import android.widget.FrameLayout;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import com.limelight.binding.PlatformBinding;
import com.limelight.computers.ComputerManagerService;
import com.limelight.nova.NovaApi;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.NvHTTP;
import com.limelight.nvstream.http.PairingManager;
import com.limelight.utils.ServerHelper;

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

    // «Jugar» de un toque: el servicio de equipos de Moonlight (agregar, emparejar) y el estado de la conexion
    private ComputerManagerService.ComputerManagerBinder managerBinder;
    private boolean connecting;
    private String connectMsg = "";
    private final ServiceConnection serviceConnection = new ServiceConnection() {
        public void onServiceConnected(ComponentName className, IBinder binder) {
            final ComputerManagerService.ComputerManagerBinder local = (ComputerManagerService.ComputerManagerBinder) binder;
            new Thread(() -> {
                local.waitForReady();
                managerBinder = local;
            }).start();
        }

        public void onServiceDisconnected(ComponentName className) {
            managerBinder = null;
        }
    };

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
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(NIGHT);
        loadFonts();
        FrameLayout frame = new FrameLayout(this);
        frame.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Color.parseColor("#1B1240"), Color.parseColor("#0B0A1E"), NIGHT, NIGHT}));
        // brillo morado de la marca arriba a la derecha
        View glow = new View(this);
        GradientDrawable g = new GradientDrawable();
        g.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        g.setColors(new int[]{Color.parseColor("#559B4DFF"), Color.TRANSPARENT});
        g.setGradientRadius(dp(260));
        glow.setBackground(g);
        FrameLayout.LayoutParams lpGlow = new FrameLayout.LayoutParams(dp(520), dp(520));
        lpGlow.gravity = Gravity.TOP | Gravity.END;
        lpGlow.topMargin = -dp(260);
        lpGlow.rightMargin = -dp(220);
        frame.addView(glow, lpGlow);
        root = new ScrollView(this);
        root.setFillViewport(true);
        root.setClipToPadding(false);
        frame.addView(root, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        // el contenido arranca debajo de la barra de estado (hora, bateria) y termina encima de la barra de navegacion
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        setContentView(frame);
        frame.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
        bindService(new Intent(this, ComputerManagerService.class), serviceConnection, Service.BIND_AUTO_CREATE);
        show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            unbindService(serviceConnection);
        } catch (Exception ignored) {
        }
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

    private Typeface sora = Typeface.DEFAULT;
    private Typeface soraSemi = Typeface.DEFAULT_BOLD;
    private Typeface soraBold = Typeface.DEFAULT_BOLD;

    // Sora (la de la marca) es variable: en Android 8+ se pide cada grosor; en los viejos se usa normal y negrita simulada
    private void loadFonts() {
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                sora = new Typeface.Builder(getAssets(), "sora.ttf").setFontVariationSettings("'wght' 400").build();
                soraSemi = new Typeface.Builder(getAssets(), "sora.ttf").setFontVariationSettings("'wght' 600").build();
                soraBold = new Typeface.Builder(getAssets(), "sora.ttf").setFontVariationSettings("'wght' 700").build();
            } else {
                sora = Typeface.createFromAsset(getAssets(), "sora.ttf");
                soraSemi = Typeface.create(sora, Typeface.BOLD);
                soraBold = soraSemi;
            }
        } catch (Exception ignored) {
        }
    }

    private TextView text(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(bold ? soraBold : sora);
        return t;
    }

    private TextView semi(String s, float sp, int color) {
        TextView t = text(s, sp, color, false);
        t.setTypeface(soraSemi);
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
        b.setTypeface(soraSemi);
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
        e.setSingleLine(true);
        // el tipo va DESPUES de setSingleLine: setSingleLine quita el ocultamiento y la contrasena se veia (16-nov-2026)
        e.setInputType(type);
        if ((type & InputType.TYPE_TEXT_VARIATION_PASSWORD) == InputType.TYPE_TEXT_VARIATION_PASSWORD) {
            e.setTransformationMethod(android.text.method.PasswordTransformationMethod.getInstance());
        }
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        e.setTypeface(sora);
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
        loginButton.setBackground(gradient(26));
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

    private void setConnect(String msg) {
        ui.post(() -> {
            connectMsg = msg;
            renderHome();
        });
    }

    /**
     * «Jugar»: agrega la consola por su direccion publica, la primera vez empareja este celular sin codigos (el codigo se registra en
     * la cuenta por la API y el agente de la consola lo confirma) y abre la lista de apps de la consola.
     */
    private void startPlay() {
        if (managerBinder == null) {
            wakeError = "Nova Play se está preparando. Inténtalo de nuevo en unos segundos.";
            renderHome();
            return;
        }
        final String address = myHost.optString("publicHost");
        final String hostId = myHost.optString("id");
        connecting = true;
        connectMsg = "Conectando con tu consola…";
        wakeError = "";
        renderHome();
        new Thread(() -> {
            String err = null;
            ComputerDetails computer = null;
            try {
                String host = address;
                int port = NvHTTP.DEFAULT_HTTP_PORT;
                int colon = address.lastIndexOf(':');
                if (colon > 0 && address.indexOf(':') == colon) {
                    host = address.substring(0, colon);
                    try {
                        port = Integer.parseInt(address.substring(colon + 1));
                    } catch (NumberFormatException ignored) {
                    }
                }
                ComputerDetails details = new ComputerDetails();
                details.manualAddress = new ComputerDetails.AddressTuple(host, port);
                if (!managerBinder.addComputerBlocking(details)) {
                    err = "No pudimos conectar con tu consola. Revisa tu internet e inténtalo de nuevo.";
                } else {
                    computer = managerBinder.getComputer(details.uuid);
                    if (computer == null) computer = details;
                    NvHTTP http = new NvHTTP(ServerHelper.getCurrentAddressFromComputer(computer), computer.httpsPort,
                            managerBinder.getUniqueId(), computer.serverCert, PlatformBinding.getCryptoProvider(NovaHome.this));
                    if (http.getPairState() != PairingManager.PairState.PAIRED) {
                        setConnect("Preparando tu celular para jugar (solo la primera vez)…");
                        String pin = PairingManager.generatePinString();
                        JSONObject body = new JSONObject().put("hostId", hostId).put("pin", pin).put("name", "Nova Play " + Build.MODEL);
                        Object[] r = api.postNow("/api/v1/accounts/" + accountId + "/play/devices", body);
                        int status = (Integer) r[0];
                        if (status != 200 && status != 201) {
                            err = NovaApi.errorMessage(status, r[1]);
                        } else {
                            PairingManager pm = http.getPairingManager();
                            PairingManager.PairState st = pm.pair(http.getServerInfo(true), pin);
                            if (st == PairingManager.PairState.PAIRED) {
                                ComputerDetails stored = managerBinder.getComputer(computer.uuid);
                                if (stored != null) stored.serverCert = pm.getPairedCert();
                                managerBinder.invalidateStateForComputer(computer.uuid);
                            } else {
                                err = "No se pudo emparejar tu celular con la consola. Inténtalo de nuevo en un minuto.";
                            }
                        }
                    }
                }
            } catch (Exception e) {
                err = "No pudimos conectar con tu consola. Inténtalo de nuevo.";
            }
            final String fe = err;
            final ComputerDetails fc = computer;
            ui.post(() -> {
                connecting = false;
                if (fe != null || fc == null) {
                    wakeError = fe != null ? fe : "No pudimos conectar con tu consola.";
                    renderHome();
                    return;
                }
                renderHome();
                Intent i = new Intent(NovaHome.this, AppView.class);
                i.putExtra(AppView.NAME_EXTRA, fc.name);
                i.putExtra(AppView.UUID_EXTRA, fc.uuid);
                i.putExtra(AppView.NEW_PAIR_EXTRA, true);
                startActivity(i);
            });
        }).start();
    }

    private void showHome() {
        homeBox = new LinearLayout(this);
        homeBox.setOrientation(LinearLayout.VERTICAL);
        homeBox.setPadding(dp(20), dp(14), dp(20), dp(24));
        root.addView(homeBox, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        renderHome();
    }

    private GradientDrawable gradient(float radiusDp) {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{Color.parseColor("#8A3DF5"), Color.parseColor("#A95CFF")});
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    // dato compacto (saldo, tiempo, paquete): tres en una fila
    private LinearLayout stat(String k, String v, int accent) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(12), dp(12), dp(10), dp(12));
        c.setBackground(box(Color.parseColor("#B30D1022"), 16, Color.parseColor("#1AFFFFFF")));
        View bar = new View(this);
        bar.setBackground(box(accent, 2, 0));
        c.addView(bar, new LinearLayout.LayoutParams(dp(18), dp(3)));
        TextView key = text(k, 11, MIST, false);
        key.setLetterSpacing(0.04f);
        c.addView(key, lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 8));
        TextView val = text(v, 15, FROST, true);
        val.setMaxLines(1);
        c.addView(val, lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 3));
        return c;
    }

    private void renderHome() {
        if (homeBox == null || !api.isLoggedIn()) return;
        homeBox.removeAllViews();

        // cabecera: logo a la izquierda, inicial del usuario a la derecha
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.novaplay_logo);
        logo.setAdjustViewBounds(true);
        logo.setScaleType(ImageView.ScaleType.FIT_START);
        head.addView(logo, new LinearLayout.LayoutParams(0, dp(24), 1f));
        String first = api.getFirstName();
        String email = api.getEmail();
        String initial = !first.isEmpty() ? first.substring(0, 1) : !email.isEmpty() ? email.substring(0, 1) : "N";
        TextView avatar = text(initial.toUpperCase(), 15, Color.WHITE, true);
        avatar.setGravity(Gravity.CENTER);
        GradientDrawable av = gradient(20);
        av.setShape(GradientDrawable.OVAL);
        avatar.setBackground(av);
        head.addView(avatar, new LinearLayout.LayoutParams(dp(38), dp(38)));
        homeBox.addView(head, lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(44), 4));

        homeBox.addView(text(greeting(), 26, FROST, true), lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 22));
        homeBox.addView(text("Tu PC gamer en la nube, lista cuando la necesites.", 14, MIST, false), lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 4));

        if (play == null) {
            ProgressBar p = new ProgressBar(this);
            LinearLayout.LayoutParams lpP = new LinearLayout.LayoutParams(dp(40), dp(40));
            lpP.gravity = Gravity.CENTER_HORIZONTAL;
            lpP.topMargin = dp(40);
            homeBox.addView(p, lpP);
        } else {
            long pkg = play.optLong("packageMinutesLeft");
            LinearLayout stats = new LinearLayout(this);
            LinearLayout.LayoutParams c1 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
            LinearLayout.LayoutParams c2 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
            c2.leftMargin = dp(8);
            LinearLayout.LayoutParams c3 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
            c3.leftMargin = dp(8);
            stats.addView(stat("SALDO", money(play.optLong("balanceMinor")), VIOLET), c1);
            stats.addView(stat("PARA JUGAR", hm(play.optLong("minutesAffordable")), ELECTRIC), c2);
            stats.addView(stat("PAQUETE", pkg > 0 ? hm(pkg) : "Sin paquete", OK), c3);
            homeBox.addView(stats, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 20));

            // la consola del jugador
            homeBox.addView(semi("Tu consola", 16, FROST), lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 26));
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setGravity(Gravity.CENTER_HORIZONTAL);
            card.setPadding(dp(20), dp(22), dp(20), dp(20));
            GradientDrawable cardBg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                    new int[]{Color.parseColor("#2A1A5E"), Color.parseColor("#141433"), INK});
            cardBg.setCornerRadius(dp(26));
            cardBg.setStroke(dp(1), Color.parseColor("#339B4DFF"));
            card.setBackground(cardBg);
            homeBox.addView(card, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 10));

            // la N de la marca sobre un halo morado
            FrameLayout halo = new FrameLayout(this);
            View ring = new View(this);
            GradientDrawable rg = new GradientDrawable();
            rg.setGradientType(GradientDrawable.RADIAL_GRADIENT);
            rg.setColors(new int[]{Color.parseColor("#669B4DFF"), Color.TRANSPARENT});
            rg.setGradientRadius(dp(70));
            ring.setBackground(rg);
            halo.addView(ring, new FrameLayout.LayoutParams(dp(140), dp(140)));
            ImageView n = new ImageView(this);
            n.setImageResource(R.drawable.novaplay_n);
            FrameLayout.LayoutParams lpN = new FrameLayout.LayoutParams(dp(92), dp(92));
            lpN.gravity = Gravity.CENTER;
            halo.addView(n, lpN);
            card.addView(halo, new LinearLayout.LayoutParams(dp(140), dp(140)));

            boolean starting = waking || connecting || (myHost != null && myHost.optBoolean("starting"));
            boolean online = myHost != null && myHost.optBoolean("online");
            boolean asleep = myHost != null && myHost.optBoolean("asleep");
            boolean canStart = play.optBoolean("canStart");

            TextView name = text(myHost != null ? myHost.optString("name") : "Sin consola disponible", 24, FROST, true);
            name.setGravity(Gravity.CENTER);
            card.addView(name, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 2));

            // estado en una pastilla
            LinearLayout state = new LinearLayout(this);
            state.setGravity(Gravity.CENTER);
            state.setPadding(dp(14), dp(8), dp(14), dp(8));
            state.setBackground(box(Color.parseColor("#14FFFFFF"), 20, 0));
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
                    : connecting ? connectMsg
                    : starting ? "Encendiendo tu consola… estará lista en 20 a 30 segundos."
                    : online ? "Encendida y lista para jugar"
                    : asleep ? "Apagada para ahorrar energía" : "Sin conexión en este momento", 14, FROST, false);
            st.setPadding(dp(8), 0, 0, 0);
            st.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            state.addView(st);
            LinearLayout.LayoutParams lpState = lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 10);
            lpState.gravity = Gravity.CENTER_HORIZONTAL;
            card.addView(state, lpState);

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
            main.setBackground(gradient(27));
            main.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
            main.setElevation(dp(6));
            main.setOnClickListener(v -> {
                if (myHost != null && myHost.optBoolean("online")) startPlay();
                else wake();
            });
            card.addView(main, lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(54), 20));

            // «Prueba de red» y «Mis consolas» lado a lado
            LinearLayout more = new LinearLayout(this);
            Button net = button("Prueba de red", false);
            net.setOnClickListener(v -> {
                Intent i = new Intent(this, NetTestActivity.class);
                String addr = myHost != null ? myHost.optString("publicHost") : "";
                int colon = addr.lastIndexOf(':');
                if (colon > 0 && addr.indexOf(':') == colon) addr = addr.substring(0, colon);
                if (!addr.isEmpty()) i.putExtra(NetTestActivity.HOST_EXTRA, addr);
                startActivity(i);
            });
            Button consoles = button("Mis consolas", false);
            consoles.setOnClickListener(v -> startActivity(new Intent(this, PcView.class)));
            LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(0, dp(50), 1f);
            LinearLayout.LayoutParams half2 = new LinearLayout.LayoutParams(0, dp(50), 1f);
            half2.leftMargin = dp(10);
            more.addView(net, half);
            more.addView(consoles, half2);
            card.addView(more, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 10));
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
        homeBox.addView(foot, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 26));
    }
}
