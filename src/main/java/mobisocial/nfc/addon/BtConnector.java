package mobisocial.nfc.addon;

import java.io.IOException;
import java.util.Arrays;

import mobisocial.nfc.Nfc;
import mobisocial.nfc.Nfc.NdefHandler;
import android.bluetooth.BluetoothSocket;
import android.nfc.NdefMessage;
import android.nfc.NdefRecord;

public abstract class BtConnector {
	public static final String TAG = "ndefserver";
	
	public static void prepare(Nfc nfc, OnBtConnectedListener conn) throws IOException {
		nfc.addNdefHandler(new BtConnecting(conn));
		nfc.share(getHandoverRequestMessage());
	}
	
	private static NdefMessage getHandoverRequestMessage() {
		return null;
	}

	public static boolean isBtConnectionRequest(NdefMessage[] ndef) {
		if (!Nfc.isHandoverRequest(ndef[0])) {
			return false;
		}
		
		// TODO: how to handle multiple handover records?
		NdefRecord handoverRequest = ndef[0].getRecords()[2];
		if (handoverRequest.getTnf() != NdefRecord.TNF_ABSOLUTE_URI
				|| !Arrays.equals(handoverRequest.getType(), NdefRecord.RTD_URI)) {
			return false;
		}
		
		String uriString = new String(handoverRequest.getPayload());
		if (uriString.startsWith("btsocket://")) {
			return true;
		}
		
		return false;
	}
	
	private static class BtConnecting implements NdefHandler {
		private final OnBtConnectedListener mmBtConnected;
		
		public BtConnecting(OnBtConnectedListener onBtConnected) {
			mmBtConnected = onBtConnected;
		}

		public int handleNdef(NdefMessage[] ndefMessages) {
			NdefMessage ndef = ndefMessages[0];
			
			if (!isBtConnectionRequest(ndefMessages)) {
				return NDEF_PROPAGATE;
			}
			
			mmBtConnected.onConnectionEstablished(null);
			return NDEF_CONSUME;
		}
	}
	
	public interface OnBtConnectedListener {
		public void onConnectionEstablished(BluetoothSocket socket);
		public void onError();
	}
}
