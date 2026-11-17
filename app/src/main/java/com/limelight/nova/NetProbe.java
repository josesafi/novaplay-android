package com.limelight.nova;

import java.io.InputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * «Prueba de red» de Nova Play: mide contra el servidor de pruebas que esta junto a las consolas (nova-play-netprobe, puerto 47800):
 *  1. latencia: 40 paquetes UDP pequenos, uno cada 50 ms (mediana del ida y vuelta);
 *  2. perdida y variacion: 400 paquetes UDP de 1000 bytes a 100 por segundo, como viaja el video del juego;
 *  3. ancho de banda: 4 descargas TCP en paralelo durante 6 s (sin contar el primer segundo, mientras TCP acelera).
 * Corre en su propio hilo y avisa por {@link Listener} (desde ese hilo: la pantalla debe pasar a su hilo de interfaz).
 */
public class NetProbe {
    public static final int PORT = 47800;
    private static final byte[] MAGIC = {'N', 'P', 'R', 'B'};

    public static final int PHASE_LATENCY = 0, PHASE_LOSS = 1, PHASE_BANDWIDTH = 2;

    public interface Listener {
        void onPhase(int phase);
        /** avance total de 0 a 1 */
        void onProgress(float fraction);
        /** valor en vivo de la fase actual (ms o Mbps) */
        void onLive(int phase, double value);
        void onDone(Result r);
        void onError(String message);
    }

    public static class Result {
        public double latencyMs, jitterMs, lossPct, mbps;
        public int sent, received;
    }

    private final String host;
    private final Listener listener;
    private final AtomicBoolean cancelled = new AtomicBoolean();

    public NetProbe(String host, Listener listener) {
        this.host = host;
        this.listener = listener;
    }

    public void cancel() { cancelled.set(true); }

    public void start() {
        new Thread(this::run, "nova-netprobe").start();
    }

    private void run() {
        Result r = new Result();
        try {
            InetAddress addr = InetAddress.getByName(host);
            try (DatagramSocket sock = new DatagramSocket()) {
                sock.setSoTimeout(400);
                // 1) latencia
                listener.onPhase(PHASE_LATENCY);
                List<Double> rtts = new ArrayList<>();
                for (int i = 0; i < 40 && !cancelled.get(); i++) {
                    Double rtt = ping(sock, addr, i, 64);
                    if (rtt != null) {
                        rtts.add(rtt);
                        listener.onLive(PHASE_LATENCY, median(rtts));
                    }
                    listener.onProgress(0.15f * (i + 1) / 40f);
                    sleep(50);
                }
                if (cancelled.get()) return;
                if (rtts.isEmpty()) {
                    listener.onError("No pudimos llegar al servidor de pruebas de Nova Play. Revisa que tengas internet; si estás en una red de empresa o universidad, puede que bloquee los juegos en la nube.");
                    return;
                }
                r.latencyMs = median(rtts);

                // 2) perdida y variacion con paquetes del tamano del video
                listener.onPhase(PHASE_LOSS);
                final int total = 400;
                final long[] sentAt = new long[total];
                final double[] rtt = new double[total];
                final boolean[] got = new boolean[total];
                final AtomicBoolean receiving = new AtomicBoolean(true);
                sock.setSoTimeout(200);
                Thread rx = new Thread(() -> {
                    byte[] buf = new byte[1500];
                    while (receiving.get()) {
                        try {
                            DatagramPacket p = new DatagramPacket(buf, buf.length);
                            sock.receive(p);
                            long now = System.nanoTime();
                            ByteBuffer b = ByteBuffer.wrap(p.getData(), 0, p.getLength());
                            if (p.getLength() < 16 || b.get(0) != 'N' || b.get(3) != 'B') continue;
                            int seq = b.getInt(4);
                            if (seq >= 1000 && seq < 1000 + total && !got[seq - 1000]) {
                                got[seq - 1000] = true;
                                rtt[seq - 1000] = (now - sentAt[seq - 1000]) / 1e6;
                            }
                        } catch (SocketTimeoutException ignored) {
                        } catch (Exception e) {
                            break;
                        }
                    }
                }, "nova-netprobe-rx");
                rx.start();
                byte[] payload = new byte[1000];
                long t0 = System.nanoTime();
                for (int i = 0; i < total && !cancelled.get(); i++) {
                    long due = t0 + i * 10_000_000L;
                    long wait = due - System.nanoTime();
                    if (wait > 0) sleep(wait / 1_000_000L);
                    ByteBuffer b = ByteBuffer.wrap(payload);
                    b.put(MAGIC).putInt(1000 + i).putLong(System.nanoTime());
                    sentAt[i] = System.nanoTime();
                    sock.send(new DatagramPacket(payload, payload.length, addr, PORT));
                    if (i % 20 == 19) {
                        int ok = 0;
                        for (int k = 0; k <= i; k++) if (got[k]) ok++;
                        listener.onLive(PHASE_LOSS, 100.0 * (i + 1 - ok) / (i + 1));
                        listener.onProgress(0.15f + 0.35f * (i + 1) / total);
                    }
                }
                sleep(1000); // los ultimos en llegar
                receiving.set(false);
                rx.join(1500);
                if (cancelled.get()) return;
                int ok = 0;
                double jitterSum = 0;
                int jitterN = 0;
                double prev = -1;
                for (int i = 0; i < total; i++) {
                    if (!got[i]) continue;
                    ok++;
                    if (prev >= 0) { jitterSum += Math.abs(rtt[i] - prev); jitterN++; }
                    prev = rtt[i];
                }
                r.sent = total;
                r.received = ok;
                r.lossPct = 100.0 * (total - ok) / total;
                r.jitterMs = jitterN > 0 ? jitterSum / jitterN : 0;
            }

            // 3) ancho de banda
            listener.onPhase(PHASE_BANDWIDTH);
            r.mbps = bandwidth();
            if (cancelled.get()) return;
            if (r.mbps < 0.1) {
                listener.onError("No pudimos medir la velocidad de descarga: la conexión con el servidor de pruebas se cortó. Inténtalo de nuevo en un momento.");
                return;
            }
            listener.onProgress(1f);
            listener.onDone(r);
        } catch (Exception e) {
            if (!cancelled.get()) listener.onError("La prueba no se pudo completar. Revisa tu conexión e inténtalo de nuevo.");
        }
    }

    private Double ping(DatagramSocket sock, InetAddress addr, int seq, int size) throws Exception {
        byte[] out = new byte[Math.max(16, size)];
        ByteBuffer.wrap(out).put(MAGIC).putInt(seq).putLong(System.nanoTime());
        long sent = System.nanoTime();
        sock.send(new DatagramPacket(out, out.length, addr, PORT));
        byte[] in = new byte[1500];
        long deadline = sent + 400_000_000L;
        while (System.nanoTime() < deadline) {
            try {
                DatagramPacket p = new DatagramPacket(in, in.length);
                sock.receive(p);
                if (p.getLength() >= 16 && ByteBuffer.wrap(in).getInt(4) == seq) return (System.nanoTime() - sent) / 1e6;
            } catch (SocketTimeoutException e) {
                return null;
            }
        }
        return null;
    }

    private double bandwidth() throws InterruptedException {
        final int streams = 4;
        final long durationMs = 6000, warmMs = 1000;
        final AtomicLong bytes = new AtomicLong();
        final AtomicBoolean stop = new AtomicBoolean();
        List<Thread> threads = new ArrayList<>();
        for (int s = 0; s < streams; s++) {
            Thread t = new Thread(() -> {
                HttpURLConnection c = null;
                try {
                    c = (HttpURLConnection) new URL("http://" + host + ":" + PORT + "/down?s=7").openConnection();
                    c.setConnectTimeout(5000);
                    c.setReadTimeout(5000);
                    c.setUseCaches(false);
                    try (InputStream in = c.getInputStream()) {
                        byte[] buf = new byte[64 * 1024];
                        int n;
                        while (!stop.get() && !cancelled.get() && (n = in.read(buf)) > 0) bytes.addAndGet(n);
                    }
                } catch (Exception ignored) {
                } finally {
                    if (c != null) c.disconnect();
                }
            }, "nova-netprobe-down");
            t.start();
            threads.add(t);
        }
        long start = System.currentTimeMillis();
        long warmBytes = -1, warmAt = 0;
        double mbps = 0;
        while (!cancelled.get()) {
            sleep(250);
            long el = System.currentTimeMillis() - start;
            long b = bytes.get();
            if (el >= warmMs && warmBytes < 0) { warmBytes = b; warmAt = System.currentTimeMillis(); }
            if (warmBytes >= 0) {
                double secs = (System.currentTimeMillis() - warmAt) / 1000.0;
                if (secs > 0.2) mbps = (b - warmBytes) * 8 / 1e6 / secs;
            } else if (el > 0) {
                mbps = b * 8 / 1e6 / (el / 1000.0);
            }
            listener.onLive(PHASE_BANDWIDTH, mbps);
            listener.onProgress(0.5f + 0.5f * Math.min(1f, el / (float) durationMs));
            if (el >= durationMs) break;
        }
        stop.set(true);
        for (Thread t : threads) t.join(1000);
        return mbps;
    }

    private static double median(List<Double> v) {
        List<Double> s = new ArrayList<>(v);
        Collections.sort(s);
        int n = s.size();
        return n % 2 == 1 ? s.get(n / 2) : (s.get(n / 2 - 1) + s.get(n / 2)) / 2;
    }

    private static void sleep(long ms) {
        if (ms <= 0) return;
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
    }

    // ---- criterios (los mismos en la app de PC)
    public static final double BW_REQUIRED = 15, BW_RECOMMENDED = 35;
    public static final double LOSS_REQUIRED = 2.0, LOSS_RECOMMENDED = 1.0;
    public static final double LAT_REQUIRED = 80, LAT_RECOMMENDED = 40;
    public static final double JITTER_REQUIRED = 30, JITTER_RECOMMENDED = 10;

    /** 2 = cumple lo recomendado, 1 = cumple lo requerido, 0 = no alcanza */
    public static int gradeHigh(double v, double req, double rec) { return v >= rec ? 2 : v >= req ? 1 : 0; }
    public static int gradeLow(double v, double req, double rec) { return v < rec ? 2 : v < req ? 1 : 0; }

    /** Calidad que conviene: resolucion, fps y tasa de bits (kbps), o null si la red no alcanza. */
    public static int[] recommendedQuality(Result r) {
        if (r.mbps < BW_REQUIRED || r.lossPct >= LOSS_REQUIRED * 2 || r.latencyMs >= 150) return null;
        int kbps = (int) Math.max(5000, Math.min(40000, Math.round(r.mbps * 0.6 / 1) * 1000));
        if (r.mbps >= BW_RECOMMENDED && r.lossPct < LOSS_REQUIRED) return new int[]{1920, 1080, 60, kbps};
        if (r.mbps >= 25 && r.lossPct < LOSS_REQUIRED) return new int[]{1920, 1080, 60, Math.min(kbps, 15000)};
        return new int[]{1280, 720, 60, Math.min(kbps, 10000)};
    }
}
