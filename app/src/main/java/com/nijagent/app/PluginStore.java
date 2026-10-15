package com.nijagent.app;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * HTTP API Plugins — user apne API endpoints (MCP-style HTTP servers, AI APIs, custom tools)
 * yahan jod sakta hai. Agent `call_plugin` action se inhe call karta hai.
 *
 * Plugin JSON: {id, name, url, method(GET/POST), headers{...}, apiKey, keyHeader, keyParam,
 *               bodyTemplate, description, enabled}
 * - apiKey: agar keyHeader set hai to header me, warna keyParam query param me jayega
 * - bodyTemplate: POST body me {input} ki jagah user input jayega
 */
public class PluginStore {

    private static PluginStore inst;

    public static synchronized PluginStore get(Context c) {
        if (inst == null) inst = new PluginStore(c.getApplicationContext());
        return inst;
    }

    private final File file;
    private JSONArray plugins = new JSONArray();

    private PluginStore(Context c) {
        file = new File(c.getFilesDir(), "plugins.json");
        load();
    }

    private void load() {
        try {
            if (!file.exists()) return;
            BufferedReader r = new BufferedReader(new FileReader(file));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            r.close();
            plugins = new JSONArray(sb.toString());
        } catch (Throwable ignored) {
            plugins = new JSONArray();
        }
    }

    private void save() {
        try {
            FileWriter w = new FileWriter(file);
            w.write(plugins.toString());
            w.close();
        } catch (Throwable ignored) {
        }
    }

    public synchronized String listJson() {
        // apiKey ko list me mask karo
        try {
            JSONArray out = new JSONArray();
            for (int i = 0; i < plugins.length(); i++) {
                JSONObject p = plugins.optJSONObject(i);
                if (p == null) continue;
                JSONObject c = new JSONObject(p.toString());
                if (c.optString("apiKey").length() > 4) {
                    c.put("apiKey", "****" + c.optString("apiKey").substring(c.optString("apiKey").length() - 4));
                } else if (c.optString("apiKey").length() > 0) {
                    c.put("apiKey", "****");
                }
                out.put(c);
            }
            return out.toString();
        } catch (Throwable t) {
            return "[]";
        }
    }

    public synchronized JSONObject find(String idOrName) {
        for (int i = 0; i < plugins.length(); i++) {
            JSONObject p = plugins.optJSONObject(i);
            if (p == null) continue;
            if (p.optString("id").equalsIgnoreCase(idOrName) || p.optString("name").equalsIgnoreCase(idOrName))
                return p;
        }
        return null;
    }

    public synchronized String add(String name, String url, String method, String apiKey,
                                   String keyHeader, String description) {
        try {
            JSONObject p = new JSONObject();
            String id = "plug_" + System.currentTimeMillis();
            p.put("id", id);
            p.put("name", name);
            p.put("url", url);
            p.put("method", (method == null || method.isEmpty()) ? "POST" : method.toUpperCase());
            p.put("apiKey", apiKey == null ? "" : apiKey);
            p.put("keyHeader", (keyHeader == null || keyHeader.isEmpty()) ? "Authorization" : keyHeader);
            p.put("description", description == null ? "" : description);
            p.put("enabled", true);
            p.put("created", System.currentTimeMillis());
            plugins.put(p);
            save();
            return id;
        } catch (Throwable t) {
            return null;
        }
    }

    public synchronized boolean remove(String id) {
        for (int i = 0; i < plugins.length(); i++) {
            JSONObject p = plugins.optJSONObject(i);
            if (p != null && p.optString("id").equals(id)) {
                plugins.remove(i);
                save();
                return true;
            }
        }
        return false;
    }

    public synchronized boolean toggle(String id, boolean on) {
        JSONObject p = find(id);
        if (p == null) return false;
        try {
            p.put("enabled", on);
            save();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Agent ke liye prompt text: available plugins ki list. */
    public synchronized String forPrompt() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < plugins.length(); i++) {
            JSONObject p = plugins.optJSONObject(i);
            if (p == null || !p.optBoolean("enabled", true)) continue;
            sb.append("- ").append(p.optString("name"))
              .append(": ").append(p.optString("description")).append("\n");
        }
        return sb.length() == 0 ? "(koi plugin nahi joda)" : sb.toString();
    }

    /**
     * Plugin call karo. Input ko JSON body me bhejo.
     * @return result string (response body, 4000 chars tak)
     */
    public String call(String idOrName, String input, Map<String, String> extra) {
        JSONObject p = find(idOrName);
        if (p == null) return "ERROR: plugin nahi mila: " + idOrName;
        if (!p.optBoolean("enabled", true)) return "ERROR: plugin band hai: " + p.optString("name");
        HttpURLConnection conn = null;
        try {
            String urlStr = p.optString("url");
            String method = p.optString("method", "POST");
            String apiKey = p.optString("apiKey", "");
            String keyHeader = p.optString("keyHeader", "Authorization");

            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod(method);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Accept", "application/json");
            if (apiKey.length() > 0) {
                if (keyHeader.equalsIgnoreCase("Bearer")) {
                    conn.setRequestProperty("Authorization", "Bearer " + apiKey);
                } else {
                    conn.setRequestProperty(keyHeader, apiKey);
                }
            }

            if (method.equals("POST") || method.equals("PUT") || method.equals("PATCH")) {
                conn.setDoOutput(true);
                JSONObject body = new JSONObject();
                body.put("input", input == null ? "" : input);
                if (extra != null) {
                    for (Map.Entry<String, String> e : extra.entrySet()) body.put(e.getKey(), e.getValue());
                }
                byte[] bytes = body.toString().getBytes("UTF-8");
                OutputStream os = conn.getOutputStream();
                os.write(bytes);
                os.flush();
                os.close();
            }

            int code = conn.getResponseCode();
            InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
            BufferedReader r = new BufferedReader(new InputStreamReader(is, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null && sb.length() < 4000) sb.append(line).append("\n");
            r.close();
            String resp = sb.toString().trim();
            if (resp.length() > 4000) resp = resp.substring(0, 4000) + "...";
            if (code < 200 || code >= 300) return "ERROR " + code + ": " + resp;
            return resp;
        } catch (Throwable t) {
            return "ERROR: " + t.getMessage();
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}
