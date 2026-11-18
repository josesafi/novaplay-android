package com.limelight;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.text.method.HideReturnsTransformationMethod;
import android.text.method.PasswordTransformationMethod;
import android.util.Patterns;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.autofill.AutofillManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.limelight.nova.NovaApi;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.EnumMap;
import java.util.Map;

/**
 * Nova Play: la cuenta de novawebcolombia.com sin salir de la app (18-nov-2026).
 *   MODE_REGISTER  crear cuenta (con la pregunta antibot «¿Cuánto es 2 + 4?» en vez del captcha de la web) y «Revisa tu correo»
 *   MODE_FORGOT    recuperar la contraseña (el enlace del correo abre la web para escribir la nueva)
 *   MODE_VERIFY    «Revisa tu correo» con «Reenviar correo» (para quien creó la cuenta y no la ha confirmado)
 *   MODE_SECURITY  verificación en dos pasos con app autenticadora: activar (QR + código + códigos de recuperación) y desactivar
 * Cada pregunta antibot sirve una sola vez: despues de cada envio se pide otra. Toda espera muestra rueda y estado.
 * Al volver a NovaHome devuelve EXTRA_EMAIL para rellenar el correo del inicio de sesion.
 */
public class NovaAccountActivity extends Activity {
    public static final String EXTRA_MODE = "mode";
    public static final String EXTRA_EMAIL = "email";
    public static final String MODE_REGISTER = "register";
    public static final String MODE_FORGOT = "forgot";
    public static final String MODE_VERIFY = "verify";
    public static final String MODE_SECURITY = "security";

    private static final int NIGHT = Color.parseColor("#06070F");
    private static final int INK = Color.parseColor("#0D1022");
    private static final int CARD = Color.parseColor("#121530");
    private static final int FROST = Color.parseColor("#EEF0FA");
    private static final int MIST = Color.parseColor("#8D93B0");
    private static final int LILAC = Color.parseColor("#B98AFF");
    private static final int OK = Color.parseColor("#3EE08F");
    private static final int WARN = Color.parseColor("#FFC24A");
    private static final int BAD = Color.parseColor("#FF8A8A");

    private NovaApi api;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private ScrollView root;
    private LinearLayout card;
    private String resultEmail = "";

    // antibot
    private String challengeToken = "";
    private String termsVersion = "";
    private int passwordMin = 12;
    private TextView challengeQ;
    private EditText challengeA;

    // estado comun de cada formulario
    private TextView status;
    private ProgressBar spinner;
    private Button primary;

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
        root = new ScrollView(this);
        root.setFillViewport(true);
        root.setClipToPadding(false);
        frame.addView(root, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        setContentView(frame);
        frame.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);

        String mode = getIntent().getStringExtra(EXTRA_MODE);
        String email = getIntent().getStringExtra(EXTRA_EMAIL);
        if (MODE_FORGOT.equals(mode)) showForgot(email);
        else if (MODE_VERIFY.equals(mode)) showCheckMail(email == null ? "" : email);
        else if (MODE_SECURITY.equals(mode)) showSecurity();
        else showRegister();
    }

    @Override
    public void finish() {
        if (!resultEmail.isEmpty()) setResult(RESULT_OK, new Intent().putExtra(EXTRA_EMAIL, resultEmail));
        super.finish();
    }

    // ------------------------------------------------------------------ utilidades de interfaz (las mismas de NovaHome)
    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private Typeface sora = Typeface.DEFAULT;
    private Typeface soraSemi = Typeface.DEFAULT_BOLD;
    private Typeface soraBold = Typeface.DEFAULT_BOLD;

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

    private GradientDrawable box(int color, float radiusDp, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        if (stroke != 0) g.setStroke(dp(1), stroke);
        return g;
    }

    private GradientDrawable gradient(float radiusDp) {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{Color.parseColor("#8A3DF5"), Color.parseColor("#A95CFF")});
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private Button button(String label, boolean isPrimary) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        b.setTypeface(soraSemi);
        b.setTextColor(isPrimary ? Color.WHITE : FROST);
        b.setBackground(isPrimary ? gradient(26) : box(Color.TRANSPARENT, 26, Color.parseColor("#33FFFFFF")));
        b.setPadding(dp(22), 0, dp(22), 0);
        b.setMinHeight(dp(52));
        b.setStateListAnimator(null);
        return b;
    }

    private EditText field(String hint, int type, String autofill) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setHintTextColor(MIST);
        e.setTextColor(FROST);
        e.setSingleLine(true);
        e.setInputType(type);
        if ((type & InputType.TYPE_TEXT_VARIATION_PASSWORD) == InputType.TYPE_TEXT_VARIATION_PASSWORD) {
            e.setTransformationMethod(PasswordTransformationMethod.getInstance());
        }
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        e.setTypeface(sora);
        e.setBackground(box(CARD, 14, Color.parseColor("#26FFFFFF")));
        e.setPadding(dp(16), dp(14), dp(16), dp(14));
        if (Build.VERSION.SDK_INT >= 26) {
            if (autofill != null) {
                e.setAutofillHints(autofill);
                e.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_YES);
            } else {
                e.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
            }
        }
        return e;
    }

    /** Contraseña con «Mostrar / Ocultar» a la derecha. */
    private View passwordBox(EditText e) {
        FrameLayout f = new FrameLayout(this);
        e.setPadding(dp(16), dp(14), dp(92), dp(14));
        f.addView(e, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView eye = text("Mostrar", 13, LILAC, true);
        eye.setPadding(dp(14), dp(14), dp(16), dp(14));
        eye.setOnClickListener(v -> {
            boolean hidden = e.getTransformationMethod() instanceof PasswordTransformationMethod;
            e.setTransformationMethod(hidden ? HideReturnsTransformationMethod.getInstance() : PasswordTransformationMethod.getInstance());
            e.setSelection(e.getText().length());
            eye.setText(hidden ? "Ocultar" : "Mostrar");
        });
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
        f.addView(eye, p);
        return f;
    }

    private LinearLayout.LayoutParams lp(int w, int h, int top) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.topMargin = dp(top);
        return p;
    }

    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT, WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    private void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception ignored) {
        }
    }

    /** Tarjeta nueva con logo, titulo, subtitulo y enlace «Volver». */
    private void page(String title, String sub) {
        root.removeAllViews();
        LinearLayout wrap = new LinearLayout(this);
        wrap.setGravity(Gravity.CENTER);
        wrap.setPadding(dp(20), dp(30), dp(20), dp(30));
        root.addView(wrap, new ViewGroup.LayoutParams(MATCH, MATCH));
        card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(26), dp(28), dp(26), dp(26));
        card.setBackground(box(INK, 24, Color.parseColor("#14FFFFFF")));
        wrap.addView(card, new LinearLayout.LayoutParams(Math.min(dp(460), getResources().getDisplayMetrics().widthPixels - dp(40)), WRAP));
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.novaplay_logo);
        logo.setAdjustViewBounds(true);
        LinearLayout.LayoutParams lpLogo = new LinearLayout.LayoutParams(WRAP, dp(30));
        lpLogo.gravity = Gravity.CENTER_HORIZONTAL;
        card.addView(logo, lpLogo);
        TextView t = text(title, 23, FROST, true);
        t.setGravity(Gravity.CENTER);
        card.addView(t, lp(MATCH, WRAP, 16));
        if (sub != null) {
            TextView s = text(sub, 14, MIST, false);
            s.setGravity(Gravity.CENTER);
            card.addView(s, lp(MATCH, WRAP, 6));
        }
        root.scrollTo(0, 0);
    }

    private void backLink(String label) {
        TextView back = text(label, 14, LILAC, true);
        back.setGravity(Gravity.CENTER);
        back.setPadding(0, dp(10), 0, dp(4));
        back.setOnClickListener(v -> finish());
        card.addView(back, lp(MATCH, WRAP, 12));
    }

    /** Boton principal + rueda + mensaje de estado (error en rojo, avance en gris, exito en verde). */
    private void actionRow(String label, View.OnClickListener onClick) {
        status = text("", 14, BAD, false);
        status.setVisibility(View.GONE);
        card.addView(status, lp(MATCH, WRAP, 12));
        primary = button(label, true);
        primary.setOnClickListener(onClick);
        card.addView(primary, lp(MATCH, dp(52), 16));
        spinner = new ProgressBar(this);
        spinner.setVisibility(View.GONE);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(28), dp(28));
        p.gravity = Gravity.CENTER_HORIZONTAL;
        p.topMargin = dp(10);
        card.addView(spinner, p);
    }

    private void busy(boolean on, String msg) {
        primary.setEnabled(!on);
        primary.setAlpha(on ? 0.55f : 1f);
        spinner.setVisibility(on ? View.VISIBLE : View.GONE);
        if (on && msg != null) say(msg, MIST);
    }

    private void say(String msg, int color) {
        status.setText(msg);
        status.setTextColor(color);
        status.setVisibility(msg == null || msg.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private static String msgOf(int code, Object body) {
        if (code == 404) return "Esta función todavía no está disponible. Actualiza la app o inténtalo más tarde.";
        if (code == 429 && body instanceof JSONObject) {
            int s = ((JSONObject) body).optInt("retryAfter", 0);
            String base = NovaApi.errorMessage(code, body);
            return s > 0 && !base.contains("minuto") && !base.contains("segundo") ? base + " (espera " + (s >= 90 ? Math.round(s / 60.0) + " minutos" : s + " segundos") + ")" : base;
        }
        return NovaApi.errorMessage(code, body);
    }

    // ------------------------------------------------------------------ pregunta antibot
    private void challengeBlock() {
        LinearLayout boxQ = new LinearLayout(this);
        boxQ.setOrientation(LinearLayout.VERTICAL);
        boxQ.setPadding(dp(16), dp(14), dp(16), dp(14));
        boxQ.setBackground(box(Color.parseColor("#1A9B4DFF"), 16, Color.parseColor("#559B4DFF")));
        TextView k = text("CONFIRMA QUE ERES UNA PERSONA", 11, LILAC, true);
        boxQ.addView(k);
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        challengeQ = text("Preparando la pregunta…", 17, FROST, true);
        row.addView(challengeQ, new LinearLayout.LayoutParams(0, WRAP, 1f));
        TextView again = text("Otra", 13, LILAC, true);
        again.setPadding(dp(12), dp(8), 0, dp(8));
        again.setOnClickListener(v -> loadChallenge());
        row.addView(again);
        boxQ.addView(row, lp(MATCH, WRAP, 6));
        challengeA = field("Tu respuesta", InputType.TYPE_CLASS_NUMBER, null);
        boxQ.addView(challengeA, lp(MATCH, WRAP, 10));
        card.addView(boxQ, lp(MATCH, WRAP, 16));
        loadChallenge();
    }

    private void loadChallenge() {
        challengeToken = "";
        challengeQ.setText("Preparando la pregunta…");
        challengeQ.setTextColor(MIST);
        if (challengeA != null) challengeA.setText("");
        api.appChallenge((code, body) -> ui.post(() -> {
            if (code == 200 && body instanceof JSONObject) {
                JSONObject o = (JSONObject) body;
                challengeToken = o.optString("token", "");
                termsVersion = o.optString("termsVersion", termsVersion);
                passwordMin = o.optInt("passwordMinLength", passwordMin);
                challengeQ.setText(o.optString("question", "¿Cuánto es 1 + 1?"));
                challengeQ.setTextColor(FROST);
            } else {
                challengeQ.setText("No pudimos preparar la pregunta. Toca «Otra» para intentarlo de nuevo.");
                challengeQ.setTextColor(WARN);
            }
        }));
    }

    /** Agrega token y respuesta al cuerpo; devuelve null (y avisa) si falta. */
    private JSONObject withChallenge(JSONObject body) throws Exception {
        String a = challengeA.getText().toString().trim();
        if (challengeToken.isEmpty()) { say("Espera a que aparezca la pregunta (o toca «Otra»).", BAD); return null; }
        if (a.isEmpty()) { say("Responde la pregunta para confirmar que eres una persona.", BAD); challengeA.requestFocus(); return null; }
        return body.put("appChallengeToken", challengeToken).put("appChallengeAnswer", a);
    }

    // ------------------------------------------------------------------ crear cuenta
    private EditText first, last, email, phone, pass, pass2;
    private CheckBox terms, adult;
    private TextView strength;

    private void showRegister() {
        page("Crea tu cuenta", "Es la misma cuenta de novawebcolombia.com: con ella juegas, recargas y ves tu saldo.");
        LinearLayout names = new LinearLayout(this);
        first = field("Nombre", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS | InputType.TYPE_TEXT_VARIATION_PERSON_NAME, "personGivenName");
        last = field("Apellido", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS | InputType.TYPE_TEXT_VARIATION_PERSON_NAME, "personFamilyName");
        LinearLayout.LayoutParams h1 = new LinearLayout.LayoutParams(0, WRAP, 1f);
        LinearLayout.LayoutParams h2 = new LinearLayout.LayoutParams(0, WRAP, 1f);
        h2.leftMargin = dp(10);
        names.addView(first, h1);
        names.addView(last, h2);
        card.addView(names, lp(MATCH, WRAP, 20));
        email = field("Correo", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, View.AUTOFILL_HINT_EMAIL_ADDRESS);
        card.addView(email, lp(MATCH, WRAP, 12));
        phone = field("Celular (opcional)", InputType.TYPE_CLASS_PHONE, View.AUTOFILL_HINT_PHONE);
        card.addView(phone, lp(MATCH, WRAP, 12));
        pass = field("Contraseña", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD, "newPassword");
        card.addView(passwordBox(pass), lp(MATCH, WRAP, 12));
        strength = text("", 12, MIST, false);
        card.addView(strength, lp(MATCH, WRAP, 6));
        pass2 = field("Repite la contraseña", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD, "newPassword");
        card.addView(passwordBox(pass2), lp(MATCH, WRAP, 10));
        pass.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) {}
            public void afterTextChanged(Editable s) { showStrength(s.toString()); }
        });
        showStrength("");

        terms = check("Acepto los términos y condiciones y la política de privacidad (tratamiento de mis datos).");
        card.addView(terms, lp(MATCH, WRAP, 16));
        LinearLayout legal = new LinearLayout(this);
        TextView tLink = text("Ver términos", 13, LILAC, true);
        tLink.setPadding(dp(36), dp(2), dp(18), dp(6));
        tLink.setOnClickListener(v -> openUrl("https://novawebcolombia.com/terminos"));
        TextView pLink = text("Ver privacidad", 13, LILAC, true);
        pLink.setPadding(0, dp(2), 0, dp(6));
        pLink.setOnClickListener(v -> openUrl("https://novawebcolombia.com/privacidad"));
        legal.addView(tLink);
        legal.addView(pLink);
        card.addView(legal);
        adult = check("Soy mayor de 18 años.");
        card.addView(adult, lp(MATCH, WRAP, 4));

        challengeBlock();
        actionRow("Crear mi cuenta", v -> register());
        backLink("Ya tengo cuenta: iniciar sesión");
    }

    private CheckBox check(String label) {
        CheckBox c = new CheckBox(this);
        c.setText(label);
        c.setTextColor(FROST);
        c.setTypeface(sora);
        c.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        if (Build.VERSION.SDK_INT >= 21) c.setButtonTintList(android.content.res.ColorStateList.valueOf(LILAC));
        return c;
    }

    private void showStrength(String p) {
        int score = 0;
        if (p.length() >= passwordMin) score++;
        if (p.length() >= passwordMin + 4) score++;
        if (p.matches(".*[a-z].*") && p.matches(".*[A-Z].*")) score++;
        if (p.matches(".*[0-9].*")) score++;
        if (p.matches(".*[^A-Za-z0-9].*")) score++;
        if (p.isEmpty()) {
            strength.setText("Mínimo " + passwordMin + " caracteres. Mejor una frase que solo tú conozcas.");
            strength.setTextColor(MIST);
        } else if (p.length() < passwordMin) {
            strength.setText("Muy corta: faltan " + (passwordMin - p.length()) + " caracteres.");
            strength.setTextColor(BAD);
        } else if (score <= 2) {
            strength.setText("Fuerza: aceptable. Agrega números o símbolos para hacerla más segura.");
            strength.setTextColor(WARN);
        } else {
            strength.setText("Fuerza: buena.");
            strength.setTextColor(OK);
        }
    }

    private void register() {
        String f = first.getText().toString().trim(), l = last.getText().toString().trim();
        String m = email.getText().toString().trim(), ph = phone.getText().toString().trim();
        String p = pass.getText().toString(), p2 = pass2.getText().toString();
        if (f.isEmpty() || l.isEmpty()) { say("Escribe tu nombre y tu apellido.", BAD); return; }
        if (!Patterns.EMAIL_ADDRESS.matcher(m).matches()) { say("Revisa el correo: no parece válido.", BAD); email.requestFocus(); return; }
        if (p.length() < passwordMin) { say("La contraseña debe tener al menos " + passwordMin + " caracteres.", BAD); pass.requestFocus(); return; }
        if (!p.equals(p2)) { say("Las dos contraseñas no coinciden.", BAD); pass2.requestFocus(); return; }
        if (!terms.isChecked()) { say("Para crear la cuenta debes aceptar los términos y la política de privacidad.", BAD); return; }
        if (!adult.isChecked()) { say("Debes ser mayor de 18 años para crear la cuenta.", BAD); return; }
        try {
            JSONObject body = new JSONObject().put("email", m).put("password", p).put("firstName", f).put("lastName", l)
                    .put("accountType", "PERSON").put("acceptTerms", true).put("acceptPrivacy", true).put("isAdult", true)
                    .put("termsVersion", termsVersion);
            if (!ph.isEmpty()) body.put("phone", ph);
            body = withChallenge(body);
            if (body == null) return;
            busy(true, "Creando tu cuenta…");
            api.publicPost("/api/v1/auth/register", body, (code, res) -> ui.post(() -> {
                busy(false, null);
                if (code == 202 || code == 200 || code == 201) {
                    resultEmail = m;
                    api.setPendingEmail(m);
                    commitAutofill();
                    showCheckMail(m);
                } else {
                    say(msgOf(code, res), BAD);
                    loadChallenge();
                }
            }));
        } catch (Exception e) {
            say("Algo salió mal. Inténtalo de nuevo.", BAD);
        }
    }

    /** Le dice al administrador de contraseñas (Google) que el formulario se envió: ofrece guardar la contraseña nueva. */
    private void commitAutofill() {
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                AutofillManager af = getSystemService(AutofillManager.class);
                if (af != null) af.commit();
            } catch (Exception ignored) {
            }
        }
    }

    // ------------------------------------------------------------------ revisa tu correo
    private void showCheckMail(String mail) {
        resultEmail = mail;
        page("Revisa tu correo", null);
        TextView t = text("Te enviamos un enlace a " + (mail.isEmpty() ? "tu correo" : mail) + ". Ábrelo, confirma con tu contraseña y vuelve aquí para entrar.", 15, FROST, false);
        t.setGravity(Gravity.CENTER);
        card.addView(t, lp(MATCH, WRAP, 10));
        TextView tip = text("¿No llega? Mira en «Spam» o «Promociones». Puede tardar unos minutos.", 13, MIST, false);
        tip.setGravity(Gravity.CENTER);
        card.addView(tip, lp(MATCH, WRAP, 8));
        Button enter = button("Ya confirmé, entrar", true);
        enter.setOnClickListener(v -> finish());
        card.addView(enter, lp(MATCH, dp(52), 20));

        TextView k = text("¿Necesitas otro correo?", 14, FROST, true);
        card.addView(k, lp(MATCH, WRAP, 22));
        final EditText to = field("Correo", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, View.AUTOFILL_HINT_EMAIL_ADDRESS);
        to.setText(mail);
        card.addView(to, lp(MATCH, WRAP, 8));
        challengeBlock();
        status = text("", 14, BAD, false);
        status.setVisibility(View.GONE);
        card.addView(status, lp(MATCH, WRAP, 12));
        primary = button("Reenviar correo", false);
        card.addView(primary, lp(MATCH, dp(52), 12));
        spinner = new ProgressBar(this);
        spinner.setVisibility(View.GONE);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(28), dp(28));
        p.gravity = Gravity.CENTER_HORIZONTAL;
        p.topMargin = dp(10);
        card.addView(spinner, p);
        primary.setOnClickListener(v -> {
            String m = to.getText().toString().trim();
            if (!Patterns.EMAIL_ADDRESS.matcher(m).matches()) { say("Revisa el correo: no parece válido.", BAD); return; }
            try {
                JSONObject body = withChallenge(new JSONObject().put("email", m));
                if (body == null) return;
                busy(true, "Enviando el correo…");
                api.publicPost("/api/v1/auth/resend-verification", body, (code, res) -> ui.post(() -> {
                    busy(false, null);
                    if (code == 202 || code == 200) {
                        resultEmail = m;
                        say("Listo: si la cuenta está pendiente de confirmar, te llegará un correo nuevo en unos minutos.", OK);
                    } else say(msgOf(code, res), BAD);
                    loadChallenge();
                }));
            } catch (Exception e) {
                say("Algo salió mal. Inténtalo de nuevo.", BAD);
            }
        });
        backLink("Volver al inicio de sesión");
    }

    // ------------------------------------------------------------------ recuperar contraseña
    private void showForgot(String mail) {
        page("Recupera tu contraseña", "Te enviamos un enlace para crear una contraseña nueva. Se abre en el navegador.");
        final EditText to = field("Correo de tu cuenta", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, View.AUTOFILL_HINT_EMAIL_ADDRESS);
        if (mail != null) to.setText(mail);
        card.addView(to, lp(MATCH, WRAP, 20));
        challengeBlock();
        actionRow("Enviar enlace", v -> {
            String m = to.getText().toString().trim();
            if (!Patterns.EMAIL_ADDRESS.matcher(m).matches()) { say("Revisa el correo: no parece válido.", BAD); return; }
            try {
                JSONObject body = withChallenge(new JSONObject().put("email", m));
                if (body == null) return;
                busy(true, "Enviando el enlace…");
                api.publicPost("/api/v1/auth/forgot-password", body, (code, res) -> ui.post(() -> {
                    busy(false, null);
                    if (code == 202 || code == 200) {
                        resultEmail = m;
                        say("Listo: si el correo tiene cuenta, te llegará un enlace en unos minutos. Ábrelo, crea tu contraseña nueva y vuelve a entrar aquí.", OK);
                        primary.setText("Volver a iniciar sesión");
                        primary.setOnClickListener(x -> finish());
                    } else {
                        say(msgOf(code, res), BAD);
                        loadChallenge();
                    }
                }));
            } catch (Exception e) {
                say("Algo salió mal. Inténtalo de nuevo.", BAD);
            }
        });
        backLink("Volver");
    }

    // ------------------------------------------------------------------ verificacion en dos pasos
    private void showSecurity() {
        page("Verificación en dos pasos", "Protege tu cuenta con un código de tu app autenticadora (Google Authenticator, Microsoft Authenticator, Authy…).");
        status = text("Consultando tu cuenta…", 14, MIST, false);
        card.addView(status, lp(MATCH, WRAP, 16));
        spinner = new ProgressBar(this);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(28), dp(28));
        p.gravity = Gravity.CENTER_HORIZONTAL;
        p.topMargin = dp(10);
        card.addView(spinner, p);
        api.session((code, body) -> ui.post(() -> {
            if (code == 200 && body instanceof JSONObject) {
                JSONObject user = ((JSONObject) body).optJSONObject("user");
                boolean on = user != null && user.optBoolean("twoFactorEnabled", false);
                if (on) showDisable();
                else showEnableStart();
            } else if (code == 401) {
                spinner.setVisibility(View.GONE);
                say("Tu sesión venció. Vuelve a iniciar sesión.", BAD);
                backLink("Volver");
            } else {
                spinner.setVisibility(View.GONE);
                say(msgOf(code, body), BAD);
                backLink("Volver");
            }
        }));
    }

    private void showEnableStart() {
        page("Activa el autenticador", "Paso 1 de 3: confirma tu contraseña.");
        TextView why = text("Cuando lo actives, además de tu contraseña te pediremos un código de 6 dígitos que cambia cada 30 segundos. Así nadie puede entrar aunque sepa tu contraseña.", 14, FROST, false);
        card.addView(why, lp(MATCH, WRAP, 14));
        final EditText pw = field("Tu contraseña actual", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD, View.AUTOFILL_HINT_PASSWORD);
        card.addView(passwordBox(pw), lp(MATCH, WRAP, 16));
        actionRow("Continuar", v -> {
            String p = pw.getText().toString();
            if (p.isEmpty()) { say("Escribe tu contraseña.", BAD); return; }
            try {
                busy(true, "Preparando tu código…");
                api.post("/api/v1/auth/2fa/setup", new JSONObject().put("password", p), (code, body) -> ui.post(() -> {
                    busy(false, null);
                    if (code == 200 && body instanceof JSONObject) {
                        JSONObject o = (JSONObject) body;
                        showEnableScan(o.optString("secret", ""), o.optString("otpauthUrl", ""));
                    } else say(msgOf(code, body), BAD);
                }));
            } catch (Exception e) {
                say("Algo salió mal. Inténtalo de nuevo.", BAD);
            }
        });
        backLink("Ahora no");
    }

    private Bitmap qr(String content, int size) {
        try {
            Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
            hints.put(EncodeHintType.MARGIN, 1);
            BitMatrix m = new QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints);
            int w = m.getWidth(), h = m.getHeight();
            int[] px = new int[w * h];
            for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) px[y * w + x] = m.get(x, y) ? Color.BLACK : Color.WHITE;
            Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            b.setPixels(px, 0, w, 0, 0, w, h);
            return b;
        } catch (Exception e) {
            return null;
        }
    }

    private void copy(String label, String value) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText(label, value));
        Toast.makeText(this, "Copiado", Toast.LENGTH_SHORT).show();
    }

    private void showEnableScan(final String secret, final String url) {
        page("Activa el autenticador", "Paso 2 de 3: agrega Nova Play a tu app autenticadora.");
        TextView a = text("Si la app autenticadora está en OTRO equipo, escanea este código. Si está en este mismo celular, toca «Abrir en mi app autenticadora».", 14, FROST, false);
        card.addView(a, lp(MATCH, WRAP, 14));
        Bitmap b = qr(url, dp(220));
        if (b != null) {
            ImageView iv = new ImageView(this);
            iv.setImageBitmap(b);
            iv.setBackground(box(Color.WHITE, 12, 0));
            iv.setPadding(dp(8), dp(8), dp(8), dp(8));
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(220), dp(220));
            p.gravity = Gravity.CENTER_HORIZONTAL;
            p.topMargin = dp(14);
            card.addView(iv, p);
        }
        Button open = button("Abrir en mi app autenticadora", false);
        open.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            } catch (Exception e) {
                Toast.makeText(this, "No encontramos una app autenticadora. Instala Google Authenticator o copia la clave.", Toast.LENGTH_LONG).show();
            }
        });
        card.addView(open, lp(MATCH, dp(50), 14));
        StringBuilder grouped = new StringBuilder();
        for (int i = 0; i < secret.length(); i++) { if (i > 0 && i % 4 == 0) grouped.append(' '); grouped.append(secret.charAt(i)); }
        TextView k = text("O escribe esta clave a mano:", 13, MIST, false);
        card.addView(k, lp(MATCH, WRAP, 14));
        TextView key = text(grouped.toString(), 16, FROST, true);
        key.setTextIsSelectable(true);
        card.addView(key, lp(MATCH, WRAP, 4));
        TextView cp = text("Copiar clave", 13, LILAC, true);
        cp.setPadding(0, dp(6), 0, dp(6));
        cp.setOnClickListener(v -> copy("Clave de Nova Play", secret));
        card.addView(cp);

        TextView s3 = text("Paso 3 de 3: escribe el código de 6 dígitos que muestra la app.", 14, FROST, true);
        card.addView(s3, lp(MATCH, WRAP, 18));
        final EditText code = field("Código de 6 dígitos", InputType.TYPE_CLASS_NUMBER, "smsOTPCode");
        card.addView(code, lp(MATCH, WRAP, 10));
        actionRow("Activar", v -> {
            String c = code.getText().toString().replace(" ", "").trim();
            if (!c.matches("[0-9]{6}")) { say("El código tiene 6 dígitos.", BAD); return; }
            try {
                busy(true, "Activando…");
                api.post("/api/v1/auth/2fa/enable", new JSONObject().put("code", c), (st, body) -> ui.post(() -> {
                    busy(false, null);
                    if (st == 200 && body instanceof JSONObject) {
                        showRecoveryCodes(((JSONObject) body).optJSONArray("recoveryCodes"));
                    } else say(msgOf(st, body), BAD);
                }));
            } catch (Exception e) {
                say("Algo salió mal. Inténtalo de nuevo.", BAD);
            }
        });
        backLink("Cancelar");
    }

    private void showRecoveryCodes(JSONArray codes) {
        page("¡Listo! Tu cuenta está protegida", null);
        TextView w = text("Guarda estos códigos de recuperación en un lugar seguro. Cada uno sirve UNA vez para entrar si pierdes el celular con tu app autenticadora. No los volveremos a mostrar.", 14, WARN, false);
        card.addView(w, lp(MATCH, WRAP, 12));
        StringBuilder all = new StringBuilder();
        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setPadding(dp(16), dp(12), dp(16), dp(12));
        grid.setBackground(box(CARD, 14, Color.parseColor("#26FFFFFF")));
        if (codes != null) {
            for (int i = 0; i < codes.length(); i += 2) {
                LinearLayout row = new LinearLayout(this);
                for (int j = i; j < Math.min(i + 2, codes.length()); j++) {
                    String c = codes.optString(j);
                    all.append(c).append('\n');
                    TextView t = text(c, 15, FROST, true);
                    t.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
                    row.addView(t, new LinearLayout.LayoutParams(0, WRAP, 1f));
                }
                grid.addView(row, lp(MATCH, WRAP, i == 0 ? 0 : 6));
            }
        }
        card.addView(grid, lp(MATCH, WRAP, 14));
        Button cp = button("Copiar los códigos", false);
        cp.setOnClickListener(v -> copy("Códigos de recuperación de Nova Play", all.toString().trim()));
        card.addView(cp, lp(MATCH, dp(50), 14));
        Button done = button("Ya los guardé", true);
        done.setOnClickListener(v -> finish());
        card.addView(done, lp(MATCH, dp(52), 12));
        TextView note = text("Por seguridad cerramos tu cuenta en los demás equipos; en este sigues conectado.", 12, MIST, false);
        card.addView(note, lp(MATCH, WRAP, 12));
    }

    private void showDisable() {
        page("Verificación en dos pasos", null);
        TextView on = text("● Activa: al entrar te pedimos el código de tu app autenticadora.", 15, OK, true);
        card.addView(on, lp(MATCH, WRAP, 12));
        TextView k = text("Para desactivarla escribe tu contraseña y un código de la app (o un código de recuperación).", 14, FROST, false);
        card.addView(k, lp(MATCH, WRAP, 16));
        final EditText pw = field("Tu contraseña", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD, View.AUTOFILL_HINT_PASSWORD);
        card.addView(passwordBox(pw), lp(MATCH, WRAP, 12));
        final EditText code = field("Código", InputType.TYPE_CLASS_TEXT, null);
        card.addView(code, lp(MATCH, WRAP, 10));
        actionRow("Desactivar", v -> {
            String p = pw.getText().toString(), c = code.getText().toString().replace(" ", "").trim();
            if (p.isEmpty() || c.isEmpty()) { say("Escribe tu contraseña y el código.", BAD); return; }
            try {
                busy(true, "Desactivando…");
                api.post("/api/v1/auth/2fa/disable", new JSONObject().put("password", p).put("code", c), (st, body) -> ui.post(() -> {
                    busy(false, null);
                    if (st == 200) {
                        say("Listo: desactivaste la verificación en dos pasos.", OK);
                        primary.setText("Volver");
                        primary.setOnClickListener(x -> finish());
                    } else say(msgOf(st, body), BAD);
                }));
            } catch (Exception e) {
                say("Algo salió mal. Inténtalo de nuevo.", BAD);
            }
        });
        primary.setBackground(box(Color.TRANSPARENT, 26, BAD));
        primary.setTextColor(BAD);
        backLink("Volver");
    }
}
