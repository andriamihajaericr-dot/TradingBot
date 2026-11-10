package com.tradingbot.analyzer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Table directionnelle fixe : vecteur macro -> sens des 6 actifs autorisés.
 * Remplace les matrices écrites dans les prompts. Le LLM ne fournit que le vecteur ;
 * la direction, la conviction et la validité sont calculées ici.
 *
 * Ordre des actifs (index du tableau sens) : NASDAQ, SP500, GOLD, USOIL, USDJPY, GBPUSD.
 * Valeurs : +1 = 🟢 (BULLISH), -1 = 🔴 (BEARISH), 0 = NEUTRE (omis du message).
 */
public final class MatriceMacro {

    public enum Actif {
        NASDAQ("💻"), SP500("📊"), GOLD("🏆"), USOIL("🛢️"), USDJPY("🇯🇵"), GBPUSD("🇬🇧");
        public final String emoji;
        Actif(String emoji) { this.emoji = emoji; }
    }

    public enum Regime { DOLLAR_FORT, DOLLAR_FAIBLE, RISK_ON, RISK_OFF, BANQUE_CENTRALE, NEUTRE }

    public enum Vecteur {
        HAWKISH_US(Regime.DOLLAR_FORT,        new int[]{-1, -1, -1,  0, +1, -1}, "taux réels US en hausse"),
        DOVISH_US(Regime.DOLLAR_FAIBLE,       new int[]{+1, +1, +1,  0, -1, +1}, "taux réels US en baisse"),
        EIA_DEFICIT(Regime.NEUTRE,            new int[]{ 0,  0,  0, +1,  0,  0}, "stocks pétrole inférieurs aux attentes"),
        EIA_SURPLUS(Regime.NEUTRE,            new int[]{ 0,  0,  0, -1,  0,  0}, "stocks pétrole supérieurs aux attentes"),
        TARIFS(Regime.RISK_OFF,               new int[]{-1, -1, +1, -1, -1, -1}, "escalade tarifaire, risk-off"),
        CHINE(Regime.RISK_ON,                 new int[]{+1, +1,  0, +1,  0,  0}, "croissance chinoise forte"),
        SENTIMENT_FAIBLE(Regime.RISK_OFF,     new int[]{-1, -1, +1, -1,  0,  0}, "sentiment consommateur faible"),
        IPO(Regime.RISK_ON,                   new int[]{+1, +1, -1,  0, -1,  0}, "IPO majeure, appétit pour le risque"),
        HAWKISH_BOJ(Regime.BANQUE_CENTRALE,   new int[]{ 0,  0,  0,  0, -1,  0}, "yen renforcé, BoJ hawkish"),
        DOVISH_BOJ(Regime.BANQUE_CENTRALE,    new int[]{ 0,  0,  0,  0, +1,  0}, "yen affaibli, BoJ dovish"),
        HAWKISH_BOE(Regime.BANQUE_CENTRALE,   new int[]{ 0,  0,  0,  0,  0, +1}, "livre renforcée, BoE hawkish"),
        DOVISH_BOE(Regime.BANQUE_CENTRALE,    new int[]{ 0,  0,  0,  0,  0, -1}, "livre affaiblie, BoE dovish"),
        HAWKISH_ECB(Regime.BANQUE_CENTRALE,   new int[]{ 0,  0,  0,  0,  0, +1}, "calibrage EUR/GBP, signal faible"),
        DOVISH_ECB(Regime.BANQUE_CENTRALE,    new int[]{ 0,  0,  0,  0,  0, -1}, "calibrage EUR/GBP, signal faible"),
        GEO_CAS_A(Regime.DOLLAR_FORT,         new int[]{-1, -1, -1, +1, +1, -1}, "choc d'offre réel confirmé"),
        GEO_CAS_B(Regime.RISK_OFF,            new int[]{-1, -1, +1, +1, -1, -1}, "escalade géopolitique non confirmée"),
        DESESCALADE(Regime.RISK_ON,           new int[]{+1, +1, -1, -1, +1, +1}, "désescalade, retour au régime pré-choc");

        public final Regime regime;
        public final String libelle;
        private final int[] sens;

        Vecteur(Regime regime, int[] sens, String libelle) {
            this.regime = regime;
            this.sens = sens;
            this.libelle = libelle;
        }

        public int[] sens() { return sens.clone(); }
    }

    public static final class Resultat {
        public final Vecteur vecteur;
        public final int[] sens;
        public final int conviction;
        public final List<String> violations;

        Resultat(Vecteur vecteur, int[] sens, int conviction, List<String> violations) {
            this.vecteur = vecteur;
            this.sens = sens;
            this.conviction = conviction;
            this.violations = violations;
        }

        /** Émis seulement si aucune incohérence et au moins un actif directionnel. */
        public boolean emissible() {
            if (!violations.isEmpty()) return false;
            for (int s : sens) if (s != 0) return true;
            return false;
        }
    }

    private MatriceMacro() {}

    /** Convertit le tag renvoyé par le LLM (ex: "HAWKISH_US") en vecteur. Null si inconnu. */
    public static Vecteur vecteurDepuisTag(String tag) {
        if (tag == null) return null;
        try {
            return Vecteur.valueOf(tag.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Détection déterministe du caractère conditionnel (règle E du prompt, appliquée en code
     * plutôt que confiée au modèle).
     */
    public static boolean detecterSpeculatif(String texte) {
        if (texte == null) return false;
        String t = texte.toLowerCase(Locale.ROOT);
        String[] indices = {"pourrait", "menace de", "envisage", "sources non confirmées",
                "selon des sources", "risque de", "non confirmé", "rumeur"};
        for (String i : indices) if (t.contains(i)) return true;
        return false;
    }

    /**
     * Évalue un vecteur. Les invariants sont vérifiés ; une violation rend le signal non émissible.
     *
     * @param confirme          l'événement est un fait observé (pas une annonce ou une menace)
     * @param speculatif        le texte source est conditionnel (voir detecterSpeculatif)
     * @param surpriseChiffree  actual et forecast disponibles et différents
     * @param sourceForte       source de référence (Bloomberg, Reuters, FT, FinancialJuice...)
     */
    public static Resultat evaluer(Vecteur v, boolean confirme, boolean speculatif,
                                   boolean surpriseChiffree, boolean sourceForte) {
        int[] s = v.sens();
        List<String> viol = new ArrayList<>();

        if (speculatif && v == Vecteur.GEO_CAS_A) {
            viol.add("choc géo cas A appliqué à un texte conditionnel");
        }

        // NASDAQ et SP500 : même sens
        if (s[0] != 0 && s[1] != 0 && s[0] != s[1]) {
            viol.add("NASDAQ et SP500 de sens opposé");
        }

        // GOLD et USDJPY : opposés dès que les deux sont directionnels
        if (s[2] != 0 && s[4] != 0 && s[2] == s[4]) {
            viol.add("GOLD et USDJPY de même sens");
        }

        // USDJPY et GBPUSD : inverses en régime dollar, identiques en régime risque
        if (s[4] != 0 && s[5] != 0) {
            if ((v.regime == Regime.DOLLAR_FORT || v.regime == Regime.DOLLAR_FAIBLE) && s[4] == s[5]) {
                viol.add("régime dollar : USDJPY et GBPUSD doivent être inverses");
            }
            if ((v.regime == Regime.RISK_ON || v.regime == Regime.RISK_OFF) && s[4] != s[5]) {
                viol.add("régime risque : USDJPY et GBPUSD doivent être identiques");
            }
        }

        int conviction = 50;
        if (confirme) conviction += 10;
        if (surpriseChiffree) conviction += 10;
        if (sourceForte) conviction += 5;
        if (speculatif) conviction = Math.min(conviction, 50);
        if (v == Vecteur.HAWKISH_ECB || v == Vecteur.DOVISH_ECB) conviction = Math.min(conviction, 50);
        conviction = Math.max(25, Math.min(95, conviction));

        return new Resultat(v, s, conviction, viol);
    }

    /** Explication causale par actif, selon le régime. Une phrase courte, jamais de régime seul. */
    public static String mecanisme(Vecteur v, Actif a) {
        if (v == Vecteur.GEO_CAS_A && a == Actif.USOIL) return "choc d'offre réel, prime géopolitique";
        if (v == Vecteur.GEO_CAS_A && a == Actif.GOLD)  return "dollar renforcé par achats énergie";
        if (v == Vecteur.GEO_CAS_B && a == Actif.USOIL) return "prime géopolitique activée";
        if (v == Vecteur.GEO_CAS_B && a == Actif.GOLD)  return "refuge classique soutient l'or";
        if (v == Vecteur.DESESCALADE && a == Actif.USOIL) return "prime géopolitique retirée";
        if (v == Vecteur.DESESCALADE && a == Actif.GOLD)  return "refuge géopolitique retiré";
        if (v == Vecteur.CHINE && a == Actif.USOIL) return "demande chinoise soutient le pétrole";

        switch (v.regime) {
            case DOLLAR_FORT:   return mecanismeDollarFort(a);
            case DOLLAR_FAIBLE: return mecanismeDollarFaible(a);
            case RISK_ON:       return mecanismeRiskOn(a);
            case RISK_OFF:      return mecanismeRiskOff(a);
            default:            return v.libelle;
        }
    }

    private static String mecanismeDollarFort(Actif a) {
        switch (a) {
            case NASDAQ: case SP500: return "taux réels en hausse, valorisations comprimées";
            case GOLD:    return "dollar fort pèse sur l'or";
            case USDJPY:  return "différentiel de taux favorable au dollar";
            case GBPUSD:  return "dollar fort comprime la livre";
            default:      return "";
        }
    }

    private static String mecanismeDollarFaible(Actif a) {
        switch (a) {
            case NASDAQ: case SP500: return "taux réels en baisse, valorisations soutenues";
            case GOLD:    return "dollar faible soutient l'or";
            case USDJPY:  return "dollar faible, yen relatif soutenu";
            case GBPUSD:  return "dollar faible soutient la livre";
            default:      return "";
        }
    }

    private static String mecanismeRiskOn(Actif a) {
        switch (a) {
            case NASDAQ: case SP500: return "appétit pour le risque soutient les indices";
            case GOLD:    return "refuge délaissé, or sous pression";
            case USOIL:   return "demande énergie soutenue";
            case USDJPY:  return "désengagement du yen refuge";
            case GBPUSD:  return "appétit pour le risque soutient la livre";
            default:      return "";
        }
    }

    private static String mecanismeRiskOff(Actif a) {
        switch (a) {
            case NASDAQ: case SP500: return "appétit pour le risque dégradé";
            case GOLD:    return "refuge classique soutient l'or";
            case USOIL:   return "demande énergie révisée à la baisse";
            case USDJPY:  return "flux refuge vers le yen";
            case GBPUSD:  return "risk-off comprime les paires risquées";
            default:      return "";
        }
    }

    /** Message Telegram en texte brut (pas de Markdown, donc pas d'erreur de parsing). */
    public static String construireMessage(Resultat r, String source, String heureMada, String titre) {
        StringBuilder sb = new StringBuilder();
        sb.append("⚡ ANALYSE MACRO\n");
        sb.append("🚨 ").append(source).append("\n");
        sb.append("🕒 ").append(heureMada).append(" (Mada)\n");
        sb.append("📊 Conviction : ").append(r.conviction).append("% | Vecteur : ")
          .append(r.vecteur.name()).append("\n");
        sb.append("📢 ").append(titre).append("\n");
        sb.append("--- IMPACTS ---\n");
        for (Actif a : Actif.values()) {
            int s = r.sens[a.ordinal()];
            if (s == 0) continue;
            sb.append("• ").append(a.emoji).append(" ").append(a.name()).append(" : ")
              .append(s > 0 ? "🟢" : "🔴").append(" | ").append(mecanisme(r.vecteur, a)).append("\n");
        }
        sb.append("🏁 FLUX : ").append(r.vecteur.regime.name());
        return sb.toString();
    }

    /**
     * Contrôle de cohérence de toute la table, à lancer une fois au démarrage (log uniquement).
     * Retourne la liste des lignes qui violent un invariant, sans les modifier.
     */
    public static List<String> verifierTable() {
        List<String> problemes = new ArrayList<>();
        for (Vecteur v : Vecteur.values()) {
            Resultat r = evaluer(v, true, false, true, true);
            for (String p : r.violations) problemes.add(v.name() + " : " + p);
        }
        return problemes;
    }
}
