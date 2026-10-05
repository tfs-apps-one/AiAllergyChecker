package tfsapps.aiallergychecker;

import android.Manifest;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
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

import com.google.android.gms.ads.AdError;
import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.AdSize;
import com.google.android.gms.ads.AdView;
import com.google.android.gms.ads.FullScreenContentCallback;
import com.google.android.gms.ads.LoadAdError;
import com.google.android.gms.ads.MobileAds;
import com.google.android.gms.ads.rewarded.RewardedAd;
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.common.util.concurrent.ListenableFuture;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * MainActivity
 *
 * Orchestrates the main concerns of the AR Allergy Checker:
 *
 *  1. CameraX setup  – Preview + ImageAnalysis bound to the activity lifecycle.
 *  2. AR overlay     – Allergen boxes are shown only after STABILITY_REQUIRED consecutive
 *                      frames detect them at the same position, and held for ALLERGEN_TIMEOUT_MS.
 *  3. Dashboard      – "Allergen Monitor": LED chips light up red when detected,
 *                      revert after ALLERGEN_TIMEOUT_MS.
 *  4. Expanded mode  – 設定（歯車）からリワード動画を視聴すると、特定原材料に準ずるもの
 *                      20品目を追加した計29品目を24時間検出できる。
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

    /** SharedPreferences キー：起動回数 / 評価ダイアログを今後出さないフラグ */
    private static final String KEY_LAUNCH_COUNT       = "launch_count";
    private static final String KEY_RATE_DIALOG_DONE   = "rate_dialog_done";

    /** SharedPreferences キー：拡張モードの解放期限（Unix ms）/ 解放中の ON/OFF */
    private static final String KEY_EXPAND_UNTIL   = "expand_until_ms";
    private static final String KEY_EXPAND_ENABLED = "expand_enabled";

    /** SharedPreferences キー：表示モード（true = 検出したアレルゲンだけを大きく表示） */
    private static final String KEY_DETECTED_ONLY  = "display_detected_only";

    /** リワード動画 1 回で解放される時間 */
    private static final long EXPAND_DURATION_MS = 24L * 60 * 60 * 1000;

    /** この回数以上起動したユーザーに評価ダイアログを表示する */
    private static final int  RATE_DIALOG_MIN_LAUNCHES = 3;

    /** 起動直後のカメラ立ち上げと重ならないよう、少し遅らせて表示する */
    private static final long RATE_DIALOG_DELAY_MS     = 1500;

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

    /** 全品目数（基本 9 + 拡張 20） */
    private static final int TOTAL_COUNT = AllergyAnalyzer.getAllergenCount();

    // ── Views ─────────────────────────────────────────────────────────────────
    private PreviewView       mPreviewView;
    private CustomOverlayView mOverlayView;
    private FrameLayout       mCameraContainer;
    private LinearLayout      mDashboardPanel;
    private LinearLayout      mDashboardContent;
    private ScrollView        mDashboardScroll;
    private TextView          mMonitorCount;
    private TextView          mModeBadge;
    private View              mMonitorLed;
    private FrameLayout       mAdContainer;

    // ── AdMob ─────────────────────────────────────────────────────────────────
    private AdView  mAdView;
    private boolean mAdLoadRequested = false;

    // ── AdMob リワード動画 ──────────────────────────────────────────────────────
    private RewardedAd  mRewardedAd;
    private boolean     mRewardedLoading  = false;
    private boolean     mShowWhenLoaded   = false;
    private boolean     mRewardEarned     = false;
    private AlertDialog mRewardLoadingDialog;

    // ── 設定ボトムシート ────────────────────────────────────────────────────────
    private BottomSheetDialog mSettingsSheet;
    private MaterialSwitch    mExpandSwitch;
    private TextView          mExpandStatus;
    private boolean           mSuppressSwitchCallback = false;

    // ── Mode ──────────────────────────────────────────────────────────────────
    /** true = 29 品目（拡張モード）, false = 9 品目 */
    private boolean mExpanded = false;

    /** true = 検出したアレルゲンだけを大きな文字で表示 / false = 全品目を一覧表示 */
    private boolean mDetectedOnly = false;

    /** 検出のみモードで現在表示中の品目（bit i = allergen index i）。-1 = 未描画 */
    private long mShownMask = -1L;

    // ── Dashboard cards (one per visible allergen) ────────────────────────────
    private TextView[] mAllergenCards = new TextView[0];
    private boolean[]  mCardActive    = new boolean[0];
    private ObjectAnimator mLedAnimator;

    // ── CameraX / Analyzer ────────────────────────────────────────────────────
    private ExecutorService mCameraExecutor;
    private AllergyAnalyzer mAllergyAnalyzer;

    // ── Dashboard timer ───────────────────────────────────────────────────────
    /** Unix-ms timestamp of the last frame each allergen was seen in. */
    private final long[]  mLastDetectedMs = new long[TOTAL_COUNT];
    private final Handler mHandler        = new Handler(Looper.getMainLooper());

    // ── AR box stability state (per allergen index 0–28) ──────────────────────
    /** Raw box from the most recent frame (null = not detected). */
    private final AllergyAnalyzer.AllergenBox[] mLastRawBox =
            new AllergyAnalyzer.AllergenBox[TOTAL_COUNT];

    /** How many consecutive frames this allergen was detected at the same Y position. */
    private final int[] mBoxStability = new int[TOTAL_COUNT];

    /** The confirmed box currently being displayed (null = none confirmed yet). */
    private final AllergyAnalyzer.AllergenBox[] mConfirmedBox =
            new AllergyAnalyzer.AllergenBox[TOTAL_COUNT];

    /** Unix-ms timestamp when the confirmed box was last refreshed. */
    private final long[] mBoxConfirmedMs = new long[TOTAL_COUNT];

    // ── Dashboard colours ("Allergen Monitor" theme) ──────────────────────────
    private static final int CHIP_BG_OFF        = Color.parseColor("#FF161B22");
    private static final int CHIP_STROKE_OFF    = Color.parseColor("#FF262C36");
    private static final int CHIP_BG_ON_START   = Color.parseColor("#FFFF3B30");
    private static final int CHIP_BG_ON_END     = Color.parseColor("#FFB0001C");
    private static final int CHIP_STROKE_ON     = Color.parseColor("#FFFF8A80");
    private static final int TEXT_OFF           = Color.parseColor("#FFC9D1D9");
    private static final int TEXT_SUB_OFF       = Color.parseColor("#FF6E7681");
    private static final int TEXT_ON            = Color.WHITE;
    private static final int LED_SCAN           = Color.parseColor("#FF3FB950");
    private static final int LED_ALERT          = Color.parseColor("#FFFF3B30");
    private static final int ACCENT_RED         = Color.parseColor("#FFFF6B6B");
    private static final int ACCENT_AMBER       = Color.parseColor("#FFFFC857");

    /** LED colour per category (CAT_BASIC … CAT_OTHER). */
    private static final int[] CATEGORY_COLORS = {
            Color.parseColor("#FFFF5A5F"), // 基本 9 品目
            Color.parseColor("#FFFF8A65"), // 肉類
            Color.parseColor("#FF4FC3F7"), // 魚介類
            Color.parseColor("#FFD7B377"), // 豆・種実類
            Color.parseColor("#FF9CCC65"), // 果物・野菜
            Color.parseColor("#FFB39DDB"), // その他
    };
    private static final String[] CATEGORY_LABELS = {
            "基本", "肉", "魚介", "豆・種実", "果物・野菜", "他"
    };

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

        mPreviewView      = findViewById(R.id.preview_view);
        mOverlayView      = findViewById(R.id.overlay_view);
        mCameraContainer  = findViewById(R.id.camera_container);
        mDashboardPanel   = findViewById(R.id.dashboard_panel);
        mDashboardContent = findViewById(R.id.dashboard_content);
        mDashboardScroll  = findViewById(R.id.dashboard_scroll);
        mMonitorCount     = findViewById(R.id.monitor_count);
        mModeBadge        = findViewById(R.id.mode_badge);
        mMonitorLed       = findViewById(R.id.monitor_led);
        mAdContainer      = findViewById(R.id.ad_container);

        ImageButton settingsButton = findViewById(R.id.btn_settings);
        settingsButton.setOnClickListener(v -> showSettingsSheet());

        mCameraExecutor = Executors.newSingleThreadExecutor();

        // Initialise all timestamps to "never detected"
        long now = System.currentTimeMillis();
        for (int i = 0; i < TOTAL_COUNT; i++) {
            mLastDetectedMs[i]  = now - ALLERGEN_TIMEOUT_MS - 1;
            mBoxConfirmedMs[i]  = now - ALLERGEN_TIMEOUT_MS - 1;
        }

        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        // 起動時のモード：解放期限内かつ ON なら 29 品目、それ以外は 9 品目
        mExpanded = shouldBeExpanded();
        mDetectedOnly = prefs.getBoolean(KEY_DETECTED_ONLY, false);
        setupMonitorLed();
        applyMode(mExpanded, true);

        // 起動回数をカウント（画面再生成時は数えない）
        if (savedInstanceState == null) {
            int launches = prefs.getInt(KEY_LAUNCH_COUNT, 0) + 1;
            prefs.edit().putInt(KEY_LAUNCH_COUNT, launches).apply();
        }

        // 初回起動かどうかチェック → 未同意なら利用規約ダイアログを表示
        if (!prefs.getBoolean(KEY_TERMS_AGREED, false)) {
            showTermsDialog();
        } else {
            requestCameraOrStart();
            if (savedInstanceState == null) {
                maybeShowRateDialog(prefs);
            }
        }

        // 広告 SDK の初期化はバックグラウンドで（起動・カメラを遅らせない）
        new Thread(() -> MobileAds.initialize(this, status -> {})).start();
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  AdMob アダプティブバナー
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 画面最下部にアンカー型アダプティブバナーを読み込む。
     * 利用規約同意後（カメラ起動時）に一度だけ呼ばれる。
     */
    private void loadBannerAd() {
        if (mAdLoadRequested || mAdContainer == null) return;
        mAdLoadRequested = true;

        // コンテナの幅が確定してからサイズを決める
        mAdContainer.post(() -> {
            if (isFinishing() || isDestroyed()) return;

            mAdView = new AdView(this);
            mAdView.setAdUnitId(getString(R.string.admob_banner_unit_id));
            mAdView.setAdSize(getAdaptiveBannerSize());

            mAdContainer.removeAllViews();
            mAdContainer.addView(mAdView);
            mAdView.loadAd(new AdRequest.Builder().build());
        });
    }

    /** コンテナ幅（取得できなければ画面幅）からアダプティブバナーのサイズを算出。 */
    private AdSize getAdaptiveBannerSize() {
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        float widthPx = mAdContainer.getWidth();
        if (widthPx <= 0) widthPx = metrics.widthPixels;
        int adWidthDp = (int) (widthPx / metrics.density);
        return AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(this, adWidthDp);
    }

    @Override
    protected void onPause() {
        if (mAdView != null) mAdView.pause();
        mHandler.removeCallbacks(mExpandTicker);
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mAdView != null) mAdView.resume();
        // バックグラウンド中に期限切れになっていないか確認し、カウントダウンを再開
        mHandler.removeCallbacks(mExpandTicker);
        mExpandTicker.run();
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  拡張モード（20品目追加 / リワード動画で 24 時間解放）
    // ─────────────────────────────────────────────────────────────────────────

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /** 解放期限の残りミリ秒（期限外なら 0）。 */
    private long remainingUnlockMs() {
        long until = prefs().getLong(KEY_EXPAND_UNTIL, 0L);
        long now   = System.currentTimeMillis();
        // 端末時刻を大きく巻き戻した場合の不正延長を防ぐ（残りが 24h を超えたら無効）
        long remain = until - now;
        if (remain <= 0 || remain > EXPAND_DURATION_MS) return 0L;
        return remain;
    }

    private boolean isExpandUnlocked() {
        return remainingUnlockMs() > 0;
    }

    private boolean shouldBeExpanded() {
        return isExpandUnlocked() && prefs().getBoolean(KEY_EXPAND_ENABLED, false);
    }

    /** 1 秒ごと：カウントダウン表示の更新と期限切れチェック。 */
    private final Runnable mExpandTicker = new Runnable() {
        @Override
        public void run() {
            if (mExpanded && !isExpandUnlocked()) {
                prefs().edit().putBoolean(KEY_EXPAND_ENABLED, false).apply();
                applyMode(false, false);
                Toast.makeText(MainActivity.this, R.string.expand_expired, Toast.LENGTH_LONG).show();
            } else if (!mExpanded && shouldBeExpanded()) {
                applyMode(true, false);
            }
            updateExpandUi();
            if (mExpanded || mSettingsSheet != null) {
                mHandler.postDelayed(this, 1000);
            }
        }
    };

    private void restartExpandTicker() {
        mHandler.removeCallbacks(mExpandTicker);
        mHandler.postDelayed(mExpandTicker, 1000);
        updateExpandUi();
    }

    /**
     * 9 品目 / 29 品目を切り替える。
     * ダッシュボードの再構築、Analyzer の検出対象数、画面の配分を更新する。
     */
    private void applyMode(boolean expanded, boolean force) {
        if (!force && expanded == mExpanded) {
            updateExpandUi();
            return;
        }
        mExpanded = expanded;

        // 拡張品目の検出状態をリセット（OFF にした瞬間に赤枠が残らないように）
        long past = System.currentTimeMillis() - ALLERGEN_TIMEOUT_MS - 1;
        for (int i = AllergyAnalyzer.BASIC_COUNT; i < TOTAL_COUNT; i++) {
            mLastDetectedMs[i] = past;
            mBoxConfirmedMs[i] = past;
            mConfirmedBox[i]   = null;
            mLastRawBox[i]     = null;
            mBoxStability[i]   = 0;
        }

        if (mAllergyAnalyzer != null) {
            mAllergyAnalyzer.setActiveCount(visibleCount());
        }

        // 29 品目のときはダッシュボードを少し広げる（カメラ 2:1 → 1.55:1）
        updatePanelWeights();

        // 29 品目時はヘッダーの幅を「品目数＋検出数＋残り時間」に譲る
        View title = findViewById(R.id.monitor_title);
        if (title != null) title.setVisibility(expanded ? View.GONE : View.VISIBLE);

        buildAllergenCards();
        refreshDashboard();
        updateExpandUi();

        if (expanded) restartExpandTicker();
    }

    private int visibleCount() {
        return mExpanded ? TOTAL_COUNT : AllergyAnalyzer.BASIC_COUNT;
    }

    /**
     * カメラ / ダッシュボードの配分。
     * 29 品目の一覧表示のときだけダッシュボードを広げる（カメラ 2:1 → 1.55:1）。
     * 検出のみ表示は件数が少ないので 2:1 のまま。
     */
    private void updatePanelWeights() {
        setWeight(mCameraContainer, (mExpanded && !mDetectedOnly) ? 1.55f : 2f);
        setWeight(mDashboardPanel, 1f);
    }

    /** 表示モード（全品目 / 検出のみ）を切り替えて保存する。 */
    private void applyDisplayMode(boolean detectedOnly) {
        if (detectedOnly == mDetectedOnly) return;
        mDetectedOnly = detectedOnly;
        prefs().edit().putBoolean(KEY_DETECTED_ONLY, detectedOnly).apply();
        updatePanelWeights();
        buildAllergenCards();
        refreshDashboard();
    }

    private static void setWeight(View v, float weight) {
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) v.getLayoutParams();
        if (lp.weight != weight) {
            lp.weight = weight;
            v.setLayoutParams(lp);
        }
    }

    /** ヘッダーのバッジと設定シートの状態表示を更新。 */
    private void updateExpandUi() {
        long remain = remainingUnlockMs();
        String hms  = formatHms(remain);

        if (mModeBadge != null) {
            if (mExpanded) {
                mModeBadge.setVisibility(View.VISIBLE);
                mModeBadge.setText("+20  " + hms);
            } else {
                mModeBadge.setVisibility(View.GONE);
            }
        }

        if (mExpandStatus != null) {
            if (remain > 0) {
                mExpandStatus.setText(getString(R.string.expand_status_unlocked, hms));
                mExpandStatus.setTextColor(ACCENT_AMBER);
            } else {
                mExpandStatus.setText(R.string.expand_status_locked);
                mExpandStatus.setTextColor(TEXT_SUB_OFF);
            }
        }
        if (mExpandSwitch != null) {
            setSwitchSilently(mExpanded);
        }
    }

    private static String formatHms(long ms) {
        long totalSec = Math.max(0L, ms / 1000);
        long h = totalSec / 3600;
        long m = (totalSec % 3600) / 60;
        long s = totalSec % 60;
        return String.format(Locale.US, "%02d:%02d:%02d", h, m, s);
    }

    // ── 設定ボトムシート ──────────────────────────────────────────────────────

    private void showSettingsSheet() {
        if (mSettingsSheet != null && mSettingsSheet.isShowing()) return;

        BottomSheetDialog sheet = new BottomSheetDialog(this);
        View content = getLayoutInflater().inflate(R.layout.dialog_settings, null);
        sheet.setContentView(content);

        mExpandSwitch = content.findViewById(R.id.switch_expand);
        mExpandStatus = content.findViewById(R.id.text_expand_status);
        styleSwitch(mExpandSwitch);

        content.findViewById(R.id.btn_close_settings).setOnClickListener(v -> sheet.dismiss());

        mExpandSwitch.setOnCheckedChangeListener((button, checked) -> {
            if (mSuppressSwitchCallback) return;
            if (checked) {
                if (isExpandUnlocked()) {
                    // 解放期間中 → すぐに 29 品目へ
                    prefs().edit().putBoolean(KEY_EXPAND_ENABLED, true).apply();
                    applyMode(true, false);
                } else {
                    // 未解放 → いったん OFF に戻して動画視聴の確認へ
                    setSwitchSilently(false);
                    confirmAndShowRewardedAd();
                }
            } else {
                prefs().edit().putBoolean(KEY_EXPAND_ENABLED, false).apply();
                applyMode(false, false);
            }
        });

        sheet.setOnShowListener(d -> {
            View bottomSheet = sheet.findViewById(com.google.android.material.R.id.design_bottom_sheet);
            if (bottomSheet != null) {
                bottomSheet.setBackgroundColor(Color.TRANSPARENT);
                BottomSheetBehavior.from(bottomSheet).setState(BottomSheetBehavior.STATE_EXPANDED);
            }
        });
        sheet.setOnDismissListener(d -> {
            mSettingsSheet = null;
            mExpandSwitch  = null;
            mExpandStatus  = null;
        });

        // ── 表示モード（全品目 / 検出のみ）──
        MaterialButtonToggleGroup displayGroup = content.findViewById(R.id.toggle_display_mode);
        displayGroup.check(mDetectedOnly ? R.id.btn_display_detected : R.id.btn_display_all);
        displayGroup.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (isChecked) applyDisplayMode(checkedId == R.id.btn_display_detected);
        });

        mSettingsSheet = sheet;
        updateExpandUi();
        sheet.show();

        // 未解放なら動画を先読みしておく（タップ後の待ち時間を減らす）
        if (!isExpandUnlocked()) loadRewardedAd();
        restartExpandTicker();
    }

    private void setSwitchSilently(boolean checked) {
        if (mExpandSwitch == null || mExpandSwitch.isChecked() == checked) return;
        mSuppressSwitchCallback = true;
        mExpandSwitch.setChecked(checked);
        mSuppressSwitchCallback = false;
    }

    /** ダークな設定シートに合わせたスイッチ配色（ON = 赤）。 */
    private static void styleSwitch(MaterialSwitch sw) {
        int[][] states = {
                new int[]{android.R.attr.state_checked},
                new int[]{}
        };
        sw.setTrackTintList(new ColorStateList(states, new int[]{
                Color.parseColor("#FFFF3B30"), Color.parseColor("#FF21262D")}));
        sw.setThumbTintList(new ColorStateList(states, new int[]{
                Color.WHITE, Color.parseColor("#FF8B949E")}));
        sw.setTrackDecorationTintList(new ColorStateList(states, new int[]{
                Color.TRANSPARENT, Color.parseColor("#FF484F58")}));
    }

    // ── リワード動画 ──────────────────────────────────────────────────────────

    private void confirmAndShowRewardedAd() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.reward_confirm_title)
                .setMessage(R.string.reward_confirm_message)
                .setPositiveButton(R.string.reward_confirm_watch, (d, w) -> showRewardedAd())
                .setNegativeButton(R.string.reward_confirm_cancel, null)
                .show();
    }

    /** リワード動画を読み込む（読み込み済み・読み込み中なら何もしない）。 */
    private void loadRewardedAd() {
        if (mRewardedAd != null || mRewardedLoading) return;
        mRewardedLoading = true;

        RewardedAd.load(this,
                getString(R.string.admob_rewarded_unit_id),
                new AdRequest.Builder().build(),
                new RewardedAdLoadCallback() {
                    @Override
                    public void onAdLoaded(@NonNull RewardedAd ad) {
                        mRewardedLoading = false;
                        mRewardedAd      = ad;
                        if (mShowWhenLoaded) {
                            mShowWhenLoaded = false;
                            dismissRewardLoadingDialog();
                            presentRewardedAd();
                        }
                    }

                    @Override
                    public void onAdFailedToLoad(@NonNull LoadAdError error) {
                        Log.w(TAG, "Rewarded ad failed to load: " + error.getMessage());
                        mRewardedLoading = false;
                        mRewardedAd      = null;
                        if (mShowWhenLoaded) {
                            mShowWhenLoaded = false;
                            dismissRewardLoadingDialog();
                            Toast.makeText(MainActivity.this,
                                    R.string.reward_load_failed, Toast.LENGTH_LONG).show();
                        }
                    }
                });
    }

    private void showRewardedAd() {
        if (mRewardedAd != null) {
            presentRewardedAd();
            return;
        }
        // まだ読み込めていない → 読み込み中ダイアログを出して、読み込み完了後に表示
        mShowWhenLoaded = true;
        showRewardLoadingDialog();
        loadRewardedAd();
    }

    private void presentRewardedAd() {
        if (isFinishing() || isDestroyed()) return;
        RewardedAd ad = mRewardedAd;
        if (ad == null) return;
        mRewardedAd   = null; // 1 回しか表示できないので手放す
        mRewardEarned = false;

        ad.setFullScreenContentCallback(new FullScreenContentCallback() {
            @Override
            public void onAdDismissedFullScreenContent() {
                if (mRewardEarned) {
                    onExpandUnlocked();
                } else {
                    Toast.makeText(MainActivity.this,
                            R.string.reward_not_earned, Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void onAdFailedToShowFullScreenContent(@NonNull AdError adError) {
                Log.w(TAG, "Rewarded ad failed to show: " + adError.getMessage());
                Toast.makeText(MainActivity.this,
                        R.string.reward_load_failed, Toast.LENGTH_LONG).show();
            }
        });

        ad.show(this, rewardItem -> {
            // 視聴完了：即座に保存（広告表示中にプロセスが終了しても特典を失わない）
            mRewardEarned = true;
            prefs().edit()
                    .putLong(KEY_EXPAND_UNTIL, System.currentTimeMillis() + EXPAND_DURATION_MS)
                    .putBoolean(KEY_EXPAND_ENABLED, true)
                    .apply();
        });
    }

    /** 動画視聴完了後（広告を閉じたあと）に呼ばれる。 */
    private void onExpandUnlocked() {
        applyMode(true, false);
        Toast.makeText(this, R.string.reward_granted, Toast.LENGTH_SHORT).show();
    }

    private void showRewardLoadingDialog() {
        dismissRewardLoadingDialog();
        int pad = dp(24);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(pad, pad, pad, pad);
        ProgressBar pb = new ProgressBar(this);
        TextView tv = new TextView(this);
        tv.setText(R.string.reward_loading);
        tv.setTextSize(15f);
        tv.setPadding(dp(16), 0, 0, 0);
        row.addView(pb);
        row.addView(tv);

        mRewardLoadingDialog = new AlertDialog.Builder(this)
                .setView(row)
                .setNegativeButton(R.string.reward_confirm_cancel, (d, w) -> mShowWhenLoaded = false)
                .setOnCancelListener(d -> mShowWhenLoaded = false)
                .show();
    }

    private void dismissRewardLoadingDialog() {
        if (mRewardLoadingDialog != null) {
            if (mRewardLoadingDialog.isShowing()) mRewardLoadingDialog.dismiss();
            mRewardLoadingDialog = null;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  アプリ評価のお願いダイアログ（3回以上起動したユーザーのみ）
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 起動回数が RATE_DIALOG_MIN_LAUNCHES 以上で、まだ「評価する」「今後表示しない」の
     * どちらも押していなければ評価ダイアログを表示する。
     *
     * Google Play ポリシー対応：
     *  ・特典や報酬と引き換えに評価を求めない
     *  ・特定の星の数（★5 など）を求めない
     */
    private void maybeShowRateDialog(@NonNull SharedPreferences prefs) {
        if (prefs.getBoolean(KEY_RATE_DIALOG_DONE, false)) return;
        if (prefs.getInt(KEY_LAUNCH_COUNT, 0) < RATE_DIALOG_MIN_LAUNCHES) return;

        mHandler.postDelayed(() -> {
            if (isFinishing() || isDestroyed()) return;

            new AlertDialog.Builder(this)
                    .setTitle(R.string.rate_dialog_title)
                    .setMessage(R.string.rate_dialog_message)
                    .setCancelable(false)
                    .setPositiveButton(R.string.rate_dialog_rate, (d, w) -> {
                        markRateDialogDone(prefs);
                        openPlayStorePage();
                    })
                    .setNegativeButton(R.string.rate_dialog_never, (d, w) ->
                            markRateDialogDone(prefs))
                    .show();
        }, RATE_DIALOG_DELAY_MS);
    }

    private void markRateDialogDone(@NonNull SharedPreferences prefs) {
        prefs.edit().putBoolean(KEY_RATE_DIALOG_DONE, true).apply();
    }

    /** Google Play のアプリページを開く（Play ストアアプリが無ければブラウザで開く）。 */
    private void openPlayStorePage() {
        String pkg = getPackageName();
        try {
            startActivity(new Intent(Intent.ACTION_VIEW,
                    Uri.parse("market://details?id=" + pkg)));
        } catch (ActivityNotFoundException e) {
            startActivity(new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://play.google.com/store/apps/details?id=" + pkg)));
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
        if (mAdView != null) {
            mAdView.destroy();
            mAdView = null;
        }
        dismissRewardLoadingDialog();
        if (mSettingsSheet != null) mSettingsSheet.dismiss();
        if (mLedAnimator != null) mLedAnimator.cancel();
        super.onDestroy();
        mHandler.removeCallbacksAndMessages(null);
        mCameraExecutor.shutdown();
        if (mAllergyAnalyzer != null) {
            mAllergyAnalyzer.shutdown();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Dashboard ("Allergen Monitor") construction
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 9 品目モード ：3 × 3 の大きめチップ（日本語 + 英語）で下部いっぱいに表示。
     * 29 品目モード：「特定原材料など 9」「準ずるもの 20」の 2 セクション × 5 列の
     *               コンパクトチップ。LED の色でカテゴリ（肉・魚介…）を表す。
     */
    private void buildAllergenCards() {
        mDashboardContent.removeAllViews();

        final int count = visibleCount();
        mAllergenCards = new TextView[count];
        mCardActive    = new boolean[count];
        mShownMask     = -1L;

        if (mDetectedOnly) {
            // 検出のみ表示：中身は refreshDashboard() → rebuildDetectedList() で描く
        } else if (!mExpanded) {
            buildGrid(0, AllergyAnalyzer.BASIC_COUNT, 3, false);
        } else {
            mDashboardContent.addView(makeSectionLabel("特定原材料など", 9, false));
            buildGrid(0, AllergyAnalyzer.BASIC_COUNT, 5, true);
            mDashboardContent.addView(makeSectionLabel("準ずるもの", 20, true));
            buildGrid(AllergyAnalyzer.BASIC_COUNT, TOTAL_COUNT, 5, true);
        }
        mDashboardScroll.scrollTo(0, 0);
    }

    /** Adds rows of chips for allergen indices [from, to) with {@code cols} columns. */
    private void buildGrid(int from, int to, int cols, boolean compact) {
        final int gap = dp(3);
        for (int rowStart = from; rowStart < to; rowStart += cols) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);

            LinearLayout.LayoutParams rowLp;
            if (compact) {
                rowLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(30));
            } else {
                // 9 品目モード：行を均等に伸ばして下部エリアを埋める
                rowLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
            }
            row.setLayoutParams(rowLp);

            for (int c = 0; c < cols; c++) {
                int i = rowStart + c;
                View cell;
                if (i < to) {
                    TextView chip = makeChip(i, compact);
                    mAllergenCards[i] = chip;
                    cell = chip;
                } else {
                    cell = new View(this); // 端数の空きセル（列幅を揃えるため）
                }
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
                lp.setMargins(gap, gap, gap, gap);
                cell.setLayoutParams(lp);
                row.addView(cell);
            }
            mDashboardContent.addView(row);
        }
    }

    private TextView makeChip(int index, boolean compact) {
        TextView chip = new TextView(this);
        chip.setGravity(Gravity.CENTER);
        chip.setIncludeFontPadding(false);
        chip.setCompoundDrawablePadding(dp(compact ? 3 : 6));
        chip.setPadding(dp(compact ? 4 : 8), 0, dp(compact ? 4 : 8), 0);

        if (compact) {
            chip.setText(AllergyAnalyzer.getAllergenShortJa()[index]);
            chip.setMaxLines(1);
            chip.setEllipsize(TextUtils.TruncateAt.END);
            chip.setAutoSizeTextTypeUniformWithConfiguration(
                    8, 12, 1, TypedValue.COMPLEX_UNIT_SP);
        } else {
            chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
            chip.setLineSpacing(0f, 1.05f);
        }
        styleChip(chip, index, false, compact);
        return chip;
    }

    /** Applies the ON (detected) / OFF style to a chip. */
    private void styleChip(TextView chip, int index, boolean active, boolean compact) {
        final int category = AllergyAnalyzer.getCategory(index);
        final float radius = dp(compact ? 8 : 12);

        GradientDrawable bg;
        if (active) {
            bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                    new int[]{CHIP_BG_ON_START, CHIP_BG_ON_END});
            bg.setStroke(dp(1), CHIP_STROKE_ON);
        } else {
            bg = new GradientDrawable();
            bg.setColor(CHIP_BG_OFF);
            bg.setStroke(dp(1), CHIP_STROKE_OFF);
        }
        bg.setCornerRadius(radius);
        chip.setBackground(bg);

        // LED インジケーター：OFF = カテゴリ色を暗く / ON = 白く発光
        int ledSize = dp(compact ? 6 : 8);
        GradientDrawable led = new GradientDrawable();
        led.setShape(GradientDrawable.OVAL);
        led.setSize(ledSize, ledSize);
        if (active) {
            led.setColor(Color.WHITE);
            led.setStroke(dp(2), Color.argb(120, 255, 255, 255));
            led.setSize(ledSize + dp(2), ledSize + dp(2));
        } else {
            int cc = CATEGORY_COLORS[category];
            led.setColor(Color.argb(150, Color.red(cc), Color.green(cc), Color.blue(cc)));
        }
        chip.setCompoundDrawablesRelativeWithIntrinsicBounds(led, null, null, null);

        if (compact) {
            chip.setTextColor(active ? TEXT_ON : TEXT_OFF);
            chip.setTypeface(Typeface.DEFAULT, active ? Typeface.BOLD : Typeface.NORMAL);
        } else {
            String ja = AllergyAnalyzer.getAllergenNamesJa()[index];
            String en = AllergyAnalyzer.getAllergenNamesEn()[index].toUpperCase(Locale.US);
            SpannableStringBuilder sb = new SpannableStringBuilder();
            sb.append(ja);
            sb.setSpan(new StyleSpan(Typeface.BOLD), 0, ja.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            if (ja.length() >= 5) {
                // 「カシューナッツ」など長い名前は 1 行に収まるよう縮小
                sb.setSpan(new RelativeSizeSpan(0.72f), 0, ja.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            int s = sb.length();
            sb.append("\n").append(en);
            sb.setSpan(new RelativeSizeSpan(0.62f), s, sb.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.setSpan(new ForegroundColorSpan(active ? Color.argb(220, 255, 255, 255) : TEXT_SUB_OFF),
                    s, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            chip.setText(sb);
            chip.setTextColor(active ? TEXT_ON : TEXT_OFF);
            chip.setTypeface(Typeface.DEFAULT, Typeface.NORMAL);
        }
    }

    /** Section label row for the 29-item layout. 右側にカテゴリ色の凡例を表示。 */
    private View makeSectionLabel(String title, int count, boolean withLegend) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(4), dp(4), dp(4), dp(1));

        TextView label = new TextView(this);
        SpannableStringBuilder sb = new SpannableStringBuilder(title);
        int s = sb.length();
        sb.append("  ").append(String.valueOf(count));
        sb.setSpan(new ForegroundColorSpan(withLegend ? ACCENT_AMBER : ACCENT_RED),
                s, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        label.setText(sb);
        label.setTextColor(Color.parseColor("#FF8B949E"));
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
        label.setTypeface(Typeface.DEFAULT_BOLD);
        label.setLetterSpacing(0.05f);
        row.addView(label, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        if (withLegend) {
            TextView legend = new TextView(this);
            SpannableStringBuilder lg = new SpannableStringBuilder();
            for (int c = AllergyAnalyzer.CAT_MEAT; c <= AllergyAnalyzer.CAT_OTHER; c++) {
                int st = lg.length();
                lg.append("●");
                lg.setSpan(new ForegroundColorSpan(CATEGORY_COLORS[c]), st, lg.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                lg.append(CATEGORY_LABELS[c]).append(c < AllergyAnalyzer.CAT_OTHER ? " " : "");
            }
            legend.setText(lg);
            legend.setTextColor(TEXT_SUB_OFF);
            legend.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f);
            legend.setMaxLines(1);
            row.addView(legend);
        }
        return row;
    }

    /** ヘッダーの LED：スキャン中は緑でゆっくり点滅、検出時は赤。 */
    private void setupMonitorLed() {
        setMonitorLedColor(LED_SCAN);
        mLedAnimator = ObjectAnimator.ofFloat(mMonitorLed, View.ALPHA, 1f, 0.25f);
        mLedAnimator.setDuration(900);
        mLedAnimator.setRepeatCount(ValueAnimator.INFINITE);
        mLedAnimator.setRepeatMode(ValueAnimator.REVERSE);
        mLedAnimator.start();
    }

    private void setMonitorLedColor(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        mMonitorLed.setBackground(d);
    }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  CameraX setup
    // ─────────────────────────────────────────────────────────────────────────

    private void startCamera() {
        loadBannerAd();

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
                mAllergyAnalyzer.setActiveCount(visibleCount());
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
     *   For each visible allergen index i:
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
            // モード切替直後の古いフレームで非表示の品目が光らないよう、表示中の品目のみ扱う
            final int  count = visibleCount();

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
                if (idx >= 0 && idx < count) {
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
        final long now       = System.currentTimeMillis();
        int        numActive = 0;

        long mask = 0L;

        for (int i = 0; i < mAllergenCards.length; i++) {
            final boolean active = (now - mLastDetectedMs[i]) < ALLERGEN_TIMEOUT_MS;
            if (active) {
                numActive++;
                mask |= (1L << i);
            }
            if (mDetectedOnly) continue;

            // 状態が変わったときだけ描き直す（毎フレームの Drawable 生成を避ける）
            if (active != mCardActive[i]) {
                mCardActive[i] = active;
                TextView chip = mAllergenCards[i];
                styleChip(chip, i, active, mExpanded);
                if (active) {
                    // 点灯した瞬間に軽くパルス
                    chip.animate().cancel();
                    chip.setScaleX(1.10f);
                    chip.setScaleY(1.10f);
                    chip.animate().scaleX(1f).scaleY(1f).setDuration(220).start();
                }
            }
        }

        // 検出のみ表示：検出された品目の組み合わせが変わったときだけ作り直す
        if (mDetectedOnly && mask != mShownMask) {
            long added = (mShownMask == -1L) ? mask : (mask & ~mShownMask);
            rebuildDetectedList(mask, added);
            mShownMask = mask;
        }

        updateMonitorHeader(numActive);

        if (numActive > 0) {
            mHandler.removeCallbacks(mDashboardTimeoutRunnable);
            mHandler.postDelayed(mDashboardTimeoutRunnable, ALLERGEN_TIMEOUT_MS + 50);
        }
    }

    /**
     * 検出のみ表示モード：検出された品目だけを大きな文字のカードで表示する。
     * 1 件なら横幅いっぱい、2 件以上は 2 列。未検出時は「未検出」メッセージ。
     *
     * @param mask  表示する品目（bit i = allergen index i）
     * @param added 今回新たに検出された品目（パルス演出の対象）
     */
    private void rebuildDetectedList(long mask, long added) {
        mDashboardContent.removeAllViews();

        List<Integer> items = new ArrayList<>();
        for (int i = 0; i < visibleCount(); i++) {
            if ((mask & (1L << i)) != 0) items.add(i);
        }

        if (items.isEmpty()) {
            mDashboardContent.addView(makeEmptyState());
            return;
        }

        final int cols = items.size() == 1 ? 1 : 2;
        final int gap  = dp(4);
        for (int r = 0; r < items.size(); r += cols) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(66)));

            for (int c = 0; c < cols; c++) {
                int k = r + c;
                View cell;
                if (k < items.size()) {
                    int idx = items.get(k);
                    TextView chip = makeBigChip(idx);
                    if ((added & (1L << idx)) != 0) pulse(chip);
                    cell = chip;
                } else {
                    cell = new View(this);
                }
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
                lp.setMargins(gap, gap, gap, gap);
                cell.setLayoutParams(lp);
                row.addView(cell);
            }
            mDashboardContent.addView(row);
        }
        mDashboardScroll.scrollTo(0, 0);
    }

    /** 検出のみ表示用の大きなカード（日本語を最大 30sp、英語を小さく併記）。 */
    private TextView makeBigChip(int index) {
        TextView chip = new TextView(this);
        chip.setGravity(Gravity.CENTER);
        chip.setIncludeFontPadding(false);
        chip.setPadding(dp(10), dp(4), dp(10), dp(4));
        chip.setCompoundDrawablePadding(dp(10));
        styleChip(chip, index, true, false);   // 背景・LED を「検出中」スタイルに

        String ja = AllergyAnalyzer.getAllergenNamesJa()[index];
        String en = AllergyAnalyzer.getAllergenNamesEn()[index].toUpperCase(Locale.US);
        SpannableStringBuilder sb = new SpannableStringBuilder(ja);
        sb.setSpan(new StyleSpan(Typeface.BOLD), 0, ja.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        int s = sb.length();
        sb.append("\n").append(en);
        sb.setSpan(new RelativeSizeSpan(0.42f), s, sb.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.setSpan(new ForegroundColorSpan(Color.argb(220, 255, 255, 255)),
                s, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        chip.setText(sb);
        chip.setTextColor(TEXT_ON);
        chip.setMaxLines(2);
        // 長い名前（マカダミアナッツ等）は枠に収まるまで自動縮小
        chip.setAutoSizeTextTypeUniformWithConfiguration(
                14, 30, 1, TypedValue.COMPLEX_UNIT_SP);
        return chip;
    }

    /** 検出のみ表示で、何も検出されていないときのメッセージ。 */
    private View makeEmptyState() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        // 下部エリアの高さいっぱいに広げて中央に表示（ScrollView の fillViewport を利用）
        box.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        box.setMinimumHeight(dp(120));

        TextView main = new TextView(this);
        main.setText(R.string.detected_only_empty);
        main.setTextColor(TEXT_OFF);
        main.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f);
        main.setTypeface(Typeface.DEFAULT_BOLD);
        main.setGravity(Gravity.CENTER);

        TextView sub = new TextView(this);
        sub.setText(R.string.detected_only_empty_note);
        sub.setTextColor(TEXT_SUB_OFF);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        sub.setGravity(Gravity.CENTER);
        sub.setPadding(dp(8), dp(8), dp(8), 0);

        box.addView(main);
        box.addView(sub);
        return box;
    }

    private static void pulse(View v) {
        v.animate().cancel();
        v.setScaleX(1.08f);
        v.setScaleY(1.08f);
        v.animate().scaleX(1f).scaleY(1f).setDuration(240).start();
    }

    private int mLastHeaderActive = -1;

    /** ヘッダー：「29品目」＋ 検出数、LED の色。 */
    private void updateMonitorHeader(int numActive) {
        String total = visibleCount() + "品目";
        String key   = total + numActive;
        if (numActive == mLastHeaderActive && key.equals(mMonitorCount.getTag())) return;
        mLastHeaderActive = numActive;
        mMonitorCount.setTag(key);

        SpannableStringBuilder sb = new SpannableStringBuilder(total);
        if (numActive > 0) {
            int s = sb.length();
            sb.append("  ▲").append(String.valueOf(numActive)).append("件検出");
            sb.setSpan(new ForegroundColorSpan(ACCENT_RED), s, sb.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        mMonitorCount.setText(sb);
        setMonitorLedColor(numActive > 0 ? LED_ALERT : LED_SCAN);
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
