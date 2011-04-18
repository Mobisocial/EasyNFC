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

import android.nfc.NdefMessage;

/**
 * A callback issued when an Nfc tag is read.
 */
public interface NdefHandler {
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