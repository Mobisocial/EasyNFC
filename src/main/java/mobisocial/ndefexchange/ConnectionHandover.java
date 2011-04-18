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

package mobisocial.ndefexchange;

import java.io.IOException;


import android.nfc.NdefMessage;
import android.nfc.NdefRecord;

/**
 * An interface for classes supporting communication established using
 * Nfc but with an out-of-band data transfer.
 */
public interface ConnectionHandover {
	/**
	 * Issues a connection handover of the given type.
	 * @param handoverRequest The connection handover request message.
	 * @param recordNumber The index of the handover record entry being attempted.
	 * @param nfcInterface The Nfc interface for sending and receiving ndef messages.
	 * @throws IOException
	 */
	public void doConnectionHandover(NdefMessage handoverRequest, int recordNumber, NdefExchangeContract nfcInterface) throws IOException;
	public boolean supportsRequest(NdefRecord record);
}
