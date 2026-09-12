package com.tungsten.fcl.geo;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.widget.Toast;

import com.tungsten.fcllibrary.component.dialog.FCLAlertDialog;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * BREAKFRONT —— Geekhonize 网页授权登录（设备码）。
 *
 * <p>登录全程在浏览器完成（auth.geekhonize.top 的「设备授权」页）；本类只负责：
 * 取授权码 → 打开网页 → 轮询授权结果 → 把令牌存进本地 Prefs；再在启动游戏前
 * 由 {@link #writeIntoGameDir} 写进游戏目录的 {@code config/breakfront-client.properties}，
 * 客户端 mod（com.breakfront.client.geo.GeoSession）启动时即自动登录。</p>
 */
public final class BreakfrontGeoAuth {

    private static final String BASE = "https://auth.geekhonize.top";
    private static final String DEVICE_START = BASE + "/api/v1/auth/device/start";
    private static final String DEVICE_POLL = BASE + "/api/v1/auth/device/poll";

    private static final String PREFS = "breakfront_geo";
    private static final String K_TOKEN = "token";
    private static final String K_USER = "username";
    private static final String K_EXP = "expires";

    private static final String KEY_TOKEN = "auth.token";
    private static final String KEY_USER = "auth.username";
    private static final String KEY_EXPIRES = "auth.expires";
    private static final String PROPS_NAME = "breakfront-client.properties";

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private BreakfrontGeoAuth() {
    }

    public static boolean signedIn(Context c) {
        return !token(c).isEmpty();
    }

    public static String token(Context c) {
        return prefs(c).getString(K_TOKEN, "");
    }

    public static String username(Context c) {
        return prefs(c).getString(K_USER, "");
    }

    public static long expires(Context c) {
        return prefs(c).getLong(K_EXP, 0L);
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** 设备码网页授权登录。登录与授权都在浏览器完成。 */
    public static void startLogin(final Context context) {
        final Context app = context.getApplicationContext() != null
                ? context.getApplicationContext() : context;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final String code = requestCode();
                    if (code == null) {
                        toast(app, "无法连接 Geekhonize 登录服务，请检查网络后重试。");
                        return;
                    }
                    ui(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(BASE + "/"));
                                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                                app.startActivity(i);
                            } catch (Throwable ignored) {
                            }
                            FCLAlertDialog.Builder b = new FCLAlertDialog.Builder(app);
                            b.setCancelable(false);
                            b.setAlertLevel(FCLAlertDialog.AlertLevel.ALERT);
                            b.setMessage("已打开 Geekhonize 登录页。\n\n"
                                    + "请登录后进入「设备授权」，输入授权码：\n\n"
                                    + code + "\n\n"
                                    + "授权完成后会自动完成登录，无需在此操作。");
                            b.setNegativeButton(app.getString(com.tungsten.fcl.R.string.dialog_positive), null);
                            b.create().show();
                        }
                    });

                    final String token = poll(code);
                    if (token == null) {
                        toast(app, "登录未完成（未授权或已超时），请重试。");
                        return;
                    }
                    String user = claim(token, "sub");
                    long exp = expiry(token);
                    prefs(app).edit()
                            .putString(K_TOKEN, token)
                            .putString(K_USER, user)
                            .putLong(K_EXP, exp)
                            .apply();
                    toast(app, "Geekhonize 登录成功" + (user.isEmpty() ? "！" : "：" + user)
                            + "\n启动游戏后自动生效。");
                } catch (Throwable e) {
                    toast(app, "Geekhonize 登录失败：" + e);
                }
            }
        }, "bf-geo-login");
        t.setDaemon(true);
        t.start();
    }

    /**
     * 启动游戏前把已登录的令牌写进游戏目录，供客户端 mod 自动登录。
     * 只在已登录时写；保留文件里其它配置键。
     */
    public static void writeIntoGameDir(Context context, File workingDir) {
        if (context == null || workingDir == null) {
            return;
        }
        String token = token(context);
        if (token.isEmpty()) {
            return;
        }
        try {
            File dir = new File(workingDir, "config");
            if (!dir.isDirectory() && !dir.mkdirs()) {
                return;
            }
            File file = new File(dir, PROPS_NAME);

            List<String> kept = new ArrayList<String>();
            BufferedReader reader = null;
            try {
                if (file.isFile()) {
                    reader = new BufferedReader(new InputStreamReader(
                            new java.io.FileInputStream(file), StandardCharsets.UTF_8));
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.startsWith(KEY_TOKEN + "=")
                                || line.startsWith(KEY_USER + "=")
                                || line.startsWith(KEY_EXPIRES + "=")) {
                            continue;
                        }
                        kept.add(line);
                    }
                }
            } catch (Throwable ignored) {
            } finally {
                if (reader != null) {
                    try {
                        reader.close();
                    } catch (Throwable ignored) {
                    }
                }
            }

            kept.add(KEY_TOKEN + "=" + token);
            kept.add(KEY_USER + "=" + username(context));
            kept.add(KEY_EXPIRES + "=" + expires(context));

            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < kept.size(); i++) {
                sb.append(kept.get(i)).append('\n');
            }
            OutputStream out = null;
            try {
                out = new FileOutputStream(file);
                out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
            } finally {
                if (out != null) {
                    out.close();
                }
            }
        } catch (Throwable ignored) {
        }
    }

    // ---------------- HTTP ----------------

    private static String requestCode() {
        try {
            JSONObject o = post(DEVICE_START, "{}");
            if (o == null || !o.optBoolean("ok", false) || !o.has("data")) {
                return null;
            }
            JSONObject data = o.optJSONObject("data");
            return data == null ? null : data.optString("code", "");
        } catch (Throwable e) {
            return null;
        }
    }

    private static String poll(String code) {
        long deadline = System.currentTimeMillis() + 10L * 60L * 1000L;
        String body = "{\"code\":\"" + code + "\"}";
        while (System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(3000L);
            } catch (InterruptedException e) {
                return null;
            }
            try {
                JSONObject o = post(DEVICE_POLL, body);
                if (o == null || !o.optBoolean("ok", false)) {
                    continue;
                }
                String t = o.optString("access_token", "");
                if (t != null && !t.isEmpty()) {
                    return t;
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static JSONObject post(String url, String json) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(20000);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("User-Agent", "breakfront-launcher-android");
            OutputStream os = conn.getOutputStream();
            os.write(json.getBytes(StandardCharsets.UTF_8));
            os.close();
            if (conn.getResponseCode() / 100 != 2) {
                return null;
            }
            InputStream in = conn.getInputStream();
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line);
            }
            r.close();
            return new JSONObject(sb.toString());
        } catch (Throwable e) {
            return null;
        } finally {
            if (conn != null) {
                try {
                    conn.disconnect();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    // ---------------- JWT ----------------

    private static String claim(String jwt, String key) {
        try {
            JSONObject p = payload(jwt);
            return p == null ? "" : p.optString(key, "");
        } catch (Throwable e) {
            return "";
        }
    }

    private static long expiry(String jwt) {
        try {
            JSONObject p = payload(jwt);
            return p == null ? 0L : p.optLong("exp", 0L);
        } catch (Throwable e) {
            return 0L;
        }
    }

    private static JSONObject payload(String jwt) {
        if (jwt == null) {
            return null;
        }
        String[] parts = jwt.split("\\.");
        if (parts.length < 2) {
            return null;
        }
        byte[] raw = Base64.decode(parts[1], Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
        return new JSONObject(new String(raw, StandardCharsets.UTF_8));
    }

    // ---------------- ui helpers ----------------

    private static void ui(Runnable r) {
        MAIN.post(r);
    }

    private static void toast(final Context c, final String msg) {
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                try {
                    Toast.makeText(c, msg, Toast.LENGTH_LONG).show();
                } catch (Throwable ignored) {
                }
            }
        });
    }
}
