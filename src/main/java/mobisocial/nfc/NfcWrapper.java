package mobisocial.nfc;

import android.content.Context;
import android.nfc.NfcAdapter;
import android.os.Build;

/**
 * Provides an abstraction for the various Nfc classes
 * introduced in Android API 9 and beyond.
 *
 */
public abstract class NfcWrapper {
	public static int SDK_NDEF_EXCHANGE = 10;
	public static int SDK_NDEF_DEFINED = 9;

	public static NfcWrapper getInstance() {
		if  (Build.VERSION.SDK_INT >= SDK_NDEF_EXCHANGE) {
			return NfcWrapper233.LazyHolder.sInstance;
		} else {
			return null;
		}
	}
	
	public abstract NfcAdapter getAdapter(Context c);
	

	private static class NfcWrapper233 extends NfcWrapper {
		private static class LazyHolder {
			private static final NfcWrapper sInstance = new NfcWrapper233();
		}

		@Override
		public NfcAdapter getAdapter(Context c) {
			return NfcAdapter.getDefaultAdapter(c);
		}
	}

}