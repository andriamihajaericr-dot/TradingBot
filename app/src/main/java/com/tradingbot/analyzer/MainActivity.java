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
    public static String CLAUDE_API_KEY   = "";
    public static String TELEGRAM_TOKEN   = "";
    public static String TELEGRAM_CHAT_ID = "";
    public static MainActivity instance;

    private TextView statusText, logText;
    private Switch botSwitch;
    private EditText apiKeyInput, telegramTokenInput, telegramChatIdInput;
    private EventDatabase eventDb;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        instance = this;
        eventDb = new EventDatabase(this);

        statusText          = findViewById(R.id.statusText);
        botSwitch           = findViewById(R.id.botSwitch);
        logText             = findViewById(R.id.logText);
        apiKeyInput         = findViewById(R.id.apiKeyInput);
        telegramTokenInput  = findViewById(R.id.telegramTokenInput);
        telegramChatIdInput = findViewById(R.id.telegramChatIdInput);
        Button saveBtn      = findViewById(R.id.saveBtn);
        Button permBtn      = findViewById(R.id.permBtn);
        Button testBtn      = findViewById(R.id.testBtn);

        loadSavedKeys();
        updateStatus();

        saveBtn.setOnClickListener(v -> saveKeys());

        permBtn.setOnClickListener(v -> {
            addLog("[SYSTEM] Redirection vers les autorisations Android.");
            startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
        });

        testBtn.setOnClickListener(v -> {
            addLog("🧪 [TEST] Vérification de la liaison réseau descendante...");
            new Thread(() -> {
                try {
                    NotificationService.sendTelegramSecure(
                        "🧪 **TEST LIAISON TRANSMISSION**\n" +
                        "Statut : Opérationnel (UTC+3 Madagascar)\n" +
                        "Flux simulé : *Moteur d'acquisition actif*"
                    );
                } catch (Exception e) {
                    Log.e(TAG, "Échec du bouton de test", e);
                }
            }).start();
        });

        botSwitch.setOnCheckedChangeListener((btn, isChecked) -> {
            if (isChecked && !isPermissionGranted()) {
                Toast.makeText(this, "Active d'abord la permission notifications !", Toast.LENGTH_LONG).show();
                botSwitch.setChecked(false);
                return;
            }
            getPrefs().edit().putBoolean("bot_active", isChecked).apply();
            updateStatus();
            addLog(isChecked ? "🚀 MOTEUR MACRO ACTIVÉ" : "🛑 MOTEUR EN VEILLE");
        });

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
            statusText.setTextColor(0xFFFF9800);
        } else if (active) {
            statusText.setText("🟢 BOT ACTIF — EN ÉCOUTE DES DRIVERS...");
            statusText.setTextColor(0xFF00FF00);
        } else {
            statusText.setText("🔴 BOT INACTIF — COUPE FLUX ENGAGÉ");
            statusText.setTextColor(0xFFFF0000);
        }
    }

    private void saveKeys() {
        String k = apiKeyInput.getText().toString().trim();
        String t = telegramTokenInput.getText().toString().trim();
        String c = telegramChatIdInput.getText().toString().trim();
        if (k.isEmpty() || t.isEmpty() || c.isEmpty()) {
            Toast.makeText(this, "Remplis toutes les clés !", Toast.LENGTH_SHORT).show();
            return;
        }
        getPrefs().edit().putString("claude_key", k).putString("tg_token", t).putString("tg_chat_id", c).apply();
        CLAUDE_API_KEY = k; 
        TELEGRAM_TOKEN = t; 
        TELEGRAM_CHAT_ID = c;
        Toast.makeText(this, "✅ Clés sauvegardées !", Toast.LENGTH_SHORT).show();
        addLog("✅ Clés API configurées avec succès.");
    }

    private void loadSavedKeys() {
        SharedPreferences p = getPrefs();
        CLAUDE_API_KEY   = p.getString("claude_key", "");
        TELEGRAM_TOKEN   = p.getString("tg_token", "");
        TELEGRAM_CHAT_ID = p.getString("tg_chat_id", "");
        apiKeyInput.setText(CLAUDE_API_KEY);
        telegramTokenInput.setText(TELEGRAM_TOKEN);
        telegramChatIdInput.setText(TELEGRAM_CHAT_ID);
    }

    private SharedPreferences getPrefs() {
        return getSharedPreferences("TradingBot", MODE_PRIVATE);
    }

    public void addLog(String message) {
        runOnUiThread(() -> {
            String ts = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
            String cur = logText.getText().toString();
            if (cur.length() > 5000) {
                cur = cur.substring(0, 2000);
            }
            logText.setText("[" + ts + "] " + message + "\n" + cur);
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (eventDb != null) {
            eventDb.close();
        }
    }
}
