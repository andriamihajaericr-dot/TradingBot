package com.tradingbot.analyzer;

import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import android.util.Log;

public class EventValidator {

    private static Map<String, EconomicCalendarAPI.CalendarEvent> upcomingEvents =
        new ConcurrentHashMap<>();
    
    private static final Map<String, Long> recentFingerprints = new ConcurrentHashMap<>(256);
    private static final long DUPLICATE_WINDOW_MS = 45 * 60 * 1000L; // 45 minutes

    // Ajouter en haut de la classe
    private static EventDatabase eventDatabase;
    public static void init(EventDatabase db) {
       eventDatabase = db;
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

        public static ValidationResult validate(
            String title,
            String content,
            long timestamp,
            List<String> detectedAssets
    ) {
        ValidationResult result = new ValidationResult();

        if (title    == null) title    = "";
        if (content  == null) content  = "";
        if (detectedAssets == null) detectedAssets = new ArrayList<>();

        String combined = (title + " " + content).toLowerCase();

        // ── ÉTAPE 1 : Anti-Doublons (très haut dans le flux) ─────────────
        if (isRecentDuplicate(title, content)) {
            result.confidence  = 0;
            result.isConfirmed = false;
            result.reason      = "Doublon récent détecté (45min)";
            logToMain("[VALIDATOR] 🔄 Doublon ignoré");
            return result;
        }
        // ── INERTIE MACRO (éviter plusieurs analyses sur le même driver majeur) ─────
        String detectedType = EconomicEventDetector.detectEvent(title, content).eventType;
        
        // On vérifie seulement si on est dans un contexte où la DB est accessible
        if (!detectedType.startsWith("GEO") && eventDatabase != null) {
            try {
                long currentSeconds = timestamp / 1000;
                if (eventDatabase.isDriverActiveRecently(detectedType, currentSeconds)) {
                    result.confidence  = 0;
                    result.isConfirmed = false;
                    result.reason      = "Driver déjà actif récemment (Inertie Macro)";
                    logToMain("[VALIDATOR] ⏳ Driver " + detectedType + " déjà actif — ignoré");
                    return result;
                }
            } catch (Exception e) {
                // Si erreur DB, on continue sans bloquer
                Log.e("EventValidator", "Erreur inertie macro", e);
            }
        }

        // ── ÉTAPE 2 : Filtre anti-rumeur absolu ───────────────────────────
        if (containsRumorMarkers(combined)) {
            result.confidence  = 0;
            result.isConfirmed = false;
            result.reason      = "Rejeté — Marqueur de rumeur ou non-confirmé détecté";
            logToMain("[VALIDATOR] ❌ Rumeur/Non-confirmé rejeté");
            return result;
        }

        // ── ÉTAPE 3 : Filtre éditorial ───────────────────────────────────
        if (containsEditorialContent(combined)) {
            result.confidence  = 0;
            result.isConfirmed = false;
            result.reason      = "Bruit macroéconomique (Opinion/Éditorial pur)";
            logToMain("[VALIDATOR] ❌ Rejeté — Contenu éditorial");
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
                        result.assetsEnriched = true;
                    }
                }
            }
            logToMain("[VALIDATOR] ✓ Calendrier confirmé");
            return result;
        }

        // ── ÉTAPE 5 : Géopolitique (déjà amélioré précédemment) ─────────
        GeoAssessment geo = assessGeopoliticalEvent(combined);
        if (geo.confidence >= 65) {
            result.isConfirmed = true;
            result.confidence  = geo.confidence;
            result.reason      = "Événement géopolitique confirmé";
            result.geoContext  = geo.contextLabel;

            for (String asset : geo.impactedAssets) {
                if (!detectedAssets.contains(asset)) {
                    detectedAssets.add(asset);
                    result.assetsEnriched = true;
                }
            }
            logToMain("[VALIDATOR] 🌍 Géo confirmé [" + geo.contextLabel + "] " + geo.confidence + "%");
            return result;
        }

        // ── ÉTAPE 6 : Breaking News générique (seuil plus strict) ───────
        result.confidence = calculateBreakingNewsConfidence(title, content);
        result.reason     = "Breaking News (Flux Interbancaire)";

        if (result.confidence < 70) {   // Augmenté de 65 → 70
            result.confidence  = 0;
            result.isConfirmed = false;
            MainActivity.instance.addLog("[VALIDATOR] ❌ Rejeté – " + (title != null ? title.substring(0, Math.min(40, title.length())) : "?") + "… (confiance " + result.confidence + "%)");
        } else {
            result.isConfirmed = true;
            logToMain("[VALIDATOR] ⚡ Breaking News retenu : " + result.confidence + "%");
        }

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

    // ─────────────────────────────────────────────────────────────
    //  FILTRE ÉDITORIAL (ÉTAPE 2) — version resserrée
    //  Ne bloque plus "party", "republican", "democrat" qui
    //  peuvent apparaître dans des news légitimes sur la Fed.
    // ─────────────────────────────────────────────────────────────

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

    // ─────────────────────────────────────────────────────────────
    //  ÉVALUATION GÉOPOLITIQUE (ÉTAPE 4)
    //
    //  Grille de scoring en 3 dimensions :
    //  A) Source crédible identifiée          → +25
    //  B) Action militaire/géo factualisée    → +30
    //  C) Zone géographique à impact marché   → +20
    //  D) Entité précise (nombre, lieu)       → +15
    //  E) Confirmation officielle citée       → +10
    //
    //  Chaque zone géo mappe vers des actifs précis.
    // ─────────────────────────────────────────────────────────────

    private static class GeoAssessment {
        int          confidence    = 0;
        String       contextLabel  = "";
        List<String> impactedAssets = new ArrayList<>();
    }
        private static GeoAssessment assessGeopoliticalEvent(String text) {
        GeoAssessment geo = new GeoAssessment();
        int score = 0;

        String lowerText = text.toLowerCase();

        // ── A. Source crédible (score source) ────────────────────
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

        // ── B. Action militaire/géo concrète (verbe factuel passé) ──
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

        // ── C. Zone géographique à impact marché direct ──────────
        boolean geoZoneFound = false;

        // Zone 1 : Moyen-Orient (Israël, Iran, Golfe, etc.)
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
            lowerText.contains("strait of hormuz") ||
            lowerText.contains("persian gulf") ||
            lowerText.contains("saudi")        ||
            lowerText.contains("tel aviv")     ||
            lowerText.contains("jerusalem")    ||
            lowerText.contains("beirut")       ||
            lowerText.contains("tehran")       ||
            lowerText.contains("middle east");

        boolean isTrumpIran = lowerText.contains("trump") && lowerText.contains("iran");

        if (isMoyenOrient) {
            if (hasFactualAction) {
                geo.contextLabel = "Moyen-Orient - Action Militaire";
                score += 26;
                geo.impactedAssets.addAll(Arrays.asList(
                    "USOIL", "GOLD", "USDJPY", "NASDAQ", "SP500", "BITCOIN", "EURUSD", "AUDUSD"
                ));
            } else if (isTrumpIran) {
                geo.contextLabel = "Moyen-Orient - Déclaration Trump/Iran";
                score += 11;   // Score très réduit pour éviter les faux positifs
                geo.impactedAssets.addAll(Arrays.asList("GOLD", "USOIL", "USDJPY", "NASDAQ", "SP500"));
            } else {
                geo.contextLabel = "Moyen-Orient / Pétrole";
                score += 17;
                geo.impactedAssets.addAll(Arrays.asList("GOLD", "USOIL", "USDJPY"));
            }
            geoZoneFound = true;
        }

        // Zone 2 : Europe de l'Est (Ukraine, Russie, OTAN)
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
            geo.impactedAssets.addAll(Arrays.asList(
                "EURUSD", "GBPUSD", "USOIL", "GOLD", "USDJPY", "NASDAQ", "SP500", "BITCOIN"
            ));
            score += 20;
            geoZoneFound = true;
        } else if (isEuropeEst) {
            if (!geo.impactedAssets.contains("EURUSD")) geo.impactedAssets.add("EURUSD");
            if (!geo.impactedAssets.contains("GBPUSD")) geo.impactedAssets.add("GBPUSD");
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
            geo.impactedAssets.addAll(Arrays.asList(
                "AUDUSD", "USDJPY", "NASDAQ", "SP500", "GOLD", "BITCOIN", "USOIL"
            ));
            score += 20;
            geoZoneFound = true;
        } else if (isAsiePacifique) {
            if (!geo.impactedAssets.contains("AUDUSD")) geo.impactedAssets.add("AUDUSD");
            if (!geo.impactedAssets.contains("NASDAQ")) geo.impactedAssets.add("NASDAQ");
        }

        // Zone 4 : Amérique Latine / Commerce
        boolean isAmeriqueLatine =
            lowerText.contains("mexico")       ||
            lowerText.contains("tariff")       ||
            lowerText.contains("trade war")    ||
            lowerText.contains("opec")         ||
            lowerText.contains("venezuela");

        if (isAmeriqueLatine && !geoZoneFound) {
            geo.contextLabel = "Commerce / OPEC / Amériques";
            geo.impactedAssets.addAll(Arrays.asList("USDCAD", "USOIL", "NASDAQ", "SP500"));
            score += 15;
            geoZoneFound = true;
        }

        // Zone 5 : Autres
        boolean isAutresZones =
            lowerText.contains("africa") || lowerText.contains("sudan") ||
            lowerText.contains("coup")   || lowerText.contains("civil war");

        if (isAutresZones && !geoZoneFound) {
            geo.contextLabel = "Géopolitique Émergent";
            geo.impactedAssets.addAll(Arrays.asList("GOLD", "USOIL"));
            score += 10;
            geoZoneFound = true;
        }

        // Si aucune zone mais action militaire → score partiel
        if (!geoZoneFound && hasFactualAction) {
            geo.contextLabel = "Événement Géo Non Régionalisé";
            geo.impactedAssets.addAll(Arrays.asList("GOLD", "USDJPY"));
            score += 8;
        }

        // ── D. Entité précise (nombre, lieu précis) ──────────────
        boolean hasPreciseEntity =
            lowerText.matches(".*\\d+\\s*(drone|missile|rocket|soldier|ship|bomb).*") ||
            lowerText.matches(".*\\d+\\s*(km|miles|kilometers).*")                    ||
            lowerText.contains("confirmed dead") || lowerText.contains("confirmed killed");

        if (hasPreciseEntity) score += 15;

        // ── E. Confirmation officielle citée ─────────────────────
        boolean hasOfficialConfirmation =
            lowerText.contains("confirmed")       ||
            lowerText.contains("official said")   ||
            lowerText.contains("officials said")  ||
            lowerText.contains("announced")       ||
            lowerText.contains("idf confirmed")   ||
            lowerText.contains("pentagon confirmed");

        if (hasOfficialConfirmation) score += 10;

        // ── ANTI-FAUX POSITIF (spécifique Twitter + Trump) ───────
        if (isTrumpIran && !hasFactualAction && !hasOfficialConfirmation) {
            score = Math.min(score, 55);   // Bloque juste sous le seuil de 65
        }

        // ── Pondération finale ────────────────────────────────────
        if (!geoZoneFound && !hasFactualAction) score = 0;

        geo.confidence = Math.min(100, score);
        return geo;
    }

    // ─────────────────────────────────────────────────────────────
    //  CALENDRIER ÉCONOMIQUE (ÉTAPE 3)
    // ─────────────────────────────────────────────────────────────

    private static EconomicCalendarAPI.CalendarEvent findMatchingEvent(
            String title, String content, long timestamp) {

        String combined = (title + " " + content).toLowerCase();
        long window = 10 * 60 * 1000; // Fenêtre stricte ±10 minutes

        for (EconomicCalendarAPI.CalendarEvent event : upcomingEvents.values()) {
            if (event == null || event.timestamp == null || event.indicator == null) continue;

            long eventTime = parseTimestamp(event.timestamp);

            if (Math.abs(eventTime - timestamp) < window) {
                String indicator = event.indicator.toLowerCase();
                String country   = event.country != null ? event.country : "";

                if (combined.contains(indicator) ||
                    matchesIndicatorKeywords(combined, indicator, country)) {
                    return event;
                }
            }
        }
        return null;
    }

    private static boolean matchesIndicatorKeywords(String text, String indicator, String country) {
        if (text == null || indicator == null) return false;
        if (indicator.contains("nfp") || indicator.contains("non-farm")) {
            return text.contains("nfp") || text.contains("non-farm") || text.contains("payroll");
        }
        if (indicator.contains("cpi") || indicator.contains("inflation")) {
            return text.contains("cpi") || text.contains("inflation") || text.contains("pce");
        }
        if (indicator.contains("gdp") || indicator.contains("growth")) {
            return text.contains("gdp") || text.contains("gross domestic");
        }
        if (indicator.contains("fed") || indicator.contains("fomc") || indicator.contains("rate")) {
            return text.contains("fed") || text.contains("rate") ||
                   text.contains("fomc") || text.contains("powell");
        }
        if (indicator.contains("ism") || indicator.contains("pmi")) {
            return text.contains("ism") || text.contains("pmi") || text.contains("manufacturing");
        }
        if (indicator.contains("retail")) {
            return text.contains("retail") || text.contains("consumer spending");
        }
        return false;
    }

    // ─────────────────────────────────────────────────────────────
    //  BREAKING NEWS GÉNÉRIQUE (ÉTAPE 5)
    // ─────────────────────────────────────────────────────────────

    private static int calculateBreakingNewsConfidence(String title, String content) {
        int score = 40;
        String lower = ((title != null ? title : "") + " " +
                        (content != null ? content : "")).toLowerCase();

        if (lower.contains("breaking"))                              score += 25;
        if (lower.contains("urgent") || lower.contains("alert"))    score += 20;
        if (lower.contains("fxhedgers") || lower.contains("deltaone")) score += 25;
        if (lower.contains("federal reserve") || lower.contains("fomc")) score += 20;
        if (content != null && content.matches(".*\\d+\\.\\d+%.*")) score += 15;

        return Math.min(100, score);
    }

    // ─────────────────────────────────────────────────────────────
    //  UTILITAIRES
    // ─────────────────────────────────────────────────────────────

    private static String createEventKey(String indicator, String timestamp) {
        if (indicator == null || timestamp == null) {
            return UUID.randomUUID().toString();
        }
        return indicator.toLowerCase().replaceAll("[^a-z0-9]", "_") + "_" + timestamp;
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

    /** Raccourci thread-safe pour logger dans MainActivity sans crash si l'activité est détruite. */
    private static void logToMain(String message) {
        if (MainActivity.instance != null) {
            MainActivity.instance.addLog(message);
        }
    }

        // ─────────────────────────────────────────────────────────────
    //  ANTI-DOUBLONS
    // ─────────────────────────────────────────────────────────────
        // ─────────────────────────────────────────────────────────────
    //  ANTI-DOUBLONS OPTIMISÉ (Mémoire légère)
    // ─────────────────────────────────────────────────────────────

    private static String generateFingerprint(String title, String content) {
        if (title == null) title = "";
        if (content == null) content = "";

        String combined = (title + " " + content)
                            .toLowerCase()
                            .replaceAll("[^a-z0-9\\s]", " ")
                            .replaceAll("\\s+", " ")
                            .trim();

        // On limite à 90 caractères pour minimiser la taille en mémoire
        int maxLen = Math.min(90, combined.length());
        return combined.substring(0, maxLen);
    }

    private static boolean isRecentDuplicate(String title, String content) {
        String fingerprint = generateFingerprint(title, content);
        long now = System.currentTimeMillis();

        Long lastSeen = recentFingerprints.get(fingerprint);
        if (lastSeen != null && (now - lastSeen) < DUPLICATE_WINDOW_MS) {
            logToMain("[VALIDATOR] 🔄 Doublon ignoré");
            return true;
        }

        recentFingerprints.put(fingerprint, now);

        // Nettoyage agressif si la map grossit trop
        if (recentFingerprints.size() > 180) {
            long cleanupThreshold = now - (2 * 60 * 60 * 1000L); // > 2 heures
            recentFingerprints.entrySet().removeIf(entry -> entry.getValue() < cleanupThreshold);
        }

        return false;
    }
    // ─────────────────────────────────────────────────────────────
    //  PRÉCHARGEMENT DU CALENDRIER
    // ─────────────────────────────────────────────────────────────

    public static void preloadCalendar() {
        try {
            List<EconomicCalendarAPI.CalendarEvent> events =
                EconomicCalendarAPI.fetchUpcomingEvents(24);
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
        // Méthode appelée depuis NotificationService pour nettoyage périodique
    public static void cleanupOldFingerprints() {
        if (recentFingerprints == null) return;
        long now = System.currentTimeMillis();
        long cleanupThreshold = now - (2 * 60 * 60 * 1000L); // > 2 heures
        recentFingerprints.entrySet().removeIf(entry -> entry.getValue() < cleanupThreshold);
    }
}
