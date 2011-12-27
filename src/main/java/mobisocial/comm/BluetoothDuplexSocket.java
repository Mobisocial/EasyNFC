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

package mobisocial.comm;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;

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

		public BluetoothDuplexSocket(BluetoothSocket socket) {
		    mmBluetoothAdapter = null;
            mmMac = null;
            mmServiceUuid = null;

		    mmSocket = socket;
		}
		
		@Override
		public void connect() throws IOException {
		    if (mmSocket == null) {
    			BluetoothDevice device = mmBluetoothAdapter.getRemoteDevice(mmMac);
    			mmSocket = device.createInsecureRfcommSocketToServiceRecord(mmServiceUuid);
    			mmSocket.connect();
		    }
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