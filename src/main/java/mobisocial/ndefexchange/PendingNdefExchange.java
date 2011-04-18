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

import mobisocial.nfc.ConnectionHandoverManager;
import android.nfc.NdefMessage;

/**
 * An Ndef Exchange connection handover that is ready to be executed.
 *
 */
public class PendingNdefExchange {
	private final NdefMessage mHandover;
	private final ConnectionHandoverManager mConnectionHandoverManager;

	public PendingNdefExchange(NdefMessage handover, NdefExchangeContract nfcInterface) {
		mHandover = handover;
		mConnectionHandoverManager = new ConnectionHandoverManager(nfcInterface);
	}
	
	public void exchangeNdef(NdefMessage ndef) {
		mConnectionHandoverManager.doHandover(mHandover, ndef);
	}
}
