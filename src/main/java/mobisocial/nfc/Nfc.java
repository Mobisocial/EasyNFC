/*
 * Copyright (C) 2011 Stanford University MobiSocial Lab
 * http://mobisocial.stanford.edu
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
 * limitations under the License.
 */

package mobisocial.nfc;

import java.io.IOException;
import java.net.URL;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import mobisocial.ndefexchange.NdefExchangeContract;
import mobisocial.ndefexchange.NdefExchangeManager;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.nfc.NdefMessage;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.tech.Ndef;
import android.nfc.tech.NdefFormatable;
import android.os.Build;
import android.os.Parcelable;
import android.util.Log;
import android.widget.Toast;

/**
 * <p>This class acts as an abstraction layer for Android's Nfc stack.
 * The goals of this project are to:
 * <ul>
 * <li>Provide compatibility for Nfc across platforms.
 * <li>Degrade gracefully on platforms and devices lacking Nfc support.
 * <li>Extend the capabilities of Nfc by following Connection Handover Requests.
 * <li>Simplify the Nfc experience for developers.
 * </ul>
 * </p>
 * 
 * <p>
 * The Nfc class must be run from a foregrounded activity. It requires
 * a few lifecycle events be triggered during an activity's runtime:
 * </p>
 * <pre class="prettyprint">
 *
 * class MyActivity extends Activity {
 * 
 *   public void onCreate(Bundle savedInstanceState) {
 *     super.onCreate(savedInstanceState);
 *     mNfc = new Nfc(this);
 *     mNfc.onCreate(this);
 *   }
 * 
 *   public void onResume() {
 *     super.onResume();
 *     mNfc.onResume(this);
 *     // your activity's onResume code
 *   }
 *
 *  public void onPause() {
 *    super.onPause();
 *    mNfc.onPause(this);
 *    // your activity's onPause code
 *  }
 *  
 *  public void onNewIntent(Intent intent) {
 *    if (mNfc.onNewIntent(this, intent)) {
 *      return;
 *    }
 *    // your activity's onNewIntent code
 *  }
 * }
 * </pre>
 * <p>
 * Your application must hold the {@code android.permission.NFC}
 * permission to use this class. However, this class will degrade gracefully
 * on devices lacking Nfc capabilities.
 * </p>
 * <p>
 * The Nfc interface can be in one of three modes: {@link #MODE_WRITE}, for writing
 * to a passive NFC tag, and {@link #MODE_EXCHANGE}, in which the interface can
 * read data from passive tags and exchange data with another active Nfc device, or
 * {@link #MODE_PASSTHROUGH} which disables this interface.
 * <ul>
 *   <li>{@link #share(NdefMessage)} and similar, to share messages with other Nfc devices.
 *   <li>{@link #addNdefHandler(NdefHandler)}, for acting on received messages.
 *   <li>{@link #enableTagWriteMode(NdefMessage)}, to write to physical Nfc tags.
 * </ul>
 * </p>
 */
public class Nfc {
	private static final String TAG = "easynfc";

	private Activity mActivity;
	private NfcAdapter mNfcAdapter;
	private final IntentFilter[] mIntentFilters;
	private final String[][] mTechLists;
	private NdefMessage mForegroundMessage = null;
	private NdefMessage mWriteMessage = null;
	private final Map<Integer, Set<NdefHandler>> mNdefHandlers = new TreeMap<Integer, Set<NdefHandler>>();
	private boolean mHandoverEnabled = true;
	private OnTagWriteListener mOnTagWriteListener = null;
	private final ConnectionHandoverManager mNdefExchangeManager;
	
	private int mState = STATE_PAUSED;
	private int mInterfaceMode = MODE_EXCHANGE;
	
	private static final int STATE_PAUSED = 0;
	private static final int STATE_PAUSING = 1;
	private static final int STATE_RESUMING = 2;
	private static final int STATE_RESUMED = 3;

	/**
	 * A broadcasted intent used to set an NDEF message for use in a Connection
	 * Handover, for devices that do not have an active NFC radio.
	 */
	protected static final String ACTION_SET_NDEF = "mobisocial.intent.action.SET_NDEF";
	
	/**
	 * The action of an ordered broadcast intent for applications to handle a
	 * received NDEF messages. Such intents are broadcast from connection
	 * handover services. This library sets the result code to
	 * {@code Activity.RESULT_CANCELED}, indicating the foreground application has
	 * consumed the intent.
	 */
	protected static final String ACTION_HANDLE_NDEF = "mobisocial.intent.action.HANDLE_NDEF";
	
	/**
	 * Nfc interface mode in which Nfc interaction is disabled for this class.
	 */
	public static final int MODE_PASSTHROUGH = 0;
	
	/**
	 * Nfc interface mode for reading data from a passive tag
	 * or exchanging information with another active device. 
	 * See {@link #addNdefHandler(NdefHandler)} and
	 * {@link #share(NdefMessage)} for handling the actual data. 
	 */
	public static final int MODE_EXCHANGE = 1;
	
	/**
	 * Nfc interface mode for writing data to a passive tag.
	 */
	public static final int MODE_WRITE = 2;


	public Nfc(Activity activity, IntentFilter[] intentFilters, String[][] techLists) {
		mActivity = activity;
		mIntentFilters = intentFilters;
		mTechLists = techLists;

		if (Build.VERSION.SDK_INT >= NfcWrapper.SDK_NDEF_DEFINED  && PackageManager.PERMISSION_GRANTED !=
			mActivity.checkCallingOrSelfPermission("android.permission.NFC")) {

			throw new SecurityException("Application must hold android.permission.NFC to use libhotpotato.");
		}

		if (PackageManager.PERMISSION_GRANTED !=
			mActivity.checkCallingOrSelfPermission("android.permission.BLUETOOTH")) {

			Log.w(TAG, "No android.permission.BLUETOOTH permission; bluetooth handover not supported.");
		}

		if (PackageManager.PERMISSION_GRANTED !=
			mActivity.checkCallingOrSelfPermission("android.permission.INTERNET")) {

			Log.w(TAG, "No android.permission.INTERNET permission; internet handover not supported.");
		}

		if (NfcWrapper.getInstance() != null) {
			mNfcAdapter = NfcWrapper.getInstance().getAdapter(mActivity);
		}

		if (mNfcAdapter == null) {
			Log.i(TAG, "Nfc implementation not available.");
		}

		mNdefExchangeManager = new NdefExchangeManager(new NdefExchangeContract() {
			@Override
			public int handleNdef(NdefMessage[] ndef) {
				doHandleNdef(ndef);
				return NDEF_CONSUME;
			}
			
			@Override
			public NdefMessage getForegroundNdefMessage() {
				return mForegroundMessage;
			}
		});
		addNdefHandler(mNdefExchangeManager);
		addNdefHandler(new EmptyNdefHandler());
	}
	
	public Nfc(Activity activity) {
		this(activity, null, null);
	}

	/**
	 * Returns true if this device has a native NFC implementation.
	 */
	public boolean isNativeNfcAvailable() {
		return mNfcAdapter != null;
	}

	/**
	 * Removes any message from being shared with an interested reader.
	 */
	public void clearSharing() {
		mForegroundMessage = null;
		synchronized(this) {
			if (mState == STATE_RESUMED) {
				enableNdefPush();
			}
		}
	}
	
	/**
	 * Makes an ndef message available to any interested reader.
	 * @see NdefFactory
	 */
	public void share(NdefMessage ndefMessage) {
		mForegroundMessage = ndefMessage;
		synchronized(this) {
			if (mState == STATE_RESUMED) {
				enableNdefPush();
			}
		}
	}
	
	public void share(Uri uri) {
		mForegroundMessage = NdefFactory.fromUri(uri);
		synchronized(this) {
			if (mState == STATE_RESUMED) {
				enableNdefPush();
			}
		}
	}
	
	public void share(URL url) {
		mForegroundMessage = NdefFactory.fromUrl(url);
		synchronized(this) {
			if (mState == STATE_RESUMED) {
				enableNdefPush();
			}
		}
	}
	
	public void share (String mimeType, byte[] data) {
		mForegroundMessage = NdefFactory.fromMime(mimeType, data);
		synchronized(this) {
			if (mState == STATE_RESUMED) {
				enableNdefPush();
			}
		}
	}

	/**
	 * Sets a callback to call when an Nfc tag is written.
	 */
	public void setOnTagWriteListener(OnTagWriteListener listener) {
		mOnTagWriteListener = listener;
	}

	/**
	 * Disallows connection handover requests.
	 */
	public void disableConnectionHandover() {
		mHandoverEnabled = false;
	}
	
	/**
	 * Enables support for connection handover requests.
	 */
	public void enableConnectionHandover() {
		mHandoverEnabled = true;
	}
	
	/**
	 * Returns true if connection handovers are currently supported.
	 */
	public boolean isConnectionHandoverEnabled() {
		return mHandoverEnabled;
	}

	/**
	 * Sets a callback to call when an Nfc tag is written.
	 */
	public void addNdefHandler(NdefHandler handler) {
		if (handler instanceof PrioritizedHandler) {
			addNdefHandler(((PrioritizedHandler)handler).getPriority(), handler);
		} else {
			addNdefHandler(PrioritizedHandler.DEFAULT_PRIORITY, handler);
		}
	}
	
	public synchronized void addNdefHandler(Integer priority, NdefHandler handler) {
		if (!mNdefHandlers.containsKey(priority)) {
			mNdefHandlers.put(priority, new HashSet<NdefHandler>());
		}
		Set<NdefHandler> handlers = mNdefHandlers.get(priority);
		handlers.add(handler);
	}
	
	public synchronized void clearNdefHandlers() {
		mNdefHandlers.clear();
	}
	
	private synchronized void doHandleNdef(NdefMessage[] ndefMessages) {
		Iterator<Integer> bins = mNdefHandlers.keySet().iterator();
		while (bins.hasNext()) {
			Integer priority = bins.next();
			Iterator<NdefHandler> handlers = mNdefHandlers.get(priority).iterator();
			while (handlers.hasNext()) {
				NdefHandler handler = handlers.next();
				if (handler.handleNdef(ndefMessages) == NdefHandler.NDEF_CONSUME) {
					return;
				}
			}
		}
	}
	
	/**
	 * Interface definition for a callback called after an attempt to write
	 * an Nfc tag.
	 */
	public interface OnTagWriteListener {
		public static final int WRITE_OK = 0;
		public static final int WRITE_ERROR_READ_ONLY = 1;
		public static final int WRITE_ERROR_CAPACITY = 2;
		public static final int WRITE_ERROR_BAD_FORMAT = 3;
		public static final int WRITE_ERROR_IO_EXCEPTION = 4;
		
		/**
		 * Callback issued after an attempt to write an NFC tag.
		 * This method is executed off the main thread, so be careful when
		 * updating UI elements as a result of this callback.
		 */
		public void onTagWrite(int status);
	}
	
	/**
	 * Puts the interface in mode {@link #MODE_WRITE}.
	 * @param ndef The NdefMessage to write to a discovered tag.
	 * @throws NullPointerException if ndef is null.
	 */
	public void enableTagWriteMode(NdefMessage ndef) {
		if (mNfcAdapter == null) {
			return;
		}

		if (ndef == null) {
			throw new NullPointerException("Cannot write null NDEF message.");
		}

		mWriteMessage = ndef;
		mInterfaceMode = MODE_WRITE;

		mActivity.runOnUiThread(new Runnable() {
			@Override
			public void run() {
				if (mState == STATE_RESUMED && mInterfaceMode == MODE_WRITE) {
					installNfcHandler();
				}
			}
		});
	}
	
	/**
	 * Puts the interface in mode {@link #MODE_EXCHANGE},
	 * the default mode of operation for this Nfc interface.
	 */
	public void enableExchangeMode() {
		mInterfaceMode = MODE_EXCHANGE;
		mActivity.runOnUiThread(new Runnable() {
			@Override
			public void run() {
				if (mState == STATE_RESUMED) {
					installNfcHandler();
					enableNdefPush();
				}
			}
		});
	}


	public void onCreate(Activity activity) {
	    onNewIntent(activity, activity.getIntent());
	}

	/**
	 * Call this method in your Activity's onResume() method body.
	 */
	public void onResume(Activity activity) {
		// refresh mActivity
		mActivity = activity;

		mState = STATE_RESUMING;
		if (isConnectionHandoverEnabled()) {
			installNfcHandoverHandler();
			enableNdefPush();
		}

		if (mNfcAdapter != null) {
			synchronized(this) {
				if (mInterfaceMode != MODE_PASSTHROUGH) {
					installNfcHandler();
					if (mInterfaceMode == MODE_EXCHANGE) {
						enableNdefPush();
					}
				}
			}
		}
		mState = STATE_RESUMED;
	}
	
	/**
	 * Call this method in your Activity's onPause() method body.
	 */
	public void onPause(Activity activity) {
		// refresh mActivity
		mActivity = activity;

		mState = STATE_PAUSING;
		if (isConnectionHandoverEnabled()) {
		    uninstallNfcHandoverHandler();
            notifyRemoteNfcInteface(null);
		}

		if (mNfcAdapter != null) {
			synchronized(this) {
				mNfcAdapter.disableForegroundDispatch(mActivity);
				mNfcAdapter.disableForegroundNdefPush(mActivity);
			}
		}
		mState = STATE_PAUSED;
	}
	
	/**
	 * Call this method in your activity's onNewIntent(Intent) method body.
	 * @return true if this call consumed the intent.
	 */
	public boolean onNewIntent(Activity activity, Intent intent) {
		// refresh mActivity
		mActivity = activity;
		if (mInterfaceMode == MODE_PASSTHROUGH) {
			return false;
		}

		// Check to see if the intent is ours to handle:
		if (!(NfcAdapter.ACTION_TAG_DISCOVERED.equals(intent.getAction())
				|| NfcAdapter.ACTION_NDEF_DISCOVERED.equals(intent.getAction()))) {
			return false;
		}

		new TagHandlerThread(mInterfaceMode, intent).start();
		return true;
	}
	
	private class TagHandlerThread extends Thread {
		final int mmMode;
		final Intent mmIntent;
		
		TagHandlerThread(int mode, Intent intent) {
			mmMode = mode;
			mmIntent = intent;
		}
		
		@Override
		public void run() {
			// Check to see if we are writing to a tag
			if (mmMode == MODE_WRITE) {
				final Tag tag = mmIntent.getParcelableExtra(NfcAdapter.EXTRA_TAG);
				final NdefMessage ndef = mWriteMessage;
				if (tag != null && ndef != null) {
					OnTagWriteListener listener = mOnTagWriteListener;
					int status = writeTag(tag, ndef);
					if (listener != null) {
						listener.onTagWrite(status);
					}
				}
				return;
			}

			// In "exchange" mode.
			Parcelable[] rawMsgs = mmIntent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES);
			if (rawMsgs == null || rawMsgs.length == 0) {
				return;
			}

			final NdefMessage[] ndefMessages = new NdefMessage[rawMsgs.length];
			for (int i = 0; i < rawMsgs.length; i++) {
				ndefMessages[i] = (NdefMessage)rawMsgs[i];
			}
			doHandleNdef(ndefMessages);
		}
	}

	public ConnectionHandoverManager getConnectionHandoverManager() {
		return mNdefExchangeManager;
	}
	
	/**
	 * Puts the interface in mode {@link #MODE_PASSTHROUGH}.
	 */
	public void disable() {
		mInterfaceMode = MODE_PASSTHROUGH;
		mActivity.runOnUiThread(new Runnable() {
			@Override
			public void run() {
				synchronized(Nfc.this) {
					try {
						if (mState < STATE_RESUMING) {
							return;
						}
						mNfcAdapter.disableForegroundDispatch(mActivity);
						mNfcAdapter.disableForegroundNdefPush(mActivity);
					} catch (IllegalStateException e) {

					}
				}
			}
		});
	}
	
	/**
	 * Sets an ndef message to be read via android.npp protocol.
	 * This method may be called off the main thread.
	 */
	private void enableNdefPush() {
		final NdefMessage ndef = mForegroundMessage;
		if (isConnectionHandoverEnabled()) {
			notifyRemoteNfcInteface(ndef);
		}

		if (!isNativeNfcAvailable()) {
			return;
		}

		if (ndef == null) {
			mActivity.runOnUiThread(new Runnable() {
				@Override
				public void run() {
					synchronized (Nfc.this) {
						if (mState < STATE_RESUMING) {
							return;
						}
						mNfcAdapter.disableForegroundNdefPush(mActivity);
					}
				}
			});
			return;
		} else {		
    		mActivity.runOnUiThread(new Runnable() {
    			@Override
    			public void run() {
    				synchronized (Nfc.this) {
    					if (mState < STATE_RESUMING) {
    						return;
    					}
    					mNfcAdapter.enableForegroundNdefPush(mActivity, ndef);
    				}
    			}
    		});
		}
	}

	private void notifyRemoteNfcInteface(NdefMessage ndef) {
		Intent intent = new Intent(ACTION_SET_NDEF);
		if (ndef != null) {
			NdefMessage[] ndefMessages = new NdefMessage[] { ndef };
			intent.putExtra(NfcAdapter.EXTRA_NDEF_MESSAGES, ndefMessages);
		}
		
		mActivity.sendBroadcast(intent);
	}
	
	/**
	 * Requests any foreground NFC activity. This method must be called from
	 * the main thread.
	 */
	private void installNfcHandler() {
		Intent activityIntent = new Intent(mActivity, mActivity.getClass());
		activityIntent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
		
        PendingIntent intent = PendingIntent.getActivity(mActivity, 0,
                activityIntent, PendingIntent.FLAG_CANCEL_CURRENT);
        mNfcAdapter.enableForegroundDispatch(mActivity, intent, mIntentFilters, mTechLists);
	}
	
	private BroadcastReceiver mHandoverReceiver = new BroadcastReceiver() {
		@Override
		public void onReceive(Context context, Intent intent) {
			new TagHandlerThread(mInterfaceMode, intent).start();
			setResultCode(Activity.RESULT_CANCELED);
		}
	};

	private void installNfcHandoverHandler() {
		IntentFilter handoverFilter = new IntentFilter();
		handoverFilter.addAction(ACTION_HANDLE_NDEF);
		mActivity.registerReceiver(mHandoverReceiver, handoverFilter);
	}

	private void uninstallNfcHandoverHandler() {
		mActivity.unregisterReceiver(mHandoverReceiver);
	}

	/*
	 * Credit: AOSP, via Android Tag application.
	 * http://android.git.kernel.org/?p=platform/packages/apps/Tag.git;a=summary
	 */
	private int writeTag(Tag tag, NdefMessage message) {
        try {
        	int size = message.toByteArray().length;
            Ndef ndef = Ndef.get(tag);
            if (ndef != null) {
                ndef.connect();
                if (!ndef.isWritable()) {
                	Log.w(TAG, "Tag is read-only.");
                    return OnTagWriteListener.WRITE_ERROR_READ_ONLY;
                }
                if (ndef.getMaxSize() < size) {
                    Log.d(TAG, "Tag capacity is " + ndef.getMaxSize() + " bytes, message is " +
                            size + " bytes.");
                    return OnTagWriteListener.WRITE_ERROR_CAPACITY;
                }

                ndef.writeNdefMessage(message);
                return OnTagWriteListener.WRITE_OK;
            } else {
                NdefFormatable format = NdefFormatable.get(tag);
                if (format != null) {
                    try {
                        format.connect();
                        format.format(message);
                        return OnTagWriteListener.WRITE_OK;
                    } catch (IOException e) {
                        return OnTagWriteListener.WRITE_ERROR_IO_EXCEPTION;
                    }
                } else {
                    return OnTagWriteListener.WRITE_ERROR_BAD_FORMAT;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to write tag", e);
        }

        return OnTagWriteListener.WRITE_ERROR_IO_EXCEPTION;
    }

	private class EmptyNdefHandler implements NdefHandler, PrioritizedHandler {
		@Override
		public int handleNdef(NdefMessage[] ndefMessages) {
			return NdefFactory.isEmpty(ndefMessages[0]) ? NDEF_CONSUME : NDEF_PROPAGATE;
		}
		
		@Override
		public int getPriority() {
			return 0;
		}
	};

	/**
	 * @hide
	 */
	public Activity getContext() {
	    return mActivity;
	}

	private void toast(final String text) {
		mActivity.runOnUiThread(new Runnable() {
			@Override
			public void run() {
				Toast.makeText(mActivity, text, Toast.LENGTH_LONG).show();
			}
		});
	}
}