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

import mobisocial.nfc.ConnectionHandover;


import android.nfc.NdefMessage;
import android.nfc.NdefRecord;

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
		if (handoverRequest.getTnf() != NdefRecord.TNF_ABSOLUTE_URI
				|| !Arrays.equals(handoverRequest.getType(), NdefRecord.RTD_URI)) {
			return false;
		}
		
		String uriString = new String(handoverRequest.getPayload());
		if (uriString.startsWith("ndef+tcp://")) {
			return true;
		}
		
		return false;
	}

	@Override
	public void doConnectionHandover(NdefMessage handoverMessage, int record) throws IOException {
		NdefRecord handoverRequest = handoverMessage.getRecords()[record];
		NdefMessage outboundNdef = mNdefExchange.getForegroundNdefMessage();
		if (outboundNdef == null) return;
		
		String uriString = new String(handoverRequest.getPayload());
		URI uri = URI.create(uriString);
		sendNdefOverTcp(uri, mNdefExchange);
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

	public class TcpDuplexSocket implements DuplexSocket {
		final Socket mSocket;

		public TcpDuplexSocket(String host, int port) throws IOException {
			mSocket = new Socket(host, port);
		}
		
		//@Override
		public void connect() throws IOException {
			
		}
		
		//@Override
		public InputStream getInputStream() throws IOException {
			return mSocket.getInputStream();
		}
		
		//@Override
		public OutputStream getOutputStream() throws IOException {
			return mSocket.getOutputStream();
		}
		
		//@Override
		public void close() throws IOException {
			mSocket.close();
		}
	}
}