package org.matrix.matrixandroidsdk.services;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.IBinder;
import android.support.v4.app.NotificationCompat;
import android.support.v4.app.TaskStackBuilder;
import android.util.Log;
import android.widget.Toast;

import org.matrix.androidsdk.MXSession;
import org.matrix.androidsdk.data.Room;
import org.matrix.androidsdk.data.RoomState;
import org.matrix.androidsdk.listeners.MXEventListener;
import org.matrix.androidsdk.rest.model.Event;
import org.matrix.matrixandroidsdk.ConsoleApplication;
import org.matrix.matrixandroidsdk.ViewedRoomTracker;
import org.matrix.matrixandroidsdk.activity.HomeActivity;
import org.matrix.matrixandroidsdk.Matrix;
import org.matrix.matrixandroidsdk.R;
import org.matrix.matrixandroidsdk.activity.PublicRoomsActivity;
import org.matrix.matrixandroidsdk.activity.RoomActivity;
import org.matrix.matrixandroidsdk.util.EventUtils;

import java.util.ArrayList;

/**
 * A foreground service in charge of controlling whether the event stream is running or not.
 */
public class EventStreamService extends Service {
    public static enum StreamAction {
        UNKNOWN,
        STOP,
        START,
        PAUSE,
        RESUME
    }
    public static final String EXTRA_STREAM_ACTION = "org.matrix.matrixandroidsdk.services.EventStreamService.EXTRA_STREAM_ACTION";

    private static final String LOG_TAG = "EventStreamService";
    private static final int NOTIFICATION_ID = 42;
    private static final int MSG_NOTIFICATION_ID = 43;

    private MXSession mSession;
    private StreamAction mState = StreamAction.UNKNOWN;

    private static ArrayList<String> mUnnotifiedRooms = new ArrayList<String>();

    private AlertDialog mAlertDialog = null;

    private MXEventListener mListener = new MXEventListener() {

        @Override
        public void onBingRulesUpdate() {
            mUnnotifiedRooms = new ArrayList<String>();
        }

        @Override
        public void onBingEvent(Event event, RoomState roomState) {

            final String roomId = event.roomId;

            // Just don't bing for the room the user's currently in
            if ((roomId != null) && event.roomId.equals(ViewedRoomTracker.getInstance().getViewedRoomId())) {
                return;
            }

            String from = event.userId;
            // FIXME: Support event contents with no body
            if (!event.content.has("body")) {
                return;
            }

            final String body = event.content.getAsJsonPrimitive("body").getAsString();

            if (null != ConsoleApplication.getCurrentActivity()) {

                final Activity activity = ConsoleApplication.getCurrentActivity();

                // the user decided to ignore any notification from this room
                if (mUnnotifiedRooms.indexOf(roomId) >= 0) {
                    return;
                }

                activity.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        String title = "";

                        if (null != mSession.getDataHandler()) {
                            Room room = mSession.getDataHandler().getRoom(roomId);

                            if (null != room) {
                                title = room.getName(mSession.getCredentials().userId);
                            }
                        }

                        if (null != mAlertDialog) {
                            mAlertDialog.dismiss();;
                        }

                        // The user is trying to leave with unsaved changes. Warn about that
                        mAlertDialog = new AlertDialog.Builder(activity)
                                .setTitle(title)
                                .setMessage(body)
                                .setPositiveButton(getResources().getString(R.string.view), new DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(DialogInterface dialog, int which) {
                                        dialog.dismiss();

                                        activity.runOnUiThread(new
                                           Runnable() {
                                               @Override
                                               public void run () {
                                                   // if the activity is not the home activity
                                                   if (!(activity instanceof HomeActivity)) {
                                                       // pop to the home activity
                                                       Intent intent = new Intent(activity, HomeActivity.class);
                                                       intent.setFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP);
                                                       activity.startActivity(intent);

                                                       activity.runOnUiThread(new
                                                              Runnable() {
                                                                  @Override
                                                                  public void run () {
                                                                      // and open the room
                                                                      Activity homeActivity = ConsoleApplication.getCurrentActivity();
                                                                      Intent intent = new Intent(homeActivity, RoomActivity.class);
                                                                      intent.putExtra(RoomActivity.EXTRA_ROOM_ID, roomId);
                                                                      homeActivity.startActivity(intent);
                                                                  }
                                                              });
                                                   } else {
                                                       // already to the home activity
                                                       // so just need to open the room activity
                                                       Intent intent = new Intent(activity, RoomActivity.class);
                                                       intent.putExtra(RoomActivity.EXTRA_ROOM_ID, roomId);
                                                       activity.startActivity(intent);
                                                   }
                                               }
                                           }
                                        );
                                    }
                                })
                                .setNegativeButton(getResources().getString(R.string.cancel), new DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(DialogInterface dialog, int which) {
                                        dialog.dismiss();

                                        // the user will ignore any event until the application is debackgrounded
                                        if (mUnnotifiedRooms.indexOf(roomId) < 0) {
                                            mUnnotifiedRooms.add(roomId);
                                        }
                                    }
                                })
                                .create();

                        mAlertDialog .show();
                    }
                });
            } else {
                Notification n = buildMessageNotification(from, body, event.roomId);
                NotificationManager nm = (NotificationManager) EventStreamService.this.getSystemService(Context.NOTIFICATION_SERVICE);
                Log.w(LOG_TAG, "onMessageEvent >>>> " + event);
                nm.notify(MSG_NOTIFICATION_ID, n);
            }
//            }
        }

    };

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        StreamAction action = StreamAction.values()[intent.getIntExtra(EXTRA_STREAM_ACTION, StreamAction.UNKNOWN.ordinal())];
        Log.d(LOG_TAG, "onStartCommand >> "+action);
        switch (action) {
            case START:
            case RESUME:
                start();
                break;
            case STOP:
                stop();
                break;
            case PAUSE:
                pause();
                break;
            default:
                break;
        }

        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        stop();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void start() {
        if (mState == StreamAction.START) {
            Log.w(LOG_TAG, "Already started.");
            return;
        }
        else if (mState == StreamAction.PAUSE) {
            Log.i(LOG_TAG, "Resuming active stream.");
            resume();
            return;
        }
        if (mSession == null) {
            mSession = Matrix.getInstance(getApplicationContext()).getDefaultSession();
            if (mSession == null) {
                Log.e(LOG_TAG, "No valid MXSession.");
                return;
            }
        }

        mSession.getDataHandler().addListener(mListener);
        mSession.startEventStream();
        startWithNotification();
    }

    private void stop() {
        stopForeground(true);
        if (mSession != null) {
            mSession.stopEventStream();
            mSession.getDataHandler().removeListener(mListener);
        }
        mSession = null;
        mState = StreamAction.STOP;
    }

    private void pause() {
        stopForeground(true);
        if (mSession != null) {
            mSession.pauseEventStream();
        }
        mState = StreamAction.PAUSE;
    }

    private void resume() {
        if (mSession != null) {
            mSession.resumeEventStream();
        }
        startWithNotification();
    }

    private void startWithNotification() {
        Notification notification = buildNotification();
        startForeground(NOTIFICATION_ID, notification);
        mState = StreamAction.START;
    }

    private Notification buildMessageNotification(String from, String body, String roomId) {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this);
        builder.setWhen(System.currentTimeMillis());
        builder.setContentTitle(from + " (Matrix)");
        builder.setContentText(body);
        builder.setAutoCancel(true);
        builder.setSmallIcon(R.drawable.ic_menu_start_conversation);
        builder.setTicker(from + ":" + body);
        /*Uri uri= RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
        builder.setSound(uri);*/

        // Build the pending intent for when the notification is clicked
        Intent roomIntent = new Intent(this, RoomActivity.class);
        roomIntent.putExtra(RoomActivity.EXTRA_ROOM_ID, roomId);
        // Recreate the back stack
        TaskStackBuilder stackBuilder = TaskStackBuilder.create(this)
                .addParentStack(RoomActivity.class)
                .addNextIntent(roomIntent);

        builder.setContentIntent(stackBuilder.getPendingIntent(0, PendingIntent.FLAG_UPDATE_CURRENT));

        Notification n = builder.build();
        n.flags |= Notification.FLAG_SHOW_LIGHTS;
        n.defaults |= Notification.DEFAULT_LIGHTS;
        //n.defaults |= Notification.DEFAULT_VIBRATE;
        return n;
    }

    private Notification buildNotification() {
        Notification notification = new Notification(
                R.drawable.ic_menu_start_conversation,
                "Matrix",
                System.currentTimeMillis()
        );

        // go to the home screen if this is clicked.
        Intent i = new Intent(this, HomeActivity.class);

        i.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|
                Intent.FLAG_ACTIVITY_SINGLE_TOP);

        PendingIntent pi = PendingIntent.getActivity(this, 0, i, 0);

        notification.setLatestEventInfo(this, getString(R.string.app_name),
                "Listening for events",
                pi);
        notification.flags |= Notification.FLAG_NO_CLEAR;
        return notification;
    }

    public static void acceptAlertNotificationsFrom(String RoomId) {
        if (null != RoomId) {
            mUnnotifiedRooms.remove(RoomId);
        }
    }
}
