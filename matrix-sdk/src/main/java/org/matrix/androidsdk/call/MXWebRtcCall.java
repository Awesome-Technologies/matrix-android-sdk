/*
 * Copyright 2015 OpenMarket Ltd
 * Copyright 2017 Vector Creations Ltd
 * Copyright 2018 New Vector Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.matrix.androidsdk.call;

import android.content.Context;
import android.os.Build;
import android.text.TextUtils;
import android.view.View;
import android.widget.RelativeLayout;

import androidx.core.content.ContextCompat;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import org.matrix.androidsdk.MXSession;
import org.matrix.androidsdk.core.Log;
import org.matrix.androidsdk.rest.model.Event;
import org.webrtc.Camera1Enumerator;
import org.webrtc.Camera2Enumerator;
import org.webrtc.CameraEnumerator;
import org.webrtc.IceCandidate;
import org.webrtc.PeerConnection;
import org.webrtc.SessionDescription;

import java.util.ArrayList;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

import javax.annotation.Nullable;

public class MXWebRtcCall extends MXCall {
    private static final String LOG_TAG = MXWebRtcCall.class.getSimpleName();

    private MXWebRtcClient mWebRtcClient;
    private RelativeLayout mCallView = null;

    // default value
    private String mCallState = CALL_STATE_CREATED;

    // ICE candidate management
    private boolean mIsIncomingPrepared = false;
    private JsonArray mPendingCandidates = new JsonArray();

    private JsonObject mCallInviteParams = null;

    private boolean mIsAnswered = false;

    /**
     * @return true if this stack can perform calls.
     */
    public static boolean isSupported() {
        return Build.VERSION.SDK_INT > 21;
    }

    /**
     * Tells if the camera2 Api is supported
     *
     * @param context the context
     * @return true if the Camera2 API is supported
     */
    private static boolean useCamera2(Context context) {
        return Camera2Enumerator.isSupported(context);
    }

    /**
     * Get a camera enumerator
     *
     * @param context the context
     * @return the camera enumerator
     */
    private static CameraEnumerator getCameraEnumerator(Context context) {
        if (useCamera2(context)) {
            return new Camera2Enumerator(context);
        } else {
            return new Camera1Enumerator(false);
        }
    }

    /**
     * Constructor
     *
     * @param session    the session
     * @param context    the context
     * @param turnServer the turn server
     */
    MXWebRtcCall(MXSession session, Context context, JsonElement turnServer, String defaultIceServerUri) {
        if (!isSupported()) {
            throw new AssertionError("MXWebRtcCall : not supported with the current android version");
        }

        if (null == session) {
            throw new AssertionError("MXWebRtcCall : session cannot be null");
        }

        if (null == context) {
            throw new AssertionError("MXWebRtcCall : context cannot be null");
        }

        // privacy
        Log.d(LOG_TAG, "constructor " + turnServer.getAsJsonObject().get("uris"));

        mCallId = "c" + System.currentTimeMillis();
        mSession = session;
        mContext = context;
        mTurnServer = turnServer;
        if (!TextUtils.isEmpty(defaultIceServerUri)) {
            try {
                if (!defaultIceServerUri.startsWith("stun:")) {
                    defaultIceServerUri = "stun:" + defaultIceServerUri;
                }
                defaultIceServer = PeerConnection.IceServer.builder(defaultIceServerUri).createIceServer();
            } catch (Throwable e) {
                Log.e(LOG_TAG, "MXWebRtcCall constructor  invalid default stun" + defaultIceServerUri);
            }
        }
    }

    /**
     * Create the callviews
     */
    @Override
    public void createCallView() {
        super.createCallView();

        Log.d(LOG_TAG, "createCallView()");

        dispatchOnStateDidChange(CALL_STATE_CREATING_CALL_VIEW);
        mUIThreadHandler.postDelayed(() -> {
            mCallView = new RelativeLayout(mContext);
            mCallView.setLayoutParams(new RelativeLayout.LayoutParams(RelativeLayout.LayoutParams.MATCH_PARENT,
                    RelativeLayout.LayoutParams.MATCH_PARENT));
            mCallView.setBackgroundColor(ContextCompat.getColor(mContext, android.R.color.black));
            mCallView.setVisibility(View.GONE);

            dispatchOnCallViewCreated(mCallView);
            dispatchOnStateDidChange(CALL_STATE_READY);
            dispatchOnReady();
        }, 10);
    }

    @Override
    public void updateLocalVideoRendererPosition(VideoLayoutConfiguration configuration) {
        super.updateLocalVideoRendererPosition(configuration);
        if (null != mWebRtcClient) {
            mWebRtcClient.updateWebRtcViewLayout(configuration);
        }
    }

    @Override
    public boolean isSwitchCameraSupported() {
        String[] deviceNames = getCameraEnumerator(mContext).getDeviceNames();
        return (null != deviceNames) && (0 != deviceNames.length);
    }

    @Override
    public boolean switchRearFrontCamera() {
        return mWebRtcClient.switchRearFrontCamera();
    }

    @Override
    public void muteVideoRecording(boolean muteValue) {
        mWebRtcClient.muteVideoRecording(muteValue);
    }

    @Override
    public boolean isVideoRecordingMuted() {
        if (null != mWebRtcClient) {
            return mWebRtcClient.isVideoRecordingMuted();
        } else {
            return true;
        }
    }

    @Override
    public boolean isCameraSwitched() {
        if (null != mWebRtcClient) {
            return mWebRtcClient.isCameraSwitched();
        } else {
            return false;
        }
    }

    @Override
    public List<PeerConnection.IceServer> getIceServers() {
        // build ICE servers list
        List<PeerConnection.IceServer> iceServers = super.getIceServers();
        if (iceServers.isEmpty() && defaultIceServer != null) {
            iceServers.add(defaultIceServer);
        }
        return iceServers;
    }

    /**
     * Initialize the call, create a MXWebRtcClient and setup UI
     *
     * @param callInviteParams    the invite params, if the call is incoming
     * @param aLocalVideoPosition position of the local video attendee
     */
    private void initCall(final JsonObject callInviteParams, VideoLayoutConfiguration aLocalVideoPosition) {
        Log.d(LOG_TAG, "initCall(): IN");

        if (isCallEnded()) {
            Log.w(LOG_TAG, "initCall(): skipped due to call is ended");
            return;
        }

        dispatchOnStateDidChange(CALL_STATE_WAIT_LOCAL_MEDIA);
        try {
            mWebRtcClient = new MXWebRtcClient(mSession, mContext, this);
            if (isVideo()) {
                mWebRtcClient.initVideoCallUI(mCallView, aLocalVideoPosition);
            }
            dispatchOnStateDidChange(IMXCall.CALL_STATE_RINGING);

            // set remote description if we received some invite parameters for an incoming call
            if (null != callInviteParams && callInviteParams.has("offer")) {
                try {
                    JsonObject answer = callInviteParams.getAsJsonObject("offer");
                    String type = answer.get("type").getAsString();
                    String sdp = answer.get("sdp").getAsString();

                    if (!TextUtils.isEmpty(type) && !TextUtils.isEmpty(sdp)) {
                        SessionDescription description = new SessionDescription(SessionDescription.Type.OFFER, sdp);
                        mWebRtcClient.setRemoteDescription(description);
                    }
                } catch (Exception e) {
                    Log.e(LOG_TAG, "initCall, malformed invite message: " + e.getMessage(), e);
                }
            }
        } catch (Exception e) {
            Log.e(LOG_TAG, "initCall(): MXWebRtcClient: Exception Msg = " + e.getMessage(), e);
        }
        Log.d(LOG_TAG, "initCall(): OUT");
    }

    /**
     * The activity is paused.
     */
    @Override
    public void onPause() {
        super.onPause();

        Log.d(LOG_TAG, "onPause");

        try {
            if (!isCallEnded() && null != mWebRtcClient) {
                mWebRtcClient.onPause();
            }
        } catch (Exception e) {
            Log.e(LOG_TAG, "onPause failed " + e.getMessage(), e);
        }
    }

    /**
     * The activity is resumed.
     */
    @Override
    public void onResume() {
        super.onResume();

        Log.d(LOG_TAG, "onResume");

        if (!isCallEnded() && null != mWebRtcClient) {
            mWebRtcClient.onResume();
        }
    }

    /**
     * Start an outgoing call.
     */
    @Override
    public void placeCall(VideoLayoutConfiguration aLocalVideoPosition) {
        Log.d(LOG_TAG, "placeCall");
        super.placeCall(aLocalVideoPosition);
        initCall(null, aLocalVideoPosition);
    }

    /**
     * Prepare a call reception.
     *
     * @param aCallInviteParams the invitation Event content
     * @param aCallId           the call ID
     */
    @Override
    public void prepareIncomingCall(final JsonObject aCallInviteParams, final String aCallId, final VideoLayoutConfiguration aLocalVideoPosition) {
        Log.d(LOG_TAG, "prepareIncomingCall() while " + getCallState());
        super.prepareIncomingCall(aCallInviteParams, aCallId, aLocalVideoPosition);
        mCallId = aCallId;

        if (CALL_STATE_READY.equals(getCallState())) {
            mIsIncoming = true;
            initCall(aCallInviteParams, aLocalVideoPosition);
        } else if (CALL_STATE_CREATED.equals(getCallState())) {
            mCallInviteParams = aCallInviteParams;

            // detect call type from the sdp
            try {
                JsonObject offer = mCallInviteParams.get("offer").getAsJsonObject();
                JsonElement sdp = offer.get("sdp");
                String sdpValue = sdp.getAsString();
                setIsVideo(sdpValue.contains("m=video"));
            } catch (Exception e) {
                Log.e(LOG_TAG, "prepareIncomingCall(): Exception Msg=" + e.getMessage(), e);
            }
        }
    }

    /**
     * The call has been detected as an incoming one.
     * The application launches the dedicated activity and expects to launch the incoming call.
     * The local video attendee is displayed in the screen according to the values given in aLocalVideoPosition.
     *
     * @param aLocalVideoPosition local video position
     */
    @Override
    public void launchIncomingCall(VideoLayoutConfiguration aLocalVideoPosition) {
        Log.d(LOG_TAG, "launchIncomingCall() while " + getCallState());

        super.launchIncomingCall(aLocalVideoPosition);
        if (CALL_STATE_READY.equals(getCallState())) {
            prepareIncomingCall(mCallInviteParams, mCallId, aLocalVideoPosition);
        }
    }

    /**
     * The callee accepts the call.
     *
     * @param event the event
     */
    private void onCallAnswer(final Event event) {
        Log.d(LOG_TAG, "onCallAnswer : call state " + getCallState());

        if (!CALL_STATE_CREATED.equals(getCallState()) && (null != mWebRtcClient)) {
            dispatchOnStateDidChange(IMXCall.CALL_STATE_CONNECTING);

            // extract the description
            try {
                JsonObject eventContent = event.getContentAsJsonObject();
                JsonObject answer = eventContent.getAsJsonObject("answer");
                String type = answer.get("type").getAsString();
                String sdp = answer.get("sdp").getAsString();

                if (!TextUtils.isEmpty(type) && !TextUtils.isEmpty(sdp) && type.equals("answer")) {
                    SessionDescription description = new SessionDescription(SessionDescription.Type.ANSWER, sdp);
                    mWebRtcClient.setRemoteDescription(description);
                }
            } catch (Exception e) {
                Log.e(LOG_TAG, "onCallAnswer: malformed event: " + e.getMessage(), e);
            }
        }
    }

    /**
     * Handle hangup signalled by matrix.
     */
    private void onCallHangup() {
        Log.d(LOG_TAG, "onCallHangup() while " + getCallState());
        String state = getCallState();

        if (!CALL_STATE_ENDED.equals(state)) {
            dispatchOnStateDidChange(CALL_STATE_ENDED);
        }

        if (null != mWebRtcClient) {
            mWebRtcClient.close();
            // TODO callback to delete mWebRtcClient when it is really closed
            mWebRtcClient = null;
        }
    }

    /**
     * Called by the peer connection when the remote SDP description is successfully set
     */
    void onIncomingPrepared() {
        mIsIncomingPrepared = true;

        Log.d(LOG_TAG, "checkPendingCandidates");
        /* Some Ice candidates could have been received while creating the call view.
           Check if some of them have been defined. */
        // TODO run on UI thread?
        synchronized (LOG_TAG) {
            onNewCandidates(mPendingCandidates);
            mPendingCandidates = new JsonArray();
        }
    }

    /**
     * Manage the call events.
     *
     * @param event the call event.
     */
    @Override
    public void handleCallEvent(Event event) {
        super.handleCallEvent(event);

        if (event.isCallEvent()) {
            String eventType = event.getType();

            Log.d(LOG_TAG, "handleCallEvent " + eventType);

            // event from other member
            if (!TextUtils.equals(event.getSender(), mSession.getMyUserId())) {
                if (Event.EVENT_TYPE_CALL_ANSWER.equals(eventType) && !mIsIncoming) {
                    onCallAnswer(event);
                } else if (Event.EVENT_TYPE_CALL_CANDIDATES.equals(eventType)) {
                    JsonObject eventContent = event.getContentAsJsonObject();

                    if (eventContent != null) {
                        JsonArray candidates = eventContent.getAsJsonArray("candidates");
                        addCandidates(candidates);
                    }
                } else if (Event.EVENT_TYPE_CALL_HANGUP.equals(eventType)) {
                    onCallHangup(/* IMXCall.END_CALL_REASON_PEER_HANG_UP */);
                }

            } else { // event from the current member, but sent from another device
                // FIXME: also receives events from the same device!
                switch (eventType) {
                    case Event.EVENT_TYPE_CALL_INVITE:
                        // warn in the UI thread
                        dispatchOnStateDidChange(CALL_STATE_RINGING);
                        break;

                    case Event.EVENT_TYPE_CALL_ANSWER:
                        // call answered from another device
                        if (!CALL_STATE_CONNECTED.equals((getCallState()))) {
                            onAnsweredElsewhere();
                        }
                        break;

                    case Event.EVENT_TYPE_CALL_HANGUP:
                        // current member answered elsewhere
                        onCallHangup(/* IMXCall.END_CALL_REASON_PEER_HANG_UP_ELSEWHERE */);
                        break;

                    default:
                        break;
                } // switch end
            }
        }
    }

    /**
     * Add ice candidates
     *
     * @param candidates ic candidates
     */
    private void addCandidates(JsonArray candidates) {
        if (mIsIncomingPrepared || !isIncoming()) {
            Log.d(LOG_TAG, "addCandidates : ready");
            onNewCandidates(candidates);
        } else {
            synchronized (LOG_TAG) {
                Log.d(LOG_TAG, "addCandidates : pending");
                mPendingCandidates.addAll(candidates);
            }
        }
    }

    /**
     * A new Ice candidate is received
     *
     * @param candidates the channel candidates
     */
    private void onNewCandidates(final JsonArray candidates) {
        Log.d(LOG_TAG, "onNewCandidates(): call state " + getCallState() + " with candidates " + candidates);

        if (!CALL_STATE_CREATED.equals(getCallState()) && (null != mWebRtcClient)) {
            List<IceCandidate> candidatesList = new ArrayList<>();

            // convert the JSON to IceCandidate
            for (int index = 0; index < candidates.size(); index++) {
                JsonObject item = candidates.get(index).getAsJsonObject();
                try {
                    String candidate = item.get("candidate").getAsString();
                    String sdpMid = item.get("sdpMid").getAsString();
                    int sdpLineIndex = item.get("sdpMLineIndex").getAsInt();

                    candidatesList.add(new IceCandidate(sdpMid, sdpLineIndex, candidate));
                } catch (Exception e) {
                    Log.e(LOG_TAG, "onNewCandidates(): Exception Msg=" + e.getMessage(), e);
                }
            }

            mWebRtcClient.addIceCandidates(candidatesList);
        }
    }

    void sendNewCandidate(final IceCandidate iceCandidate) {
        Log.d(LOG_TAG, "sendNewCandidate()");
        if (!isCallEnded()) {
            JsonObject content = new JsonObject();
            content.addProperty("version", 0);
            content.addProperty("call_id", mCallId);

            JsonArray candidates = new JsonArray();
            JsonObject cand = new JsonObject();
            cand.addProperty("sdpMLineIndex", iceCandidate.sdpMLineIndex);
            cand.addProperty("sdpMid", iceCandidate.sdpMid);
            cand.addProperty("candidate", iceCandidate.sdp);
            candidates.add(cand);
            content.add("candidates", candidates);

            // merge candidates
            mUIThreadHandler.post(() -> {
                boolean addIt = true;
                if (mPendingEvents.size() > 0) {
                    try {
                        Event lastEvent = mPendingEvents.get(mPendingEvents.size() - 1);

                        if (TextUtils.equals(lastEvent.getType(), Event.EVENT_TYPE_CALL_CANDIDATES)) {
                            // return the content cast as a JsonObject
                            // it is not a copy
                            JsonObject lastContent = lastEvent.getContentAsJsonObject();

                            JsonArray lastContentCandidates = lastContent.get("candidates").getAsJsonArray();
                            JsonArray newContentCandidates = content.get("candidates").getAsJsonArray();

                            Log.d(LOG_TAG, "Merge candidates from " + lastContentCandidates.size()
                                    + " to " + (lastContentCandidates.size() + newContentCandidates.size() + " items."));

                            lastContentCandidates.addAll(newContentCandidates);

                            // replace the candidates list
                            lastContent.remove("candidates");
                            lastContent.add("candidates", lastContentCandidates);

                            // don't need to save anything, lastContent is a reference not a copy
                            addIt = false;
                        }
                    } catch (Exception e) {
                        Log.e(LOG_TAG, "createLocalStream(): createPeerConnection - onIceCandidate() Exception Msg="
                                + e.getMessage(), e);
                    }
                }

                if (addIt) {
                    Event event = new Event(Event.EVENT_TYPE_CALL_CANDIDATES, content, mSession.getCredentials().userId,
                            mCallSignalingRoom.getRoomId());

                    mPendingEvents.add(event);
                    sendNextEvent();
                }
            });
        }
    }

    // user actions

    /**
     * The call is accepted.
     */
    @Override
    public void answer() {
        super.answer();
        Log.d(LOG_TAG, "answer() while " + getCallState());
/*
        if (!CALL_STATE_CREATED.equals(getCallState())) {
            if (null != mWebRtcClient) {
                mWebRtcClient.createAnswer(isVideo());
            } else {
                Log.e(LOG_TAG, "answer(): mWebRtcClient is null");
            }
        }
 */
    }

    /**
     * The call is hung up.
     *
     * @param reason the reason, or null for no reason. Reasons are used to indicate errors in the current VoIP implementation.
     */
    @Override
    public void hangup(@Nullable String reason) {
        super.hangup(reason);

        Log.d(LOG_TAG, "hangup(): reason=" + reason);

        if (!isCallEnded()) {
            if (null != mWebRtcClient) {
                mWebRtcClient.hangup();
            }
            sendHangup(reason);
        }
    }

    /**
     * @return the callstate (must be a CALL_STATE_XX value)
     */
    @Override
    public String getCallState() {
        return mCallState;
    }

    /**
     * @return the callView
     */
    @Override
    public View getCallView() {
        return mCallView;
    }

    /**
     * @return the callView visibility
     */
    @Override
    public int getVisibility() {
        if (null != mCallView) {
            return mCallView.getVisibility();
        } else {
            return View.GONE;
        }
    }

    /**
     * Set the callview visibility
     *
     * @return true if the operation succeeds
     */
    @Override
    public boolean setVisibility(int visibility) {
        if (null != mCallView) {
            mCallView.setVisibility(visibility);
            return true;
        }

        return false;
    }

    /**
     * The call has been answered on another device.
     * We distinguish the case where an account is active on
     * multiple devices and a video call is launched on the account. In this case
     * the callee who did not answer must display a "answered elsewhere" message.
     */
    @Override
    public void onAnsweredElsewhere() {
        super.onAnsweredElsewhere();

        String state = getCallState();

        Log.d(LOG_TAG, "onAnsweredElsewhere in state " + state);

        if (!isCallEnded() && !mIsAnswered) {
            dispatchAnsweredElsewhere();
            if (null != mWebRtcClient) {
                mWebRtcClient.close();
                mWebRtcClient = null;
            }
            dispatchOnStateDidChange(CALL_STATE_ENDED);
            dispatchOnCallEnd(END_CALL_REASON_PEER_HANG_UP_ELSEWHERE);
        }
    }

    @Override
    protected void dispatchOnStateDidChange(String newState) {
        Log.d(LOG_TAG, "dispatchOnStateDidChange " + newState);

        mCallState = newState;

        // call timeout management
        if (CALL_STATE_CONNECTING.equals(mCallState) || CALL_STATE_CONNECTED.equals(mCallState)) {
            if (null != mCallTimeoutTimer) {
                mCallTimeoutTimer.cancel();
                mCallTimeoutTimer = null;
            }
        }

        super.dispatchOnStateDidChange(newState);
    }

    /**
     * Send the invite event
     *
     * @param sessionDescription the session description.
     */
    void sendInvite(final SessionDescription sessionDescription) {
        // check if the call has not been killed
        if (isCallEnded()) {
            Log.d(LOG_TAG, "sendInvite(): isCallEnded");
            return;
        }

        Log.d(LOG_TAG, "sendInvite()");

        // build the invitation event
        JsonObject inviteContent = new JsonObject();
        inviteContent.addProperty("version", 0);
        inviteContent.addProperty("call_id", mCallId);
        inviteContent.addProperty("lifetime", CALL_TIMEOUT_MS);

        JsonObject offerContent = new JsonObject();
        offerContent.addProperty("sdp", sessionDescription.description);
        offerContent.addProperty("type", sessionDescription.type.canonicalForm());
        inviteContent.add("offer", offerContent);

        Event event = new Event(Event.EVENT_TYPE_CALL_INVITE, inviteContent, mSession.getCredentials().userId, mCallSignalingRoom.getRoomId());

        mPendingEvents.add(event);

        try {
            mCallTimeoutTimer = new Timer();
            mCallTimeoutTimer.schedule(new TimerTask() {
                @Override
                public void run() {
                    try {
                        if (getCallState().equals(IMXCall.CALL_STATE_RINGING) || getCallState().equals(IMXCall.CALL_STATE_INVITE_SENT)) {
                            Log.d(LOG_TAG, "sendInvite : CALL_ERROR_USER_NOT_RESPONDING");
                            dispatchOnCallError(CALL_ERROR_USER_NOT_RESPONDING);
                            hangup(null);
                        }

                        // cancel the timer
                        mCallTimeoutTimer.cancel();
                        mCallTimeoutTimer = null;
                    } catch (Exception e) {
                        Log.e(LOG_TAG, "sendInvite(): Exception Msg= " + e.getMessage(), e);
                    }
                }
            }, CALL_TIMEOUT_MS);
        } catch (Throwable throwable) {
            Log.e(LOG_TAG, "sendInvite(): failed " + throwable.getMessage(), throwable);
            if (null != mCallTimeoutTimer) {
                mCallTimeoutTimer.cancel();
                mCallTimeoutTimer = null;
            }
        }

        sendNextEvent();
    }

    /**
     * Send the answer event
     *
     * @param sessionDescription the session description
     */
    void sendAnswer(final SessionDescription sessionDescription) {
        // check if the call has not been killed
        if (isCallEnded()) {
            Log.d(LOG_TAG, "sendAnswer isCallEnded");
            return;
        }

        Log.d(LOG_TAG, "sendAnswer");

        // build the invitation event
        JsonObject answerContent = new JsonObject();
        answerContent.addProperty("version", 0);
        answerContent.addProperty("call_id", mCallId);
        answerContent.addProperty("lifetime", CALL_TIMEOUT_MS);

        JsonObject offerContent = new JsonObject();
        offerContent.addProperty("sdp", sessionDescription.description);
        offerContent.addProperty("type", sessionDescription.type.canonicalForm());
        answerContent.add("answer", offerContent);

        Event event = new Event(Event.EVENT_TYPE_CALL_ANSWER, answerContent, mSession.getCredentials().userId, mCallSignalingRoom.getRoomId());
        mPendingEvents.add(event);
        sendNextEvent();

        mIsAnswered = true;
    }
}
