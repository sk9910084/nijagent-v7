package com.nijagent.app;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Skills (seekhe hue workflows) aur task history ko phone ki private storage me rakhta hai.
 *
 * Skill JSON:
 * { id, name, desc, triggers:["youtube pe {query} search karo"], params:["query"],
 *   steps:[{a:"open",app:"YouTube"},{a:"tap",sel:{id,text,desc,n}},{a:"type",text:"{query}"},...],
 *   enabled, source:"learned|recorded|imported", uses, ok, fail, streak_fail, broken, version, created, updated }
 */
public class SkillStore {

    private static SkillStore instance;

    public static synchronized SkillStore get(Context c) {
        if (instance == null) instance = new SkillStore(c.getApplicationContext());
        return instance;
    }

    private final File skillFile;
    private final File histFile;
    private JSONArray skills;
    private JSONArray history;

    private SkillStore(Context c) {
        skillFile = new File(c.getFilesDir(), "skills.json");
        histFile = new File(c.getFilesDir(), "history.json");
        skills = readArr(skillFile);
        history = readArr(histFile);
        // Pehli baar: 10 default skills load karo
        if (skills.length() == 0) {
            try {
                java.io.InputStream in = c.getAssets().open("default_skills.json");
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[8192]; int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                in.close();
                skills = new JSONArray(out.toString("UTF-8"));
                saveSkills();
            } catch (Exception ignored) {}
        }
    }

    private void saveSkills() {
        try {
            java.io.FileWriter w = new java.io.FileWriter(skillFile);
            w.write(skills.toString());
            w.close();
        } catch (Exception ignored) {}
    }

    // ---------------------------------------------------------------- file io

    private static JSONArray readArr(File f) {
        try {
            if (!f.exists()) return new JSONArray();
            FileInputStream in = new FileInputStream(f);
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            return new JSONArray(bo.toString("UTF-8"));
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    private static void writeArr(File f, JSONArray a) {
        try {
            File tmp = new File(f.getPath() + ".tmp");
            FileOutputStream out = new FileOutputStream(tmp);
            out.write(a.toString().getBytes("UTF-8"));
            out.close();
            if (!tmp.renameTo(f)) {
                FileOutputStream o2 = new FileOutputStream(f);
                o2.write(a.toString().getBytes("UTF-8"));
                o2.close();
                tmp.delete();
            }
        } catch (Exception ignored) {
        }
    }

    // ---------------------------------------------------------------- skills

    public synchronized String skillsJson() {
        return skills.toString();
    }

    public synchronized int count() {
        return skills.length();
    }

    public synchronized JSONObject find(String idOrName) {
        if (idOrName == null) return null;
        String q = idOrName.trim().toLowerCase();
        for (int i = 0; i < skills.length(); i++) {
            JSONObject s = skills.optJSONObject(i);
            if (s == null) continue;
            if (q.equals(s.optString("id").toLowerCase())) return s;
        }
        for (int i = 0; i < skills.length(); i++) {
            JSONObject s = skills.optJSONObject(i);
            if (s == null) continue;
            if (q.equals(s.optString("name").trim().toLowerCase())) return s;
        }
        return null;
    }

    /** Naya skill jodta hai. Agar replaceId diya hai (ya same naam ka skill hai) to usko badal deta hai. */
    public synchronized JSONObject upsert(JSONObject skill, String replaceId) {
        try {
            long now = System.currentTimeMillis();
            JSONObject old = null;
            if (replaceId != null && replaceId.length() > 0) old = find(replaceId);
            if (old == null) old = findByName(skill.optString("name"));
            if (skill.optString("id").length() == 0) {
                skill.put("id", old != null ? old.optString("id") : "s_" + now);
            }
            if (old != null) {
                skill.put("id", old.optString("id"));
                skill.put("uses", old.optInt("uses", 0));
                skill.put("ok", old.optInt("ok", 0));
                skill.put("fail", old.optInt("fail", 0));
                skill.put("created", old.optLong("created", now));
                skill.put("version", old.optInt("version", 1) + 1);
                skill.put("streak_fail", 0);
                skill.put("broken", false);
                skill.put("enabled", old.optBoolean("enabled", true));
            } else {
                skill.put("uses", 0);
                skill.put("ok", 0);
                skill.put("fail", 0);
                skill.put("created", now);
                skill.put("version", 1);
                skill.put("streak_fail", 0);
                skill.put("broken", false);
                skill.put("enabled", true);
            }
            skill.put("updated", now);
            boolean replaced = false;
            for (int i = 0; i < skills.length(); i++) {
                JSONObject s = skills.optJSONObject(i);
                if (s != null && s.optString("id").equals(skill.optString("id"))) {
                    skills.put(i, skill);
                    replaced = true;
                    break;
                }
            }
            if (!replaced) skills.put(skill);
            writeArr(skillFile, skills);
            return skill;
        } catch (JSONException e) {
            return null;
        }
    }

    private JSONObject findByName(String name) {
        if (name == null) return null;
        String q = name.trim().toLowerCase();
        if (q.length() == 0) return null;
        for (int i = 0; i < skills.length(); i++) {
            JSONObject s = skills.optJSONObject(i);
            if (s != null && q.equals(s.optString("name").trim().toLowerCase())) return s;
        }
        return null;
    }

    public synchronized boolean delete(String id) {
        JSONArray n = new JSONArray();
        boolean removed = false;
        for (int i = 0; i < skills.length(); i++) {
            JSONObject s = skills.optJSONObject(i);
            if (s != null && s.optString("id").equals(id)) {
                removed = true;
                continue;
            }
            n.put(s);
        }
        if (removed) {
            skills = n;
            writeArr(skillFile, skills);
        }
        return removed;
    }

    public synchronized void setEnabled(String id, boolean on) {
        JSONObject s = find(id);
        if (s == null) return;
        try {
            s.put("enabled", on);
            if (on) {
                s.put("broken", false);
                s.put("streak_fail", 0);
            }
        } catch (JSONException ignored) {
        }
        writeArr(skillFile, skills);
    }

    public synchronized void recordResult(String id, boolean success) {
        JSONObject s = find(id);
        if (s == null) return;
        try {
            s.put("uses", s.optInt("uses", 0) + 1);
            if (success) {
                s.put("ok", s.optInt("ok", 0) + 1);
                s.put("streak_fail", 0);
                s.put("broken", false);
            } else {
                s.put("fail", s.optInt("fail", 0) + 1);
                int st = s.optInt("streak_fail", 0) + 1;
                s.put("streak_fail", st);
                if (st >= 3) s.put("broken", true);
            }
            s.put("last_used", System.currentTimeMillis());
        } catch (JSONException ignored) {
        }
        writeArr(skillFile, skills);
    }

    /** User ne "galat hua" dabaya: skill ko do baar fail gino. */
    public synchronized void penalize(String id) {
        JSONObject s = find(id);
        if (s == null) return;
        try {
            s.put("fail", s.optInt("fail", 0) + 1);
            int st = s.optInt("streak_fail", 0) + 2;
            s.put("streak_fail", st);
            if (st >= 3) s.put("broken", true);
        } catch (JSONException ignored) {
        }
        writeArr(skillFile, skills);
    }

    /** Skills ko import karta hai (JSON array ya single object). Wapas kitne judhe woh deta hai. */
    public synchronized int importJson(String json) {
        int added = 0;
        try {
            JSONArray arr;
            String t = json.trim();
            if (t.startsWith("[")) arr = new JSONArray(t);
            else {
                arr = new JSONArray();
                arr.put(new JSONObject(t));
            }
            for (int i = 0; i < arr.length(); i++) {
                JSONObject s = arr.optJSONObject(i);
                if (s == null) continue;
                if (s.optString("name").length() == 0) continue;
                JSONArray st = s.optJSONArray("steps");
                if (st == null || st.length() == 0) continue;
                s.remove("id");
                s.put("source", "imported");
                if (upsert(s, null) != null) added++;
            }
        } catch (Exception ignored) {
        }
        return added;
    }

    /**
     * Command ko saved skills ke triggers se match karta hai (bina Gemini ke).
     * Sirf poora match maana jata hai. Params out me bhar deta hai.
     */
    public synchronized JSONObject matchCommand(String cmd, Map<String, String> outParams) {
        String c = norm(cmd);
        if (c.length() == 0) return null;
        for (int i = 0; i < skills.length(); i++) {
            JSONObject s = skills.optJSONObject(i);
            if (s == null) continue;
            if (!s.optBoolean("enabled", true) || s.optBoolean("broken", false)) continue;
            JSONArray trig = s.optJSONArray("triggers");
            if (trig == null) continue;
            for (int j = 0; j < trig.length(); j++) {
                String t = norm(trig.optString(j));
                if (t.length() == 0) continue;
                List<String> names = new ArrayList<>();
                Pattern p = toPattern(t, names);
                Matcher m = p.matcher(c);
                if (m.matches()) {
                    outParams.clear();
                    for (int g = 0; g < names.size(); g++) {
                        String v = m.group(g + 1);
                        outParams.put(names.get(g), v == null ? "" : v.trim());
                    }
                    return s;
                }
            }
        }
        return null;
    }

    private static Pattern toPattern(String t, List<String> names) {
        StringBuilder re = new StringBuilder("^");
        Matcher m = Pattern.compile("\\{([a-z0-9_]+)\\}").matcher(t);
        int last = 0;
        while (m.find()) {
            re.append(Pattern.quote(t.substring(last, m.start())));
            re.append("(.+?)");
            names.add(m.group(1));
            last = m.end();
        }
        re.append(Pattern.quote(t.substring(last)));
        re.append("$");
        return Pattern.compile(re.toString());
    }

    public static String norm(String s) {
        if (s == null) return "";
        String x = s.toLowerCase().trim();
        x = x.replaceAll("[^\\p{L}\\p{N}\\p{M}{}_ ]", " ");
        x = x.replaceAll("\\s+", " ").trim();
        return x;
    }

    /** Gemini ko dikhane ke liye skills ki chhoti list. */
    public synchronized String skillsForPrompt() {
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (int i = 0; i < skills.length() && n < 40; i++) {
            JSONObject s = skills.optJSONObject(i);
            if (s == null) continue;
            if (!s.optBoolean("enabled", true) || s.optBoolean("broken", false)) continue;
            sb.append("- ").append(s.optString("name")).append(" | ").append(s.optString("desc"));
            JSONArray p = s.optJSONArray("params");
            if (p != null && p.length() > 0) sb.append(" | params: ").append(p.toString());
            sb.append("\n");
            n++;
        }
        return sb.length() == 0 ? "(koi skill nahi)" : sb.toString();
    }

    // ---------------------------------------------------------------- history

    public synchronized void addHistory(String cmd, boolean ok, String summary, String mode, String skill) {
        try {
            JSONObject h = new JSONObject();
            h.put("t", System.currentTimeMillis());
            h.put("cmd", cmd);
            h.put("ok", ok);
            h.put("summary", summary == null ? "" : summary);
            h.put("mode", mode);
            h.put("skill", skill == null ? "" : skill);
            history.put(h);
            if (history.length() > 150) {
                JSONArray n = new JSONArray();
                for (int i = history.length() - 150; i < history.length(); i++) n.put(history.get(i));
                history = n;
            }
            writeArr(histFile, history);
        } catch (JSONException ignored) {
        }
    }

    public synchronized String historyJson() {
        return history.toString();
    }

    public synchronized void clearHistory() {
        history = new JSONArray();
        writeArr(histFile, history);
    }

    public synchronized int doneCount() {
        int n = 0;
        for (int i = 0; i < history.length(); i++) {
            JSONObject h = history.optJSONObject(i);
            if (h != null && h.optBoolean("ok")) n++;
        }
        return n;
    }
}
