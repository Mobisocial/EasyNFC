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
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.util.Arrays;

import mobisocial.comm.DuplexSocket;
import mobisocial.comm.TcpDuplexSocket;
import mobisocial.nfc.ConnectionHandover;
import mobisocial.nfc.NdefFactory;


import android.net.Uri;
import android.nfc.FormatException;
import android.nfc.NdefMessage;
import android.nfc.NdefRecord;
import android.util.Log;

/**
 * <p>Implements an Ndef push handover request in which a static tag
 * represents an Ndef reader device listening on a TCP socket.
 * </p>
 * <p>
 * Your application must hold the {@code android.permission.INTERNET}
 * permission to support TCP handovers.
 * </p>
 */
public class NdefTcpPushHandover implements ConnectionHandover {
	private static final int DEFAULT_TCP_HANDOVER_PORT = 7924;
	private final NdefExchangeContract mNdefExchange;

	public NdefTcpPushHandover(NdefExchangeContract ndefExchange) {
		mNdefExchange = ndefExchange;
	}

	//@Override
	public boolean supportsRequest(NdefRecord handoverRequest) {
	    short tnf = handoverRequest.getTnf();
        if (tnf != NdefRecord.TNF_ABSOLUTE_URI && (tnf != NdefRecord.TNF_WELL_KNOWN &&
                !Arrays.equals(handoverRequest.getType(), NdefRecord.RTD_URI))) {
            return false;
        }
        Uri uri;
        try {
            uri= NdefFactory.parseUri(handoverRequest);
        } catch (FormatException e) {
            return false;
        }
        String scheme = uri.getScheme();
        return (scheme != null && scheme.equals("ndef+tcp"));
	}

	@Override
	public void doConnectionHandover(NdefMessage handoverMessage, int handover, int record) throws IOException {
		NdefRecord handoverRequest = handoverMessage.getRecords()[record];
		NdefMessage outboundNdef = mNdefExchange.getForegroundNdefMessage();
		if (outboundNdef == null) return;

        try {
            String uriString = NdefFactory.parseUri(handoverRequest).toString();
            URI uri = URI.create(uriString);
            sendNdefOverTcp(uri, mNdefExchange);
        } catch (FormatException e) {
            Log.wtf("easynfc", "Bad handover request", e);
        }
	}

	private void sendNdefOverTcp(URI target, NdefExchangeContract ndefProxy) throws IOException {
		String host = target.getHost();
		int port = target.getPort();
		if (port == -1) {
			port = DEFAULT_TCP_HANDOVER_PORT;
		}

		DuplexSocket socket = new TcpDuplexSocket(host, port);
		new NdefExchangeThread(socket, ndefProxy).start();
	}
}