package com.gigya.android.sdk.api;

import com.gigya.android.sdk.AccountManager;
import com.gigya.android.sdk.GigyaCallback;
import com.gigya.android.sdk.SessionManager;
import com.gigya.android.sdk.model.account.SessionInfo;
import com.gigya.android.sdk.network.GigyaApiRequest;
import com.gigya.android.sdk.network.GigyaApiRequestBuilder;
import com.gigya.android.sdk.network.GigyaApiResponse;
import com.gigya.android.sdk.network.adapter.NetworkAdapter;

import java.util.Map;


@SuppressWarnings("unchecked")
public class AnonymousApi<H> extends BaseApi<H> {

    private Class<H> clazz;

    private final AccountManager accountManager;

    public AnonymousApi(NetworkAdapter networkAdapter, SessionManager sessionManager, AccountManager accountManager) {
        super(networkAdapter, sessionManager);
        this.accountManager = accountManager;
    }

    public AnonymousApi(NetworkAdapter networkAdapter, SessionManager sessionManager, AccountManager accountManager,
                        Class<H> clazz) {
        super(networkAdapter, sessionManager);
        this.accountManager = accountManager;
        this.clazz = clazz;
    }

    public void call(final String api, Map<String, Object> params, final GigyaCallback<H> callback) {
        GigyaApiRequest request = new GigyaApiRequestBuilder(sessionManager).params(params).api(api).build();
        sendRequest(request, api, callback);
    }

    @Override
    protected void onRequestSuccess(String api, GigyaApiResponse response, GigyaCallback<H> callback) {
        if (sessionManager != null && response.contains("sessionSecret") && response.contains("sessionToken")) {
            SessionInfo session = response.getField("sessionInfo", SessionInfo.class);
            sessionManager.setSession(session);
            accountManager.invalidateAccount();
        }
        if (api.equals("accounts.setAccountInfo")) {
            accountManager.invalidateAccount();
        }
        if (clazz == null) {
            /* Callback will return GigyaApiResponse instance */
            callback.onSuccess((H) response);
        } else {
            H parsed = response.getGson().fromJson(response.asJson(), clazz);
            callback.onSuccess(parsed);
        }
    }
}
