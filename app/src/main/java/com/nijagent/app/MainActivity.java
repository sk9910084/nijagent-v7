package com.nijagent.app;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity implements AgentService.Listener {

    private WebView web;
    private SharedPreferences prefs;
    private SkillStore store;
    private SpeechRecognizer recognizer;
    private TextToSpeech tts;
    private boolean ttsReady = false;
    private boolean resumed = false;
    private String pendingDone = null;
    private FrameLayout browserContainer;

    /** Agent ne code banaya — UI ko batao (Code tab refresh). */
    public void onCodeReady(final String lang, final String code, final String fname) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                js("window.onCodeReady&&onCodeReady(" + q(lang) + "," + q(fname) + ")");
            }
        });
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("nijagent", MODE_PRIVATE);
        store = SkillStore.get(this);
        if (!prefs.contains("first_run")) prefs.edit().putLong("first_run", System.currentTimeMillis()).apply();

        getWindow().setStatusBarColor(0xFF0B0E14);
        getWindow().setNavigationBarColor(0xFF151A24);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF0B0E14);
        web = new WebView(this);
        web.setBackgroundColor(0xFF0B0E14);
        root.addView(web, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);

        WebSettings ws = web.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setAllowFileAccess(false);
        ws.setAllowContentAccess(false);
        ws.setAllowFileAccessFromFileURLs(false);
        ws.setAllowUniversalAccessFromFileURLs(false);
        ws.setBlockNetworkLoads(true);
        ws.setSupportZoom(false);
        web.setOverScrollMode(WebView.OVER_SCROLL_NEVER);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                return !r.getUrl().toString().startsWith("file:///android_asset/");
            }
        });
        web.addJavascriptInterface(new Bridge(), "NijAgent");
        web.loadUrl("file:///android_asset/ui.html");

        // BrowserHub container (hidden overlay for in-app browser)
        browserContainer = new FrameLayout(this);
        browserContainer.setVisibility(View.GONE);
        // Top toolbar with back + close
        android.widget.LinearLayout bar = new android.widget.LinearLayout(this);
        bar.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        bar.setBackgroundColor(0xFF151A24);
        int pad = (int) (12 * getResources().getDisplayMetrics().density);
        bar.setPadding(pad, pad, pad, pad);
        android.widget.Button backBtn = new android.widget.Button(this);
        backBtn.setText("‹ Back");
        backBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { BrowserHub.get(MainActivity.this).goBack(); }
        });
        android.widget.Button closeBtn = new android.widget.Button(this);
        closeBtn.setText("✕ Band karo");
        closeBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                BrowserHub b = BrowserHub.get(MainActivity.this);
                b.shownInActivity = false;
                b.detach();
                browserContainer.setVisibility(View.GONE);
            }
        });
        android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        bar.addView(backBtn, lp);
        bar.addView(closeBtn, lp);
        browserContainer.addView(bar, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        // WebView holder below toolbar
        FrameLayout webHolder = new FrameLayout(this);
        webHolder.setId(View.generateViewId());
        FrameLayout.LayoutParams hlp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        hlp.topMargin = (int) (64 * getResources().getDisplayMetrics().density);
        browserContainer.addView(webHolder, hlp);
        browserContainer.setTag(webHolder);
        root.addView(browserContainer, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        tts = new TextToSpeech(this, new TextToSpeech.OnInitListener() {
            @Override
            public void onInit(int status) {
                if (status == TextToSpeech.SUCCESS) {
                    int r = tts.setLanguage(new Locale("hi", "IN"));
                    if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                        tts.setLanguage(Locale.getDefault());
                    }
                    ttsReady = true;
                }
            }
        });
        AgentService.listener = this;
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        AgentService.listener = this;
        if (pendingDone != null) {
            String d = pendingDone;
            pendingDone = null;
            js("window.onTaskDone&&onTaskDone(" + d + ")");
        }
        js("window.onResumeApp&&onResumeApp()");
    }

    @Override
    protected void onPause() {
        resumed = false;
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (AgentService.listener == this) AgentService.listener = null;
        if (recognizer != null) recognizer.destroy();
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
        if (web != null) {
            web.removeJavascriptInterface("NijAgent");
            web.destroy();
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        web.evaluateJavascript("(function(){try{return window.handleBack?handleBack():false;}catch(e){return false;}})()",
                new ValueCallback<String>() {
                    @Override
                    public void onReceiveValue(String v) {
                        if (!"true".equals(v)) moveTaskToBack(true);
                    }
                });
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (code == 7) {
            boolean ok = res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED;
            js("window.onMicPermission&&onMicPermission(" + ok + ")");
        }
    }

    // ================================================================ helpers

    private void js(final String code) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (web != null) web.evaluateJavascript(code, null);
            }
        });
    }

    private static String q(String s) {
        return JSONObject.quote(s == null ? "" : s);
    }

    private boolean serviceOn() {
        if (AgentService.instance != null) return true;
        try {
            String s = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            return s != null && s.toLowerCase().contains(getPackageName().toLowerCase() + "/");
        } catch (Throwable t) {
            return false;
        }
    }

    private void say(String text) {
        if (!ttsReady || text == null) return;
        String clean = text.replaceAll("[\\p{So}\\p{Cn}\\u2600-\\u27BF]", "").replaceAll("\\s+", " ").trim();
        if (clean.length() == 0) return;
        if (clean.length() > 300) clean = clean.substring(0, 300);
        tts.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "nij");
    }

    // ================================================================ AgentService.Listener

    @Override
    public void onStep(String json) {
        js("window.onStep&&onStep(" + json + ")");
    }

    @Override
    public void onResult(String text) {
        js("window.onAgentResult&&onAgentResult(" + q(text) + ")");
    }

    @Override
    public void onDone(String json) {
        try {
            JSONObject o = new JSONObject(json);
            if (prefs.getBoolean("voice_reply", true)) say(o.optString("summary"));
        } catch (Throwable ignored) {
        }
        if (resumed) js("window.onTaskDone&&onTaskDone(" + json + ")");
        else pendingDone = json;
    }

    @Override
    public void onSkillsChanged() {
        js("window.onSkillsChanged&&onSkillsChanged()");
    }

    @Override
    public void onLearn(String json) {
        js("window.onLearn&&onLearn(" + json + ")");
    }

    @Override
    public void onRecord(String json) {
        js("window.onRecordState&&onRecordState(" + json + ")");
    }

    // ================================================================ voice

    private void startListening() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (!SpeechRecognizer.isRecognitionAvailable(MainActivity.this)) {
                    Toast.makeText(MainActivity.this, "Is phone me voice recognition nahi hai", Toast.LENGTH_LONG).show();
                    js("window.onVoiceResult&&onVoiceResult('')");
                    return;
                }
                if (recognizer == null) {
                    recognizer = SpeechRecognizer.createSpeechRecognizer(MainActivity.this);
                    recognizer.setRecognitionListener(new RecognitionListener() {
                        @Override
                        public void onReadyForSpeech(Bundle p) {
                            js("window.onVoiceState&&onVoiceState('listening')");
                        }

                        @Override
                        public void onBeginningOfSpeech() {
                        }

                        @Override
                        public void onRmsChanged(float v) {
                        }

                        @Override
                        public void onBufferReceived(byte[] b) {
                        }

                        @Override
                        public void onEndOfSpeech() {
                            js("window.onVoiceState&&onVoiceState('idle')");
                        }

                        @Override
                        public void onError(int e) {
                            js("window.onVoiceResult&&onVoiceResult('')");
                        }

                        @Override
                        public void onResults(Bundle r) {
                            ArrayList<String> l = r.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                            String t = (l != null && !l.isEmpty()) ? l.get(0) : "";
                            js("window.onVoiceResult&&onVoiceResult(" + q(t) + ")");
                        }

                        @Override
                        public void onPartialResults(Bundle r) {
                            ArrayList<String> l = r.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                            if (l != null && !l.isEmpty()) js("window.onVoicePartial&&onVoicePartial(" + q(l.get(0)) + ")");
                        }

                        @Override
                        public void onEvent(int t, Bundle p) {
                        }
                    });
                }
                Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
                i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
                i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hi-IN");
                i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
                recognizer.startListening(i);
            }
        });
    }

    // ================================================================ JS bridge

    public class Bridge {

        @JavascriptInterface
        public void setScreen(String name) {
        }

        // ---- tasks
        @JavascriptInterface
        public void sendCommand(final String cmd) {
            AgentService s = AgentService.instance;
            if (s == null) {
                js("window.onAgentResult&&onAgentResult(" + q("Agent service ON nahi hai. Pehle Accessibility me NijAgent ON karo.") + ")");
                js("window.onTaskDone&&onTaskDone(" + "{ok:false,summary:" + q("Accessibility service ON nahi hai. Profile > Permissions me ON karo.") + ",mode:'basic',skill:'',steps:0}" + ")");
                return;
            }
            s.sendCommand(cmd, 0);
        }

        @JavascriptInterface
        public void chat(final String msg) {            AgentService s = AgentService.instance;
            if (s == null) {
                // Bina service ke bhi chat kar sakte hain (sirf AI se)
                s = null;
            }
            final AgentService svc = s;
            if (svc != null) {
                svc.chat(msg);
            } else {
                // Fallback: direct AI chat without service
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            String key = prefs.getString("gemini_key", "");
                            String system = "You are NijAgent, a friendly phone AI assistant. Reply in Hinglish, short (2-3 lines).";
                            String reply;
                            if (key.length() > 10) {
                                try { reply = com.nijagent.app.GeminiClient.call(key, com.nijagent.app.GeminiClient.DEFAULT_MODEL, system, msg, null); }
                                catch (Exception e) { reply = com.nijagent.app.FreeAiClient.callPollinations(system, msg); }
                            } else {
                                reply = com.nijagent.app.FreeAiClient.callPollinations(system, msg);
                            }
                            final String r = reply;
                            runOnUiThread(new Runnable() {
                                @Override public void run() {
                                    js("window.onAgentResult&&onAgentResult(" + q(r) + ")");
                                }
                            });
                        } catch (Exception e) {
                            final String err = "Jawab nahi de paya: " + e.getMessage();
                            runOnUiThread(new Runnable() {
                                @Override public void run() {
                                    js("window.onAgentResult&&onAgentResult(" + q(err) + ")");
                                }
                            });
                        }
                    }
                }).start();
            }
        }

        // ---- BrowserHub (in-app browser)
        @JavascriptInterface
        public void browserOpen(final String url) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    BrowserHub b = BrowserHub.get(MainActivity.this);
                    FrameLayout holder = (FrameLayout) browserContainer.getTag();
                    b.attach(holder != null ? holder : browserContainer, MainActivity.this);
                    browserContainer.setVisibility(View.VISIBLE);
                    b.shownInActivity = true;
                    b.load(url == null || url.length() == 0 ? "https://www.google.com" : url);
                }
            });
        }

        @JavascriptInterface
        public void browserClose() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    BrowserHub b = BrowserHub.get(MainActivity.this);
                    b.shownInActivity = false;
                    b.detach();
                    browserContainer.setVisibility(View.GONE);
                }
            });
        }

        @JavascriptInterface
        public String browserState() {
            return BrowserHub.get(MainActivity.this).stateJson();
        }

        @JavascriptInterface
        public void browserGo(final String input) {
            BrowserHub.get(MainActivity.this).load(input);
        }

        // ---- Plugins
        @JavascriptInterface
        public String getPlugins() {
            return PluginStore.get(MainActivity.this).listJson();
        }

        @JavascriptInterface
        public String addPlugin(String name, String url, String method, String apiKey, String description) {
            String id = PluginStore.get(MainActivity.this).add(name, url, method, apiKey, "Authorization", description);
            return id == null ? "" : id;
        }

        @JavascriptInterface
        public boolean deletePlugin(String id) {
            return PluginStore.get(MainActivity.this).remove(id);
        }

        @JavascriptInterface
        public boolean togglePlugin(String id, boolean on) {
            return PluginStore.get(MainActivity.this).toggle(id, on);
        }

        @JavascriptInterface
        public String testPlugin(final String id, final String input) {            final String[] out = {""};
            Thread t = new Thread(new Runnable() {
                @Override public void run() {
                    out[0] = PluginStore.get(MainActivity.this).call(id, input == null ? "" : input, null);
                }
            });
            t.start();
            try { t.join(35000); } catch (InterruptedException ignored) {}
            String r = out[0];
            return r.length() > 2000 ? r.substring(0, 2000) + "..." : r;
        }

        // ---- Code files
        @JavascriptInterface
        public String getCodes() {
            AgentService s = AgentService.instance;
            return s == null ? "[]" : s.listCodes();
        }

        @JavascriptInterface
        public String readCode(String name) {
            AgentService s = AgentService.instance;
            return s == null ? "" : s.readCode(name);
        }

        @JavascriptInterface
        public void previewHtml(final String name) {
            final AgentService s = AgentService.instance;
            if (s == null) return;
            new Thread(new Runnable() {
                @Override public void run() {
                    final String html = s.readCode(name);
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            BrowserHub b = BrowserHub.get(MainActivity.this);
                            FrameLayout holder = (FrameLayout) browserContainer.getTag();
                            b.attach(holder != null ? holder : browserContainer, MainActivity.this);
                            browserContainer.setVisibility(View.VISIBLE);
                            b.shownInActivity = true;
                            b.loadHtml(html);
                        }
                    });
                }
            }).start();
        }

        @JavascriptInterface
        public void sendCommandDelayed(final String cmd, final int secs) {
            AgentService s = AgentService.instance;
            if (s == null) {
                toast("Pehle Accessibility ON karo");
                return;
            }
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    moveTaskToBack(true);
                }
            });
            s.sendCommand(cmd, Math.max(1, secs));
        }

        @JavascriptInterface
        public void stopTask() {
            AgentService s = AgentService.instance;
            if (s != null) s.stopTask();
        }

        @JavascriptInterface
        public boolean isRunning() {
            AgentService s = AgentService.instance;
            return s != null && s.isRunning();
        }

        @JavascriptInterface
        public void feedback(boolean good) {
            AgentService s = AgentService.instance;
            if (s != null) s.feedback(good);
        }

        @JavascriptInterface
        public String getServiceStatus() {
            return serviceOn() ? "on" : "off";
        }

        // ---- history & stats
        @JavascriptInterface
        public String getHistory() {
            return store.historyJson();
        }

        @JavascriptInterface
        public void clearHistory() {
            store.clearHistory();
        }

        @JavascriptInterface
        public String getStats() {
            try {
                JSONObject o = new JSONObject();
                o.put("tasks", store.doneCount());
                o.put("skills", store.count());
                o.put("apiCalls", prefs.getInt("api_calls", 0));
                o.put("since", prefs.getLong("first_run", System.currentTimeMillis()));
                return o.toString();
            } catch (Throwable t) {
                return "{}";
            }
        }

        // ---- skills
        @JavascriptInterface
        public String getSkills() {
            return store.skillsJson();
        }

        @JavascriptInterface
        public void deleteSkill(String id) {
            store.delete(id);
            onSkillsChanged();
        }

        @JavascriptInterface
        public void setSkillEnabled(String id, boolean on) {
            store.setEnabled(id, on);
            onSkillsChanged();
        }

        @JavascriptInterface
        public void runSkill(String id, String paramsJson) {
            AgentService s = AgentService.instance;
            if (s == null) {
                toast("Pehle Accessibility ON karo");
                return;
            }
            s.runSkillDirect(id, paramsJson);
        }

        @JavascriptInterface
        public String exportSkills() {
            return store.skillsJson();
        }

        @JavascriptInterface
        public int importSkills(String json) {
            int n = store.importJson(json);
            onSkillsChanged();
            return n;
        }

        @JavascriptInterface
        public void startRecording(String name) {
            AgentService s = AgentService.instance;
            if (s == null) {
                toast("Pehle Accessibility ON karo");
                return;
            }
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    moveTaskToBack(true);
                }
            });
            s.startRecording(name);
        }

        @JavascriptInterface
        public void stopRecording() {
            AgentService s = AgentService.instance;
            if (s != null) s.stopRecording();
        }

        @JavascriptInterface
        public boolean isRecording() {
            AgentService s = AgentService.instance;
            return s != null && s.isRecording();
        }

        // ---- prefs
        @JavascriptInterface
        public String getPref(String k, String d) {
            if (k.equals("gemini_key")) return prefs.getString(k, "").length() > 10 ? "set" : "";
            return prefs.getString(k, d);
        }

        @JavascriptInterface
        public boolean getPrefBool(String k, boolean d) {
            return prefs.getBoolean(k, d);
        }

        @JavascriptInterface
        public void setPref(String k, String v) {
            if (k.equals("gemini_key")) return;
            prefs.edit().putString(k, v).apply();
        }

        @JavascriptInterface
        public void setPrefBool(String k, boolean v) {
            prefs.edit().putBoolean(k, v).apply();
            js("window.onPrefChanged&&onPrefChanged(" + q(k) + ")");
        }

        @JavascriptInterface
        public void setInt(String k, int v) {
            if (k.equals("max_steps")) prefs.edit().putInt(k, Math.max(5, Math.min(60, v))).apply();
        }

        @JavascriptInterface
        public int getInt(String k, int d) {
            return prefs.getInt(k, d);
        }

        @JavascriptInterface
        public boolean hasKey() {
            return prefs.getString("gemini_key", "").length() > 10;
        }

        @JavascriptInterface
        public void saveKey(String k) {
            prefs.edit().putString("gemini_key", k.trim()).putBoolean("cloud_ai", true).apply();
        }

        @JavascriptInterface
        public void clearKey() {
            prefs.edit().remove("gemini_key").apply();
        }

        @JavascriptInterface
        public void testGemini() {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        String key = prefs.getString("gemini_key", "");
                        String model = prefs.getString("gemini_model", GeminiClient.DEFAULT_MODEL);
                        String r = GeminiClient.call(key, model, "Reply with JSON {\"ok\":true}", "ping", null);
                        js("window.onGeminiTest&&onGeminiTest(true," + q("Gemini connect ho gaya ✓") + ")");
                    } catch (Throwable t) {
                        js("window.onGeminiTest&&onGeminiTest(false," + q(GeminiClient.friendly(t)) + ")");
                    }
                }
            }).start();
        }

        // ---- voice
        @JavascriptInterface
        public void startVoice() {
            startListening();
        }

        @JavascriptInterface
        public void stopVoice() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (recognizer != null) recognizer.stopListening();
                }
            });
        }

        @JavascriptInterface
        public void speak(String t) {
            say(t);
        }

        @JavascriptInterface
        public boolean micGranted() {
            return Build.VERSION.SDK_INT < 23
                    || checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        }

        @JavascriptInterface
        public void requestMic() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 7);
                }
            });
        }

        // ---- system
        @JavascriptInterface
        public void openAccessibilitySettings() {
            Intent i = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        }

        @JavascriptInterface
        public void openAppInfo() {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        }

        @JavascriptInterface
        public void dial(String d) {
            Intent i = new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + (d == null ? "" : d)));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        }

        @JavascriptInterface
        public void openSms() {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("sms:"));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                startActivity(i);
            } catch (Throwable t) {
                toast("SMS app nahi mila");
            }
        }

        @JavascriptInterface
        public void toast(final String m) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(MainActivity.this, m, Toast.LENGTH_SHORT).show();
                }
            });
        }

        @JavascriptInterface
        public String greeting() {
            int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
            if (h < 12) return "Good Morning!";
            if (h < 17) return "Good Afternoon!";
            return "Good Evening!";
        }

        @JavascriptInterface
        public String installedApps() {
            try {
                PackageManager pm = getPackageManager();
                Intent mi = new Intent(Intent.ACTION_MAIN);
                mi.addCategory(Intent.CATEGORY_LAUNCHER);
                List<String> names = new ArrayList<>();
                for (ResolveInfo ri : pm.queryIntentActivities(mi, 0)) {
                    String l = String.valueOf(ri.loadLabel(pm));
                    if (!names.contains(l) && !l.equals("NijAgent")) names.add(l);
                }
                Collections.sort(names, String.CASE_INSENSITIVE_ORDER);
                return new JSONArray(names).toString();
            } catch (Throwable t) {
                return "[]";
            }
        }

        @JavascriptInterface
        public void openApp(String label) {
            try {
                PackageManager pm = getPackageManager();
                Intent mi = new Intent(Intent.ACTION_MAIN);
                mi.addCategory(Intent.CATEGORY_LAUNCHER);
                for (ResolveInfo ri : pm.queryIntentActivities(mi, 0)) {
                    if (String.valueOf(ri.loadLabel(pm)).equals(label)) {
                        Intent i = pm.getLaunchIntentForPackage(ri.activityInfo.packageName);
                        if (i != null) startActivity(i);
                        return;
                    }
                }
            } catch (Throwable ignored) {
            }
        }

        @JavascriptInterface
        public void shareText(String text) {
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType("text/plain");
            i.putExtra(Intent.EXTRA_TEXT, text);
            Intent c = Intent.createChooser(i, "Share karo");
            c.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(c);
        }

        @JavascriptInterface
        public String saveResult(String text) {
            try {
                File dir = getExternalFilesDir("Results");
                if (dir == null) dir = new File(getFilesDir(), "Results");
                dir.mkdirs();
                String name = "Result_" + new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new java.util.Date()) + ".txt";
                File f = new File(dir, name);
                FileOutputStream o = new FileOutputStream(f);
                o.write(text.getBytes("UTF-8"));
                o.close();
                return name + "|" + f.length();
            } catch (Throwable t) {
                return "";
            }
        }
    }
}
