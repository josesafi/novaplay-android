package com.limelight.nova;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Portadas del catálogo de Nova Play (estilo GeForce NOW): se bajan una vez por HTTPS (Steam CDN) y quedan en memoria y en la caché del
 * celular. Sin librerías nuevas. Solo acepta https y hasta 1,5 MB por imagen.
 */
public final class CoverLoader {
    private static CoverLoader instance;
    private final LruCache<String, Bitmap> memory = new LruCache<String, Bitmap>(12 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };
    private final ExecutorService pool = Executors.newFixedThreadPool(3);
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final File dir;

    private CoverLoader(Context ctx) {
        dir = new File(ctx.getCacheDir(), "portadas");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
    }

    public static synchronized CoverLoader get(Context ctx) {
        if (instance == null) instance = new CoverLoader(ctx.getApplicationContext());
        return instance;
    }

    /** Pone la portada en la vista cuando esté lista (si la vista no se reutilizó para otra portada mientras tanto). */
    public void load(final String url, final ImageView view) {
        if (url == null || !url.startsWith("https://")) return;
        view.setTag(url);
        Bitmap hit = memory.get(url);
        if (hit != null) {
            view.setImageBitmap(hit);
            return;
        }
        pool.execute(() -> {
            Bitmap b = fetch(url);
            if (b == null) return;
            memory.put(url, b);
            ui.post(() -> {
                if (url.equals(view.getTag())) view.setImageBitmap(b);
            });
        });
    }

    private Bitmap fetch(String url) {
        File f = new File(dir, hash(url) + ".img");
        try {
            if (f.isFile() && f.length() > 0) {
                Bitmap b = BitmapFactory.decodeFile(f.getAbsolutePath());
                if (b != null) return b;
            }
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(10000);
            c.setInstanceFollowRedirects(true);
            if (c.getResponseCode() != 200) return null;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (InputStream in = c.getInputStream()) {
                byte[] buf = new byte[16 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    if (out.size() > 1_500_000) return null;
                }
            } finally {
                c.disconnect();
            }
            byte[] data = out.toByteArray();
            Bitmap b = BitmapFactory.decodeByteArray(data, 0, data.length);
            if (b == null) return null;
            try (FileOutputStream w = new FileOutputStream(f)) {
                w.write(data);
            }
            return b;
        } catch (Exception e) {
            return null;
        }
    }

    private static String hash(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 12; i++) sb.append(String.format("%02x", d[i]));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
    }
}
