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

    private static final String CHANNEL_ID   = "trading_alerts";
    private static final int    NOTIF_ID     = 2001;
    private static final String CLAUDE_MODEL = "claude-haiku-4-5-20251001";

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
    //  MOTS-CLES DECLENCHEURS
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
    //  ACTIFS ET LEURS MOTS-CLES
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
        for (String a : ALLOWED_APPS) {
            if (packageName.toLowerCase().contains(a.toLowerCase())) {
                isAllowed = true; break;
            }
        }
        if (!isAllowed) return;

        // Extraire le texte
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
            MainActivity.instance.addLog("[BOT] Analyse " + assetsStr + " en cours...");

        String analysis = analyzeWithClaude(text, assetsStr);

        String ts = new java.text.SimpleDateFormat(
            "dd/MM/yyyy HH:mm:ss", Locale.getDefault()).format(new Date());

        // Telegram — sans emojis complexes pour eviter les erreurs d'encodage
        String tgMsg = "*ALERTE TRADING* - " + ts + "\n"
            + "Source: " + appName + "\n\n"
            + "News:\n" + text.substring(0, Math.min(300, text.length())) + "\n\n"
            + "ANALYSE:\n" + analysis;

        sendTelegram(tgMsg);
        showLocalNotif(ctx, assetsStr, analysis);

        if (MainActivity.instance != null)
            MainActivity.instance.addLog("[BOT] Analyse envoyee - " + assetsStr);
    }

    // =========================================================
    //  APPEL CLAUDE API - CORRIGE SANS EMOJIS DANS LE PROMPT
    // =========================================================
    private static String analyzeWithClaude(String text, String assets) {
        try {
            // Verification cle API
            if (MainActivity.CLAUDE_API_KEY == null
                || MainActivity.CLAUDE_API_KEY.isEmpty()) {
                return "Cle Claude API non configuree";
            }

            // Nettoyer le texte - supprimer les caracteres non-ASCII
            // qui peuvent corrompre le JSON
            String cleanText = text.replaceAll("[^\\x00-\\x7F]", "")
                                   .replaceAll("\"", "'")
                                   .replaceAll("\\\\", " ")
                                   .trim();

            String cleanAssets = assets.replaceAll("[^\\x00-\\x7F]", "").trim();

            // Prompt sans emojis - cause principale de l'erreur 400
            String prompt = "You are an expert financial trading analyst.\n"
                + "Breaking news: \"" + cleanText + "\"\n"
                + "Assets: " + cleanAssets + "\n\n"
                + "Short analysis per asset:\n"
                + "IMPACT: Bullish/Bearish/Neutral\n"
                + "SIGNAL: BUY/SELL/WAIT\n"
                + "REASON: 1 sentence\n"
                + "CONVICTION: Low/Medium/High\n"
                + "SUMMARY: [asset] -> [BUY/SELL]\n\n"
                + "Reply in French.";

            // Construction JSON via JSONObject - plus sur que la concatenation
            JSONObject msgObj = new JSONObject();
            msgObj.put("role", "user");
            msgObj.put("content", prompt);

            JSONArray msgsArr = new JSONArray();
            msgsArr.put(msgObj);

            JSONObject body = new JSONObject();
            body.put("model", CLAUDE_MODEL);
            body.put("max_tokens", 1024);
            body.put("messages", msgsArr);

            String bodyStr = body.toString();

            // Log debug - voir ce qu'on envoie
            if (MainActivity.instance != null)
                MainActivity.instance.addLog("[API] Envoi requete - " 
                    + bodyStr.length() + " chars");

            // Connexion HTTP
            URL url = new URL("https://api.anthropic.com/v1/messages");
            HttpURLConnection c = (HttpURLConnection) url.openConnection();
            c.setRequestMethod("POST");
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("x-api-key", MainActivity.CLAUDE_API_KEY.trim());
            c.setRequestProperty("anthropic-version", "2023-06-01");
            c.setRequestProperty("Content-Length", String.valueOf(bodyStr.getBytes("UTF-8").length));
            c.setDoOutput(true);
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);

            // Envoi
            OutputStream os = c.getOutputStream();
            os.write(bodyStr.getBytes("UTF-8"));
            os.flush();
            os.close();

            // Lecture reponse
            int code = c.getResponseCode();

            if (MainActivity.instance != null)
                MainActivity.instance.addLog("[API] Code reponse: " + code);

            InputStream is = (code == 200) ? c.getInputStream() : c.getErrorStream();
            BufferedReader br = new BufferedReader(new InputStreamReader(is, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            br.close();
            c.disconnect();

            if (code == 200) {
                JSONObject resp = new JSONObject(sb.toString());
                String result = resp.getJSONArray("content")
                                    .getJSONObject(0)
                                    .getString("text");
                return result;
            } else {
                // Log complet de l'erreur pour debug
                String errDetail = sb.toString();
                if (MainActivity.instance != null)
                    MainActivity.instance.addLog("[API] Erreur " + code + ": "
                        + errDetail.substring(0, Math.min(200, errDetail.length())));
                return "Erreur API " + code + " - voir journal";
            }

        } catch (Exception e) {
            if (MainActivity.instance != null)
                MainActivity.instance.addLog("[API] Exception: " + e.getMessage());
            return "Erreur: " + e.getMessage();
        }
    }

    // =========================================================
    //  ENVOI TELEGRAM
    // =========================================================
    private static void sendTelegram(String message) {
        try {
            if (MainActivity.TELEGRAM_TOKEN == null
                || MainActivity.TELEGRAM_TOKEN.isEmpty()) return;

            String enc = URLEncoder.encode(message, "UTF-8");
            URL url = new URL("https://api.telegram.org/bot"
                + MainActivity.TELEGRAM_TOKEN.trim()
                + "/sendMessage?chat_id=" + MainActivity.TELEGRAM_CHAT_ID.trim()
                + "&text=" + enc
                + "&parse_mode=Markdown");

            HttpURLConnection c = (HttpURLConnection) url.openConnection();
            c.setConnectTimeout(10000);
            c.setReadTimeout(10000);
            int code = c.getResponseCode();
            c.disconnect();

            if (MainActivity.instance != null)
                MainActivity.instance.addLog(
                    code == 200 ? "[TG] Envoye OK" : "[TG] Erreur: " + code);

        } catch (Exception e) {
            if (MainActivity.instance != null)
                MainActivity.instance.addLog("[TG] Exception: " + e.getMessage());
        }
    }

    // =========================================================
    //  NOTIFICATION PUSH LOCALE
    // =========================================================
    private static void showLocalNotif(Context ctx, String assets, String analysis) {
        NotificationManager nm = (NotificationManager)
            ctx.getSystemService(Context.NOTIFICATION_SERVICE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            nm.createNotificationChannel(new NotificationChannel(
                CHANNEL_ID, "Trading Alerts",
                NotificationManager.IMPORTANCE_HIGH));

        String summary = analysis.length() > 120
            ? analysis.substring(analysis.length() - 120) : analysis;

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
