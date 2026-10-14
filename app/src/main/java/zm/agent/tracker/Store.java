package zm.agent.tracker;

import android.content.Context;
import android.content.SharedPreferences;
import java.text.SimpleDateFormat;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

/** Reads Airtel / MTN agent SMS and keeps the transactions on the phone. */
public class Store {
    static final int F = Pattern.CASE_INSENSITIVE | Pattern.DOTALL;
    static final String N = "([\\d,.]+)";
    static final Pattern AIR_DEP = Pattern.compile("You have sent ZMW\\s*" + N + "\\s+to\\s+(\\d+)\\s+(.+?)\\.\\s*Bal ZMW\\s*" + N + "\\.?\\s*Comm? ZMW\\s*" + N + "\\s*TID:\\s*([A-Za-z0-9.]+)", F);
    static final Pattern AIR_WD = Pattern.compile("ZMW\\s*" + N + "\\s+received from\\s+(\\d+)\\s+(.+?)\\.\\s*Bal ZMW\\s*" + N + "\\.?\\s*Comm? ZMW\\s*" + N + "\\s*TID:\\s*([A-Za-z0-9.]+)", F);
    static final String DT = "(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2})";
    static final Pattern MTN_DEP = Pattern.compile("cashed in\\s*" + N + "\\s*ZMW to\\s+(.+?)\\s*\\((\\d+)\\)\\s*at\\s*" + DT + ".*?new balance:?\\s*" + N + "\\s*ZMW.*?Financial Transaction ID:?\\s*(\\d+)", F);
    static final Pattern MTN_WD = Pattern.compile("Cash out initiated on\\s*" + DT + "\\s*is completed.*?payout the amount:?\\s*" + N + "\\s*ZMW in cash to\\s+(.+?),\\s*id:?\\s*(\\d+).*?new balance is:?\\s*" + N + "\\s*ZMW.*?Financial Transaction Id:?\\s*(\\d+)", F);
    static final Pattern MTN_COM = Pattern.compile("You have earned\\s*" + N + "\\s*ZMW commission on the last transaction.*?total commission is ZMW\\s*" + N, F);

    static SharedPreferences sp(Context c) { return c.getSharedPreferences("agent_tracker", Context.MODE_PRIVATE); }

    static synchronized JSONArray load(Context c, String k) {
        try { return new JSONArray(sp(c).getString(k, "[]")); } catch (Exception e) { return new JSONArray(); }
    }

    static synchronized void save(Context c, String k, JSONArray a) {
        sp(c).edit().putString(k, a.toString()).commit();
    }

    static double num(String s) {
        s = s.replace(",", "");
        while (s.endsWith(".")) s = s.substring(0, s.length() - 1);
        return Double.parseDouble(s);
    }

    static String phone(String p) {
        if (p.startsWith("260") && p.length() == 12) return "0" + p.substring(3);
        if (p.length() == 9) return "0" + p;
        return p;
    }

    static String tid(String t) { return t.replaceAll("\\.+$", ""); }

    static long parseDt(String s, long fallback) {
        try { return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).parse(s).getTime(); }
        catch (Exception e) { return fallback; }
    }

    /** Returns true if something new was saved. */
    static boolean termsOk(Context c) { return sp(c).getBoolean("terms_v1", false); }

    static void acceptTerms(Context c) { sp(c).edit().putBoolean("terms_v1", true).putLong("terms_ts", System.currentTimeMillis()).commit(); }

    static synchronized boolean process(Context c, String body, long smsTs) {
        if (!termsOk(c)) return false;
        try {
            Matcher m;
            if ((m = AIR_DEP.matcher(body)).find())
                return add(c, rec("Airtel Money", "deposit", tid(m.group(6)), num(m.group(1)), num(m.group(5)), num(m.group(4)), m.group(3).trim(), phone(m.group(2)), smsTs));
            if ((m = AIR_WD.matcher(body)).find())
                return add(c, rec("Airtel Money", "withdrawal", tid(m.group(6)), num(m.group(1)), num(m.group(5)), num(m.group(4)), m.group(3).trim(), phone(m.group(2)), smsTs));
            if ((m = MTN_DEP.matcher(body)).find())
                return add(c, rec("MTN Money", "deposit", m.group(6), num(m.group(1)), 0, num(m.group(5)), m.group(2).trim(), phone(m.group(3)), parseDt(m.group(4), smsTs)));
            if ((m = MTN_WD.matcher(body)).find())
                return add(c, rec("MTN Money", "withdrawal", m.group(6), num(m.group(2)), 0, num(m.group(5)), m.group(3).trim(), phone(m.group(4)), parseDt(m.group(1), smsTs)));
            if ((m = MTN_COM.matcher(body)).find())
                return commission(c, num(m.group(1)), num(m.group(2)), smsTs);
        } catch (Exception ignored) { }
        noteUnread(c, body, smsTs);
        return false;
    }

    /** Keep messages that look like money messages but were not understood, so they can be fixed. */
    static void noteUnread(Context c, String body, long ts) {
        try {
            String b = body.toLowerCase(Locale.ROOT);
            if (!b.contains("zmw")) return;
            if (!(b.contains("tid") || b.contains("transaction") || b.contains("cash") || b.contains("commission")
                || b.contains("received from") || b.contains("you have sent") || b.contains("payout") || b.contains("deposit")
                || b.contains("withdraw"))) return;
            JSONArray a = load(c, "unread");
            String key = body.trim();
            for (int i = 0; i < a.length(); i++) if (a.getJSONObject(i).getString("body").equals(key)) return;
            JSONObject o = new JSONObject();
            o.put("body", key.length() > 400 ? key.substring(0, 400) : key); o.put("ts", ts);
            a.put(o);
            if (a.length() > 60) { JSONArray t = new JSONArray(); for (int i = a.length() - 60; i < a.length(); i++) t.put(a.get(i)); a = t; }
            save(c, "unread", a);
        } catch (Exception ignored) { }
    }

    static synchronized void clearUnread(Context c) { save(c, "unread", new JSONArray()); }

    static JSONObject rec(String prov, String type, String ref, double amt, double com, double bal, String name, String ph, long ts) throws Exception {
        JSONObject o = new JSONObject();
        o.put("id", prov + ":" + ref);
        o.put("provider", prov); o.put("type", type); o.put("ref", ref);
        o.put("amount", amt); o.put("com", com); o.put("bal", bal);
        o.put("name", name); o.put("phone", ph); o.put("ts", ts);
        o.put("src", "sms"); o.put("note", "");
        return o;
    }

    static boolean add(Context c, JSONObject o) throws Exception {
        JSONArray a = load(c, "tx");
        for (int i = 0; i < a.length(); i++)
            if (a.getJSONObject(i).getString("id").equals(o.getString("id"))) return false;
        a.put(o);
        save(c, "tx", a);
        return true;
    }

    /** MTN sends commission in its own SMS; attach it to the latest MTN transaction. */
    static boolean commission(Context c, double amt, double total, long ts) throws Exception {
        if (amt <= 0) return false;
        String key = "C|" + amt + "|" + total;
        JSONArray seen = load(c, "seen");
        for (int i = 0; i < seen.length(); i++) if (seen.getString(i).equals(key)) return false;
        seen.put(key);
        if (seen.length() > 500) { JSONArray t = new JSONArray(); for (int i = 250; i < seen.length(); i++) t.put(seen.get(i)); seen = t; }
        save(c, "seen", seen);
        JSONArray a = load(c, "tx");
        for (int i = a.length() - 1; i >= 0; i--) {
            JSONObject o = a.getJSONObject(i);
            if (!o.getString("provider").equals("MTN Money")) continue;
            if (o.getDouble("com") == 0) { o.put("com", amt); save(c, "tx", a); return true; }
            break;
        }
        JSONArray or = load(c, "orphans");
        JSONObject x = new JSONObject();
        x.put("id", UUID.randomUUID().toString()); x.put("amount", amt); x.put("ts", ts);
        or.put(x);
        save(c, "orphans", or);
        return true;
    }

    static synchronized void remove(Context c, String id) throws Exception {
        JSONArray a = load(c, "tx"), b = new JSONArray();
        for (int i = 0; i < a.length(); i++) if (!a.getJSONObject(i).getString("id").equals(id)) b.put(a.get(i));
        save(c, "tx", b);
    }

    static synchronized void dismiss(Context c, String id) throws Exception {
        JSONArray a = load(c, "orphans"), b = new JSONArray();
        for (int i = 0; i < a.length(); i++) if (!a.getJSONObject(i).getString("id").equals(id)) b.put(a.get(i));
        save(c, "orphans", b);
    }

    static synchronized void addManual(Context c, JSONObject o) throws Exception {
        o.put("id", "manual:" + UUID.randomUUID());
        o.put("src", "manual");
        add(c, o);
    }
}
