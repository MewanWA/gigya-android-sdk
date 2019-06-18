package com.gigya.android.sdk.tfa.push.firebase;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;
import android.support.v4.app.NotificationCompat;
import android.support.v4.app.NotificationManagerCompat;

import com.gigya.android.sdk.GigyaLogger;
import com.gigya.android.sdk.tfa.R;
import com.gigya.android.sdk.tfa.push.PushTFAReceiver;
import com.gigya.android.sdk.tfa.ui.PushTFAActivity;
import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

import java.util.Map;

import static android.content.Intent.FLAG_ACTIVITY_NO_ANIMATION;

/**
 * Main FCM messaging service.
 * Extend this service if your application already uses the FirebaseMessagingService.
 */
public class GigyaFirebaseMessagingService extends FirebaseMessagingService {

    final private static String LOG_TAG = "GigyaMessagingService";

    public static final int PUSH_TFA_CONTENT_ACTION_REQUEST_CODE = 2020;
    public static final int PUSH_TFA_CONTENT_INTENT_REQUEST_CODE = 2021;

    public static final int PUSH_TFA_NOTIFICATION_ID = 99990;

    @Override
    public void onCreate() {
        // Safe to call here. Once notification channel is created it won't be recreated again.
        createTFANotificationChannel();
    }

    private static final String CHANNEL_ID = "tfa_channel";

    private void createTFANotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            CharSequence name = getString(R.string.tfa_channel_name);
            String description = getString(R.string.tfa_channel_description);
            int importance = NotificationManager.IMPORTANCE_HIGH;
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, name, importance);
            channel.setDescription(description);
            NotificationManager notificationManager = getSystemService(NotificationManager.class);
            notificationManager.createNotificationChannel(channel);
        }
    }

    @Override
    public void onMessageReceived(RemoteMessage remoteMessage) {
        GigyaLogger.debug(LOG_TAG, "RemoteMessage: " + remoteMessage.toString());

        if (remoteMessage.getData().size() > 0) {
            // Check data purpose and continue flow accordingly.
            if (isCancelMessage()) {
                cancel();
            } else {
                notifyWith(remoteMessage.getData());
            }
        }
    }

    @Override
    public void onNewToken(String fcmToken) {
        GigyaLogger.debug(LOG_TAG, "onNewToken: " + fcmToken);
    }

    private boolean isCancelMessage() {
        return false;
    }

    private void notifyWith(Map<String, String> data) {
        // Fetch the data.
        final String title = "My notification";
        final String content = "Hello World!";

        // Content activity pending intent.
        Intent intent = new Intent(this, getCustomActionActivity());
        // We don't want the annoying enter animation.
        intent.addFlags(FLAG_ACTIVITY_NO_ANIMATION | Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, PUSH_TFA_CONTENT_INTENT_REQUEST_CODE,
                intent, PendingIntent.FLAG_ONE_SHOT);

        // Deny action.
        Intent denyIntent = new Intent(this, PushTFAReceiver.class);
        denyIntent.setAction(getString(R.string.tfa_action_deny));
        denyIntent.putExtra("notificationId", PUSH_TFA_NOTIFICATION_ID);
        PendingIntent denyPendingIntent =
                PendingIntent.getBroadcast(this, PUSH_TFA_CONTENT_ACTION_REQUEST_CODE, denyIntent, 0);

        // Approve action.
        Intent approveIntent = new Intent(this, PushTFAReceiver.class);
        approveIntent.setAction(getString(R.string.tfa_action_approve));
        approveIntent.putExtra("notificationId", PUSH_TFA_NOTIFICATION_ID);
        PendingIntent approvePendingIntent =
                PendingIntent.getBroadcast(this, PUSH_TFA_CONTENT_ACTION_REQUEST_CODE, approveIntent, 0);

        // Build notification.
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(getSmallIcon())
                .setContentTitle(title)
                .setContentText(content)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .addAction(getDenyActionIcon(), getString(R.string.deny),
                        denyPendingIntent)
                .addAction(getApproveActionIcon(), getString(R.string.approve),
                        approvePendingIntent);

        // Notify.
        NotificationManagerCompat notificationManager = NotificationManagerCompat.from(this);
        notificationManager.notify(PUSH_TFA_NOTIFICATION_ID, builder.build());
    }

    /**
     * Attempt to cancel a displayed notification given a unique identification.
     */
    private void cancel() {
        NotificationManagerCompat notificationManager = NotificationManagerCompat.from(this);
        notificationManager.cancel(PUSH_TFA_NOTIFICATION_ID);
    }

    //region CUSTOMIZATION OPTIONS

    /**
     * Optional override
     * Define the notification small icon.
     *
     * @return Icon reference.
     */
    protected int getSmallIcon() {
        return 0;
    }

    /**
     * Optional override.
     * Define the notification approve action icon.
     *
     * @return Icon reference.
     */
    protected int getApproveActionIcon() {
        return 0;
    }

    /**
     * Optional override.
     * Define the notification deny action icon.
     *
     * @return Icon reference.
     */
    protected int getDenyActionIcon() {
        return 0;
    }

    /**
     * Optional override.
     * Allows to define the activity class used by the the notification's content intent.
     * default class GigyaPushTfaActivity.class.
     *
     * @return Activity class reference.
     */
    public Class getCustomActionActivity() {
        return PushTFAActivity.class;
    }

    //endregion
}
