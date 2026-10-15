package com.svndev.svnagent;

import android.content.Context;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

/**
 * Local credential vault. Entries are {@code KEY=value} pairs (think a private
 * .env file) stored encrypted on disk: the plaintext map is serialized to
 * UTF-8 and AES-256-GCM encrypted with a key that lives only in the Android
 * Keystore (hardware-backed on devices that have a TEE/StrongBox). The file
 * itself sits in the app's private storage at filesDir/authstore.env.enc —
 * unreadable without the device keystore, and never exported by backup.
 *
 * Usage:
 *   AuthStore.put(ctx, "openai_api_key", "sk-...");
 *   String k = AuthStore.get(ctx, "openai_api_key");
 *   Map<String,String> all = AuthStore.all(ctx);   // for the agent
 */
public final class AuthStore {

    private static final String KEY_ALIAS = "svnagent_auth_v1";
    private static final String FILE_NAME = "authstore.env.enc";
    private static final int GCM_IV_LEN = 12;
    private static final int GCM_TAG_BITS = 128;

    private AuthStore() {}

    /** All stored keys (values not exposed here — use {@link #get}). */
    public static List<String> keys(Context ctx) {
        try {
            return new ArrayList<>(load(ctx).keySet());
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    /** Decrypted value for {@code key}, or null when absent/unreadable. */
    public static String get(Context ctx, String key) {
        try {
            return load(ctx).get(key);
        } catch (Exception e) {
            return null;
        }
    }

    /** Store/overwrite an entry. Key must match [A-Za-z0-9._-]. */
    public static void put(Context ctx, String key, String value) {
        if (key == null || !key.matches("[A-Za-z0-9._-]+") || value == null) {
            return;
        }
        try {
            Map<String, String> m = load(ctx);
            m.put(key, value);
            save(ctx, m);
        } catch (Exception ignored) {}
    }

    public static void remove(Context ctx, String key) {
        try {
            Map<String, String> m = load(ctx);
            if (m.remove(key) != null) {
                save(ctx, m);
            }
        } catch (Exception ignored) {}
    }

    /** Full decrypted map — intended for the on-device agent's tool calls. */
    public static Map<String, String> all(Context ctx) {
        try {
            return load(ctx);
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    // ------------------------------------------------------------------

    private static Map<String, String> load(Context ctx) throws Exception {
        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        File f = file(ctx);
        if (!f.exists()) {
            return out;
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        FileInputStream in = new FileInputStream(f);
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) {
            bos.write(buf, 0, n);
        }
        in.close();
        byte[] blob = bos.toByteArray();
        if (blob.length < GCM_IV_LEN) {
            return out;
        }
        byte[] iv = new byte[GCM_IV_LEN];
        System.arraycopy(blob, 0, iv, 0, GCM_IV_LEN);
        byte[] cipher = new byte[blob.length - GCM_IV_LEN];
        System.arraycopy(blob, GCM_IV_LEN, cipher, 0, cipher.length);

        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(GCM_TAG_BITS, iv));
        String plain = new String(c.doFinal(cipher), "UTF-8");
        for (String line : plain.split("\n")) {
            int eq = line.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String k = line.substring(0, eq);
            String v = new String(Base64.decode(line.substring(eq + 1), Base64.NO_WRAP), "UTF-8");
            out.put(k, v);
        }
        return out;
    }

    private static void save(Context ctx, Map<String, String> m) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : m.entrySet()) {
            sb.append(e.getKey()).append('=')
                    .append(Base64.encodeToString(e.getValue().getBytes("UTF-8"), Base64.NO_WRAP))
                    .append('\n');
        }
        byte[] iv = new byte[GCM_IV_LEN];
        new SecureRandom().nextBytes(iv);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(GCM_TAG_BITS, iv));
        byte[] cipher = c.doFinal(sb.toString().getBytes("UTF-8"));
        FileOutputStream out = new FileOutputStream(file(ctx));
        out.write(iv);
        out.write(cipher);
        out.close();
    }

    private static SecretKey key() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (ks.containsAlias(KEY_ALIAS)) {
            return ((KeyStore.SecretKeyEntry) ks.getEntry(KEY_ALIAS, null)).getSecretKey();
        }
        KeyGenerator gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        gen.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return gen.generateKey();
    }

    private static File file(Context ctx) {
        return new File(ctx.getApplicationContext().getFilesDir(), FILE_NAME);
    }
}
