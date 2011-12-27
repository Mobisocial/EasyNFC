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
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Random;
import java.util.UUID;

import mobisocial.nfc.ConnectionHandover;
import mobisocial.nfc.NdefFactory;
import mobisocial.nfc.Nfc;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.net.Uri;
import android.nfc.NdefMessage;
import android.nfc.NdefRecord;
import android.os.Build.VERSION;
import android.os.Build.VERSION_CODES;
import android.util.Log;

import mobisocial.nfc.ConnectionHandoverManager;


/**
 * <p>Allows two devices to establish a Bluetooth connection after exchanging an NFC
 * Connection Handover Request. The socket is returned via callback.
 *
 * <p>A simple example for establishing a Bluetooth connection when both phones
 * are in the same activity:
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
 * <p>A more complex example, which supports:
 * <ul>
 *   <li>Pairing when both phones are in the same activity
 *   <li>Pairing when only one phone is in the activity
 *   <li>Providing a download link if your application is not yet installed.
 * </ul>
 *
 * <p>You should also ensure that Bluetooth and Nfc are enabled on the device.
 *
 * <pre class="prettyprint">
 * public class MyActivity extends Activity {
 *  private Nfc mNfc;
 *  private Long mLastPausedMillis = 0L;
 *
 *  public void onCreate(Bundle savedInstanceState) {
 *      super.onCreate(savedInstanceState);
 *      setContentView(R.layout.main);
 *      mNfc = new Nfc(this);
 *
 *      // If this activity was launched from an NFC interaction, start the
 *      // Bluetooth connection process.
 *      if (NfcAdapter.ACTION_NDEF_DISCOVERED.equals(getIntent().getAction())) {
 *          BluetoothConnector.join(mNfc, mBluetoothConnected, getNdefMessages(getIntent())[0]);
 *      } else {
 *          // If both phones are running this activity, or to allow remote
 *          // device to join from home screen.
 *          BluetoothConnector.prepare(mNfc, mBluetoothConnected, getAppReference());
 *      }
 *  }
 *
 *  protected void onResume() {
 *      super.onResume();
 *      mNfc.onResume(this);
 *  }
 *
 *  protected void onPause() {
 *      super.onPause();
 *      mLastPausedMillis = System.currentTimeMillis();
 *      mNfc.onPause(this);
 *  }
 *
 *  protected void onNewIntent(Intent intent) {
 *      // Check for "warm boot" if the activity uses singleInstance launch mode:
 *      if (NfcAdapter.ACTION_NDEF_DISCOVERED.equals(intent.getAction())) {
 *          Long ms = System.currentTimeMillis() - mLastPausedMillis;
 *          if (ms > 150) {
 *              BluetoothConnector.join(mNfc, mBluetoothConnected, getNdefMessages(intent)[0]);
 *              return;
 *          }
 *      }
 *      if (mNfc.onNewIntent(this, intent)) {
 *          return;
 *      }
 *  }
 *
 *  public NdefRecord[] getAppReference() {
 *      byte[] urlBytes = "http://example.com/funapp".getBytes();
 *      NdefRecord ref = new NdefRecord(NdefRecord.TNF_ABSOLUTE_URI, NdefRecord.RTD_URI, new byte[]{}, urlBytes);
 *      return new NdefRecord[] { ref };
 *  }
 *
 *  OnConnectedListener mBluetoothConnected = new OnConnectedListener() {
 *      public void onConnectionEstablished(BluetoothSocket socket, boolean isServer) {
 *          toast("connected! server: " + isServer);
 *      }
 *  };
 *
 *  private void toast(final String text) {
 *      runOnUiThread(new Runnable() {
 *          public void run() {
 *              Toast.makeText(MyActivity.this, text, Toast.LENGTH_SHORT).show();
 *          }
 *      });
 *  }
 *
 *  private NdefMessage[] getNdefMessages(Intent intent) {
 *      if (!intent.hasExtra(NfcAdapter.EXTRA_NDEF_MESSAGES)) {
 *          return null;
 *      }
 *      Parcelable[] msgs = intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES);
 *      NdefMessage[] ndef = new NdefMessage[msgs.length];
 *      for (int i = 0; i < msgs.length; i++) {
 *          ndef[i] = (NdefMessage) msgs[i];
 *      }
 *      return ndef;
 *  }
 * }
 * </pre>
 *
 * You will also need to add an intent filter to your application's manifest:
 * <pre class="prettyprint">
 *   &lt;activity android:name=".MyActivity"&gt;
 *        &lt;intent-filter&gt;
 *            &lt;action android:name="android.nfc.action.NDEF_DISCOVERED" /&gt;
 *            &lt;category android:name="android.intent.category.DEFAULT" /&gt;
 *            &lt;data android:scheme="http"
 *                  android:host="example.com"
 *                  android:path="/funapp" /&gt;
 *        &lt;/intent-filter&gt;
 *   &lt;/activity&gt;
 * </pre>
 *
 * For devices supporting SDK 14 and above, the handover record also includes
 * an Android Application Record, allowing your application to be discovered in
 * the market if it is not yet installed. Otherwise, the uri provided by
 * getAppReference() should direct the user to a web page relevant to your
 * application.
 */
public abstract class BluetoothConnector {
    private static final String SERVICE_NAME = "NfcBtHandover";
    private static final String BT_SOCKET_SCHEMA = "btsocket://";
    private static final String TAG = "btconnect";
    private static final boolean DBG = false;

    /**
     * Configures the {@link mobisocial.nfc.Nfc} interface to set up a Bluetooth
     * socket with another device. The method both sets the foreground ndef
     * messages and registers an {@link mobisocial.nfc.NdefHandler} to look for
     * incoming pairing requests.
     * 
     * <p>When this method is called, a Bluetooth server socket is created,
     * and the socket is closed after a successful connection. You must call
     * prepare() again to reinitiate the server socket.
     * 
     * @return The server socket listening for peers.
     */
    public static BluetoothServerSocket prepare(Nfc nfc, OnConnectedListener conn) {
        BluetoothConnecting btConnecting = new BluetoothConnecting(conn);
        nfc.getConnectionHandoverManager().addConnectionHandover(btConnecting);
        nfc.share(btConnecting.getHandoverRequestMessage(nfc.getContext()));
        return btConnecting.mAcceptThread.mmServerSocket;
    }

    /**
     * Configures the {@link mobisocial.nfc.Nfc} interface to set up a Bluetooth
     * socket with another device. The method both sets the foreground ndef
     * messages and registers an {@link mobisocial.nfc.NdefHandler} to look for
     * incoming pairing requests.
     * 
     * <p>When this method is called, a Bluetooth server socket is created,
     * and the socket is closed after a successful connection. You must call
     * prepare() again to reinitiate the server socket.
     * 
     * @return The server socket listening for peers.
     */
    public static BluetoothServerSocket prepare(Nfc nfc, OnConnectedListener conn, NdefRecord[] ndef) {
        BluetoothConnecting btConnecting = new BluetoothConnecting(conn);
        NdefMessage handoverRequest = btConnecting.getHandoverRequestMessage(nfc.getContext());
        NdefRecord[] combinedRecords = new NdefRecord[ndef.length + handoverRequest.getRecords().length];

        int i = 0;
        for (NdefRecord r : ndef) {
            combinedRecords[i++] = r;
        }
        for (NdefRecord r : handoverRequest.getRecords()) {
            combinedRecords[i++] = r;
        }

        NdefMessage outbound = new NdefMessage(combinedRecords);
        nfc.getConnectionHandoverManager().addConnectionHandover(btConnecting);
        nfc.share(outbound);
        return btConnecting.mAcceptThread.mmServerSocket;
    }

    /**
     * Extracts the Bluetooth socket information from an ndef message and
     * connects as a client.
     */
    public static void join(Nfc nfc, OnConnectedListener conn, NdefMessage ndef) {
        BluetoothConnecting btConnecting = new BluetoothConnecting(conn, true, UUID.randomUUID());
        ConnectionHandoverManager manager = new ConnectionHandoverManager();
        manager.addConnectionHandover(btConnecting);
        manager.doHandover(ndef);
    }

    private static class BluetoothConnecting implements ConnectionHandover {
        private final AcceptThread mAcceptThread;
        private final byte[] mCollisionResolution;
        private final OnConnectedListener mmBtConnected;
        private final BluetoothAdapter mBluetoothAdapter;
        private final UUID mServiceUuid;
        private final int mChannel;
        private final boolean mAlwaysClient;
        private boolean mConnectionStarted;

        public BluetoothConnecting(OnConnectedListener onBtConnected, boolean alwaysClient,
                UUID serviceUuid) {
            mAlwaysClient = alwaysClient;
            mmBtConnected = onBtConnected;
            mServiceUuid = serviceUuid;
            mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
            if (mBluetoothAdapter == null) {
                throw new IllegalStateException("No Bluetooth adapter found.");
            }
            Random random = new Random();
            mCollisionResolution = new byte[2];
            random.nextBytes(mCollisionResolution);

            mAcceptThread = new AcceptThread();
            mChannel = mAcceptThread.getListeningPort();
            mAcceptThread.start();
        }

        public BluetoothConnecting(OnConnectedListener onBtConnected) {
            this(onBtConnected, false, UUID.randomUUID());
        }

        private NdefMessage getHandoverRequestMessage(Context context) {
            NdefRecord[] records = new NdefRecord[4];

            /* Handover Request */
            byte[] version = new byte[] {
                (0x1 << 4) | (0x2)
            };
            records[0] = new NdefRecord(NdefRecord.TNF_WELL_KNOWN, NdefRecord.RTD_HANDOVER_REQUEST,
                    new byte[0], version);

            /* Collision Resolution */
            records[1] = new NdefRecord(NdefRecord.TNF_WELL_KNOWN, new byte[] {
                    0x63, 0x72
            }, new byte[0], mCollisionResolution);

            /* Handover record */
            StringBuilder btRequest = new StringBuilder(BT_SOCKET_SCHEMA)
                .append(mBluetoothAdapter.getAddress())
                .append("/")
                .append(mServiceUuid);
            if (mChannel != -1) {
                btRequest.append("?channel=" + mChannel);
            }
            records[2] = new NdefRecord(NdefRecord.TNF_ABSOLUTE_URI, NdefRecord.RTD_URI,
                    new byte[0], btRequest.toString().getBytes());

            records[3] = NdefFactory.createApplicationRecord(context.getPackageName());
            NdefMessage ndef = new NdefMessage(records);
            return ndef;
        }

        @Override
        public void doConnectionHandover(NdefMessage handoverRequest, int handover, int record)
                throws IOException {

            byte[] remoteCollision = handoverRequest.getRecords()[handover + 1].getPayload();
            if (remoteCollision[0] == mCollisionResolution[0]
                    && remoteCollision[1] == mCollisionResolution[1]) {
                return; // They'll have to try again.
            }
            boolean amServer = (remoteCollision[0] < mCollisionResolution[0] ||
                    (remoteCollision[0] == mCollisionResolution[0] && remoteCollision[1] < mCollisionResolution[1]));

            if (mAlwaysClient) {
                amServer = false;
            }

            if (!mConnectionStarted) {
                synchronized(BluetoothConnecting.this) {
                    if (!mConnectionStarted) {
                        mConnectionStarted = true;
                        mmBtConnected.beforeConnect(amServer);
                    }
                }
            }
            if (!amServer) {
                // Not waiting for a connection:
                mAcceptThread.cancel();
                Uri uri = Uri.parse(new String(handoverRequest.getRecords()[record].getPayload()));
                UUID serviceUuid = UUID.fromString(uri.getPath().substring(1));
                int channel = -1;
                String channelStr = uri.getQueryParameter("channel");
                if (null != channelStr) {
                    channel = Integer.parseInt(channelStr);
                }

                BluetoothDevice remoteDevice = mBluetoothAdapter.getRemoteDevice(uri.getAuthority());
                new ConnectThread(remoteDevice, serviceUuid, channel).start();
            }
        }

        @Override
        public boolean supportsRequest(NdefRecord handoverRequest) {
            if (handoverRequest.getTnf() != NdefRecord.TNF_ABSOLUTE_URI
                    || !Arrays.equals(handoverRequest.getType(), NdefRecord.RTD_URI)) {
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

            public ConnectThread(BluetoothDevice device, UUID uuid, int channel) {
                mmDevice = device;

                BluetoothSocket tmp = null;
                try {
                    tmp = createBluetoothSocket(mmDevice, uuid, channel);
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
            private final int mmListeningPort;

            private AcceptThread() {
                BluetoothServerSocket tmp = null;

                // Create a new listening server socket
                int listeningPort = -1;
                try {
                    tmp = getBluetoothServerSocket();
                    listeningPort = getBluetoothListeningPort(tmp);
                } catch (IOException e) {
                    Log.e(TAG, "listen() failed", e);
                }
                mmListeningPort = listeningPort;
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
                    if (!mConnectionStarted) {
                        synchronized(BluetoothConnecting.this) {
                            if (!mConnectionStarted) {
                                mConnectionStarted = true;
                                mmBtConnected.beforeConnect(true);
                            }
                        }
                    }
                } catch (IOException e) {
                    if (DBG) Log.e(TAG, "accept() failed", e);
                    return;
                }

                if (socket == null) {
                    return;
                }

                try {
                    mmServerSocket.close();
                } catch (IOException e) {
                }
                mmBtConnected.onConnectionEstablished(socket, true);
            }

            public void cancel() {
                try {
                    mmServerSocket.close();
                } catch (IOException e) {
                }
            }

            public int getListeningPort() {
                return mmListeningPort;
            }
        }

        private BluetoothServerSocket getBluetoothServerSocket() throws IOException {
            BluetoothServerSocket tmp;

            if (VERSION.SDK_INT < VERSION_CODES.GINGERBREAD_MR1) {
                tmp = mBluetoothAdapter.listenUsingRfcommWithServiceRecord(SERVICE_NAME,
                        mServiceUuid);
                if (DBG) Log.d(TAG, "Using secure bluetooth server socket");
            } else {
                try {
                    // compatibility with pre SDK 10 devices
                    Method listener = mBluetoothAdapter.getClass().getMethod(
                            "listenUsingInsecureRfcommWithServiceRecord", String.class, UUID.class);
                    tmp = (BluetoothServerSocket) listener.invoke(mBluetoothAdapter, SERVICE_NAME, mServiceUuid);
                    if (DBG) Log.d(TAG, "Using insecure bluetooth server socket");
                } catch (NoSuchMethodException e) {
                    Log.wtf(TAG, "listenUsingInsecureRfcommWithServiceRecord not found");
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

        private BluetoothSocket createBluetoothSocket(BluetoothDevice device, UUID uuid, int channel)
                throws IOException {

            BluetoothSocket tmp;

            if (channel != -1) {
                try {
                    if (DBG) Log.d(TAG, "trying to connect to channel " + channel);
                    Method listener = device.getClass().getMethod("createInsecureRfcommSocket", int.class);
                    return (BluetoothSocket) listener.invoke(device, channel);
                } catch (Exception e) {
                    if (DBG) Log.w(TAG, "Could not connect to channel.", e);
                }
            }

            if (VERSION.SDK_INT < VERSION_CODES.GINGERBREAD_MR1) {
                tmp = device.createRfcommSocketToServiceRecord(uuid);
                if (DBG) Log.d(TAG, "Using secure bluetooth socket");
            } else {
                try {
                    // compatibility with pre SDK 10 devices
                    Method listener = device.getClass().getMethod(
                            "createInsecureRfcommSocketToServiceRecord", UUID.class);
                    tmp = (BluetoothSocket) listener.invoke(device, uuid);
                    if (DBG) Log.d(TAG, "Using insecure bluetooth socket");
                } catch (NoSuchMethodException e) {
                    Log.wtf(TAG, "createInsecureRfcommSocketToServiceRecord not found");
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

        private static int getBluetoothListeningPort(BluetoothServerSocket serverSocket) {
            try {
                Field socketField = BluetoothServerSocket.class.getDeclaredField("mSocket");
                socketField.setAccessible(true);
                BluetoothSocket socket = (BluetoothSocket)socketField.get(serverSocket);

                Field portField = BluetoothSocket.class.getDeclaredField("mPort");
                portField.setAccessible(true);
                int port = (Integer)portField.get(socket);
                return port;
            } catch (Exception e) {
                Log.d(TAG, "Error getting port from socket", e);
                return -1;
            }
        }
    }

    /**
     * A callback used when a Bluetooth connection has been established.
     */
    public interface OnConnectedListener {
        /**
         * The method called when a Bluetooth connection has been established.
         * 
         * @param socket The connected Bluetooth socket.
         * @param isServer True if this connection is the "host" of this
         *            connection. Useful in establishing an asymmetric
         *            relationship between otherwise symmetric devices.
         */
        public void onConnectionEstablished(BluetoothSocket socket, boolean isServer);

        /**
         * Called before an attempt to set up a Bluetooth connection.
         * @param isServer True if this connection is the "host" of this
         *            connection. Useful in establishing an asymmetric
         *            relationship between otherwise symmetric devices.
         */
        public void beforeConnect(boolean isServer);
    }
}
