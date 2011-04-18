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
import java.util.Arrays;
import java.util.UUID;

import mobisocial.nfc.ConnectionHandover;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.net.Uri;
import android.nfc.NdefMessage;
import android.nfc.NdefRecord;

/**
 * <p>Implements an Ndef push handover request in which a static tag
 * represents an Ndef reader device listening on a Bluetooth socket.
 * </p>
 * <p>
 * Your application must hold the {@code android.permission.BLUETOOTH}
 * permission to support Bluetooth handovers.
 * </p>
 */
public class NdefBluetoothPushHandover implements ConnectionHandover {
	final BluetoothAdapter mmBluetoothAdapter;
	private final NdefExchangeContract mNdefExchange;

	public NdefBluetoothPushHandover(NdefExchangeContract ndefExchange) {
		mmBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
		mNdefExchange = ndefExchange;
	}

	@Override
	public boolean supportsRequest(NdefRecord handoverRequest) {
		if (handoverRequest.getTnf() != NdefRecord.TNF_ABSOLUTE_URI
				|| !Arrays.equals(handoverRequest.getType(), NdefRecord.RTD_URI)) {
			return false;
		}

		String uriString = new String(handoverRequest.getPayload());
		if (uriString.startsWith("ndef+bluetooth://")) {
			return true;
		}
		
		return false;
	}
	
	@Override
	public void doConnectionHandover(NdefMessage handoverMessage, int record) throws IOException {
		NdefRecord handoverRequest = handoverMessage.getRecords()[record];
		String uriString = new String(handoverRequest.getPayload());
		Uri target = Uri.parse(uriString);
		
		String mac = target.getAuthority();
		UUID uuid = UUID.fromString(target.getPath().substring(1));
		DuplexSocket socket = new BluetoothDuplexSocket(mmBluetoothAdapter, mac, uuid);
		new NdefExchangeThread(socket, mNdefExchange).start();
	}

	public class BluetoothDuplexSocket implements DuplexSocket {
		final String mmMac;
		final UUID mmServiceUuid;
		final BluetoothAdapter mmBluetoothAdapter;
		BluetoothSocket mmSocket;

		public BluetoothDuplexSocket(BluetoothAdapter adapter, String mac, UUID serviceUuid) throws IOException {
			mmBluetoothAdapter = adapter;
			mmMac = mac;
			mmServiceUuid = serviceUuid;
		}
		
		@Override
		public void connect() throws IOException {
			BluetoothDevice device = mmBluetoothAdapter.getRemoteDevice(mmMac);
			mmSocket = device.createInsecureRfcommSocketToServiceRecord(mmServiceUuid);
			mmSocket.connect();
		}
		
		@Override
		public InputStream getInputStream() throws IOException {
			return mmSocket.getInputStream();
		}
		
		@Override
		public OutputStream getOutputStream() throws IOException {
			return mmSocket.getOutputStream();
		}
		
		@Override
		public void close() throws IOException {
			if (mmSocket != null) {
				mmSocket.close();
			}
		}
	}
}