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
 *  3. Scans every recognized Text.Line for the 9 major Japanese allergen keywords.
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
    // Index  0 = 卵   (Egg)
    // Index  1 = 乳   (Milk)
    // Index  2 = 小麦 (Wheat)
    // Index  3 = えび (Shrimp)
    // Index  4 = かに (Crab)
    // Index  5 = そば (Buckwheat)
    // Index  6 = 落花生 (Peanut)
    // Index  7 = くるみ (Walnut)   ← mandatory since April 2025
    // Index  8 = カシューナッツ (Cashew)

    private static final String[][] ALLERGEN_KEYWORDS = {
            /* 0 – Egg       */ {"卵", "たまご", "玉子"},
            /* 1 – Milk      */ {"乳", "ミルク", "牛乳", "乳成分"},
            /* 2 – Wheat     */ {"小麦", "こむぎ"},
            /* 3 – Shrimp    */ {"えび", "エビ", "海老"},
            /* 4 – Crab      */ {"かに", "カニ", "蟹"},
            /* 5 – Buckwheat */ {"そば", "ソバ", "蕎麦"},
            /* 6 – Peanut    */ {"落花生", "ピーナッツ"},
            /* 7 – Walnut    */ {"くるみ", "クルミ", "胡桃"},
            /* 8 – Cashew    */ {"カシューナッツ", "かしゅーなっつ"},
    };

    /** Japanese display name for each allergen (index matches ALLERGEN_KEYWORDS). */
    private static final String[] ALLERGEN_NAMES_JA = {
            "卵", "乳", "小麦", "えび", "かに", "そば", "落花生", "くるみ", "カシューナッツ"
    };

    /** English display name for each allergen (index matches ALLERGEN_KEYWORDS). */
    private static final String[] ALLERGEN_NAMES_EN = {
            "Egg", "Milk", "Wheat", "Shrimp", "Crab", "Buckwheat", "Peanut", "Walnut", "Cashew"
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
         * Index into ALLERGEN_KEYWORDS (0–8).
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
     * @param detectedIndices Indices (0–8) of allergens found in this frame.
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

    // ── Constructor ───────────────────────────────────────────────────────────

    public AllergyAnalyzer(@NonNull AllergenCallback callback) {
        mRecognizer = TextRecognition.getClient(new JapaneseTextRecognizerOptions.Builder().build());
        mCallback   = callback;
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

        // Map: allergen index → topmost AllergenBox found so far
        Map<Integer, AllergenBox> bestBox      = new HashMap<>();
        Set<Integer>              detectedIndices = new HashSet<>();

        for (Text.TextBlock block : visionText.getTextBlocks()) {
            for (Text.Line line : block.getLines()) {

                String lineText = line.getText();

                for (int i = 0; i < ALLERGEN_KEYWORDS.length; i++) {
                    for (String keyword : ALLERGEN_KEYWORDS[i]) {
                        if (containsAllergenKeyword(lineText, keyword)) {
                            detectedIndices.add(i);

                            AllergenBox ab = findKeywordBox(line.getElements(), keyword);
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

    /**
     * Returns true if {@code text} contains {@code keyword} in a context that
     * looks like a genuine allergen declaration — i.e. the keyword is NOT
     * buried inside a katakana loanword compound on both sides.
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
                                           @NonNull String keyword) {
        int idx = 0;
        while ((idx = text.indexOf(keyword, idx)) >= 0) {
            if (!isSurroundedByKatakana(text, idx, keyword.length())) {
                return true; // genuine occurrence found
            }
            idx += keyword.length();
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
                                              @NonNull String keyword) {

        // Pass 1: keyword is wholly inside a single element
        for (Text.Element element : elements) {
            if (containsAllergenKeyword(element.getText(), keyword)) {
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

                if (containsAllergenKeyword(sb.toString(), keyword)) {
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

    public static String[] getAllergenNamesJa() { return ALLERGEN_NAMES_JA; }
    public static String[] getAllergenNamesEn()  { return ALLERGEN_NAMES_EN; }
    public static int getAllergenCount()          { return ALLERGEN_KEYWORDS.length; }
}
