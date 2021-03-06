package info.blockchain.wallet.viewModel;

import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.annotation.StringRes;
import android.support.annotation.UiThread;
import android.support.annotation.VisibleForTesting;

import info.blockchain.api.WalletPayload;
import info.blockchain.wallet.datamanagers.AuthDataManager;
import info.blockchain.wallet.util.AppUtil;
import info.blockchain.wallet.util.CharSequenceX;
import info.blockchain.wallet.view.helpers.ToastCustom;

import javax.inject.Inject;

import piuk.blockchain.android.R;
import piuk.blockchain.android.annotations.Thunk;
import piuk.blockchain.android.di.Injector;
import rx.Subscriber;
import rx.subscriptions.CompositeSubscription;

@SuppressWarnings("WeakerAccess")
public class ManualPairingViewModel implements ViewModel {

    @Inject protected AppUtil mAppUtil;
    @Inject protected AuthDataManager mAuthDataManager;
    @Thunk DataListener mDataListener;
    @VisibleForTesting boolean mWaitingForAuth = false;
    @VisibleForTesting CompositeSubscription mCompositeSubscription;

    public interface DataListener {

        String getGuid();

        String getPassword();

        void goToPinPage();

        void showToast(@StringRes int message, @ToastCustom.ToastType String toastType);

        void updateWaitingForAuthDialog(int secondsRemaining);

        void showProgressDialog(@StringRes int messageId, @Nullable String suffix, boolean cancellable);

        void dismissProgressDialog();

        void resetPasswordField();

    }

    public ManualPairingViewModel(DataListener listener) {
        Injector.getInstance().getAppComponent().inject(this);
        mDataListener = listener;
        mCompositeSubscription = new CompositeSubscription();
    }

    public void onViewReady() {
        // No-op
    }

    public void onContinueClicked() {

        String guid = mDataListener.getGuid();
        String password = mDataListener.getPassword();

        if (guid == null || guid.isEmpty()) {
            showErrorToast(R.string.invalid_guid);
        } else if (password == null || password.isEmpty()) {
            showErrorToast(R.string.invalid_password);
        } else {
            verifyPassword(new CharSequenceX(password), guid);
        }
    }

    private void verifyPassword(CharSequenceX password, String guid) {
        mDataListener.showProgressDialog(R.string.validating_password, null, false);

        mWaitingForAuth = true;

        mCompositeSubscription.add(
                mAuthDataManager.getSessionId(guid)
                        .flatMap(sessionId -> mAuthDataManager.getEncryptedPayload(guid, sessionId))
                        .subscribe(response -> {
                            if (response.equals(WalletPayload.KEY_AUTH_REQUIRED)) {
                                showCheckEmailDialog();

                                mCompositeSubscription.add(
                                        mAuthDataManager.startPollingAuthStatus(guid).subscribe(payloadResponse -> {
                                            mWaitingForAuth = false;

                                            if (payloadResponse == null || payloadResponse.equals(WalletPayload.KEY_AUTH_REQUIRED)) {
                                                showErrorToastAndRestartApp(R.string.auth_failed);
                                                return;

                                            }
                                            attemptDecryptPayload(password, guid, payloadResponse);

                                        }, throwable -> {
                                            mWaitingForAuth = false;
                                            showErrorToastAndRestartApp(R.string.auth_failed);
                                        }));
                            } else {
                                mWaitingForAuth = false;
                                attemptDecryptPayload(password, guid, response);
                            }
                        }, throwable -> {
                            throwable.printStackTrace();
                            showErrorToastAndRestartApp(R.string.auth_failed);
                        }));
    }

    private void attemptDecryptPayload(CharSequenceX password, String guid, String payload) {
        mAuthDataManager.attemptDecryptPayload(password, guid, payload, new AuthDataManager.DecryptPayloadListener() {
            @Override
            public void onSuccess() {
                mDataListener.goToPinPage();
            }

            @Override
            public void onPairFail() {
                showErrorToast(R.string.pairing_failed);
            }

            @Override
            public void onAuthFail() {
                showErrorToast(R.string.auth_failed);
            }

            @Override
            public void onFatalError() {
                showErrorToastAndRestartApp(R.string.auth_failed);
            }
        });
    }

    private void showCheckEmailDialog() {
        mDataListener.showProgressDialog(R.string.check_email_to_auth_login, "120", true);

        mCompositeSubscription.add(mAuthDataManager.createCheckEmailTimer()
                .takeUntil(integer -> !mWaitingForAuth)
                .subscribe(new Subscriber<Integer>() {
                    @Override
                    public void onCompleted() {
                        // No-op
                    }

                    @Override
                    public void onError(Throwable e) {
                        showErrorToast(R.string.auth_failed);
                        mWaitingForAuth = false;
                    }

                    @Override
                    public void onNext(Integer integer) {
                        if (integer <= 0) {
                            // Only called if timer has run out
                            showErrorToastAndRestartApp(R.string.pairing_failed);
                        } else {
                            mDataListener.updateWaitingForAuthDialog(integer);
                        }
                    }
                }));
    }

    public void onProgressCancelled() {
        mWaitingForAuth = false;
        destroy();
    }

    @UiThread
    @Thunk void showErrorToast(@StringRes int message) {
        mDataListener.dismissProgressDialog();
        mDataListener.resetPasswordField();
        mDataListener.showToast(message, ToastCustom.TYPE_ERROR);
    }

    @UiThread
    @Thunk void showErrorToastAndRestartApp(@StringRes int message) {
        mDataListener.resetPasswordField();
        mDataListener.dismissProgressDialog();
        mDataListener.showToast(message, ToastCustom.TYPE_ERROR);
        mAppUtil.clearCredentialsAndRestart();
    }

    @NonNull
    public AppUtil getAppUtil() {
        return mAppUtil;
    }

    @Override
    public void destroy() {
        // Clear all subscriptions so that:
        // 1) all processes are cancelled
        // 2) processes don't try to update a null View
        // 3) background processes don't leak memory
        mCompositeSubscription.clear();
    }
}
