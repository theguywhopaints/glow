package io.github.theguywhopaints.glow;

import android.util.Base64;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.List;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Local control of a Tapo bulb over its KLAP protocol (v2 handshake), the same exchange python-kasa
 * uses. Nothing goes through TP-Link's cloud.
 *
 * The login is never stored: only authHash = sha256(sha1(email) + sha1(password)), which the
 * handshake needs, is kept on the TV.
 */
final class Tapo {

    /** The bulb refused the login: the hash does not match the account the bulb belongs to. */
    static final class AuthException extends IOException {
        AuthException(String message) { super(message); }
    }

    private static final int TIMEOUT_MS = 1500;
    private final SecureRandom random = new SecureRandom();
    private final String host;
    private final byte[] authHash;
    private final String terminalUuid;

    private byte[] key;       // AES-128 key
    private byte[] ivBase;    // first 12 bytes of the IV; the last 4 are the sequence number
    private byte[] sig;       // signature prefix
    private int seq;
    private String cookie;
    private long sessionExpiresAt;

    Tapo(String host, byte[] authHash) {
        this.host = host;
        this.authHash = authHash;
        byte[] r = new byte[16];
        random.nextBytes(r);
        this.terminalUuid = Base64.encodeToString(md5(r), Base64.NO_WRAP);
    }

    String host() { return host; }

    // ---- Bulb commands ----

    /** hue 0-359, saturation 0-100, brightness 1-100. */
    void setColor(int hue, int saturation, int brightness) throws IOException {
        JSONObject p = new JSONObject();
        try {
            p.put("device_on", true);
            p.put("color_temp", 0);   // colour_temp wins over hue/saturation when set
            p.put("hue", clamp(hue, 0, 359));
            p.put("saturation", clamp(saturation, 0, 100));
            p.put("brightness", clamp(brightness, 1, 100));
        } catch (Exception ignored) {}
        call("set_device_info", p);
    }

    JSONObject getInfo() throws IOException {
        return call("get_device_info", null).optJSONObject("result");
    }

    /** Puts the bulb back the way {@link #getInfo()} found it. */
    void restore(JSONObject info) throws IOException {
        if (info == null) return;
        JSONObject p = new JSONObject();
        try {
            int ct = info.optInt("color_temp", 0);
            if (ct > 0) {
                p.put("color_temp", ct);
            } else {
                p.put("color_temp", 0);
                p.put("hue", info.optInt("hue", 0));
                p.put("saturation", info.optInt("saturation", 0));
            }
            p.put("brightness", clamp(info.optInt("brightness", 100), 1, 100));
            p.put("device_on", info.optBoolean("device_on", true));
        } catch (Exception ignored) {}
        call("set_device_info", p);
    }

    // ---- Protocol ----

    synchronized JSONObject call(String method, JSONObject params) throws IOException {
        IOException last = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            if (key == null || System.currentTimeMillis() > sessionExpiresAt) handshake();
            try {
                return send(method, params);
            } catch (AuthException e) {
                throw e;
            } catch (IOException e) {
                last = e;
                key = null;   // session lost (bulb restarted, cookie expired): handshake again
            }
        }
        throw last;
    }

    private void handshake() throws IOException {
        key = null;
        cookie = null;
        byte[] localSeed = new byte[16];
        random.nextBytes(localSeed);

        Response r1 = post("/app/handshake1", localSeed);
        if (r1.code != 200 || r1.body.length < 48) throw new IOException("handshake1 failed: HTTP " + r1.code);
        cookie = r1.sessionCookie;
        long timeoutSec = r1.timeoutSec > 0 ? r1.timeoutSec : 86400;
        byte[] remoteSeed = Arrays.copyOfRange(r1.body, 0, 16);
        byte[] serverHash = Arrays.copyOfRange(r1.body, 16, 48);
        if (!MessageDigest.isEqual(serverHash, sha256(localSeed, remoteSeed, authHash))) {
            throw new AuthException("The bulb did not accept this Tapo login");
        }

        Response r2 = post("/app/handshake2", sha256(remoteSeed, localSeed, authHash));
        if (r2.code != 200) throw new IOException("handshake2 failed: HTTP " + r2.code);

        key = Arrays.copyOf(sha256(bytes("lsk"), localSeed, remoteSeed, authHash), 16);
        byte[] fullIv = sha256(bytes("iv"), localSeed, remoteSeed, authHash);
        ivBase = Arrays.copyOf(fullIv, 12);
        seq = ByteBuffer.wrap(fullIv, 28, 4).getInt();
        sig = Arrays.copyOf(sha256(bytes("ldk"), localSeed, remoteSeed, authHash), 28);
        // Renew well before the bulb's own timeout.
        sessionExpiresAt = System.currentTimeMillis() + (timeoutSec - 1200) * 1000L;
    }

    private JSONObject send(String method, JSONObject params) throws IOException {
        JSONObject req = new JSONObject();
        try {
            req.put("method", method);
            req.put("request_time_milis", System.currentTimeMillis());
            req.put("terminal_uuid", terminalUuid);
            if (params != null) req.put("params", params);
        } catch (Exception ignored) {}

        seq++;
        byte[] iv = ByteBuffer.allocate(16).put(ivBase).putInt(seq).array();
        byte[] cipherText = aes(Cipher.ENCRYPT_MODE, iv, req.toString().getBytes(StandardCharsets.UTF_8));
        byte[] signature = sha256(sig, ByteBuffer.allocate(4).putInt(seq).array(), cipherText);
        byte[] body = ByteBuffer.allocate(signature.length + cipherText.length).put(signature).put(cipherText).array();

        Response r = post("/app/request?seq=" + seq, body);
        if (r.code != 200 || r.body.length <= 32) throw new IOException("request failed: HTTP " + r.code);
        byte[] plain = aes(Cipher.DECRYPT_MODE, iv, Arrays.copyOfRange(r.body, 32, r.body.length));
        try {
            JSONObject res = new JSONObject(new String(plain, StandardCharsets.UTF_8));
            int err = res.optInt("error_code", 0);
            if (err != 0) throw new IOException(method + " error " + err);
            return res;
        } catch (org.json.JSONException e) {
            throw new IOException("bad response", e);
        }
    }

    // ---- HTTP ----

    private static final class Response {
        int code;
        byte[] body = new byte[0];
        String sessionCookie;
        long timeoutSec;
    }

    private Response post(String path, byte[] payload) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL("http://" + host + path).openConnection();
        try {
            c.setConnectTimeout(TIMEOUT_MS);
            c.setReadTimeout(TIMEOUT_MS);
            c.setDoOutput(true);
            c.setRequestMethod("POST");
            c.setRequestProperty("Content-Type", "application/octet-stream");
            if (cookie != null) c.setRequestProperty("Cookie", cookie);
            c.setFixedLengthStreamingMode(payload.length);
            try (OutputStream out = c.getOutputStream()) { out.write(payload); }

            Response r = new Response();
            r.code = c.getResponseCode();
            List<String> setCookies = c.getHeaderFields().get("Set-Cookie");
            if (setCookies != null) {
                for (String header : setCookies) {
                    for (String part : header.split(";")) {
                        String p = part.trim();
                        if (p.startsWith("TP_SESSIONID=")) r.sessionCookie = p;
                        else if (p.startsWith("TIMEOUT=")) {
                            try { r.timeoutSec = Long.parseLong(p.substring(8)); } catch (NumberFormatException ignored) {}
                        }
                    }
                }
            }
            InputStream in = r.code < 400 ? c.getInputStream() : c.getErrorStream();
            if (in != null) {
                try (InputStream s = in) {
                    ByteArrayOutputStream buf = new ByteArrayOutputStream();
                    byte[] chunk = new byte[1024];
                    int n;
                    while ((n = s.read(chunk)) > 0) buf.write(chunk, 0, n);
                    r.body = buf.toByteArray();
                }
            }
            return r;
        } finally {
            c.disconnect();
        }
    }

    // ---- Crypto helpers ----

    private byte[] aes(int mode, byte[] iv, byte[] data) throws IOException {
        try {
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(mode, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
            return cipher.doFinal(data);
        } catch (Exception e) {
            throw new IOException("crypto failed", e);
        }
    }

    static byte[] sha256(byte[]... parts) {
        try {
            MessageDigest d = MessageDigest.getInstance("SHA-256");
            for (byte[] p : parts) d.update(p);
            return d.digest();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] md5(byte[] data) {
        try {
            return MessageDigest.getInstance("MD5").digest(data);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] bytes(String s) { return s.getBytes(StandardCharsets.US_ASCII); }

    static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    static byte[] fromHex(String hex) {
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }
}
