package tfsapps.aiallergychecker;

import android.annotation.SuppressLint;
import android.media.Image;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions;

import android.graphics.Rect;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * AllergyAnalyzer
 *
 * Implements {@link ImageAnalysis.Analyzer} to process each camera frame through
 * ML Kit's Japanese Text Recognition engine.
 *
 * For every frame:
 *  1. Creates an InputImage from the ImageProxy (with rotation metadata).
 *  2. Runs the Japanese TextRecognizer.
 *  3. Scans every recognized Text.Line for the currently-active allergen keywords
 *     (9 basic items, or 9 + 20 when the expanded mode is unlocked).
 *  4. Collects matching bounding boxes (Rect, in sensor/image coordinates) and
 *     the set of allergen indices that were found.
 *  5. Fires {@link AllergenCallback#onAllergenDetected} with the results so the
 *     Activity can update the CustomOverlayView and the bottom dashboard.
 *
 * imageProxy.close() is always called via addOnCompleteListener to unblock CameraX
 * for the next frame (critical with STRATEGY_KEEP_ONLY_LATEST).
 */
public class AllergyAnalyzer implements ImageAnalysis.Analyzer {

    private static final String TAG = "AllergyAnalyzer";

    // ── Allergen definitions ──────────────────────────────────────────────────
    //
    //  [基本 9 品目]  index 0–8   … 常に検出
    //  [拡張 20 品目] index 9–28  … 特定原材料に準ずるもの（動画視聴で 1 時間解放）
    //
    //  Index  0 = 卵          Index  9 = 牛肉        Index 19 = アーモンド
    //  Index  1 = 乳          Index 10 = 鶏肉        Index 20 = マカダミアナッツ
    //  Index  2 = 小麦        Index 11 = 豚肉        Index 21 = オレンジ
    //  Index  3 = えび        Index 12 = あわび      Index 22 = キウイフルーツ
    //  Index  4 = かに        Index 13 = いか        Index 23 = バナナ
    //  Index  5 = そば        Index 14 = いくら      Index 24 = もも
    //  Index  6 = 落花生      Index 15 = さけ        Index 25 = りんご
    //  Index  7 = くるみ      Index 16 = さば        Index 26 = やまいも
    //  Index  8 = カシュー    Index 17 = 大豆        Index 27 = ピスタチオ
    //                         Index 18 = ごま        Index 28 = ゼラチン

    /** Number of allergens that are always detected (特定原材料 8 + カシューナッツ). */
    public static final int BASIC_COUNT = 9;

    private static final String[][] ALLERGEN_KEYWORDS = {
            // ── 基本 9 品目 ──
            /*  0 – Egg       */ {"卵", "たまご", "玉子"},
            /*  1 – Milk      */ {"乳", "ミルク", "牛乳", "乳成分"},
            /*  2 – Wheat     */ {"小麦", "こむぎ"},
            /*  3 – Shrimp    */ {"えび", "エビ", "海老"},
            /*  4 – Crab      */ {"かに", "カニ", "蟹"},
            /*  5 – Buckwheat */ {"そば", "ソバ", "蕎麦"},
            /*  6 – Peanut    */ {"落花生", "ピーナッツ"},
            /*  7 – Walnut    */ {"くるみ", "クルミ", "胡桃"},
            /*  8 – Cashew    */ {"カシューナッツ", "かしゅーなっつ"},

            // ── 拡張 20 品目（特定原材料に準ずるもの）──
            // 肉類
            /*  9 – Beef      */ {"牛肉", "ビーフ", "牛脂", "牛エキス", "牛骨"},
            /* 10 – Chicken   */ {"鶏肉", "とり肉", "チキン", "鶏エキス", "鶏ガラ", "鶏脂", "鶏がら",
                                  "鶏もも", "鶏むね", "鶏ささみ", "鶏皮", "鶏ミンチ"},
            /* 11 – Pork      */ {"豚", "ポーク", "ぶた肉"},
            // 魚介類
            /* 12 – Abalone   */ {"あわび", "アワビ", "鮑"},
            /* 13 – Squid     */ {"いか", "イカ", "烏賊"},
            /* 14 – Roe       */ {"いくら", "イクラ"},
            /* 15 – Salmon    */ {"さけ", "サケ", "鮭", "サーモン"},
            /* 16 – Mackerel  */ {"さば", "サバ", "鯖"},
            // 豆・種実類
            /* 17 – Soybean   */ {"大豆", "だいず", "ダイズ", "豆乳"},
            /* 18 – Sesame    */ {"ごま", "ゴマ", "胡麻"},
            /* 19 – Almond    */ {"アーモンド"},
            /* 20 – Macadamia */ {"マカダミア", "マカデミア"},
            // 果物・野菜
            /* 21 – Orange    */ {"オレンジ"},
            /* 22 – Kiwi      */ {"キウイ", "キウィ"},
            /* 23 – Banana    */ {"バナナ"},
            /* 24 – Peach     */ {"もも", "モモ", "桃", "ピーチ"},
            /* 25 – Apple     */ {"りんご", "リンゴ", "林檎", "アップル"},
            /* 26 – Yam       */ {"やまいも", "ヤマイモ", "山芋", "山いも", "長芋", "長いも",
                                  "ながいも", "大和芋", "とろろ"},
            // その他
            /* 27 – Pistachio */ {"ピスタチオ"},
            /* 28 – Gelatin   */ {"ゼラチン"},
    };

    /**
     * Context-exclusion words per allergen (index matches ALLERGEN_KEYWORDS).
     *
     * If a keyword occurrence is part of one of these longer words, that occurrence
     * is ignored. Used to suppress well-known false positives of the new items, e.g.
     *   「鶏もも肉」→ もも（桃）ではない
     *   「すいか」  → いか（烏賊）ではない
     * The basic 9 items intentionally have no exclusions (avoid missing a mandatory allergen).
     */
    private static final String[][] ALLERGEN_EXCLUDES = {
            /*  0 */ {}, /*  1 */ {}, /*  2 */ {}, /*  3 */ {}, /*  4 */ {},
            /*  5 */ {}, /*  6 */ {}, /*  7 */ {}, /*  8 */ {},
            /*  9 Beef      */ {},
            /* 10 Chicken   */ {},
            /* 11 Pork      */ {},
            /* 12 Abalone   */ {},
            /* 13 Squid     */ {"すいか", "スイカ", "西瓜", "いかなご", "イカナゴ"},
            /* 14 Roe       */ {},
            /* 15 Salmon    */ {"さける", "サケル"},
            /* 16 Mackerel  */ {"サバイバル"},
            /* 17 Soybean   */ {},
            /* 18 Sesame    */ {"ごまかし"},
            /* 19 Almond    */ {},
            /* 20 Macadamia */ {},
            /* 21 Orange    */ {},
            /* 22 Kiwi      */ {},
            /* 23 Banana    */ {},
            /* 24 Peach     */ {"もも肉", "モモ肉", "すもも", "スモモ", "鶏もも", "鶏モモ"},
            /* 25 Apple     */ {},
            /* 26 Yam       */ {},
            /* 27 Pistachio */ {},
            /* 28 Gelatin   */ {},
    };

    /** Japanese display name for each allergen (index matches ALLERGEN_KEYWORDS). */
    private static final String[] ALLERGEN_NAMES_JA = {
            "卵", "乳", "小麦", "えび", "かに", "そば", "落花生", "くるみ", "カシューナッツ",
            "牛肉", "鶏肉", "豚肉",
            "あわび", "いか", "いくら", "さけ", "さば",
            "大豆", "ごま", "アーモンド", "マカダミアナッツ",
            "オレンジ", "キウイフルーツ", "バナナ", "もも", "りんご", "やまいも",
            "ピスタチオ", "ゼラチン",
    };

    /** Short Japanese label for the compact (29-item) dashboard. */
    private static final String[] ALLERGEN_SHORT_JA = {
            "卵", "乳", "小麦", "えび", "かに", "そば", "落花生", "くるみ", "カシュー",
            "牛肉", "鶏肉", "豚肉",
            "あわび", "いか", "いくら", "さけ", "さば",
            "大豆", "ごま", "アーモンド", "マカダミア",
            "オレンジ", "キウイ", "バナナ", "もも", "りんご", "やまいも",
            "ピスタチオ", "ゼラチン",
    };

    /** English display name for each allergen (index matches ALLERGEN_KEYWORDS). */
    private static final String[] ALLERGEN_NAMES_EN = {
            "Egg", "Milk", "Wheat", "Shrimp", "Crab", "Buckwheat", "Peanut", "Walnut", "Cashew",
            "Beef", "Chicken", "Pork",
            "Abalone", "Squid", "Salmon roe", "Salmon", "Mackerel",
            "Soybean", "Sesame", "Almond", "Macadamia",
            "Orange", "Kiwi", "Banana", "Peach", "Apple", "Yam",
            "Pistachio", "Gelatin",
    };

    /** Category of each allergen — used for the colour accent on the dashboard. */
    public static final int CAT_BASIC   = 0;
    public static final int CAT_MEAT    = 1;
    public static final int CAT_SEAFOOD = 2;
    public static final int CAT_NUTS    = 3;
    public static final int CAT_FRUIT   = 4;
    public static final int CAT_OTHER   = 5;

    private static final int[] ALLERGEN_CATEGORY = {
            0, 0, 0, 0, 0, 0, 0, 0, 0,
            1, 1, 1,
            2, 2, 2, 2, 2,
            3, 3, 3, 3,
            4, 4, 4, 4, 4, 4,
            5, 5,
    };

    // ── AllergenBox ───────────────────────────────────────────────────────────

    /**
     * A detected allergen bounding box together with ML Kit's OCR confidence
     * for the element that matched the keyword.
     *
     * confidence == 1.0  →  ML Kit is certain about this character sequence.
     * confidence < HIGH_CONF_THRESHOLD → displayed as yellow ("needs verification").
     * confidence ≥ HIGH_CONF_THRESHOLD → displayed as red   ("confirmed allergen").
     */
    public static class AllergenBox {
        /** Bounding box in ML Kit's post-rotation coordinate space. */
        public final Rect  rect;
        /** Element-level OCR confidence: 0.0 (lowest) … 1.0 (highest). */
        public final float confidence;
        /**
         * Index into ALLERGEN_KEYWORDS (0–28).
         * -1 when allergen index is not yet assigned (internal use in findKeywordBox).
         */
        public final int   allergenIndex;

        /** Internal constructor used by findKeywordBox (allergenIndex set to -1). */
        AllergenBox(@NonNull Rect rect, float confidence) {
            this(rect, confidence, -1);
        }

        /** Full constructor — used when allergen index is known. */
        AllergenBox(@NonNull Rect rect, float confidence, int allergenIndex) {
            this.rect          = rect;
            this.confidence    = Math.max(0f, Math.min(1f, confidence));
            this.allergenIndex = allergenIndex;
        }
    }

    /** Cells with confidence ≥ this threshold are shown RED; below → YELLOW.
     *  ML Kit Japanese OCR typically returns element confidence in the 0.3–0.8 range,
     *  so 0.55 is a practical midpoint: clearly-read text → red, ambiguous → yellow. */
    public static final float HIGH_CONF_THRESHOLD = 0.55f;

    // ── Callback interface ────────────────────────────────────────────────────

    /**
     * Called on the ML Kit completion thread after every analyzed frame.
     * The Activity should dispatch UI updates to the main thread itself.
     *
     * @param allergenBoxes   Bounding boxes + confidence for each matched element.
     * @param detectedIndices Indices of allergens found in this frame.
     * @param imageWidth      ImageProxy width  (sensor orientation, before rotation).
     * @param imageHeight     ImageProxy height (sensor orientation, before rotation).
     * @param rotationDegrees Rotation from CameraX (0 / 90 / 180 / 270).
     */
    public interface AllergenCallback {
        void onAllergenDetected(
                List<AllergenBox> allergenBoxes,
                Set<Integer>      detectedIndices,
                int               imageWidth,
                int               imageHeight,
                int               rotationDegrees
        );
    }

    // ── Fields ────────────────────────────────────────────────────────────────

    private final TextRecognizer   mRecognizer;
    private final AllergenCallback mCallback;

    /** Number of allergens scanned per frame (BASIC_COUNT or getAllergenCount()). */
    private volatile int mActiveCount = BASIC_COUNT;

    // ── Constructor ───────────────────────────────────────────────────────────

    public AllergyAnalyzer(@NonNull AllergenCallback callback) {
        mRecognizer = TextRecognition.getClient(new JapaneseTextRecognizerOptions.Builder().build());
        mCallback   = callback;
    }

    /**
     * Switch between the basic 9 items and the expanded 29 items.
     * Safe to call from any thread; takes effect from the next frame.
     */
    public void setActiveCount(int count) {
        mActiveCount = Math.max(1, Math.min(count, ALLERGEN_KEYWORDS.length));
    }

    // ── ImageAnalysis.Analyzer ────────────────────────────────────────────────

    @Override
    @SuppressLint("UnsafeOptInUsageError")
    public void analyze(@NonNull ImageProxy imageProxy) {
        Image mediaImage = imageProxy.getImage();

        if (mediaImage == null) {
            imageProxy.close();
            return;
        }

        final int rotationDegrees = imageProxy.getImageInfo().getRotationDegrees();
        final int imageWidth      = imageProxy.getWidth();
        final int imageHeight     = imageProxy.getHeight();

        InputImage inputImage = InputImage.fromMediaImage(mediaImage, rotationDegrees);

        mRecognizer.process(inputImage)
                .addOnSuccessListener(visionText ->
                        processResult(visionText, imageWidth, imageHeight, rotationDegrees))
                .addOnFailureListener(e ->
                        Log.w(TAG, "Text recognition failed: " + e.getMessage()))
                .addOnCompleteListener(task ->
                        imageProxy.close()); // always release; unblocks CameraX for next frame
    }

    // ── Result processing ─────────────────────────────────────────────────────

    /**
     * Scan every recognized line for allergen keywords.
     *
     * Detection  → Line level with loanword guard.
     * Bounding box → Element level (precise positioning).
     *
     * De-duplication rule
     *   A label often prints the same allergen keyword twice: once in the
     *   ingredient list (top area) and again in a manufacturer's disclaimer
     *   ("本製品は乳成分を含む製品と共通の設備で…", typically at the bottom).
     *   To highlight the ingredient-list occurrence rather than the disclaimer,
     *   we keep only the TOPMOST box (smallest rect.top) for each allergen index.
     */
    private void processResult(@NonNull Text visionText,
                               int imageWidth, int imageHeight, int rotationDegrees) {

        final int activeCount = mActiveCount;

        // Map: allergen index → topmost AllergenBox found so far
        Map<Integer, AllergenBox> bestBox      = new HashMap<>();
        Set<Integer>              detectedIndices = new HashSet<>();

        for (Text.TextBlock block : visionText.getTextBlocks()) {
            for (Text.Line line : block.getLines()) {

                String lineText = line.getText();

                for (int i = 0; i < activeCount; i++) {
                    final String[] excludes = ALLERGEN_EXCLUDES[i];
                    for (String keyword : ALLERGEN_KEYWORDS[i]) {
                        if (containsAllergenKeyword(lineText, keyword, excludes)) {
                            detectedIndices.add(i);

                            // 文字単位（Symbol）でキーワードの文字だけを囲む（iOS と同じ表示）
                            AllergenBox ab = findKeywordCharBox(line, keyword, excludes);
                            if (ab == null) {
                                ab = findKeywordBox(line.getElements(), keyword, excludes);
                            }
                            if (ab != null) {
                                // Attach allergen index and keep only the topmost box
                                AllergenBox abIndexed = new AllergenBox(ab.rect, ab.confidence, i);
                                AllergenBox prev = bestBox.get(i);
                                if (prev == null || abIndexed.rect.top < prev.rect.top) {
                                    bestBox.put(i, abIndexed);
                                }
                            }

                            break; // one keyword match per allergen per line is enough
                        }
                    }
                }
            }
        }

        List<AllergenBox> allergenBoxes = new ArrayList<>(bestBox.values());

        mCallback.onAllergenDetected(
                allergenBoxes, detectedIndices, imageWidth, imageHeight, rotationDegrees);
    }

    // ── Keyword matching ──────────────────────────────────────────────────────

    /** Backwards-compatible overload (no exclusion words). */
    static boolean containsAllergenKeyword(@NonNull String text,
                                           @NonNull String keyword) {
        return containsAllergenKeyword(text, keyword, new String[0]);
    }

    /**
     * Returns true if {@code text} contains {@code keyword} in a context that
     * looks like a genuine allergen declaration — i.e. the keyword is NOT
     * buried inside a katakana loanword compound on both sides, and is NOT
     * part of one of the {@code excludes} words.
     *
     * <h3>Root cause this solves</h3>
     * ML Kit OCR sometimes confuses the katakana long-vowel mark「ー」(U+30FC)
     * with「バ」because their shapes are similar at certain print sizes and
     * angles.  "パスタソース" (pasta sauce) can be misread as "パスタソバス",
     * which contains "ソバ" (buckwheat/そば) → spurious allergen hit.
     *
     * <h3>Guard rule</h3>
     * If the character <em>immediately before</em> AND the character
     * <em>immediately after</em> the keyword are both katakana, the occurrence
     * is treated as embedded in a compound loanword and rejected.
     *
     * <h3>Safety of the rule</h3>
     * Legitimate allergen occurrences are almost never surrounded by katakana
     * on <em>both</em> sides:
     * <pre>
     *   "…、ソバ粉、…"    before=「、」(not katakana) → kept   ✓
     *   "…・ソバ・…"     before=「・」(U+30FB, excluded) → kept ✓
     *   "パスタソバス"    before=「タ」AND after=「ス」 → rejected ✓
     *   "エビフライ"      before=「、」               → kept   ✓
     * </pre>
     * Note: ・(U+30FB, middle dot) is intentionally <em>excluded</em> from the
     * katakana test so that ingredient separators like「ソバ・えび」never cause
     * a valid match to be discarded.
     */
    static boolean containsAllergenKeyword(@NonNull String text,
                                           @NonNull String keyword,
                                           @NonNull String[] excludes) {
        return indexOfAllergenKeyword(text, keyword, excludes) >= 0;
    }

    /**
     * {@link #containsAllergenKeyword} と同じ判定で、最初の「本物の」出現位置を返す。
     * 見つからなければ -1。
     */
    static int indexOfAllergenKeyword(@NonNull String text,
                                      @NonNull String keyword,
                                      @NonNull String[] excludes) {
        int idx = 0;
        while ((idx = text.indexOf(keyword, idx)) >= 0) {
            if (!isSurroundedByKatakana(text, idx, keyword.length())
                    && !isPartOfExcludedWord(text, idx, keyword, excludes)) {
                return idx; // genuine occurrence found
            }
            idx += keyword.length();
        }
        return -1;
    }

    /**
     * Returns true when the occurrence of {@code keyword} at {@code idx} is part of
     * one of the {@code excludes} words (e.g. 「もも」inside「鶏もも肉」).
     */
    private static boolean isPartOfExcludedWord(@NonNull String text, int idx,
                                                @NonNull String keyword,
                                                @NonNull String[] excludes) {
        for (String ex : excludes) {
            int k = ex.indexOf(keyword);
            while (k >= 0) {
                int start = idx - k;
                if (start >= 0 && text.startsWith(ex, start)) return true;
                k = ex.indexOf(keyword, k + 1);
            }
        }
        return false;
    }

    /**
     * Returns true when the substring {@code text[start .. start+len)} is
     * flanked by a katakana character on BOTH sides (indicating it is embedded
     * in a compound katakana loanword).
     */
    private static boolean isSurroundedByKatakana(@NonNull String text,
                                                   int start, int len) {
        boolean prevKatakana = start > 0
                && isKatakana(text.charAt(start - 1));
        int end = start + len;
        boolean nextKatakana = end < text.length()
                && isKatakana(text.charAt(end));
        return prevKatakana && nextKatakana;
    }

    /**
     * Full-width katakana block: U+30A1（ァ）– U+30F6（ヶ）plus U+30FC（ー）.
     * U+30FB（・ middle dot）is deliberately excluded so that ingredient
     * separators are never mistaken for katakana context.
     */
    private static boolean isKatakana(char c) {
        return (c >= 'ァ' && c <= 'ヶ') || c == 'ー';
    }

    // ── Element-level box finding ─────────────────────────────────────────────

    /**
     * キーワードの文字だけを囲む枠を求める（文字単位）。
     *
     * 日本語は単語の間に空白が無いため、ML Kit の Element は行のほぼ全体になることが多い。
     * そこで行内の全 Element の Symbol（1 文字ごとの枠）を並べた文字列を作り、
     * キーワードの出現位置に当たる文字の枠だけを結合する。
     * Symbol が取れない Element は、その枠を文字数で等分して近似する。
     *
     * 信頼度は従来どおり Element の信頼度（該当文字を含む Element の最小値）を使う。
     */
    private static AllergenBox findKeywordCharBox(@NonNull Text.Line line,
                                                  @NonNull String keyword,
                                                  @NonNull String[] excludes) {
        StringBuilder    chars = new StringBuilder();
        List<Rect>       rects = new ArrayList<>();
        List<Float>      confs = new ArrayList<>();

        for (Text.Element element : line.getElements()) {
            float elemConf = element.getConfidence();
            List<Text.Symbol> symbols = element.getSymbols();

            if (symbols != null && !symbols.isEmpty()) {
                for (Text.Symbol sym : symbols) {
                    String t = sym.getText();
                    Rect   r = sym.getBoundingBox();
                    for (int k = 0; k < t.length(); k++) {
                        chars.append(t.charAt(k));
                        rects.add(r);
                        confs.add(elemConf);
                    }
                }
            } else {
                // Symbol が無い場合：Element の枠を文字数で等分（縦書きなら縦方向に等分）
                String t = element.getText();
                Rect   b = element.getBoundingBox();
                int    n = t.length();
                for (int k = 0; k < n; k++) {
                    chars.append(t.charAt(k));
                    rects.add(b == null ? null : sliceRect(b, k, n));
                    confs.add(elemConf);
                }
            }
        }

        int idx = indexOfAllergenKeyword(chars.toString(), keyword, excludes);
        if (idx < 0) return null;

        Rect  union   = null;
        float minConf = 1f;
        for (int k = idx; k < idx + keyword.length() && k < rects.size(); k++) {
            Rect r = rects.get(k);
            if (r != null) {
                if (union == null) union = new Rect(r);
                else               union.union(r);
            }
            minConf = Math.min(minConf, confs.get(k));
        }
        return union != null ? new AllergenBox(union, minConf) : null;
    }

    /** 枠 {@code b} を {@code n} 等分した {@code k} 番目（横長なら横方向、縦長なら縦方向）。 */
    private static Rect sliceRect(@NonNull Rect b, int k, int n) {
        if (b.width() >= b.height()) {
            int l = b.left + b.width() * k / n;
            int r = b.left + b.width() * (k + 1) / n;
            return new Rect(l, b.top, r, b.bottom);
        } else {
            int t  = b.top + b.height() * k / n;
            int bo = b.top + b.height() * (k + 1) / n;
            return new Rect(b.left, t, b.right, bo);
        }
    }

    /**
     * Find an {@link AllergenBox} (rect + confidence) for {@code keyword} in
     * the given element list.
     *
     *  Pass 1 – single element whose text already contains the keyword.
     *  Pass 2 – consecutive elements whose concatenated text contains the keyword
     *            (handles cases where a 2-kanji keyword is split across elements).
     *            Confidence = minimum across the contributing elements (conservative).
     *
     * The loanword guard ({@link #containsAllergenKeyword}) is applied in both passes.
     *
     * @return An {@link AllergenBox}, or {@code null} if the keyword cannot be
     *         located at element level.
     */
    private static AllergenBox findKeywordBox(@NonNull List<Text.Element> elements,
                                              @NonNull String keyword,
                                              @NonNull String[] excludes) {

        // Pass 1: keyword is wholly inside a single element
        for (Text.Element element : elements) {
            if (containsAllergenKeyword(element.getText(), keyword, excludes)) {
                Rect box = element.getBoundingBox();
                if (box != null) {
                    return new AllergenBox(new Rect(box), element.getConfidence());
                }
            }
        }

        // Pass 2: keyword spans consecutive elements
        final int maxWindow = keyword.length() + 2;
        for (int start = 0; start < elements.size(); start++) {
            StringBuilder sb      = new StringBuilder();
            Rect          union   = null;
            float         minConf = 1f;

            for (int end = start;
                 end < elements.size() && (end - start) < maxWindow;
                 end++) {

                Text.Element elem = elements.get(end);
                sb.append(elem.getText());

                Rect box = elem.getBoundingBox();
                if (box != null) {
                    if (union == null) union = new Rect(box);
                    else               union.union(box);
                }
                minConf = Math.min(minConf, elem.getConfidence());

                if (containsAllergenKeyword(sb.toString(), keyword, excludes)) {
                    return (union != null) ? new AllergenBox(union, minConf) : null;
                }
            }
        }

        return null; // keyword not locatable at element level
    }

    // ── Cleanup ───────────────────────────────────────────────────────────────

    /** Release the ML Kit recognizer. Call from Activity.onDestroy(). */
    public void shutdown() {
        mRecognizer.close();
    }

    // ── Static accessors (used by MainActivity to build the dashboard) ────────

    public static String[] getAllergenNamesJa()  { return ALLERGEN_NAMES_JA; }
    public static String[] getAllergenShortJa()  { return ALLERGEN_SHORT_JA; }
    public static String[] getAllergenNamesEn()  { return ALLERGEN_NAMES_EN; }
    public static int      getCategory(int i)    { return ALLERGEN_CATEGORY[i]; }
    /** Total number of defined allergens (basic + expanded = 29). */
    public static int getAllergenCount()          { return ALLERGEN_KEYWORDS.length; }
}
