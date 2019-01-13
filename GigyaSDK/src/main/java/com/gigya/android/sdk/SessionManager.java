package com.gigya.android.sdk;

import android.content.Context;
import android.content.SharedPreferences;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.text.TextUtils;

import com.gigya.android.sdk.encryption.EncryptionException;
import com.gigya.android.sdk.encryption.IEncryptor;
import com.gigya.android.sdk.log.GigyaLogger;
import com.gigya.android.sdk.model.Configuration;
import com.gigya.android.sdk.model.SessionInfo;
import com.gigya.android.sdk.utils.CipherUtils;

import org.json.JSONObject;

import javax.crypto.SecretKey;

public class SessionManager {

    /*
     * SDK shared preference file key for _session persistence.
     */
    private static final String PREFS_FILE_KEY = "GSLIB";

    /*
     * SDK shared preference key for _session string.
     */
    private static final String PREFS_KEY_SESSION = "GS_PREFS";

    private static final String LOG_TAG = "SessionManager";

    @Nullable
    private SharedPreferences _prefs;

    @NonNull
    private Gigya _gigya;

    @Nullable
    private SessionInfo _session;

    @Nullable
    public SessionInfo getSession() {
        return _session;
    }

    private IEncryptor _encryptor;

    /*
    Check if the current _session is valid.
     */
    public boolean isValidSession() {
        boolean isValid = false;
        if (_session != null) {
            isValid = _session.isValid();
            GigyaLogger.debug(LOG_TAG, "isValid: " + String.valueOf(isValid));
        }
        return isValid;
    }

    public SessionManager(@NonNull Gigya gigya, IEncryptor encryptor) {
        _encryptor = encryptor;
        this._gigya = gigya;
        // Get reference to SDK shared preference file.
        load();
    }

    @NonNull
    private SharedPreferences getPrefs() {
        if (this._prefs == null) {
            this._prefs = _gigya.getContext().getSharedPreferences(PREFS_FILE_KEY, Context.MODE_PRIVATE);
        }
        return _prefs;
    }

    /*
    Manually set the current session (override current).
     */
    public void setSession(SessionInfo session) {
        if (session == null) {
            GigyaLogger.error(LOG_TAG, "Failed to parse _session info from response");
            return;
        }
        _session = session;
        GigyaLogger.debug(LOG_TAG, "setSession : " + _session.toString());
        save();
    }

    //region Session persistence

    /*
    Clear _session data from shared preferences. Nullify _session instance.
     */
    public void clear() {
        GigyaLogger.debug(LOG_TAG, "clear: ");
        getPrefs().edit().remove(PREFS_KEY_SESSION).apply();
        this._session = null;
    }

    /*
    Save _session to shared preferences (encrypted).
     */
    void save() {
        JSONObject jsonObject = new JSONObject();
        try {
            jsonObject.put("sessionToken", _session != null ? _session.getSessionToken() : null);
            jsonObject.put("sessionSecret", _session != null ? _session.getSessionSecret() : null);
            jsonObject.put("expirationTime", _session != null ? _session.getExpirationTime() : null);

            final Configuration configuration = _gigya.getConfiguration();
            jsonObject.put("ucid", configuration.getUCID());
            jsonObject.put("gmid", configuration.getGMID());

            // Encrypt _session.
            final String sessionJSON = jsonObject.toString();
            final String encryptedSession = encrypt(sessionJSON);

            // Save to preferences.
            getPrefs().edit().putString(PREFS_KEY_SESSION, encryptedSession).apply();
        } catch (Exception ex) {
            ex.printStackTrace();
            GigyaLogger.error(LOG_TAG, "sessionToJson: Error in conversion to " + ex.getMessage());
        }
    }

    private boolean isLegacySession() {
        return (!TextUtils.isEmpty(getPrefs().getString("session.Token", "")));
    }

    /*
    Try to load persistent _session (encrypted).
     */
    private void load() {
        if (isLegacySession()) {
            loadLegacySession();
            return;
        }
        // Load from preferences.
        if (getPrefs().contains(PREFS_KEY_SESSION)) {
            String encryptedSession = getPrefs().getString(PREFS_KEY_SESSION, null);
            if (!TextUtils.isEmpty(encryptedSession)) {

                // Decrypt _session string.
                final String sessionJson = decrypt(encryptedSession);

                try {
                    JSONObject jsonObject = new JSONObject(sessionJson);
                    final String sessionToken = jsonObject.has("sessionToken") ? jsonObject.getString("sessionToken") : null;
                    final String sessionSecret = jsonObject.has("sessionSecret") ? jsonObject.getString("sessionSecret") : null;
                    final long expirationTime = jsonObject.has("expirationTime") ? jsonObject.getLong("expirationTime") : -1;
                    _session = new SessionInfo(sessionSecret, sessionToken, expirationTime);

                    final String ucid = jsonObject.getString("ucid");
                    final String gmid = jsonObject.getString("gmid");
                    _gigya.getConfiguration().updateIds(ucid, gmid);

                    GigyaLogger.debug(LOG_TAG, "Session load: " + _session.toString());
                } catch (Exception ex) {
                    ex.printStackTrace();
                    GigyaLogger.error(LOG_TAG, "sessionToJson: Error in conversion from" + ex.getMessage());
                }
            }
        }
    }

    /*
    Load legacy session from prefs, clear it and save as new.
     */
    private void loadLegacySession() {
        SharedPreferences prefs = getPrefs();
        final String token = prefs.getString("session.Token", null);
        final String secret = prefs.getString("session.Secret", null);
        final long expiration = prefs.getLong("session.ExpirationTime", 0);
        _session = new SessionInfo(secret, token, expiration);

        final String ucid = prefs.getString("ucid", null);
        final String gmid = prefs.getString("gmid", null);
        _gigya.getConfiguration().updateIds(ucid, gmid);

        // Clear the legacy session.
        SharedPreferences.Editor editor = prefs.edit();
        editor.remove("ucid");
        editor.remove("gmid");
        editor.remove("lastLoginProvider");
        editor.remove("session.Token");
        editor.remove("session.Secret");
        editor.remove("tsOffset");
        editor.remove("session.ExpirationTime");
        editor.apply();

        // Save session in current construct.
        save();
    }

    //endregion

    //region Session encryption/decryption

    /*
    Encryptor class is responsible for generating secret/private/public keys for _session secret encryption purposes.
     */

    private static final String ENCRYPTION_ALGORITHM = "AES";

    /*
    Encrypt session secret.
     */
    private String encrypt(String plain) throws EncryptionException {
        GigyaLogger.debug(LOG_TAG, ENCRYPTION_ALGORITHM + " encrypt: ");
        try {
            final SecretKey secretKey = _encryptor.getKey(_gigya.getContext(), getPrefs());
            return CipherUtils.encrypt(plain, ENCRYPTION_ALGORITHM, secretKey);
        } catch (Exception ex) {
            ex.printStackTrace();
            throw new EncryptionException("Session encryption exception", ex.getCause());
        }
    }

    /*
    Decrypt encrypt secret.
     */
    private String decrypt(String encrypted) throws EncryptionException {
        GigyaLogger.debug(LOG_TAG, ENCRYPTION_ALGORITHM + " decrypt: ");
        try {
            final SecretKey secretKey = _encryptor.getKey(_gigya.getContext(), getPrefs());
            return CipherUtils.decrypt(encrypted, ENCRYPTION_ALGORITHM, secretKey);
        } catch (Exception ex) {
            ex.printStackTrace();
            throw new EncryptionException("Session encryption exception", ex.getCause());
        }
    }

    //endregion

}
