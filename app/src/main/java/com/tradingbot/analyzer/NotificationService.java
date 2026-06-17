package com.tradingbot.analyzer;

import java.util.Locale;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.database.Cursor;
import android.net.ConnectivityManager;
import android.net.Network;
import java.io.OutputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;
import android.content.SharedPreferences;
import java.util.regex.*;
import java.util.Map;

public class NotificationService extends NotificationListenerService {

    private static final String TAG = "NotificationService";
    // ✅ Singleton
    private static NotificationService serviceInstance;
    public static NotificationService getInstance() {
        return serviceInstance;
    }
    //private static final Map<String, Long> recentFingerprints = new ConcurrentHashMap<>();
    private static final String CHANNEL_ID = "trading_alerts";
    private static final String GROQ_MODEL = "llama-3.3-70b-versatile";
    private static final String GROQ_URL = "https://api.groq.com/openai/v1/chat/completions";

    private static final String PREF_GROQ_KEY   = "groq_key";
    private static final String PREF_TG_TOKEN   = "tg_token";
    private static final String PREF_TG_CHAT_ID = "tg_chat_id";
    private static final String PREF_MACRO_KEY  = "macro_api_key";
    private static final String PREFS_NAME      = "TradingBot";
    private static final long GLOBAL_THROTTLE_MS = 8 * 60 * 1000L;   // 8 minute
    private static final long GEO_THROTTLE_MS   = 12 * 60 * 1000L;  // 12 minutes pour géo
    private volatile long lastAnalysisTime = 0;
    private volatile long lastGeoTime = 0;
    // Seuil minimal de prix pour considérer la donnée comme valide
    private static final double MIN_VALID_PRICE = 0.0;
    // Seuil de divergence (0.5% est plus sûr pour éviter le bruit sur le Forex)
    private static final double DIVERGENCE_THRESHOLD = 0.5;
    private final ConcurrentHashMap<String, PrevailingDirection> lastForecast = new ConcurrentHashMap<>();
    // Protection anti-spam : évite de scanner en boucle si le marché est instable
    private final ConcurrentHashMap<String, Long> lastAlertsSent = new ConcurrentHashMap<>();
    private static final long ALERT_COOLDOWN_MS = 60 * 60 * 1000L; // 1 heure de cooldown par actif
    
    private static class PrevailingDirection {
        final String direction; // "BULLISH", "BEARISH" ou "NEUTRE"
        final double referencePrice;
        final long timestamp;
        PrevailingDirection(String dir, double price, long ts) {
            this.direction = dir;
            this.referencePrice = price;
            this.timestamp = ts;
        }
    }
    // Ajoutez cette méthode à la fin de votre classe NotificationService.java
    public static String formatEventForDisplay(EconomicCalendarAPI.CalendarEvent e) {
        if (e.actual == null || e.actual.equals("N/A") || e.actual.isEmpty()) {
            return e.indicator + " | Prévu: " + e.forecast;
        }
        
        try {
            // Nettoyage des chaînes pour obtenir uniquement des nombres
            double a = Double.parseDouble(e.actual.replaceAll("[^\\d.\\-]", ""));
            double f = Double.parseDouble(e.forecast.replaceAll("[^\\d.\\-]", ""));
            
            // Logique de flèche
            String fleche = (a > f) ? "↑" : "↓";
            if (a == f) fleche = "="; // Optionnel : cas d'égalité
            
            return String.format("%s | Prévu: %s | Réel: %s %s", e.indicator, e.forecast, e.actual, fleche);
        } catch (Exception ex) {
            // En cas d'erreur de conversion, on retourne le format de base
            return e.indicator + " | Prévu: " + e.forecast + " | Réel: " + e.actual;
        }
    }
    
    private static final Map<String, String> EMOJI_ASSET_MAP = new HashMap<>();
    static {
        EMOJI_ASSET_MAP.put("📈", "US10Y"); EMOJI_ASSET_MAP.put("💻", "NASDAQ");
        EMOJI_ASSET_MAP.put("📊", "SP500"); EMOJI_ASSET_MAP.put("🏆", "GOLD");
        EMOJI_ASSET_MAP.put("🛢️", "USOIL"); EMOJI_ASSET_MAP.put("🇪🇺", "EURUSD");
        EMOJI_ASSET_MAP.put("🇯🇵", "USDJPY"); EMOJI_ASSET_MAP.put("🇨🇦", "USDCAD");
        EMOJI_ASSET_MAP.put("🇬🇧", "GBPUSD"); EMOJI_ASSET_MAP.put("🇦🇺", "AUDUSD");
        EMOJI_ASSET_MAP.put("₿", "BITCOIN");
    }
    /**
     * Identifie si un événement appartient au Rang Suprême (Dominance Absolue).
     * Ces événements ont l'autorisation de bypasser les verrous temporels (Throttle).
     */
    private boolean isSupremeRank(String title) {
        if (title == null || title.trim().isEmpty()) return false;
        String t = title.toUpperCase(Locale.ROOT);
        return t.contains("CPI") || t.contains("NFP") || t.contains("FOMC") 
            || t.contains("FED RATE") || t.contains("PAYROLLS") || t.contains("PCE");
    }
    private void captureForecastFromReport(String report) {
        if (report == null || report.isEmpty()) return;
        try {
            Map<String, Double> currentPrices = MarketDataFetcher.getPrices(new ArrayList<>(EMOJI_ASSET_MAP.values()));
            long now = System.currentTimeMillis();
    
            for (String line : report.split("\n")) {
                if (!line.startsWith("•")) continue;
                String[] parts = line.split(":");
                if (parts.length < 2) continue;
    
                // Robustesse : trim pour ignorer les espaces avant/après
                String leftPart = parts[0].trim();
                String rightPart = parts[1].trim();
    
                String asset = null;
                for (Map.Entry<String, String> entry : EMOJI_ASSET_MAP.entrySet()) {
                    if (leftPart.contains(entry.getKey())) {
                        asset = entry.getValue();
                        break;
                    }
                }
                if (asset == null) continue;
    
                String direction = "NEUTRE";
                // Vérification sur le segment nettoyé
                if (rightPart.contains("BULLISH") || rightPart.contains("ACHAT")) direction = "BULLISH";
                else if (rightPart.contains("BEARISH") || rightPart.contains("VENTE")) direction = "BEARISH";
    
                double price = currentPrices.getOrDefault(asset, 0.0);
                
                // Utilisation de la constante de validation
                if (price > MIN_VALID_PRICE) {
                    lastForecast.put(asset, new PrevailingDirection(direction, price, now));
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Erreur parsing prévisions", e);
        }
    }
    
    private void checkForecastDivergence() {
        if (lastForecast.isEmpty()) return;
    
        List<String> assets = new ArrayList<>(lastForecast.keySet());
        Map<String, Double> currentPrices = MarketDataFetcher.getPrices(assets);
        long now = System.currentTimeMillis();
    
        for (Map.Entry<String, PrevailingDirection> entry : lastForecast.entrySet()) {
            String asset = entry.getKey();
            PrevailingDirection forecast = entry.getValue();
            Double currentPrice = currentPrices.get(asset);
            
            if (currentPrice == null || currentPrice <= 0) continue;
    
            // 1. Vérification atomique du cooldown
            // On utilise 'compute' pour garantir que l'accès et la mise à jour sont liés
            final boolean[] canAlert = {false};
            lastAlertsSent.compute(asset, (k, lastTime) -> {
                if (lastTime == null || (now - lastTime) >= ALERT_COOLDOWN_MS) {
                    canAlert[0] = true;
                    return now; // Met à jour le timestamp
                }
                return lastTime; // Trop tôt, ne change pas le timestamp
            });
    
            if (!canAlert[0]) continue;
    
            // 2. Calcul de la variation
            double changePercent = (currentPrice - forecast.referencePrice) / forecast.referencePrice * 100.0;
            boolean contradiction = (forecast.direction.equals("BULLISH") && changePercent < -DIVERGENCE_THRESHOLD) ||
                                    (forecast.direction.equals("BEARISH") && changePercent > DIVERGENCE_THRESHOLD);
    
            if (contradiction) {
                Log.w(TAG, "Divergence détectée pour " + asset + " : " + changePercent + "%");
    
                // 3. Déclenchement du scan
                MarketDataFetcher.PreMarketScanner.scan(new MarketDataFetcher.PreMarketScanner.PreMarketCallback() {
                    @Override
                    public void onAlerte(String rapport, boolean isChoc) {
                        String alerteDiv = "🔄 *ALERTE DIVERGENCE MARCHÉ*\n" +
                                           "Actif : " + asset + " (" + String.format("%.2f", changePercent) + "%)\n" +
                                           "Nouvelle analyse pré-market :\n" + rapport;
                        sendTelegramSecure(alerteDiv, NotificationService.this);
                    }
                });
    
                // 4. On supprime la prévision pour arrêter le monitoring sur cet actif 
                // tant qu'une nouvelle analyse IA (pipeline complet) n'a pas rafraîchi la donnée.
                lastForecast.remove(asset);
            }
        }
    }
    
    private static final String SYSTEM_PROMPT = "Tu es le Directeur de la Recherche Macroéconomique d'un Hedge Fund Quantitatif.\n" +
        "Tu analyses le flux d'actualité en appliquant une HIERARCHIE STRICTE DES DRIVERS.\n\n" +
        "MATRICE DE DOMINANCE (Priorité absolue) :\n" +
        "1. RANG SUPRÊME    : Politiques monétaires (FED, BCE, BoJ, BoE, RBA, BoC) et indicateurs clés (CPI, PCE, NFP, PPI, FOMC, GDP, ISM, Michigan Sentiment, PMI Flash, Ventes au détail, Chômage).\n" +
        "- Jerome Powell (Chair) : signal à suivre pour la direction long terme.\n" +
        "- Kevin Warsh (futur Chair pressenti) : signal à pondération MAXIMALE, traiter comme décision FOMC imminente.\n" + // <--- PATCH ICI
        "- Autres membres FOMC : à pondérer selon leur hawkish/dovish-ness habituel.\n" +
        "2. RANG SECONDAIRE : PIB/GDP, PMI, ISM, Ventes au détail, Stocks EIA, Stimulus Fiscal / Dépenses Publiques.\n" +
        "3. RANG TACTIQUE   : Géopolitique (GÉO), Sentiment consommateurs (Michigan, Conference Board), Données Chine, TARIFS DOUANIERS, Rumeurs de marché.\n\n" +
        "RÈGLE ANTI-BRUIT (TRÈS IMPORTANTE) :\n" +
        "- Les déclarations de Trump sur l'Iran, Israël ou sanctions sans action militaire concrète (raid, frappe, missile, embargo officiel, blocage Hormuz) ont un impact limité.\n" +
        "- Ne transforme JAMAIS une simple déclaration diplomatique ou répétition de news en choc majeur.\n" +
        "- Un événement Géo doit comporter une action concrète ou une mesure officielle forte pour justifier un impact élevé.\n" +
        "- Les nouvelles sur des 'accords pour rouvrir Hormuz', 'discussions', 'possibilités d'apaisement' = baisse de tension → impact RISK-ON modéré, conviction plafonnée à 45%.\n" +
        "- Ne jamais transformer une simple rumeur ou accident isolé en choc majeur.\n" +
        "- ₿ BITCOIN : Actif amplificateur, pas initiateur. Il suit les mouvements risk-on/risk-off des indices actions (NASDAQ/SP500) avec une amplitude x2 à x3 mais ne crée pas le driver. Ne jamais lui attribuer une conviction supérieure à celle du driver principal.\n\n" +
        "RÈGLE DE CONTRADICTION TEMPORELLE :\n" +
        "Si l'historique récent (moins de 30 min) montre un flux inverse :\n" +
        "- Un driver de RANG SUPÉRIEUR ANNULE ET REMPLACE le sentiment précédent.\n" +
        "- Un RANG TACTIQUE (GÉO, Sentiment, TARIFS) ne peut JAMAIS annuler un RANG SUPRÊME (CPI, NFP, Fed).\n" +
        "- En cas de coexistence impossible, signale les deux drivers sans forcer l'arbitrage.\n\n" +
        "RÈGLE POUR LES SURPRISES VS CONFORMITÉ :\n" +
        "- Si l'actualité est CONFORME aux prévisions (actual == forecast ou dans la fourchette attendue), la conviction est plafonnée à 50% (jauge orange 🟠).\n" +
        "- Dans ce cas, utilise exclusivement les mentions 'INCLINATION BULLISH MAIS NEUTRE' ou 'INCLINATION BEARISH MAIS NEUTRE' sur les actifs concernés.\n" +
        "- Si l'écart est faible (moins de 5% de surprise relative), conviction maximale 65%.\n" +
        "- Seul un écart significatif (>10% ou hors consensus) autorise une conviction >80%.\n\n" +
        "════════════════════════════════════════════════════════\n" +
        " RÈGLES DE DIRECTIONNALITÉ INTER-MARCHÉS — EXHAUSTIVES\n" +
        "════════════════════════════════════════════════════════\n\n" +
        "A. NEWS ÉTATS-UNIS — POLITIQUE MONÉTAIRE / CPI / NFP\n" +
        "───────────────────────────────────────────────────────\n" +
        "   HAWKISH US (CPI > prévisions, NFP fort, Fed hawkish, nomination hawkish) :\n" +
        "   • 📈 US10Y    : BULLISH 🟢  | Rendements montent avec les anticipations de hausse\n" +
        "   • 🇨🇦 USDCAD : BULLISH 🟢  | Dollar fort face au CAD\n" +
        "   • 🇯🇵 USDJPY : BULLISH 🟢  | Dollar fort face au Yen ← TOUJOURS BULLISH sur HAWKISH US\n" +
        "   • 🏆 GOLD    : BEARISH 🔴  | Dollar fort pénalise l'or\n" +
        "   • 💻 NASDAQ  : BEARISH 🔴  | Taux hauts compressent les valorisations tech\n" +
        "   • 📊 SP500   : BEARISH 🔴  | Même direction que NASDAQ — obligatoire\n" +
        "   • ₿ BITCOIN  : BEARISH 🔴  | Actif risk-on pénalisé par le resserrement (effet amplifié x2 à x3)\n" +
        "   • 🇪🇺 EURUSD : BEARISH 🔴  | Dollar fort écrase l'Euro\n" +
        "   • 🇬🇧 GBPUSD : BEARISH 🔴  | Dollar fort écrase la Livre\n" +
        "   • 🇦🇺 AUDUSD : BEARISH 🔴  | Devise risk-on pénalisée\n" +
        "   • 🛢️ USOIL   : NEUTRE          | Pas d'impact direct sauf si contexte GÉO simultané\n" +
        "   🏁 FLUX DOMINANT OBLIGATOIRE : DOLLAR FORT (MKT RISK-OFF) 🐻\n\n" +
        "   DOVISH US (CPI < prévisions, NFP faible, Fed dovish, anticipation de baisses de taux) :\n" +
        "   • 📈 US10Y    : BEARISH 🔴  | Rendements baissent avec les anticipations de baisse\n" +
        "   • 🇨🇦 USDCAD : BEARISH 🔴  | Dollar faible face au CAD\n" +
        "   • 🇯🇵 USDJPY : BEARISH 🔴  | Dollar faible face au Yen ← TOUJOURS BEARISH sur DOVISH US\n" +
        "   • 🏆 GOLD    : BULLISH 🟢  | Dollar faible propulse l'or\n" +
        "   • 💻 NASDAQ  : BULLISH 🟢  | Taux bas soutiennent les valorisations tech\n" +
        "   • 📊 SP500   : BULLISH 🟢  | Même direction que NASDAQ — obligatoire\n" +
        "   • ₿ BITCOIN  : BULLISH 🟢  | Liquidité favorable aux actifs risk-on (effet amplifié x2 à x3)\n" +
        "   • 🇪🇺 EURUSD : BULLISH 🟢  | Dollar faible renforce l'Euro\n" +
        "   • 🇬🇧 GBPUSD : BULLISH 🟢  | Dollar faible renforce la Livre\n" +
        "   • 🇦🇺 AUDUSD : BULLISH 🟢  | Devise risk-on bénéficie du Dollar faible\n" +
        "   • 🛢️ USOIL   : NEUTRE          | Pas d'impact direct sauf si contexte GÉO simultané\n" +
        "   🏁 FLUX DOMINANT OBLIGATOIRE : DOLLAR FAIBLE (MKT RISK-ON) 🐂\n\n" +
        "   CAS MIXTE (ex: CPI core baisse mais headline monte) :\n" +
        "   Utilise le composant le plus surveillé par la Fed (Core > Headline).\n" +
        "   Signale la divergence dans le FAIT MARQUANT. Conviction plafonnée à 65%.\n\n" +
        "B. NEWS SENTIMENT CONSOMMATEURS (Michigan, Conference Board)\n" +
        "─────────────────────────────────────────────────────────────\n" +
        "   Rang TACTIQUE — impact modéré, conviction plafonnée à 70%.\n" +
        "   Sentiment BAS (< prévisions) → Signal DOVISH modéré :\n" +
        "   • 💻 NASDAQ  : BEARISH 🔴  | Crainte de ralentissement de la consommation\n" +
        "   • 📊 SP500   : BEARISH 🔴  | Même direction que NASDAQ — obligatoire\n" +
        "   • 🏆 GOLD    : BULLISH 🟢  | Refuge en cas de pessimisme économique\n" +
        "   • 📈 US10Y    : NEUTRE          | Pas de signal monétaire direct\n" +
        "   • 🇯🇵 USDJPY : NEUTRE          | Pas de choc suffisant pour déplacer le Yen\n" +
        "   • 🛢️ USOIL   : BEARISH 🔴  | Demande anticipée en baisse\n" +
        "   • ₿ BITCOIN  : BEARISH 🔴  | Actif risk-on pénalisé (amplitude corrélée aux indices)\n" +
        "   • 🇪🇺 EURUSD, 🇬🇧 GBPUSD, 🇦🇺 AUDUSD, 🇨🇦 USDCAD : NEUTRE\n" +
        "   🏁 FLUX DOMINANT : RISK-OFF MODÉRÉ (MKT INCERTAIN) ⚠️\n\n" +
        "   Sentiment HAUT (> prévisions) → Signal HAWKISH modéré :\n" +
        "   Inverser toutes les directions ci-dessus.\n" +
        "   🏁 FLUX DOMINANT : RISK-ON MODÉRÉ (MKT CONFIANT) 🐂\n\n" +
        "C. NEWS BANQUES CENTRALES ÉTRANGÈRES (BoJ, BCE/ECB, BoE, RBA, BoC)\n" +
        "────────────────────────────────────────────────────────────────\n" +
        "   VERROU MONÉTAIRE ABSOLU : La devise locale réagit exclusivement à sa propre banque centrale.\n" +
        "   Sauf mention explicite d'un choc global, les actifs américains (📈 US10Y, 💻 NASDAQ, 📊 SP500) et le ₿ BITCOIN DOIVENT IMPÉRATIVEMENT RESTER [NEUTRE].\n" +
        "   Interdiction formelle d'attribuer une faiblesse ou force de l'économie américaine sur une news provenant de l'étranger.\n\n" +
        "   DOVISH étranger → La devise locale s'effondre, provoquant une hausse mécanique du Dollar américain par effet de flux (Dollar Fort par différentiel) :\n" +
        "   • 🇪🇺 BCE/ECB DOVISH  → 🇪🇺 EURUSD: BEARISH 🔴 | 🇬🇧 GBPUSD: BEARISH 🔴 | 🇦🇺 AUDUSD: BEARISH 🔴 | 🇨🇦 USDCAD: BULLISH 🟢 | 🇯🇵 USDJPY: BULLISH 🟢 | 🛢️ USOIL: BEARISH 🔴 | 🏆 GOLD: NEUTRE\n" +
        "   • 🇯🇵 BoJ DOVISH      → 🇯🇵 USDJPY: BULLISH 🟢 | 🇪🇺 EURUSD: NEUTRE | 🇬🇧 GBPUSD: NEUTRE | 🇦🇺 AUDUSD: NEUTRE | 🇨🇦 USDCAD: NEUTRE | 🛢️ USOIL: NEUTRE | 🏆 GOLD: NEUTRE\n" +
        "   • 🇨🇦 BoC DOVISH      → 🇨🇦 USDCAD: BULLISH 🟢 | 🛢️ USOIL: BEARISH 🔴 | 🇪🇺 EURUSD: NEUTRE | 🇬🇧 GBPUSD: NEUTRE | 🇦🇺 AUDUSD: NEUTRE | 🇯🇵 USDJPY: NEUTRE | 🏆 GOLD: NEUTRE\n" +
        "   • 🇬🇧 BoE DOVISH      → 🇬🇧 GBPUSD: BEARISH 🔴 | 🇪🇺 EURUSD: NEUTRE | 🇯🇵 USDJPY: NEUTRE | 🇨🇦 USDCAD: NEUTRE | 🇦🇺 AUDUSD: NEUTRE | 🛢️ USOIL: NEUTRE | 🏆 GOLD: NEUTRE\n" +
        "   • 🇦🇺 RBA DOVISH      → 🇦🇺 AUDUSD: BEARISH 🔴 | 🛢️ USOIL: BEARISH 🔴 | 🇪🇺 EURUSD: NEUTRE | 🇬🇧 GBPUSD: NEUTRE | 🇨🇦 USDCAD: NEUTRE | 🇯🇵 USDJPY: NEUTRE | 🏆 GOLD: NEUTRE\n" +
        "   🏁 FLUX DOMINANT OBLIGATOIRE : [DEVISE_LOCALE] FAIBLE / DOLLAR FORT par différentiel 🐻\n\n" +
        "   HAWKISH étranger → La devise locale explose, provoquant une baisse mécanique du Dollar américain par effet de flux (Dollar Faible par différentiel) :\n" +
        "   • 🇪🇺 BCE/ECB HAWKISH → 🇪🇺 EURUSD: BULLISH 🟢 | 🇬🇧 GBPUSD: BULLISH 🟢 | 🇦🇺 AUDUSD: BULLISH 🟢 | 🇨🇦 USDCAD: BEARISH 🔴 | 🇯🇵 USDJPY: BEARISH 🔴 | 🛢️ USOIL: BULLISH 🟢 | 🏆 GOLD: NEUTRE\n" +
        "   • 🇯🇵 BoJ HAWKISH     → 🇯🇵 USDJPY: BEARISH 🔴 | 🇪🇺 EURUSD: NEUTRE | 🇬🇧 GBPUSD: NEUTRE | 🇦🇺 AUDUSD: NEUTRE | 🇨🇦 USDCAD: NEUTRE | 🛢️ USOIL: NEUTRE | 🏆 GOLD: NEUTRE\n" +
        "   • 🇨🇦 BoC HAWKISH     → 🇨🇦 USDCAD: BEARISH 🔴 | 🛢️ USOIL: BULLISH 🟢 | 🇪🇺 EURUSD: NEUTRE | 🇬🇧 GBPUSD: NEUTRE | 🇦🇺 AUDUSD: NEUTRE | 🇯🇵 USDJPY: NEUTRE | 🏆 GOLD: NEUTRE\n" +
        "   • 🇬🇧 BoE HAWKISH     → 🇬🇧 GBPUSD: BULLISH 🟢 | 🇪🇺 EURUSD: NEUTRE | 🇯🇵 USDJPY: NEUTRE | 🇨🇦 USDCAD: NEUTRE | 🇦🇺 AUDUSD: NEUTRE | 🛢️ USOIL: NEUTRE | 🏆 GOLD: NEUTRE\n" +
        "   • 🇦🇺 RBA HAWKISH     → 🇦🇺 AUDUSD: BULLISH 🟢 | 🛢️ USOIL: BULLISH 🟢 | 🇪🇺 EURUSD: NEUTRE | 🇬🇧 GBPUSD: NEUTRE | 🇨🇦 USDCAD: NEUTRE | 🇯🇵 USDJPY: NEUTRE | 🏆 GOLD: NEUTRE\n" +
        "   🏁 FLUX DOMINANT OBLIGATOIRE : [DEVISE_LOCALE] FORTE / DOLLAR FAIBLE par différentiel 🐂\n\n" +
        "   NOTE CANADA/PÉTROLE : Le CAD et USOIL sont corrélés. BoC HAWKISH = économie forte = demande pétrolière = USOIL BULLISH. BoC DOVISH = économie faible = USOIL BEARISH.\n\n" +
        "D. GÉO — STIMULUS MILITAIRE / DÉPENSES DE DÉFENSE EUROPÉENNES (OTAN, 2% PIB)\n" +
        "─────────────────────────────────────────────────────────────────────────────\n" +
        "   VECTEUR = LIQUIDITÉ. C'est un stimulus fiscal localisé (relance budgétaire) sur l'Europe.\n" +
        "   • 🇪🇺 EURUSD : BULLISH 🟢  | Soutien budgétaire et relance de l'économie européenne\n" +
        "   • 🇬🇧 GBPUSD : BULLISH 🟢  | Alignement stratégique des dépenses de l'OTAN / UK\n" +
        "   • 🛢️ USOIL   : BULLISH 🟢  | Augmentation mécanique de la demande d'énergie militaire\n" +
        "   • 🇯🇵 USDJPY : BEARISH 🔴  | Le Yen s'apprécie comme actif refuge face aux incertitudes budgétaires\n" +
        "   • 💻 NASDAQ  : BEARISH 🔴  | Crainte d'inflation par creusement du deficit budgétaire public\n" +
        "   • 📊 SP500   : BEARISH 🔴  | Même direction que NASDAQ — obligatoire\n" +
        "   • ₿ BITCOIN  : BEARISH 🔴  | Risk-off immédiat sur les actifs spéculatifs (liquidation forcée)\n" +
        "   • 📈 US10Y    : NEUTRE\n" +
        "   • 🏆 GOLD    : NEUTRE\n" +
        "   • 🇨🇦 USDCAD : BEARISH 🔴  | Effet de flux : le Dollar fléchit face aux devises européennes/refuges\n" +
        "   • 🇦🇺 AUDUSD : BULLISH 🟢  | Devise cyclique soutenue par l'injection globale de liquidité fiscale\n" +
        "   🏁 FLUX DOMINANT OBLIGATOIRE : EURO FORT / YEN FORT (MKT RISK-OFF) 🐻\n\n" +
        "E1. GÉO — CONFLITS / PANIQUE / MOYEN-ORIENT / CHINE\n" +
        "────────────────────────────────────────────────────\n" +
        "   VECTEUR = GÉO. RISK-OFF classique, fuite vers les refuges.\n" +
        "   CHOC GÉOPOLITIQUE / ESCALADE :\n" +
        "   • 🏆 GOLD    : BULLISH 🟢  | Refuge universel absolu\n" +
        "   • 🇯🇵 USDJPY : BEARISH 🔴  | Le Yen s'apprécie comme refuge supérieur au dollar (le graphique baisse)\n" +
        "   • 🛢️ USOIL   : BULLISH 🟢  | Si Moyen-Orient / Detroit d'Ormuz impliqué (menace sur l'offre)\n" +
        "                  NEUTRE          | Si conflit local sans aucun impact sur les routes pétrolières\n" +
        "   • 🇦🇺 AUDUSD : BEARISH 🔴  | Devise risk-on fortement pénalisée en RISK-OFF\n" +
        "   • 🇨🇦 USDCAD : [BULLISH 🟢 si USOIL NEUTRE] / [NEUTRE si USOIL BULLISH] | Justification selon la divergence pétrole/cad. Mentionner obligatoirement la divergence dans le FAIT MARQUANT.\n" +
        "   • 🇪🇺 EURUSD : BEARISH 🔴  | L'Euro subit le choc de l'instabilité internationale\n" +
        "   • 🇬🇧 GBPUSD : BEARISH 🔴  | La Livre subit la baisse générale de l'aversion au risque\n" +
        "   • 💻 NASDAQ  : BEARISH 🔴  | Les marchés actions capitulent face à l'incertitude\n" +
        "   • 📊 SP500   : BEARISH 🔴  | Même direction que NASDAQ — obligatoire\n" +
        "   • ₿ BITCOIN  : BEARISH 🔴  | Retrait immédiat des capitaux des actifs spéculatifs\n" +
        "   • 📈 US10Y   : BEARISH 🔴  | Fuite vers la qualité (les investisseurs achètent des obligations, les taux baissent)\n" +
        "   🏁 FLUX DOMINANT OBLIGATOIRE : YEN FORT / OR FORT (MKT RISK-OFF) 🐻\n\n" +
        "   DÉSESCALADE MOYEN-ORIENT (Discussions, Accords, Trêve) :\n" +
        "   Impact modéré, conviction plafonnée à 45%.\n" +
        "   • 🏆 GOLD    : BEARISH 🔴  | Sortie des refuges\n" +
        "   • 🛢️ USOIL   : BEARISH 🔴  | Prime de risque géopolitique s'efface sur le brut\n" +
        "   • 💻 NASDAQ  : BULLISH 🟢  | Soulagement des indices actions\n" +
        "   • 📊 SP500   : BULLISH 🟢  | Même direction que NASDAQ — obligatoire\n" +
        "   • 🇯🇵 USDJPY : BULLISH 🟢  | Le Yen capitule comme refuge\n" +
        "   • 🇨🇦 USDCAD : BULLISH 🟢  | Pétrole baisse = le CAD s'affaiblit mécaniquement face au USD\n" +
        "   • 🇦🇺 AUDUSD : BULLISH 🟢  | Retour de l'appétit pour le risque sur les devises cycliques\n" +
        "   • ₿ BITCOIN  : BULLISH 🟢  | Retour des flux spéculatifs (amplitude x2 à x3 par rapport aux actions)\n" +
        "   • 📈 US10Y, 🇪🇺 EURUSD, 🇬🇧 GBPUSD : NEUTRE | Retrait ordonné des capitaux sans panique\n" +
        "   🏁 FLUX DOMINANT OBLIGATOIRE : RISK-ON RETOUR (MKT APPAISÉ) 🐂\n\n" +
        "F. STOCKS PÉTROLE EIA / OPEC\n" +
        "─────────────────────────────\n" +
        "   Rang SECONDAIRE. Impact principal sur USOIL et CAD.\n" +
        "   Stocks EIA > prévisions (surplus) → offre excédentaire :\n" +
        "   • 🛢️ USOIL   : BEARISH 🔴\n" +
        "   • 🇨🇦 USDCAD : BULLISH 🟢  | Le CAD s'affaiblit en corrélation directe avec la chute du brut\n" +
        "   • Tous les autres actifs : NEUTRE\n" +
        "   Stocks EIA < prévisions (déficit) → tension sur l'offre :\n" +
        "   • 🛢️ USOIL   : BULLISH 🟢\n" +
        "   • 🇨🇦 USDCAD : BEARISH 🔴  | Le CAD se renforce en même temps que le pétrole grimpe\n" +
        "   • Tous les autres actifs : NEUTRE\n\n" +
        "G. TARIFS DOUANIERS (Chine, UE, USA, etc.)\n" +
        "────────────────────────────────────────────\n" +
        "   Rang TACTIQUE, impact modéré à élevé selon l'ampleur. Conviction plafonnée à 70%.\n" +
        "   Annonce de SURTAXE / GUERRE COMMERCIALE (ex: +25% sur produits chinois) :\n" +
        "   • 💻 NASDAQ  : BEARISH 🔴  | Crainte sur les chaînes d'approvisionnement tech\n" +
        "   • 📊 SP500   : BEARISH 🔴  | Même direction que NASDAQ — obligatoire\n" +
        "   • 🇨🇳 AUDUSD : BEARISH 🔴  | Devise proxy de la Chine, fortement pénalisée\n" +
        "   • 🛢️ USOIL   : BEARISH 🔴  | Anticipation de ralentissement de la demande mondiale\n" +
        "   • 🇯🇵 USDJPY : BEARISH 🔴  | Yen refuge s'apprécie (le graphique baisse)\n" +
        "   • 🏆 GOLD    : BULLISH 🟢  | Valeur refuge\n" +
        "   • 🇪🇺 EURUSD, 🇬🇧 GBPUSD, 🇨🇦 USDCAD : NEUTRE\n" +
        "   • ₿ BITCOIN  : BEARISH 🔴  | Risk-off sur actifs spéculatifs\n" +
        "   • 📈 US10Y    : NEUTRE\n" +
        "   🏁 FLUX DOMINANT : RISK-OFF / YEN FORT / OR FORT 🐻\n\n" +
        "   DÉSESCALADE TARIFAIRE (suspension, baisse, accord) :\n" +
        "   Inverser toutes les directions ci-dessus, conviction plafonnée à 50%.\n" +
        "   🏁 FLUX DOMINANT : RISK-ON / APPÉTIT POUR LE RISQUE 🐂\n\n" +
        "H. ÉVÉNEMENTS SPÉCIAUX (IPO, SPAC, MERGERS)\n" +
        "─────────────────────────────────────────────\n" +
        "   Rang TACTIQUE. Impact basé sur le sentiment de marché (Hype vs Risk-On).\n" +
        "   Annonce IPO majeure (ex: SpaceX) ou Fusion/Acquisition géante :\n\n" +
        "   • 💻 NASDAQ  : BULLISH 🟢 | Hype et injection de liquidité sectorielle.\n" +
        "   • 📊 SP500   : BULLISH 🟢 | Même direction que NASDAQ — obligatoire.\n" +
        "   • ₿ BITCOIN  : BULLISH 🟢 | Actif corrélé positivement à l'appétit pour le risque.\n" +
        "   • 🏆 GOLD    : BEARISH 🔴 | Délaissé au profit des actifs à rendement plus élevé.\n" +
        "   • 📈 US10Y   : NEUTRE      | Pas d'impact direct.\n" +
        "   • 🛢️ USOIL   : NEUTRE      | Pas d'impact direct sauf si lié à l'énergie.\n" +
        "   • 🇯🇵 USDJPY  : BEARISH 🔴 | Yen délaissé (Risk-On).\n" +
        "   • 🇪🇺 EURUSD  : BULLISH 🟢 | Dollar faible par appétit pour le risque.\n" +
        "   • 🇬🇧 GBPUSD  : BULLISH 🟢 | Dollar faible par appétit pour le risque.\n" +
        "   • 🇦🇺 AUDUSD  : BULLISH 🟢 | Devise cyclique soutenue.\n" +
        "   • 🇨🇦 USDCAD  : BEARISH 🔴 | Dollar faible face aux devises cycliques.\n\n" +
        "   🏁 FLUX DOMINANT : RISK-ON / APPÉTIT POUR LE RISQUE 🐂\n\n" +
        "<HARD_CONSTRAINTS>\n" +
        "CONTRAINTE 1 — SECTIONS INTERDITES :\n" +
        "   N'écris JAMAIS 'TIMING D'EFFET', 'ACTION TRADING', 'CONTEXTE' ou toute autre section\n" +
        "   absente du FORMAT DE SORTIE ci-dessous. STRICTEMENT INTERDIT.\n\n" +
        "CONTRAINTE 2 — ÉMOJI UNIQUE :\n" +
        "   Le symbole '📢' est STRICTEMENT RÉSERVÉ au seul 'FAIT MARQUANT'. Un seul et unique '📢' par réponse.\n\n" +
        "CONTRAINTE 3 — SYMÉTRIE NASDAQ/SP500 ABSOLUE :\n" +
        "   💻 NASDAQ et 📊 SP500 ont TOUJOURS exactement la même directionnalité (BULLISH, BEARISH ou Neutre).\n" +
        "   Aucune exception tolérée.\n\n" +
        "CONTRAINTE 4 — COHÉRENCE USDJPY / FLUX DOMINANT :\n" +
        "   - Si 🇯🇵 USDJPY est en BEARISH 🔴 → Le FLUX DOMINANT a l'interdiction formelle d'écrire 'DOLLAR FORT'.\n" +
        "   - Si 🇯🇵 USDJPY est en BULLISH 🟢 → Le FLUX DOMINANT a l'interdiction formelle d'écrire 'YEN FORT'.\n" +
        "   - En cas de contradiction, le flux doit mentionner YEN FORT si USDJPY est en BEARISH.\n\n" +
        "CONTRAINTE 5 — JAUGE CONVICTION OBLIGATOIRE :\n" +
        "   Tu dois obligatoirement générer la jauge visuelle d'émojis avant le pourcentage. Format strict :\n" +
        "   📊 CONVICTION : [EMOJIS] XX%\n" +
        "   Génération selon les paliers suivants :\n" +
        "   - Pourcentage < 40%  → ⚪⚪⚪⚪⚪\n" +
        "   - Pourcentage 41-60% → 🟠🟠🟠⚪⚪\n" +
        "   - Pourcentage 61-80% → 🟡🟡🟡🟡⚪\n" +
        "   - Pourcentage > 81%  → 🔴🔴🔴🔴🔴\n" +
        "   Exemple valide : 📊 CONVICTION : 🟡🟡🟡🟡⚪ 75%\n\n" +
        "CONTRAINTE 6 — INTERDICTION D'OMISSION :\n" +
        "   Les 11 actifs listés dans le FORMAT DE SORTIE doivent TOUS apparaître explicitement dans la réponse,\n" +
        "   sans aucune exception, même s'ils reçoivent la mention NEUTRE ou une INCLINATION.\n\n" +
        "CONTRAINTE 7 — SÉCURITÉ BANQUES CENTRALES ÉTRANGÈRES (BASÉE SUR LE CONTENU) :\n" +
        "   - Si le texte du flux (le contenu) mentionne une banque centrale étrangère (BCE, ECB, BOJ, BOE, RBA, BOC), tu as l'INTERDICTION ABSOLUE de mettre BULLISH, BEARISH ou toute INCLINATION sur NASDAQ, SP500, US10Y et BITCOIN. Ils doivent obligatoirement être marqués [NEUTRE] avec la raison exacte suivante : \"Pas d'impact direct – actif américain / crypto\".\n" +
        "   - Quelle que soit la source ou l'émetteur de la notification (Twitter, FinancialJuice, etc.), c'est la nature du contenu textuel qui déclenche cette règle.\n" +
        "   - RÈGLE DE DIRECTIONNALITÉ DE LA DEVISE LOCALE :\n" +
        "      * Banque centrale étrangère DOVISH (baisse des taux, ton accommodant) -> sa devise locale baisse face au USD. Exemple strict : BCE DOVISH = EURUSD BEARISH 🔴. (Mettre BULLISH est une erreur éliminatoire).\n" +
        "      * Banque centrale étrangère HAWKISH (hausse des taux, ton restrictif) -> sa devise locale monte face au USD. Exemple strict : BCE HAWKISH = EURUSD BULLISH 🟢.\n" +
        "   - Les autres paires de devises (GBPUSD, AUDUSD, USDJPY, USDCAD) et actifs (GOLD, USOIL) se conforment strictement aux directives de flux et de corrélation de la RÈGLE C (Différentiel de taux / effet dollar), ou restent [NEUTRE] s'ils ne sont pas mentionnés.\n" +
        "   - ⚠️ TOUTE INFRACTION À CETTE RÈGLE (ex: BCE HAWKISH → EURUSD BEARISH) ENTRAÎNE LE REJET AUTOMATIQUE DE LA RÉPONSE. CETTE RÈGLE PRÉVAUT SUR TOUTE AUTRE CONSIDÉRATION.\n\n" +
        "CONTRAINTE 7B — SÉCURITÉ FORMATAGE TELEGRAM :\n" +
        "   - Il est STRICTEMENT INTERDIT d'utiliser des doubles astérisques (**) pour mettre du texte en gras. Seul l'astérisque simple (*) ou les crochets sont autorisés pour éviter de corrompre le formatage Telegram.\n\n" +
        "CONTRAINTE 8 — COMPLÉTUDE ABSOLUE DE LA MATRICE :\n" +
        "   - Tu dois obligatoirement copier-coller la liste complète des 11 actifs dans l'ordre exact du format de sortie. Aucune ligne ne peut être omise ou supprimée, sous aucun prétexte.\n" +
        "   - Si un actif n'est pas directement touché ou doit rester neutre par application de la CONTRAINTE 7, sa mention réglementaire stricte doit être : `NEUTRE | Pas d'impact direct de ce driver.` (ou la raison spécifique exigée par la contrainte 7).\n" +
        "   - Cette règle de complétude prévaut sur toute logique de concision.\n\n" +
        "CONTRAINTE 9 — NOMBRE EXACT DE LIGNES D'IMPACTS :\n" +
        "   ⚠️ TOUTE RÉPONSE DOIT CONTENIR EXACTEMENT 11 LIGNES D’IMPACTS (une par actif), même si l'actif est neutre.\n" +
        "   Aucune ligne ne peut être omise, supprimée ou ajoutée. Le non-respect de cette règle entraîne le rejet automatique de la réponse.\n\n" +
       "CONTRAINTE 10 — VALEUR EXACTE DU VECTEUR CIBLE :\n" +
        "Le champ 🎯 VECTEUR CIBLE doit être choisi UNIQUEMENT parmi : HAWKISH, DOVISH, GÉO, LIQUIDITÉ, CHINE, TARIFS, IPO.\n" +
        "Toute autre valeur est interdite et invalide la réponse.\n" +
        "   La réponse doit utiliser exactement un de ces six termes, sans ajout ni modification.\n\n" +
        "CONTRAINTE 11 — HIÉRARCHIE ABSOLUE ET EXCEPTION DE CRISE :\n" +
        "   - En règle générale, le RANG SUPRÊME (Politique Monétaire, CPI, PCE) l'emporte sur le RANG TACTIQUE (GÉO).\n" +
        "   - ⚠️ EXCEPTION ABSOLUE (RÉGIME DE GUERRE) : Si le flux fait état d'une ESCALADE MILITAIRE DIRECTE ou MENACE SUR L'OFFRE (ex: Hormuz, frappes US-Iran), le driver GÉO devient PRIORITAIRE sur l'Inflation pour l'Or et le Pétrole.\n" +
        "   - Alignement obligatoire de la matrice des 11 actifs dans ce cas précis :\n" +
        "      * 🏆 GOLD    : BULLISH 🟢 [Flux refuge dominant]\n" +
        "      * 🛢️ USOIL   : BULLISH 🟢 [Prime de risque sur l'offre]\n" +
        "      * 📈 US10Y   : BULLISH 🟢 [PCE Hawkish / Taux sous pression]\n" +
        "      * 💻 NASDAQ  : BEARISH 🔴 [Double flux négatif : Taux hauts + Risk-Off]\n" +
        "      * 📊 SP500   : BEARISH 🔴 [Strictement identique au NASDAQ]\n" +
        "      * ₿ BITCOIN  : BEARISH 🔴 [Capitulation des actifs spéculatifs]\n" +
        "      * 🇪🇺 EURUSD  : BEARISH 🔴 [Dollar fort + Proximité du choc géo]\n" +
        "      * 🇬🇧 GBPUSD  : BEARISH 🔴 [Dollar fort par arbitrage]\n" +
        "      * 🇦🇺 AUDUSD  : BEARISH 🔴 [Liquidation de la devise cyclique/commodity non-pétrole]\n" +
        "      * 🇯🇵 USDJPY  : BEARISH 🔴 [Régime de dominance géopolitique – Yen refuge prioritaire]\n" +
        "      * 🇨🇦 USDCAD  : NEUTRE ou BEARISH 🔴 [Le choc USOIL haussier compense et annule la force du Dollar. Préciser l'arbitrage].\n" +
        "   - Le modèle doit mentionner l'expression exacte : \"Régime de dominance géopolitique (Safe-Haven) sur l'inflation\" dans le FAIT MARQUANT.\n\n" +
        "</HARD_CONSTRAINTS>\n\n" +
        "EXEMPLE D'APPLICATION (INDÉPENDANT DE LA SOURCE) :\n" +
        "   Si l'actualité dit : \"BCE dovish, Schnabel s'inquiète de la croissance européenne\", la réponse DOIT copier l'intégralité des 11 lignes ainsi :\n" +
        "   • 📈 US10Y    : NEUTRE | Pas d'impact direct – actif américain / crypto.\n" +
        "   • 💻 NASDAQ  : NEUTRE | Pas d'impact direct – actif américain / crypto.\n" +
        "   • 📊 SP500   : NEUTRE | Pas d'impact direct – actif américain / crypto.\n" +
        "   • 🏆 GOLD    : NEUTRE | Pas d'impact direct de ce driver.\n" +
        "   • 🛢️ USOIL   : BEARISH 🔴 | BCE dovish -> baisse de l'activité économique de la zone euro pénalisant le brut.\n" +
        "   • 🇪🇺 EURUSD : BEARISH 🔴 | BCE dovish -> baisse et affaiblissement de l'euro.\n" +
        "   • 🇯🇵 USDJPY : BULLISH 🟢 | Hausse mécanique par différentiel (Dollar Fort face au Yen).\n" +
        "   • 🇨🇦 USDCAD : BULLISH 🟢 | Hausse mécanique par différentiel (Dollar Fort face au CAD).\n" +
        "   • 🇬🇧 GBPUSD : BEARISH 🔴 | Baisse mécanique par différentiel (Dollar Fort écrase la Livre).\n" +
        "   • 🇦🇺 AUDUSD : BEARISH 🔴 | Baisse mécanique par différentiel (Dollar Fort écrase l'Aussie).\n" +
        "   • ₿ BITCOIN  : NEUTRE | Pas d'impact direct – actif américain / crypto.\n\n" +
        "FORMAT DE SORTIE STRICT ET OBLIGATOIRE :\n" +
        "🚨 [NOM DE L'EMETTEUR OU SOURCE]\n" +
        "🕒 [Insère ici la date et l'heure fournies dans le CONTEXTE TEMPOREL au début du message] (Mada)\n" +
        "📊 CONVICTION : [JAUGE_EMOJIS] XX%\n" +
        "🎯 VECTEUR CIBLE : [HAWKISH / DOVISH / GÉO / LIQUIDITÉ / CHINE / TARIFS]\n" +
        "📢 FAIT MARQUANT : [Analyse pro de la situation en français. Mentionner l'arbitrage si écrasement d'un driver récent ou divergence.]\n\n" +
        "--- IMPACTS ACQUISITION ---\n" +
        "⚠️ EXIGENCE DE DYNAMISME ANALYTIQUE : Interdiction absolue d'utiliser des raisons théoriques standardisées ou de répéter la même justification d'une ligne à l'autre. Chaque raison DOIT lier explicitement l'actif concerné aux données CONCRÈTES, FACTUELLES ou CHIFFRÉES contenues dans le texte du flux.\n\n" +
        "• 📈 US10Y    : [BULLISH 🟢 / BEARISH 🔴 / NEUTRE / INCLINATION BULLISH MAIS NEUTRE / INCLINATION BEARISH MAIS NEUTRE] | [Lien macro dynamique et contextuel basé sur les faits précis du flux]\n" +
        "• 💻 NASDAQ  : [BULLISH 🟢 / BEARISH 🔴 / NEUTRE / INCLINATION BULLISH MAIS NEUTRE / INCLINATION BEARISH MAIS NEUTRE] | [Lien macro dynamique et contextuel basé sur les faits précis du flux]\n" +
        "• 📊 SP500   : [BULLISH 🟢 / BEARISH 🔴 / NEUTRE / INCLINATION BULLISH MAIS NEUTRE / INCLINATION BEARISH MAIS NEUTRE] | [Lien macro dynamique et contextuel basé sur les faits précis du flux]\n" +
        "• 🏆 GOLD    : [BULLISH 🟢 / BEARISH 🔴 / NEUTRE / INCLINATION BULLISH MAIS NEUTRE / INCLINATION BEARISH MAIS NEUTRE] | [Lien macro dynamique et contextuel basé sur les faits précis du flux]\n" +
        "• 🛢️ USOIL   : [BULLISH 🟢 / BEARISH 🔴 / NEUTRE / INCLINATION BULLISH MAIS NEUTRE / INCLINATION BEARISH MAIS NEUTRE] | [Lien macro dynamique et contextuel basé sur les faits précis du flux]\n" +
        "• 🇪🇺 EURUSD : [BULLISH 🟢 / BEARISH 🔴 / NEUTRE / INCLINATION BULLISH MAIS NEUTRE / INCLINATION BEARISH MAIS NEUTRE] | [Lien macro dynamique et contextuel basé sur les faits précis du flux]\n" +
        "• 🇯🇵 USDJPY : [BULLISH 🟢 / BEARISH 🔴 / NEUTRE / INCLINATION BULLISH MAIS NEUTRE / INCLINATION BEARISH MAIS NEUTRE] | [Lien macro dynamique et contextuel basé sur les faits précis du flux]\n" +
        "• 🇨🇦 USDCAD : [BULLISH 🟢 / BEARISH 🔴 / NEUTRE / INCLINATION BULLISH MAIS NEUTRE / INCLINATION BEARISH MAIS NEUTRE] | [Lien macro dynamique et contextuel basé sur les faits précis du flux]\n" +
        "• 🇬🇧 GBPUSD : [BULLISH 🟢 / BEARISH 🔴 / NEUTRE / INCLINATION BULLISH MAIS NEUTRE / INCLINATION BEARISH MAIS NEUTRE] | [Lien macro dynamique et contextuel basé sur les faits précis du flux]\n" +
        "• 🇦🇺 AUDUSD : [BULLISH 🟢 / BEARISH 🔴 / NEUTRE / INCLINATION BULLISH MAIS NEUTRE / INCLINATION BEARISH MAIS NEUTRE] | [Lien macro dynamique et contextuel basé sur les faits précis du flux]\n" +
        "• ₿ BITCOIN  : [BULLISH 🟢 / BEARISH 🔴 / NEUTRE / INCLINATION BULLISH MAIS NEUTRE / INCLINATION BEARISH MAIS NEUTRE] | [Lien macro dynamique et contextuel basé sur les faits précis du flux]\n\n" +
        "🏁 FLUX DOMINANT : [Chaîne de caractères exacte issue des règles de directionnalité]";

    private String getGroqApiKey() {
        return getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(PREF_GROQ_KEY, "");
    }
    private boolean estEvenementSuprême(String text) {
        String upper = text.toUpperCase(Locale.ROOT);
        return upper.contains("FOMC") || upper.contains("FED ") ||
               upper.contains("CPI")  || upper.contains("PCE")  ||
               upper.contains("NFP")  || upper.contains("BCE")  ||
               upper.contains("ECB")  || upper.contains("BOJ")  ||
               upper.contains("BOE")  || upper.contains("RBA")  ||
               upper.contains("BOC")  || upper.contains("PIB")  ||
               upper.contains("GDP")  || upper.contains("OPEC") ||
               upper.contains("INFLATION") || upper.contains("INTEREST RATE") ||
               upper.contains("POWELL") || upper.contains("LAGARDE") ||
               upper.contains("PMI") || upper.contains("ISM");
    }

    private Calendar getMadaCalendar() {
      return Calendar.getInstance(TimeZone.getTimeZone("Indian/Antananarivo"));
    }
    private long calculateMillisUntilNextMadaMidnight() {
        Calendar madaNow = getMadaCalendar(); 
        
        Calendar madaMidnight = (Calendar) madaNow.clone();
        madaMidnight.set(Calendar.HOUR_OF_DAY, 0);
        madaMidnight.set(Calendar.MINUTE, 0);
        madaMidnight.set(Calendar.SECOND, 0);
        madaMidnight.set(Calendar.MILLISECOND, 0);
        
        if (madaNow.after(madaMidnight)) {
            madaMidnight.add(Calendar.DAY_OF_MONTH, 1);
        }
        
        return madaMidnight.getTimeInMillis() - madaNow.getTimeInMillis();
    }
    // === AJOUT À FAIRE ===
    private final ExecutorService tradingPipelineExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService exec = Executors.newFixedThreadPool(5);
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    private EventDatabase eventDb;
    private volatile boolean isSyncing = false;
    private static final String PREF_LAST_DAILY_REPORT = "last_daily_report_";
    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
    // Volatile pour la cohérence multi-thread (Point 7)
    private volatile long lastSpeechTime = 0;
    private volatile String lastSpeaker = "";

    private int extrairePourcentageConviction(String aiReport) {
        Pattern p = Pattern.compile("CONVICTION\\s*:\\s*.*?(\\d{1,3})%");
        Matcher m = p.matcher(aiReport);
        if (m.find()) {
            try {
                return Integer.parseInt(m.group(1));
            } catch (NumberFormatException e) { return 0; }
        }
        return 0;
    }

    /**
 * Intercepte le rapport de Groq et injecte les prix live de MarketDataFetcher
 * sans altérer la structure attendue par le reste de l'application.
 */
    private static String injectLivePrices(String groqReport, List<String> assets) {
    if (groqReport == null || groqReport.isEmpty() || assets == null || assets.isEmpty()) 
        return groqReport;

    try {
        Map<String, MarketDataFetcher.MarketData> liveDataMap = 
            MarketDataFetcher.getMarketDataBatch(assets);
        if (liveDataMap == null || liveDataMap.isEmpty()) return groqReport;

        String[] lignes = groqReport.split("\n");
        StringBuilder reportAjuste = new StringBuilder();

        for (String ligne : lignes) {
            String ligneModifiee = ligne;
            
            for (String asset : assets) {
                // Regex blindée contre les légères variations de mise en forme de l'IA
                String patternStr = "^\\s*[•\\-*]?\\s*\\S*\\s*" + Pattern.quote(asset) + "\\s*:.*";
                
                if (ligne.matches(patternStr)) {
                    MarketDataFetcher.MarketData data = liveDataMap.get(asset);
                    if (data != null && data.price > 0) {
                        String sign = (data.changePercent >= 0) ? "+" : "";
                        String badgeMarche = String.format(Locale.US,
                            " (%.4f | %s%.2f%%)", data.price, sign, data.changePercent);
                        
                        // Insertion propre juste avant les deux-points explicatifs
                        int indexColon = ligne.indexOf(":");
                        if (indexColon != -1) {
                            ligneModifiee = ligne.substring(0, indexColon) + badgeMarche + " :" + ligne.substring(indexColon + 1);
                        } else {
                            ligneModifiee = ligne + badgeMarche;
                        }
                    }
                    break; // Un seul actif traitable par ligne de liste
                }
            }
            reportAjuste.append(ligneModifiee).append("\n");
        }
        return reportAjuste.toString().trim();
    } catch (Exception e) {
        Log.e(TAG, "Erreur lors de l'injection des prix live", e);
        return groqReport;
    }
}

    private void processAnalysisWithAI(final String sourceName, final String title, final String body, final List<String> enrichedAssets, final String fingerprint, final String customSystemPrompt, final boolean isSupremeRank){
        // 1. Intégration de votre SYSTEM_PROMPT (Le moule et les contraintes strictes)
    final String systemPrompt = (customSystemPrompt != null && !customSystemPrompt.isEmpty())
       ? customSystemPrompt
       : SYSTEM_PROMPT
    ;
    // 2. Génération dynamique de l'horodatage actuel au format de Madagascar (EAT)
    java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("dd/MM/yyyy HH:mm:ss", java.util.Locale.FRANCE);
    sdf.setTimeZone(java.util.TimeZone.getTimeZone("Indian/Antananarivo"));
    String currentMadaTime = sdf.format(new java.util.Date());

    // Sécurisation anti-NullPointerException de la liste des actifs
    String assetsString = (enrichedAssets != null) ? enrichedAssets.toString() : "[]";

    // ✅ CORRECTION 1 : Rendre 'userContent' FINAL pour permettre sa lecture sécurisée dans le Thread d'arrière-plan
    final String userContent = "CONTEXTE TEMPOREL : " + currentMadaTime + "\n"
            + "SOURCE DE LA NEWS : " + sourceName + "\n"
            + "TITRE : " + title + "\n"
            + "CORPS DE LA NOTIFICATION : " + body + "\n"
            + "ACTIFS PRÉ-QUALIFIÉS : " + assetsString;

    Executors.newSingleThreadExecutor().execute(new Runnable() {
        @Override
        public void run() {
            java.net.HttpURLConnection conn = null;
            EventDatabase db = EventDatabase.getInstance(NotificationService.this);
            if (db == null || fingerprint == null) {
                Log.e(TAG, "Instance SQLite ou empreinte manquante. Avortement du pipeline.");
                return;
            }
    
            try {
                List<String> historique = db.obtenirTexteEvenementsRecents();
                String promptFinal = construirePromptFinalAvecPrompt(body, historique, systemPrompt);
                JSONObject jsonPayload = new JSONObject();
                jsonPayload.put("model", GROQ_MODEL);
                jsonPayload.put("temperature", 0.02);
    
                JSONArray messages = new JSONArray();
                messages.put(new JSONObject().put("role", "system").put("content", promptFinal));
                messages.put(new JSONObject().put("role", "user").put("content", userContent));
                jsonPayload.put("messages", messages);
    
                String apiKey = getGroqApiKey();
                if (apiKey.isEmpty()) {
                    Log.e(TAG, "[GROQ] Clé API absente. Analyse annulée.");
                    db.markEventAsSynced(fingerprint, "FAILED_MISSING_API_KEY");
                    return;
                }
    
                java.net.URL url = new java.net.URL(GROQ_URL);
                conn = (java.net.HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setRequestProperty("Authorization", "Bearer " + apiKey);
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(15000);
    
                try (java.io.OutputStream os = conn.getOutputStream()) {
                    byte[] input = jsonPayload.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    os.write(input, 0, input.length);
                    os.flush();
                }
    
                int status = conn.getResponseCode();
                if (status == java.net.HttpURLConnection.HTTP_OK) {
                    StringBuilder response = new StringBuilder();
                    try (java.io.BufferedReader br = new java.io.BufferedReader(
                            new java.io.InputStreamReader(conn.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = br.readLine()) != null) {
                            response.append(line);
                        }
                    }
    
                    JSONObject jsonResponse = new JSONObject(response.toString());
                    String aiReport = jsonResponse.getJSONArray("choices")
                            .getJSONObject(0)
                            .getJSONObject("message")
                            .getString("content");
    
                    if (aiReport == null || aiReport.length() < 50) {
                        Log.w(TAG, "[GROQ] Rapport reçu trop court ou vide.");
                        db.markEventAsSynced(fingerprint, "FAILED_EMPTY_LLM_REPORT");
                        return;
                    }
    
                    // Filtrage intelligent des signaux d'impacts macroéconomiques
                    StringBuilder filteredMessage = new StringBuilder();
                    String[] lines = aiReport.split("\n");
                    int activeSignalsCount = 0;
                    boolean inImpactSection = false;
    
                    for (String line : lines) {
                        String trimmed = line.trim();
                        if (trimmed.isEmpty()) continue;
                        if (trimmed.startsWith("🚨") || trimmed.startsWith("📊") || trimmed.startsWith("🎯") ||
                            trimmed.startsWith("📢") || trimmed.startsWith("🏁") || trimmed.startsWith("--- IMPACTS")) {
                            filteredMessage.append(line).append("\n");
                            if (trimmed.startsWith("--- IMPACTS")) inImpactSection = true;
                            continue;
                        }
                        if (inImpactSection && trimmed.startsWith("•")) {
                            String upperLine = line.toUpperCase(Locale.ROOT);
                            boolean isInclinationNeutral = upperLine.contains("MAIS NEUTRE");
                            boolean isSignificant = !isInclinationNeutral &&
                             (upperLine.contains("BULLISH") || upperLine.contains("BEARISH"));
                                if (isSignificant) {
                                    filteredMessage.append(line).append("\n");
                                    activeSignalsCount++;
                                }
                        }
                    }
    
                    // ✅ Application du filtre conviction
                    if (activeSignalsCount > 0) {
                    int convictionPercent = extrairePourcentageConviction(aiReport);
                      if (convictionPercent >= 40 || isSupremeRank) {
                            String finalPayload = "⚡ *ANALYSE MACRO ÉCONOMIQUE*\n" + filteredMessage.toString().trim();
                            sendTelegramSecure(finalPayload, NotificationService.this);
                            db.markEventAsSynced(fingerprint, "PROCESSED_OK");
                        } else {
                            Log.d(TAG, "Conviction trop faible (" + convictionPercent + "%) et non suprême → message ignoré");
                            db.markEventAsSynced(fingerprint, "LOW_CONVICTION_FILTERED");
                        }
                    } else {
                        db.markEventAsSynced(fingerprint, "FILTERED_ALL_NEUTRAL");
                    }
                } else {
                    Log.e(TAG, "[GROQ] Erreur de serveur HTTP Code : " + status);
                    db.markEventAsSynced(fingerprint, "FAILED_SERVER_HTTP_" + status);
                }
            } catch (Exception e) {
                Log.e(TAG, "[GROQ] Échec lors de l'exécution réseau / SQLite", e);
                if (db != null) {
                    try {
                        db.markEventAsSynced(fingerprint, "FAILED_CRITICAL_EXCEPTION");
                    } catch (Exception ex) {
                        Log.e(TAG, "Impossible de forcer la mise à jour du verrou SQLite", ex);
                    }
                }
            } finally {
                if (conn != null) conn.disconnect();
            }
        }
        });    
    }
    // Point 5 : Déconnexion sécurisée encapsulée dans un bloc finally
    public static void sendTelegramSecure(String message, Context context) {
        new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                android.content.SharedPreferences prefs = context.getSharedPreferences("TradingBot", Context.MODE_PRIVATE);
                String token  = prefs.getString("tg_token", "");
                String chatId = prefs.getString("tg_chat_id", "");

                if (token.isEmpty() || chatId.isEmpty()) return;

                URL url = new URL("https://api.telegram.org/bot" + token + "/sendMessage");
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                conn.setDoOutput(true);
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(8000);

                JSONObject payload = new JSONObject();
                payload.put("chat_id", chatId);
                payload.put("text", message);
                payload.put("parse_mode", "Markdown");

                OutputStream os = conn.getOutputStream();
                os.write(payload.toString().getBytes("UTF-8"));
                os.flush();
                os.close();

                conn.getResponseCode();
            } catch (Exception e) {
                Log.e(TAG, "Échec Telegram POST", e);
            } finally {
                if (conn != null) conn.disconnect();
            }
        }).start();
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        // 1️⃣ Vérification de l'état d'activation du bot (Doit être ultra-rapide sur le thread UI)
        if (!getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getBoolean("bot_active", false)) return;
    
        // Détection et filtrage immédiat des packages sources autorisés
        String packageName = sbn.getPackageName().toLowerCase(Locale.ROOT);
        String sourceName = "Source Institutionnelle";
        if (packageName.contains("financialjuice")) {
        sourceName = "FinancialJuice";
        } else if (packageName.contains("nikkei")) {
            sourceName = "TradingEconomics";
        } else if (packageName.contains("forex.portal")) {
            sourceName = "Myfxbook";
        } else if (packageName.contains("twitter") || packageName.contains("periscope")) {
            sourceName = "X /Twitter";
        } else if (packageName.contains("chrome") || packageName.equals("com.android.chrome")) {
            sourceName = "Chrome";
        } else {
            return; // Ignore immédiatement tout le reste
        }
    
        // Extraction sécurisée des chaînes de caractères brutes fournies par Android
        Bundle extras = sbn.getNotification().extras;
        final String title = extras.getString(Notification.EXTRA_TITLE, "");
        String bigText = extras.getString(Notification.EXTRA_BIG_TEXT, "");
        String text = extras.getString(Notification.EXTRA_TEXT, "");
    
        // 🔴 SÉCURITÉ REGEX : Conservation du texte brut le plus complet pour l'extraction mathématique d'EconomicAnalyzer
        final String bodyTextRaw = bigText.length() > text.length() ? bigText : text;
    
        // Reconstruction standard du flux texte unifié pour le reste des modules
        String subText = extras.getString(Notification.EXTRA_SUB_TEXT, "");
        String summary = extras.getString(Notification.EXTRA_SUMMARY_TEXT, "");
        String tempBody = bodyTextRaw;
        if (subText.length() > tempBody.length()) tempBody = subText;
        if (summary.length() > tempBody.length()) tempBody = summary;
    
        String tempUnifiedFeed = (title + " " + tempBody).trim();
        CharSequence[] lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);
        if (lines != null && lines.length > 0) {
            StringBuilder bundled = new StringBuilder(title).append(" ");
            for (CharSequence line : lines) {
                if (line != null) bundled.append(line).append(" ");
            }
            String bundledFeed = bundled.toString().trim();
            if (bundledFeed.length() > tempUnifiedFeed.length()) {
                tempUnifiedFeed = bundledFeed;
            }
        }
    
        if (tempUnifiedFeed.length() < 6) return;
        
        final String finalUnifiedFeed = tempUnifiedFeed;
        final String finalSourceName = sourceName;
        final long postTimeMs = sbn.getPostTime();
    
        // 2️⃣ BASCOULEMENT IMMÉDIAT ET ISOLÉ DANS LE PIPELINE ASYNCHRONE (THREAD D'ARRIÈRE-PLAN)
        // Utilisation de l'exécuteur existant de la classe pour stabiliser la RAM et le CPU
        tradingPipelineExecutor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    String upperFeed = finalUnifiedFeed.toUpperCase(Locale.ROOT);
                    long currentTime = System.currentTimeMillis();
                    String currentSpeaker = "";
    
                    // Identification des Speakers de Banques Centrales
                    if (upperFeed.contains("POWELL") || upperFeed.contains("WARSH") || upperFeed.contains("WALLER") ||
                        upperFeed.contains("BARKIN") || upperFeed.contains("GOOLSBEE") || upperFeed.contains("WILLIAMS") ||
                        upperFeed.contains("KUGLER") || upperFeed.contains("BOSTIC") || upperFeed.contains("DALY") ||
                        upperFeed.contains("LOGAN") || upperFeed.contains("FED")) {
                        currentSpeaker = "FED";
                    } else if (upperFeed.contains("LAGARDE") || upperFeed.contains("SCHNABEL") || upperFeed.contains("NAGEL")) {
                        currentSpeaker = "ECB";
                    }
    
                    // Détection sémantique/lexicale des types d'événements (Drivers macro & géopolitique)
                   // Détection sémantique/lexicale des types d'événements (Drivers macro & géopolitique)
                    // Détection sémantique/lexicale des types d'événements (Drivers macro & géopolitique)
                    // 🔁 BUG #2 FIX : Branchement du détecteur riche EconomicEventDetector (26 catégories : Warsh, ISM,
                    // PMI Flash, Michigan, GDP-Advance, tariffs, etc.) qui était orphelin dans processIncomingMacroFeed()
                    // (jamais appelée). On l'utilise désormais comme source de vérité pour eventTypeStr.
                    EconomicEventDetector.DetectedEvent detectedEvt = EconomicEventDetector.detectEvent(title, finalUnifiedFeed);
                    String eventTypeStr = detectedEvt.eventType;
                    boolean isSupremeRank = false;

                    // 🛡️ Préservation de TOUTE la logique géo en aval (allocation d'actifs, bypass throttle, seuils)
                    // EconomicEventDetector retourne des types granulaires (GEO-MIDDLE-EAST, GEO-EUROPE-EST,
                    // GEO-ASIA-PACIFIC) là où l'ancien code inline utilisait un seul marqueur générique "GEOPOLITICAL".
                    // On normalise ici pour ne rien casser dans le reste de la méthode.
                    boolean isGeoDetected = eventTypeStr.startsWith("GEO-")
                            || upperFeed.contains("HORMUZ") || upperFeed.contains("ORMUZ") || upperFeed.contains("IRAN") || upperFeed.contains("ISRAEL")
                            || upperFeed.contains("HEZBOLLAH") || upperFeed.contains("HOUTHI") || upperFeed.contains("GAZA") || upperFeed.contains("LEBANON")
                            || upperFeed.contains("MOYEN-ORIENT") || upperFeed.contains("MIDDLE EAST") || upperFeed.contains("WAR") || upperFeed.contains("STRIKE")
                            || upperFeed.contains("FRAPPE") || upperFeed.contains("ESCALADE") || upperFeed.contains("CONFLIT") || upperFeed.contains("MILITARY")
                            || upperFeed.contains("TAIWAN") || upperFeed.contains("UKRAINE") || upperFeed.contains("RUSSIA");

                    if (isGeoDetected) {
                        eventTypeStr = "GEOPOLITICAL"; // Conserve le marqueur exact attendu par toute la logique en aval
                        isSupremeRank = false;
                    } else if (upperFeed.contains("OIL") || upperFeed.contains("WTI") || upperFeed.contains("BRENT") || upperFeed.contains("CRUDE") ||
                               upperFeed.contains("EIA") || upperFeed.contains("OPEC") || upperFeed.contains("INVENTORIES") || upperFeed.contains("PETROLE")) {
                        // Cas non couvert nommément par EconomicEventDetector → on garde le comportement historique
                        eventTypeStr = "OIL-INVENTORY";
                    }
                    // Sinon : eventTypeStr reste la valeur riche fournie par EconomicEventDetector
                    // (ex: FED-WARSH-SIGNAL, INFLATION-DATA, ISM-INDICATOR, PMI-FLASH, MICHIGAN-SENTIMENT,
                    // GDP-ADVANCE, TRADE-TARIFF, CHINA-MACRO, etc.)
    
                    // 3️⃣ SYNCHRONISATION MACRO DÉTERMINISTE avec enrichissement calendaire
                    // Enrichir le contenu avec les données du calendrier (ACTUAL/FORECAST) si disponibles
                    String enrichedBody = EventValidator.enrichWithCalendar(title, bodyTextRaw, postTimeMs);
                    EconomicAnalyzer.EvaluationResult ecoResult = EconomicAnalyzer.analyserEvenement(title, enrichedBody);
                    Log.d(TAG, "Devise détectée : " + ecoResult.currency + ", poids : " + ecoResult.weight);
                    // Le poids n'est plus forcé à 5 ou 3 statiquement, il découle de la surprise de l'écart mathématique (1 à 4)
                    int finalCalculatedWeight = ecoResult.weight;
                    // Ajustement du pavillon suprême selon le verdict de l'analyseur mathématique ou de l'urgence géopolitique
                    // 🆕 Exploite désormais detectedEvt.getRawImpact() (HIGH/MEDIUM/LOW/NEUTRE) en plus du score
                    // mathématique d'EconomicAnalyzer, pour que les types riches (Warsh, ISM, PMI Flash, Michigan,
                    // GDP-Advance) déclenchent eux aussi le statut suprême même si le calcul de poids reste bas.
                    String rawImpact = detectedEvt.getRawImpact();
                    if (finalCalculatedWeight >= 3 || currentSpeaker.equals("FED") || eventTypeStr.equals("GEOPOLITICAL")
                            || "HIGH".equals(rawImpact) || "FED-WARSH-SIGNAL".equals(detectedEvt.eventType)) {
                        isSupremeRank = true;
                    }
    
                    // 4️⃣ Anti-spam / Protection contre les flux de paroles répétitifs des speakers
                    String speakerToken = currentSpeaker.trim();
                    if (!speakerToken.isEmpty()) {
                        if (!isSupremeRank && speakerToken.equals(lastSpeaker) && (currentTime - lastSpeechTime < 60000)) {
                            Log.d(TAG, "Doublon de discours filtré (" + speakerToken + ")");
                            return;
                        }
                        lastSpeechTime = currentTime;
                        lastSpeaker = speakerToken;
                    }
                    // 5️⃣ Matrice de ciblage et d'allocation des Actifs Financiers (Thread-safe via liste locale)
                    List<String> enrichedAssets = new ArrayList<>();
                    if (upperFeed.contains("EUR") || upperFeed.contains("ECB") || upperFeed.contains("LAGARDE")) enrichedAssets.add("EURUSD");
                    if (upperFeed.contains("JPY") || upperFeed.contains("YEN") || upperFeed.contains("BOJ")) enrichedAssets.add("USDJPY");
                    if (upperFeed.contains("GBP") || upperFeed.contains("BOE")) enrichedAssets.add("GBPUSD");
                    if (upperFeed.contains("AUD") || upperFeed.contains("RBA")) enrichedAssets.add("AUDUSD");
                    if (upperFeed.contains("CAD") || upperFeed.contains("BOC")) enrichedAssets.add("USDCAD");
                    if (upperFeed.contains("GOLD") || upperFeed.contains("XAU")) enrichedAssets.add("GOLD");
                    if (upperFeed.contains("NASDAQ") || upperFeed.contains("TECH") || upperFeed.contains("AI")) enrichedAssets.add("NASDAQ");
                    if (upperFeed.contains("SP500") || upperFeed.contains("S&P")) enrichedAssets.add("SP500");
                    if (upperFeed.contains("BITCOIN") || upperFeed.contains("BTC")) enrichedAssets.add("BITCOIN");
    
                    // Association contextuelle Pétrole / Risque d'approvisionnement (Hormuz)
                    if (upperFeed.contains("OIL") || upperFeed.contains("WTI") || upperFeed.contains("CRUDE") || 
                        upperFeed.contains("EIA") || upperFeed.contains("HORMUZ") || upperFeed.contains("ORMUZ")) {
                        if (!enrichedAssets.contains("USOIL")) enrichedAssets.add("USOIL");
                        if (!enrichedAssets.contains("USDCAD")) enrichedAssets.add("USDCAD");
                        if (!enrichedAssets.contains("GOLD")) enrichedAssets.add("GOLD");
                    }
    
                    // Profil d'allocation en Régime de Crise Géopolitique
                    if (eventTypeStr.equals("GEOPOLITICAL")) {
                        String[] geoAssets = {"GOLD", "USOIL", "USDJPY", "US10Y", "NASDAQ", "SP500"};
                        for (String asset : geoAssets) {
                            if (!enrichedAssets.contains(asset)) enrichedAssets.add(asset);
                        }
                    }
    
                    // Profil d'allocation standard lors des chocs macroéconomiques majeurs
                    if (isSupremeRank && !eventTypeStr.equals("GEOPOLITICAL")) {
                        String[] macroAssets = {"US10Y", "NASDAQ", "SP500", "GOLD", "EURUSD", "USDJPY", "BITCOIN"};
                        for (String asset : macroAssets) {
                            if (!enrichedAssets.contains(asset)) enrichedAssets.add(asset);
                        }
                    }
    
                    // Panier de secours par défaut si aucun mot-clé d'actif n'a matché
                    if (enrichedAssets.isEmpty()) {
                        enrichedAssets.add("NASDAQ");
                        enrichedAssets.add("SP500");
                        enrichedAssets.add("US10Y");
                    }
    
                    // 6️⃣ Validation de cohérence temporelle et historique via EventValidator
                    EventValidator.ValidationResult validationResult = EventValidator.validate(NotificationService.this, title, bodyTextRaw, currentTime, enrichedAssets);
                    
                    // Log dans l'Ui
                    if (MainActivity.instance != null) {
                    MainActivity.instance.addLog(finalSourceName + ": " + (validationResult.isConfirmed ? "CONFIRMÉ" : "REJETÉ") + " - " + validationResult.reason);
                    }
                    // Coupe-circuit du Validateur : On bloque les doublons temporels, sauf s'il s'agit d'un choc absolu de poids 4
                    if (validationResult != null && !validationResult.isConfirmed) {
        // Cas particulier : inertie macro (driver déjà actif) → on envoie un rappel Telegram
        if (validationResult.isInertiaBlock) {
            String reminderMsg = "⏳ *RAPPEL : DRIVER DÉJÀ ACTIF*\n" +
                                 "🔹 " + validationResult.reason + "\n\n" +
                                 "📋 *Dernier événement similaire :*\n" +
                                 validationResult.lastEventSummary;
            sendTelegramSecure(reminderMsg, NotificationService.this);
            Log.d(TAG, "[RAPPEL] Driver actif : rappel envoyé.");
            return; // On arrête le traitement normal
        }
        // Pour les autres cas de rejet (doublon, rumeur, faible confiance, etc.)
        if (finalCalculatedWeight < 4) {
            Log.d(TAG, "[COUPE-CIRCUIT TIMING] Événement rejeté : " + validationResult.reason);
            return;
        }
        }

                // 7️⃣ RÈGLE DE QUALIFICATION MINIMALE DU PIPELINE : Seuil fixé à 3 pour valider les drivers confirmés
                if (finalCalculatedWeight < 3 && !eventTypeStr.equals("GEOPOLITICAL")) {
                    Log.d(TAG, "[COUPE-CIRCUIT MACRO] Impact mathématique insuffisant (" + finalCalculatedWeight + "). Fin de tâche.");
                    return; // Stoppe le traitement lourd et évite l'appel à Groq pour du bruit de fond conformes aux attentes
                }

                // 8️⃣ Génération de la signature cryptographique et persistance SQLite en base de données
                String fingerprint = generateSecureHash(packageName + "_" + title + "_" + bodyTextRaw + "_" + (postTimeMs / 60000));

                StringBuilder assetsSb = new StringBuilder();
                for (int i = 0; i < enrichedAssets.size(); i++) {
                    assetsSb.append(enrichedAssets.get(i));
                    if (i < enrichedAssets.size() - 1) assetsSb.append(",");
                }

                // Sauvegarde de l'événement en base de données persistante avec injection du vrai poids macro calculé
                boolean saved = eventDb.saveEvent(
                        fingerprint, packageName, finalSourceName, eventTypeStr, title, bodyTextRaw,
                        assetsSb.toString(), "pending", postTimeMs / 1000, "pending", finalCalculatedWeight);

                if (saved) {
                    Log.i(TAG, "[DATABASE] Match " + eventTypeStr + " enregistré avec succès. Poids affecté : " + finalCalculatedWeight);
                }

                // 9️⃣ Enrichissement dynamique et forcé du Prompt Système IA avec les flèches théoriques de l'analyseur
                // 9️⃣ Enrichissement dynamique du Prompt Système IA
                // 9️⃣ ENRICHISSEMENT MARKET DATA & PROMPT IA (Pipeline Intégré)
                // 📋 IA (Pipeline Intégré) : Préparation du Snapshot Marché Temps Réel
String marketSnapshot = "Marché non analysé.";
try {
    // Récupération globale instantanée de tous les actifs en 1 seul appel Batch
    java.util.Map<String, MarketDataFetcher.MarketData> batchSnapshot = 
        MarketDataFetcher.getMarketDataBatch(enrichedAssets);

    if (batchSnapshot != null && !batchSnapshot.isEmpty()) {
        StringBuilder sb = new StringBuilder("Données de marché (Live Batch) : ");
        boolean premierActif = true;

        for (java.util.Map.Entry<String, MarketDataFetcher.MarketData> entry : batchSnapshot.entrySet()) {
            MarketDataFetcher.MarketData mData = entry.getValue();
            
            // 🛡️ MULTI-PROTECTION : Filtrage préventif des valeurs nulles ou prix aberrants (<= 0)
            if (mData == null || mData.price <= 0) {
                Log.w(TAG, "Snapshot - Actif ignoré car données invalides ou nulles : " + entry.getKey());
                continue; 
            }

            // ✂️ SUPPRESSION DU SÉPARATEUR DE FIN : Ajouté uniquement avant les éléments suivants
            if (!premierActif) {
                sb.append(" | ");
            }
            premierActif = false;

            String sign = (mData.changePercent >= 0) ? "+" : "";
            sb.append(entry.getKey())
              .append(" => ")
              .append(String.format(Locale.US, "%.4f (%s%.2f%%)", mData.price, sign, mData.changePercent));
        }

        // Vérification finale au cas où TOUX les actifs auraient été rejetés par le filtre de sécurité
        if (premierActif) {
            marketSnapshot = "Données de marché indisponibles (aucun prix valide extrait).";
        } else {
            marketSnapshot = sb.toString();
        }
    } else {
        marketSnapshot = "Données de marché indisponibles (Twelve Data hors-ligne ou limite atteinte).";
    }
} catch (Exception e) {
    Log.e(TAG, "Échec de la génération du snapshot marché", e);
    marketSnapshot = "Erreur technique lors de l'acquisition des données.";
}

                // Construction du prompt enrichi
                String baseSystemPrompt = SYSTEM_PROMPT;
                String promptAI = "📊 [CONTEXTE MARCHÉ ACTUEL] : " + marketSnapshot + "\n\n" + baseSystemPrompt;

                if (ecoResult.isParsed) {
                    promptAI = "⚠️ [GUIDAGE MATRICIEL INTERNE] : \n" +
                            "L'analyseur mathématique déterministe a détecté un écart type. " +
                            "Direction recommandée : " + ecoResult.directionText + "\n\n" + promptAI;
                }

                // 🔟 Exécution finale de l'analyse cognitive LLM
                processAnalysisWithAI(finalSourceName, title, bodyTextRaw, enrichedAssets, fingerprint, promptAI, isSupremeRank);

            } catch (Exception e) {
                Log.e(TAG, "Erreur critique au sein de l'exécution asynchrone de la pipeline", e);
            }
        }
        });
    }

  public static void sendToGroqAndTelegram(String source, String title, String body, List<String> assets, Context context) {
    if (context == null) return;
    String fingerprint = String.valueOf((source + title + body).hashCode());
    NotificationService instance = serviceInstance;

    // ✅ Sauvegarder dans SQLite pour inclusion dans le Daily Report (INCHANGÉ)
    // ✅ Ne pas re-sauvegarder si déjà en DB — updateActualIfMissing l'a déjà mis à jour
// Sauvegarder uniquement si vraiment absent (fingerprint non existant)
      // 2. Gestion intelligente des actifs (Priorité aux paramètres, repli sur la liste globale)
    List<String> finalAssetsList = (assets != null && !assets.isEmpty()) ? assets : new ArrayList<>(Arrays.asList(
        "GOLD","NASDAQ","SP500","BITCOIN","EURUSD",
        "USDJPY","GBPUSD","AUDUSD","USDCAD","USOIL","US10Y"
    ));
    // Conversion de la Liste en String pour correspondre au schéma SQLite
    String assetsStr = String.join(",", finalAssetsList);
if (!instance.eventDb.isEventAlreadySaved(title, System.currentTimeMillis() / 1000)) {
    int dynamicWeight = EconomicCalendarAPI.isSupremeCalendarIndicator(title) ? 5 : 3;
    instance.eventDb.saveEvent(
        fingerprint, "com.tradingbot.calendar", source,
        "CALENDAR-RESULT", title, body, assetsStr,
        "pending", System.currentTimeMillis() / 1000,
        "pending", dynamicWeight
    );
}
   // 🚀 INJECTION : Déportation réseau simplifiée et ultra-rapide via Batch API
new Thread(new Runnable() {
    @Override
    public void run() {
        try {
            StringBuilder blocPrix = new StringBuilder();

            if (assets != null && !assets.isEmpty()) {
                blocPrix.append("\n\n📊 *COURS INSTANTANÉS AU MOMENT DE L'IMPACT :*");
                
                // Un seul appel réseau unifié pour TOUS les actifs qualifiés simultanément
                java.util.Map<String, MarketDataFetcher.MarketData> batchPrices = 
                        MarketDataFetcher.getMarketDataBatch(assets);

                for (String asset : assets) {
                    MarketDataFetcher.MarketData data = batchPrices.get(asset);
                    
                    if (data != null && data.price > 0) {
                        String tendance = data.changePercent >= 0 ? "📈" : "📉";
                        // ✅ Correction Markdown : Remplacement des ** par * pour le parse_mode standard de Telegram
                        String formatPrix = (data.price > 1000) ? "\n%s %s : *%,.2f* (%+.2f%%)" : "\n%s %s : *%.5f* (%+.2f%%)";
                        blocPrix.append(String.format(Locale.US, formatPrix, tendance, asset, data.price, data.changePercent));
                    } else {
                        blocPrix.append("\n🔸 ").append(asset).append(" : (Cours indisponible)");
                    }
                }
            }

            String bodyEnrichi = body + blocPrix.toString();

            if (instance != null) {
                instance.processAnalysisWithAI(source, title, bodyEnrichi, assets, fingerprint, SYSTEM_PROMPT, true);
            } else {
                String msg = "📅 *RÉSULTAT CALENDAIRE*\n📌 *" + title + "*\n📊 " + bodyEnrichi;
                sendTelegramSecure(msg, context);
            }

        } catch (Exception e) {
            Log.e(TAG, "Erreur critique lors de l'enrichissement par Batch API", e);
            // Mode dégradé sécurisé (Fallback) : transmission du corps initial sans bloc de prix
            if (instance != null) {
                instance.processAnalysisWithAI(source, title, body, assets, fingerprint, SYSTEM_PROMPT, true);
            } else {
                String msg = "📅 *RÉSULTAT CALENDAIRE*\n📌 *" + title + "*\n📊 " + body;
                sendTelegramSecure(msg, context);
            }
        }
    }
}).start();
   }

    @Override
    public void onCreate() {
        super.onCreate();
        eventDb = EventDatabase.getInstance(this);
        
        // ── Liaison du contexte pour l'extraction de la clé macro_api_key ──
        EconomicCalendarAPI.init(this);
        EventValidator.setAppContext(this); 
        serviceInstance = this;                 // ✅ Assure la survie de l'instance pour l'IA
        
        // ── Déportation du préchargement réseau dans un thread d'arrière-plan ──
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    EventValidator.preloadCalendar(); 
                    if (System.currentTimeMillis() % (30 * 60 * 1000) < 60000) { // toutes les 30 min
                        Log.d(TAG, "Refresh forcé du calendrier économique");
                    }
                } catch (Exception e) {
                    Log.e(TAG, "[SERVICE] Erreur lors du préchargement du calendrier", e);
                }
            }
        }).start();
    
        createNotificationChannel();
        startDailyBriefScheduler();
        startMonthlyReportScheduler();
        registerNetworkCallback();
        
        // Planification unifiée : purge SQLite + nettoyage RAM + préchargement toutes les 24h à Minuit (Madagascar)
        long initialDelayMillis = calculateMillisUntilNextMadaMidnight();
        long period24HoursMillis = 24 * 60 * 60 * 1000L; 
    
        scheduler.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                if (isSyncing) return;
                isSyncing = true;
                try {
                    Log.d(TAG, "🕒 [MAINTENANCE] Déclenchement automatique de minuit (Heure de Madagascar)...");
                    
                    // 1. Purge SQLite des événements ayant dépassé la fenêtre de validité de 48 heures
                    long thresholdSeconds = (System.currentTimeMillis() - (48 * 60 * 60 * 1000L)) / 1000;
                    eventDb.purgeOldEvents(thresholdSeconds); 
                    Log.d(TAG, "[MAINTENANCE] Base de données SQLite purgée des données > 48h.");
    
                    // 2. Nettoyage de la table des empreintes pour éviter les fuites RAM
                    EventValidator.cleanupOldFingerprints();
                    Log.d(TAG, "[MAINTENANCE] Table des empreintes mémoires RAM nettoyée.");
    
                    // 3. Re-synchronisation du calendrier pour la nouvelle journée
                    EventValidator.preloadCalendar();
                    lastAlertsSent.clear();
                    Log.d(TAG, "[MAINTENANCE] Cooldowns d'alertes réinitialisés.");
                    
                } catch (Exception e) {
                    Log.e(TAG, "[MAINTENANCE] Erreur lors de la maintenance à minuit", e);
                } finally {
                    isSyncing = false;
                }
            }
        }, initialDelayMillis, period24HoursMillis, TimeUnit.MILLISECONDS);
    
        // Rafraîchissement du calendrier toutes les 6 heures (21600000 ms)
        long sixHoursMillis = 6 * 60 * 60 * 1000L;
        scheduler.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                Log.d(TAG, "[CALENDAR] Rafraîchissement périodique du calendrier économique...");
                EventValidator.preloadCalendar();
            }
        }, sixHoursMillis, sixHoursMillis, TimeUnit.MILLISECONDS);
        // --- NOUVEAU : Moniteur de divergence (toutes les 15 minutes) ---
        scheduler.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                try {
                    checkForecastDivergence();
                } catch (Exception e) {
                    Log.e(TAG, "[DIVERGENCE] Erreur critique dans le moniteur", e);
                }
            }
        }, 15, 15, TimeUnit.MINUTES);
    }

    private void processIncomingMacroFeed(String source, String title, String text, String feed, 
                                          String pkg, long postTime, String fingerprint, String promptAI, boolean isSupremeParam) {
        // 1. Nettoyage automatique des empreintes obsolètes au début de chaque cycle
        EventValidator.cleanupOldFingerprints();
    
        // 🛡️ [CIRCUIT BREAKER] Capture des états temporels ORIGINAUX dès l'entrée de la méthode
        final long previousGeoTime = lastGeoTime;
        final long previousAnalysisTime = lastAnalysisTime;

        String heureExacteMada = getMadaFormattedDateTime();
        // 2. Injection du contexte temporel au début de la variable feed avant l'analyse
        feed = "CONTEXTE TEMPOREL : Nous sommes le " + heureExacteMada + " (Heure de Madagascar).\n\n" + feed;
            
        long now = System.currentTimeMillis();
        boolean isGeoEvent = isGeoEvent(feed.toUpperCase(Locale.ROOT));
    
        // Throttle géopolitique prioritaire
        if (isGeoEvent && (now - lastGeoTime < GEO_THROTTLE_MS)) {
            Log.d(TAG, "[THROTTLE] Notification Géo bloquée (12 min) - dernier il y a " + (now - lastGeoTime)/1000 + "s");
            return;
        }
    
        // ⚡ [SUPREME BYPASS] Throttle global uniquement pour les événements non-géo
        if (!isGeoEvent) {
            if (isSupremeParam || isSupremeRank(title)) {
                // Autorisation immédiate pour les chocs majeurs simultanés
                Log.d(TAG, "⚡ [SUPREME BYPASS] Événement majeur simultané autorisé sans restriction temporelle : " + title);
            } else if (now - lastAnalysisTime < GLOBAL_THROTTLE_MS) {
                // Blocage des bruits/données secondaires si la fenêtre de tir globale (8 min) est active
                Log.w(TAG, "⏳ [THROTTLE] Notification instantanée bloquée par le Throttle Global (8 min) pour : " + title);
                return;
            }
        }

        List<String> targetAssets = filterActiveAssets(feed);
        // 🛡️ PROTECTION NPE : Évite un crash fatal dans String.join si aucun actif n'est retourné
        if (targetAssets == null) {
            targetAssets = new ArrayList<>();
        }

        EventValidator.ValidationResult vr = EventValidator.validate(NotificationService.this, title, feed, postTime, targetAssets);
        
        // Détection élargie de TOUTES les actualités majeures
        String upFeed = feed.toUpperCase(Locale.ROOT);
        boolean isSupremeNews = upFeed.contains("FOMC") || upFeed.contains("FED ") || 
                                upFeed.contains("CPI")  || upFeed.contains("PCE")  || 
                                upFeed.contains("NFP")  || upFeed.contains("BCE")  || 
                                upFeed.contains("ECB")  || upFeed.contains("BOJ")  || 
                                upFeed.contains("BOE")  || upFeed.contains("RBA")  || 
                                upFeed.contains("BOC")  || upFeed.contains("PIB")  || 
                                upFeed.contains("GDP")  || upFeed.contains("OPEC") ||
                                upFeed.contains("INFLATION") || upFeed.contains("INTEREST RATE") ||
                                upFeed.contains("POWELL") || upFeed.contains("LAGARDE") || upFeed.contains("WARSH") ||
                                upFeed.contains("PMI") || upFeed.contains("ISM");
    
        int weight = assignDriverWeight(feed);
    
        if (vr.isConfirmed && !vr.geoContext.isEmpty() && vr.confidence >= 70) {
            weight = Math.max(weight, 4);
        }
    
        String hash = generateSecureHash(title + text);
        Log.d(TAG, "🟢 Nouvelle notification : source=" + source + ", title=" + title + ", hash=" + hash);
            
        // Filtre stratégique anti-bruit
        if (!vr.isConfirmed && weight < 3 && !isSupremeNews && !detectDriverDeviation(feed)) {
            eventDb.saveEvent(hash, pkg, source, "Soft-Data", title, feed,
                    String.join(", ", targetAssets), "Conforme (Filtré)", (long)(postTime/1000), "synced", weight);
            return;
        }
    
        // ✅ LE SECOND IF DESTRUCTEUR A ÉTÉ SUPPRIMÉ ICI : Les news suprêmes continuent leur route.
    
        EconomicEventDetector.DetectedEvent detected = EconomicEventDetector.detectEvent(title, feed);
    
        // --- APPLICATION DE LA CONTRAINTE DE FORCE BRUTE GÉOPOLITIQUE ---
        String initialImpact = ""; 
    
        if (!vr.geoContext.isEmpty()) {
            if (targetAssets.contains("USDJPY")) {
                detected.impact = "ACHAT CHOC";
                detected.description = "Dollar Dominance Absolue (Régime de Crise Géo Asie/Moyen-Orient)";
            }
            initialImpact = "🌍 CHOC GÉOPOLITIQUE [" + vr.geoContext + "] — Conviction: " + vr.confidence + "% | " + detected.impact + " (Poids: " + weight + ")";
            lastGeoTime = now;
        } else if (isSupremeNews && (upFeed.contains("FOMC") || upFeed.contains("FED "))) {
            initialImpact = "💥 PIVOT MAJEUR BANQUE CENTRALE | " + detected.description + " | " + detected.impact + " (Poids: " + weight + ")";
            lastAnalysisTime = now;
        } else {
            initialImpact = "⚡ [" + detected.eventType + "] " + detected.description + " | " + detected.impact + " (Poids: " + weight + ")";
            lastAnalysisTime = now;
        } 
    
        Log.d(TAG, "Impact final qualifié : " + initialImpact);
    
        if (vr.geoContext.isEmpty() && !(upFeed.contains("FOMC") || upFeed.contains("FED "))) {
            if (detected.impact != null && (detected.impact.equalsIgnoreCase("Neutre") || detected.impact.toUpperCase().contains("NEUTRE"))) {
                if (weight < 3) {
                    Log.d(TAG, "Événement filtré (Bruit Neutre standard). Annulation.");
                    return;
                }
            }
        }
    
        long timestampSec = System.currentTimeMillis() / 1000;
        int geoWeight = (vr.confidence >= 80) ? 4 : (vr.confidence >= 60) ? 3 : 1;
        int finalWeightForStorage = (!vr.geoContext.isEmpty()) ? geoWeight : weight;

        boolean saved = eventDb.saveEvent(hash, pkg, source, "Macro-Choc", title, feed, String.join(", ", targetAssets), initialImpact, timestampSec, "pending", finalWeightForStorage);
        if (saved && isDeviceOnline()) {
            triggerQueueSynchronization();
        }
    
        // ✅ PIPELINE ASYNCHRONE SÉCURISÉ
        if (weight >= 3 || (weight >= 3 && vr.isConfirmed) || (vr.isConfirmed && vr.confidence >= 70)) {
            Log.d(TAG, "[SIGNAL TRIGGER] Driver majeur qualifié (Poids=" + weight + ") → Préparation du pipeline.");
            
            List<String> listeHistorique = new ArrayList<>();
            try {
                listeHistorique = eventDb.obtenirTexteEvenementsRecents();
            } catch (Exception dbEx) {
                Log.w(TAG, "Impossible de charger l'historique de la DB.", dbEx);
            }
    
            // Verrouillage préventif immédiat pendant la durée du traitement en arrière-plan
            if (isGeoEvent) {
                lastGeoTime = System.currentTimeMillis();
            } else {
                lastAnalysisTime = System.currentTimeMillis();
            }
    
            // Captures finales immuables pour le thread de calcul
            final String currentFeed = feed;
            final String currentSource = source;
            final String currentHash = hash;
            final long currentPostTime = postTime;
            final List<String> assets = targetAssets;
            final List<String> finalHistorique = listeHistorique;
            final boolean finalIsGeo = isGeoEvent;
    
            exec.submit(() -> {
                boolean pipelineSucces = false;
                try {
                    String promptFinal = construirePromptFinalAvecPrompt(currentFeed, finalHistorique, SYSTEM_PROMPT);
                    executeAnalysisPipeline(currentSource, currentFeed, promptFinal, assets, currentPostTime, currentHash);
                    pipelineSucces = true; 
                    
                } catch (Exception e) {
                    Log.e(TAG, "Erreur critique dans le pipeline d'analyse asynchrone", e);
                } finally {
                    // 🛡️ [CIRCUIT BREAKER] Restauration des VRAIS timestamps initiaux en cas d'échec
                    if (!pipelineSucces) {
                        Log.w(TAG, "🔄 [ROLLBACK THROTTLE] Échec du traitement API/Réseau. Libération du verrou temporel d'origine.");
                        if (finalIsGeo) {
                            lastGeoTime = previousGeoTime;
                        } else {
                            lastAnalysisTime = previousAnalysisTime;
                        }
                    }
                }
            });
        }
    }
    
    private int assignDriverWeight(String text) {
        String u = text.toUpperCase();
        
        // CORRECTION ACTIFS CRUCIAUX : Ajout de la détection des synonymes/surnoms institutionnels
        if (u.contains("CPI")            || u.contains("INFLATION")       || u.contains("NFP")          ||
            u.contains("NON-FARM PAYROLLS") || u.contains("FOMC")           || u.contains("INTEREST RATE") ||
            u.contains("RBA")            || u.contains("BOC")             || u.contains("BOJ")           ||
            u.contains("BOE")            || u.contains("ECB")             || u.contains("BCE")           ||
            u.contains("LAGARDE")        || u.contains("BAILEY")          || u.contains("MACKLEM")       ||
            u.contains("BULLOCK")        || u.contains("UEDA")            || u.contains("CABLE")         || 
            u.contains("STERLING")       || u.contains("AUSSIE")          || u.contains("LOONIE")        ||
            u.contains("WARSH")          || u.contains("POWELL")) return 5;
            
        if (u.contains("GDP")                || u.contains("PIB")                    ||
            u.contains("RETAIL SALES")       || u.contains("EMPLOYMENT RATE")        ||
            u.contains("STOCKS")             || u.contains("JOBLESS")                ||
            u.contains("ADP")                || u.contains("JOLTS")                  ||
            u.contains("JOB OPENINGS")       || u.contains("PPI")                    ||
            u.contains("PRODUCER PRICE")     || u.contains("DURABLE GOODS")          ||
            u.contains("TRADE BALANCE")      || u.contains("CURRENT ACCOUNT")        ||
            u.contains("INDUSTRIAL PRODUCTION") || u.contains("CAPACITY UTILIZATION") ||
            u.contains("PHILLY FED")         || u.contains("EMPIRE STATE")           ||
            u.contains("CHICAGO PMI")        || u.contains("BEIGE BOOK")             ||
            u.contains("PERSONAL SPENDING")  || u.contains("PERSONAL INCOME")        ||
            u.contains("HOUSING STARTS")     || u.contains("BUILDING PERMITS")       ||
            u.contains("WTI")                || u.contains("BRENT")                  || 
            u.contains("CRUDE OIL")          || u.contains("XAUUSD")                 ||
            u.contains("HOME SALES")         || u.contains("CHALLENGER")) return 4;
            
        if (u.contains("PMI")               || u.contains("ISM")                  ||
            u.contains("MICHIGAN")          || u.contains("CONSUMER CONFIDENCE")   ||
            u.contains("CONSUMER SENTIMENT")|| u.contains("IMPORT PRICE")          ||
            u.contains("NAS100")            || u.contains("SPX")                  ||
            u.contains("US500")             || u.contains("USTECH")                ||
            u.contains("EXPORT PRICE")      || u.contains("NATURAL GAS")) return 3;
            
        return 1;
    }

    private boolean detectDriverDeviation(String text) {
        String upper = text.toUpperCase();
        if (upper.contains("HIGHER THAN EXPECTED") || upper.contains("LOWER THAN EXPECTED") ||
            upper.contains("ABOVE FORECAST") || upper.contains("BELOW FORECAST") ||
            upper.contains("SURPRISE") || upper.contains("SHOCK") || upper.contains("MISSES")) return true;

        Pattern pattern = Pattern.compile("(ACTUAL|ACT):?\\s*([\\d\\.\\-%]+).*?(FORECAST|EST|EXP):?\\s*([\\d\\.\\-%]+)");
        Matcher matcher = pattern.matcher(upper);
        if (matcher.find()) {
            try {
                double actual = Double.parseDouble(matcher.group(2).replaceAll("[^\\d\\.]", ""));
                double forecast = Double.parseDouble(matcher.group(4).replaceAll("[^\\d\\.]", ""));
                return actual != forecast;
            } catch (Exception e) { return true; }
        }
        return false;
    }

    private synchronized void triggerQueueSynchronization() {
        if (isSyncing || !isDeviceOnline()) return;
        isSyncing = true;

        exec.submit(() -> {
            Cursor cursor = null;
            try {
                long now = System.currentTimeMillis() / 1000;
                fetchMissingDataFromInstitutionalAPI();

                cursor = eventDb.getUnsyncedEvents(now);
                if (cursor != null && cursor.moveToFirst()) {
                    do {
                        String fingerprint = cursor.getString(cursor.getColumnIndexOrThrow("fingerprint"));
                        String source = cursor.getString(cursor.getColumnIndexOrThrow("source"));
                        String feed = cursor.getString(cursor.getColumnIndexOrThrow("feed_content"));
                        String assetsStr = cursor.getString(cursor.getColumnIndexOrThrow("target_assets"));
                        long timestamp = cursor.getLong(cursor.getColumnIndexOrThrow("unix_timestamp")) * 1000;

                        List<String> assets = Arrays.asList(assetsStr.split(", "));
                        String historyContext = eventDb.getRecentEventsForAssets(assets, 5);

                        boolean success = executeAnalysisPipeline(source, feed, historyContext, assets, timestamp, fingerprint);
                        if (!success) {
                            Log.w(TAG, "Échec de traitement du nœud : " + fingerprint);
                        }

                    } while (cursor.moveToNext());
                }
            } catch (Exception e) {
                Log.e(TAG, "Erreur synchronisation réseau", e);
            } finally {
                if (cursor != null) cursor.close();
                isSyncing = false;
            }
        });
    }

    // Point 6 : Connexion fermée de manière étanche dans le bloc finally
    private void fetchMissingDataFromInstitutionalAPI() {
        // 🟢 1. Récupération sécurisée des événements historiques (7 jours)
        List<EconomicCalendarAPI.CalendarEvent> historicalEvents = EconomicCalendarAPI.fetchHistoricalEvents(this, 7);
        
        // 🟢 2. Initialisation du StringBuilder qui manquait au compilateur
        StringBuilder apiMacroBlock = new StringBuilder();
        
        for (EconomicCalendarAPI.CalendarEvent e : historicalEvents) {
            if (e == null) continue;
        
            // Vérification des critères d'impact et de publication des chiffres
            if (e.importance != null && e.importance.equalsIgnoreCase("HIGH") && e.actual != null && e.forecast != null) {
                
                String dateStr = e.timestamp; 
                String countryOrCurrency = (e.country != null) ? e.country : "USD";
                
                // 🟢 FIX : Remplacement de logToMain par le Log d'Android standard
                Log.i("NotificationService", String.format("High Impact Event: %s, Region: %s, Event: %s, Act: %s, Fcst: %s",
                        dateStr, countryOrCurrency, e.indicator, e.actual, e.forecast));
        
                // Formatage de la ligne pour le bloc macro envoyé à Groq
                apiMacroBlock.append("- ")
                            .append(e.indicator)
                            .append(" (").append(countryOrCurrency).append(") | ")
                            .append("Actual: ").append(e.actual).append(" | ")
                            .append("Forecast: ").append(e.forecast).append("\n");
            }
        }
        
        // 🟢 3. Envoi groupé à l'analyse Groq si des données sont présentes
        if (apiMacroBlock.length() > 0) {
            dispatchHistoricalBulkToGroq(apiMacroBlock.toString());
        }
        if (apiMacroBlock.length() > 0) {
            dispatchHistoricalBulkToGroq(apiMacroBlock.toString());
        }
    }

    private void dispatchHistoricalBulkToGroq(String bulkData) {
        HttpURLConnection conn = null;
        try {
            String apiKey = getGroqApiKey();
            if (apiKey.isEmpty()) return;

            JSONObject payload = new JSONObject();
            payload.put("model", GROQ_MODEL);
            payload.put("temperature", 0.1);

            JSONArray messages = new JSONArray();
            messages.put(new JSONObject().put("role", "system").put("content", "Tu es un Macro-Strategist de premier plan. Analyse ce relevé complet de données à fort impact."));
            messages.put(new JSONObject().put("role", "user").put("content", "DONNÉES EXTRAITES :\n" + bulkData));
            payload.put("messages", messages);

            URL url = new URL(GROQ_URL);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + apiKey);
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(15000);
            conn.setDoOutput(true);

            OutputStream os = conn.getOutputStream();
            os.write(payload.toString().getBytes("UTF-8"));
            os.flush();
            os.close();

            if (conn.getResponseCode() == 200) {
                BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
                StringBuilder r = new StringBuilder();
                String l;
                while ((l = br.readLine()) != null) r.append(l);
                br.close();

                String analysis = new JSONObject(r.toString()).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content");
                sendTelegramSecure("🚨 *RAPPORT CRITIQUE DE RATTRAPAGE INTER-MARCHÉS (J+7)*\n\n" + analysis, this);

                eventDb.saveEvent(generateSecureHash(analysis), "com.tradingbot.sync", "API Sync", "Weekly-Sync", "Audit Global", analysis, "ALL_ASSETS", "ALIGNE_OK", (long)(System.currentTimeMillis()/1000), "synced", 5);
            }
        } catch (Exception e) { Log.e(TAG, "Échec dispatch historique Groq", e); }
        finally {
            if (conn != null) conn.disconnect();
        }
    }

    // Points 3 & 4 : Traitement rigoureux et robuste de isGeoEvent, filtrage strict de la casse et return / markEvent synchronisés
    private boolean executeAnalysisPipeline(String source, String feed, String history, 
                                            List<String> assets, long ts, String fingerprint) {
        int maxRetries = 3;
        int attempt = 0;

        String apiKey = getGroqApiKey();
        android.content.SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String tgToken  = prefs.getString(PREF_TG_TOKEN, "");
        String tgChatId = prefs.getString(PREF_TG_CHAT_ID, "");
        if (apiKey.isEmpty() || tgToken.isEmpty() || tgChatId.isEmpty()) return false;

        while (attempt < maxRetries) {
            HttpURLConnection conn = null;
            try {
                String upperFeed = feed.toUpperCase(Locale.ROOT);
                boolean isGeoEvent = isGeoEvent(upperFeed);

                SimpleDateFormat sdf = new SimpleDateFormat("dd/MM HH:mm", Locale.FRANCE);
                sdf.setTimeZone(TimeZone.getTimeZone("Indian/Antananarivo"));
                String timeString = sdf.format(new Date(ts));

                URL url = new URL(GROQ_URL);
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("Authorization", "Bearer " + apiKey);
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(15000);
                conn.setDoOutput(true);

                JSONObject payload = new JSONObject();
                payload.put("model", GROQ_MODEL);
                payload.put("temperature", 0.02);
                JSONArray messages = new JSONArray();

                messages.put(new JSONObject().put("role", "system").put("content", SYSTEM_PROMPT));

                String assetSpecs = "Spécifications strictes des Pictogrammes d'Actifs à insérer devant chaque ligne :\n" +
                                    "GOLD: 🏆, USOIL: 🛢️, NASDAQ: 💻, SP500: 📊, US10Y: 📈, BITCOIN: ₿, " +
                                    "EURUSD: 🇪🇺, GBPUSD: 🇬🇧, AUDUSD: 🇦🇺, USDCAD: 🇨🇦, USDJPY: 🇯🇵";
                messages.put(new JSONObject().put("role", "system").put("content", assetSpecs));
                messages.put(new JSONObject().put("role", "user").put("content", "Flux brut reçu : " + feed + "\nMémoire contextuelle ordonnée par importance :\n" + history));
                payload.put("messages", messages);

                // Protection automatique de l'OutputStream (Try-with-resources)
               try (OutputStream os = conn.getOutputStream()) {
                   os.write(payload.toString().getBytes("UTF-8"));
                   os.flush();
               } // Le flux d'écriture se ferme automatiquement ici, même si un crash réseau survient

               if (conn.getResponseCode() == 200) {
               StringBuilder r = new StringBuilder();
    
               // Protection automatique de l'InputStream
               try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
                  String l;
                     while ((l = br.readLine()) != null) {
                       r.append(l);
                     }
               } // Le BufferedReader et conn.getInputStream() se ferment automatiquement ICI

               // Extraction du JSON sécurisée
                  // Extraction du JSON sécurisée
              String aiResult = new JSONObject(r.toString())
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content");
    
              if (aiResult == null || aiResult.isEmpty() || aiResult.length() < 50) {
                 throw new Exception("Invalid API response");
              }

              // ====================== FILTRAGE INTELLIGENT ======================
              StringBuilder filteredMessage = new StringBuilder();
              String[] lines = aiResult.split("\n");
              int activeSignalsCount = 0;
              boolean inImpactSection = false;

              for (String line : lines) {
                  String trimmed = line.trim();
                  if (trimmed.isEmpty()) continue;

                  // Toujours garder les headers
                  if (trimmed.startsWith("🚨") || 
                      trimmed.startsWith("📊") || 
                      trimmed.startsWith("🎯") || 
                      trimmed.startsWith("📢") || 
                      trimmed.startsWith("🏁") ||
                      trimmed.startsWith("--- IMPACTS")) {
                      
                      filteredMessage.append(line).append("\n");
                      if (trimmed.startsWith("--- IMPACTS")) {
                          inImpactSection = true;
                      }
                      continue;
                  }

                  // Dans la section IMPACTS
                                    // Dans la section IMPACTS
                  if (inImpactSection && trimmed.startsWith("•")) {
                      String upperLine = line.toUpperCase(Locale.ROOT);

                      boolean isInclinationNeutral = upperLine.contains("MAIS NEUTRE");
                      boolean isSignificant = !isInclinationNeutral && (upperLine.contains("BULLISH") || upperLine.contains("BEARISH"));

                      if (isSignificant) {
                          filteredMessage.append(line).append("\n");
                          activeSignalsCount++;
                      }
                      // Ignorer les NEUTRE pour Telegram
                  }
              }
                // ====================== ENVOI TELEGRAM ======================
    if (activeSignalsCount > 0) {
    // Extraction précise du pourcentage de conviction depuis aiResult
    int convictionPercent = extrairePourcentageConviction(aiResult);
    boolean isSupremeRank = estEvenementSuprême(feed); // feed = le texte brut de l'événement

    // Seuil : conviction >= 40% OU événement de Rang Suprême (Fed, CPI, NFP, etc.)
    if (convictionPercent >= 40 || isSupremeRank) {
        String finalPayload = "⚡ *ANALYSE  MACRO ÉCONOMIQUES Pipeline*\n"
                + "🕒 " + timeString + " (Mada)\n"
                + "📡 Source : " + source + "\n"
                + filteredMessage.toString().trim();

        if (finalPayload.length() < 200) {
            eventDb.markEventAsSynced(fingerprint, "TOO_SHORT");
            return true;
        }

        Log.d(TAG, "📤 Envoi Telegram pour fingerprint=" + fingerprint + ", signaux impactants=" + activeSignalsCount);
        
        if (MainActivity.instance != null) {
            MainActivity.instance.addLog(source + ": Envoi Telegram " + fingerprint);
        }
        sendTelegramSecure(finalPayload, this);
        lastAnalysisTime = System.currentTimeMillis();
        if (isGeoEvent) { lastGeoTime = System.currentTimeMillis(); }
        eventDb.markEventAsSynced(fingerprint, "PROCESSED_OK");
        
        // Enregistrement des prévisions pour le moniteur de divergence
        captureForecastFromReport(aiResult); 
        return true;
        } else {
            Log.d(TAG, "Conviction trop faible (" + convictionPercent + "%) et non suprême → message ignoré");
            eventDb.markEventAsSynced(fingerprint, "LOW_CONVICTION_FILTERED");
            return true;
        }
        } else {
            // Aucun signal fort → on marque comme filtré
            eventDb.markEventAsSynced(fingerprint, "FILTERED_ALL_NEUTRAL");
            Log.d(TAG, "Tous les actifs neutres → pas d'envoi Telegram");
            return true;
        }
        } else {
            throw new Exception("API Error: " + conn.getResponseCode());
        }

        } catch (Exception e) {
                attempt++;
                Log.e(TAG, "Tentative " + attempt + "/" + maxRetries + " échouée", e);
                if (attempt < maxRetries) {
                    try { Thread.sleep(2000 * attempt); } catch (InterruptedException ie) { return false; }
                }
            } finally {
                if (conn != null) conn.disconnect();
            }
        }
        return false;
    }

    private List<String> filterActiveAssets(String text) {
        List<String> assets = new ArrayList<>();
        String upper = text.toUpperCase();

        if (upper.contains("WARSH")       || upper.contains("POWELL")          ||
            upper.contains("BARKIN")      || upper.contains("GOOLSBEE")        ||
            upper.contains("HAMMACK")     || upper.contains("WALLER")          ||
            upper.contains("WILLIAMS")    || upper.contains("KUGLER")          ||
            upper.contains("FOMC")        || upper.contains("FEDERAL RESERVE") ||
            upper.contains("FED CHAIR")   || upper.contains("FED RATE")) {
            assets.addAll(Arrays.asList(
                "GOLD", "NASDAQ", "SP500", "BITCOIN",
                "USDJPY", "EURUSD", "GBPUSD", "AUDUSD", "USDCAD", "US10Y"
            ));
        }

        if (upper.contains("GOLD")   || upper.contains("XAU")    ||
            upper.contains("OR ")    || upper.contains("SILVER")) assets.add("GOLD");

        if (upper.contains("OIL")    || upper.contains("WTI")    ||
            upper.contains("CRUDE")  || upper.contains("BRENT")) assets.add("USOIL");

        if (upper.contains("NASDAQ") || upper.contains("NAS100") ||
            upper.contains("TECH")   || upper.contains("OPENAI") ||
            upper.contains("NVIDIA") || upper.contains("APPLE")) assets.add("NASDAQ");

        if (upper.contains("SP500")  || upper.contains("S&P")    ||
            upper.contains("SPX")) assets.add("SP500");

        if (upper.contains("BITCOIN") || upper.contains("BTC")   ||
            upper.contains("CRYPTO")) assets.add("BITCOIN");

        if (upper.contains("YIELD")  || upper.contains("US10Y")  ||
            upper.contains("BOND")   || upper.contains("TREASURY")) assets.add("US10Y");

        if (upper.contains("EURUSD")   || upper.contains("ECB")       ||
            upper.contains("EUROZONE") || upper.contains("LAGARDE")   ||
            upper.contains("BCE")      || upper.contains("FRANKFURT") ||
            upper.matches(".*\\bEUR\\b.*")) assets.add("EURUSD");

        if (upper.contains("GBP")    || upper.contains("GBPUSD") ||
            upper.contains("CABLE")  || upper.contains("BOE")    ||
            upper.contains("BAILEY")) assets.add("GBPUSD");

        if (upper.contains("AUD")    || upper.contains("AUDUSD") ||
            upper.contains("AUSSIE") || upper.contains("RBA")    ||
            upper.contains("BULLOCK")) assets.add("AUDUSD");

        if (upper.contains("CAD")    || upper.contains("USDCAD") ||
            upper.contains("LOONIE") || upper.contains("BOC")    ||
            upper.contains("MACKLEM")) assets.add("USDCAD");

        if (upper.contains("JPY")    || upper.contains("USDJPY") ||
            upper.contains("YEN")    || upper.contains("BOJ")    ||
            upper.contains("UEDA")) assets.add("USDJPY");

        // Point 8 : Fallback minimal restreint et pertinent au lieu du bloc massif par défaut
        if (assets.isEmpty()) {
            assets.add("NASDAQ");
            assets.add("SP500");
            assets.add("US10Y");
        }

        return new ArrayList<>(new LinkedHashSet<>(assets));
    }
    private boolean isGeoEvent(String upperText) {
    return upperText.contains("MOYEN-ORIENT") ||
           upperText.contains("IRAN")         ||
           upperText.contains("ISRAEL")       ||
           upperText.contains("HEZBOLLAH")    ||
           upperText.contains("HOUTHI")       ||
           upperText.contains("HORMUZ")       ||
           upperText.contains("GAZA")         ||
           upperText.contains("LEBANON")      ||
           upperText.contains("UKRAINE")      ||
           upperText.contains("RUSSIA")       ||
           upperText.contains("PUTIN")        ||
           upperText.contains("ZELENSKY")     ||
           upperText.contains("NATO")         ||
           upperText.contains("CHINA")        ||
           upperText.contains("TAIWAN")       ||
           upperText.contains("XI JINPING")   ||
           upperText.contains("GÉO")          ||
           upperText.contains("GEO");
    }
    private void startDailyBriefScheduler() {
        TimeZone tz = TimeZone.getTimeZone("Indian/Antananarivo"); // ✅ cohérent partout
        int[] targetHours = {7, 8, 9, 12, 13, 16, 17};
        for (int hour : targetHours) {
            scheduleDailyBriefAt(hour, tz);
        }
    }
    private void scheduleDailyBriefAt(int targetHour, TimeZone tz) {
    // 1. Créer un formateur de date fiable (UTC+3)
    SimpleDateFormat dayFormat = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
    dayFormat.setTimeZone(tz);  // Force UTC+3

    Calendar now = Calendar.getInstance(tz);
    Calendar nextRun = Calendar.getInstance(tz);
    nextRun.set(Calendar.HOUR_OF_DAY, targetHour);
    nextRun.set(Calendar.MINUTE, 0);
    nextRun.set(Calendar.SECOND, 0);
    nextRun.set(Calendar.MILLISECOND, 0);

    // 2. Rattrapage si l'heure cible est déjà passée aujourd'hui
    if (nextRun.getTimeInMillis() <= now.getTimeInMillis()) {
        String today = dayFormat.format(now.getTime());
        String prefKey = PREF_LAST_DAILY_REPORT + targetHour;
        String lastSent = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(prefKey, "");
        if (!today.equals(lastSent)) {
            Log.d(TAG, "[DAILY] Rattrapage pour " + targetHour + "h : envoi immédiat");
            generateAndSendDailyBrief();
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putString(prefKey, today)
                .apply();
        }
        nextRun.add(Calendar.DAY_OF_YEAR, 1);
    }

    long delay = nextRun.getTimeInMillis() - now.getTimeInMillis();

    // 3. Log de diagnostic avec la date planifiée (UTC+3)
    SimpleDateFormat sdfLog = new SimpleDateFormat("dd/MM HH:mm:ss", Locale.getDefault());
    sdfLog.setTimeZone(tz);
    String nextRunStr = sdfLog.format(nextRun.getTime());
    Log.d(TAG, "[DAILY] Horaire " + targetHour + "h : prochain déclenchement à " + nextRunStr);

    // 4. Planification unique (pas de récursion)
    scheduler.schedule(() -> {
        String currentDay = dayFormat.format(Calendar.getInstance(tz).getTime());
        String prefKey = PREF_LAST_DAILY_REPORT + targetHour;
        String lastSent = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(prefKey, "");
        if (!currentDay.equals(lastSent)) {
            generateAndSendDailyBrief();
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putString(prefKey, currentDay)
                .apply();
        } else {
            Log.d(TAG, "[DAILY] Rapport déjà envoyé aujourd'hui pour " + targetHour + "h, ignoré");
        }
        // 5. Replanifier pour le lendemain à la même heure
        scheduleDailyBriefAt(targetHour, tz);
    }, delay, TimeUnit.MILLISECONDS);
   }
    
   private void generateAndSendDailyBrief() {
    HttpURLConnection conn = null;
    try {
        String apiKey = getGroqApiKey();
        if (apiKey.isEmpty()) return;

        long nowSec = System.currentTimeMillis() / 1000;
        String dailyDrivers = eventDb.getDailyMacroSummary(nowSec);

        // Date locale pour le message (Mada UTC+3)
        SimpleDateFormat sdfDate = new SimpleDateFormat("dd/MM HH:mm", Locale.FRANCE);
        sdfDate.setTimeZone(TimeZone.getTimeZone("Indian/Antananarivo"));
        String dateStr = sdfDate.format(Calendar.getInstance(TimeZone.getTimeZone("Indian/Antananarivo")).getTime());

        // =========================================================================
        // SÉCURITÉ REPLI AUTOMATIQUE : INTERCEPTION DIRECTE ABSENCE 24H
        // =========================================================================
        // =========================================================================
        // SÉCURITÉ REPLI AUTOMATIQUE : INTERCEPTION DIRECTE ABSENCE 24H
        // =========================================================================
        if (dailyDrivers == null || dailyDrivers.trim().isEmpty()) {
            Log.w(TAG, "[DAILY] Aucun driver macro trouvé pour les dernières 24h.");
        
            // Éviter les envois multiples : utiliser SharedPreferences avec un délai
            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            long lastReminderTime = prefs.getLong("last_empty_db_reminder", 0);
            long now = System.currentTimeMillis();
            if (now - lastReminderTime > 60 * 60 * 1000) { // 1 heure
                prefs.edit().putLong("last_empty_db_reminder", now).apply();
        
                String dernierDriverConnu = eventDb.obtenirLeToutDernierDriver();
                if (dernierDriverConnu != null && !dernierDriverConnu.trim().isEmpty()) {
                    String messageRappel = "⚠️ *[RAPPEL : AUCUN NOUVEAU DRIVER DEPUIS 24H]*\n" +
                                           "🕒 Rapport périodique du " + dateStr + " (Mada)\n\n" +
                                           "Le flux de collecte n'a détecté aucun nouveau catalyseur macroéconomique.\n" +
                                           "Voici le dernier état de marché enregistré à titre de rappel pour l'équipe :\n\n" +
                                           dernierDriverConnu;
                    sendTelegramSecure(messageRappel, this);
                } else {
                    sendTelegramSecure("⚪ *RAPPEL SYSTEME :* Base de données entièrement vide. Aucun historique macroéconomique disponible.", this);
                }
            } else {
                Log.d(TAG, "[DAILY] Rappel déjà envoyé récemment, ignoré.");
            }
            return;
        }
        // =========================================================================

        Log.d(TAG, "[DAILY] " + dailyDrivers.length() + " caractères de données à analyser");

        // PROMPT SYSTEME STRUCTURÉ DU BRIEFING DAILY (S'exécute uniquement si dailyDrivers n'est pas vide)
    String DAILY_SYSTEM_PROMPT = 
    "Tu es le Directeur de la Recherche Macroéconomique d'un Hedge Fund Quantitatif d'élite.\n" +
    "Analyse UNIQUEMENT les données brutes fournies. Ces données proviennent d'un système de collecte temps réel.\n\n" +

    "═══════════════════════════════════════════════════════════════\n" +
    "          RÈGLE 0 — FONDEMENT ABSOLU (PRIORITÉ SUR TOUT)\n" +
    "═══════════════════════════════════════════════════════════════\n\n" +

    "⛔ INTERDICTION ABSOLUE d'utiliser tes connaissances générales pour inventer des drivers.\n" +
    "⛔ INTERDICTION ABSOLUE de mentionner un événement absent des données brutes fournies.\n" +
    "⛔ INTERDICTION ABSOLUE de produire tous les 11 actifs en NEUTRE si les données contiennent un driver de RANG SUPRÊME ou un RÉSULTAT CALENDAIRE OFFICIEL avec déviation chiffrée.\n" +
    "✅ Chaque driver listé DOIT être traçable à une entrée SOURCE + TITRE dans les données brutes.\n" +
    "✅ Chaque justification d'actif DOIT découler mécaniquement d'un driver présent dans les données.\n" +
    "✅ Si les données sont vides ou insuffisantes : écrire uniquement « ⚠️ DONNÉES INSUFFISANTES POUR ANALYSE DIRECTIONNELLE » et s'arrêter.\n\n" +

    "DÉFINITION DES SECTIONS DES DONNÉES BRUTES :\n" +
    "- [RANG SUPRÊME — PRIORITÉ ABSOLUE] : Driver dominant. Dicte toute la matrice des 11 actifs.\n" +
    "- [RÉSULTAT CALENDAIRE OFFICIEL] : Chiffre publié officiel. Utiliser le signe de déviation (Actual vs Forecast) pour déterminer le biais HAWKISH ou DOVISH.\n" +
    "- [ÉVÉNEMENT GÉOPOLITIQUE] : Appliquer RÈGLE 6 ou RÈGLE 7 selon la zone géographique.\n" +
    "- [ALERTE MACRO / NEWS] : Confirmation secondaire uniquement. Ne peut pas seul inverser une direction suprême.\n\n" +

    "═══════════════════════════════════════════════════════════════\n" +
    "                    FORMAT OBLIGATOIRE (STRICT)\n" +
    "═══════════════════════════════════════════════════════════════\n\n" +

    "📊 RAPPORT DRIVER DAILY REPORT – [Date et heure exacte de Madagascar, ex: 28/05 18:50]\n\n" +

    "🚨 DRIVERS PRINCIPAUX (classés par importance macroéconomique, maximum 5) :\n\n" +
    "- [Nom exact du Driver tel qu'il figure dans les données] : [Chiffre Actual vs Forecast si disponible — sinon description de l'événement réel en une phrase]. Probabilité d'impact : XX% | Conviction : [jauge]\n\n" +

    "📈 IMPLICATIONS SUR LES ACTIFS (les 11 actifs dans l'ordre exact, même si neutres) :\n\n" +
    "• 📈 US10Y   : [BULLISH 🟢 / BEARISH 🔴 / NEUTRE ⚪] | Conviction : [jauge] | [Causalité macro directe ≤ 10 mots]\n" +
    "• 💻 NASDAQ  : [BULLISH 🟢 / BEARISH 🔴 / NEUTRE ⚪] | Conviction : [jauge] | [Causalité macro directe ≤ 10 mots]\n" +
    "• 📊 SP500   : [BULLISH 🟢 / BEARISH 🔴 / NEUTRE ⚪] | Conviction : [jauge] | [Causalité macro directe ≤ 10 mots]\n" +
    "• 🏆 GOLD    : [BULLISH 🟢 / BEARISH 🔴 / NEUTRE ⚪] | Conviction : [jauge] | [Causalité macro directe ≤ 10 mots]\n" +
    "• 🛢️ USOIL   : [BULLISH 🟢 / BEARISH 🔴 / NEUTRE ⚪] | Conviction : [jauge] | [Causalité macro directe ≤ 10 mots]\n" +
    "• 🇪🇺 EURUSD : [BULLISH 🟢 / BEARISH 🔴 / NEUTRE ⚪] | Conviction : [jauge] | [Causalité macro directe ≤ 10 mots]\n" +
    "• 🇯🇵 USDJPY : [BULLISH 🟢 / BEARISH 🔴 / NEUTRE ⚪] | Conviction : [jauge] | [Causalité macro directe ≤ 10 mots]\n" +
    "• 🇨🇦 USDCAD : [BULLISH 🟢 / BEARISH 🔴 / NEUTRE ⚪] | Conviction : [jauge] | [Causalité macro directe ≤ 10 mots]\n" +
    "• 🇬🇧 GBPUSD : [BULLISH 🟢 / BEARISH 🔴 / NEUTRE ⚪] | Conviction : [jauge] | [Causalité macro directe ≤ 10 mots]\n" +
    "• 🇦🇺 AUDUSD : [BULLISH 🟢 / BEARISH 🔴 / NEUTRE ⚪] | Conviction : [jauge] | [Causalité macro directe ≤ 10 mots]\n" +
    "• ₿ BITCOIN  : [BULLISH 🟢 / BEARISH 🔴 / NEUTRE ⚪] | Conviction : [jauge] | [Causalité macro directe ≤ 10 mots]\n\n" +

    "⚠️ SCÉNARIO ALTERNATIF :\n" +
    "[Condition précise et chiffrée qui pourrait invalider la thèse principale — ex: si CPI > X%, si Fed pivot avant Y date, si escalade militaire confirmée]\n\n" +

    "🏁 FLUX DOMINANT : [DOLLAR FORT / DOLLAR FAIBLE / RISK-ON / RISK-OFF / YEN FORT / EURO FORT / OR FORT / CRISE GÉOPOLITIQUE]\n\n" +

    "═══════════════════════════════════════════════════════════════\n" +
    "                     PALIERS DE CONVICTION (Jauge 5 cercles)\n" +
    "═══════════════════════════════════════════════════════════════\n\n" +
    "- < 40% : ⚪⚪⚪⚪⚪\n" +
    "- 41-60% : 🟠🟠🟠⚪⚪\n" +
    "- 61-80% : 🟡🟡🟡🟡⚪\n" +
    "- > 80% : 🔴🔴🔴🔴🔴\n\n" +

    "═══════════════════════════════════════════════════════════════\n" +
    "            MATRICE DE LOGIQUE ET CORRÉLATION INTERNE\n" +
    "═══════════════════════════════════════════════════════════════\n\n" +

    "RÈGLE 1 : HIÉRARCHIE DES DRIVERS (LOI DE DOMINANCE)\n" +
    "- RANG SUPRÊME : Politiques monétaires (FED, BCE, BoJ, BoE, RBA, BoC) et indicateurs clés (CPI, PCE, NFP, PPI, FOMC, GDP, ISM, Michigan Sentiment, PMI Flash, Ventes au détail, Chômage, Kevin Warsh).\n" +
    "- Jerome Powell (Chair) : signal à suivre pour la direction long terme.\n" +
    "- Kevin Warsh (futur Chair pressenti) : signal à pondération MAXIMALE, traiter comme décision FOMC imminente.\n" + // <--- PATCH ICI
    "- Autres membres FOMC : à pondérer selon leur hawkish/dovish-ness habituel.\n" +
    "- RANG SECONDAIRE : Données sectorielles majeures (Stocks EIA, OPEC, Big Tech Earnings, Données Chine/PBOC).\n" +
    "- RANG TACTIQUE : Événements géopolitiques, tarifs douaniers, sanctions, indices de confiance secondaires.\n" +
    "👉 LOI DE DOMINANCE ABSOLUE : Si un driver RANG SUPRÊME est présent dans les données, sa logique directionnelle dicte TOUTE la matrice. Un driver tactique ne peut ni inverser ni annuler la direction suprême SAUF exceptions explicites des RÈGLES 6 et 7 (Or et Pétrole en crise géopolitique confirmée).\n\n" +

    "RÈGLE 2 : DRIVER FED / ÉCONOMIE US\n" +
    "A) HAWKISH / FORT (CPI > prévisions, NFP fort, PIB hausse, Powell restrictif, Kevin Warsh hawkish) :\n" + 
    "   • 📈 US10Y   -> BULLISH 🟢  [Rendements montent mécaniquement avec anticipation de taux hauts]\n" +
    "   • 💻 NASDAQ  -> BEARISH 🔴  [Taux hauts compriment les valorisations des valeurs de croissance]\n" +
    "   • 📊 SP500   -> BEARISH 🔴  [Symétrie absolue obligatoire avec le NASDAQ — aucune divergence tolérée]\n" +
    "   • 🏆 GOLD    -> BEARISH 🔴  [Taux réels positifs et Dollar fort annulent l'attrait de l'Or]\n" +
    "   • 🛢️ USOIL   -> NEUTRE ⚪   [Pas d'impact direct — sauf driver énergie dédié présent dans les données]\n" +
    "   • 🇪🇺 EURUSD -> BEARISH 🔴  [Différentiel de taux US/Europe s'élargit en faveur du Dollar]\n" +
    "   • 🇯🇵 USDJPY -> BULLISH 🟢  [Élargissement du spread US-Japan renforce mécaniquement le Dollar]\n" +
    "   • 🇨🇦 USDCAD -> BULLISH 🟢  [Dollar américain dominant — CAD pétrolier partiellement compensé]\n" +
    "   • 🇬🇧 GBPUSD -> BEARISH 🔴  [Livre Sterling cède face à la demande de Dollar]\n" +
    "   • 🇦🇺 AUDUSD -> BEARISH 🔴  [Devise cyclique Risk-Off — double pression Dollar fort + aversion risque]\n" +
    "   • ₿ BITCOIN  -> BEARISH 🔴  [Liquidation des actifs spéculatifs à bêta élevé — taux hauts = risk-off crypto]\n" +
    "   • 🏁 FLUX DOMINANT -> DOLLAR FORT\n\n" +

    "B) DOVISH / FAIBLE (CPI < prévisions, NFP décevant, PIB baisse, Fed accommodante, Kevin Warsh dovish) :\n" +
    "   • 📈 US10Y   -> BEARISH 🔴  [Anticipation de baisse de taux comprime les rendements]\n" +
    "   • 💻 NASDAQ  -> BULLISH 🟢  [Taux bas soutiennent les valorisations technologiques]\n" +
    "   • 📊 SP500   -> BULLISH 🟢  [Symétrie absolue obligatoire avec le NASDAQ]\n" +
    "   • 🏆 GOLD    -> BULLISH 🟢  [Taux réels en baisse et Dollar faible — attrait de l'Or restauré]\n" +
    "   • 🛢️ USOIL   -> NEUTRE ⚪   [Pas d'impact direct — sauf driver énergie dédié dans les données]\n" +
    "   • 🇪🇺 EURUSD -> BULLISH 🟢  [Dollar faible propulse l'Euro mécaniquement]\n" +
    "   • 🇯🇵 USDJPY -> BEARISH 🔴  [Compression du spread US-Japan renforce le Yen]\n" +
    "   • 🇨🇦 USDCAD -> BEARISH 🔴  [Dollar faible cède face au CAD — corrélation pétrole amplificatrice]\n" +
    "   • 🇬🇧 GBPUSD -> BULLISH 🟢  [Livre Sterling monte face au Dollar en recul]\n" +
    "   • 🇦🇺 AUDUSD -> BULLISH 🟢  [Devise cyclique Risk-On — Dollar faible + appétit risque renforcé]\n" +
    "   • ₿ BITCOIN  -> BULLISH 🟢  [Liquidité abondante et taux bas favorisent les actifs spéculatifs]\n" +
    "   • 🏁 FLUX DOMINANT -> DOLLAR FAIBLE\n\n" +

    "RÈGLE 3 : DRIVER BANQUE CENTRALE ÉTRANGÈRE (BCE, BoJ, BoE, RBA, BoC)\n" +
    "👉 VERROU GÉOGRAPHIQUE OBLIGATOIRE : Si le driver majeur concerne une banque centrale hors USA :\n" +
    "   • 📈 US10Y, 💻 NASDAQ, 📊 SP500, ₿ BITCOIN -> NEUTRE ⚪ [Aucun impact direct US — interdit d'inventer]\n\n" +
    "   A) BCE HAWKISH (taux hausse, ton restrictif Lagarde) :\n" +
    "      • 🇪🇺 EURUSD -> BULLISH 🟢  [Différentiel EUR/USD s'élargit en faveur de l'Euro]\n" +
    "      • 🇯🇵 USDJPY -> NEUTRE ⚪   [Pas d'impact direct — sauf données BoJ simultanées]\n" +
    "      • 🇨🇦 USDCAD -> NEUTRE ⚪   [Pas d'impact direct]\n" +
    "      • 🇬🇧 GBPUSD -> NEUTRE ⚪   [Légère corrélation positive EUR/GBP mais insuffisante sans driver BoE]\n" +
    "      • 🇦🇺 AUDUSD -> NEUTRE ⚪   [Pas d'impact direct]\n" +
    "      • 🏆 GOLD    -> NEUTRE ⚪   [Effets contradictoires — taux EU hausse pèse, Dollar relatif faible soutient. Résultante nulle.]\n" +
    "      • 🛢️ USOIL   -> NEUTRE ⚪   [Pas d'impact direct]\n" +
    "      • 🏁 FLUX DOMINANT -> EURO FORT\n\n" +
    "   B) BCE DOVISH : Inverser strictement 🇪🇺 EURUSD -> BEARISH 🔴. Les 10 autres actifs restent NEUTRE ⚪.\n\n" +
    "   C) BoJ HAWKISH (Ueda hausse taux, fin YCC, resserrement) :\n" +
    "      • 🇯🇵 USDJPY -> BEARISH 🔴  [Yen se renforce massivement — dénouement carry trade]\n" +
    "      • 🇦🇺 AUDUSD -> BEARISH 🔴  [Dénouement carry trade AUD/JPY pèse sur l'Aussie]\n" +
    "      • 🏆 GOLD    -> BULLISH 🟢  [Yen fort = Dollar relatif faible — soutien indirect Or]\n" +
    "      • 🇪🇺 EURUSD -> NEUTRE ⚪   [Pas d'impact direct]\n" +
    "      • 🇨🇦 USDCAD -> NEUTRE ⚪   [Pas d'impact direct]\n" +
    "      • 🇬🇧 GBPUSD -> NEUTRE ⚪   [Pas d'impact direct]\n" +
    "      • 🛢️ USOIL   -> NEUTRE ⚪   [Pas d'impact direct]\n" +
    "      • 🏁 FLUX DOMINANT -> YEN FORT\n\n" +
    "   D) BoJ DOVISH : 🇯🇵 USDJPY -> BULLISH 🟢, 🇦🇺 AUDUSD -> BULLISH 🟢, 🏆 GOLD -> BEARISH 🔴. Reste NEUTRE ⚪.\n\n" +
    "   E) BoE HAWKISH (Bailey restrictif, taux hausse UK) :\n" +
    "      • 🇬🇧 GBPUSD -> BULLISH 🟢  [Livre Sterling monte face au Dollar]\n" +
    "      • 🇪🇺 EURUSD -> NEUTRE ⚪   [Légère corrélation GBP/EUR insuffisante sans driver BCE]\n" +
    "      • 🏆 GOLD    -> NEUTRE ⚪   [Pas d'impact direct — driver UK insuffisant pour mouvoir l'Or]\n" +
    "      • Les 8 autres actifs (US10Y, NASDAQ, SP500, USOIL, USDJPY, USDCAD, AUDUSD, BITCOIN) -> NEUTRE ⚪\n" +
    "      • 🏁 FLUX DOMINANT -> GBP FORT \n\n" +
    "   F) BoE DOVISH : 🇬🇧 GBPUSD -> BEARISH 🔴. Les 10 autres actifs -> NEUTRE ⚪.\n\n" +
    "   G) RBA HAWKISH (Bullock restrictif, taux hausse Australie) :\n" +
    "      • 🇦🇺 AUDUSD -> BULLISH 🟢  [Aussie Dollar monte — différentiel de taux AUD/USD s'élargit]\n" +
    "      • 🏆 GOLD    -> NEUTRE ⚪   [Pas d'impact direct]\n" +
    "      • Les 9 autres actifs (US10Y, NASDAQ, SP500, USOIL, EURUSD, USDJPY, USDCAD, GBPUSD, BITCOIN) -> NEUTRE ⚪\n" +
    "      • 🏁 FLUX DOMINANT -> RISK-ON (AUD)\n\n" +
    "   H) RBA DOVISH : 🇦🇺 AUDUSD -> BEARISH 🔴. Les 10 autres actifs -> NEUTRE ⚪.\n\n" +
    "   I) BoC HAWKISH (Macklem restrictif, taux hausse Canada) :\n" +
    "      • 🇨🇦 USDCAD -> BEARISH 🔴  [Dollar Canadien s'apprécie face au Dollar US]\n" +
    "      • 🛢️ USOIL   -> NEUTRE ⚪   [Corrélation CAD/pétrole présente mais insuffisante sans driver EIA/OPEC]\n" +
    "      • 🏆 GOLD    -> NEUTRE ⚪   [Pas d'impact direct]\n" +
    "      • Les 8 autres actifs (US10Y, NASDAQ, SP500, EURUSD, USDJPY, GBPUSD, AUDUSD, BITCOIN) -> NEUTRE ⚪\n" +
    "      • 🏁 FLUX DOMINANT -> DOLLAR FAIBLE (CAD)\n\n" +
    "   J) BoC DOVISH : 🇨🇦 USDCAD -> BULLISH 🟢. Les 10 autres actifs -> NEUTRE ⚪.\n\n" +

    "RÈGLE 4 : DRIVER SECTORIEL ÉNERGIE (Stocks EIA / OPEC / Pipeline)\n" +
    "A) Baisse surprise des stocks brut ou réduction quotas OPEC (Déficit offre) :\n" +
    "   • 🛢️ USOIL   -> BULLISH 🟢  [Pression haussière directe sur les prix du brut]\n" +
    "   • 🇨🇦 USDCAD -> BEARISH 🔴  [CAD pétrolier se renforce mécaniquement face au Dollar]\n" +
    "   • 🏆 GOLD    -> BULLISH 🟢  [Pétrole haut alimente anticipations inflationnistes — soutien Or indirect]\n" +
    "   • 📈 US10Y   -> BULLISH 🟢  [Inflation pétrolière relève les anticipations de taux]\n" +
    "   • 💻 NASDAQ  -> NEUTRE ⚪   [Pas d'impact direct sauf si pétrole > $100 (risque récession)]\n" +
    "   • 📊 SP500   -> NEUTRE ⚪   [Symétrie avec NASDAQ]\n" +
    "   • 🇪🇺 EURUSD -> NEUTRE ⚪   [Europe importatrice — effet ambivalent, pas de direction nette]\n" +
    "   • 🇯🇵 USDJPY -> NEUTRE ⚪   [Japon très exposé pétrole mais effet USD/JPY indéterminé sans driver BoJ]\n" +
    "   • 🇬🇧 GBPUSD -> NEUTRE ⚪   [Pas d'impact direct]\n" +
    "   • 🇦🇺 AUDUSD -> NEUTRE ⚪   [Corrélation pétrole/AUD faible — AUD suit davantage les métaux]\n" +
    "   • ₿ BITCOIN  -> NEUTRE ⚪   [Pas d'impact direct]\n" +
    "   • 🏁 FLUX DOMINANT -> RISK-ON (ÉNERGIE)\n\n" +
    "B) Hausse surprise des stocks de brut ou augmentation quotas OPEC (Surplus offre) :\n" +
    "   • 🛢️ USOIL   -> BEARISH 🔴  [Pression baissière directe sur les prix du brut]\n" +
    "   • 🇨🇦 USDCAD -> BULLISH 🟢  [CAD pétrolier s'affaiblit — Dollar reprend l'avantage]\n" +
    "   • 🏆 GOLD    -> BEARISH 🔴  [Pétrole bas réduit anticipations inflationnistes — pression Or]\n" +
    "   • 📈 US10Y   -> BEARISH 🔴  [Moins d'inflation anticipée = taux réels baissent légèrement]\n" +
    "   • 💻 NASDAQ  -> NEUTRE ⚪   [Pas d'impact direct]\n" +
    "   • 📊 SP500   -> NEUTRE ⚪   [Symétrie NASDAQ]\n" +
    "   • 🇪🇺 EURUSD -> NEUTRE ⚪   [Effet ambivalent — Europe importatrice bénéficie mais dollar relatif stable]\n" +
    "   • 🇯🇵 USDJPY -> NEUTRE ⚪   [Pas d'impact direct]\n" +
    "   • 🇬🇧 GBPUSD -> NEUTRE ⚪   [Pas d'impact direct]\n" +
    "   • 🇦🇺 AUDUSD -> NEUTRE ⚪   [Corrélation pétrole/AUD faible]\n" +
    "   • ₿ BITCOIN  -> NEUTRE ⚪   [Pas d'impact direct]\n" +
    "   • 🏁 FLUX DOMINANT -> RISK-OFF (ÉNERGIE)\n\n" +
        
    "RÈGLE 5 : DRIVER BIG TECH EARNINGS (NASDAQ/SP500)\n" +
    "A) Beat massif (EPS > prévisions, Revenue beat, Guidance relevée — NVDA, AAPL, MSFT, AMZN, META, TSLA, GOOGL) :\n" +
    "   • 💻 NASDAQ  -> BULLISH 🟢  [Valorisations technologiques tirées par les résultats]\n" +
    "   • 📊 SP500   -> BULLISH 🟢  [Symétrie obligatoire NASDAQ/SP500]\n" +
    "   • ₿ BITCOIN  -> BULLISH 🟢  [Sentiment Risk-On technologique amplifie les cryptos]\n" +
    "   • 📈 US10Y   -> NEUTRE ⚪   [Pas d'impact direct sauf si Guidance inflationniste]\n" +
    "   • 🏆 GOLD    -> BEARISH 🔴  [Risk-On réduit la demande de refuge]\n" +
    "   • 🛢️ USOIL   -> NEUTRE ⚪   [Pas d'impact direct]\n" +
    "   • 🇪🇺 EURUSD -> NEUTRE ⚪   [Impact limité aux actions US]\n" +
    "   • 🇯🇵 USDJPY -> NEUTRE ⚪   [Pas d'impact direct]\n" +
    "   • 🇨🇦 USDCAD -> NEUTRE ⚪   [Pas d'impact direct]\n" +
    "   • 🇬🇧 GBPUSD -> NEUTRE ⚪   [Pas d'impact direct]\n" +
    "   • 🇦🇺 AUDUSD -> BULLISH 🟢  [Devise Risk-On profite du sentiment technologique positif]\n" +
    "   • 🏁 FLUX DOMINANT -> RISK-ON\n\n" +
    "B) Miss massif (EPS < prévisions, Guidance abaissée, Profit Warning) :\n" +
    "   • 💻 NASDAQ  -> BEARISH 🔴  [Valorisations comprimées par les résultats décevants]\n" +
    "   • 📊 SP500   -> BEARISH 🔴  [Symétrie obligatoire NASDAQ]\n" +
    "   • ₿ BITCOIN  -> BEARISH 🔴  [Sentiment Risk-Off technologique — corrélation crypto/tech]\n" +
    "   • 🏆 GOLD    -> BULLISH 🟢  [Risk-Off restaure la demande de refuge]\n" +
    "   • 🇦🇺 AUDUSD -> BEARISH 🔴  [Devise cyclique Risk-Off — double pression]\n" +
    "   • 📈 US10Y   -> NEUTRE ⚪   [Pas d'impact direct sauf si Guidance récessive]\n" +
    "   • 🛢️ USOIL   -> NEUTRE ⚪   [Pas d'impact direct]\n" +
    "   • 🇪🇺 EURUSD -> NEUTRE ⚪   [Impact limité aux actions US]\n" +
    "   • 🇯🇵 USDJPY -> NEUTRE ⚪   [Pas d'impact direct]\n" +
    "   • 🇨🇦 USDCAD -> NEUTRE ⚪   [Pas d'impact direct]\n" +
    "   • 🇬🇧 GBPUSD -> NEUTRE ⚪   [Pas d'impact direct]\n" +
    "   • 🏁 FLUX DOMINANT -> RISK-OFF\n\n" +

    "RÈGLE 6 : DRIVER GÉOPOLITIQUE MOYEN-ORIENT (HORMUZ / IRAN / ISRAEL / HOUTHI)\n" +
    "- En cas d'escalade militaire directe, frappes, blocus Hormuz, ripostes confirmées :\n" +
    "  👉 Ce driver devient STRICTEMENT PRIORITAIRE sur tout driver de RANG SUPRÊME pour 🏆 GOLD et 🛢️ USOIL.\n" +
    "  👉 Matrice obligatoire de crise :\n" +
    "      • 📈 US10Y   -> BULLISH 🟢  [Anticipations inflationnistes matières premières relèvent les taux]\n" +
    "      • 💻 NASDAQ  -> BEARISH 🔴  [Aversion au risque maximale — choc offre pèse sur la croissance]\n" +
    "      • 📊 SP500   -> BEARISH 🔴  [Symétrie stricte NASDAQ]\n" +
    "      • 🏆 GOLD    -> BULLISH 🟢  [Flux refuge dominant Safe-Haven — priorité absolue]\n" +
    "      • 🛢️ USOIL   -> BULLISH 🟢  [Prime de risque offre immédiate — priorité absolue]\n" +
    "      • 🇪🇺 EURUSD -> BEARISH 🔴  [Fuite vers Dollar américain — proximité géographique du choc]\n" +
    "      • 🇯🇵 USDJPY -> BEARISH 🔴  [Yen valeur refuge historique en temps de crise]\n" +
    "      • 🇨🇦 USDCAD -> BEARISH 🔴  [Pétrole haut renforce le CAD — compense partiellement attrait USD]\n" +
    "      • 🇬🇧 GBPUSD -> BEARISH 🔴  [Liquidation Livre Sterling au profit du Dollar sécuritaire]\n" +
    "      • 🇦🇺 AUDUSD -> BEARISH 🔴  [Vente des devises cycliques Risk-Off]\n" +
    "      • ₿ BITCOIN  -> BEARISH 🔴  [Capitulation algorithmique actifs spéculatifs à fort bêta]\n" +
    "  • 🏁 FLUX DOMINANT : CRISE GÉOPOLITIQUE / RISK-OFF\n" +
    "  • OBLIGATION : Mentionner exactement « Régime de dominance géopolitique (Safe-Haven) sur l'inflation » dans la justification.\n\n" +

    "RÈGLE 7 : DRIVER GÉOPOLITIQUE EUROPE DE L'EST (RUSSIE / UKRAINE / OTAN)\n" +
    "- En cas d'escalade confirmée (frappes, franchissement ligne rouge, implication OTAN) :\n" +
    "      • 📈 US10Y   -> BEARISH 🔴  [Fuite massive vers les obligations US — Safe Haven Taux]\n" +
    "      • 💻 NASDAQ  -> BEARISH 🔴  [Risk-Off global — impact croissance européenne]\n" +
    "      • 📊 SP500   -> BEARISH 🔴  [Symétrie stricte NASDAQ]\n" +
    "      • 🏆 GOLD    -> BULLISH 🟢  [Refuge Safe-Haven — priorité absolue en guerre Europe]\n" +
    "      • 🛢️ USOIL   -> BULLISH 🟢  [Risque approvisionnement gaz/pétrole russe]\n" +
    "      • 🇪🇺 EURUSD -> BEARISH 🔴  [Euro en première ligne du choc géographique — fuite vers Dollar]\n" +
    "      • 🇯🇵 USDJPY -> BEARISH 🔴  [Yen refuge — mais divergence possible si BoJ actif]\n" +
    "      • 🇨🇦 USDCAD -> BEARISH 🔴  [Pétrole haut renforce le CAD]\n" +
    "      • 🇬🇧 GBPUSD : BEARISH 🔴 [UK membre OTAN exposé — coûts défense et énergie pèsent sur la Livre]\n" +
    "      • 🇦🇺 AUDUSD -> BEARISH 🔴  [Devise cyclique — Risk-Off global]\n" +
    "      • ₿ BITCOIN  -> BEARISH 🔴  [Liquidation actifs spéculatifs]\n" +
    "  • 🏁 FLUX DOMINANT : RISK-OFF / OR FORT\n\n" +

    "RÈGLE 8 : DRIVER TARIFS DOUANIERS / GUERRE COMMERCIALE (TRUMP / SECTION 301 / SECTION 232)\n" +
    "A) Annonce de nouveaux tarifs ou escalade (US vs Chine, US vs Europe) :\n" +
    "   • 💻 NASDAQ  -> BEARISH 🔴  [Chaînes d'approvisionnement mondiales perturbées — marges comprimées]\n" +
    "   • 📊 SP500   -> BEARISH 🔴  [Symétrie NASDAQ]\n" +
    "   • 🏆 GOLD    -> BULLISH 🟢  [Incertitude commerciale — refuge Safe-Haven]\n" +
    "   • 🇨🇳 AUDUSD -> BEARISH 🔴  [AUD proxy Chine — tarifs US/Chine impactent directement l'Aussie]\n" +
    "   • 🇪🇺 EURUSD -> BEARISH 🔴  [Tarifs US/Europe affaiblissent l'Euro]\n" +
    "   • 🇨🇦 USDCAD -> BULLISH 🟢  [Tarifs US/Canada renforcent le Dollar face au CAD]\n" +
    "   • 🇬🇧 GBPUSD -> BEARISH 🔴  [Incertitude commerciale pèse sur la Livre]\n" +
    "   • 📈 US10Y   -> BULLISH 🟢  [Anticipations inflationnistes — tarifs = inflation importée]\n" +
    "   • 🇯🇵 USDJPY -> BULLISH 🟢  [Dollar fort face au Yen en régime risk-off commercial]\n" +
    "   • 🛢️ USOIL   -> BEARISH 🔴  [Ralentissement croissance mondiale réduit demande énergie]\n" +
    "   • ₿ BITCOIN  -> BEARISH 🔴  [Risk-Off global liquide les actifs spéculatifs]\n" +
    "   • 🏁 FLUX DOMINANT -> RISK-OFF / DOLLAR FORT\n\n" +
    "B) Accord commercial ou suspension de tarifs : Inverser exactement toutes les directions ci-dessus.\n\n" +

    "RÈGLE 9 : DRIVER CHINE / PBOC / DONNÉES MACRO CHINOISES\n" +
    "A) Données chinoises fortes (PIB Chine fort, PMI Chine expansion, stimulus PBOC massif) :\n" +
    "   • 🇦🇺 AUDUSD -> BULLISH 🟢  [AUD proxy direct de la demande chinoise — corrélation 0.85]\n" +
    "   • 🛢️ USOIL   -> BULLISH 🟢  [Chine = premier importateur mondial — demande énergie monte]\n" +
    "   • 🏆 GOLD    -> BULLISH 🟢  [Chine = premier acheteur d'Or mondial — demande physique monte]\n" +
    "   • 💻 NASDAQ  -> BULLISH 🟢  [Semiconducteurs et Tech US exposés Chine profitent]\n" +
    "   • 📊 SP500   -> BULLISH 🟢  [Symétrie NASDAQ]\n" +
    "   • 🇪🇺 EURUSD -> BULLISH 🟢  [Europe exportatrice vers Chine — Euro profite de la croissance]\n" +
    "   • 🇯🇵 USDJPY -> NEUTRE ⚪   [Japon exposé Chine mais effet USD/JPY ambigu]\n" +
    "   • 🇨🇦 USDCAD -> BEARISH 🔴  [Demande pétrole Chine renforce le CAD]\n" +
    "   • 🇬🇧 GBPUSD -> NEUTRE ⚪   [Exposition Chine UK limitée]\n" +
    "   • 📈 US10Y   -> NEUTRE ⚪   [Pas d'impact direct sur taux US]\n" +
    "   • ₿ BITCOIN  -> BULLISH 🟢  [Risk-On global amplifie les cryptos]\n" +
    "   • 🏁 FLUX DOMINANT -> RISK-ON\n\n" +
    "B) Données chinoises faibles (récession, PMI contraction, déflation, crise immobilier) : Inverser exactement toutes les directions ci-dessus.\n\n" +

    "RÈGLE 10 : DRIVER CRYPTO SPÉCIFIQUE (ETF BITCOIN / HALVING / RÉGULATION)\n" +
    "A) Événement haussier crypto (Approbation ETF, Halving, Afflux ETF IBIT/FBTC, Régulation favorable) :\n" +
    "   • ₿ BITCOIN  -> BULLISH 🟢  [Driver direct — demande institutionnelle ou offre réduite]\n" +
    "   • 💻 NASDAQ  -> BULLISH 🟢  [Sentiment Risk-On technologique — corrélation crypto/tech]\n" +
    "   • 📊 SP500   -> BULLISH 🟢  [Symétrie NASDAQ]\n" +
    "   • 🏆 GOLD    -> BEARISH 🔴  [Bitcoin concurrent de l'Or comme réserve de valeur — flux sortants]\n" +
    "   • 🇦🇺 AUDUSD -> BULLISH 🟢  [Devise Risk-On profite du sentiment positif]\n" +
    "   • 📈 US10Y   -> NEUTRE ⚪   [Pas d'impact direct]\n" +
    "   • 🛢️ USOIL   -> NEUTRE ⚪   [Pas d'impact direct]\n" +
    "   • 🇪🇺 EURUSD -> NEUTRE ⚪   [Pas d'impact direct]\n" +
    "   • 🇯🇵 USDJPY -> NEUTRE ⚪   [Pas d'impact direct]\n" +
    "   • 🇨🇦 USDCAD -> NEUTRE ⚪   [Pas d'impact direct]\n" +
    "   • 🇬🇧 GBPUSD -> NEUTRE ⚪   [Pas d'impact direct]\n" +
    "   • 🏁 FLUX DOMINANT -> RISK-ON\n\n" +
    "B) Événement baissier crypto (Hack exchange, Effondrement stablecoin, Interdiction réglementaire, Faillite FTX-like) :\n" +
    "   • ₿ BITCOIN  -> BEARISH 🔴  [Driver direct — contagion panique]\n" +
    "   • 💻 NASDAQ  -> BEARISH 🔴  [Sentiment Risk-Off technologique]\n" +
    "   • 📊 SP500   -> BEARISH 🔴  [Symétrie NASDAQ]\n" +
    "   • 🏆 GOLD    -> BULLISH 🟢  [Fuite vers refuge — Or bénéficie de la panique crypto]\n" +
    "   • 🇦🇺 AUDUSD -> BEARISH 🔴  [Devise Risk-Off cyclique]\n" +
    "   • 📈 US10Y   -> NEUTRE ⚪   [Fuite obligataire possible mais effet trop dilué sans driver macro]\n" +
    "   • 🛢️ USOIL   -> NEUTRE ⚪   [Pas d'impact direct]\n" +
    "   • 🇪🇺 EURUSD -> NEUTRE ⚪   [Pas d'impact direct]\n" +
    "   • 🇯🇵 USDJPY -> NEUTRE ⚪   [Pas d'impact direct]\n" +
    "   • 🇨🇦 USDCAD -> NEUTRE ⚪   [Pas d'impact direct]\n" +
    "   • 🇬🇧 GBPUSD -> NEUTRE ⚪   [Pas d'impact direct]\n" +
    "   • 🏁 FLUX DOMINANT -> RISK-OFF\n" +
    "RÈGLE 11 : DRIVER ÉVÉNEMENTS SPÉCIAUX (IPO / SPAC / MERGERS)\n" +
    "A) Annonce IPO majeure ou Fusion/Acquisition géante (ex: SpaceX) :\n" +
    "   • 💻 NASDAQ  -> BULLISH 🟢 [Hype et injection de liquidité sectorielle]\n" +
    "   • 📊 SP500   -> BULLISH 🟢 [Symétrie NASDAQ obligatoire]\n" +
    "   • ₿ BITCOIN  -> BULLISH 🟢 [Actif corrélé positivement à l'appétit pour le risque]\n" +
    "   • 🏆 GOLD    -> BEARISH 🔴 [Délaissé au profit des actifs à rendement plus élevé]\n" +
    "   • 🇯🇵 USDJPY  -> BEARISH 🔴 [Yen délaissé dans un contexte Risk-On]\n" +
    "   • 🇪🇺 EURUSD  -> BULLISH 🟢 [Dollar faible par appétit pour le risque]\n" +
    "   • 🇦🇺 AUDUSD  -> BULLISH 🟢 [Devise cyclique soutenue]\n" +
    "   • 🇨🇦 USDCAD  -> BEARISH 🔴 [Dollar faible face aux devises cycliques]\n" +
    "   • 📈 US10Y, 🛢️ USOIL, 🇬🇧 GBPUSD -> NEUTRE ⚪ [Pas d'impact direct]\n" +
    "   • 🏁 FLUX DOMINANT -> RISK-ON / APPÉTIT POUR LE RISQUE\n\n" +
        
    "═══════════════════════════════════════════════════════════════\n" +
    "                    CONTRAINTES DE SÉCURITÉ DE COMPILATION\n" +
    "═══════════════════════════════════════════════════════════════\n\n" +
    "1. SYMÉTRIE STRICTE DES INDICES : 💻 NASDAQ et 📊 SP500 pointent TOUJOURS dans le même sens. Aucune divergence tolérée.\n" +
    "2. AMPLIFICATION BITCOIN : ₿ BITCOIN calque sa direction sur 💻 NASDAQ SAUF si driver Crypto Spécifique (RÈGLE 10) actif — auquel cas Bitcoin est le driver primaire.\n" +
    "3. AUDUSD PROXY CHINE : 🇦🇺 AUDUSD est le baromètre de la demande chinoise et du sentiment Risk-On global. En l'absence de driver Chine, il suit la direction Risk-On/Risk-Off dominante.\n" +
    "4. CORRÉLATION EURUSD/GBPUSD : Les deux paires partagent une corrélation de 0.85-0.96. Une divergence n'est tolérée que si des drivers BCE et BoE distincts et opposés sont simultanément présents dans les données.\n" +
    "5. USDCAD PÉTROLE : En cas de driver énergie fort (RÈGLE 4 ou RÈGLE 6), 🇨🇦 USDCAD DOIT refléter la direction du pétrole (pétrole monte = USDCAD baisse). Cette corrélation prime sur la direction Dollar général.\n" +
    "6. EXCLUSION ET CONCISION : Pas de politesse, pas de salutations, pas de résumés verbeux. Les 11 actifs doivent tous figurer sans omission.\n" +
    "7. EXCLUSIVITÉ MARKDOWN TELEGRAM : Un SEUL astérisque (*texte*) pour le gras. Les doubles astérisques (**) sont STRICTEMENT INTERDITS — ils corrompent l'affichage Telegram.\n" +
    "8. ANCRAGE DONNÉES : Chaque driver des DRIVERS PRINCIPAUX doit être traçable à une SOURCE + TITRE dans les données brutes. Aucune extrapolation sans ancrage explicite.\n";
                    
        // Traitement de l'enveloppe de prompt (Filtres géopolitiques complexes de votre script)
        String systemPromptFinal = construirePromptQuotidienSystem(dailyDrivers, DAILY_SYSTEM_PROMPT);

                JSONObject payload = new JSONObject();
        payload.put("model", GROQ_MODEL);
        payload.put("temperature", 0.02);

        JSONArray messages = new JSONArray();
        messages.put(new JSONObject().put("role", "system").put("content", systemPromptFinal));
        // ✅ Snapshot marché injecté dans le daily comme dans le pipeline news live
String dailyMarketSnapshot = "Données de marché indisponibles.";
try {
    List<String> allAssets = new ArrayList<>(Arrays.asList(
        "GOLD","NASDAQ","SP500","BITCOIN","EURUSD",
        "USDJPY","GBPUSD","AUDUSD","USDCAD","USOIL","US10Y"
    ));
    Map<String, MarketDataFetcher.MarketData> snap =
        MarketDataFetcher.getMarketDataBatch(allAssets);
    if (snap != null && !snap.isEmpty()) {
        StringBuilder sbM = new StringBuilder("📊 COURS AU MOMENT DU RAPPORT :\n");
        for (Map.Entry<String, MarketDataFetcher.MarketData> e : snap.entrySet()) {
            MarketDataFetcher.MarketData d = e.getValue();
            if (d != null && d.price > 0) {
                String sign = d.changePercent >= 0 ? "+" : "";
                sbM.append(e.getKey()).append(" => ")
                   .append(String.format(Locale.US, "%.4f (%s%.2f%%)", d.price, sign, d.changePercent))
                   .append("\n");
            }
        }
        dailyMarketSnapshot = sbM.toString();
    }
} catch (Exception e) {
    Log.w(TAG, "[DAILY] Snapshot marché indisponible : " + e.getMessage());
}

messages.put(new JSONObject().put("role", "user").put("content",
    "Génère le rapport périodique pour la date/heure : " + dateStr + " (Mada).\n" +
    dailyMarketSnapshot + "\n" +
    "DONNÉES BRUTES DES DERNIÈRES 24H :\n" + dailyDrivers));
        payload.put("messages", messages);

        URL url = new URL(GROQ_URL);
        conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        conn.setRequestProperty("Authorization", "Bearer " + apiKey);
        conn.setConnectTimeout(15000); 
        conn.setReadTimeout(20000);
        conn.setDoOutput(true);

        // ✅ Gestion sécurisée de l'OutputStream
        try (OutputStream os = conn.getOutputStream()) {
            byte[] input = payload.toString().getBytes(StandardCharsets.UTF_8);
            os.write(input, 0, input.length);
            os.flush();
        }

        int responseCode = conn.getResponseCode();
        if (responseCode == HttpURLConnection.HTTP_OK) {
            StringBuilder r = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    r.append(line);
                }
            }
            
            JSONObject jsonResponse = new JSONObject(r.toString());
            String aiResult = jsonResponse.getJSONArray("choices")
                                         .getJSONObject(0)
                                         .getJSONObject("message")
                                         .getString("content");

            if (aiResult != null && aiResult.trim().length() > 50) {
                sendTelegramSecure(aiResult.trim(), this);
                Log.d(TAG, "[DAILY] Rapport IA standard généré et envoyé avec succès.");
            }
        } else {
            try (BufferedReader errorReader = new BufferedReader(new InputStreamReader(conn.getErrorStream(), StandardCharsets.UTF_8))) {
                StringBuilder errorResponse = new StringBuilder();
                String line;
                while ((line = errorReader.readLine()) != null) {
                    errorResponse.append(line);
                }
                Log.e(TAG, "[DAILY] Erreur HTTP " + responseCode + " de l'API Groq : " + errorResponse.toString());
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "[DAILY] Échec critique lors du traitement du briefing journalier", e);
            } finally {
                if (conn != null) conn.disconnect();
            }
    }


    private void startMonthlyReportScheduler() {
        Calendar nextRun = Calendar.getInstance(TimeZone.getTimeZone("GMT+3"));
        nextRun.set(Calendar.DAY_OF_MONTH, nextRun.getActualMaximum(Calendar.DAY_OF_MONTH));
        nextRun.set(Calendar.HOUR_OF_DAY, 23);
        nextRun.set(Calendar.MINUTE, 0);
        nextRun.set(Calendar.SECOND, 0);
        if (nextRun.getTimeInMillis() <= System.currentTimeMillis()) {
            nextRun.add(Calendar.MONTH, 1);
            nextRun.set(Calendar.DAY_OF_MONTH, nextRun.getActualMaximum(Calendar.DAY_OF_MONTH));
        }
        scheduler.scheduleAtFixedRate(this::generateAndPurgeMonthlyReport, nextRun.getTimeInMillis() - System.currentTimeMillis(), 30L * 24 * 60 * 60 * 1000, TimeUnit.MILLISECONDS);
    }

    private void generateAndPurgeMonthlyReport() {
        HttpURLConnection conn = null;
        try {
            String apiKey = getGroqApiKey();
            if (apiKey.isEmpty()) return;

            long now = System.currentTimeMillis() / 1000;
            String monthlyRegistry = eventDb.getMonthlyMacroRegistry(now);
            if (monthlyRegistry.isEmpty()) return;

            JSONObject payload = new JSONObject();
            payload.put("model", GROQ_MODEL);
            payload.put("temperature", 0.1);

            JSONArray messages = new JSONArray();
            messages.put(new JSONObject().put("role", "system").put("content", "Analyse le registre mensuel des ruptures fondamentales."));
            messages.put(new JSONObject().put("role", "user").put("content", "REGISTRE MENSUEL :\n" + monthlyRegistry));
            payload.put("messages", messages);

            URL url = new URL(GROQ_URL);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + apiKey);
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(10000);
            conn.setDoOutput(true);

            OutputStream os = conn.getOutputStream();
            os.write(payload.toString().getBytes("UTF-8"));
            os.flush();
            os.close();

            if (conn.getResponseCode() == 200) {
                BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
                StringBuilder r = new StringBuilder();
                String l;
                while ((l = br.readLine()) != null) r.append(l);
                br.close();

                String report = new JSONObject(r.toString()).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content");

                sendTelegramSecure("📊 *RAPPORT DE TRANSITION MACROÉCONOMIQUE MENSUEL*\n\n" + report, this);
                eventDb.purgeOldEvents(now);
            }
        } catch (Exception e) { Log.e(TAG, "Erreur Rapport Mensuel", e); }
        finally {
            if (conn != null) conn.disconnect();
        }
    }

    private void registerNetworkCallback() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            cm.registerDefaultNetworkCallback(new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    triggerQueueSynchronization();
                }
            });
        }
    }

    private boolean isDeviceOnline() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Network net = cm.getActiveNetwork();
            if (net == null) return false;
            NetworkCapabilities cap = cm.getNetworkCapabilities(net);
            return cap != null && (cap.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || cap.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR));
        }
        return false;
    }

    private String generateSecureHash(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes("UTF-8"));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception e) {
            return String.valueOf(System.currentTimeMillis());
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Trading Core Alerts", NotificationManager.IMPORTANCE_HIGH);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }
    // ── NOUVELLE MÉTHODE DE MAINTENANCE PÉRIODIQUE ──
    private void syncCalendarAndPurge() {
        if (isSyncing) return;
        isSyncing = true;
        
        exec.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    Log.d(TAG, "[MAINTENANCE] Démarrage de la synchronisation et de la purge périodique...");
                    
                    // Configuration du timestamp actuel en secondes
                    long nowSeconds = System.currentTimeMillis() / 1000;

                    // 1. Purge SQLite (Règle des 48h / 45 jours via votre instance eventDb)
                    if (eventDb != null) {
                        eventDb.purgeOldEvents(nowSeconds);
                        Log.d(TAG, "[MAINTENANCE] Base de données SQLite purgée.");
                    }

                    // 2. Nettoyage de la table des empreintes pour éviter les fuites RAM
                    EventValidator.cleanupOldFingerprints();
                    Log.d(TAG, "[MAINTENANCE] Table des empreintes mémoires RAM nettoyée.");

                    // 3. Re-synchronisation du calendrier pour les prochaines 24 heures
                    EventValidator.preloadCalendar();
                    Log.d(TAG, "[MAINTENANCE] Calendrier économique mis à jour.");
                    
                } catch (Exception e) {
                    Log.e(TAG, "[MAINTENANCE] Erreur lors de la maintenance périodique", e);
                } finally {
                    isSyncing = false;
                }
            }
        });
    }
    // ── AJOUT DE COMPATIBILITÉ CHRONOLOGIQUE (MADA) ──
    private String getMadaFormattedDateTime() {
        try {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
            // On récupère dynamiquement le fuseau horaire depuis votre utilitaire existant
            sdf.setTimeZone(getMadaCalendar().getTimeZone());
            return sdf.format(new Date());
        } catch (Exception e) {
            return "N/A";
        }
    }
    // Méthode 1 — version simple sans paramètre prompt
    public String construirePromptFinal(String evenementActuel, List<String> historiqueRecent) {
            boolean alerteGéoMajeure = false;
            String[] motsClesCrise = {
                "hormuz", "ormuz", "détroit d'hormuz", "strait of hormuz",
                "iran", "israel", "hezbollah", "houthi", "frappe militaire",
                "airstrike", "missile", "drone attack", "riposte", "escalade",
                "blocus", "blockade", "raid", "invasion"
            };
            String toutLeTexte = (evenementActuel != null ? evenementActuel.toLowerCase() : "");
            if (historiqueRecent != null) {
                for (String hist : historiqueRecent) {
                    if (hist != null) toutLeTexte += " " + hist.toLowerCase();
                }
            }
            for (String mot : motsClesCrise) {
                if (toutLeTexte.contains(mot)) { alerteGéoMajeure = true; break; }
            }
            String directiveDeCrise = "";
            if (alerteGéoMajeure) {
                directiveDeCrise =
                    "⚠️ [ALERTE SYSTÈME : RÉGIME DE MARCHÉ EN MODE CRISE GÉOPOLITIQUE ACTIF]. " +
                    "Le risque de guerre au Moyen-Orient ou une menace sur le Détroit d'Hormuz est prioritaire. " +
                    "L'Or (GOLD) doit refléter le flux refuge (Safe-Haven). " +
                    "Appliquer immédiatement la CONTRAINTE 11.\n\n";
            }
            return directiveDeCrise + SYSTEM_PROMPT;
    }
        
    // Méthode 2 — version avec prompt personnalisé (séparée, au même niveau)
    public String construirePromptFinalAvecPrompt(String evenementActuel,
                List<String> historiqueRecent, String basePrompt) {
            boolean alerteGéoMajeure = false;
            String[] motsClesCrise = {
                "hormuz", "ormuz", "détroit d'hormuz", "strait of hormuz",
                "iran", "israel", "hezbollah", "houthi", "frappe militaire",
                "airstrike", "missile", "drone attack", "riposte", "escalade",
                "blocus", "blockade", "raid", "invasion"
            };
            String toutLeTexte = (evenementActuel != null ? evenementActuel.toLowerCase() : "");
            if (historiqueRecent != null) {
                for (String hist : historiqueRecent) {
                    if (hist != null) toutLeTexte += " " + hist.toLowerCase();
                }
            }
            for (String mot : motsClesCrise) {
                if (toutLeTexte.contains(mot)) { alerteGéoMajeure = true; break; }
            }
            String directiveDeCrise = "";
            if (alerteGéoMajeure) {
                directiveDeCrise =
                    "⚠️ [ALERTE SYSTÈME : RÉGIME DE MARCHÉ EN MODE CRISE GÉOPOLITIQUE ACTIF]. " +
                    "Appliquer immédiatement la CONTRAINTE 11.\n\n";
            }
            // ✅ Utilise basePrompt (contient le guidage mathématique si présent)
            return directiveDeCrise + basePrompt;
    }

     // Méthode de vérification géographique des mots-clés
     public String construirePromptQuotidienSystem(String registreDeLaJournee, String promptDeBase) {
        boolean alerteGéoMajeure = false;
        
        // Liste enrichie des mots-clés de crise
        String[] motsClesCrise = {
            "hormuz", "ormuz", "détroit d'hormuz", "strait of hormuz",
            "iran", "israel", "hezbollah", "houthi", "frappe militaire", 
            "airstrike", "missile", "drone attack", "riposte", "escalade", 
            "blocus", "blockade", "raid", "invasion"
        };
    
        String toutLeTexte = (registreDeLaJournee != null) ? registreDeLaJournee.toLowerCase() : "";
        for (String mot : motsClesCrise) {
            if (toutLeTexte.contains(mot)) {
                alerteGéoMajeure = true;
                break;
            }
        }
    
        // Si un mot-clé de crise est détecté, on injecte une directive d'activation en tête de prompt
        if (alerteGéoMajeure) {
            String alerteFlash = 
                "⚠️ [ALERTE SYSTÈME CRITIQUE : EXCEPTION DE CRISE ACTIVE].\n" +
                "Le registre des dernières 24h fait état d'une ESCALADE MILITAIRE DIRECTE ou d'une MENACE SUR L'OFFRE (notamment Hormuz).\n" +
                "CONSIGNE : Tu te trouves dans le cas d'exception absolue décrit à la CONTRAINTE 11. Active immédiatement la matrice géopolitique prioritaire (Régime de dominance géopolitique sur l'inflation) pour l'alignement des 11 actifs et le fait marquant.\n\n";
            
            return alerteFlash + promptDeBase;
        }
    
        // Sinon, on renvoie le prompt standard (la hiérarchie normale s'applique)
        return promptDeBase;
    }

  @Override
  public void onDestroy() {
    super.onDestroy();

    serviceInstance = null; // ✅ Libération immédiate du singleton

    // 1. Demande d'arrêt simultanée et non bloquante
    scheduler.shutdown();
    exec.shutdown();
    tradingPipelineExecutor.shutdown();

    // 2. Attente de la terminaison propre (500ms max pour éviter les ANR sous Android)
    try {
        if (!scheduler.awaitTermination(500, TimeUnit.MILLISECONDS)) {
            scheduler.shutdownNow();
        }
        if (!exec.awaitTermination(500, TimeUnit.MILLISECONDS)) {
            exec.shutdownNow();
        }
        if (!tradingPipelineExecutor.awaitTermination(500, TimeUnit.MILLISECONDS)) {
            tradingPipelineExecutor.shutdownNow();
        }
    } catch (InterruptedException e) {
        // En cas d'interruption, on force uniquement les exécuteurs encore actifs
        if (!scheduler.isTerminated()) scheduler.shutdownNow();
        if (!exec.isTerminated()) exec.shutdownNow();
        if (!tradingPipelineExecutor.isTerminated()) tradingPipelineExecutor.shutdownNow();
        Thread.currentThread().interrupt();
    }

    // 3. Arrêt simplifié de MarketDataFetcher (gestion des erreurs interne)
    MarketDataFetcher.shutdownExecutor();

    // 4. Fermeture sécurisée de la base de données
    if (eventDb != null) {
        eventDb.close(); 
    }

    Log.d(TAG, "[SERVICE] Service arrêté proprement");
}
        
}
