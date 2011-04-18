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

import android.content.Context;
import android.nfc.NfcAdapter;
import android.os.Build;

/**
 * Wraps the NfcAdapter class for use on platforms not supporting
 * native NFC interactions.
 */
abstract class NfcWrapper {
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