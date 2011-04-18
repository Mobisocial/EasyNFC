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

import java.net.URI;
import java.net.URL;

import android.net.Uri;
import android.nfc.FormatException;
import android.nfc.NdefMessage;
import android.nfc.NdefRecord;
import android.util.Base64;

/**
 * A utility class for generating NDEF messages.
 * @see NdefMessage
 */
public class NdefFactory {
	public static NdefMessage fromUri(Uri uri) {
		try {
			NdefRecord record = new NdefRecord(NdefRecord.TNF_ABSOLUTE_URI, NdefRecord.RTD_URI, 
					new byte[0], uri.toString().getBytes());
			NdefRecord[] records = new NdefRecord[] { record };
			return new NdefMessage(records);
		} catch (NoClassDefFoundError e) {
			return null;
		}
	}
	
	public static NdefMessage fromUri(URI uri) {
		try {
			NdefRecord record = new NdefRecord(NdefRecord.TNF_ABSOLUTE_URI, NdefRecord.RTD_URI, 
					new byte[0], uri.toString().getBytes());
			NdefRecord[] records = new NdefRecord[] { record };
			return new NdefMessage(records);
		} catch (NoClassDefFoundError e) {
			return null;
		}
	}

	public static NdefMessage fromUri(String uri) {
		try {
			NdefRecord record = new NdefRecord(NdefRecord.TNF_ABSOLUTE_URI, NdefRecord.RTD_URI, 
					new byte[0], uri.getBytes());
			NdefRecord[] records = new NdefRecord[] { record };
			return new NdefMessage(records);
		} catch (NoClassDefFoundError e) {
			return null;
		}
	}

	public static NdefMessage fromUrl(URL url) {
		try {
			NdefRecord record = new NdefRecord(NdefRecord.TNF_ABSOLUTE_URI,
					NdefRecord.RTD_URI, new byte[0], url.toString().getBytes());
			NdefRecord[] records = new NdefRecord[] { record };
			return new NdefMessage(records);
		} catch (NoClassDefFoundError e) {
			return null;
		}
	}
	
	public static NdefMessage fromMime(String mimeType, byte[] data) {
		try {
			NdefRecord record = new NdefRecord(NdefRecord.TNF_MIME_MEDIA,
					mimeType.getBytes(), new byte[0], data);
			NdefRecord[] records = new NdefRecord[] { record };
			return new NdefMessage(records);
		} catch (NoClassDefFoundError e) {
			return null;
		}
	}
	
	/**
	 * Creates an NDEF message with a single text record, with language
	 * code "en" and the given text, encoded using UTF-8.
	 */
	public static NdefMessage fromText(String text) {
		try {
			byte[] textBytes = text.getBytes();
			byte[] textPayload = new byte[textBytes.length + 3];
			textPayload[0] = 0x02; // Status byte; UTF-8 and "en" encoding.
			textPayload[1] = 'e';
			textPayload[2] = 'n';
			System.arraycopy(textBytes, 0, textPayload, 3, textBytes.length);
			NdefRecord record = new NdefRecord(NdefRecord.TNF_WELL_KNOWN,
					NdefRecord.RTD_TEXT, new byte[0], textPayload);
			NdefRecord[] records = new NdefRecord[] { record };
			return new NdefMessage(records);
		} catch (NoClassDefFoundError e) {
			return null;
		}
	}
	
	/**
	 * Creates an NDEF message with a single text record, with the given
	 * text content (UTF-8 encoded) and language code. 
	 */
	public static NdefMessage fromText(String text, String languageCode) {
		try {
			int languageCodeLength = languageCode.length();
			int textLength = text.length();
			byte[] textPayload = new byte[textLength + 1 + languageCodeLength];
			textPayload[0] = (byte)(0x3F & languageCodeLength); // UTF-8 with the given language code length.
			System.arraycopy(languageCode.getBytes(), 0, textPayload, 1, languageCodeLength);
			System.arraycopy(text.getBytes(), 0, textPayload, 1 + languageCodeLength, textLength);
			NdefRecord record = new NdefRecord(NdefRecord.TNF_WELL_KNOWN,
					NdefRecord.RTD_TEXT, new byte[0], textPayload);
			NdefRecord[] records = new NdefRecord[] { record };
			return new NdefMessage(records);
		} catch (NoClassDefFoundError e) {
			return null;
		}
	}

	public static final NdefMessage getEmptyNdef() {
		byte[] empty = new byte[] {};
		NdefRecord[] records = new NdefRecord[1];
		records[0] = new NdefRecord(NdefRecord.TNF_WELL_KNOWN, empty, empty, empty);
		NdefMessage ndef = new NdefMessage(records);
		return ndef;
	}

	public static final boolean isEmpty(NdefMessage ndef) {
		return  (ndef == null || ndef.equals(getEmptyNdef()));
	}

	/**
	 * Converts an Ndef message encoded in uri format to an NdefMessage.
	 */
	public static final NdefMessage fromNdefUri(Uri uri) {
		if (!"ndef".equals(uri.getScheme())) {
			throw new IllegalArgumentException("Not an ndef:// uri. did you want NdefFactory.fromUri()?");
		}
		NdefMessage wrappedNdef;
		try {
			wrappedNdef = new NdefMessage(android.util.Base64.decode(
         	    uri.getPath().substring(1), Base64.URL_SAFE));
		} catch (FormatException e) {
			throw new IllegalArgumentException("Format error.");
		}
		return wrappedNdef;
	}

	/**
	 * Converts an Ndef message to its uri encoding, using the
	 * {code ndef://} scheme.
	 */
	// TODO Switch for the appropriate type
	/*
	public static final Uri toNdefUri(NdefMessage ndef) {
		return Uri.parse("ndef://url/" + Base64.encodeToString(ndef.toByteArray(), Base64.URL_SAFE));
	}*/
}