package com.nijagent.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

/**
 * Free AI fallbacks: Groq (free key) aur Pollinations (bina key).
 * Gemini fail ho to ye try hote hain.
 */
public class FreeAiClient {

    /** Groq: OpenAI-compatible chat API. Model: openai/gpt-oss-20b (free tier). */
    public static String callGroq(String key, String system, String user) throws IOException {
        try {
            JSONObject msg1 = new JSONObject();
            msg1.put("role", "system");
            msg1.put("content", system);
            JSONObject msg2 = new JSONObject();
            msg2.put("role", "user");
            msg2.put("content", user);

            JSONObject body = new JSONObject();
            body.put("model", "openai/gpt-oss-20b");
            body.put("messages", new JSONArray().put(msg1).put(msg2));
            body.put("temperature", 0.2);
            body.put("response_format", new JSONObject().put("type", "json_object"));

            String resp = postJson("https://api.groq.com/openai/v1/chat/completions", "Bearer " + key, body.toString());
            JSONObject j = new JSONObject(resp);
            return j.getJSONArray("choices").getJSONObject(0)
                    .getJSONObject("message").getString("content");
        } catch (org.json.JSONException je) {
            throw new IOException("Groq JSON error: " + je.getMessage());
        }
    }

    /** Pollinations: bina key, seedha GET. JSON mode ke liye prompt me instruction. */
    public static String callPollinations(String system, String user) throws IOException {
        try {
            String prompt = system + "\n\n" + user + "\n\n(Reply ONLY with valid JSON, no markdown.)";
            String url = "https://text.pollinations.ai/" + URLEncoder.encode(prompt, "UTF-8")
                    + "?model=openai";
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            try {
                c.setRequestMethod("GET");
                c.setConnectTimeout(20000);
                c.setReadTimeout(70000);
                int code = c.getResponseCode();
                if (code != 200) throw new IOException("Pollinations HTTP " + code);
                return readAll(c.getInputStream());
            } finally {
                c.disconnect();
            }
        } catch (IOException ioe) {
            throw ioe;
        } catch (Exception e) {
            throw new IOException("Pollinations error: " + e.getMessage());
        }
    }

    private static String postJson(String urlStr, String auth, String json) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
        try {
            c.setRequestMethod("POST");
            c.setConnectTimeout(20000);
            c.setReadTimeout(70000);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("Authorization", auth);
            OutputStream os = c.getOutputStream();
            os.write(json.getBytes("UTF-8"));
            os.close();
            int code = c.getResponseCode();
            if (code != 200) {
                String err = "";
                try { err = readAll(c.getErrorStream()); } catch (Exception ignored) {}
                throw new IOException("HTTP " + code + ": " + err.substring(0, Math.min(200, err.length())));
            }
            return readAll(c.getInputStream());
        } finally {
            c.disconnect();
        }
    }

    private static String readAll(InputStream in) throws IOException {
        if (in == null) return "";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        in.close();
        return out.toString("UTF-8");
    }
}
