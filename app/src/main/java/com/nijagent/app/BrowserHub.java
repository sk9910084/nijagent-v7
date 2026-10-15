package com.nijagent.app;

import android.app.DownloadManager;
import android.content.Context;
import android.content.MutableContextWrapper;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.GeolocationPermissions;
import android.webkit.PermissionRequest;
import android.webkit.SslErrorHandler;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import org.json.JSONObject;
import org.json.JSONTokener;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Agent ka apna browser. Ek hi WebView hai jo kabhi app ke andar (preview), kabhi floating window me
 * lagta hai. Agent isse JavaScript (page padhne ke liye) aur asli touch events (tap/swipe, taaki sites
 * "trusted click" maane) se chalata hai. Is WebView me NijAgent ka JS bridge NAHI hai.
 */
public class BrowserHub {

    public interface ChooserHandler {
        boolean onChooser(ValueCallback<Uri[]> cb, WebChromeClient.FileChooserParams params);
    }

    private static BrowserHub inst;

    public static synchronized BrowserHub get(Context c) {
        if (inst == null) inst = new BrowserHub(c.getApplicationContext());
        return inst;
    }

    private static final String DESKTOP_UA =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

    private final Context app;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private MutableContextWrapper ctxw;
    private WebView web;

    public volatile boolean shownInActivity = false;
    public volatile boolean loading = false;
    public volatile boolean desktop = false;
    public volatile boolean filePickerOpen = false;
    public volatile boolean canBack = false;
    public volatile boolean canFwd = false;
    public volatile String url = "";
    public volatile String title = "";
    public volatile int progress = 0;
    private volatile int loadCount = 0;
    private volatile boolean injecting = false;

    public volatile Runnable onUserTouch;
    public volatile ChooserHandler chooser;

    private BrowserHub(Context c) {
        app = c;
    }

    // ------------------------------------------------------------------ thread helpers

    private void onUi(final Runnable r) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            r.run();
            return;
        }
        final CountDownLatch l = new CountDownLatch(1);
        ui.post(new Runnable() {
            @Override
            public void run() {
                try {
                    r.run();
                } finally {
                    l.countDown();
                }
            }
        });
        try {
            l.await(8, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
        }
    }

    private void sleep(long ms) {
        SystemClock.sleep(ms);
    }

    // ------------------------------------------------------------------ create / attach

    public void ensure() {
        onUi(new Runnable() {
            @Override
            public void run() {
                if (web != null) return;
                ctxw = new MutableContextWrapper(app);
                web = new WebView(ctxw);
                WebSettings s = web.getSettings();
                s.setJavaScriptEnabled(true);
                s.setDomStorageEnabled(true);
                s.setAllowFileAccess(false);
                s.setAllowContentAccess(false);
                s.setJavaScriptCanOpenWindowsAutomatically(true);
                s.setSupportMultipleWindows(false);
                s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
                s.setBuiltInZoomControls(true);
                s.setDisplayZoomControls(false);
                s.setUseWideViewPort(true);
                s.setLoadWithOverviewMode(true);
                s.setMediaPlaybackRequiresUserGesture(true);
                CookieManager cm = CookieManager.getInstance();
                cm.setAcceptCookie(true);
                cm.setAcceptThirdPartyCookies(web, true);

                web.setWebViewClient(new WebViewClient() {
                    @Override
                    public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                        String u = r.getUrl().toString().toLowerCase();
                        return !(u.startsWith("http://") || u.startsWith("https://") || u.startsWith("about:blank"));
                    }

                    @Override
                    public void onPageStarted(WebView v, String u, Bitmap f) {
                        loading = true;
                        url = u;
                    }

                    @Override
                    public void onPageFinished(WebView v, String u) {
                        loading = false;
                        loadCount++;
                        url = u;
                        title = v.getTitle() == null ? "" : v.getTitle();
                        canBack = v.canGoBack();
                        canFwd = v.canGoForward();
                    }

                    @Override
                    public void doUpdateVisitedHistory(WebView v, String u, boolean isReload) {
                        url = u;
                        canBack = v.canGoBack();
                        canFwd = v.canGoForward();
                    }

                    @Override
                    public void onReceivedSslError(WebView v, SslErrorHandler h, SslError e) {
                        h.cancel();
                    }
                });

                web.setWebChromeClient(new WebChromeClient() {
                    @Override
                    public void onProgressChanged(WebView v, int p) {
                        progress = p;
                    }

                    @Override
                    public void onReceivedTitle(WebView v, String t) {
                        title = t == null ? "" : t;
                    }

                    @Override
                    public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams params) {
                        ChooserHandler h = chooser;
                        if (h == null) {
                            cb.onReceiveValue(null);
                            return true;
                        }
                        filePickerOpen = true;
                        return h.onChooser(cb, params);
                    }

                    @Override
                    public void onPermissionRequest(PermissionRequest r) {
                        r.deny();
                    }

                    @Override
                    public void onGeolocationPermissionsShowPrompt(String o, GeolocationPermissions.Callback cb) {
                        cb.invoke(o, false, false);
                    }
                });

                web.setDownloadListener(new DownloadListener() {
                    @Override
                    public void onDownloadStart(String u, String ua, String cd, String mime, long len) {
                        startDownload(u, ua, cd, mime);
                    }
                });

                web.setOnTouchListener(new View.OnTouchListener() {
                    @Override
                    public boolean onTouch(View v, MotionEvent e) {
                        if (!injecting && e.getAction() == MotionEvent.ACTION_DOWN) {
                            Runnable r = onUserTouch;
                            if (r != null) r.run();
                        }
                        return false;
                    }
                });
                web.loadUrl("about:blank");
            }
        });
    }

    private void startDownload(String u, String ua, String cd, String mime) {
        try {
            if (u.startsWith("blob:") || u.startsWith("data:")) {
                toast("Is tarah ka download (blob) nahi ho sakta. Site ka Download button se normal link wala try karo.");
                return;
            }
            String name = URLUtil.guessFileName(u, cd, mime);
            DownloadManager.Request req = new DownloadManager.Request(Uri.parse(u));
            req.addRequestHeader("User-Agent", ua);
            String ck = CookieManager.getInstance().getCookie(u);
            if (ck != null) req.addRequestHeader("Cookie", ck);
            if (mime != null && mime.length() > 0) req.setMimeType(mime);
            req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            try {
                req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name);
            } catch (Throwable t) {
                req.setDestinationInExternalFilesDir(app, Environment.DIRECTORY_DOWNLOADS, name);
            }
            DownloadManager dm = (DownloadManager) app.getSystemService(Context.DOWNLOAD_SERVICE);
            dm.enqueue(req);
            toast("Download shuru: " + name);
        } catch (Throwable t) {
            toast("Download nahi ho paya");
        }
    }

    private void toast(final String m) {
        ui.post(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(app, m, Toast.LENGTH_LONG).show();
            }
        });
    }

    public void attach(final ViewGroup parent, final Context host) {
        ensure();
        onUi(new Runnable() {
            @Override
            public void run() {
                if (web.getParent() != null) ((ViewGroup) web.getParent()).removeView(web);
                parent.addView(web, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                if (host != null) ctxw.setBaseContext(host);
            }
        });
    }

    public void detach() {
        onUi(new Runnable() {
            @Override
            public void run() {
                if (web == null) return;
                if (web.getParent() != null) ((ViewGroup) web.getParent()).removeView(web);
                ctxw.setBaseContext(app);
            }
        });
    }

    public boolean parentIs(ViewGroup g) {
        return web != null && web.getParent() == g;
    }

    public boolean isAttached() {
        return web != null && web.getParent() != null;
    }

    // ------------------------------------------------------------------ navigation

    public static String normalize(String in) {
        String t = in == null ? "" : in.trim();
        if (t.length() == 0) return "https://www.google.com";
        String low = t.toLowerCase();
        if (low.startsWith("http://") || low.startsWith("https://")) return t;
        if (t.indexOf(' ') < 0 && t.indexOf('.') > 0) return "https://www.google.com/search?q=" + Uri.encode(t);
        return "https://www.google.com/search?q=" + Uri.encode(t);
    }

    public void load(final String input) {
        ensure();
        final String u = normalize(input);
        onUi(new Runnable() {
            @Override
            public void run() {
                loading = true;
                url = u;
                web.loadUrl(u);
            }
        });
    }

    public void loadAndWait(String input, long ms) {
        load(input);
        sleep(400);
        waitLoaded(ms);
    }

    /** HTML string ko seedha browser me dikhao (code preview ke liye). */
    public void loadHtml(final String html) {
        ensure();
        onUi(new Runnable() {
            @Override
            public void run() {
                try {
                    web.loadDataWithBaseURL(null, html == null ? "" : html, "text/html", "UTF-8", null);
                    loading = false;
                    url = "preview://code";
                } catch (Throwable ignored) {
                }
            }
        });
    }

    public void goBack() {
        onUi(new Runnable() {
            @Override
            public void run() {
                if (web != null && web.canGoBack()) web.goBack();
            }
        });
    }

    public void goForward() {
        onUi(new Runnable() {
            @Override
            public void run() {
                if (web != null && web.canGoForward()) web.goForward();
            }
        });
    }

    public void reload() {
        onUi(new Runnable() {
            @Override
            public void run() {
                if (web != null) web.reload();
            }
        });
    }

    public void setDesktop(final boolean on) {
        ensure();
        desktop = on;
        onUi(new Runnable() {
            @Override
            public void run() {
                web.getSettings().setUserAgentString(on ? DESKTOP_UA : null);
                web.reload();
            }
        });
    }

    /** Page load hone tak ruko (max ms). */
    public void waitLoaded(long ms) {
        long end = SystemClock.uptimeMillis() + ms;
        int calm = 0;
        while (SystemClock.uptimeMillis() < end) {
            if (!loading) {
                calm++;
                if (calm >= 3) return;
            } else {
                calm = 0;
            }
            sleep(200);
        }
    }

    public String stateJson() {
        try {
            JSONObject o = new JSONObject();
            o.put("url", url);
            o.put("title", title);
            o.put("progress", progress);
            o.put("loading", loading);
            o.put("canBack", canBack);
            o.put("canFwd", canFwd);
            o.put("desktop", desktop);
            o.put("picker", filePickerOpen);
            return o.toString();
        } catch (Throwable t) {
            return "{}";
        }
    }

    // ------------------------------------------------------------------ JS driver

    private static final String SELQ = "a[href],button,input,textarea,select,summary,[role=button],[role=link],[role=tab],"
            + "[role=menuitem],[role=checkbox],[role=switch],[role=option],[onclick],[contenteditable=\"\"],"
            + "[contenteditable=\"true\"],[tabindex]:not([tabindex=\"-1\"])";

    private static final String JS_OBSERVE = "(function(){"
            + "function vis(e){var r=e.getBoundingClientRect(); if(r.width<4||r.height<4) return false;"
            + "var cs=getComputedStyle(e); if(cs.visibility==='hidden'||cs.display==='none'||parseFloat(cs.opacity)===0) return false;"
            + "return r.bottom>0&&r.right>0&&r.top<innerHeight&&r.left<innerWidth;}"
            + "var all=document.querySelectorAll(\"%Q%\"), out=[], arr=[], seen=new Set();"
            + "for(var i=0;i<all.length&&out.length<110;i++){"
            + "var e=all[i]; if(seen.has(e)||!vis(e)) continue; seen.add(e);"
            + "var tag=e.tagName.toLowerCase(), type=(e.type||'').toLowerCase(), k='btn';"
            + "if(tag==='a'||e.getAttribute('role')==='link') k='link';"
            + "if(tag==='textarea'||e.isContentEditable||(tag==='input'&&['text','search','email','url','tel','number','password',''].indexOf(type)>=0)) k='edit';"
            + "if(tag==='input'&&(type==='checkbox'||type==='radio')) k='check';"
            + "if(tag==='select') k='select';"
            + "if(tag==='input'&&type==='file') k='file';"
            + "if(tag==='input'&&type==='hidden') continue;"
            + "var t=(e.innerText||'').trim().replace(/\\s+/g,' ').slice(0,70);"
            + "var aria=(e.getAttribute('aria-label')||e.title||e.alt||'').trim().slice(0,70);"
            + "var ph=(e.placeholder||'').slice(0,50);"
            + "var val=(k==='edit'&&type!=='password')?String(e.value||e.innerText||'').slice(0,50):'';"
            + "var did=e.id||''; if(/\\d{5,}|:r\\w+:/.test(did)||did.length>40) did='';"
            + "var key=did||(e.name||'');"
            + "var meta=(e.name||'')+' '+(e.id||'')+' '+ph+' '+aria+' '+(e.autocomplete||'');"
            + "var pw=type==='password'||/pass(word|code)?|\\bpin\\b|\\botp\\b|\\bcvv\\b|\\bcvc\\b|card.?number|one-time-code/i.test(meta);"
            + "arr.push(e);"
            + "out.push({id:arr.length,k:k,t:t||val,a:aria,p:ph,v:val,d:key,pw:pw,c:!!e.checked,x:!!e.disabled});"
            + "}"
            + "window.__nij=arr;"
            + "var txt=(document.body?document.body.innerText:'').replace(/\\s+/g,' ').trim().slice(0,1500);"
            + "var sh=document.documentElement.scrollHeight||1;"
            + "return JSON.stringify({url:location.href,title:document.title,sy:Math.round(100*(scrollY+innerHeight)/sh),els:out,text:txt});"
            + "})()";

    private static final String JS_FIND = "(function(a){"
            + "var Q=\"%Q%\";"
            + "function vis(e){var r=e.getBoundingClientRect(); if(r.width<2||r.height<2) return false;"
            + "var cs=getComputedStyle(e); return cs.visibility!=='hidden'&&cs.display!=='none';}"
            + "function isEd(x){return x&&(x.tagName==='INPUT'||x.tagName==='TEXTAREA'||x.isContentEditable);}"
            + "function txt(e){return ((e.innerText||e.value||'')+'').trim().replace(/\\s+/g,' ').toLowerCase();}"
            + "function aria(e){return ((e.getAttribute('aria-label')||e.title||e.alt||'')+'').trim().toLowerCase();}"
            + "function lbl(e){return ((e.innerText||e.value||e.getAttribute('aria-label')||e.title||e.placeholder||'')+'').trim().replace(/\\s+/g,' ').slice(0,70);}"
            + "var el=null;"
            + "if(a.id>0&&window.__nij&&window.__nij[a.id-1]&&document.contains(window.__nij[a.id-1])) el=window.__nij[a.id-1];"
            + "if(!el&&a.sel){"
            + "var s=a.sel,tx=(s.text||'').toLowerCase(),ds=(s.desc||'').toLowerCase(),id=s.id||'',best=0,list=[];"
            + "var c=document.querySelectorAll(Q);"
            + "for(var i=0;i<c.length;i++){var e=c[i]; if(!vis(e)) continue; var sc=0,nt=txt(e),na=aria(e),np=(e.placeholder||'').toLowerCase();"
            + "if(id&&(e.id===id||e.name===id)) sc+=4;"
            + "if(tx){ if(nt===tx||na===tx||np===tx) sc+=3; else if(nt.indexOf(tx)>=0||na.indexOf(tx)>=0||np.indexOf(tx)>=0) sc+=1; }"
            + "if(ds){ if(na===ds||nt===ds) sc+=3; else if(na.indexOf(ds)>=0||nt.indexOf(ds)>=0) sc+=1; }"
            + "if(!sc) continue; if(sc>best){best=sc;list=[];} if(sc===best) list.push(e);}"
            + "if(!list.length&&tx){ var q=document.querySelectorAll('body *');"
            + "for(var j=0;j<q.length;j++){var f=q[j]; if(f.children.length===0&&vis(f)&&txt(f)===tx) list.push(f);} }"
            + "if(list.length){ var n=Math.max(1,s.n||1)-1; el=list[n<list.length?n:0]; }"
            + "}"
            + "if(!el&&a.edit){ el=document.activeElement;"
            + "if(!isEd(el)){ el=null; var cc=document.querySelectorAll('input,textarea,[contenteditable=\"\"],[contenteditable=\"true\"]');"
            + "for(var m=0;m<cc.length;m++){var t=(cc[m].type||'').toLowerCase();"
            + "if(t==='hidden'||t==='checkbox'||t==='radio'||t==='submit'||t==='button'||t==='file') continue;"
            + "if(vis(cc[m])){el=cc[m];break;}}}}"
            + "if(!el) return JSON.stringify({ok:false});"
            + "try{el.scrollIntoView({block:'center',inline:'center',behavior:'instant'});}catch(x){el.scrollIntoView();}"
            + "var r=el.getBoundingClientRect(); window.__nijLast=el;"
            + "var meta=(el.name||'')+' '+(el.id||'')+' '+(el.placeholder||'')+' '+(el.getAttribute('aria-label')||'')+' '+(el.autocomplete||'');"
            + "var pw=(el.type==='password')||/pass(word|code)?|\\bpin\\b|\\botp\\b|\\bcvv\\b|\\bcvc\\b|card.?number|one-time-code/i.test(meta);"
            + "return JSON.stringify({ok:true,cx:r.left+r.width/2,cy:r.top+r.height/2,iw:innerWidth,ih:innerHeight,label:lbl(el),pw:pw,tag:el.tagName.toLowerCase()});"
            + "})(%ARG%)";

    private static final String JS_SETTEXT = "(function(t){"
            + "function ed(x){return x&&(x.tagName==='INPUT'||x.tagName==='TEXTAREA'||x.isContentEditable);}"
            + "var el=document.activeElement; if(!ed(el)) el=window.__nijLast; if(!ed(el)) return 'no';"
            + "el.focus();"
            + "if(el.isContentEditable){ document.execCommand('selectAll',false,null);"
            + "var ok=document.execCommand('insertText',false,t);"
            + "if(!ok){el.textContent=t; el.dispatchEvent(new InputEvent('input',{bubbles:true,data:t,inputType:'insertText'}));}"
            + "return 'ok'; }"
            + "var proto=el.tagName==='TEXTAREA'?HTMLTextAreaElement.prototype:HTMLInputElement.prototype;"
            + "var setter=Object.getOwnPropertyDescriptor(proto,'value').set; setter.call(el,t);"
            + "el.dispatchEvent(new Event('input',{bubbles:true})); el.dispatchEvent(new Event('change',{bubbles:true}));"
            + "return 'ok';"
            + "})(%ARG%)";

    private static final String JS_ENTER = "(function(){"
            + "var el=document.activeElement||window.__nijLast; if(!el) return 'no';"
            + "['keydown','keypress','keyup'].forEach(function(n){el.dispatchEvent(new KeyboardEvent(n,{key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true}));});"
            + "var f=el.form; if(f){ if(f.requestSubmit) f.requestSubmit(); else f.submit(); }"
            + "return 'ok';"
            + "})()";

    /** JS chalao aur string result lao (blocking). */
    private String eval(final String js) {
        ensure();
        final String[] out = {null};
        final CountDownLatch l = new CountDownLatch(1);
        ui.post(new Runnable() {
            @Override
            public void run() {
                try {
                    web.evaluateJavascript(js, new ValueCallback<String>() {
                        @Override
                        public void onReceiveValue(String v) {
                            out[0] = v;
                            l.countDown();
                        }
                    });
                } catch (Throwable t) {
                    l.countDown();
                }
            }
        });
        try {
            l.await(8, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
        }
        String raw = out[0];
        if (raw == null || raw.equals("null")) return null;
        try {
            Object o = new JSONTokener(raw).nextValue();
            return o == null ? null : String.valueOf(o);
        } catch (Throwable t) {
            return raw;
        }
    }

    private JSONObject evalObj(String js) {
        try {
            String s = eval(js);
            return s == null ? null : new JSONObject(s);
        } catch (Throwable t) {
            return null;
        }
    }

    public JSONObject observe() {
        return evalObj(JS_OBSERVE.replace("%Q%", SELQ.replace("\"", "\\\"")));
    }

    /** Element dhundho (id = observe ki list ka number, ya sel, ya edit=true). Result me cx, cy, iw, label, pw. */
    public JSONObject find(int id, JSONObject sel, boolean edit) {
        try {
            JSONObject a = new JSONObject();
            a.put("id", id);
            if (sel != null) a.put("sel", sel);
            a.put("edit", edit);
            String js = JS_FIND.replace("%Q%", SELQ.replace("\"", "\\\"")).replace("%ARG%", a.toString());
            return evalObj(js);
        } catch (Throwable t) {
            return null;
        }
    }

    public boolean setText(String text) {
        String r = eval(JS_SETTEXT.replace("%ARG%", JSONObject.quote(text)));
        return "ok".equals(r);
    }

    // ------------------------------------------------------------------ real touch / keys

    public int width() {
        return web == null ? 0 : web.getWidth();
    }

    public int height() {
        return web == null ? 0 : web.getHeight();
    }

    /** CSS coordinates (find() ka cx, cy, iw) ko view pixels me badal kar tap karta hai. */
    public boolean tapCss(JSONObject f, long dur) {
        int w = width();
        double iw = f.optDouble("iw", 0);
        if (w <= 0 || iw <= 0) return false;
        double sc = w / iw;
        float x = (float) (f.optDouble("cx") * sc);
        float y = (float) (f.optDouble("cy") * sc);
        x = Math.max(2, Math.min(w - 2, x));
        y = Math.max(2, Math.min(height() - 2, y));
        tapPx(x, y, dur);
        return true;
    }

    public void tapFrac(double fx, double fy, long dur) {
        tapPx((float) (fx * width()), (float) (fy * height()), dur);
    }

    public void tapPx(final float x, final float y, long dur) {
        final long down = SystemClock.uptimeMillis();
        onUi(new Runnable() {
            @Override
            public void run() {
                injecting = true;
                web.requestFocus();
                MotionEvent e = MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x, y, 0);
                web.dispatchTouchEvent(e);
                e.recycle();
            }
        });
        sleep(Math.max(40, dur));
        onUi(new Runnable() {
            @Override
            public void run() {
                MotionEvent e = MotionEvent.obtain(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y, 0);
                web.dispatchTouchEvent(e);
                e.recycle();
                injecting = false;
            }
        });
    }

    public void swipeFrac(double fx1, double fy1, double fx2, double fy2) {
        final float w = width(), h = height();
        if (w <= 0 || h <= 0) return;
        final float x1 = (float) (fx1 * w), y1 = (float) (fy1 * h), x2 = (float) (fx2 * w), y2 = (float) (fy2 * h);
        final long down = SystemClock.uptimeMillis();
        onUi(new Runnable() {
            @Override
            public void run() {
                injecting = true;
                MotionEvent e = MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x1, y1, 0);
                web.dispatchTouchEvent(e);
                e.recycle();
            }
        });
        final int steps = 8;
        for (int i = 1; i <= steps; i++) {
            final float x = x1 + (x2 - x1) * i / steps, y = y1 + (y2 - y1) * i / steps;
            sleep(30);
            onUi(new Runnable() {
                @Override
                public void run() {
                    MotionEvent e = MotionEvent.obtain(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_MOVE, x, y, 0);
                    web.dispatchTouchEvent(e);
                    e.recycle();
                }
            });
        }
        sleep(40);
        onUi(new Runnable() {
            @Override
            public void run() {
                MotionEvent e = MotionEvent.obtain(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x2, y2, 0);
                web.dispatchTouchEvent(e);
                e.recycle();
                injecting = false;
            }
        });
    }

    public void pressEnter() {
        final int before = loadCount;
        onUi(new Runnable() {
            @Override
            public void run() {
                injecting = true;
                web.requestFocus();
                web.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER));
                web.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER));
                injecting = false;
            }
        });
        sleep(1200);
        if (!loading && loadCount == before) {
            eval(JS_ENTER);
        }
    }

    // ------------------------------------------------------------------ screenshot

    public Bitmap shot() {
        final Bitmap[] out = {null};
        onUi(new Runnable() {
            @Override
            public void run() {
                try {
                    if (web == null || web.getWidth() <= 0 || web.getHeight() <= 0) return;
                    Bitmap b = Bitmap.createBitmap(web.getWidth(), web.getHeight(), Bitmap.Config.ARGB_8888);
                    web.draw(new Canvas(b));
                    out[0] = b;
                } catch (Throwable ignored) {
                }
            }
        });
        return out[0];
    }
}
