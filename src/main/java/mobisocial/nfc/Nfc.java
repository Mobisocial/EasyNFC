package mobisocial.nfc;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URL;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;


import android.app.Activity;
import android.app.Dialog;
import android.app.PendingIntent;
import android.app.ProgressDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Intent;
import android.net.Uri;
import android.nfc.NdefMessage;
import android.nfc.NdefRecord;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.tech.Ndef;
import android.nfc.tech.NdefFormatable;
import android.os.AsyncTask;
import android.os.Parcelable;
import android.util.Log;
import android.widget.Toast;

/**
 * <p>This class acts as an abstraction layer for Android's Nfc stack.
 * The goals of this project are to:
 * <ul>
 * <li>Provide compatibility for Nfc across platforms.
 * <li>Degrade gracefully on platforms and devices lacking Nfc support.
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
 *   onResume() {
 *     super.onResume();
 *     mNfc.resume(this);
 *     // your activity's onResume code
 *   }
 *
 *  onPause() {
 *    super.onPause();
 *    mNfc.pause(this);
 *    // your activity's onPause code
 *  }
 *  
 *  onNewIntent(Intent intent) {
 *    if (mNfc.handleNewIntent(this, intent)) {
 *      return;
 *    }
 *    // your activity's onNewIntent code
 *  }
 * }
 * </pre>
 *
 * <p>Obtain an instance of this class by using the static {@link #getInstance} method.
 * To interact with the Nfc device, see:
 * <ul>
 *   <li>{@link #share(NdefMessage)} and similar, to share messages with other Nfc devices.
 *   <li>{@link #setOnTagReadListener}, for reacting to scanned tags without blocking.
 *   <li>{@link #waitForRead}, to block the UI while waiting for a tag to be read.
 *   <li>{@link #waitForWrite}, to block the UI while waiting to write to a tag.
 * </ul>
 * </p>
 */
public class Nfc {
	private static final String TAG = "easynfc";
	private static final Map<String, Nfc> sNfcs = new HashMap<String, Nfc>();
	
	private final Set<ConnectionHandover> mConnectionHandovers;
	private Activity mActivity;
	private NfcAdapter mNfcAdapter;
	private NdefMessage mForegroundMessage = null;
	private boolean mConnectionHandoverEnabled = true;
	private Intent mLastTagDiscoveredIntent = null;
	private OnTagReadListener mOnTagReadListener = null;
	
	private int mState = STATE_PAUSED;
	private int mTagAction = ACTION_NONE;
	
	private static final int STATE_PAUSED = 0;
	private static final int STATE_PAUSING = 1;
	private static final int STATE_RESUMING = 2;
	private static final int STATE_RESUMED = 3;
	
	private static final int ACTION_NONE = 0;
	private static final int ACTION_READ = 1;
	private static final int ACTION_WRITE = 2;
	
	private Dialog mWriteTagDialog;
	private Dialog mReadTagDialog;
	
	private Nfc(Activity activity) {
		mActivity = activity;
		try {
			mNfcAdapter = NfcAdapter.getDefaultAdapter(mActivity); 
		} catch (NoClassDefFoundError e) {
			Log.i(TAG, "Nfc not available.");
		}
		mConnectionHandovers =	new LinkedHashSet<ConnectionHandover>();
		mConnectionHandovers.add(new NdefBluetoothPushHandover());
		mConnectionHandovers.add(new NdefTcpPushHandover());
	}
	
	/**
	 * Returns a new Nfc object bound to the given
	 * activity's package.
	 */
	public static Nfc getInstance(Activity activity) {
		String key = activity.getPackageName();
		if (!sNfcs.containsKey(key)) {
			sNfcs.put(key, new Nfc(activity));
		}
		return sNfcs.get(key);
	}
	
	/**
	 * Makes an ndef message available to any interested reader.
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
	 * Returns the (mutable) set of active connection handover
	 * responders.
	 */
	public Set<ConnectionHandover> getConnectionHandoverResponders() {
		return mConnectionHandovers;
	}
	
	
	/**
	 * Disallows connection handover requests.
	 */
	public void disableConnectionHandover() {
		mConnectionHandoverEnabled = false;
	}
	
	/**
	 * Enables support for connection handover requests.
	 */
	public void enableConnectionHandover() {
		mConnectionHandoverEnabled = true;
	}
	
	/**
	 * Returns true if connection handovers are currently supported.
	 */
	public boolean isConnectionHandoverEnabled() {
		return mConnectionHandoverEnabled;
	}
	

	/** 
	 * Read an NdefMessage from an available tag or device. This method
	 * presents a dialog to the user so the rest of your activity cannot be
	 * used until a read completes or the user cancels the action. This method
	 * must be called from the main thread of your activity.
	 * 
	 * The results of scanning a tag are handled with
	 * a callback. Set it with {@link #setOnTagReadListener}.
	 * 
	 * Note that you may use this callback without calling
	 * {@code waitForRead()}, resulting in a non-blocking read.
         *
         * {@see #setOnTagReadListener}
	 */
	public void waitForRead() {
		ReadTagTask task = new ReadTagTask();
		task.execute();
	}
	
        /**
         * Sets a callback to call when an Nfc tag is scanned.
         */
	public void setOnTagReadListener(OnTagReadListener listener) {
		mOnTagReadListener = listener;
	}
	
	/**
 	 * Interface definition for a callback called when
         * an Nfc tag is read.
         */
	public interface OnTagReadListener {
		public void onTagRead(NdefMessage ndef);
	}
	
	/** 
	 * Writes an NdefMessage to an available tag.
	 * This method presents a dialog to the user so the
	 * rest of your activity cannot be used until a write
	 * completes or the user cancels the action.
	 */
	public void waitForWrite(NdefMessage ndef) {
		new WriteTagTask().execute(ndef);
	}
	
	public void waitForWrite(Uri uri) {
		new WriteTagTask().execute(NdefFactory.fromUri(uri));
	}
	
	/**
	 * Call this method in your Activity's onResume() method body.
	 */
	public void resume(Activity activity) {
		if (mNfcAdapter == null) {
			return;
		}
		
		// refresh mActivity
		mActivity = activity;
		
		mState = STATE_RESUMING;
		synchronized(this) {
			try {
				if (mState == STATE_RESUMING) {
					installNfcHandler();
					enableNdefPush();
				}
			} catch (IllegalStateException e) {
				Toast.makeText(mActivity, "failed to enable ndef push", Toast.LENGTH_SHORT).show();
			}
		}
		mState = STATE_RESUMED;
	}
	
	/**
	 * Call this method in your Activity's onPause() method body.
	 */
	public void pause(Activity activity) {
		if (mNfcAdapter == null) {
			return;
		}
		
		// refresh mActivity
		mActivity = activity;
		
		mState = STATE_PAUSING;
		synchronized(this) {
			try {
				if (mState == STATE_PAUSING) {
					uninstallNfcHandler();
					disableNdefPush();
				}
			} catch (IllegalStateException e) {
				Toast.makeText(mActivity, "failed to enable ndef push", Toast.LENGTH_SHORT).show();
			}
		}
		mState = STATE_PAUSED;
	}
	
	/**
	 * Call this method in your activity's onNewIntent(Intent) method body.
	 */
	public boolean handleNewIntent(Activity activity, Intent intent) {
		// refresh mActivity
		mActivity = activity;

		// Check to see if the intent is ours to handle:
		if (!(NfcAdapter.ACTION_TAG_DISCOVERED.equals(intent.getAction())
				|| NfcAdapter.ACTION_NDEF_DISCOVERED.equals(intent.getAction()))) {
			return false;
		}
		
		Parcelable[] rawMsgs = intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES);
		if (rawMsgs == null || rawMsgs.length == 0) {
			return false;
		}
		
		// TODO: With full NPP implementation, READ should respect Connection Handover.
		// Move C.H. logic up. Should write overwrite connection handover?
		if (mTagAction == ACTION_READ && mOnTagReadListener != null) {
			mOnTagReadListener.onTagRead((NdefMessage)rawMsgs[0]);
		}
		
		// Are we blocking for nfc read or write?
		if (mTagAction != ACTION_NONE) {
			synchronized(Nfc.this) {
				mTagAction = ACTION_NONE;
				mLastTagDiscoveredIntent = intent;
				Nfc.this.notifyAll();
			}
			return true;
		}
		
		boolean handoverRequested = false;
		NdefRecord[] records = ((NdefMessage)rawMsgs[0]).getRecords();
		if (records[0].getTnf() == NdefRecord.TNF_WELL_KNOWN
			&& records.length >= 3 
			&& Arrays.equals(records[0].getType(), NdefRecord.RTD_HANDOVER_REQUEST)) {
			handoverRequested = true;
		}
		
		if (!mConnectionHandoverEnabled || handoverRequested == false) {
			if (mOnTagReadListener != null) {
				mOnTagReadListener.onTagRead((NdefMessage)rawMsgs[0]);
			}
			return true;
		}
		
		for (int i = 2; i < records.length; i++) {
			Iterator<ConnectionHandover> handovers = mConnectionHandovers.iterator();
			while (handovers.hasNext()) {
				ConnectionHandover handover = handovers.next();
				if (handover.supportsRequest(records[i])) {
					try {
						handover.doConnectionHandover(records[i]);
						return true;
					} catch (IOException e) {
						// try the next one.
					}
				}
			}
		}

		Log.w(TAG, "Found connection handover request, but failed to handle.");
		return true;
	}
	
	public void disable() {
		mForegroundMessage = null;
		if (mState == STATE_RESUMED) {
			disableNdefPush();
		}
	}
	
	/**
	 * Sets an ndef message to be read via android.npp protocol.
	 * This method may be called off the main thread.
	 */
	private void enableNdefPush() {
		if (mNfcAdapter == null) {
			return;
		}

		final NdefMessage ndef = mForegroundMessage;
		if (ndef == null) {
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
	
	private void disableNdefPush() {
		mActivity.runOnUiThread(new Runnable() {
			@Override
			public void run() {
				synchronized (Nfc.this) {
					if (mState < STATE_PAUSING) {
						Log.w(TAG, "Bad state while disabling ndef push.");
						return;
					}
					mNfcAdapter.disableForegroundNdefPush(mActivity);
				}
			}
		});
	}
	
	/**
	 * Requests any foreground NFC activity. This method must be called from
	 * the main thread.
	 */
	private void installNfcHandler() {
		Intent activityIntent = new Intent(mActivity, mActivity.getClass());
		activityIntent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
		
		PendingIntent intent = PendingIntent.getActivity(mActivity, 0, activityIntent, 0);
		mNfcAdapter.enableForegroundDispatch(mActivity, intent, null, null);
	}
	
	private void uninstallNfcHandler() {
		mNfcAdapter.disableForegroundDispatch(mActivity);
	}

	/**
	 * Credit: AOSP, via Android Tag application.
	 * http://android.git.kernel.org/?p=platform/packages/apps/Tag.git;a=summary
	 */
	private boolean writeTag(Tag tag, NdefMessage message) {
        try {
        	int size = message.toByteArray().length;
            Ndef ndef = Ndef.get(tag);
            if (ndef != null) {
                ndef.connect();
                if (!ndef.isWritable()) {
                	Log.w(TAG, "Tag is read-only.");
                    return false;
                }
                if (ndef.getMaxSize() < size) {
                    Log.d(TAG, "Tag capacity is " + ndef.getMaxSize() + " bytes, message is " +
                            size + " bytes.");
                    return false;
                }

                ndef.writeNdefMessage(message);
                return true;
            } else {
                NdefFormatable format = NdefFormatable.get(tag);
                if (format != null) {
                    try {
                        format.connect();
                        format.format(message);
                        return true;
                    } catch (IOException e) {
                        return false;
                    }
                } else {
                    return false;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to write tag", e);
        }

        Log.w(TAG, "Failed to write tag");
        return false;
    }
	
	/**
	 * Implements an Ndef push handover request in which a tag represents
	 * an Ndef reader device listening on a TCP socket.
	 *
	 */
	public class NdefTcpPushHandover implements ConnectionHandover {
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
		public void doConnectionHandover(NdefRecord handoverRequest) throws IOException {
			NdefMessage outboundNdef = mForegroundMessage;
			if (outboundNdef == null) return;
			
			String uriString = new String(handoverRequest.getPayload());
			Uri uri = Uri.parse(uriString);
			sendNdefOverTcp(uri, outboundNdef);
		}
		
		private void sendNdefOverTcp(Uri target, NdefMessage ndef) throws IOException {
			String host = target.getHost();
			int port = target.getPort();
			if (port == -1) {
				port = DEFAULT_TCP_HANDOVER_PORT;
			}
			
			DuplexSocket socket = new TcpDuplexSocket(host, port);
			HandoverConnectedThread connected = new HandoverConnectedThread(socket, ndef);
			connected.start();
		}
	}
		
	/**
	 * Implements an Ndef push handover request in which a tag represents
	 * an Ndef reader device listening on a Bluetooth socket.
	 *
	 */
	public class NdefBluetoothPushHandover implements ConnectionHandover {
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
		public void doConnectionHandover(NdefRecord handoverRequest) throws IOException {
			NdefMessage outboundNdef = mForegroundMessage;
			if (outboundNdef == null) return;
			
			String uriString = new String(handoverRequest.getPayload());
			Uri uri = Uri.parse(uriString);
			sendNdefOverBt(uri, outboundNdef);
		}
		
		private void sendNdefOverBt(Uri target, NdefMessage ndef) throws IOException {
			String mac = target.getAuthority();
			UUID uuid = UUID.fromString(target.getPath().substring(1));
			DuplexSocket socket = new BluetoothDuplexSocket(mac, uuid);
			HandoverConnectedThread connected = new HandoverConnectedThread(socket, ndef);
			connected.start();
		}
	}
		
	
	/**
	 * Runs a thread during a connection handover with a remote device over a
	 * {@see DuplexSocket}, transmitting the given Ndef message.
	 */
	private class HandoverConnectedThread extends Thread {
		private final DuplexSocket mmSocket;
		@SuppressWarnings("unused")
		private final InputStream mmInStream;
		private final OutputStream mmOutStream;
		private final NdefMessage mmOutboundMessage;
		
		public HandoverConnectedThread(DuplexSocket socket, NdefMessage ndef) {
			mmOutboundMessage = ndef;
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
				if (mmOutboundMessage != null) {
					byte[] ndefBytes = mmOutboundMessage.toByteArray();
					mmOutStream.write(ndefBytes);
				}
			} catch (Exception e) {
				Log.e(TAG, "Error writing to socket", e);
			}
			// No longer listening.
			cancel();
		}

		public void cancel() {
			try {
				mmSocket.close();
			} catch (IOException e) {
				Log.e(TAG, "close() of connect socket failed", e);
			}
		}
	}
	
	/**
	 * An interface for classes supporting communications established using
         * Nfc but with an out-of-band data transfer protocol.
	 */
	public interface ConnectionHandover {
		public void doConnectionHandover(NdefRecord handoverRequest) throws IOException;
		public boolean supportsRequest(NdefRecord record);
	}
	
	/**
	 * A wrapper for the standard Socket implementation.
	 *
	 */
	interface DuplexSocket extends Closeable {
		public InputStream getInputStream() throws IOException;
		public OutputStream getOutputStream() throws IOException;
		public void connect() throws IOException;
	}
	
	class BluetoothDuplexSocket implements DuplexSocket {
		final BluetoothSocket mSocket;

		public BluetoothDuplexSocket(String mac, UUID serviceUuid) throws IOException {
			BluetoothDevice device = BluetoothAdapter.getDefaultAdapter().getRemoteDevice(mac);
			mSocket = device.createInsecureRfcommSocketToServiceRecord(serviceUuid);
		}
		
		@Override
		public void connect() throws IOException {
			mSocket.connect();
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
	
	private class TcpDuplexSocket implements DuplexSocket {
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
	
	/**
	 * Sets the dialog to display when blocking for
	 * a tag read to complete.
	 */
	public void setReadTagDialog(Dialog dialog) {
		mReadTagDialog = dialog;
	}
	
	private class ReadTagTask extends AsyncTask<Void, Void, Void> {
		private final Dialog mmDialog;
		
		public ReadTagTask() {
			if (mReadTagDialog == null) {
				ProgressDialog dialog = new ProgressDialog(mActivity);
				dialog.setTitle("Scan tag now...");
				dialog.setIndeterminate(true);
				mmDialog = dialog;
			} else {
				mmDialog = mReadTagDialog;
			}
		}
		
		@Override
		protected void onPreExecute() {
			mmDialog.show();
		}
		
		@Override
		protected Void doInBackground(Void... params) {
			try {
				synchronized (Nfc.this) {
					mTagAction = ACTION_READ;
					while (mLastTagDiscoveredIntent == null) {
						Nfc.this.wait();
					}
					mLastTagDiscoveredIntent = null;
				}
			} catch (Exception e) {}
			return null;
		}
		
		@Override
		protected void onPostExecute(Void result) {
			mmDialog.hide();
		}
	}
	
	/**
	 * Sets the dialog to display when blocking to
	 * write a tag.
	 */
	
	public void setWriteTagDialog(Dialog dialog) {
		mWriteTagDialog = dialog;
	}
	
	private class WriteTagTask extends AsyncTask<NdefMessage, Void, Boolean> {
		private final Dialog mmDialog;
		
		public WriteTagTask() {
			if (mWriteTagDialog == null) {
				mmDialog = mWriteTagDialog;
			} else {
				ProgressDialog dialog = new ProgressDialog(mActivity);
				dialog.setTitle("Touch tag to write...");
				dialog.setIndeterminate(true);
				mmDialog = dialog;
			}
		}
		
		@Override
		protected void onPreExecute() {
			mmDialog.show();
		}
		
		@Override
		protected Boolean doInBackground(NdefMessage... params) {
			Boolean result = null;
			Tag tag = null;
			try {
				synchronized (Nfc.this) {
					mTagAction = ACTION_WRITE;
					while (mLastTagDiscoveredIntent == null) {
						Nfc.this.wait();
					}

					tag = mLastTagDiscoveredIntent.getParcelableExtra(NfcAdapter.EXTRA_TAG);
					mLastTagDiscoveredIntent = null;
				}
				
				NdefMessage ndef = params[0];
				result = writeTag(tag, ndef);
			} catch (Exception e) {}
			return result;
		}
		
		@Override
		protected void onPostExecute(Boolean result) {
			mmDialog.hide();
			if (result) {
				Toast.makeText(mActivity, "Wrote tag successfully.", Toast.LENGTH_SHORT).show();
			} else {
				Toast.makeText(mActivity, "Error writing tag.", Toast.LENGTH_SHORT).show();
			}
		}
	}
	
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
	}
}
