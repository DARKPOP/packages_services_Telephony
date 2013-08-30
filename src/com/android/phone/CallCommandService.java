/*
 * Copyright (C) 2013 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License
 */

package com.android.phone;

import android.bluetooth.IBluetoothHeadsetPhone;
import android.content.Context;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.util.Log;

import com.android.internal.telephony.CallManager;
import com.android.internal.telephony.PhoneConstants;
import com.android.phone.CallModeler.CallResult;
import com.android.services.telephony.common.AudioMode;
import com.android.services.telephony.common.Call;
import com.android.services.telephony.common.ICallCommandService;

/**
 * Service interface used by in-call ui to control phone calls using commands exposed as methods.
 * Instances of this class are handed to in-call UI via CallMonitorService.
 */
class CallCommandService extends ICallCommandService.Stub {
    private static final String TAG = CallCommandService.class.getSimpleName();
    private static final boolean DBG =
            (PhoneGlobals.DBG_LEVEL >= 1) && (SystemProperties.getInt("ro.debuggable", 0) == 1);

    private final Context mContext;
    private final CallManager mCallManager;
    private final CallModeler mCallModeler;
    private final DTMFTonePlayer mDtmfTonePlayer;
    private final AudioRouter mAudioRouter;
    private final RejectWithTextMessageManager mRejectWithTextMessageManager;

    public CallCommandService(Context context, CallManager callManager, CallModeler callModeler,
            DTMFTonePlayer dtmfTonePlayer, AudioRouter audioRouter,
            RejectWithTextMessageManager rejectWithTextMessageManager) {
        mContext = context;
        mCallManager = callManager;
        mCallModeler = callModeler;
        mDtmfTonePlayer = dtmfTonePlayer;
        mAudioRouter = audioRouter;
        mRejectWithTextMessageManager = rejectWithTextMessageManager;
    }

    /**
     * TODO(klp): Add a confirmation callback parameter.
     */
    @Override
    public void answerCall(int callId) {
        try {
            CallResult result = mCallModeler.getCallWithId(callId);
            if (result != null) {
                PhoneUtils.answerCall(result.getConnection().getCall());
            }
        } catch (Exception e) {
            Log.e(TAG, "Error during answerCall().", e);
        }
    }

    /**
     * TODO(klp): Add a confirmation callback parameter.
     */
    @Override
    public void rejectCall(int callId, boolean rejectWithMessage, String message) {
        try {
            CallResult result = mCallModeler.getCallWithId(callId);
            if (result != null) {
                final String number = result.getConnection().getAddress();
                Log.v(TAG, "Hanging up");
                PhoneUtils.hangupRingingCall(result.getConnection().getCall());
                if (rejectWithMessage) {
                    if (message != null) {
                        mRejectWithTextMessageManager.rejectCallWithMessage(number, message);
                    } else {
                        mRejectWithTextMessageManager.rejectCallWithNewMessage(number);
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error during rejectCall().", e);
        }
    }

    @Override
    public void disconnectCall(int callId) {
        try {
            CallResult result = mCallModeler.getCallWithId(callId);
            if (DBG) Log.d(TAG, "disconnectCall " + result.getCall());

            if (result != null) {
                int state = result.getCall().getState();
                if (Call.State.ACTIVE == state ||
                        Call.State.ONHOLD == state ||
                        Call.State.DIALING == state ||
                        Call.State.CONFERENCED == state) {
                    result.getConnection().getCall().hangup();
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error during disconnectCall().", e);
        }
    }

    @Override
    public void hold(int callId, boolean hold) {
        try {
            CallResult result = mCallModeler.getCallWithId(callId);
            if (result != null) {
                int state = result.getCall().getState();
                if (hold && Call.State.ACTIVE == state) {
                    PhoneUtils.switchHoldingAndActive(mCallManager.getFirstActiveBgCall());
                } else if (!hold && Call.State.ONHOLD == state) {
                    PhoneUtils.switchHoldingAndActive(result.getConnection().getCall());
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error trying to place call on hold.", e);
        }
    }

    @Override
    public void merge() {
        if (PhoneUtils.okToMergeCalls(mCallManager)) {
            PhoneUtils.mergeCalls(mCallManager);
        }
    }

    @Override
    public void addCall() {
        // start new call checks okToAddCall() already
        PhoneUtils.startNewCall(mCallManager);
    }


    @Override
    public void swap() {
        if (!PhoneUtils.okToSwapCalls(mCallManager)) {
            // TODO: throw an error instead?
            return;
        }

        // Swap the fg and bg calls.
        // In the future we may provides some way for user to choose among
        // multiple background calls, for now, always act on the first background calll.
        PhoneUtils.switchHoldingAndActive(mCallManager.getFirstActiveBgCall());

        final PhoneGlobals mApp = PhoneGlobals.getInstance();

        // If we have a valid BluetoothPhoneService then since CDMA network or
        // Telephony FW does not send us information on which caller got swapped
        // we need to update the second call active state in BluetoothPhoneService internally
        if (mCallManager.getBgPhone().getPhoneType() == PhoneConstants.PHONE_TYPE_CDMA) {
            final IBluetoothHeadsetPhone btPhone = mApp.getBluetoothPhoneService();
            if (btPhone != null) {
                try {
                    btPhone.cdmaSwapSecondCallState();
                } catch (RemoteException e) {
                    Log.e(TAG, Log.getStackTraceString(new Throwable()));
                }
            }
        }
    }

    @Override
    public void mute(boolean onOff) {
        try {
            PhoneUtils.setMute(onOff);
        } catch (Exception e) {
            Log.e(TAG, "Error during mute().", e);
        }
    }

    @Override
    public void speaker(boolean onOff) {
        try {
            // TODO(klp): add bluetooth logic from InCallScreen.toggleSpeaker()
            PhoneUtils.turnOnSpeaker(mContext, onOff, true);
        } catch (Exception e) {
            Log.e(TAG, "Error during speaker().", e);
        }
    }

    @Override
    public void playDtmfTone(char digit, boolean timedShortTone) {
        try {
            mDtmfTonePlayer.playDtmfTone(digit, timedShortTone);
        } catch (Exception e) {
            Log.e(TAG, "Error playing DTMF tone.", e);
        }
    }

    @Override
    public void stopDtmfTone() {
        try {
            mDtmfTonePlayer.stopDtmfTone();
        } catch (Exception e) {
            Log.e(TAG, "Error stopping DTMF tone.", e);
        }
    }

    @Override
    public void setAudioMode(int mode) {
        try {
            mAudioRouter.setAudioMode(mode);
        } catch (Exception e) {
            Log.e(TAG, "Error setting the audio mode.", e);
        }
    }
}
