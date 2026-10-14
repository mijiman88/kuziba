package zm.agent.tracker;

import android.Manifest;
import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import java.security.MessageDigest;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Paint;
import android.graphics.pdf.PdfDocument;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import org.json.JSONArray;
import org.json.JSONObject;

public class MainActivity extends Activity {
    WebView w;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        w = new WebView(this);
        setContentView(w);
        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        w.addJavascriptInterface(new Bridge(), "Android");
        w.loadUrl("file:///android_asset/index.html");
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] p, int[] r) {
        w.evaluateJavascript("window.onPerm && onPerm()", null);
    }

    boolean hasSms() {
        return checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED
            && checkSelfPermission(Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED;
    }

    String saveBytes(String name, String mime, byte[] data) throws Exception {
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
            Uri u = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            try (OutputStream o = getContentResolver().openOutputStream(u)) { o.write(data); }
            return "Saved to your Downloads folder: " + name;
        }
        File d = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        File f = new File(d, name);
        try (FileOutputStream o = new FileOutputStream(f)) { o.write(data); }
        return "Saved: " + f.getAbsolutePath();
    }

    class Bridge {
        @JavascriptInterface public boolean hasSmsPermission() { return hasSms(); }
        SharedPreferences kvp() { return getSharedPreferences("kv", MODE_PRIVATE); }
        String hash(String pin) {
            try {
                byte[] d = MessageDigest.getInstance("SHA-256").digest(("kuziba:" + pin).getBytes(StandardCharsets.UTF_8));
                StringBuilder sb = new StringBuilder();
                for (byte x : d) sb.append(String.format("%02x", x));
                return sb.toString();
            } catch (Exception e) { return ""; }
        }
        @JavascriptInterface public String getKV(String k) { return kvp().getString(k, ""); }
        @JavascriptInterface public void setKV(String k, String v) { kvp().edit().putString(k, v).commit(); }
        @JavascriptInterface public boolean hasPin() { return !kvp().getString("pin_hash", "").isEmpty(); }
        @JavascriptInterface public void setPin(String pin) { kvp().edit().putString("pin_hash", hash(pin)).commit(); }
        @JavascriptInterface public boolean checkPin(String pin) { return hash(pin).equals(kvp().getString("pin_hash", "")); }
        @JavascriptInterface public void clearPin() { kvp().edit().remove("pin_hash").commit(); }
        @JavascriptInterface public void openSettings() {
            Intent i = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()));
            runOnUiThread(() -> startActivity(i));
        }
        @JavascriptInterface public boolean termsAccepted() { return Store.termsOk(MainActivity.this); }
        @JavascriptInterface public void acceptTerms() { Store.acceptTerms(MainActivity.this); }
        @JavascriptInterface public void exitApp() { runOnUiThread(() -> finish()); }

        @JavascriptInterface public void requestSms() {
            runOnUiThread(() -> requestPermissions(new String[]{Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS}, 1));
        }

        @JavascriptInterface public String getData() {
            try {
                JSONObject o = new JSONObject();
                o.put("tx", Store.load(MainActivity.this, "tx"));
                o.put("orphans", Store.load(MainActivity.this, "orphans"));
                o.put("unread", Store.load(MainActivity.this, "unread"));
                return o.toString();
            } catch (Exception e) { return "{\"tx\":[],\"orphans\":[]}"; }
        }

        /** Reads old messages. Returns "added|scanned|notUnderstood" or an error text starting with "E:". */
        @JavascriptInterface public String scanSms() {
            if (!Store.termsOk(MainActivity.this)) return "E:terms";
            if (!hasSms()) return "E:no-permission";
            int added = 0, scanned = 0, bad = 0;
            try (Cursor c = getContentResolver().query(Uri.parse("content://sms"),
                    new String[]{"body", "date"}, "body LIKE ?", new String[]{"%ZMW%"}, "date ASC")) {
                if (c == null) return "E:cannot-open-inbox";
                while (c.moveToNext()) {
                    String body = c.getString(0);
                    if (body == null) continue;
                    scanned++;
                    if (Store.process(MainActivity.this, body, c.getLong(1))) added++;
                }
            } catch (Exception e) { return "E:" + e; }
            return added + "|" + scanned + "|" + Store.load(MainActivity.this, "unread").length();
        }

        @JavascriptInterface public void clearUnread() { Store.clearUnread(MainActivity.this); }

        @JavascriptInterface public void copyText(String t) {
            runOnUiThread(() -> ((android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE))
                .setPrimaryClip(android.content.ClipData.newPlainText("messages", t)));
        }

        @JavascriptInterface public String addManual(String json) {
            try { Store.addManual(MainActivity.this, new JSONObject(json)); return "ok"; }
            catch (Exception e) { return "error"; }
        }

        @JavascriptInterface public void remove(String id) { try { Store.remove(MainActivity.this, id); } catch (Exception ignored) { } }
        @JavascriptInterface public void dismiss(String id) { try { Store.dismiss(MainActivity.this, id); } catch (Exception ignored) { } }

        @JavascriptInterface public String saveCsv(String name, String text) {
            try { return saveBytes(name, "text/csv", ("\uFEFF" + text).getBytes(StandardCharsets.UTF_8)); }
            catch (Exception e) { return "Could not save: " + e.getMessage(); }
        }

        /** cols: [[header,width],...]  rows: [[cell,...],...]  foot: [line,...] */
        @JavascriptInterface public String savePdf(String name, String title, String colsJ, String rowsJ, String footJ) {
            try {
                JSONArray cols = new JSONArray(colsJ), rows = new JSONArray(rowsJ), foot = new JSONArray(footJ);
                PdfDocument doc = new PdfDocument();
                Paint p = new Paint(); p.setTextSize(8.5f);
                Paint hp = new Paint(p); hp.setFakeBoldText(true);
                int pageNo = 1, y = 0;
                PdfDocument.Page page = null;
                android.graphics.Canvas cv = null;
                for (int r = -1; r <= rows.length(); r++) {
                    if (page == null || y > 800) {
                        if (page != null) doc.finishPage(page);
                        page = doc.startPage(new PdfDocument.PageInfo.Builder(595, 842, pageNo++).create());
                        cv = page.getCanvas(); y = 45;
                        if (pageNo == 2) { Paint tp = new Paint(); tp.setTextSize(15f); tp.setFakeBoldText(true); cv.drawText(title, 40, y, tp); y += 24; }
                        float x = 40;
                        for (int k = 0; k < cols.length(); k++) { JSONArray c = cols.getJSONArray(k); cv.drawText(c.getString(0), x, y, hp); x += c.getInt(1); }
                        y += 14;
                        if (r == -1) continue;
                    }
                    if (r == -1) continue;
                    if (r == rows.length()) {
                        y += 10;
                        for (int k = 0; k < foot.length(); k++) { if (y > 810) break; cv.drawText(foot.getString(k), 40, y, hp); y += 14; }
                        break;
                    }
                    JSONArray row = rows.getJSONArray(r);
                    float x = 40;
                    for (int k = 0; k < cols.length(); k++) {
                        int wd = cols.getJSONArray(k).getInt(1);
                        String t = row.optString(k, "");
                        int n = p.breakText(t, true, wd - 4, null);
                        cv.drawText(t.substring(0, n), x, y, p);
                        x += wd;
                    }
                    y += 13;
                }
                if (page != null) doc.finishPage(page);
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                doc.writeTo(bo); doc.close();
                return saveBytes(name, "application/pdf", bo.toByteArray());
            } catch (Exception e) { return "Could not save: " + e.getMessage(); }
        }
    }
}
