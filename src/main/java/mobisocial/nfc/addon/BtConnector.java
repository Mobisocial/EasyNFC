package mobisocial.nfc.addon;

import java.io.IOException;
import java.util.Arrays;
import java.util.Random;
import java.util.UUID;

import mobisocial.nfc.Nfc;
import mobisocial.nfc.Nfc.NdefHandler;
import mobisocial.nfc.Nfc.NfcInterface;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothSocket;
import android.nfc.NdefMessage;
import android.nfc.NdefRecord;

public abstract class BtConnector {
	public static final String SERVICE_NAME = "NfcBtHandover";
	public static final String BT_SOCKET_SCHEMA = "btsocket://";

	/**
	 * Configures the {@link Nfc} interface to set up a bluetooth socket
	 * with another device. The method both sets the foreground ndef message
	 * and registers an {@link NdefHandler} to look for incoming pairing
	 * requests.
	 */
	public static void prepare(Nfc nfc, OnBtConnectedListener conn) throws IOException {
		BtConnecting btConnecting = new BtConnecting(conn);
		nfc.getConnectionHandoverManager().addConnectionHandover(btConnecting);
		nfc.share(btConnecting.getHandoverRequestMessage());
	}

	private static class BtConnecting implements Nfc.ConnectionHandover {
		private final byte[] mCollisionResolution;
		private final OnBtConnectedListener mmBtConnected;
		private final BluetoothAdapter mBluetoothAdapter;
		private final UUID mServiceUuid;
		
		public BtConnecting(OnBtConnectedListener onBtConnected) {
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
			records[0] = new NdefRecord(NdefRecord.TNF_WELL_KNOWN, NdefRecord.RTD_HANDOVER_REQUEST, new byte[0], version);
			
			/* Collision Resolution */
	    	records[1] = new NdefRecord(NdefRecord.TNF_WELL_KNOWN, new byte[] {0x63, 0x72}, new byte[0], mCollisionResolution);
			
	    	/* Handover record */
	    	String btRequest = BT_SOCKET_SCHEMA + mBluetoothAdapter.getAddress() + "/" + mServiceUuid;
	    	records[2] = new NdefRecord(NdefRecord.TNF_ABSOLUTE_URI, NdefRecord.RTD_URI, new byte[0], btRequest.getBytes());
	    	
			NdefMessage ndef = new NdefMessage(records);
			return ndef;
		}

		@Override
		public void doConnectionHandover(NdefMessage handoverRequest, int record,
				NfcInterface nfcInterface) throws IOException {
			
			byte[] remoteCollision = handoverRequest.getRecords()[1].getPayload();
			if (remoteCollision[0] == mCollisionResolution[0] &&
					remoteCollision[1] == mCollisionResolution[1]) {
				return; // They'll have to try again.
			}
			boolean amServer = 
				(remoteCollision[0] < mCollisionResolution[0] ||
					(remoteCollision[0] == mCollisionResolution[0] &&
							remoteCollision[1] < mCollisionResolution[1]));
			
			if (amServer) {
				// TODO: AcceptThread
			} else {
				// TODO: ConnectThread
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
	}

	/**
	 * A callback for when a Bluetooth connection has been established.
	 *
	 */
	public interface OnBtConnectedListener {
		public void onConnectionEstablished(BluetoothSocket socket);
		public void onError();
	}
}
