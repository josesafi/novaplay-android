package com.limelight.nova;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Nova Play: conexion con la cuenta de novawebcolombia.com (la misma API que usa el panel).
 * Inicia sesion con correo y contrasena (y el codigo de verificacion en dos pasos si la cuenta lo tiene), guarda la sesion en
 * las preferencias privadas de la app y hace las llamadas con la cookie de sesion y la cabecera X-CSRF-Token.
 * Todas las llamadas corren en segundo plano; el resultado llega por {@link Callback} (en segundo plano: la pantalla lo pasa a la
 * interfaz con runOnUiThread).
 */
public class NovaApi {
    public static final String BASE = "https://api.novawebcolombia.com";
    private static final String COOKIE = "__Host-novacloud_session";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    public interface Callback {
        void done(int status, Object body);
    }

    public interface LoginCallback {
        void done(boolean ok, boolean twoFactorRequired, String message);
    }

    private static NovaApi instance;

    public static synchronized NovaApi get(Context context) {
        if (instance == null) {
            instance = new NovaApi(context.getApplicationContext());
        }
        return instance;
    }

    private final SharedPreferences prefs;
    private final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build();
    private final ExecutorService pool = Executors.newSingleThreadExecutor();

    private String session;
    private String csrf;
    private String firstName;
    private String email;
    private String challenge;
    /** Codigo del ultimo error del inicio de sesion (p. ej. EMAIL_VERIFICATION_REQUIRED). */
    private volatile String lastErrorCode = "";

    public String lastErrorCode() { return lastErrorCode == null ? "" : lastErrorCode; }

    private NovaApi(Context context) {
        prefs = context.getSharedPreferences("novaplay", Context.MODE_PRIVATE);
        session = prefs.getString("session", "");
        csrf = prefs.getString("csrf", "");
        firstName = prefs.getString("firstName", "");
        email = prefs.getString("email", "");
    }

    public boolean isLoggedIn() { return session != null && !session.isEmpty(); }
    public String getFirstName() { return firstName == null ? "" : firstName; }
    public String getEmail() { return email == null ? "" : email; }

    private void save() {
        prefs.edit().putString("session", session).putString("csrf", csrf).putString("firstName", firstName).putString("email", email).apply();
    }

    private void takeCookie(Response res) {
        List<String> cookies = res.headers("Set-Cookie");
        String prefix = COOKIE + "=";
        for (String c : cookies) {
            int at = c.indexOf(prefix);
            if (at < 0) continue;
            String v = c.substring(at + prefix.length());
            int end = v.indexOf(';');
            if (end >= 0) v = v.substring(0, end);
            if (!v.isEmpty()) session = v;
        }
    }

    private static Object parse(String text) {
        if (text == null) return null;
        String t = text.trim();
        try {
            if (t.startsWith("[")) return new JSONArray(t);
            if (t.startsWith("{")) return new JSONObject(t);
        } catch (Exception ignored) {
        }
        return null;
    }

    /** Hace la llamada en el hilo actual (ya en segundo plano). */
    private Object[] call(String method, String path, JSONObject body, boolean authed) {
        Request.Builder b = new Request.Builder().url(BASE + path).header("Accept", "application/json").header("User-Agent", "NovaPlay-Android");
        if (authed && isLoggedIn()) {
            b.header("Cookie", COOKIE + "=" + session);
            if (csrf != null && !csrf.isEmpty()) b.header("X-CSRF-Token", csrf);
        }
        RequestBody rb = body == null ? (method.equals("GET") ? null : RequestBody.create("{}", JSON)) : RequestBody.create(body.toString(), JSON);
        b.method(method, rb);
        try (Response res = http.newCall(b.build()).execute()) {
            takeCookie(res);
            String text = res.body() != null ? res.body().string() : "";
            int status = res.code();
            if (authed && status == 401 && isLoggedIn()) {
                // sesion vencida o cerrada desde el panel: se olvida y la app vuelve a pedir el inicio de sesion
                session = "";
                csrf = "";
                save();
            }
            return new Object[]{status, parse(text)};
        } catch (IOException e) {
            return new Object[]{0, null};
        }
    }

    public static String errorMessage(int status, Object body) {
        if (body instanceof JSONObject) {
            JSONObject o = (JSONObject) body;
            JSONObject err = o.optJSONObject("error");
            String m = err != null ? err.optString("message", "") : "";
            if (m.isEmpty()) m = o.optString("message", "");
            if (!m.isEmpty()) return m;
        }
        if (status == 0) return "No hay conexión con Nova Play. Revisa tu internet e inténtalo de nuevo.";
        if (status == 401) return "Correo o contraseña incorrectos.";
        if (status == 429) return "Demasiados intentos. Espera unos minutos e inténtalo de nuevo.";
        return "Algo salió mal (código " + status + "). Inténtalo de nuevo.";
    }

    private void finishLogin(JSONObject body, LoginCallback cb) {
        csrf = body.optString("csrfToken", "");
        Object[] s = call("GET", "/api/v1/auth/session", null, true);
        if (s[1] instanceof JSONObject) {
            JSONObject so = (JSONObject) s[1];
            JSONObject user = so.optJSONObject("user");
            if (user != null) {
                firstName = user.optString("firstName", "");
                String e = user.optString("email", "");
                if (!e.isEmpty()) email = e;
            }
            String c = so.optString("csrfToken", "");
            if (!c.isEmpty()) csrf = c;
        }
        save();
        cb.done(true, false, "");
    }

    public void login(final String mail, final String password, final LoginCallback cb) {
        pool.execute(() -> {
            try {
                email = mail.trim();
                session = "";
                JSONObject body = new JSONObject().put("email", email).put("password", password);
                Object[] r = call("POST", "/api/v1/auth/login", body, false);
                int status = (Integer) r[0];
                if (status != 200 && status != 201) {
                    JSONObject eo = r[1] instanceof JSONObject ? ((JSONObject) r[1]).optJSONObject("error") : null;
                    lastErrorCode = eo != null ? eo.optString("code", "") : "";
                    cb.done(false, false, errorMessage(status, r[1]));
                    return;
                }
                lastErrorCode = "";
                JSONObject o = r[1] instanceof JSONObject ? (JSONObject) r[1] : new JSONObject();
                if (o.optBoolean("twoFactorRequired", false)) {
                    challenge = o.optString("challengeId", "");
                    cb.done(true, true, "");
                    return;
                }
                if (!isLoggedIn()) {
                    cb.done(false, false, "No se pudo iniciar la sesión. Inténtalo de nuevo.");
                    return;
                }
                finishLogin(o, cb);
            } catch (Exception e) {
                cb.done(false, false, "Algo salió mal. Inténtalo de nuevo.");
            }
        });
    }

    public void loginTwoFactor(final String code, final LoginCallback cb) {
        pool.execute(() -> {
            try {
                String clean = code.trim().replace(" ", "");
                boolean totp = clean.matches("[0-9]{6}");
                JSONObject body = new JSONObject().put("challengeId", challenge).put(totp ? "code" : "recoveryCode", clean);
                Object[] r = call("POST", "/api/v1/auth/login/2fa", body, false);
                int status = (Integer) r[0];
                if ((status != 200 && status != 201) || !isLoggedIn()) {
                    cb.done(false, false, errorMessage(status, r[1]));
                    return;
                }
                finishLogin(r[1] instanceof JSONObject ? (JSONObject) r[1] : new JSONObject(), cb);
            } catch (Exception e) {
                cb.done(false, false, "Algo salió mal. Inténtalo de nuevo.");
            }
        });
    }

    public void logout() {
        final boolean had = isLoggedIn();
        pool.execute(() -> {
            if (had) call("POST", "/api/v1/auth/logout", new JSONObject(), true);
            session = "";
            csrf = "";
            firstName = "";
            if (!rememberEmail()) email = "";
            save();
        });
    }

    // ------------------------------------------------------------ cuenta desde la app (18-nov-2026)

    /** «Recordar mi correo» en la pantalla de inicio de sesion (por defecto si). */
    public boolean rememberEmail() { return prefs.getBoolean("rememberEmail", true); }

    public void setRememberEmail(boolean on) {
        prefs.edit().putBoolean("rememberEmail", on).apply();
    }

    /** Correo de una cuenta recien creada (o recuperada): el inicio de sesion ya lo trae escrito. */
    public void setPendingEmail(String mail) {
        if (isLoggedIn() || mail == null) return;
        email = mail.trim();
        save();
    }

    /** Antibot de la app: {token, question, expiresInSeconds, termsVersion, passwordMinLength}. */
    public void appChallenge(final Callback cb) {
        pool.execute(() -> {
            Object[] r = call("GET", "/api/v1/auth/app-challenge", null, false);
            cb.done((Integer) r[0], r[1]);
        });
    }

    /** POST sin sesion (registro, recuperar contrasena, reenviar verificacion). */
    public void publicPost(final String path, final JSONObject body, final Callback cb) {
        pool.execute(() -> {
            Object[] r = call("POST", path, body == null ? new JSONObject() : body, false);
            cb.done((Integer) r[0], r[1]);
        });
    }

    /** GET /auth/session: datos de la cuenta (user.twoFactorEnabled). */
    public void session(final Callback cb) {
        get("/api/v1/auth/session", cb);
    }

    /** POST en el hilo actual (para usar desde un hilo de trabajo). Devuelve {status, cuerpo}. */
    public Object[] postNow(String path, JSONObject body) {
        return call("POST", path, body == null ? new JSONObject() : body, true);
    }

    public void get(final String path, final Callback cb) {
        pool.execute(() -> {
            Object[] r = call("GET", path, null, true);
            cb.done((Integer) r[0], r[1]);
        });
    }

    public void post(final String path, final JSONObject body, final Callback cb) {
        pool.execute(() -> {
            Object[] r = call("POST", path, body == null ? new JSONObject() : body, true);
            cb.done((Integer) r[0], r[1]);
        });
    }
}
