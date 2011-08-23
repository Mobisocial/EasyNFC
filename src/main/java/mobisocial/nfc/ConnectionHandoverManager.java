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

import android.net.Uri;
import android.nfc.FormatException;
import android.nfc.NdefMessage;
import android.nfc.NdefRecord;
import android.util.Log;

public class ConnectionHandoverManager implements NdefHandler, PrioritizedHandler {
	public static final String USER_HANDOVER_PREFIX = "ndef://wkt:hr/";
	public static final String TAG = "connectionhandover";
	public static final int HANDOVER_PRIORITY = 5;
	private final Set<ConnectionHandover> mmConnectionHandovers = new LinkedHashSet<ConnectionHandover>();

	public ConnectionHandoverManager() {

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
		return doHandover(handoverRequest[0]);
	}

	public final int doHandover(NdefMessage handoverRequest) {
	    int handoverRecordPos = findHandoverRequest(handoverRequest);
		if (handoverRecordPos == -1) {
			return NDEF_PROPAGATE;
		}

		if (findUserspaceHandoverRequest(handoverRequest) != -1) {
		    Uri uri;
		    try {
		        uri = NdefFactory.parseUri(handoverRequest.getRecords()[0]);
		    } catch (FormatException e) {
		        Log.e(TAG, "Bad handover record.", e);
		        return NDEF_PROPAGATE;
		    }
			handoverRequest = NdefFactory.fromNdefUri(uri);
			handoverRecordPos = 0;
		}

		NdefRecord[] records = handoverRequest.getRecords();
		for (int i = handoverRecordPos + 2; i < records.length; i++) {
			Iterator<ConnectionHandover> handovers = mmConnectionHandovers.iterator();
			while (handovers.hasNext()) {
				ConnectionHandover handover = handovers.next();
				if (handover.supportsRequest(records[i])) {
					try {
						Log.d(TAG, "Attempting handover " + handover);
						handover.doConnectionHandover(handoverRequest, handoverRecordPos, i);
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
	public static int findHandoverRequest(NdefMessage ndef) {
		NdefRecord[] records = (ndef).getRecords();

		for (int i = 0; i < records.length; i++) {
    		if (records[i].getTnf() == NdefRecord.TNF_WELL_KNOWN
    			&& Arrays.equals(records[i].getType(), NdefRecord.RTD_HANDOVER_REQUEST)) {
    			return i;
    		}
		}
		return findUserspaceHandoverRequest(ndef);
	}

	public static boolean isHandoverRequest(NdefMessage ndefMessage) {
	    NdefRecord ndef = ndefMessage.getRecords()[0];
            return (ndef.getTnf() == NdefRecord.TNF_WELL_KNOWN
                && Arrays.equals(ndef.getType(), NdefRecord.RTD_HANDOVER_REQUEST));
	}
	// User-space handover:
	// TODO: Support uri profile
	private static int findUserspaceHandoverRequest(NdefMessage ndef) {
		NdefRecord[] records = (ndef).getRecords();
		for (int i = 0; i < records.length; i++) {
		    try {
		        String uriStr = NdefFactory.parseUri(records[i]).toString();
		        if (uriStr.startsWith(USER_HANDOVER_PREFIX)) {
		            return i;
		        }
		    } catch (FormatException e)  { }
		}
		return -1;
	}
}
