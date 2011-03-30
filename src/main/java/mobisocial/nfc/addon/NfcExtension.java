package mobisocial.nfc.addon;

import mobisocial.nfc.Nfc.NfcInterface;
import android.nfc.NdefMessage;

public interface NfcExtension {
	public int handleNdef(NdefMessage[] ndefMessages);
	
	public void init(NfcInterface nfc);
	public void activate();
	
	public void setForegroundNdef(NdefMessage[] ndefs);
}
