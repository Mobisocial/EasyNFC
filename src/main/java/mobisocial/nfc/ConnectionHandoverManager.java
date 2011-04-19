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

import java.io.IOException;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;

import mobisocial.ndefexchange.NdefBluetoothPushHandover;
import mobisocial.ndefexchange.NdefExchangeContract;
import mobisocial.ndefexchange.NdefTcpPushHandover;

import android.net.Uri;
import android.nfc.NdefMessage;
import android.nfc.NdefRecord;
import android.util.Log;
import android.widget.Toast;

public class ConnectionHandoverManager implements NdefHandler, PrioritizedHandler {
	public static final String USER_HANDOVER_PREFIX = "ndef://wkt:hr/";
	public static final String TAG = "connectionhandover";
	public static final int HANDOVER_PRIORITY = 5;
	private final Set<ConnectionHandover> mmConnectionHandovers = new LinkedHashSet<ConnectionHandover>();
	private final NdefExchangeContract mNfc;

	public ConnectionHandoverManager(NdefExchangeContract nfc) {
		mNfc = nfc;
		mmConnectionHandovers.add(new NdefBluetoothPushHandover(nfc));
		mmConnectionHandovers.add(new NdefTcpPushHandover(nfc));
	}
	
	public void addConnectionHandover(ConnectionHandover handover) {
		mmConnectionHandovers.add(handover);
	}
	
	public void clearConnectionHandovers() {
		mmConnectionHandovers.clear();
	}
	
	public boolean removeConnectionHandover(ConnectionHandover handover) {
		return mmConnectionHandovers.remove(handover);
	}

	/**
	 * Returns the (mutable) set of active connection handover
	 * responders.
	 */
	public Set<ConnectionHandover> getConnectionHandoverResponders() {
		return mmConnectionHandovers;
	}
	
	//@Override
	public final int handleNdef(NdefMessage[] handoverRequest) {
		// TODO: What does this mean?
		return doHandover(handoverRequest[0], mNfc.getForegroundNdefMessage());
	}

	public final int doHandover(NdefMessage handoverRequest, final NdefMessage outboundNdef) {
		if (!isHandoverRequest(handoverRequest)) {
			return NDEF_PROPAGATE;
		}

		if (isUserspaceHandoverRequest(handoverRequest)) {
			// TODO: Move to NdefFactory or similar
			Uri uri = Uri.parse(new String(handoverRequest.getRecords()[0].getPayload()));
			handoverRequest = NdefFactory.fromNdefUri(uri);
		}

		NdefRecord[] records = handoverRequest.getRecords();
		for (int i = 2; i < records.length; i++) {
			Iterator<ConnectionHandover> handovers = mmConnectionHandovers.iterator();
			while (handovers.hasNext()) {
				ConnectionHandover handover = handovers.next();
				if (handover.supportsRequest(records[i])) {
					try {
						Log.d(TAG, "Attempting handover " + handover);
						handover.doConnectionHandover(handoverRequest, i);
						return NDEF_CONSUME;
					} catch (IOException e) {
						Log.w(TAG, "Handover failed.", e);
						// try the next one.
					}
				}
			}
		}

		return NDEF_PROPAGATE;
	}

	//@Override
	public int getPriority() {
		return HANDOVER_PRIORITY;
	}

	/**
	 * Returns true if the given Ndef message contains a connection
	 * handover request.
	 */
	public static boolean isHandoverRequest(NdefMessage ndef) {
		NdefRecord[] records = (ndef).getRecords();

		// NFC Forum specification:
		if (records.length >= 3
			&& records[0].getTnf() == NdefRecord.TNF_WELL_KNOWN
			&& Arrays.equals(records[0].getType(), NdefRecord.RTD_HANDOVER_REQUEST)) {
			return true;
		}

		return isUserspaceHandoverRequest(ndef);
	}

	// User-space handover:
	// TODO: Support uri profile
	private static boolean isUserspaceHandoverRequest(NdefMessage ndef) {
		NdefRecord[] records = (ndef).getRecords();
		if (records.length > 0
				&& records[0].getTnf() == NdefRecord.TNF_ABSOLUTE_URI
				&& records[0].getPayload().length >= USER_HANDOVER_PREFIX.length()) {
			String scheme = new String(records[0].getPayload(), 0, USER_HANDOVER_PREFIX.length());
			return USER_HANDOVER_PREFIX.equals(scheme);
		}
		return false;
	}
}