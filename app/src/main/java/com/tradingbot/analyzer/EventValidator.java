package com.tradingbot.analyzer;

import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import android.util.Log;
import android.content.Context;

public class EventValidator {

    private static Map<String, EconomicCalendarAPI.CalendarEvent> upcomingEvents =
        new ConcurrentHashMap<>();
    
    private static final Map<String, Long> recentFingerprints = new ConcurrentHashMap<>(256);
    private static final long DUPLICATE_WINDOW_MS = 30 * 60 * 1000L; // 45 minutes
    private static final String TAG = "EventValidator";

    private static EventDatabase getDatabase(Context context) {
        return EventDatabase.getInstance(context);
    }

    // ─────────────────────────────────────────────────────────────
    //  RÉSULTAT DE VALIDATION
    // ─────────────────────────────────────────────────────────────
    public static class ValidationResult {
        public boolean isConfirmed    = false;
        public int     confidence     = 0;
        public String  forecast       = "N/A";
        public String  previous       = "N/A";
        public String  actual         = "N/A";
        public boolean assetsEnriched = false;
        public String  reason         = "";
        public String  geoContext     = "";   // Description de la zone géopolitique détectée

        public ValidationResult() {}

        public ValidationResult(boolean isConfirmed, int confidence, String reason) {
            this.isConfirmed = isConfirmed;
            this.confidence  = confidence;
            this.reason      = reason;
        }
    }

    /**
     * Méthode principale de validation et d'enrichissement de la matrice d'actifs
     */
    public static ValidationResult validate(
            String title,
            String content,
            long timestamp,
            List<String> detectedAssets
    ) {
        ValidationResult result = new ValidationResult();

        if (title == null) title = "";
        if (content == null) content = "";
        if (detectedAssets == null) detectedAssets = new ArrayList<>();

        String combined = (title + " " + content).toLowerCase(Locale.ROOT);
        String upperCombined = (title + " " + content).toUpperCase(Locale.ROOT);
        
        // ── EXTRACTION PRIORITAIRE ET SYSTÉMATIQUE DES ACTIFS (Sécurité Rang Suprême) ──
        try {
            List<String> rawExtracted = new ArrayList<>();
            String textToScan = upperCombined;

            if (textToScan.contains("EURUSD") || textToScan.contains("EUR/") || textToScan.contains("EURO")) rawExtracted.add("EURUSD");
            if (textToScan.contains("USDJPY") || textToScan.contains("JPY")  || textToScan.contains("YEN"))  rawExtracted.add("USDJPY");
            if (textToScan.contains("GBPUSD") || textToScan.contains("GBP/") || textToScan.contains("POUND")) rawExtracted.add("GBPUSD");
            if (textToScan.contains("AUDUSD") || textToScan.contains("AUD/"))                                rawExtracted.add("AUDUSD");
            if (textToScan.contains("USDCAD") || textToScan.contains("CAD/"))                                rawExtracted.add("USDCAD");
            if (textToScan.contains("GOLD")   || textToScan.contains("XAU"))                                 rawExtracted.add("GOLD");
            if (textToScan.contains("USOIL")  || textToScan.contains("CRUDE") || textToScan.contains("WTI") || textToScan.contains("PETROLE") || textToScan.contains("BRENT")) rawExtracted.add("USOIL");
            if (textToScan.contains("NASDAQ") || textToScan.contains("NAS100")|| textToScan.contains("USTECH") || textToScan.contains("TECH")) rawExtracted.add("NASDAQ");
            if (textToScan.contains("SP500")  || textToScan.contains("S&P")   || textToScan.contains("SPX"))   rawExtracted.add("SP500");
            if (textToScan.contains("BITCOIN")|| textToScan.contains("BTC"))                                 rawExtracted.add("BITCOIN");
            if (textToScan.contains("US10Y")  || textToScan.contains("TREASURY") || textToScan.contains("YIELD") || textToScan.contains("10-YEAR")) rawExtracted.add("US10Y");

            if (rawExtracted != null) {
                for (String asset : rawExtracted) {
                    if (asset != null && !detectedAssets.contains(asset)) {
                        detectedAssets.add(asset);
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Erreur lors de l'extraction brute des actifs", e);
        }

        // ── ÉTAPE 1 : Anti-Doublons (très haut dans le flux) ─────────────
        if (isRecentDuplicate(title, content)) {
            result.confidence  = 0;
            result.isConfirmed = false;
            result.reason      = "Doublon récent détecté (30min)";
            result.assetsEnriched = !detectedAssets.isEmpty();
            logToMain("🔄 Doublon identifié (Enrichissement préservé)");
            return result;
        }

        // ── INERTIE MACRO (éviter plusieurs analyses sur le même driver majeur) ─────
        String detectedType = EconomicEventDetector.detectEvent(title, content).eventType;
        EventDatabase db = (MainActivity.instance != null) ? getDatabase(MainActivity.instance) : null;
        if (!detectedType.startsWith("GEO") && db != null) {
            try {
                long currentSeconds = timestamp / 1000;
                if (db.isDriverActiveRecently(detectedType, currentSeconds)) {
                    result.confidence  = 0;
                    result.isConfirmed = false;
                    result.reason      = "Driver déjà actif récemment (Inertie Macro)";
                    result.assetsEnriched = !detectedAssets.isEmpty();
                    logToMain("[⏳ Driver " + detectedType + " déjà actif — ignoré");
                    return result;
                }
            } catch (Exception e) {
                Log.e(TAG, "Erreur inertie macro", e);
            }
        }

        // ── ÉTAPE 2 : Filtre anti-rumeur absolu ───────────────────────────
        if (containsRumorMarkers(combined)) {
            result.confidence  = 0;
            result.isConfirmed = false;
            result.reason      = "Rejeté — Marqueur de rumeur ou non-confirmé détecté";
            String shortTitle = !title.isEmpty() ? title.substring(0, Math.min(50, title.length())) : "?";
            logToMain("❌ Rumeur/Non-confirmé rejeté – " + shortTitle + "…");
            return result;
        }

        // ── ÉTAPE 3 : Filtre éditorial ───────────────────────────────────
        if (containsEditorialContent(combined)) {
            result.confidence  = 0;
            result.isConfirmed = false;
            result.reason      = "Bruit macroéconomique (Opinion/Éditorial pur)";
            String shortTitle = !title.isEmpty() ? title.substring(0, Math.min(50, title.length())) : "?";
            logToMain("❌ Rejeté – Contenu éditorial – " + shortTitle + "…");
            return result;
        }

        // ── ÉTAPE 4 : Calendrier économique ──────────────────────────────
        EconomicCalendarAPI.CalendarEvent match = findMatchingEvent(title, content, timestamp);
        if (match != null) {
            result.isConfirmed = true;
            result.confidence  = 98;
            result.forecast    = match.forecast != null ? match.forecast : "N/A";
            result.previous    = match.previous != null ? match.previous : "N/A";
            result.actual      = match.actual   != null ? match.actual   : "N/A";
            result.reason      = "Confirmé par calendrier économique";

            if (match.affectedAssets != null) {
                for (String asset : match.affectedAssets) {
                    if (asset != null && !detectedAssets.contains(asset)) {
                        detectedAssets.add(asset);
                    }
                }
            }
            result.assetsEnriched = !detectedAssets.isEmpty();
            String indicatorName = (match.indicator != null && !match.indicator.isEmpty()) ? match.indicator.substring(0, Math.min(40, match.indicator.length())) : "événement";
            logToMain("✓ Calendrier confirmé – " + indicatorName);
            return result;
        }

        // ── ÉTAPE 5 : Géopolitique ───────────────────────────────────────
        GeoAssessment geo = assessGeopoliticalEvent(combined, upperCombined);
        if (geo.confidence >= 65) {
            result.isConfirmed = true;
            result.confidence  = geo.confidence;
            result.reason      = "Événement géopolitique confirmé";
            result.geoContext  = geo.contextLabel;

            for (String asset : geo.impactedAssets) {
                if (asset != null && !detectedAssets.contains(asset)) {
                    detectedAssets.add(asset);
                }
            }
            result.assetsEnriched = !detectedAssets.isEmpty();
            String shortTitle = !title.isEmpty() ? title.substring(0, Math.min(40, title.length())) : "?";
            logToMain("🌍 Géo confirmé [" + geo.contextLabel + "] " + geo.confidence + "% – " + shortTitle + "…");
            return result;
        }

        // ── ÉTAPE 6 : Breaking News générique ───────────────────────────
        result.confidence = calculateBreakingNewsConfidence(title, content);
        result.reason      = "Breaking News (Flux Interbancaire)";

        if (result.confidence < 70) {   
            result.confidence  = 0;
            result.isConfirmed = false;
            String shortTitle = !title.isEmpty() ? title.substring(0, Math.min(40, title.length())) : "?";
            logToMain("❌ Rejeté – " + shortTitle + "… (confiance " + result.confidence + "%)");
        } else {
            result.isConfirmed = true;
            String shortTitle = !title.isEmpty() ? title.substring(0, Math.min(50, title.length())) : "?";
            logToMain("⚡ Breaking News retenu – " + shortTitle + "… (confiance " + result.confidence + "%)");
        }

        result.assetsEnriched = !detectedAssets.isEmpty();
        return result;
    }

    private static boolean containsRumorMarkers(String text) {
        if (text == null) return false;
        return text.contains("rumor")        ||
               text.contains("rumour")       ||
               text.contains("allegedly")    ||
               text.contains("unconfirmed")  ||
               text.contains("sources say")  ||
               text.contains("source says")  ||
               text.contains("could attack") ||
               text.contains("may attack")   ||
               text.contains("might strike") ||
               text.contains("speculated")   ||
               text.contains("speculation")  ||
               text.contains("supposed to")  ||
               text.contains("not confirmed")||
               text.contains("not verified") ||
               text.contains("fake news")    ||
               text.contains("unverified");
    }

    private static boolean containsEditorialContent(String text) {
        if (text == null) return false;
        return text.contains("opinion")    ||
               text.contains("editorial")  ||
               text.contains("op-ed")      ||
               text.contains("commentary") ||
               text.contains("think tank") ||
               text.contains("columnist")  ||
               text.contains("pundit")     ||
               text.contains("satire")     ||
               text.contains("parody");
    }

    private static class GeoAssessment {
        int          confidence    = 0;
        String       contextLabel  = "";
        List<String> impactedAssets = new ArrayList<>();
    }

    private static GeoAssessment assessGeopoliticalEvent(String text, String upperText) {
        GeoAssessment geo = new GeoAssessment();
        int score = 0;
        String lowerText = text.toLowerCase(Locale.ROOT);

        // ── A. Source crédible ───────────────────────────────────────
        boolean hasCredibleSource =
            lowerText.contains("reuters")         ||
            lowerText.contains("bloomberg")       ||
            lowerText.contains("associated press")||
            lowerText.contains(" ap ")            ||
            lowerText.contains("idf")             ||
            lowerText.contains("pentagon")        ||
            lowerText.contains("nato")            ||
            lowerText.contains("white house")     ||
            lowerText.contains("ministry of")     ||
            lowerText.contains("times of israel") ||
            lowerText.contains("al jazeera")      ||
            lowerText.contains("bbc")             ||
            lowerText.contains("cnn")             ||
            lowerText.contains("axios")           ||
            lowerText.contains("haaretz")         ||
            lowerText.contains("fxhedgers")       ||
            lowerText.contains("deltaone");

        if (hasCredibleSource) score += 25;

        // ── B. Action militaire/géo concrète ─────────────────────────
        boolean hasFactualAction =
            lowerText.contains("fired")        ||
            lowerText.contains("launched")     ||
            lowerText.contains("struck")       ||
            lowerText.contains("hit")          ||
            lowerText.contains("attacked")     ||
            lowerText.contains("bombed")       ||
            lowerText.contains("intercepted")  ||
            lowerText.contains("shot down")    ||
            lowerText.contains("killed")       ||
            lowerText.contains("destroyed")    ||
            lowerText.contains("airstrike")    ||
            lowerText.contains("air strike")   ||
            lowerText.contains("missile")      ||
            lowerText.contains("drone")        ||
            lowerText.contains("explosion")    ||
            lowerText.contains("raid")         ||
            lowerText.contains("invasion");

        if (hasFactualAction) score += 32;

        // ── C. Zone géographique à impact marché direct (Enrichi) ──────────────
        boolean geoZoneFound = false;

        // Zone 1 : Moyen-Orient + Hormuz (priorité maximale)
        boolean isMoyenOrient =
            lowerText.contains("israel")       ||
            lowerText.contains("iran")         ||
            lowerText.contains("gaza")         ||
            lowerText.contains("lebanon")      ||
            lowerText.contains("hezbollah")    ||
            lowerText.contains("hamas")        ||
            lowerText.contains("houthi")       ||
            lowerText.contains("yemen")        ||
            lowerText.contains("red sea")      ||
            upperText.contains("HORMUZ")       ||
            upperText.contains("ORMUZ")        ||
            lowerText.contains("persian gulf") ||
            lowerText.contains("saudi")        ||
            lowerText.contains("tel aviv")     ||
            lowerText.contains("jerusalem")    ||
            lowerText.contains("beirut")       ||
            lowerText.contains("tehran")       ||
            lowerText.contains("middle east")  ||
            lowerText.contains("moyen-orient");

        boolean isTrumpIran = lowerText.contains("trump") && lowerText.contains("iran");

        if (isMoyenOrient) {
            if (upperText.contains("HORMUZ") || upperText.contains("ORMUZ")) {
                geo.contextLabel = "Détroit d'Hormuz - Menace sur l'offre pétrole";
                score += 40;
                geo.impactedAssets.addAll(Arrays.asList("USOIL", "GOLD", "USDJPY", "NASDAQ", "SP500", "BITCOIN", "EURUSD", "US10Y"));
            } else if (hasFactualAction) {
                geo.contextLabel = "Moyen-Orient - Action Militaire";
                score += 35;
                geo.impactedAssets.addAll(Arrays.asList("USOIL", "GOLD", "USDJPY", "NASDAQ", "SP500", "BITCOIN", "EURUSD", "AUDUSD", "US10Y"));
            } else if (isTrumpIran) {
                geo.contextLabel = "Moyen-Orient - Déclaration Trump/Iran";
                score += 18;   
                geo.impactedAssets.addAll(Arrays.asList("GOLD", "USOIL", "USDJPY", "NASDAQ", "SP500"));
            } else {
                geo.contextLabel = "Moyen-Orient / Pétrole";
                score += 22;
                geo.impactedAssets.addAll(Arrays.asList("GOLD", "USOIL", "USDJPY", "US10Y"));
            }
            geoZoneFound = true;
        }

        // Zone 2 : Europe de l'Est
        boolean isEuropeEst =
            lowerText.contains("ukraine")      ||
            lowerText.contains("russia")       ||
            lowerText.contains("moscow")       ||
            lowerText.contains("kyiv")         ||
            lowerText.contains("kiev")         ||
            lowerText.contains("kremlin")      ||
            lowerText.contains("nato")         ||
            lowerText.contains("putin")        ||
            lowerText.contains("zelensky")     ||
            lowerText.contains("donbas")       ||
            lowerText.contains("crimea")       ||
            lowerText.contains("black sea");

        if (isEuropeEst && !geoZoneFound) {
            geo.contextLabel = "Europe de l'Est / OTAN";
            geo.impactedAssets.addAll(Arrays.asList("EURUSD", "GBPUSD", "USOIL", "GOLD", "USDJPY", "NASDAQ", "SP500", "BITCOIN"));
            score += 20;
            geoZoneFound = true;
        }

        // Zone 3 : Asie-Pacifique
        boolean isAsiePacifique =
            lowerText.contains("china")        ||
            lowerText.contains("taiwan")       ||
            lowerText.contains("beijing")      ||
            lowerText.contains("south china sea") ||
            lowerText.contains("north korea")  ||
            lowerText.contains("tsmc")         ||
            lowerText.contains("semiconductor");

        if (isAsiePacifique && !geoZoneFound) {
            geo.contextLabel = "Asie-Pacifique / Chine";
            geo.impactedAssets.addAll(Arrays.asList("AUDUSD", "USDJPY", "NASDAQ", "SP500", "GOLD", "BITCOIN", "USOIL"));
            score += 20;
            geoZoneFound = true;
        }

        if (!geoZoneFound && hasFactualAction) {
            geo.contextLabel = "Événement Géo Non Régionalisé";
            geo.impactedAssets.addAll(Arrays.asList("GOLD", "USDJPY", "USOIL"));
            score += 12;
        }

        // ── D. Entité précise ────────────────────────────────────────
        if (lowerText.matches(".*\\d+\\s*(drone|missile|rocket|soldier|ship|bomb).*") ||
            lowerText.matches(".*\\d+\\s*(km|miles|kilometers).*")                    ||
            lowerText.contains("confirmed dead") || lowerText.contains("confirmed killed")) {
            score += 15;
        }

        // ── E. Confirmation officielle ───────────────────────────────
        if (lowerText.contains("confirmed")       ||
            lowerText.contains("official said")   ||
            lowerText.contains("officials said")  ||
            lowerText.contains("announced")       ||
            lowerText.contains("idf confirmed")   ||
            lowerText.contains("pentagon confirmed")) {
            score += 10;
        }

        boolean hasOfficialConfirmation = lowerText.contains("confirmed") || lowerText.contains("announced");
        if (isTrumpIran && !hasFactualAction && !hasOfficialConfirmation) {
            score = Math.min(score, 55);
        }

        if (!geoZoneFound && !hasFactualAction) score = Math.max(0, score - 15);

        geo.confidence = Math.min(100, score);
        return geo;
    }

    // ─────────────────────────────────────────────────────────────
    //  CALENDRIER ÉCONOMIQUE (inchangé)
private static EconomicCalendarAPI.CalendarEvent findMatchingEvent(String title, String content, long timestamp) {
    String combined = (title + " " + content).toLowerCase(Locale.ROOT);
    // Normalisation : supprime les caractères non alphanumériques, réduit les espaces
    String normalizedCombined = combined.replaceAll("[^a-z0-9\\s]", " ").replaceAll("\\s+", " ").trim();
    
    long window = 10 * 60 * 1000; // ±10 minutes
    for (EconomicCalendarAPI.CalendarEvent event : upcomingEvents.values()) {
        if (event == null || event.timestamp == null || event.indicator == null) continue;
        long eventTime = parseTimestamp(event.timestamp);
        if (Math.abs(eventTime - timestamp) < window) {
            String indicator = event.indicator.toLowerCase(Locale.ROOT);
            String normalizedIndicator = indicator.replaceAll("[^a-z0-9\\s]", " ").replaceAll("\\s+", " ").trim();
            
            if (normalizedCombined.contains(normalizedIndicator) || 
                matchesIndicatorKeywords(normalizedCombined, indicator, event.country)) {
                return event;
            }
        }
    }
    return null;
}

private static boolean matchesIndicatorKeywords(String text, String indicator, String country) {
    if (text == null || indicator == null) return false;
    String ind = indicator.toLowerCase(Locale.ROOT);
    // Table de synonymes
    if (ind.contains("nfp") || ind.contains("non-farm")) {
        return text.contains("nfp") || text.contains("non-farm") || text.contains("payroll") || text.contains("emploi");
    }
    if (ind.contains("cpi") || ind.contains("inflation")) {
        return text.contains("cpi") || text.contains("inflation") || text.contains("pce") || text.contains("prix à la consommation");
    }
    if (ind.contains("gdp") || ind.contains("growth")) {
        return text.contains("gdp") || text.contains("pib") || text.contains("gross domestic") || text.contains("croissance");
    }
    if (ind.contains("fed") || ind.contains("fomc") || ind.contains("rate")) {
        return text.contains("fed") || text.contains("rate") || text.contains("fomc") || text.contains("powell") || text.contains("taux");
    }
    if (ind.contains("ism") || ind.contains("pmi")) {
        return text.contains("ism") || text.contains("pmi") || text.contains("manufacturing") || text.contains("industrie");
    }
    if (ind.contains("retail")) {
        return text.contains("retail") || text.contains("ventes au détail") || text.contains("consumer spending");
    }
    if (ind.contains("jobless") || ind.contains("claims")) {
        return text.contains("jobless") || text.contains("claims") || text.contains("chômage");
    }
    return false;
}

    // ─────────────────────────────────────────────────────────────
    //  BREAKING NEWS GÉNÉRIQUE
    // ─────────────────────────────────────────────────────────────
    private static int calculateBreakingNewsConfidence(String title, String content) {
        int score = 40;
        String lower = ((title != null ? title : "") + " " + (content != null ? content : "")).toLowerCase(Locale.ROOT);

        if (lower.contains("breaking"))                  score += 25;
        if (lower.contains("urgent") || lower.contains("alert"))    score += 20;
        if (lower.contains("fxhedgers") || lower.contains("deltaone")) score += 25;
        if (lower.contains("federal reserve") || lower.contains("fomc")) score += 20;
        if (content != null && content.matches(".*\\d+\\.\\d+%.*")) score += 15;

        return Math.min(100, score);
    }

    // ─────────────────────────────────────────────────────────────
    //  UTILITAIRES SÉCURISÉS (inchangés)
    // ─────────────────────────────────────────────────────────────
    private static String createEventKey(String indicator, String timestamp) {
        if (indicator == null || timestamp == null) {
            return UUID.randomUUID().toString();
        }
        return indicator.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "_") + "_" + timestamp;
    }

    private static long parseTimestamp(String timestamp) {
        if (timestamp == null) return System.currentTimeMillis();
        try {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
            return sdf.parse(timestamp).getTime();
        } catch (Exception e) {
            try {
                return Long.parseLong(timestamp) * 1000;
            } catch (Exception e2) {
                return System.currentTimeMillis();
            }
        }
    }

    private static void logToMain(String message) {
        if (MainActivity.instance != null) {
            try {
                MainActivity.instance.addLog(message);
            } catch (Exception e) {
                Log.e(TAG, "Erreur écriture log : " + message, e);
            }
        } else {
            Log.d(TAG, "[RAM-LOG] " + message);
        }
    }

    private static String generateFingerprint(String title, String content) {
        String t = (title != null) ? title : "";
        String c = (content != null) ? content : "";
        String combined = (t + " " + c).toLowerCase(Locale.ROOT)
                            .replaceAll("[^a-z0-9\\s]", " ")
                            .replaceAll("\\s+", " ")
                            .trim();

        return combined.substring(0, Math.min(90, combined.length()));
    }

    private static boolean isRecentDuplicate(String title, String content) {
        String fingerprint = generateFingerprint(title, content);
        long now = System.currentTimeMillis();

        Long lastSeen = recentFingerprints.get(fingerprint);
        if (lastSeen != null && (now - lastSeen) < DUPLICATE_WINDOW_MS) {
            return true;
        }
        recentFingerprints.put(fingerprint, now);

        if (recentFingerprints.size() > 180) {
            long cleanupThreshold = now - (2 * 60 * 60 * 1000L); // 2 heures
            recentFingerprints.entrySet().removeIf(entry -> entry.getValue() < cleanupThreshold);
        }
        return false;
    }

    public static void preloadCalendar() {
        try {
            List<EconomicCalendarAPI.CalendarEvent> events = EconomicCalendarAPI.fetchUpcomingEvents(24);
            if (events == null) return;
            upcomingEvents.clear();

            for (EconomicCalendarAPI.CalendarEvent event : events) {
                if (event == null) continue;
                String key = createEventKey(event.indicator, event.timestamp);
                upcomingEvents.put(key, event);
            }
        } catch (Exception e) {
            logToMain("[VALIDATOR] ⚠️ Échec préchargement calendrier : " + e.getMessage());
        }
    }

public static void cleanupOldFingerprints() {
    if (recentFingerprints == null || recentFingerprints.isEmpty()) return;
    
    long now = System.currentTimeMillis();
    // 2 heures converties explicitement en millisecondes (utilisation du suffixe L pour la sécurité)
    long cleanupThreshold = now - (2 * 60 * 60 * 1000L); 
    
    try {
        // removeIf est sûr ici grâce à l'utilisation d'une ConcurrentHashMap
        recentFingerprints.entrySet().removeIf(entry -> entry.getValue() < cleanupThreshold);
    } catch (Exception e) {
        Log.e("NotificationService", "Erreur lors du nettoyage des fingerprints", e);
    }
}
}
