package com.tradingbot.analyzer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import androidx.core.app.NotificationCompat;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

public class NotificationService extends NotificationListenerService {

    private static final String CHANNEL_ID = "trading_alerts";
    private static final int    NOTIF_ID   = 2001;

    // =========================================================
    //  GROQ API — 100% GRATUIT
    //  Modele : llama-3.3-70b-versatile
    //  Limite : 14 400 requetes/jour gratuitement
    //  URL    : https://api.groq.com/openai/v1/chat/completions
    // =========================================================
    private static final String GROQ_MODEL = "llama-3.3-70b-versatile";
    private static final String GROQ_URL   = "https://api.groq.com/openai/v1/chat/completions";

    private final ExecutorService exec = Executors.newFixedThreadPool(3);

    // =========================================================
    //  APPLICATIONS AUTORISEES UNIQUEMENT
    // =========================================================
    private static final List<String> ALLOWED_APPS = Arrays.asList(
        "com.twitter.android",
        "com.brave.browser",
        "com.android.chrome",
        "com.chrome.beta",
        "org.mozilla.firefox",
        "org.mozilla.fenix",
        "com.coinglass.app",
        "com.financialjuice",
        "com.investing.app",
        "com.reuters.news"
    );

    // =========================================================
    //  MOTS-CLES TRADING
    // =========================================================
    private static final List<String> KEYWORDS = Arrays.asList(
        "war","attack","missile","sanction","conflict","crisis","invasion",
        "nuclear","terror","breaking","urgent","alert","flash",
        "guerre","attaque","conflit","crise",
        "fed","rate","inflation","cpi","nfp","gdp","fomc","powell","recession","taux",
        "gold","xauusd","silver","oil","bitcoin","btc","crypto","etf",
        "dollar","usd","gbp","jpy","eur","nasdaq","sp500","dow",
        "reuters","bloomberg","breaking news"
    );

    // =========================================================
    //  ACTIFS
    // =========================================================
    private static final String[][] ASSETS = {
        {"GOLD",   "gold,xauusd,or,fed,rate,war,conflict,sanction,nuclear,inflation,powell"},
        {"BTCUSD", "bitcoin,btc,crypto,etf,binance,coinbase,hack,sec"},
        {"GBPUSD", "gbp,pound,boe,uk,britain,sterling"},
        {"USDJPY", "jpy,yen,boj,japan"},
        {"NASDAQ", "nasdaq,sp500,dow,wall street,stock,tech"},
    };

    // =========================================================
    //  DECLENCHEMENT A CHAQUE NOTIFICATION
    // =========================================================
    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        boolean botActive = getSharedPreferences("TradingBot", MODE_PRIVATE)
            .getBoolean("bot_active", false);
        if (!botActive) return;

        // Filtre par application
        String packageName = sbn.getPackageName();
        boolean isAllowed = false;
        for (String allowed : ALLOWED_APPS) {
            if (packageName.toLowerCase().contains(allowed.toLowerCase())) {
                isAllowed = true; break;
            }
        }
        if (!isAllowed) return;

        Bundle extras = sbn.getNotification().extras;
        String title   = extras.getString(Notification.EXTRA_TITLE, "");
        String text    = extras.getString(Notification.EXTRA_TEXT, "");
        String bigText = extras.getString(Notification.EXTRA_BIG_TEXT, "");
        String full    = bigText.isEmpty() ? text : bigText;
        String combined = (title + " " + full).trim();

        if (combined.isEmpty() || !isTradingRelevant(combined)) return;

        String appName = getAppName(packageName);
        if (MainActivity.instance != null)
            MainActivity.instance.addLog("[NOTIF] " + appName + ": "
                + combined.substring(0, Math.min(60, combined.length())) + "...");

        final String ft = combined, fa = appName;
        exec.submit(() -> processNotification(this, fa, ft));
    }

    // =========================================================
    //  PIPELINE PRINCIPAL
    // =========================================================
    public static void processNotification(Context ctx, String appName, String text) {
        List<String> assets = detectAssets(text);
        String assetsStr    = String.join(", ", assets);

        if (MainActivity.instance != null)
            MainActivity.instance.addLog("[BOT] Analyse " + assetsStr + "...");

        String analysis = analyzeWithGroq(text, assetsStr);

        String ts = new java.text.SimpleDateFormat(
            "dd/MM/yyyy HH:mm:ss", Locale.getDefault()).format(new Date());

        String tgMsg = "*ALERTE TRADING* - " + ts + "\n"
            + "Source: " + appName + "\n\n"
            + "NEWS:\n" + text.substring(0, Math.min(300, text.length())) + "\n\n"
            + "ANALYSE:\n" + analysis;

        sendTelegram(tgMsg);
        showLocalNotif(ctx, assetsStr, analysis);

        if (MainActivity.instance != null)
            MainActivity.instance.addLog("[OK] Envoye - " + assetsStr);
    }

    // =========================================================
    //  GROQ API — FORMAT OPENAI COMPATIBLE
    // =========================================================
    private static String analyzeWithGroq(String text, String assets) {
        try {
            // Verification cle API
            if (MainActivity.CLAUDE_API_KEY == null
                || MainActivity.CLAUDE_API_KEY.trim().isEmpty()) {
                return "Cle Groq API non configuree";
            }

            String prompt = "Tu es analyste financier expert en trading.\n"
                + "News: \"" + text + "\"\nActifs: " + assets + "\n\n"
                + "Analyse courte par actif:\n"
                + "IMPACT: Haussier/Baissier/Neutre\n"
                + "SIGNAL: BUY/SELL/WAIT\n"
                + "RAISON: 1 phrase\n"
                + "CONVICTION: Faible/Moyenne/Forte\n"
                + "RESUME: [actif] -> [BUY/SELL]";

            // Format OpenAI compatible (utilise par Groq)
            JSONObject systemMsg = new JSONObject();
            systemMsg.put("role", "system");
            systemMsg.put("content", "Tu es un analyste financier expert. Reponds toujours en francais.");

            JSONObject userMsg = new JSONObject();
            userMsg.put("role", "user");
            userMsg.put("content", prompt);

            JSONArray messages = new JSONArray();
            messages.put(systemMsg);
            messages.put(userMsg);

            JSONObject body = new JSONObject();
            body.put("model", GROQ_MODEL);
            body.put("messages", messages);
            body.put("max_tokens", 1024);
            body.put("temperature", 0.3);

            String bodyStr = body.toString();

            // Connexion HTTP vers Groq
            URL url = new URL(GROQ_URL);
            HttpURLConnection c = (HttpURLConnection) url.openConnection();
            c.setRequestMethod("POST");
            c.setRequestProperty("Content-Type", "application/json");
            // Groq utilise le meme header Authorization que OpenAI
            c.setRequestProperty("Authorization", "Bearer " + MainActivity.CLAUDE_API_KEY.trim());
            c.setDoOutput(true);
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);

            OutputStream os = c.getOutputStream();
            os.write(bodyStr.getBytes("UTF-8"));
            os.flush();
            os.close();

            // Lire la reponse
            int responseCode = c.getResponseCode();

            if (MainActivity.instance != null)
                MainActivity.instance.addLog("[GROQ] HTTP: " + responseCode);

            InputStream is = (responseCode == 200)
                ? c.getInputStream()
                : c.getErrorStream();

            BufferedReader br = new BufferedReader(new InputStreamReader(is, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            br.close();
            c.disconnect();

            String responseBody = sb.toString();

            if (responseCode == 200) {
                // Format reponse Groq/OpenAI :
                // {"choices":[{"message":{"content":"..."}}]}
                JSONObject resp = new JSONObject(responseBody);
                return resp.getJSONArray("choices")
                           .getJSONObject(0)
                           .getJSONObject("message")
                           .getString("content");
            } else {
                if (MainActivity.instance != null)
                    MainActivity.instance.addLog("[GROQ] Erreur: "
                        + responseBody.substring(0, Math.min(200, responseBody.length())));
                return "Erreur Groq " + responseCode + " - voir journal";
            }

        } catch (Exception e) {
            if (MainActivity.instance != null)
                MainActivity.instance.addLog("[GROQ] Exception: " + e.getMessage());
            return "Erreur: " + e.getMessage();
        }
    }

    // =========================================================
    //  TELEGRAM
    // =========================================================
    private static void sendTelegram(String message) {
        try {
            String enc = URLEncoder.encode(message, "UTF-8");
            URL url = new URL("https://api.telegram.org/bot" + MainActivity.TELEGRAM_TOKEN
                + "/sendMessage?chat_id=" + MainActivity.TELEGRAM_CHAT_ID
                + "&text=" + enc + "&parse_mode=Markdown");
            HttpURLConnection c = (HttpURLConnection) url.openConnection();
            c.setConnectTimeout(10000);
            c.getResponseCode();
            c.disconnect();
            if (MainActivity.instance != null)
                MainActivity.instance.addLog("[TG] Envoye OK");
        } catch (Exception e) {
            if (MainActivity.instance != null)
                MainActivity.instance.addLog("[TG] Erreur: " + e.getMessage());
        }
    }

    // =========================================================
    //  NOTIFICATION LOCALE
    // =========================================================
    private static void showLocalNotif(Context ctx, String assets, String analysis) {
        NotificationManager nm = (NotificationManager)
            ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            nm.createNotificationChannel(new NotificationChannel(
                CHANNEL_ID, "Trading Alerts", NotificationManager.IMPORTANCE_HIGH));
        String summary = analysis.length() > 150
            ? analysis.substring(0, 150) + "..." : analysis;
        nm.notify(NOTIF_ID, new NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Signal Trading - " + assets)
            .setContentText(summary)
            .setStyle(new NotificationCompat.BigTextStyle().bigText(analysis))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setVibrate(new long[]{0, 300, 100, 300})
            .build());
    }

    // =========================================================
    //  HELPERS
    // =========================================================
    private boolean isTradingRelevant(String text) {
        String lower = text.toLowerCase();
        for (String kw : KEYWORDS) if (lower.contains(kw)) return true;
        return false;
    }

    private static List<String> detectAssets(String text) {
        String lower = text.toLowerCase();
        List<String> found = new ArrayList<>();
        for (String[] a : ASSETS)
            for (String kw : a[1].split(","))
                if (lower.contains(kw.trim()) && !found.contains(a[0])) {
                    found.add(a[0]); break;
                }
        if (found.isEmpty()) { found.add("GOLD"); found.add("BTCUSD"); }
        return found;
    }

    private String getAppName(String pkg) {
        if (pkg.contains("twitter") || pkg.contains(".x")) return "X/Twitter";
        if (pkg.contains("brave"))     return "Brave";
        if (pkg.contains("chrome"))    return "Chrome";
        if (pkg.contains("firefox"))   return "Firefox";
        if (pkg.contains("coinglass")) return "Coinglass";
        if (pkg.contains("financial")) return "FinancialJuice";
        if (pkg.contains("investing")) return "Investing.com";
        if (pkg.contains("reuters"))   return "Reuters";
        return pkg;
    }
  }
