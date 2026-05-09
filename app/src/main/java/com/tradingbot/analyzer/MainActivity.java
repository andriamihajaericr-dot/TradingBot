package com.tradingbot.analyzer;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.provider.Settings;
import android.content.Intent;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.NotificationManagerCompat;
import java.util.Set;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Timer;
import java.util.TimerTask;

public class MainActivity extends AppCompatActivity {

    public static String CLAUDE_API_KEY = "";
    public static String TELEGRAM_TOKEN = "";
    public static String TELEGRAM_CHAT_ID = "";
    public static MainActivity instance;

    private EventDatabase eventDb;
    private TextView statusText, logText;
    private Switch botSwitch;
    private EditText apiKeyInput, telegramTokenInput, telegramChatIdInput;
    private EconomicEventDetector eventDetector;
    private Timer calendarCheckTimer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        instance = this;

        // Initialisation des vues
        statusText = findViewById(R.id.statusText);
        logText = findViewById(R.id.logText);
        botSwitch = findViewById(R.id.botSwitch);
        apiKeyInput = findViewById(R.id.apiKeyInput);
        telegramTokenInput = findViewById(R.id.telegramTokenInput);
        telegramChatIdInput = findViewById(R.id.telegramChatIdInput);

        Button saveBtn = findViewById(R.id.saveBtn);
        Button permBtn = findViewById(R.id.permBtn);
        Button testBtn = findViewById(R.id.testBtn);

        loadSavedKeys();
        updateStatus();

        // Listeners
        saveBtn.setOnClickListener(v -> saveKeys());

        permBtn.setOnClickListener(v ->
            startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        );

        testBtn.setOnClickListener(v -> testNotification());

        botSwitch.setOnCheckedChangeListener((btn, isChecked) -> {
            if (isChecked && !isPermissionGranted()) {
                Toast.makeText(this, "❌ Active d'abord la permission Notification Listener !", Toast.LENGTH_LONG).show();
                botSwitch.setChecked(false);
                return;
            }

            getPrefs().edit().putBoolean("bot_active", isChecked).apply();
            updateStatus();
            addLog(isChecked ? "✅ Bot démarré - Surveillance active" : "⛔ Bot arrêté");

            if (isChecked) {
                startNotificationService();
            }
        });

        // Initialisation du détecteur
        eventDb = new EventDatabase(this);
        eventDetector = new EconomicEventDetector(eventDb);

        // Lancer le monitoring calendrier
        startCalendarMonitoring();

        addLog("🚀 TradingBot initialisé avec succès");
    }

    private void testNotification() {
        addLog("🧪 Envoi d'un test...");
        new Thread(() -> {
            NotificationService.processNotification(
                this, 
                "Test System", 
                "BREAKING: Federal Reserve raises interest rates by 50bps. Gold surges +1.8%, USDJPY drops."
            );
        }).start();
    }

    private void startNotificationService() {
        Intent intent = new Intent(this, NotificationService.class);
        startService(intent);
    }

    private void startCalendarMonitoring() {
        if (calendarCheckTimer != null) calendarCheckTimer.cancel();

        calendarCheckTimer = new Timer(true);

        // Vérification événements à venir
        calendarCheckTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                if (eventDetector != null) {
                    try {
                        eventDetector.checkUpcomingEvents();
                    } catch (Exception e) {
                        addLog("[ERROR] checkUpcomingEvents: " + e.getMessage());
                    }
                }
            }
        }, 5000, 15 * 60 * 1000); // Toutes les 15 minutes

        // Vérification événements récents
        calendarCheckTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                if (eventDetector != null) {
                    try {
                        eventDetector.checkRecentEvents();
                    } catch (Exception e) {
                        addLog("[ERROR] checkRecentEvents: " + e.getMessage());
                    }
                }
            }
        }, 10000, 5 * 60 * 1000); // Toutes les 5 minutes

        addLog("[MAIN] ✅ Monitoring calendrier démarré (15min + 5min)");
    }

    private void loadSavedKeys() {
        SharedPreferences p = getPrefs();
        CLAUDE_API_KEY = p.getString("claude_key", "");
        TELEGRAM_TOKEN = p.getString("tg_token", "");
        TELEGRAM_CHAT_ID = p.getString("tg_chat_id", "");

        apiKeyInput.setText(CLAUDE_API_KEY);
        telegramTokenInput.setText(TELEGRAM_TOKEN);
        telegramChatIdInput.setText(TELEGRAM_CHAT_ID);
    }

    private void saveKeys() {
        String k = apiKeyInput.getText().toString().trim();
        String t = telegramTokenInput.getText().toString().trim();
        String c = telegramChatIdInput.getText().toString().trim();

        if (k.isEmpty() || t.isEmpty() || c.isEmpty()) {
            Toast.makeText(this, "❌ Remplis toutes les clés !", Toast.LENGTH_SHORT).show();
            return;
        }

        getPrefs().edit()
                .putString("claude_key", k)
                .putString("tg_token", t)
                .putString("tg_chat_id", c)
                .apply();

        CLAUDE_API_KEY = k;
        TELEGRAM_TOKEN = t;
        TELEGRAM_CHAT_ID = c;

        Toast.makeText(this, "✅ Clés sauvegardées", Toast.LENGTH_SHORT).show();
        addLog("💾 Clés API mises à jour");
    }

    private boolean isPermissionGranted() {
        return NotificationManagerCompat.getEnabledListenerPackages(this)
                .contains(getPackageName());
    }

    private void updateStatus() {
        boolean active = getPrefs().getBoolean("bot_active", false);
        boolean perm = isPermissionGranted();

        if (!perm) {
            statusText.setText("⚠️ Permission Notification Listener requise");
        } else if (active) {
            statusText.setText("🟢 Bot Actif - Surveillance en cours");
        } else {
            statusText.setText("🔴 Bot Inactif");
        }
    }

    private SharedPreferences getPrefs() {
        return getSharedPreferences("TradingBot", MODE_PRIVATE);
    }

    public void addLog(String message) {
        runOnUiThread(() -> {
            String ts = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
            logText.append("[" + ts + "] " + message + "\n");

            // Limiter le nombre de lignes
            if (logText.getLineCount() > 300) {
                String text = logText.getText().toString();
                logText.setText(text.substring(text.indexOf("\n") + 1));
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (calendarCheckTimer != null) {
            calendarCheckTimer.cancel();
        }
        instance = null;
    }
}
