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
    //  FILTRE 1 — APPLICATIONS AUTORISEES UNIQUEMENT
    //  Telegram, WhatsApp, SMS sont ignores automatiquement
    // =========================================================
    private static final List<String> ALLOWED_APPS = Arrays.asList(
        "com.twitter.android",    // X/Twitter
        "com.brave.browser",      // Brave
        "com.android.chrome",     // Chrome
        "com.chrome.beta",        // Chrome Beta
        "org.mozilla.firefox",    // Firefox
        "org.mozilla.fenix",      // Firefox Fenix
        "com.coinglass.app",      // Coinglass
        "com.financialjuice",     // FinancialJuice
        "com.investing.app",      // Investing.com
        "com.reuters.news"        // Reuters
    );

    // =========================================================
    //  FILTRE 2 — MOTS-CLES TRADING UNIQUEMENT
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
    //  DETECTION AUTOMATIQUE DES ACTIFS
    // =========================================================
    private static final String[][] ASSETS = {
        {"GOLD",   "gold,xauusd,or,fed,rate,war,conflict,sanction,nuclear,inflation,powell"},
        {"BTCUSD", "bitcoin,btc,crypto,etf,binance,coinbase,hack,sec"},
        {"GBPUSD", "gbp,pound,boe,uk,britain,sterling"},
        {"USDJPY", "jpy,yen,boj,japan"},
        {"NASDAQ", "nasdaq,sp500,dow,wall street,stock,tech"},
    };

    // =========================================================
    //  DECLENCHEMENT INSTANTANE A CHAQUE NOTIFICATION
    // =========================================================
    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {

        // Verifier si le bot est actif
        boolean botActive = getSharedPreferences("TradingBot", MODE_PRIVATE)
            .getBoolean("bot_active", false);
        if (!botActive) return;

        // FILTRE 1 — Par application
        String packageName = sbn.getPackageName();
        boolean isAllowed = false;
        for (String allowed : ALLOWED_APPS) {
            if (packageName.toLowerCase().contains(allowed.toLowerCase())) {
                isAllowed = true;
                break;
            }
        }
        if (!isAllowed) return; // Ignore Telegram, WhatsApp, SMS etc.

        // Extraire le texte de la notification
        Bundle extras = sbn.getNotification().extras;
        String title   = extras.getString(Notification.EXTRA_TITLE, "");
        String text    = extras.getString(Notification.EXTRA_TEXT, "");
        String bigText = extras.getString(Notification.EXTRA_BIG_TEXT, "");
        String full    = bigText.isEmpty() ? text : bigText;
        String combined = (title + " " + full).trim();

        if (combined.isEmpty()) return;

        // FILTRE 2 — Par mots-cles trading
        if (!isTradingRelevant(combined)) {
            if (MainActivity.instance != null)
                MainActivity.instance.addLog("[SKIP] Non trading: "
                    + combined.substring(0, Math.min(40, combined.length())));
            return;
        }

        // Notification pertinente detectee
        String appName = getAppName(packageName);
        if (MainActivity.instance != null)
            MainActivity.instance.addLog("[NOTIF] " + appName + ": "
                + combined.substring(0, Math.min(60, combined.length())) + "...");

        final String ft = combined;
        final String fa = appName;
        exec.submit(() -> processNotification(this, fa, ft));
    }

    // =========================================================
    //  PIPELINE : CLAUDE API -> TELEGRAM -> NOTIF LOCALE
    // =========================================================
    public static void processNotification(Context ctx, String appName, String text) {
        List<String> assets = detectAssets(text);
        String assetsStr    = String.join(", ", assets);

        if (MainActivity.instance != null)
            MainActivity.instance.addLog("[BOT] Analyse " + assetsStr + "...");

        // Appel Claude API
        String analysis = analyzeWithClaude(text, assetsStr);

        String ts = new java.text.SimpleDateFormat(
            "dd/MM/yyyy HH:mm:ss", Locale.getDefault()).format(new Date());

        // Message Telegram — sans emojis complexes pour eviter erreurs encodage
        String tgMsg = "*ALERTE TRADING* - " + ts + "\n"
            + "Source: " + appName + "\n\n"
            + "NEWS:\n" + text.substring(0, Math.min(300, text.length())) + "\n\n"
            + "ANALYSE FONDAMENTALE:\n" + analysis;

        sendTelegram(tgMsg);
        showLocalNotif(ctx, assetsStr, analysis);

        if (MainActivity.instance != null)
            MainActivity.instance.addLog("[OK] Envoye - " + assetsStr);
    }

    // =========================================================
    //  CLAUDE API — CORRIGE SANS EMOJIS (FIX ERREUR 400)
    // =========================================================
    private static String analyzeWithClaude(String text, String assets) {
        try {
            // Verification cle API
            if (MainActivity.CLAUDE_API_KEY == null
                || MainActivity.CLAUDE_API_KEY.trim().isEmpty()) {
                return "Cle Claude API non configuree";
            }

            // CORRECTION ERREUR 400 :
            // Supprimer TOUS les caracteres non-ASCII (emojis, accents speciaux)
            // qui corrompent le JSON envoye a l'API
            String cleanText = text
                .replaceAll("[^\\x00-\\x7F]", " ")  // supprime non-ASCII
                .replaceAll("\"", "'")               // echappe les guillemets
                .replaceAll("\\\\", " ")             // echappe les backslash
                .replaceAll("\\s+", " ")             // normalise les espaces
                .trim();

            // Prompt 100% ASCII — zero emoji — zero caractere special
            String prompt = "You are an expert financial trading analyst. "
                + "Breaking news: \"" + cleanText + "\". "
                + "Assets to analyze: " + assets + ". "
                + "For each asset provide: "
                + "IMPACT (Bullish/Bearish/Neutral), "
                + "SIGNAL (BUY/SELL/WAIT), "
                + "REASON (1 sentence), "
                + "CONVICTION (Low/Medium/High), "
                + "SUMMARY ([asset] -> [BUY/SELL]). "
                + "Be concise. Reply in French.";

            // Construction JSON via JSONObject — methode la plus sure
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

            // Log debug
            if (MainActivity.instance != null)
                MainActivity.instance.addLog("[API] Envoi " + bodyStr.length() + " chars...");

            // Connexion HTTP
            URL url = new URL("https://api.anthropic.com/v1/messages");
            HttpURLConnection c = (HttpURLConnection) url.openConnection();
            c.setRequestMethod("POST");
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("x-api-key", MainActivity.CLAUDE_API_KEY.trim());
            c.setRequestProperty("anthropic-version", "2023-06-01");
            c.setRequestProperty("Content-Length",
                String.valueOf(bodyStr.getBytes("UTF-8").length));
            c.setDoOutput(true);
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);

            // Envoi de la requete
            OutputStream os = c.getOutputStream();
            os.write(bodyStr.getBytes("UTF-8"));
            os.flush();
            os.close();

            // Lecture de la reponse
            int code = c.getResponseCode();

            if (MainActivity.instance != null)
                MainActivity.instance.addLog("[API] Code: " + code);

            InputStream is = (code == 200) ? c.getInputStream() : c.getErrorStream();
            BufferedReader br = new BufferedReader(new InputStreamReader(is, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            br.close();
            c.disconnect();

            if (code == 200) {
                // Succes — extraire le texte de la reponse
                JSONObject resp = new JSONObject(sb.toString());
                return resp.getJSONArray("content")
                           .getJSONObject(0)
                           .getString("text");
            } else {
                // Erreur — afficher le detail pour debug
                String errDetail = sb.toString();
                if (MainActivity.instance != null)
                    MainActivity.instance.addLog("[API] Erreur " + code + ": "
                        + errDetail.substring(0, Math.min(200, errDetail.length())));
                return "Erreur API " + code + " - voir journal app";
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
                || MainActivity.TELEGRAM_TOKEN.trim().isEmpty()) return;

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
                    code == 200 ? "[TG] Envoye OK" : "[TG] Erreur code: " + code);

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
        for (String kw : KEYWORDS)
            if (lower.contains(kw)) return true;
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
        if (found.isEmpty()) {
            found.add("GOLD");
            found.add("BTCUSD");
        }
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
