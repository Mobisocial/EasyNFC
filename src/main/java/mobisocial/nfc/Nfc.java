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

import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.net.URL;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;


import android.app.Activity;
import android.app.PendingIntent;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.nfc.NdefMessage;
import android.nfc.NdefRecord;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.tech.Ndef;
import android.nfc.tech.NdefFormatable;
import android.os.Parcelable;
import android.util.Log;

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
	private static final String TAG = "libhotpotato";

	private Activity mActivity;
	private NfcAdapter mNfcAdapter;
	private final IntentFilter[] mIntentFilters;
	private final String[][] mTechLists;
	private NdefMessage mForegroundMessage = null;
	private NdefMessage mWriteMessage = null;
	private final Map<Integer, Set<NdefHandler>> mNdefHandlers = new TreeMap<Integer, Set<NdefHandler>>();
	private final ConnectionHandoverManager mConnectionHandoverManager = new ConnectionHandoverManager();
	private OnTagWriteListener mOnTagWriteListener = null;
	
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

		if (PackageManager.PERMISSION_GRANTED !=
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
			return;
		}

		addNdefHandler(mConnectionHandoverManager);
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
		if (mConnectionHandoverManager != null) {
			mConnectionHandoverManager.disableConnectionHandover();
		}
	}
	
	/**
	 * Enables support for connection handover requests.
	 */
	public void enableConnectionHandover() {
		if (mConnectionHandoverManager != null) {
			mConnectionHandoverManager.enableConnectionHandover();
		}
	}
	
	/**
	 * Returns true if connection handovers are currently supported.
	 */
	public boolean isConnectionHandoverEnabled() {
		if (mConnectionHandoverManager != null) {
			return mConnectionHandoverManager.isConnectionHandoverEnabled();
		}
		return false;
	}
	
	/**
	 * Returns true if the given Ndef message contains a connection
	 * handover request.
	 */
	public static boolean isHandoverRequest(NdefMessage ndef) {
		NdefRecord[] records = (ndef).getRecords();
		return (records.length >= 3 
			&& records[0].getTnf() == NdefRecord.TNF_WELL_KNOWN
			&& Arrays.equals(records[0].getType(), NdefRecord.RTD_HANDOVER_REQUEST));
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


	/**
	 * A callback issued when an Nfc tag is read.
	 */
	public static interface NdefHandler {
		public static final int NDEF_PROPAGATE = 0;
		public static final int NDEF_CONSUME = 1;

		/**
		 * Callback issued after an NFC tag is read or an NDEF message is received
		 * from a remote device. This method is executed off the main thread, so be
		 * careful when updating UI elements as a result of this callback.
		 * 
		 * @return {@link #NDEF_CONSUME} to indicate this handler has consumed the
		 *         given message, or {@link #NDEF_PROPAGATE} to pass on to the next
		 *         handler.
		 */
		public abstract int handleNdef(NdefMessage[] ndefMessages);
	}

	/**
	 * Specifies the priority used to determine the order in which
	 * handlers are executed.
	 */
	public interface PrioritizedHandler {
		public static final int DEFAULT_PRIORITY = 50;
		public abstract int getPriority();
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


	/**
	 * Call this method in your Activity's onResume() method body.
	 */
	public void onResume(Activity activity) {
		mState = STATE_RESUMING;
		if (mNfcAdapter == null) {
			installNfcHandoverHandler();
			enableNdefPush();
		} else {
			// refresh mActivity
			mActivity = activity;
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
		mState = STATE_PAUSING;
		if (mNfcAdapter == null) {
			uninstallNfcHandoverHandler();
			notifyRemoteNfcInteface(null);
		} else {
			// refresh mActivity
			mActivity = activity;
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
	
	/**
	 * An interface for classes supporting communication established using
     * Nfc but with an out-of-band data transfer.
	 */
	public interface ConnectionHandover {
		/**
		 * Issues a connection handover of the given type.
		 * @param handoverRequest The connection handover request message.
		 * @param recordNumber The index of the handover record entry being attempted.
		 * @param nfcInterface The Nfc interface for sending and receiving ndef messages.
		 * @throws IOException
		 */
		public void doConnectionHandover(NdefMessage handoverRequest, int recordNumber, NfcInterface nfcInterface) throws IOException;
		public boolean supportsRequest(NdefRecord record);
	}
	
	public class ConnectionHandoverManager implements NdefHandler, PrioritizedHandler {
		public static final int HANDOVER_PRIORITY = 5;
		private final Set<ConnectionHandover> mmConnectionHandovers = new LinkedHashSet<ConnectionHandover>();
		private boolean mmConnectionHandoverEnabled = true;
		
		public ConnectionHandoverManager() {
			mmConnectionHandovers.add(new NdefBluetoothPushHandover());
			mmConnectionHandovers.add(new NdefTcpPushHandover());
		}
		
		public void addConnectionHandover(ConnectionHandover handover) {
			mmConnectionHandovers.add(handover);
		}
		
		public void clearConnectionHandovers() {
			mmConnectionHandovers.clear();
		}
		
		public boolean removeConnectionHandover(ConnectionHandover handover) {
			return mmConnectionHandovers.remove(handover);
		}
		
		/**
		 * Disallows connection handover requests.
		 */
		public void disableConnectionHandover() {
			mmConnectionHandoverEnabled = false;
		}
		
		/**
		 * Enables support for connection handover requests.
		 */
		public void enableConnectionHandover() {
			mmConnectionHandoverEnabled = true;
		}
		
		public boolean isConnectionHandoverEnabled() {
			return mmConnectionHandoverEnabled;
		}
		
		/**
		 * Returns the (mutable) set of active connection handover
		 * responders.
		 */
		public Set<ConnectionHandover> getConnectionHandoverResponders() {
			return mmConnectionHandovers;
		}
		
		@Override
		public final int handleNdef(NdefMessage[] handoverRequest) {
			return doHandover(handoverRequest[0], mForegroundMessage);
		}

		public final int doHandover(NdefMessage handoverRequest, final NdefMessage outboundNdef) {
			if (!isHandoverRequest(handoverRequest) || !mmConnectionHandoverEnabled) {
				return NDEF_PROPAGATE;
			}

			NfcInterface nfcInterface = new NfcInterface() {
				@Override
				public void handleNdef(NdefMessage ndef) {
					doHandleNdef(new NdefMessage[] { ndef });
				}
				
				@Override
				public NdefMessage getForegroundNdefMessage() {
					return outboundNdef;
				}
			};

			NdefRecord[] records = handoverRequest.getRecords();
			for (int i = 2; i < records.length; i++) {
				Iterator<ConnectionHandover> handovers = mmConnectionHandovers.iterator();
				while (handovers.hasNext()) {
					ConnectionHandover handover = handovers.next();
					if (handover.supportsRequest(records[i])) {
						try {
							handover.doConnectionHandover(handoverRequest, i, nfcInterface);
							return NDEF_CONSUME;
						} catch (IOException e) {
							Log.w(TAG, "Handover failed.", e);
							// try the next one.
						}
					}
				}
			}

			return NDEF_PROPAGATE;
		}

		@Override
		public int getPriority() {
			return HANDOVER_PRIORITY;
		}
	}
	
	public ConnectionHandoverManager getConnectionHandoverManager() {
		return mConnectionHandoverManager;
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
		}
		
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
		
		PendingIntent intent = PendingIntent.getActivity(mActivity, 0, activityIntent, 0);
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

	/**
	 * <p>Implements an Ndef push handover request in which a static tag
	 * represents an Ndef reader device listening on a TCP socket.
	 * </p>
	 * <p>
	 * Your application must hold the {@code android.permission.INTERNET}
	 * permission to support TCP handovers.
	 * </p>
	 */
	public static class NdefTcpPushHandover implements ConnectionHandover {
		private static final int DEFAULT_TCP_HANDOVER_PORT = 7924;
		
		@Override
		public boolean supportsRequest(NdefRecord handoverRequest) {
			if (handoverRequest.getTnf() != NdefRecord.TNF_ABSOLUTE_URI
					|| !Arrays.equals(handoverRequest.getType(), NdefRecord.RTD_URI)) {
				return false;
			}
			
			String uriString = new String(handoverRequest.getPayload());
			if (uriString.startsWith("ndef+tcp://")) {
				return true;
			}
			
			return false;
		}

		@Override
		public void doConnectionHandover(NdefMessage handoverMessage, int record, NfcInterface nfcInterface) throws IOException {
			NdefRecord handoverRequest = handoverMessage.getRecords()[record];
			NdefMessage outboundNdef = nfcInterface.getForegroundNdefMessage();
			if (outboundNdef == null) return;
			
			String uriString = new String(handoverRequest.getPayload());
			Uri uri = Uri.parse(uriString);
			sendNdefOverTcp(uri, nfcInterface);
		}

		private void sendNdefOverTcp(Uri target, NfcInterface ndefProxy) throws IOException {
			String host = target.getHost();
			int port = target.getPort();
			if (port == -1) {
				port = DEFAULT_TCP_HANDOVER_PORT;
			}

			DuplexSocket socket = new TcpDuplexSocket(host, port);
			new HandoverConnectedThread(socket, ndefProxy).start();
		}
	}

	/**
	 * <p>Implements an Ndef push handover request in which a static tag
	 * represents an Ndef reader device listening on a Bluetooth socket.
	 * </p>
	 * <p>
	 * Your application must hold the {@code android.permission.BLUETOOTH}
	 * permission to support Bluetooth handovers.
	 * </p>
	 */
	public static class NdefBluetoothPushHandover implements ConnectionHandover {
		final BluetoothAdapter mmBluetoothAdapter;
		
		public NdefBluetoothPushHandover() {
			mmBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
		}

		@Override
		public boolean supportsRequest(NdefRecord handoverRequest) {
			if (handoverRequest.getTnf() != NdefRecord.TNF_ABSOLUTE_URI
					|| !Arrays.equals(handoverRequest.getType(), NdefRecord.RTD_URI)) {
				return false;
			}

			String uriString = new String(handoverRequest.getPayload());
			if (uriString.startsWith("ndef+bluetooth://")) {
				return true;
			}
			
			return false;
		}
		
		@Override
		public void doConnectionHandover(NdefMessage handoverMessage, int record, NfcInterface nfcInterface) throws IOException {
			NdefRecord handoverRequest = handoverMessage.getRecords()[record];
			String uriString = new String(handoverRequest.getPayload());
			Uri target = Uri.parse(uriString);
			
			String mac = target.getAuthority();
			UUID uuid = UUID.fromString(target.getPath().substring(1));
			DuplexSocket socket = new BluetoothDuplexSocket(mmBluetoothAdapter, mac, uuid);
			new HandoverConnectedThread(socket, nfcInterface).start();
		}
	}


	/**
	 * Runs a thread during a connection handover with a remote device over a
	 * {@see DuplexSocket}, transmitting the given Ndef message.
	 */
	private static class HandoverConnectedThread extends Thread {
		public static final byte HANDOVER_VERSION = 0x19;
		private final DuplexSocket mmSocket;
		private final InputStream mmInStream;
		private final OutputStream mmOutStream;
		private final NfcInterface mmNfcInterface;
		
		private boolean mmIsWriteDone = false;
		private boolean mmIsReadDone = false;
		
		public HandoverConnectedThread(DuplexSocket socket, NfcInterface nfcInterface) {
			mmNfcInterface = nfcInterface;
			mmSocket = socket;
			InputStream tmpIn = null;
			OutputStream tmpOut = null;

			try {
				socket.connect();
				tmpIn = socket.getInputStream();
				tmpOut = socket.getOutputStream();
			} catch (IOException e) {
				Log.e(TAG, "temp sockets not created", e);
			}

			mmInStream = tmpIn;
			mmOutStream = tmpOut;
		}

		public void run() {
			try {
				if (mmInStream == null || mmOutStream == null) {
					return;
				}

				// Read on this thread, write on a new one.
				new SendNdefThread().start();
				
				DataInputStream dataIn = new DataInputStream(mmInStream);
				byte version = (byte) dataIn.readByte();
				if (version != HANDOVER_VERSION) {
					throw new Exception("Bad handover protocol version.");
				}
				int length = dataIn.readInt();
				if (length > 0) {
					byte[] ndefBytes = new byte[length];
					int read = 0;
					while (read < length) {
						read += dataIn.read(ndefBytes, read, (length - read));
					}
					NdefMessage ndef = new NdefMessage(ndefBytes);
					mmNfcInterface.handleNdef(ndef);
				}
			} catch (Exception e) {
				Log.e(TAG, "Failed to issue handover.", e);
			} finally {
				synchronized(HandoverConnectedThread.this) {
					mmIsReadDone = true;
					if (mmIsWriteDone) {
						cancel();
					}
				}
			}
		}

		public void cancel() {
			try {
				mmSocket.close();
			} catch (IOException e) {}
		}
		
		
		private class SendNdefThread extends Thread {
			@Override
			public void run() {
				try {
					NdefMessage outbound = mmNfcInterface.getForegroundNdefMessage();
					DataOutputStream dataOut = new DataOutputStream(mmOutStream);
					dataOut.writeByte(HANDOVER_VERSION);
					if (outbound != null) {
						byte[] ndefBytes = outbound.toByteArray();
						dataOut.writeInt(ndefBytes.length);
						dataOut.write(ndefBytes);
					} else {
						dataOut.writeInt(0);
					}
					dataOut.flush();
				} catch (IOException e) {
					Log.e(TAG, "Error writing to socket", e);
				} finally {
					synchronized(HandoverConnectedThread.this) {
						mmIsWriteDone = true;
						if (mmIsReadDone) {
							cancel();
						}
					}
				}
			}
		}
	}
	
	public interface NfcInterface {
		public void handleNdef(NdefMessage ndef);
		public NdefMessage getForegroundNdefMessage();
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
	 * A wrapper for the standard Socket implementation.
	 *
	 */
	interface DuplexSocket extends Closeable {
		public InputStream getInputStream() throws IOException;
		public OutputStream getOutputStream() throws IOException;
		public void connect() throws IOException;
	}
	
	static class BluetoothDuplexSocket implements DuplexSocket {
		final String mmMac;
		final UUID mmServiceUuid;
		final BluetoothAdapter mmBluetoothAdapter;
		BluetoothSocket mmSocket;

		public BluetoothDuplexSocket(BluetoothAdapter adapter, String mac, UUID serviceUuid) throws IOException {
			mmBluetoothAdapter = adapter;
			mmMac = mac;
			mmServiceUuid = serviceUuid;
		}
		
		@Override
		public void connect() throws IOException {
			BluetoothDevice device = mmBluetoothAdapter.getRemoteDevice(mmMac);
			mmSocket = device.createInsecureRfcommSocketToServiceRecord(mmServiceUuid);
			mmSocket.connect();
		}
		
		@Override
		public InputStream getInputStream() throws IOException {
			return mmSocket.getInputStream();
		}
		
		@Override
		public OutputStream getOutputStream() throws IOException {
			return mmSocket.getOutputStream();
		}
		
		@Override
		public void close() throws IOException {
			if (mmSocket != null) {
				mmSocket.close();
			}
		}
	}
	
	static class TcpDuplexSocket implements DuplexSocket {
		final Socket mSocket;

		public TcpDuplexSocket(String host, int port) throws IOException {
			mSocket = new Socket(host, port);
		}
		
		@Override
		public void connect() throws IOException {
			
		}
		
		@Override
		public InputStream getInputStream() throws IOException {
			return mSocket.getInputStream();
		}
		
		@Override
		public OutputStream getOutputStream() throws IOException {
			return mSocket.getOutputStream();
		}
		
		@Override
		public void close() throws IOException {
			mSocket.close();
		}
	}

	static class StreamDuplexSocket implements DuplexSocket {
		final InputStream mInputStream;
		final OutputStream mOutputStream;

		public StreamDuplexSocket(InputStream in, OutputStream out) throws IOException {
			mInputStream = in;
			mOutputStream = out;
		}

		@Override
		public void connect() throws IOException {

		}

		@Override
		public InputStream getInputStream() throws IOException {
			return mInputStream;
		}

		@Override
		public OutputStream getOutputStream() throws IOException {
			return mOutputStream;
		}

		@Override
		public void close() throws IOException {
			mInputStream.close();
			mOutputStream.close();
		}
	}

	/**
	 * A utility class for generating NDEF messages.
	 * @see NdefMessage
	 */
	public static class NdefFactory {
		public static NdefMessage fromUri(Uri uri) {
			try {
				NdefRecord record = new NdefRecord(NdefRecord.TNF_ABSOLUTE_URI, NdefRecord.RTD_URI, 
						new byte[0], uri.toString().getBytes());
				NdefRecord[] records = new NdefRecord[] { record };
				return new NdefMessage(records);
			} catch (NoClassDefFoundError e) {
				return null;
			}
		}
		
		public static NdefMessage fromUri(URI uri) {
			try {
				NdefRecord record = new NdefRecord(NdefRecord.TNF_ABSOLUTE_URI, NdefRecord.RTD_URI, 
						new byte[0], uri.toString().getBytes());
				NdefRecord[] records = new NdefRecord[] { record };
				return new NdefMessage(records);
			} catch (NoClassDefFoundError e) {
				return null;
			}
		}

		public static NdefMessage fromUri(String uri) {
			try {
				NdefRecord record = new NdefRecord(NdefRecord.TNF_ABSOLUTE_URI, NdefRecord.RTD_URI, 
						new byte[0], uri.getBytes());
				NdefRecord[] records = new NdefRecord[] { record };
				return new NdefMessage(records);
			} catch (NoClassDefFoundError e) {
				return null;
			}
		}

		public static NdefMessage fromUrl(URL url) {
			try {
				NdefRecord record = new NdefRecord(NdefRecord.TNF_ABSOLUTE_URI,
						NdefRecord.RTD_URI, new byte[0], url.toString().getBytes());
				NdefRecord[] records = new NdefRecord[] { record };
				return new NdefMessage(records);
			} catch (NoClassDefFoundError e) {
				return null;
			}
		}
		
		public static NdefMessage fromMime(String mimeType, byte[] data) {
			try {
				NdefRecord record = new NdefRecord(NdefRecord.TNF_MIME_MEDIA,
						mimeType.getBytes(), new byte[0], data);
				NdefRecord[] records = new NdefRecord[] { record };
				return new NdefMessage(records);
			} catch (NoClassDefFoundError e) {
				return null;
			}
		}
		
		/**
		 * Creates an NDEF message with a single text record, with language
		 * code "en" and the given text, encoded using UTF-8.
		 */
		public static NdefMessage fromText(String text) {
			try {
				byte[] textBytes = text.getBytes();
				byte[] textPayload = new byte[textBytes.length + 3];
				textPayload[0] = 0x02; // Status byte; UTF-8 and "en" encoding.
				textPayload[1] = 'e';
				textPayload[2] = 'n';
				System.arraycopy(textBytes, 0, textPayload, 3, textBytes.length);
				NdefRecord record = new NdefRecord(NdefRecord.TNF_WELL_KNOWN,
						NdefRecord.RTD_TEXT, new byte[0], textPayload);
				NdefRecord[] records = new NdefRecord[] { record };
				return new NdefMessage(records);
			} catch (NoClassDefFoundError e) {
				return null;
			}
		}
		
		/**
		 * Creates an NDEF message with a single text record, with the given
		 * text content (UTF-8 encoded) and language code. 
		 */
		public static NdefMessage fromText(String text, String languageCode) {
			try {
				int languageCodeLength = languageCode.length();
				int textLength = text.length();
				byte[] textPayload = new byte[textLength + 1 + languageCodeLength];
				textPayload[0] = (byte)(0x3F & languageCodeLength); // UTF-8 with the given language code length.
				System.arraycopy(languageCode.getBytes(), 0, textPayload, 1, languageCodeLength);
				System.arraycopy(text.getBytes(), 0, textPayload, 1 + languageCodeLength, textLength);
				NdefRecord record = new NdefRecord(NdefRecord.TNF_WELL_KNOWN,
						NdefRecord.RTD_TEXT, new byte[0], textPayload);
				NdefRecord[] records = new NdefRecord[] { record };
				return new NdefMessage(records);
			} catch (NoClassDefFoundError e) {
				return null;
			}
		}

		public static final NdefMessage getEmptyNdef() {
			byte[] empty = new byte[] {};
			NdefRecord[] records = new NdefRecord[1];
			records[0] = new NdefRecord(NdefRecord.TNF_WELL_KNOWN, empty, empty, empty);
			NdefMessage ndef = new NdefMessage(records);
			return ndef;
		}

		public static final boolean isEmpty(NdefMessage ndef) {
			return  (ndef == null || ndef.equals(getEmptyNdef()));
		}
	}
}