package tfsapps.aiallergychecker;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.billingclient.api.AcknowledgePurchaseParams;
import com.android.billingclient.api.BillingClient;
import com.android.billingclient.api.BillingClientStateListener;
import com.android.billingclient.api.BillingFlowParams;
import com.android.billingclient.api.BillingResult;
import com.android.billingclient.api.PendingPurchasesParams;
import com.android.billingclient.api.ProductDetails;
import com.android.billingclient.api.Purchase;
import com.android.billingclient.api.PurchasesUpdatedListener;
import com.android.billingclient.api.QueryProductDetailsParams;
import com.android.billingclient.api.QueryPurchasesParams;
import com.android.billingclient.api.UnfetchedProduct;

import java.util.Collections;
import java.util.List;

/**
 * BillingManager — プレミアムプラン（買い切り・アプリ内アイテム）の購入管理
 *
 *  ・Google Play Console に登録した商品 {@link #PRODUCT_PREMIUM} の価格を取得して表示用に提供
 *  ・購入フローの起動、購入の承認（acknowledge）
 *  ・起動時 / 再接続時に購入状態を照会して自動で復元（機種変更・再インストール・返金に追従）
 *  ・判定結果は SharedPreferences にキャッシュし、起動直後（Play 照会前）でも広告がチラつかないようにする
 *
 * 購入の承認は 3 日以内に行わないと自動返金されるため、未承認の購入は見つけ次第承認する。
 */
public class BillingManager implements PurchasesUpdatedListener {

    private static final String TAG = "BillingManager";

    /** Play Console の「アプリ内アイテム」に登録する商品 ID（買い切り ¥298）。 */
    public static final String PRODUCT_PREMIUM = "premium";

    private static final String PREFS_NAME   = "AllergyCheckerPrefs";
    private static final String KEY_PREMIUM  = "premium_owned";

    /** UI へ通知するイベント（すべてメインスレッドで呼ばれる）。 */
    public interface Listener {
        /** プレミアム状態が変化した。{@code byPurchase} = 今回の購入操作によるもの。 */
        void onPremiumChanged(boolean premium, boolean byPurchase);
        /** Play から価格（例「￥298」）を取得した。 */
        void onPriceLoaded(@NonNull String formattedPrice);
        /** 支払いが保留中（コンビニ払いなど）。完了後に自動で反映される。 */
        void onPurchasePending();
        /** 購入画面を開けなかった / 購入に失敗した（キャンセル以外）。 */
        void onPurchaseFailed(int responseCode, @NonNull String debugMessage);
        /** 「購入を復元」の結果。 */
        void onRestoreFinished(boolean success, boolean premium);
    }

    private final Context          mAppContext;
    private final BillingClient    mClient;
    private final Handler          mMain = new Handler(Looper.getMainLooper());
    private final SharedPreferences mPrefs;

    @Nullable private Listener       mListener;
    @Nullable private ProductDetails mPremiumDetails;
    /** 購入に使う購入オプション（Play Console の「購入」オプション）の価格情報 */
    @Nullable private ProductDetails.OneTimePurchaseOfferDetails mPremiumOffer;
    private boolean mPurchaseInFlight = false;
    private boolean mDestroyed        = false;
    /**
     * 接続状態はアプリ側で管理する。
     * enableAutoServiceReconnection() を有効にした BillingClient は、まだ接続していなくても
     * isReady() が true を返すことがあり、それを信じると接続も商品照会も行われなくなるため。
     */
    private volatile boolean mConnecting = false;
    private volatile boolean mConnected  = false;

    public BillingManager(@NonNull Context context, @Nullable Listener listener) {
        mAppContext = context.getApplicationContext();
        mPrefs      = mAppContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        mListener   = listener;
        mClient = BillingClient.newBuilder(mAppContext)
                .setListener(this)
                .enablePendingPurchases(PendingPurchasesParams.newBuilder()
                        .enableOneTimeProducts()
                        .build())
                .enableAutoServiceReconnection()
                .build();
        connect();
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Public API
    // ─────────────────────────────────────────────────────────────────────────

    /** キャッシュ済みのプレミアム状態（起動直後から即座に使える）。 */
    public boolean isPremium() {
        return mPrefs.getBoolean(KEY_PREMIUM, false);
    }

    /** Play から取得した表示用価格。未取得なら null。 */
    @Nullable
    public String getFormattedPrice() {
        return mPremiumOffer != null ? mPremiumOffer.getFormattedPrice() : null;
    }

    /** 価格を取得済みで購入ボタンを押せる状態か。 */
    public boolean isReadyToPurchase() {
        return mPremiumDetails != null && mPremiumOffer != null && mConnected;
    }

    /**
     * 「購入」タイプの購入オプションを選ぶ（レンタルは除外）。
     * Play Console の新しい 1 回限りのアイテム（購入オプション方式）では
     * getOneTimePurchaseOfferDetailsList() が正式な取得方法。
     * 取得できなければ、従来の下位互換オプション（getOneTimePurchaseOfferDetails）を使う。
     */
    @Nullable
    private static ProductDetails.OneTimePurchaseOfferDetails pickBuyOffer(
            @NonNull ProductDetails pd) {
        List<ProductDetails.OneTimePurchaseOfferDetails> offers =
                pd.getOneTimePurchaseOfferDetailsList();
        if (offers != null) {
            for (ProductDetails.OneTimePurchaseOfferDetails o : offers) {
                if (o.getRentalDetails() == null) return o;
            }
        }
        return pd.getOneTimePurchaseOfferDetails();
    }

    /**
     * 購入フローを開始する。
     * @return 開始できた場合 true（商品情報が未取得なら false）
     */
    public boolean launchPurchase(@NonNull Activity activity) {
        if (!mConnected) {
            connect();                 // 接続し直して商品情報を再取得
            return false;
        }
        if (mPremiumDetails == null || mPremiumOffer == null) {
            queryProductDetails();     // 商品情報だけ取り直す
            return false;
        }
        BillingFlowParams.ProductDetailsParams.Builder pdpb =
                BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(mPremiumDetails);
        String offerToken = mPremiumOffer.getOfferToken();
        if (offerToken != null && !offerToken.isEmpty()) {
            pdpb.setOfferToken(offerToken);  // 購入オプション方式では必須
        }
        BillingFlowParams.ProductDetailsParams pdp = pdpb.build();
        BillingFlowParams params = BillingFlowParams.newBuilder()
                .setProductDetailsParamsList(Collections.singletonList(pdp))
                .build();
        mPurchaseInFlight = true;
        Log.i(TAG, "launchBillingFlow: product=" + mPremiumDetails.getProductId()
                + " option=" + mPremiumOffer.getPurchaseOptionId()
                + " offerToken=" + (offerToken != null && !offerToken.isEmpty()));
        BillingResult r = mClient.launchBillingFlow(activity, params);
        Log.i(TAG, "launchBillingFlow result: code=" + r.getResponseCode()
                + " msg=" + r.getDebugMessage());
        if (r.getResponseCode() != BillingClient.BillingResponseCode.OK) {
            mPurchaseInFlight = false;
            if (r.getResponseCode() == BillingClient.BillingResponseCode.SERVICE_DISCONNECTED) {
                mConnected = false;
                connect();
            }
            // エラー内容はリスナー経由で表示済みなので true（呼び出し側で重ねて表示しない）
            notifyPurchaseFailed(r.getResponseCode(), r.getDebugMessage());
        }
        return true;
    }

    /** 「購入を復元」：Play に購入状態を問い合わせ直す。 */
    public void restore() {
        if (!mConnected) {
            connect();
            post(() -> { if (mListener != null) mListener.onRestoreFinished(false, isPremium()); });
            return;
        }
        queryPurchases(true);
    }

    /** Activity 復帰時などに呼ぶ：保留中だった支払いの完了や返金を反映する。 */
    public void refreshPurchases() {
        if (mConnected) {
            queryPurchases(false);
            if (mPremiumOffer == null) queryProductDetails();   // 価格が未取得なら取り直す
        } else {
            connect();
        }
    }

    /** 接続・商品取得の進行状況を Logcat に出す（タグ: BillingManager）。 */
    private static void setStatus(@NonNull String status) {
        Log.i(TAG, "status: " + status);
    }

    public void destroy() {
        mDestroyed = true;
        mListener  = null;
        mMain.removeCallbacksAndMessages(null);
        mClient.endConnection();
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Connection
    // ─────────────────────────────────────────────────────────────────────────

    private void connect() {
        if (mDestroyed || mConnecting || mConnected) return;
        mConnecting = true;
        setStatus("Google Play に接続中…");
        mClient.startConnection(new BillingClientStateListener() {
            @Override
            public void onBillingSetupFinished(@NonNull BillingResult result) {
                mConnecting = false;
                mConnected  = result.getResponseCode() == BillingClient.BillingResponseCode.OK;
                if (result.getResponseCode() == BillingClient.BillingResponseCode.OK) {
                    setStatus("接続OK → 商品「" + PRODUCT_PREMIUM + "」を照会中…");
                    queryProductDetails();
                    queryPurchases(false);
                } else {
                    // 3 = BILLING_UNAVAILABLE（Play ストア未ログイン / 古い / 非対応端末 など）
                    setStatus("接続失敗 code=" + result.getResponseCode()
                            + " " + result.getDebugMessage());
                }
            }

            @Override
            public void onBillingServiceDisconnected() {
                mConnecting = false;
                mConnected  = false;
                // enableAutoServiceReconnection() により次回の呼び出し時に自動再接続される
                setStatus("Google Play との接続が切れました");
            }
        });
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Product details（価格）
    // ─────────────────────────────────────────────────────────────────────────

    private void queryProductDetails() {
        QueryProductDetailsParams.Product product = QueryProductDetailsParams.Product.newBuilder()
                .setProductId(PRODUCT_PREMIUM)
                .setProductType(BillingClient.ProductType.INAPP)
                .build();
        QueryProductDetailsParams params = QueryProductDetailsParams.newBuilder()
                .setProductList(Collections.singletonList(product))
                .build();

        mClient.queryProductDetailsAsync(params, (billingResult, queryResult) -> {
            if (billingResult.getResponseCode() != BillingClient.BillingResponseCode.OK) {
                setStatus("商品照会失敗 code=" + billingResult.getResponseCode()
                        + " " + billingResult.getDebugMessage());
                return;
            }
            List<ProductDetails> list = queryResult.getProductDetailsList();
            for (ProductDetails pd : list) {
                if (PRODUCT_PREMIUM.equals(pd.getProductId())) {
                    ProductDetails.OneTimePurchaseOfferDetails offer = pickBuyOffer(pd);
                    if (offer == null) {
                        setStatus("商品はあるが「購入」オプションが取得できない");
                        return;
                    }
                    setStatus("取得OK " + pd.getProductId() + " " + offer.getFormattedPrice());
                    post(() -> {
                        mPremiumDetails = pd;
                        mPremiumOffer   = offer;
                        if (mListener != null) mListener.onPriceLoaded(offer.getFormattedPrice());
                    });
                    return;
                }
            }
            // 取得できなかった理由（NO_ELIGIBLE_OFFER など）
            StringBuilder why = new StringBuilder();
            for (UnfetchedProduct u : queryResult.getUnfetchedProductList()) {
                why.append(" ").append(u.getProductId()).append(":status=").append(u.getStatusCode());
            }
            setStatus("商品が返されない（" + list.size() + "件）" + why
                    + " → 商品ID・有効化・ライセンステスター・反映待ちを確認");
        });
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Purchases
    // ─────────────────────────────────────────────────────────────────────────

    private void queryPurchases(boolean fromRestore) {
        QueryPurchasesParams params = QueryPurchasesParams.newBuilder()
                .setProductType(BillingClient.ProductType.INAPP)
                .build();
        mClient.queryPurchasesAsync(params, (billingResult, purchases) -> {
            boolean ok = billingResult.getResponseCode() == BillingClient.BillingResponseCode.OK;
            post(() -> {
                if (ok) {
                    boolean owned = false;
                    for (Purchase p : purchases) {
                        if (isPremiumPurchase(p)
                                && p.getPurchaseState() == Purchase.PurchaseState.PURCHASED) {
                            owned = true;
                            acknowledgeIfNeeded(p);
                        }
                    }
                    // Play の照会に成功した時だけ確定させる（オフライン時はキャッシュを維持）
                    setPremium(owned, false);
                } else {
                    Log.w(TAG, "queryPurchases failed: " + billingResult.getDebugMessage());
                }
                if (fromRestore && mListener != null) {
                    mListener.onRestoreFinished(ok, isPremium());
                }
            });
        });
    }

    @Override
    public void onPurchasesUpdated(@NonNull BillingResult result,
                                   @Nullable List<Purchase> purchases) {
        final int code = result.getResponseCode();
        post(() -> {
            mPurchaseInFlight = false;
            if (code == BillingClient.BillingResponseCode.OK && purchases != null) {
                for (Purchase p : purchases) handlePurchase(p);
            } else if (code == BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED) {
                // 購入済み（別端末・再インストール）→ そのまま復元
                queryPurchases(false);
            } else if (code != BillingClient.BillingResponseCode.USER_CANCELED) {
                Log.w(TAG, "Purchase failed: " + code + " " + result.getDebugMessage());
                notifyPurchaseFailed(code, result.getDebugMessage());
            }
        });
    }

    private void handlePurchase(@NonNull Purchase p) {
        if (!isPremiumPurchase(p)) return;
        int state = p.getPurchaseState();
        if (state == Purchase.PurchaseState.PURCHASED) {
            setPremium(true, true);
            acknowledgeIfNeeded(p);
        } else if (state == Purchase.PurchaseState.PENDING) {
            if (mListener != null) mListener.onPurchasePending();
        }
    }

    private void acknowledgeIfNeeded(@NonNull Purchase p) {
        if (p.isAcknowledged()) return;
        AcknowledgePurchaseParams params = AcknowledgePurchaseParams.newBuilder()
                .setPurchaseToken(p.getPurchaseToken())
                .build();
        mClient.acknowledgePurchase(params, r -> {
            if (r.getResponseCode() != BillingClient.BillingResponseCode.OK) {
                // 次回起動時の queryPurchases で再試行される
                Log.w(TAG, "acknowledge failed: " + r.getDebugMessage());
            }
        });
    }

    private static boolean isPremiumPurchase(@NonNull Purchase p) {
        return p.getProducts().contains(PRODUCT_PREMIUM);
    }

    private void setPremium(boolean premium, boolean byPurchase) {
        boolean prev = isPremium();
        if (prev != premium) {
            mPrefs.edit().putBoolean(KEY_PREMIUM, premium).apply();
        }
        if ((prev != premium || byPurchase) && mListener != null) {
            mListener.onPremiumChanged(premium, byPurchase);
        }
    }

    private void notifyPurchaseFailed(int code, @Nullable String msg) {
        final String m = msg != null ? msg : "";
        post(() -> { if (mListener != null) mListener.onPurchaseFailed(code, m); });
    }

    private void post(Runnable r) {
        if (mDestroyed) return;
        if (Looper.myLooper() == Looper.getMainLooper()) r.run();
        else mMain.post(r);
    }
}
