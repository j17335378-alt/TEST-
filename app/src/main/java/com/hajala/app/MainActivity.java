package com.hajala.app;

import android.Manifest;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.util.Base64;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.webkit.WebViewAssetLoader;
import androidx.webkit.WebViewClientCompat;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.net.URLConnection;
import java.net.UnknownHostException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public class MainActivity extends Activity {
    private static final String TAG = "HajalaApp";
    private static final String APP_URL = "https://appassets.androidplatform.net/assets/index.html";
    private static final String APP_ORIGIN = "https://appassets.androidplatform.net/";
    private static final int REQ_FILE_CHOOSER = 2101;
    private static final int REQ_CREATE_DOCUMENT = 2102;
    private static final int REQ_WRITE_PERMISSION = 2103;

    private FrameLayout root;
    private WebView webView;
    private ValueCallback<Uri[]> fileChooserCallback;
    private View fullScreenView;
    private WebChromeClient.CustomViewCallback fullScreenCallback;
    private int previousSystemUiVisibility;
    private final String downloadToken = UUID.randomUUID().toString();
    private final NativeDownloads nativeDownloads = new NativeDownloads();
    private PendingNetworkDownload waitingForPermission;
    private File pendingExportFile;
    private String pendingExportName;
    private String pendingExportMime;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Window window = getWindow();
        window.setStatusBarColor(Color.rgb(16, 11, 23));
        window.setNavigationBarColor(Color.rgb(16, 11, 23));
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

        root = new FrameLayout(this);
        webView = new WebView(this);
        root.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setSupportMultipleWindows(false);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        settings.setLoadsImagesAutomatically(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);

        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(webView, true);
        WebView.setWebContentsDebuggingEnabled(true);
        webView.addJavascriptInterface(nativeDownloads, "HajalaNative");

        WebViewAssetLoader assetLoader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        webView.setWebViewClient(new WebViewClientCompat() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                WebResourceResponse local = handleEmbeddedApi(uri, request.getMethod());
                return local != null ? local : assetLoader.shouldInterceptRequest(uri);
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, String url) {
                Uri uri = Uri.parse(url);
                WebResourceResponse local = handleEmbeddedApi(uri, "GET");
                return local != null ? local : assetLoader.shouldInterceptRequest(uri);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return routeUrl(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return routeUrl(Uri.parse(url));
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                if (url != null && url.startsWith(APP_ORIGIN)) {
                    installLocalDownloadHandler();
                }
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view,
                                             ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (fileChooserCallback != null) {
                    fileChooserCallback.onReceiveValue(null);
                }
                fileChooserCallback = callback;
                try {
                    // Use WebView's requested chooser first so image/video accept types are preserved.
                    Intent intent = params.createIntent();
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivityForResult(intent, REQ_FILE_CHOOSER);
                    return true;
                } catch (ActivityNotFoundException | SecurityException ex) {
                    // Some Android vendor file pickers cannot handle the WebView intent.
                    // Fall back to the system document picker, including multi-select support.
                    try {
                        Intent fallback = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                        fallback.addCategory(Intent.CATEGORY_OPENABLE);
                        String[] types = params.getAcceptTypes();
                        String mime = "*/*";
                        if (types != null) {
                            for (String type : types) {
                                if (type != null && type.contains("/")) { mime = type; break; }
                            }
                        }
                        fallback.setType(mime);
                        fallback.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE);
                        fallback.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                        startActivityForResult(fallback, REQ_FILE_CHOOSER);
                        return true;
                    } catch (Exception fallbackError) {
                        if (fileChooserCallback != null) fileChooserCallback.onReceiveValue(null);
                        fileChooserCallback = null;
                        Toast.makeText(MainActivity.this, "تعذّر فتح منتقي الصور والفيديو. حدّث تطبيق الملفات وحاول مجددًا.", Toast.LENGTH_LONG).show();
                        return true;
                    }
                }
            }

            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                showFullScreen(view, callback);
            }

            @Override
            public void onHideCustomView() {
                hideFullScreen();
            }

            @Override
            public boolean onConsoleMessage(android.webkit.ConsoleMessage message) {
                Log.d(TAG, message.message() + " (" + message.sourceId() + ":" + message.lineNumber() + ")");
                return true;
            }
        });

        webView.setDownloadListener((url, userAgent, contentDisposition, mimeType, contentLength) -> {
            Uri uri = Uri.parse(url);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if ("http".equals(scheme) || "https".equals(scheme)) {
                startNetworkDownload(url, userAgent, contentDisposition, mimeType);
            } else if ("blob".equals(scheme) || "data".equals(scheme)) {
                Toast.makeText(this, "يتم تجهيز ملف التصدير، اختر مكان الحفظ عند ظهور النافذة", Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(this, "نوع التنزيل هذا غير مدعوم مباشرة", Toast.LENGTH_LONG).show();
            }
        });

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState);
        }
        if (webView.getUrl() == null) {
            webView.loadUrl(APP_URL);
        }
    }

    /**
     * App-local API exposed only under the bundled WebView origin. It is served
     * by the Android process itself, so no separate localhost daemon is required.
     */
    @Nullable
    private WebResourceResponse handleEmbeddedApi(@Nullable Uri uri, @Nullable String method) {
        if (uri == null || !"appassets.androidplatform.net".equalsIgnoreCase(uri.getHost())) return null;
        String path = uri.getPath();
        if (path == null || !path.startsWith("/api/")) return null;
        String verb = method == null ? "GET" : method.toUpperCase(Locale.ROOT);
        if ("OPTIONS".equals(verb)) {
            return apiResponse(204, "No Content", "application/json", new byte[0], null);
        }
        if ("GET".equals(verb) && "/api/health".equals(path)) {
            return apiJson(200, "{\"ok\":true,\"service\":\"hajala-embedded\",\"network\":\"available\"}", null);
        }
        if (!"GET".equals(verb)) return apiJson(405, "{\"error\":\"Method not allowed\"}", null);
        if (!"/api/fetch".equals(path)) return apiJson(404, "{\"error\":\"Unknown local endpoint\"}", null);

        String target = uri.getQueryParameter("url");
        if (target == null || target.trim().isEmpty()) return apiJson(400, "{\"error\":\"Missing url parameter\"}", null);
        HttpURLConnection connection = null;
        try {
            URL current = validateRemoteHttps(target.trim());
            int redirects = 0;
            int status;
            while (true) {
                connection = (HttpURLConnection) current.openConnection();
                connection.setInstanceFollowRedirects(false);
                connection.setConnectTimeout(10000);
                connection.setReadTimeout(25000);
                connection.setRequestMethod("GET");
                connection.setUseCaches(false);
                connection.setRequestProperty("Accept-Encoding", "identity");
                connection.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/json,image/avif,image/webp,image/apng,image/*,*/*;q=0.8");
                connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36 HajalaApp/1.0");
                connection.setRequestProperty("Cache-Control", "no-cache");
                status = connection.getResponseCode();
                if (status >= 300 && status < 400) {
                    String location = connection.getHeaderField("Location");
                    connection.disconnect(); connection = null;
                    if (location == null || redirects++ >= 5) throw new ApiFailure(502, "Too many redirects or missing Location");
                    current = validateRemoteHttps(new URL(current, location).toString());
                    continue;
                }
                break;
            }
            if (status < 200 || status >= 300) {
                connection.disconnect(); connection = null;
                return apiJson(502, "{\"error\":\"Remote server returned HTTP " + status + "\"}", null);
            }

            String contentTypeHeader = connection.getContentType();
            String contentType = contentTypeHeader == null ? "application/octet-stream" : contentTypeHeader.split(";", 2)[0].trim();
            String encoding = null;
            if (contentTypeHeader != null) {
                for (String part : contentTypeHeader.split(";")) {
                    String lower = part.trim().toLowerCase(Locale.ROOT);
                    if (lower.startsWith("charset=")) encoding = part.substring(part.indexOf('=') + 1).trim().replace("\"", "");
                }
            }
            if (encoding == null && (contentType.startsWith("text/") || contentType.contains("json") || contentType.contains("xml") || contentType.contains("javascript"))) encoding = "UTF-8";
            long declaredLength = connection.getContentLengthLong();
            long maxBytes = (contentType.startsWith("text/") || contentType.contains("json") || contentType.contains("xml") || contentType.contains("javascript"))
                    ? 8L * 1024L * 1024L : 64L * 1024L * 1024L;
            if (declaredLength > maxBytes) {
                connection.disconnect(); connection = null;
                return apiJson(413, "{\"error\":\"Remote file exceeds the safe size limit\"}", null);
            }
            InputStream body = connection.getInputStream();
            Map<String, String> headers = apiHeaders();
            headers.put("X-Hajala-Final-URL", current.toString());
            headers.put("X-Content-Type-Options", "nosniff");
            headers.put("Content-Disposition", "inline");
            if (declaredLength >= 0 && declaredLength <= maxBytes) headers.put("Content-Length", String.valueOf(declaredLength));
            else headers.remove("Content-Length");
            HttpURLConnection active = connection;
            InputStream bounded = new BoundedDisconnectingInputStream(body, active, maxBytes);
            connection = null; // The bounded stream owns the open connection from here on.
            return new WebResourceResponse(contentType, encoding, 200, "OK", headers, bounded);
        } catch (ApiFailure ex) {
            if (connection != null) connection.disconnect();
            return apiJson(ex.status, "{\"error\":\"" + jsonEscape(ex.getMessage()) + "\"}", null);
        } catch (Exception ex) {
            if (connection != null) connection.disconnect();
            Log.w(TAG, "Embedded fetch failed", ex);
            return apiJson(502, "{\"error\":\"Fetch failed; check the source URL or connection\"}", null);
        }
    }

    private WebResourceResponse apiJson(int status, String json, @Nullable String finalUrl) {
        Map<String, String> headers = apiHeaders();
        if (finalUrl != null) headers.put("X-Hajala-Final-URL", finalUrl);
        return apiResponse(status, status == 200 ? "OK" : status == 204 ? "No Content" : status == 400 ? "Bad Request" : status == 403 ? "Forbidden" : status == 404 ? "Not Found" : status == 405 ? "Method Not Allowed" : status == 413 ? "Content Too Large" : "Bad Gateway", "application/json", json.getBytes(java.nio.charset.StandardCharsets.UTF_8), headers);
    }

    private WebResourceResponse apiResponse(int status, String reason, String mime, byte[] data, @Nullable Map<String, String> sourceHeaders) {
        Map<String, String> headers = sourceHeaders == null ? apiHeaders() : sourceHeaders;
        return new WebResourceResponse(mime, "UTF-8", status, reason, headers, new ByteArrayInputStream(data));
    }

    private Map<String, String> apiHeaders() {
        Map<String, String> headers = new HashMap<>();
        headers.put("Access-Control-Allow-Origin", "*");
        headers.put("Access-Control-Allow-Methods", "GET, OPTIONS");
        headers.put("Access-Control-Allow-Headers", "Content-Type");
        headers.put("Access-Control-Expose-Headers", "X-Hajala-Final-URL, Content-Type, Content-Length");
        headers.put("Cache-Control", "no-store");
        headers.put("X-Content-Type-Options", "nosniff");
        return headers;
    }

    private URL validateRemoteHttps(String input) throws Exception {
        URL url = new URL(input);
        if (!"https".equalsIgnoreCase(url.getProtocol())) throw new ApiFailure(400, "Only HTTPS remote sources are supported");
        if (url.getUserInfo() != null || url.getHost() == null || url.getHost().isEmpty()) throw new ApiFailure(400, "Invalid remote URL");
        String host = url.getHost().toLowerCase(Locale.ROOT);
        if ("localhost".equals(host) || host.endsWith(".localhost") || host.endsWith(".local") || host.endsWith(".internal") || host.endsWith(".test") || host.endsWith(".invalid")) {
            throw new ApiFailure(403, "Local or reserved host blocked");
        }
        // Require a DNS hostname rather than a literal IP to reduce SSRF risk.
        if (host.indexOf(':') >= 0 || host.matches("[0-9.]+")) throw new ApiFailure(403, "IP-literal sources are blocked");
        InetAddress[] addresses = InetAddress.getAllByName(host);
        if (addresses.length == 0) throw new ApiFailure(502, "Source host did not resolve");
        for (InetAddress address : addresses) {
            if (!isPublicAddress(address)) throw new ApiFailure(403, "Private or reserved destination blocked");
        }
        return url;
    }

    private boolean isPublicAddress(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) return false;
        byte[] b = address.getAddress();
        if (b.length == 4) {
            int a = b[0] & 255, c = b[1] & 255, d = b[2] & 255;
            if (a == 0 || a == 10 || a == 127 || a >= 224) return false;
            if (a == 172 && c >= 16 && c <= 31) return false;
            if (a == 192 && c == 168) return false;
            if (a == 169 && c == 254) return false;
            if (a == 100 && c >= 64 && c <= 127) return false;
            if (a == 192 && (c == 0 || (c == 0 && d == 2))) return false;
            if (a == 198 && (c == 18 || c == 19 || (c == 51 && d == 100))) return false;
            if (a == 203 && c == 0 && d == 113) return false;
            if (a == 255) return false;
            return true;
        }
        if (b.length == 16) {
            int first = b[0] & 255, second = b[1] & 255;
            // Accept global unicast only; reject unique-local, link-local and documentation ranges.
            if ((first & 0xE0) != 0x20) return false;
            if (first == 0x20 && second == 0x01 && (b[2] & 255) == 0x0D && (b[3] & 255) == 0xB8) return false;
            return true;
        }
        return false;
    }

    private String jsonEscape(String text) {
        if (text == null) return "";
        return text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\r", " ").replace("\n", " ");
    }

    private static final class ApiFailure extends Exception {
        final int status;
        ApiFailure(int status, String message) { super(message); this.status = status; }
    }

    private static final class BoundedDisconnectingInputStream extends FilterInputStream {
        private final HttpURLConnection connection;
        private long remaining;
        private boolean checkedEnd;
        BoundedDisconnectingInputStream(InputStream input, HttpURLConnection connection, long maxBytes) {
            super(input); this.connection = connection; this.remaining = maxBytes;
        }
        @Override public int read() throws IOException {
            if (remaining <= 0) {
                if (checkedEnd) return -1;
                checkedEnd = true;
                int extra = in.read();
                if (extra < 0) return -1;
                throw new IOException("Remote response exceeded size limit");
            }
            int result = in.read(); if (result >= 0) remaining--; return result;
        }
        @Override public int read(byte[] buffer, int offset, int length) throws IOException {
            if (length == 0) return 0;
            if (remaining <= 0) return read();
            int allowed = (int) Math.min((long) length, remaining);
            int count = in.read(buffer, offset, allowed);
            if (count > 0) remaining -= count;
            return count;
        }
        @Override public void close() throws IOException {
            try { super.close(); } finally { connection.disconnect(); }
        }
    }

    private boolean routeUrl(Uri uri) {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if ("https".equals(scheme) || "http".equals(scheme) || "about".equals(scheme) || "blob".equals(scheme)) {
            return false;
        }
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, uri);
            startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException ex) {
            Toast.makeText(this, "لا يوجد تطبيق لفتح هذا الرابط", Toast.LENGTH_SHORT).show();
        }
        return true;
    }

    private void installLocalDownloadHandler() {
        String js = "(function(){try{if(window.__hjNativeSaveInstalled)return;window.__hjNativeSaveInstalled=true;"
                + "var token='" + downloadToken + "';var busy=false;"
                + "if(typeof window.go==='function'&&!window.__hjGoWrapped){var originalGo=window.go;var routeStack=[];window.__hjGoWrapped=true;"
                + "window.go=function(p,x){try{var before=(typeof V!=='undefined')?JSON.stringify(V):'';var next=Object.assign({p:p},x||{});"
                + "if(before&&JSON.stringify(next)!==before){routeStack.push(JSON.parse(before));if(routeStack.length>40)routeStack.shift();}}catch(_){}"
                + "return originalGo.apply(this,arguments);};window.__hajalaNativeBack=function(){if(!routeStack.length)return false;"
                + "var target=routeStack.pop();originalGo.call(window,target.p,target);return true;};}"
                + "document.addEventListener('click',function(ev){var a=ev.target&&ev.target.closest?ev.target.closest('a[download]'):null;"
                + "if(!a||!window.HajalaNative)return;var href=a.href||a.getAttribute('href')||'';"
                + "if(!(href.indexOf('blob:')===0||href.indexOf('data:')===0))return;"
                + "ev.preventDefault();ev.stopPropagation();if(busy)return;busy=true;"
                + "var name=a.getAttribute('download')||'hajala-export.bin';"
                + "fetch(href).then(function(r){return r.blob()}).then(async function(blob){"
                + "if(!HajalaNative.beginDownload(token,name,blob.type||'application/octet-stream'))throw new Error('begin failed');"
                + "var reader=blob.stream().getReader();while(true){var part=await reader.read();if(part.done)break;"
                + "var bytes=part.value;for(var i=0;i<bytes.length;i+=49152){var slice=bytes.subarray(i,Math.min(i+49152,bytes.length));"
                + "var bin='';for(var j=0;j<slice.length;j++)bin+=String.fromCharCode(slice[j]);"
                + "if(!HajalaNative.writeChunk(token,btoa(bin)))throw new Error('write failed');}}"
                + "HajalaNative.finishDownload(token);}).catch(function(err){try{HajalaNative.cancelDownload(token)}catch(_){}"
                + "console.error('Hajala export failed',err);alert('تعذّر تجهيز الملف للتنزيل. جرّب مرة ثانية.');})"
                + ".finally(function(){busy=false;});},true);}catch(e){console.error('Native download handler',e);}})();";
        webView.evaluateJavascript(js, null);
    }

    private void startNetworkDownload(String url, String userAgent, String disposition, String mimeType) {
        PendingNetworkDownload item = new PendingNetworkDownload(url, userAgent, disposition, mimeType);
        if (Build.VERSION.SDK_INT <= 28 && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            waitingForPermission = item;
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_WRITE_PERMISSION);
            return;
        }
        enqueueNetworkDownload(item);
    }

    private void enqueueNetworkDownload(PendingNetworkDownload item) {
        try {
            DownloadManager manager = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(item.url));
            request.setTitle(guessName(item.url, item.disposition));
            request.setDescription("حجالة: تنزيل الملف");
            request.setMimeType(item.mimeType == null || item.mimeType.isEmpty() ? "application/octet-stream" : item.mimeType);
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, guessName(item.url, item.disposition));
            if (item.userAgent != null && !item.userAgent.isEmpty()) request.addRequestHeader("User-Agent", item.userAgent);
            String cookie = CookieManager.getInstance().getCookie(item.url);
            if (cookie != null && !cookie.isEmpty()) request.addRequestHeader("Cookie", cookie);
            manager.enqueue(request);
            Toast.makeText(this, "بدأ التنزيل. ستجد الملف في مجلد Downloads", Toast.LENGTH_LONG).show();
        } catch (Exception ex) {
            Log.e(TAG, "Download failed", ex);
            Toast.makeText(this, "تعذّر بدء التنزيل", Toast.LENGTH_LONG).show();
        }
    }

    private String guessName(String url, String disposition) {
        String name = null;
        if (disposition != null) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("filename\\*=UTF-8''([^;]+)|filename=\\\"?([^;\\\"]+)\\\"?", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(disposition);
            if (m.find()) name = m.group(1) != null ? Uri.decode(m.group(1)) : m.group(2);
        }
        if (name == null || name.trim().isEmpty()) {
            String path = Uri.parse(url).getLastPathSegment();
            name = path == null || path.trim().isEmpty() ? "hajala-download" : Uri.decode(path);
        }
        name = name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        if (name.length() > 120) name = name.substring(name.length() - 120);
        return name.isEmpty() ? "hajala-download" : name;
    }

    private void showFullScreen(View view, WebChromeClient.CustomViewCallback callback) {
        if (fullScreenView != null) {
            callback.onCustomViewHidden();
            return;
        }
        fullScreenView = view;
        fullScreenCallback = callback;
        previousSystemUiVisibility = getWindow().getDecorView().getSystemUiVisibility();
        root.removeView(webView);
        root.addView(view, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
    }

    private void hideFullScreen() {
        if (fullScreenView == null) return;
        root.removeView(fullScreenView);
        fullScreenView = null;
        root.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        getWindow().getDecorView().setSystemUiVisibility(previousSystemUiVisibility);
        if (fullScreenCallback != null) {
            fullScreenCallback.onCustomViewHidden();
            fullScreenCallback = null;
        }
    }

    @Override
    public void onBackPressed() {
        if (fullScreenView != null) {
            hideFullScreen();
            return;
        }
        if (webView == null) {
            super.onBackPressed();
            return;
        }
        // The original site swaps views without adding browser-history entries.
        // Track calls to its existing go(...) function so Android Back returns to the prior view.
        webView.evaluateJavascript("(function(){try{return !!(window.__hajalaNativeBack&&window.__hajalaNativeBack())}catch(e){return false}})()", result -> {
            if (!"true".equals(result)) continueBackNavigation();
        });
    }

    private void continueBackNavigation() {
        if (isFinishing()) return;
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        if (webView != null) webView.saveState(outState);
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_FILE_CHOOSER) {
            if (fileChooserCallback != null) {
                Uri[] result = (resultCode == RESULT_OK && data != null)
                        ? WebChromeClient.FileChooserParams.parseResult(resultCode, data) : null;
                fileChooserCallback.onReceiveValue(result);
                fileChooserCallback = null;
            }
            return;
        }
        if (requestCode == REQ_CREATE_DOCUMENT) {
            File source = pendingExportFile;
            pendingExportFile = null;
            if (resultCode == RESULT_OK && data != null && data.getData() != null && source != null) {
                try (InputStream in = new BufferedInputStream(new FileInputStream(source));
                     OutputStream out = getContentResolver().openOutputStream(data.getData(), "w")) {
                    if (out == null) throw new IOException("Unable to open destination");
                    byte[] buffer = new byte[64 * 1024];
                    int count;
                    while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
                    out.flush();
                    Toast.makeText(this, "تم حفظ الملف", Toast.LENGTH_SHORT).show();
                } catch (Exception ex) {
                    Log.e(TAG, "Could not save export", ex);
                    Toast.makeText(this, "تعذّر حفظ الملف", Toast.LENGTH_LONG).show();
                }
            } else {
                Toast.makeText(this, "أُلغِي حفظ الملف", Toast.LENGTH_SHORT).show();
            }
            if (source != null) source.delete();
            pendingExportName = null;
            pendingExportMime = null;
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_WRITE_PERMISSION && waitingForPermission != null) {
            PendingNetworkDownload item = waitingForPermission;
            waitingForPermission = null;
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                enqueueNetworkDownload(item);
            } else {
                Toast.makeText(this, "يلزم السماح بالوصول للتخزين لتنزيل الملفات على هذا الإصدار من أندرويد", Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    protected void onDestroy() {
        if (fileChooserCallback != null) {
            fileChooserCallback.onReceiveValue(null);
            fileChooserCallback = null;
        }
        nativeDownloads.closeAndDelete();
        if (pendingExportFile != null) pendingExportFile.delete();
        if (webView != null) {
            webView.removeJavascriptInterface("HajalaNative");
            webView.stopLoading();
            webView.setWebChromeClient(null);
            webView.setWebViewClient(new WebViewClient());
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }

    public final class NativeDownloads {
        private File temp;
        private OutputStream stream;
        private String fileName;
        private String mime;

        @JavascriptInterface
        public synchronized boolean beginDownload(String token, String name, String type) {
            if (!downloadToken.equals(token)) return false;
            try {
                closeAndDeleteLocked();
                fileName = sanitizeName(name);
                mime = (type == null || type.trim().isEmpty()) ? "application/octet-stream" : type;
                temp = File.createTempFile("hajala-export-", ".tmp", getCacheDir());
                stream = new BufferedOutputStream(new FileOutputStream(temp));
                return true;
            } catch (IOException ex) {
                Log.e(TAG, "Cannot create export temp file", ex);
                closeAndDeleteLocked();
                return false;
            }
        }

        @JavascriptInterface
        public synchronized boolean writeChunk(String token, String encoded) {
            if (!downloadToken.equals(token) || stream == null || encoded == null) return false;
            try {
                byte[] bytes = Base64.decode(encoded, Base64.DEFAULT);
                stream.write(bytes);
                return true;
            } catch (Exception ex) {
                Log.e(TAG, "Cannot write export chunk", ex);
                return false;
            }
        }

        @JavascriptInterface
        public void finishDownload(String token) {
            final File file;
            final String name;
            final String type;
            synchronized (this) {
                if (!downloadToken.equals(token) || stream == null || temp == null) return;
                try {
                    stream.flush();
                    stream.close();
                } catch (IOException ex) {
                    Log.e(TAG, "Cannot finish export", ex);
                    closeAndDeleteLocked();
                    return;
                }
                stream = null;
                file = temp;
                name = fileName;
                type = mime;
                temp = null;
                fileName = null;
                mime = null;
            }
            runOnUiThread(() -> {
                pendingExportFile = file;
                pendingExportName = name;
                pendingExportMime = type;
                Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType(type == null || type.isEmpty() ? "application/octet-stream" : type);
                intent.putExtra(Intent.EXTRA_TITLE, name == null ? "hajala-export.bin" : name);
                try {
                    startActivityForResult(intent, REQ_CREATE_DOCUMENT);
                } catch (ActivityNotFoundException ex) {
                    Toast.makeText(MainActivity.this, "لا يوجد مدير ملفات يدعم حفظ الملف", Toast.LENGTH_LONG).show();
                    file.delete();
                    pendingExportFile = null;
                }
            });
        }

        @JavascriptInterface
        public synchronized void cancelDownload(String token) {
            if (downloadToken.equals(token)) closeAndDeleteLocked();
        }

        synchronized void closeAndDelete() {
            closeAndDeleteLocked();
        }

        private void closeAndDeleteLocked() {
            if (stream != null) {
                try { stream.close(); } catch (IOException ignored) { }
                stream = null;
            }
            if (temp != null) {
                // If finishDownload has moved the file into the document picker, temp is already null.
                temp.delete();
                temp = null;
            }
            fileName = null;
            mime = null;
        }

        private String sanitizeName(String name) {
            if (name == null || name.trim().isEmpty()) return "hajala-export.bin";
            String safe = name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
            if (safe.length() > 120) safe = safe.substring(safe.length() - 120);
            return safe.isEmpty() ? "hajala-export.bin" : safe;
        }
    }

    private static final class PendingNetworkDownload {
        final String url;
        final String userAgent;
        final String disposition;
        final String mimeType;
        PendingNetworkDownload(String url, String userAgent, String disposition, String mimeType) {
            this.url = url;
            this.userAgent = userAgent;
            this.disposition = disposition;
            this.mimeType = mimeType;
        }
    }
}
