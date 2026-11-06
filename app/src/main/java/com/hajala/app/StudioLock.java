package com.hajala.app;

import android.content.Context;
import android.util.Base64;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import org.json.JSONObject;

/**
 * App lock settings. The PIN is stored only as a PBKDF2 hash with a random salt in
 * files/studio/privacy.json — never as plain text, and never inside index.json (so a metadata
 * backup does not have to carry the secret). Nothing here is uploaded.
 */
final class StudioLock {
    static final int MIN_PIN = 4;
    static final int MAX_PIN = 6;
    private static final int ITERATIONS = 12000;
    private static final int KEY_BITS = 256;

    private final File file;
    private boolean enabled;
    private String algorithm = "PBKDF2WithHmacSHA1";
    private String salt = "";
    private String hash = "";
    private int iterations = ITERATIONS;
    private int pinLength = MIN_PIN;
    private boolean biometric;
    private int timeoutSec = 60;
    private boolean secureFlag;
    /** Files at least this big count as "large". Default 100 MB. */
    private long largeMin = 100L * 1024 * 1024;

    StudioLock(Context c) {
        File root = new File(c.getApplicationContext().getFilesDir(), "studio");
        //noinspection ResultOfMethodCallIgnored
        root.mkdirs();
        file = new File(root, "privacy.json");
        load();
    }

    private void load() {
        if (!file.exists()) return;
        try {
            byte[] buf = readAll(file);
            JSONObject o = new JSONObject(new String(buf, java.nio.charset.StandardCharsets.UTF_8));
            enabled = o.optBoolean("enabled", false);
            algorithm = o.optString("algorithm", algorithm);
            salt = o.optString("salt", "");
            hash = o.optString("hash", "");
            iterations = o.optInt("iterations", ITERATIONS);
            pinLength = o.optInt("pinLength", MIN_PIN);
            biometric = o.optBoolean("biometric", false);
            timeoutSec = o.optInt("timeoutSec", 60);
            secureFlag = o.optBoolean("secure", false);
            largeMin = o.optLong("largeMin", largeMin);
            if (pinLength < MIN_PIN || pinLength > MAX_PIN) pinLength = MIN_PIN;
            if (salt.isEmpty() || hash.isEmpty()) enabled = false;
        } catch (Exception ignored) {
            enabled = false;
        }
    }

    private static byte[] readAll(File f) throws Exception {
        try (FileInputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream((int) Math.max(32, f.length()));
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toByteArray();
        }
    }

    private void save() {
        try {
            JSONObject o = new JSONObject();
            o.put("enabled", enabled);
            o.put("algorithm", algorithm);
            o.put("salt", salt);
            o.put("hash", hash);
            o.put("iterations", iterations);
            o.put("pinLength", pinLength);
            o.put("biometric", biometric);
            o.put("timeoutSec", timeoutSec);
            o.put("secure", secureFlag);
            o.put("largeMin", largeMin);
            File tmp = new File(file.getParentFile(), "privacy.json.tmp");
            try (FileOutputStream os = new FileOutputStream(tmp)) {
                os.write(o.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                os.flush();
                os.getFD().sync();
            }
            if (!tmp.renameTo(file)) {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
                //noinspection ResultOfMethodCallIgnored
                tmp.renameTo(file);
            }
        } catch (Exception ignored) {
            // a failed write leaves the previous file in place
        }
    }

    boolean enabled() {
        return enabled && !hash.isEmpty();
    }

    int pinLength() {
        return pinLength;
    }

    boolean biometric() {
        return biometric;
    }

    void setBiometric(boolean on) {
        biometric = on;
        save();
    }

    int timeoutSec() {
        return timeoutSec;
    }

    long timeoutMs() {
        return Math.max(0, timeoutSec) * 1000L;
    }

    void setTimeoutSec(int sec) {
        timeoutSec = Math.max(0, sec);
        save();
    }

    boolean secureFlag() {
        return secureFlag;
    }

    void setSecureFlag(boolean on) {
        secureFlag = on;
        save();
    }

    long largeMin() {
        return largeMin <= 0 ? 100L * 1024 * 1024 : largeMin;
    }

    void setLargeMin(long bytes) {
        largeMin = Math.max(1024 * 1024, bytes);
        save();
    }

    static boolean validPin(String pin) {
        if (pin == null || pin.length() < MIN_PIN || pin.length() > MAX_PIN) return false;
        for (int i = 0; i < pin.length(); i++) {
            char ch = pin.charAt(i);
            if (ch < '0' || ch > '9') return false;
        }
        return true;
    }

    /** Hashes [pin] and turns the lock on. */
    boolean setPin(String pin) {
        if (!validPin(pin)) return false;
        try {
            byte[] saltBytes = new byte[16];
            new SecureRandom().nextBytes(saltBytes);
            String algo = pickAlgorithm();
            byte[] key = pbkdf2(pin, saltBytes, ITERATIONS, algo);
            algorithm = algo;
            salt = Base64.encodeToString(saltBytes, Base64.NO_WRAP);
            hash = Base64.encodeToString(key, Base64.NO_WRAP);
            iterations = ITERATIONS;
            pinLength = pin.length();
            enabled = true;
            save();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    boolean verify(String pin) {
        if (!enabled() || pin == null) return false;
        try {
            byte[] saltBytes = Base64.decode(salt, Base64.NO_WRAP);
            byte[] key = pbkdf2(pin, saltBytes, iterations, algorithm);
            byte[] want = Base64.decode(hash, Base64.NO_WRAP);
            return constantEquals(key, want);
        } catch (Exception e) {
            return false;
        }
    }

    void clear() {
        enabled = false;
        hash = "";
        salt = "";
        biometric = false;
        save();
    }

    private static String pickAlgorithm() {
        try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            return "PBKDF2WithHmacSHA256";
        } catch (Exception e) {
            return "PBKDF2WithHmacSHA1";
        }
    }

    private static byte[] pbkdf2(String pin, byte[] saltBytes, int iter, String algo) throws Exception {
        KeySpec spec = new PBEKeySpec(pin.toCharArray(), saltBytes, Math.max(1000, iter), KEY_BITS);
        return SecretKeyFactory.getInstance(algo).generateSecret(spec).getEncoded();
    }

    private static boolean constantEquals(byte[] a, byte[] b) {
        if (a == null || b == null || a.length != b.length) return false;
        int d = 0;
        for (int i = 0; i < a.length; i++) d |= a[i] ^ b[i];
        return d == 0;
    }
}
