package com.nijagent.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** Gemini REST API (generateContent). Blocking hai, isliye hamesha background thread se bulao. */
public class GeminiClient {

    public static final String DEFAULT_MODEL = "gemini-flash-latest";

    /**
     * @param key     Gemini API key
     * @param model   jaise "gemini-flash-latest"
     * @param system  system instruction
     * @param user    user text
     * @param jpegB64 optional screenshot (base64 JPEG), null ho sakta hai
     * @return model ka text jawab (JSON mode me JSON string)
     */
    public static String call(String key, String model, String system, String user, String jpegB64)
            throws IOException {
        if (model == null || model.trim().length() == 0) model = DEFAULT_MODEL;
        try {
            JSONObject body = new JSONObject();
            JSONObject sys = new JSONObject();
            sys.put("parts", new JSONArray().put(new JSONObject().put("text", system)));
            body.put("system_instruction", sys);

            JSONArray parts = new JSONArray();
            parts.put(new JSONObject().put("text", user));
            if (jpegB64 != null) {
                JSONObject img = new JSONObject();
                img.put("mime_type", "image/jpeg");
                img.put("data", jpegB64);
                parts.put(new JSONObject().put("inline_data", img));
            }
            JSONObject content = new JSONObject();
            content.put("role", "user");
            content.put("parts", parts);
            body.put("contents", new JSONArray().put(content));

            JSONObject gen = new JSONObject();
            gen.put("temperature", 0.2);
            gen.put("responseMimeType", "application/json");
            body.put("generationConfig", gen);

            String url = "https://generativelanguage.googleapis.com/v1beta/models/" + model.trim() + ":generateContent";
            IOException last = null;
            for (int attempt = 0; attempt < 3; attempt++) {
                try {
                    String resp = post(url, key, body.toString());
                    return extractText(resp);
                } catch (HttpException he) {
                    last = he;
                    if (he.code == 429 || he.code >= 500) {
                        try {
                            Thread.sleep(2500L * (attempt + 1));
                        } catch (InterruptedException ie) {
                            throw new IOException("Roka gaya");
                        }
                        continue;
                    }
                    throw he;
                }
            }
            throw last != null ? last : new IOException("Gemini jawab nahi de paya");
        } catch (org.json.JSONException je) {
            throw new IOException("JSON error: " + je.getMessage());
        }
    }

    static class HttpException extends IOException {
        final int code;

        HttpException(int code, String msg) {
            super(msg);
            this.code = code;
        }
    }

    private static String post(String urlStr, String key, String json) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
        try {
            c.setRequestMethod("POST");
            c.setConnectTimeout(20000);
            c.setReadTimeout(70000);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("x-goog-api-key", key);
            OutputStream os = c.getOutputStream();
            os.write(json.getBytes("UTF-8"));
            os.close();
            int code = c.getResponseCode();
            InputStream is = code >= 400 ? c.getErrorStream() : c.getInputStream();
            String text = read(is);
            if (code >= 400) {
                String msg = text;
                try {
                    msg = new JSONObject(text).getJSONObject("error").optString("message", text);
                } catch (Exception ignored) {
                }
                if (msg.length() > 200) msg = msg.substring(0, 200);
                throw new HttpException(code, "Gemini HTTP " + code + ": " + msg);
            }
            return text;
        } finally {
            c.disconnect();
        }
    }

    private static String read(InputStream is) throws IOException {
        if (is == null) return "";
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) > 0) bo.write(buf, 0, n);
        is.close();
        return bo.toString("UTF-8");
    }

    private static String extractText(String resp) throws IOException {
        try {
            JSONObject r = new JSONObject(resp);
            JSONArray cands = r.optJSONArray("candidates");
            if (cands == null || cands.length() == 0) {
                JSONObject pf = r.optJSONObject("promptFeedback");
                String why = pf != null ? pf.optString("blockReason", "") : "";
                throw new IOException("Gemini ne jawab nahi diya" + (why.length() > 0 ? " (" + why + ")" : ""));
            }
            JSONObject content = cands.getJSONObject(0).optJSONObject("content");
            if (content == null) throw new IOException("Gemini ka jawab khaali tha");
            JSONArray parts = content.optJSONArray("parts");
            StringBuilder sb = new StringBuilder();
            if (parts != null) {
                for (int i = 0; i < parts.length(); i++) {
                    JSONObject p = parts.optJSONObject(i);
                    if (p != null && p.has("text") && !p.optBoolean("thought", false)) sb.append(p.optString("text"));
                }
            }
            if (sb.length() == 0) throw new IOException("Gemini ka jawab khaali tha");
            return sb.toString();
        } catch (org.json.JSONException e) {
            throw new IOException("Gemini jawab padh nahi paya");
        }
    }

    /** Error ko aasaan Hinglish me badalta hai. */
    public static String friendly(Throwable t) {
        String m = t.getMessage() == null ? t.toString() : t.getMessage();
        if (t instanceof java.net.UnknownHostException || t instanceof java.net.ConnectException
                || t instanceof java.net.SocketTimeoutException) {
            return "Internet nahi chal raha ya Gemini tak nahi pahunch paya.";
        }
        if (m.contains("HTTP 429")) return "Gemini ki free limit lag gayi. Thodi der baad dobara try karo.";
        if (m.contains("HTTP 400") && m.toLowerCase().contains("api key")) return "Gemini API key galat hai.";
        if (m.contains("HTTP 403") || m.contains("HTTP 401")) return "Gemini API key kaam nahi kar rahi (galat ya band).";
        if (m.contains("HTTP 404")) return "Model ka naam galat hai. Settings me model badlo.";
        return m;
    }
}
