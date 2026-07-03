package com.tradingbot.analyzer;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.FileProvider;
import com.tradingbot.analyzer.MarketDataFetcher;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;
import java.util.Set;
import android.net.Uri;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import java.io.InputStream;
import java.util.Map;
import com.tradingbot.analyzer.TradingViewFetcher;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";

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

    private final ActivityResultLauncher<Intent> importDbLauncher = registerForActivityResult(
    new ActivityResultContracts.StartActivityForResult(),
    result -> {
        if (result.getResultCode() == RESULT_OK && result.getData() != null) {
            Uri uri = result.getData().getData();
            if (uri != null) {
                importDatabaseFromUri(uri);
            }
        }
    });

    private final ActivityResultLauncher<Intent> exportDbLauncher = registerForActivityResult(
    new ActivityResultContracts.StartActivityForResult(),
    result -> {
        if (result.getResultCode() == RESULT_OK && result.getData() != null) {
            Uri uri = result.getData().getData();
            if (uri != null) {
                exportDatabaseToUri(uri);
            }
        }
    });

    private WebhookServer webhookServer;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
      
    try {
        webhookServer = new WebhookServer(this);
        addLog("✅ Serveur Webhook démarré sur le port 8080");
    } catch (IOException e) {
        addLog("❌ Erreur démarrage serveur : " + e.getMessage());
    }
        setContentView(R.layout.activity_main);
        instance = this;

        eventDb = EventDatabase.getInstance(this);
        EventValidator.setAppContext(this);
        
        // Liaison des composants
        mainScrollView      = findViewById(R.id.mainScrollView);
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
        Button exportBtn = findViewById(R.id.exportBtn);   
        Button importBtn = findViewById(R.id.importBtn);
        Button dailyReportBtn = findViewById(R.id.dailyReportBtn);
        Button weeklyReportBtn = findViewById(R.id.weeklyReportBtn);
        Button monthlyReportBtn = findViewById(R.id.monthlyReportBtn);
        
        Button startDailySchedulerBtn = findViewById(R.id.startDailySchedulerBtn);
        Button startMonthlySchedulerBtn = findViewById(R.id.startMonthlySchedulerBtn);

        loadSavedKeys();
        updateStatus();

        saveBtn.setOnClickListener(v -> saveKeys());
        permBtn.setOnClickListener(v -> {
            addLog("[SYSTEM] Redirection vers les autorisations Android.");
            startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
        });

        testBtn.setOnClickListener(v -> {
            addLog("🧪 [TEST] Déclenchement RECUP DONNÉES TV...");
            new Thread(() -> {
                try {
                    // === TEST TRADINGVIEW FETCHER ===
                    runOnUiThread(() -> addLog("📊 [TEST] Lancement TradingViewFetcher..."));

                    // 1. Démarrer le pipeline (WebSocket + requêtes pivots natifs en arrière-plan)
                    TradingViewFetcher.start(getApplicationContext());

                    // Modifié pour correspondre à la réalité temporelle du Thread.sleep
                    runOnUiThread(() -> addLog("⏳ [TEST] Attente de la stabilisation du flux et des pivots (16s)..."));
                    try {
                        Thread.sleep(16000); 
                    } catch (InterruptedException ignored) {}

                    // 2. Récupérer les données une fois que tout est chargé et stabilisé
                    TradingViewFetcher.fetchAll(new TradingViewFetcher.OnDataReadyListener() {
                        @Override
                        public void onDataReady(Map<String, TradingViewFetcher.TVMarketData> data) {
                            runOnUiThread(() -> {
                                addLog("✅ [TV] Données reçues (" + data.size() + " symboles)");
                                StringBuilder sb = new StringBuilder();
                                
                                for (Map.Entry<String, TradingViewFetcher.TVMarketData> entry : data.entrySet()) {
                                    TradingViewFetcher.TVMarketData d = entry.getValue();
                                    String key = entry.getKey();
                                    
                                    String formatPrice = "%.4f";
                                    if ("GBPUSD".equals(key) || "EURUSD".equals(key) || "AUDUSD".equals(key) || "USDCAD".equals(key)) {
                                        formatPrice = "%.5f";
                                    } else if ("USDJPY".equals(key)) {
                                        formatPrice = "%.3f";
                                    } else if ("NASDAQ".equals(key) || "SP500".equals(key) || "GOLD".equals(key) || "USOIL".equals(key) || "BITCOIN".equals(key)) {
                                        formatPrice = "%.2f";
                                    }
                                    
                                    sb.append("• ").append(key).append(" : ")
                                      .append(String.format(Locale.US, formatPrice, d.price))
                                      .append(" (").append(String.format(Locale.US, "%+.2f", d.changePercent)).append("%)")
                                      
                                      // ── 1. LES 4 INDICATEURS MACRO ──
                                      .append(" | Amp: ").append(String.format(Locale.US, "%.2f", d.volatilityPercent)).append("%")
                                      .append(" | Range: ").append(String.format(Locale.US, "%.0f", d.dailyRangePercent)).append("%")
                                      .append(d.isNearHigh ? " 🔺PrèsHaut" : d.isNearLow ? " 🔻PrèsBas" : "")
                                      .append(" | Var: ").append(String.format(Locale.US, "%.6f", d.variance))
                                      .append("\n");
                                         // ── 2. NIVEAUX INSTITUTIONNELS ET CASSURES NATIVES ──
                                    sb.append(d.pdh > 0 ? (" | PDH=" + String.format(Locale.US, formatPrice, d.pdh)) : " | PDH=⏳(En attente TV)");
                                    sb.append(d.pdl > 0 ? (" | PDL=" + String.format(Locale.US, formatPrice, d.pdl)) : " | PDL=⏳(En attente TV)");
                                    sb.append(d.brokeAbovePDH ? " 🔺[Breakout PDH]" : (d.brokeBelowPDL ? " 🔻[Breakdown PDL]" : ""));
                                    
                                    sb.append(d.pwh > 0 ? (" | PWH=" + String.format(Locale.US, formatPrice, d.pwh)) : " | PWH=⏳(En attente TV)");
                                    sb.append(d.pwl > 0 ? (" | PWL=" + String.format(Locale.US, formatPrice, d.pwl)) : " | PWL=⏳(En attente TV)");
                                    sb.append(d.brokeAbovePWH ? " 🚀[Breakout PWH]" : (d.brokeBelowPWL ? " 🔥[Breakdown PWL]" : ""));
                                    sb.append("\n");
                                      
                                }
                                
                                addLog("📊 Données TV :\n" + sb.toString());
                                
                                NotificationService.sendTelegramSecure(
                                    "📊 *DONNÉES TRADINGVIEW COMPLETES (TEST)*\n\n" + sb.toString(),
                                    getApplicationContext()
                                );
                            });
                        }
                        
                        @Override
                        public void onError(String error) {
                            runOnUiThread(() -> addLog("❌ [TV] Erreur : " + error));
                        }
                    });
                } catch (Exception e) {
                    Log.e(TAG, "Échec test complet", e);
                    runOnUiThread(() -> addLog("❌ [TEST] Erreur : " + e.getMessage()));
                }
            }).start();
        });
         
        exportBtn.setOnClickListener(v -> exportDatabaseToStorage());
        importBtn.setOnClickListener(v -> importDatabaseFromStorage());

        dailyReportBtn.setOnClickListener(v -> {
            addLog("📅 [MANUEL] Déclenchement du rapport quotidien...");
            new Thread(() -> {
                try {
                    NotificationService svc = NotificationService.getInstance();
                    if (svc == null) {
                        runOnUiThread(() -> addLog("❌ [MANUEL] Service indisponible — active d'abord les notifications."));
                        return;
                    }
                    boolean sent = svc.generateAndSendDailyBrief();
                    runOnUiThread(() -> addLog(sent
                        ? "✅ [MANUEL] Daily Report envoyé."
                        : "⚠️ [MANUEL] Daily Report non envoyé (voir logs)."));
                } catch (Exception e) {
                    Log.e(TAG, "Échec déclenchement manuel Daily Report", e);
                    runOnUiThread(() -> addLog("❌ [MANUEL] Erreur Daily Report : " + e.getMessage()));
                }
            }).start();
        });

        weeklyReportBtn.setOnClickListener(v -> {
            addLog("📆 [MANUEL] Déclenchement du rapport hebdomadaire...");
            new Thread(() -> {
                try {
                    NotificationService svc = NotificationService.getInstance();
                    if (svc == null) {
                        runOnUiThread(() -> addLog("❌ [MANUEL] Service indisponible — active d'abord les notifications."));
                        return;
                    }
                    boolean sent = svc.generateAndSendWeeklyReport();
                    runOnUiThread(() -> addLog(sent
                        ? "✅ [MANUEL] Weekly Report envoyé."
                        : "⚠️ [MANUEL] Weekly Report non envoyé (voir logs)."));
                } catch (Exception e) {
                    Log.e(TAG, "Échec déclenchement manuel Weekly Report", e);
                    runOnUiThread(() -> addLog("❌ [MANUEL] Erreur Weekly Report : " + e.getMessage()));
                }
            }).start();
        });

        monthlyReportBtn.setOnClickListener(v -> {
            addLog("📊 [MANUEL] Déclenchement du rapport mensuel...");
            new Thread(() -> {
                try {
                    NotificationService svc = NotificationService.getInstance();
                    if (svc == null) {
                        runOnUiThread(() -> addLog("❌ [MANUEL] Service indisponible — active d'abord les notifications."));
                        return;
                    }
                    boolean sent = svc.generateAndPurgeMonthlyReport(false); 
                    runOnUiThread(() -> addLog(sent
                        ? "✅ [MANUEL] Monthly Report envoyé."
                        : "⚠️ [MANUEL] Monthly Report non envoyé (voir logs)."));
                } catch (Exception e) {
                    Log.e(TAG, "Échec déclenchement manuel Monthly Report", e);
                    runOnUiThread(() -> addLog("❌ [MANUEL] Erreur Monthly Report : " + e.getMessage()));
                }
            }).start();
        });

        startDailySchedulerBtn.setOnClickListener(v -> {
            addLog("📅 [MANUEL] Démarrage du scheduler Daily...");
            new Thread(() -> {
                try {
                    NotificationService svc = NotificationService.getInstance();
                    if (svc == null) {
                        runOnUiThread(() -> addLog("❌ [MANUEL] Service indisponible — active d'abord les notifications."));
                        return;
                    }
                    svc.startDailyBriefScheduler();
                    runOnUiThread(() -> addLog("✅ [MANUEL] Scheduler Daily démarré (horaires 7h/12h/15h/19h/22h Mada)."));
                } catch (Exception e) {
                    Log.e(TAG, "Échec démarrage manuel du scheduler Daily", e);
                    runOnUiThread(() -> addLog("❌ [MANUEL] Erreur démarrage scheduler Daily : " + e.getMessage()));
                }
            }).start();
        });

        startMonthlySchedulerBtn.setOnClickListener(v -> {
            addLog("📊 [MANUEL] Démarrage du scheduler Monthly (+ Weekly + rattrapages)...");
            new Thread(() -> {
                try {
                    NotificationService svc = NotificationService.getInstance();
                    if (svc == null) {
                        runOnUiThread(() -> addLog("❌ [MANUEL] Service indisponible — active d'abord les notifications."));
                        return;
                    }
                    svc.startMonthlyReportScheduler();
                    runOnUiThread(() -> addLog("✅ [MANUEL] Scheduler Monthly démarré (+ Weekly + rattrapages)."));
                } catch (Exception e) {
                    Log.e(TAG, "Échec démarrage manuel du scheduler Monthly", e);
                    runOnUiThread(() -> addLog("❌ [MANUEL] Erreur démarrage scheduler Monthly : " + e.getMessage()));
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
            addLog(isChecked ? "🚀 MOTEUR MACRO ACTIVÉ (EN ÉCOUTE)" : "🛑 MOTEUR EN VEILLE (STANDBY)");
        });

        boolean keysReady = areKeysSaved();
        botSwitch.setChecked(keysReady && getPrefs().getBoolean("bot_active", false));
        if (!keysReady) {
            addLog("⚠️ Aucune clé détectée — remplis les clés puis appuie sur Sauvegarder pour activer le bot.");
        } else {
            addLog("📱 Terminal prêt pour l'acquisition.");
        }
        eventDb.diagnostiquerTableEvents();

        if (keysReady) {
            new Thread(() -> {
                try {
                    EventValidator.preloadCalendar();
                    runOnUiThread(() -> addLog("✅ Calendrier économique préchargé."));
                } catch (Exception e) {
                    runOnUiThread(() -> addLog("⚠️ Erreur préchargement calendrier : " + e.getMessage()));
                }
            }).start();
        }
    }   

    private boolean areKeysSaved() {
        SharedPreferences p = getPrefs();
        return !p.getString("groq_key", "").isEmpty()
            && !p.getString("macro_api_key", "").isEmpty()
            && !p.getString("tg_token", "").isEmpty()
            && !p.getString("tg_chat_id", "").isEmpty();
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
        String m = macroApiKeyInput.getText().toString().trim();
        String t = telegramTokenInput.getText().toString().trim();
        String c = telegramChatIdInput.getText().toString().trim();

        if (k.isEmpty() || m.isEmpty() || t.isEmpty() || c.isEmpty()) {
            Toast.makeText(this, "Remplis toutes les clés !", Toast.LENGTH_SHORT).show();
            return;
        }

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

        // Propager la clé aux modules dépendants (Nettoyé des doublons)
        MarketDataFetcher.setApiKey(m);
        getSharedPreferences("TradingBotPrefs", MODE_PRIVATE).edit()
            .putString("twelve_data_key", m)
            .apply();

        Toast.makeText(this, "✅ Clés sauvegardées !", Toast.LENGTH_SHORT).show();
        addLog("✅ Configuration des clés API mise à jour. Lancement des services...");

        new Thread(() -> {
            try {
                EventValidator.preloadCalendar();
                runOnUiThread(() -> addLog("✅ Calendrier économique chargé."));
            } catch (Exception e) {
                runOnUiThread(() -> addLog("⚠️ Erreur calendrier : " + e.getMessage()));
            }
        }).start();
    }

    private void loadSavedKeys() {
        SharedPreferences p = getPrefs();
        GROQ_API_KEY     = p.getString("groq_key", "");
        MACRO_API_KEY    = p.getString("macro_api_key", "");
        TELEGRAM_TOKEN   = p.getString("tg_token", "");
        TELEGRAM_CHAT_ID = p.getString("tg_chat_id", "");

        // Initialisation propre au démarrage (Nettoyé des doublons)
        if (!MACRO_API_KEY.isEmpty()) {
            MarketDataFetcher.setApiKey(MACRO_API_KEY);
            getSharedPreferences("TradingBotPrefs", MODE_PRIVATE).edit()
                .putString("twelve_data_key", MACRO_API_KEY)
                .apply();
        }

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
            if (currentText.length() > 15000) {
                 currentText = currentText.substring(currentText.length() - 8000);
            }
            logText.setText(currentText + "[" + ts + "] " + message + "\n");
            if (mainScrollView != null) {
                mainScrollView.post(() -> mainScrollView.fullScroll(View.FOCUS_DOWN));
            }
            writeLogToFile(message);
        });
    }

    private void writeLogToFile(String message) {
        try {
            File logFile = new File(getExternalFilesDir(null), "Fonda_IOF_bot_logs.txt");
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
            String timestamp = sdf.format(new Date());
            String line = "[" + timestamp + "] " + message + "\n";
            FileWriter fw = new FileWriter(logFile, true);
            fw.write(line);
            fw.close();
        } catch (IOException e) {
            Log.e(TAG, "Erreur écriture log fichier", e);
        }
    }

    private void exportLogs() {
        File logFile = new File(getExternalFilesDir(null), "Fonda_IOF_bot_logs.txt");
        if (!logFile.exists()) {
            Toast.makeText(this, "Aucun log trouvé", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent shareIntent = new Intent(Intent.ACTION_SEND);
        shareIntent.setType("text/plain");
        shareIntent.putExtra(Intent.EXTRA_STREAM, FileProvider.getUriForFile(
            this,
            getPackageName() + ".fileprovider",
            logFile
        ));
        shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(shareIntent, "Partager les logs"));
    }

    private void importDatabaseFromStorage() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/octet-stream", "application/x-sqlite3"});
        importDbLauncher.launch(intent);
    }

    private void importDatabaseFromUri(Uri uri) {
        NotificationService.isDatabaseImportInProgress = true;
        try {
            addLog("⏳ [IMPORT] Début de la restauration de la base de données macro...");

            if (eventDb != null) {
                eventDb.close();
                eventDb = null;
            }

            EventDatabase.resetInstance();

            boolean deleted = deleteDatabase("trading_bot.db");
            if (deleted) {
                addLog("🗑️ Ancienne base et résidus WAL/SHM purgés avec succès.");
            }

            File currentDb = getDatabasePath("trading_bot.db");
            File dbDir = currentDb.getParentFile();
            if (dbDir != null && !dbDir.exists()) {
                dbDir.mkdirs();
            }

            try (InputStream is = getContentResolver().openInputStream(uri);
                 FileOutputStream fos = new FileOutputStream(currentDb)) {
                
                if (is == null) {
                    throw new IOException("Impossible d'ouvrir le flux de lecture de l'URI.");
                }

                byte[] buffer = new byte[4096]; 
                int length;
                while ((length = is.read(buffer)) > 0) {
                    fos.write(buffer, 0, length);
                }
                fos.flush();
            }

            eventDb = EventDatabase.getInstance(this);
            eventDb.diagnostiquerTableEvents();
            
            Toast.makeText(this, "Base macro restaurée avec succès", Toast.LENGTH_LONG).show();
            addLog("Base de données importée et moteur SQL réinitialisé avec succès.");

        } catch (Exception e) {
            Log.e(TAG, "Erreur critique lors de l'importation", e);
            Toast.makeText(this, "Échec de l'importation : " + e.getMessage(), Toast.LENGTH_LONG).show();
            addLog("❌ [ERREUR IMPORT] : " + e.getMessage());
            
            try {
                EventDatabase.resetInstance();
                eventDb = EventDatabase.getInstance(this);
            } catch (Exception ignored) {}
        } finally {
            NotificationService.isDatabaseImportInProgress = false;
        }
    }
    
    private void exportDatabaseToStorage() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/octet-stream");
        intent.putExtra(Intent.EXTRA_TITLE, "trading_bot_backup_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date()) + ".db");
        exportDbLauncher.launch(intent);
    }
    
    private void exportDatabaseToUri(Uri uri) {
        try {
            File dbFile = getDatabasePath("trading_bot.db");
            if (dbFile == null || !dbFile.exists()) {
                Toast.makeText(this, "Aucune base de données", Toast.LENGTH_SHORT).show();
                return;
            }
    
            FileInputStream fis = new FileInputStream(dbFile);
            OutputStream os = getContentResolver().openOutputStream(uri);
            byte[] buffer = new byte[1024];
            int length;
            while ((length = fis.read(buffer)) > 0) {
                os.write(buffer, 0, length);
            }
            os.close();
            fis.close();
    
            File privateBackupDir = new File(getExternalFilesDir(null), "AutoBackups");
            if (!privateBackupDir.exists()) privateBackupDir.mkdirs();
            String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
            File privateBackupFile = new File(privateBackupDir, "auto_trading_bot_backup_" + timestamp + ".db");
            
            FileInputStream fis2 = new FileInputStream(dbFile);
            FileOutputStream fos = new FileOutputStream(privateBackupFile);
            while ((length = fis2.read(buffer)) > 0) {
                fos.write(buffer, 0, length);
            }
            fos.close();
            fis2.close();
            Toast.makeText(this, "Base exportée + sauvegarde automatique locale", Toast.LENGTH_LONG).show();
            addLog("✅ Base exportée avec copie locale.");
        } catch (Exception e) {
            Log.e(TAG, "Erreur export", e);
            Toast.makeText(this, "Échec export : " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }
    
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 1002 && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            importDatabaseFromStorage();
        } else if (requestCode == 1001 && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            exportDatabaseToStorage();
        } else {
            Toast.makeText(this, "Permission refusée", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        instance = null;  
        
    if (webhookServer != null) {
        webhookServer.stop();
        webhookServer = null;
    }
    } 
}
