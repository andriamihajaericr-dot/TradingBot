package com.tradingbot.analyzer;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.provider.Settings;
import android.content.Intent;
import android.util.Log;
import android.view.View;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.NotificationManagerCompat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Set;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";

    // CORRECTION : Renommage CLAUDE_API_KEY → GROQ_API_KEY pour cohérence avec NotificationService
    public static String GROQ_API_KEY     = "";
    public static String MACRO_API_KEY    = "";
    public static String TELEGRAM_TOKEN   = "";
    public static String TELEGRAM_CHAT_ID = "";
    public static MainActivity instance;

    private TextView statusText, logText;
    private Switch botSwitch;
    private EditText apiKeyInput, macroApiKeyInput, telegramTokenInput, telegramChatIdInput;
    private ScrollView mainScrollView;
    private EventDatabase eventDb;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        instance = this;

        // Initialisation de la base de données locale (Singleton sécurisé)
        eventDb = EventDatabase.getInstance(this);

        // Liaison des composants avec le fichier XML activity_main
        mainScrollView      = (ScrollView) ((android.view.ViewGroup) findViewById(android.R.id.content)).getChildAt(0);
        statusText          = findViewById(R.id.statusText);
        botSwitch           = findViewById(R.id.botSwitch);
        logText             = findViewById(R.id.logText);
        apiKeyInput         = findViewById(R.id.apiKeyInput);
        macroApiKeyInput    = findViewById(R.id.macroApiKeyInput);
        telegramTokenInput  = findViewById(R.id.telegramTokenInput);
        telegramChatIdInput = findViewById(R.id.telegramChatIdInput);

        Button saveBtn = findViewById(R.id.saveBtn);
        Button permBtn = findViewById(R.id.permBtn);
        Button testBtn = findViewById(R.id.testBtn);

        // Chargement initial des configurations enregistrées
        loadSavedKeys();
        updateStatus();

        // 1. Bouton Sauvegarder
        saveBtn.setOnClickListener(v -> saveKeys());

        // 2. Bouton Demande de Permission
        permBtn.setOnClickListener(v -> {
            addLog("[SYSTEM] Redirection vers les autorisations Android.");
            startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
        });

        // 3. Bouton Test de Transmission Telegram
        testBtn.setOnClickListener(v -> {
            addLog("🧪 [TEST] Déclenchement d'une simulation réseau...");
            try {
                NotificationService.sendTelegramSecure(
                    "🧪 **TEST DU CAPTEUR DE TRADING**\n" +
                    "Statut : Opérationnel\n" +
                    "Zone Temporelle : UTC+3 (Madagascar)\n" +
                    "Flux : Liaison montante et descendante OK.",
                    this
                );
            } catch (Exception e) {
                Log.e(TAG, "Échec lors de l'envoi du test", e);
                addLog("❌ [TEST] Erreur lors de l'appel : " + e.getMessage());
            }
        });

        // 4. Interrupteur d'activation globale du Bot
        botSwitch.setOnCheckedChangeListener((btn, isChecked) -> {
            if (isChecked && !isPermissionGranted()) {
                Toast.makeText(this, "Active d'abord la permission notifications !", Toast.LENGTH_LONG).show();
                botSwitch.setChecked(false);
                return;
            }
            getPrefs().edit().putBoolean("bot_active", isChecked).apply();
            updateStatus();
            addLog(isChecked ? "🚀 MOTEUR MACRO ACTIVÉ (EN ÉCOUTE)" : "🛑 MOTEUR EN VEILLE (STANDBY)");
        });

        // Appliquer l'état sauvegardé du switch au démarrage
        botSwitch.setChecked(getPrefs().getBoolean("bot_active", false));
        addLog("📱 Terminal prêt pour l'acquisition.");
    }

    @Override
    protected void onResume() {
        super.onResume();
        instance = this;
        updateStatus();
    }

    private boolean isPermissionGranted() {
        Set<String> pkgs = NotificationManagerCompat.getEnabledListenerPackages(this);
        return pkgs.contains(getPackageName());
    }

    private void updateStatus() {
        boolean active = getPrefs().getBoolean("bot_active", false);
        boolean perm   = isPermissionGranted();
        if (!perm) {
            statusText.setText("⚠️ PERMISSION NOTIFICATIONS REQUISE");
            statusText.setTextColor(0xFFFF9800); // Orange
        } else if (active) {
            statusText.setText("🟢 BOT ACTIF — EN ÉCOUTE DES DRIVERS...");
            statusText.setTextColor(0xFF555555);
        } else {
            statusText.setText("🔴 BOT INACTIF — COUPE FLUX ENGAGÉ");
            statusText.setTextColor(0xFFFF0000); // Rouge
        }
    }

    private void saveKeys() {
        String k = apiKeyInput.getText().toString().trim();
        String m = macroApiKeyInput.getText().toString().trim();
        String t = telegramTokenInput.getText().toString().trim();
        String c = telegramChatIdInput.getText().toString().trim();

        if (k.isEmpty() || m.isEmpty() || t.isEmpty() || c.isEmpty()) {
            Toast.makeText(this, "Remplis toutes les clés !", Toast.LENGTH_SHORT).show();
            return;
        }

        // CORRECTION : Sauvegarde sous "groq_key" — cohérent avec NotificationService.PREF_GROQ_KEY
        getPrefs().edit()
            .putString("groq_key", k)
            .putString("macro_api_key", m)
            .putString("tg_token", t)
            .putString("tg_chat_id", c)
            .apply();

        GROQ_API_KEY   = k;
        MACRO_API_KEY  = m;
        TELEGRAM_TOKEN = t;
        TELEGRAM_CHAT_ID = c;

        Toast.makeText(this, "✅ Clés sauvegardées !", Toast.LENGTH_SHORT).show();
        addLog("✅ Configuration des clés API mise à jour.");
    }

    private void loadSavedKeys() {
        SharedPreferences p = getPrefs();
        // CORRECTION : Lecture sous "groq_key" — cohérent avec NotificationService.PREF_GROQ_KEY
        GROQ_API_KEY     = p.getString("groq_key", "");
        MACRO_API_KEY    = p.getString("macro_api_key", "");
        TELEGRAM_TOKEN   = p.getString("tg_token", "");
        TELEGRAM_CHAT_ID = p.getString("tg_chat_id", "");

        apiKeyInput.setText(GROQ_API_KEY);
        macroApiKeyInput.setText(MACRO_API_KEY);
        telegramTokenInput.setText(TELEGRAM_TOKEN);
        telegramChatIdInput.setText(TELEGRAM_CHAT_ID);
    }

    private SharedPreferences getPrefs() {
        return getSharedPreferences("TradingBot", MODE_PRIVATE);
    }

    public void addLog(String message) {
        runOnUiThread(() -> {
            String ts = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
            String currentText = logText.getText().toString();

            if (currentText.contains("En attente de notifications...")) {
                currentText = "";
            }

            if (currentText.length() > 5000) {
                currentText = currentText.substring(0, 2000);
            }

            logText.setText(currentText + "[" + ts + "] " + message + "\n");

            if (mainScrollView != null) {
                mainScrollView.post(() -> mainScrollView.fullScroll(View.FOCUS_DOWN));
            }
        });
    }

    private void writeLogToFile(String message) {
    try {
        File logFile = new File(getExternalFilesDir(null), "Fonda_IOF_bot_logs.txt");
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
        String timestamp = sdf.format(new Date());
        String line = "[" + timestamp + "] " + message + "\n";

        FileWriter fw = new FileWriter(logFile, true); // true = append
        fw.write(line);
        fw.close();
    } catch (IOException e) {
        Log.e(TAG, "Erreur écriture log fichier", e);
    }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Singleton : on ne ferme PAS la connexion ici.
        // EventDatabase est partagé avec NotificationService ; fermer la connexion
        // depuis l'activité provoquerait un crash du service lors du prochain accès DB.
    }
}
