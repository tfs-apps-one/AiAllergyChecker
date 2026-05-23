package tfsapps.aiallergychecker;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.widget.GridLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.Preview;
import androidx.camera.core.resolutionselector.AspectRatioStrategy;
import androidx.camera.core.resolutionselector.ResolutionSelector;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.common.util.concurrent.ListenableFuture;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * MainActivity
 *  
 * Orchestrates the three main concerns of the AR Allergy Checker:
 *
 *  1. CameraX setup  – Preview + ImageAnalysis bound to the activity lifecycle.
 *  2. AR overlay     – Allergen boxes are shown only after STABILITY_REQUIRED consecutive
 *                      frames detect them at the same position, and held for ALLERGEN_TIMEOUT_MS.
 *  3. Dashboard      – 9 allergen cards light up red when detected, revert after ALLERGEN_TIMEOUT_MS.
 *
 * Box stability filter (key behaviour change):
 *  • AllergyAnalyzer emits one box per allergen per frame (topmost occurrence).
 *  • MainActivity checks whether the box is at the same Y position as the previous frame.
 *  • Only after STABILITY_REQUIRED consecutive "same-position" frames is the box promoted to
 *    "confirmed" and sent to CustomOverlayView.
 *  • A confirmed box is held for ALLERGEN_TIMEOUT_MS so it stays visible even if one frame
 *    misses the detection (same hold time as the dashboard).
 *
 * Why this helps:
 *  Ingredient-list text is stable frame-to-frame → confirmed quickly.
 *  Manufacturer-disclaimer text is often detected only sporadically (blur, partial visibility,
 *  slightly different text-block boundaries each frame) → filtered out.
 */
public class MainActivity extends AppCompatActivity
        implements AllergyAnalyzer.AllergenCallback {

    private static final String TAG = "AllergyChecker";
    private static final int    CAMERA_PERMISSION_REQUEST = 1001;

    /** SharedPreferences キー：初回起動フラグ */
    private static final String PREFS_NAME        = "AllergyCheckerPrefs";
    private static final String KEY_TERMS_AGREED  = "terms_agreed";

    /**
     * How long (ms) an allergen card / confirmed AR box stays active after the
     * last frame it was detected in.
     */
    private static final long ALLERGEN_TIMEOUT_MS = 1500;

    /**
     * Number of consecutive frames at the same Y position required before a box
     * is promoted to "confirmed" and displayed in the AR overlay.
     * 2 frames ≈ 66 ms at 30 fps — fast enough to feel instant, yet enough to
     * reject one-off disclaimer detections.
     */
    private static final int STABILITY_REQUIRED = 2;

    /**
     * Maximum Y-centre shift (as a fraction of imageHeight) that is still treated
     * as "same position".  8 % covers minor camera shake without losing the ability
     * to distinguish ingredient-list rows from disclaimer rows.
     */
    private static final float SAME_POS_RATIO = 0.08f;

    // ── Views ─────────────────────────────────────────────────────────────────
    private PreviewView       mPreviewView;
    private CustomOverlayView mOverlayView;
    private GridLayout        mDashboardGrid;

    // ── Dashboard cards (one per allergen, index 0–8) ─────────────────────────
    private TextView[] mAllergenCards;

    // ── CameraX / Analyzer ────────────────────────────────────────────────────
    private ExecutorService mCameraExecutor;
    private AllergyAnalyzer mAllergyAnalyzer;

    // ── Dashboard timer ───────────────────────────────────────────────────────
    /** Unix-ms timestamp of the last frame each allergen was seen in. */
    private final long[]  mLastDetectedMs = new long[AllergyAnalyzer.getAllergenCount()];
    private final Handler mHandler        = new Handler(Looper.getMainLooper());

    // ── AR box stability state (per allergen index 0–8) ───────────────────────
    /** Raw box from the most recent frame (null = not detected). */
    private final AllergyAnalyzer.AllergenBox[] mLastRawBox =
            new AllergyAnalyzer.AllergenBox[AllergyAnalyzer.getAllergenCount()];

    /** How many consecutive frames this allergen was detected at the same Y position. */
    private final int[] mBoxStability = new int[AllergyAnalyzer.getAllergenCount()];

    /** The confirmed box currently being displayed (null = none confirmed yet). */
    private final AllergyAnalyzer.AllergenBox[] mConfirmedBox =
            new AllergyAnalyzer.AllergenBox[AllergyAnalyzer.getAllergenCount()];

    /** Unix-ms timestamp when the confirmed box was last refreshed. */
    private final long[] mBoxConfirmedMs = new long[AllergyAnalyzer.getAllergenCount()];

    // Colors
    private static final int COLOR_ACTIVE   = Color.parseColor("#CCE53935"); // red, 80 % opaque
    private static final int COLOR_INACTIVE = Color.parseColor("#993D3D3D"); // dark gray, 60 % opaque

    // ─────────────────────────────────────────────────────────────────────────
    //  Lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });

        mPreviewView   = findViewById(R.id.preview_view);
        mOverlayView   = findViewById(R.id.overlay_view);
        mDashboardGrid = findViewById(R.id.dashboard_grid);

        mCameraExecutor = Executors.newSingleThreadExecutor();

        // Initialise all timestamps to "never detected"
        long now = System.currentTimeMillis();
        for (int i = 0; i < mLastDetectedMs.length; i++) {
            mLastDetectedMs[i]  = now - ALLERGEN_TIMEOUT_MS - 1;
            mBoxConfirmedMs[i]  = now - ALLERGEN_TIMEOUT_MS - 1;
        }

        buildAllergenCards();

        // 初回起動かどうかチェック → 未同意なら利用規約ダイアログを表示
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        if (!prefs.getBoolean(KEY_TERMS_AGREED, false)) {
            showTermsDialog();
        } else {
            requestCameraOrStart();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  利用規約ダイアログ（初回起動時のみ）
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 利用規約を AlertDialog で表示する。
     * 「同意する」を押したときのみ SharedPreferences にフラグを保存し、
     * カメラ起動へ進む。「同意しない」はアプリを終了する。
     */
    private void showTermsDialog() {
        // ── 利用規約本文（ScrollView に包む）──────────────────────────────
        String termsText =
                "免責事項（必ずお読みください）\n\n" +
                "1. 本アプリは、食品パッケージの原材料表記をカメラで認識し、アレルゲン候補をハイライトする「確認補助ツール」です。" +
                "カメラの撮影環境や光の反射、フォントの種類等により、アレルギー物質を正しく検出できない（誤認識・見落とし）場合があります。\n\n" +
                "2. アレルギー物質の有無に関する最終的な判断は、必ずユーザーご自身で実際の原材料表記を目視確認してください。\n\n" +
                "3. 本アプリが提供する情報の正確性、完全性について開発者は一切の保証をいたしません。" +
                "本アプリの利用によって生じた健康上の被害、損害、トラブル等につきまして、開発者は直接的・間接的を問わず一切の責任を負いかねますので予めご了承ください。";

        TextView tv = new TextView(this);
        tv.setText(termsText);
        tv.setTextSize(14f);
        tv.setPadding(48, 24, 48, 24);
        tv.setLineSpacing(4f, 1.2f);

        ScrollView scrollView = new ScrollView(this);
        scrollView.addView(tv);

        // ── ダイアログ構築 ────────────────────────────────────────────────
        new AlertDialog.Builder(this)
                .setTitle("免責事項（必ずお読みください）")
                .setView(scrollView)
                .setCancelable(false)   // 外タップやバックキーで閉じられない
                .setPositiveButton("同意する", (dialog, which) -> {
                    // 同意フラグを保存
                    getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                            .edit()
                            .putBoolean(KEY_TERMS_AGREED, true)
                            .apply();
                    requestCameraOrStart();
                })
                .setNegativeButton("同意しない", (dialog, which) -> {
                    // アプリを終了
                    finish();
                })
                .show();
    }

    /** カメラ権限を確認し、あればカメラ起動、なければ権限リクエストを行う。 */
    private void requestCameraOrStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            ActivityCompat.requestPermissions(
                    this,
                    new String[]{Manifest.permission.CAMERA},
                    CAMERA_PERMISSION_REQUEST);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mHandler.removeCallbacksAndMessages(null);
        mCameraExecutor.shutdown();
        if (mAllergyAnalyzer != null) {
            mAllergyAnalyzer.shutdown();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Dashboard card construction
    // ─────────────────────────────────────────────────────────────────────────

    private void buildAllergenCards() {
        final String[] namesJa = AllergyAnalyzer.getAllergenNamesJa();
        final String[] namesEn = AllergyAnalyzer.getAllergenNamesEn();
        final int      count   = AllergyAnalyzer.getAllergenCount();

        mAllergenCards = new TextView[count];

        for (int i = 0; i < count; i++) {
            TextView card = new TextView(this);

            GridLayout.LayoutParams lp = new GridLayout.LayoutParams(
                    GridLayout.spec(i / 3, 1, GridLayout.FILL, 1f),
                    GridLayout.spec(i % 3, 1, GridLayout.FILL, 1f));
            lp.width  = 0;
            lp.height = 0;
            lp.setMargins(5, 5, 5, 5);
            card.setLayoutParams(lp);

            card.setGravity(Gravity.CENTER);
            card.setText(namesJa[i] + "\n" + namesEn[i]);
            card.setTextColor(Color.WHITE);
            card.setTextSize(14f);
            card.setPadding(4, 4, 4, 4);
            card.setBackgroundColor(COLOR_INACTIVE);

            mDashboardGrid.addView(card);
            mAllergenCards[i] = card;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  CameraX setup
    // ─────────────────────────────────────────────────────────────────────────

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> providerFuture =
                ProcessCameraProvider.getInstance(this);

        providerFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = providerFuture.get();

                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(mPreviewView.getSurfaceProvider());

                ResolutionSelector resolutionSelector = new ResolutionSelector.Builder()
                        .setAspectRatioStrategy(
                                AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                        .build();

                ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                        .setResolutionSelector(resolutionSelector)
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();

                mAllergyAnalyzer = new AllergyAnalyzer(this);
                imageAnalysis.setAnalyzer(mCameraExecutor, mAllergyAnalyzer);

                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(
                        this,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        imageAnalysis);

                Log.d(TAG, "Camera started successfully.");

            } catch (ExecutionException | InterruptedException e) {
                Log.e(TAG, "Camera start failed", e);
                Toast.makeText(this, "カメラの起動に失敗しました", Toast.LENGTH_SHORT).show();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  AllergyAnalyzer.AllergenCallback
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Called on the ML Kit completion thread after every analyzed frame.
     *
     * Box stability filter logic (runs on main thread via runOnUiThread):
     *   For each allergen index i (0–8):
     *     • If box detected at same Y as previous frame → increment mBoxStability[i]
     *     • If box detected at different Y              → reset mBoxStability[i] to 1
     *     • If box not detected this frame              → reset mBoxStability[i] to 0
     *   When mBoxStability[i] reaches STABILITY_REQUIRED → promote to confirmed box
     *   Confirmed boxes are held for ALLERGEN_TIMEOUT_MS (same as dashboard)
     */
    @Override
    public void onAllergenDetected(
            List<AllergyAnalyzer.AllergenBox> allergenBoxes,
            Set<Integer>                      detectedIndices,
            int                               imageWidth,
            int                               imageHeight,
            int                               rotationDegrees) {

        // Build index → box map (ML Kit thread; safe, no UI access)
        final Map<Integer, AllergyAnalyzer.AllergenBox> newBoxMap = new HashMap<>();
        for (AllergyAnalyzer.AllergenBox box : allergenBoxes) {
            if (box.allergenIndex >= 0) {
                newBoxMap.put(box.allergenIndex, box);
            }
        }
        final int imgH = imageHeight;

        runOnUiThread(() -> {
            final long now   = System.currentTimeMillis();
            final int  count = AllergyAnalyzer.getAllergenCount();

            // ── Step 1: Update stability counters ─────────────────────────────
            for (int i = 0; i < count; i++) {
                AllergyAnalyzer.AllergenBox newBox = newBoxMap.get(i);

                if (newBox != null) {
                    if (isSameBoxPosition(newBox, mLastRawBox[i], imgH)) {
                        // Same position as last frame → raise stability counter
                        mBoxStability[i] = Math.min(mBoxStability[i] + 1,
                                                    STABILITY_REQUIRED + 1);
                    } else {
                        // Different position (e.g. camera moved, or disclaimer vs ingredient)
                        mBoxStability[i] = 1;
                    }
                    mLastRawBox[i] = newBox;

                    if (mBoxStability[i] >= STABILITY_REQUIRED) {
                        // Confirmed: update the displayed box and refresh its hold timer
                        mConfirmedBox[i]   = newBox;
                        mBoxConfirmedMs[i] = now;
                    }
                } else {
                    // Not detected this frame — clear raw state; hold timer handles fade-out
                    mBoxStability[i] = 0;
                    mLastRawBox[i]   = null;
                }
            }

            // ── Step 2: Collect non-expired confirmed boxes for the overlay ────
            List<AllergyAnalyzer.AllergenBox> displayBoxes = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                if (mConfirmedBox[i] != null
                        && (now - mBoxConfirmedMs[i]) < ALLERGEN_TIMEOUT_MS) {
                    displayBoxes.add(mConfirmedBox[i]);
                }
            }
            mOverlayView.setResults(displayBoxes, imageWidth, imageHeight, rotationDegrees);

            // ── Step 3: Dashboard timestamps ──────────────────────────────────
            for (int idx : detectedIndices) {
                if (idx >= 0 && idx < mLastDetectedMs.length) {
                    mLastDetectedMs[idx] = now;
                }
            }

            // ── Step 4: Re-colour dashboard cards ─────────────────────────────
            refreshDashboard();
        });
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Box position comparison
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns true when boxes {@code a} and {@code b} are at approximately the
     * same vertical position in the ML Kit coordinate space.
     *
     * Threshold = max(30 px, imageHeight × SAME_POS_RATIO).
     * Using the larger of a fixed minimum and a ratio prevents the threshold from
     * becoming too tight at very low resolutions.
     */
    private static boolean isSameBoxPosition(AllergyAnalyzer.AllergenBox a,
                                             AllergyAnalyzer.AllergenBox b,
                                             int imageHeight) {
        if (a == null || b == null) return false;
        int threshold = Math.max(30, (int)(imageHeight * SAME_POS_RATIO));
        return Math.abs(a.rect.centerY() - b.rect.centerY()) <= threshold;
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Dashboard refresh
    // ─────────────────────────────────────────────────────────────────────────

    private void refreshDashboard() {
        final long now      = System.currentTimeMillis();
        boolean    anyActive = false;

        for (int i = 0; i < mAllergenCards.length; i++) {
            final boolean active = (now - mLastDetectedMs[i]) < ALLERGEN_TIMEOUT_MS;
            if (active) anyActive = true;

            mAllergenCards[i].setBackgroundColor(active ? COLOR_ACTIVE : COLOR_INACTIVE);
            mAllergenCards[i].setTypeface(null, active ? Typeface.BOLD : Typeface.NORMAL);
        }

        if (anyActive) {
            mHandler.removeCallbacks(mDashboardTimeoutRunnable);
            mHandler.postDelayed(mDashboardTimeoutRunnable, ALLERGEN_TIMEOUT_MS + 50);
        }
    }

    private final Runnable mDashboardTimeoutRunnable = this::refreshDashboard;

    // ─────────────────────────────────────────────────────────────────────────
    //  Permission result
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                            @NonNull String[]  permissions,
                                            @NonNull int[]     grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == CAMERA_PERMISSION_REQUEST) {
            if (grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startCamera();
            } else {
                Toast.makeText(this,
                        "カメラのアクセス許可が必要です。設定から許可してください。",
                        Toast.LENGTH_LONG).show();
            }
        }
    }
}
