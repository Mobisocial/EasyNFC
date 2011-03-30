package mobisocial.nfc.addon;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URI;
import java.util.Arrays;
import java.util.Random;
import java.util.UUID;

import mobisocial.nfc.Nfc;
import mobisocial.nfc.Nfc.NdefHandler;
import mobisocial.nfc.Nfc.NfcInterface;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.Intent;
import android.content.pm.ResolveInfo;
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
public class MultiPlayerConnector implements Nfc.NdefHandler {
	NfcInterface mNfcInterface;
	
	public MultiPlayerConnector(NfcInterface nfcInterface) {
		mNfcInterface = nfcInterface;
	}
	
	public interface MultiPlayerMenu {
		public String[] getLabels();
		public Intent getIntentForItem(int pos);
	}

	@Override
	public int handleNdef(NdefMessage[] ndefMessages) {
		//if (ndefMessages[0])
		return NDEF_PROPAGATE;
	}
	
	
	// whiteboard::onCreate()
	//    MultiPlayerConnector.registerMenu(mMenu);
	// mMenu.getLabels: return new String[][] { { "0", "1", "2" }, { "Join %s's whiteboard", "Save %s's whiteboard", "Discard %s's whiteboard" } };
	// mMenu.getIntentForItem(int pos): if (pos == 0) ... 
}