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

package mobisocial.nfc.addon;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URI;
import java.util.Arrays;
import java.util.Random;
import java.util.UUID;

import mobisocial.nfc.ConnectionHandover;
import mobisocial.nfc.Nfc;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.nfc.NdefMessage;
import android.nfc.NdefRecord;
import android.os.Build.VERSION;
import android.os.Build.VERSION_CODES;
import android.util.Log;

/**
 * Allows two devices to establish a Bluetooth connection after exchanging an NFC
 * Connection Handover Request. The socket is returned via callback. Example usage:
 * 
 * <pre class="prettyprint">
 * MyActivity extends Activity {
 *   Nfc mNfc;
 *   
 *   BluetoothConnector.OnConnectedListener mBtListener =
 *           new BluetoothConnector.OnConnectedListener() {
 *       
 *       public void onConnectionEstablished(BluetoothSocket socket, 
 *               boolean isServer) {
 *          Log.d(TAG, "Connected over Bluetooth as " +
 *              (isServer ? "server" : "client"));
 *       }
 *   }
 *   
 *   public void onCreate(Bundle bundle) {
 *     super.onCreate(bundle);
 *     mNfc = new Nfc(this);
 *     BluetoothConnector.prepare(mNfc, mBtListener);
 *   }
 *
 *   public void onResume() {
 *     super.onResume();
 *     mNfc.onResume(this);
 *   }
 *   
 *   public void onPause() {
 *     super.onPause();
 *     mNfc.onPause();
 *   }
 *   
 *   public void onNewInent(Intent intent) {
 *     if (mNfc.onNewIntent(this, intent)) return;
 *   }
 * }
 * </pre>
 *
 */
public abstract class BluetoothConnector {
	private static final String SERVICE_NAME = "NfcBtHandover";
	private static final String BT_SOCKET_SCHEMA = "btsocket://";
	private static final String TAG = "btconnect";

	/**
	 * Configures the {@link Nfc} interface to set up a bluetooth socket with
	 * another device. The method both sets the foreground ndef message and
	 * registers an {@link NdefHandler} to look for incoming pairing requests.
	 */
	public static void prepare(Nfc nfc, OnConnectedListener conn) {
		BluetoothConnecting btConnecting = new BluetoothConnecting(conn);
		nfc.getConnectionHandoverManager().addConnectionHandover(btConnecting);
		nfc.share(btConnecting.getHandoverRequestMessage());
	}

	private static class BluetoothConnecting implements ConnectionHandover {
		private final byte[] mCollisionResolution;
		private final OnConnectedListener mmBtConnected;
		private final BluetoothAdapter mBluetoothAdapter;
		private final UUID mServiceUuid;

		public BluetoothConnecting(OnConnectedListener onBtConnected) {
			mmBtConnected = onBtConnected;
			mServiceUuid = UUID.randomUUID();
			mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
			if (mBluetoothAdapter == null) {
				throw new IllegalStateException("No Bluetooth adapter found.");
			}
			Random random = new Random();
			mCollisionResolution = new byte[2];
			random.nextBytes(mCollisionResolution);
		}

		private NdefMessage getHandoverRequestMessage() {
			NdefRecord[] records = new NdefRecord[3];

			/* Handover Request */
			byte[] version = new byte[] { (0x1 << 4) | (0x2) };
			records[0] = new NdefRecord(NdefRecord.TNF_WELL_KNOWN,
					NdefRecord.RTD_HANDOVER_REQUEST, new byte[0], version);

			/* Collision Resolution */
			records[1] = new NdefRecord(NdefRecord.TNF_WELL_KNOWN, new byte[] {
					0x63, 0x72 }, new byte[0], mCollisionResolution);

			/* Handover record */
			String btRequest = BT_SOCKET_SCHEMA
					+ mBluetoothAdapter.getAddress() + "/" + mServiceUuid;
			records[2] = new NdefRecord(NdefRecord.TNF_ABSOLUTE_URI,
					NdefRecord.RTD_URI, new byte[0], btRequest.getBytes());

			NdefMessage ndef = new NdefMessage(records);
			return ndef;
		}

		@Override
		public void doConnectionHandover(NdefMessage handoverRequest, int record) throws IOException {

			byte[] remoteCollision = handoverRequest.getRecords()[1]
					.getPayload();
			if (remoteCollision[0] == mCollisionResolution[0]
					&& remoteCollision[1] == mCollisionResolution[1]) {
				return; // They'll have to try again.
			}
			boolean amServer = (remoteCollision[0] < mCollisionResolution[0] || (remoteCollision[0] == mCollisionResolution[0] && remoteCollision[1] < mCollisionResolution[1]));

			if (amServer) {
				new AcceptThread().start();
			} else {
				URI uri = URI.create(new String(handoverRequest.getRecords()[record].getPayload()));
				UUID serviceUuid = UUID.fromString(uri.getPath().substring(1));
				BluetoothDevice remoteDevice = mBluetoothAdapter.getRemoteDevice(uri.getAuthority());
				new ConnectThread(remoteDevice, serviceUuid).start();
			}
		}

		@Override
		public boolean supportsRequest(NdefRecord handoverRequest) {
			if (handoverRequest.getTnf() != NdefRecord.TNF_ABSOLUTE_URI
					|| !Arrays.equals(handoverRequest.getType(),
							NdefRecord.RTD_URI)) {
				return false;
			}

			String uriString = new String(handoverRequest.getPayload());
			if (uriString.startsWith(BT_SOCKET_SCHEMA)) {
				return true;
			}

			return false;
		}

		private class ConnectThread extends Thread {
			private final BluetoothSocket mmSocket;
			private final BluetoothDevice mmDevice;

			public ConnectThread(BluetoothDevice device, UUID uuid) {
				mmDevice = device;

				BluetoothSocket tmp = null;
				try {
					tmp = createBluetoothSocket(mmDevice, uuid);
				} catch (IOException e) {
					Log.e(TAG, "create() failed", e);
				}
				mmSocket = tmp;
			}

			public void run() {
				setName("ConnectThread");

				try {
					mmSocket.connect();
				} catch (IOException e) {
					Log.e(TAG, "failed to connect to bluetooth socket", e);
					try {
						mmSocket.close();
					} catch (IOException e2) {
						Log.e(TAG, "unable to close() socket during connection failure", e2);
					}

					return;
				}

				mmBtConnected.onConnectionEstablished(mmSocket, false);
			}
		}

		private class AcceptThread extends Thread {
			// The local server socket
			private final BluetoothServerSocket mmServerSocket;

			public AcceptThread() {
				BluetoothServerSocket tmp = null;

				// Create a new listening server socket
				try {
					tmp = getListeningBluetoothServerSocket();
				} catch (IOException e) {
					Log.e(TAG, "listen() failed", e);
				}
				mmServerSocket = tmp;
			}

			public void run() {
				setName("AcceptThread");
				BluetoothSocket socket = null;

				// Wait for one connection.
				try {
					// This is a blocking call and will only return on a
					// successful connection or an exception
					socket = mmServerSocket.accept();
				} catch (IOException e) {
					Log.e(TAG, "accept() failed", e);
					return;
				}

				if (socket == null) {
					return;
				}

				try {
					mmServerSocket.close();
				} catch (IOException e) {}
				mmBtConnected.onConnectionEstablished(socket, true);
			}
		}

		private BluetoothServerSocket getListeningBluetoothServerSocket()
				throws IOException {
			BluetoothServerSocket tmp;
			if (VERSION.SDK_INT < VERSION_CODES.GINGERBREAD_MR1) {
				tmp = mBluetoothAdapter.listenUsingRfcommWithServiceRecord(
						SERVICE_NAME, mServiceUuid);
				Log.d(TAG, "Using secure bluetooth server socket");
			} else {
				try {
					// compatibility with pre SDK 10 devices
					Method listener = mBluetoothAdapter.getClass().getMethod(
							"listenUsingInsecureRfcommWithServiceRecord",
							String.class, UUID.class);
					tmp = (BluetoothServerSocket) listener.invoke(
							mBluetoothAdapter, SERVICE_NAME, mServiceUuid);
					Log.d(TAG, "Using insecure bluetooth server socket");
				} catch (NoSuchMethodException e) {
					Log.wtf(TAG,
							"listenUsingInsecureRfcommWithServiceRecord not found");
					throw new IOException(e);
				} catch (InvocationTargetException e) {
					Log.wtf(TAG,
							"listenUsingInsecureRfcommWithServiceRecord not available on mBtAdapter");
					throw new IOException(e);
				} catch (IllegalAccessException e) {
					Log.wtf(TAG,
							"listenUsingInsecureRfcommWithServiceRecord not available on mBtAdapter");
					throw new IOException(e);
				}
			}
			return tmp;
		}

		private BluetoothSocket createBluetoothSocket(BluetoothDevice device,
				UUID uuid) throws IOException {

			BluetoothSocket tmp;
			if (VERSION.SDK_INT < VERSION_CODES.GINGERBREAD_MR1) {
				tmp = device.createRfcommSocketToServiceRecord(uuid);
				Log.d(TAG, "Using secure bluetooth socket");
			} else {
				try {
					// compatibility with pre SDK 10 devices
					Method listener = device.getClass().getMethod(
							"createInsecureRfcommSocketToServiceRecord",
							UUID.class);
					tmp = (BluetoothSocket) listener.invoke(device, uuid);
					Log.d(TAG, "Using insecure bluetooth socket");
				} catch (NoSuchMethodException e) {
					Log.wtf(TAG,
							"createInsecureRfcommSocketToServiceRecord not found");
					throw new IOException(e);
				} catch (InvocationTargetException e) {
					Log.wtf(TAG,
							"createInsecureRfcommSocketToServiceRecord not available on mBtAdapter");
					throw new IOException(e);
				} catch (IllegalAccessException e) {
					Log.wtf(TAG,
							"createInsecureRfcommSocketToServiceRecord not available on mBtAdapter");
					throw new IOException(e);
				}
			}
			return tmp;
		}
	}

	/**
	 * A callback used when a Bluetooth connection has been established.
	 * 
	 */
	public interface OnConnectedListener {
		/**
		 * The method called when a Bluetooth connection has been established.
		 * @param socket The connected Bluetooth socket.
		 * @param isServer True if this connection is the "host" of this connection.
		 * Useful in establishing an asymmetric relationship between otherwise
		 * symmetric devices.
		 */
		public void onConnectionEstablished(BluetoothSocket socket, boolean isServer);
	}
}