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

import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URL;
import java.util.Arrays;

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
    /**
     * An RTD indicating an Android Application Record.
     */
    public static final byte[] RTD_ANDROID_APP = "android.com:pkg".getBytes();

    private static final String[] URI_PREFIXES = new String[] {
        "",
        "http://www.",
        "https://www.",
        "http://",
        "https://",
        "tel:",
        "mailto:",
        "ftp://anonymous:anonymous@",
        "ftp://ftp.",
        "ftps://",
        "sftp://",
        "smb://",
        "nfs://",
        "ftp://",
        "dav://",
        "news:",
        "telnet://",
        "imap:",
        "rtsp://",
        "urn:",
        "pop:",
        "sip:",
        "sips:",
        "tftp:",
        "btspp://",
        "btl2cap://",
        "btgoep://",
        "tcpobex://",
        "irdaobex://",
        "file://",
        "urn:epc:id:",
        "urn:epc:tag:",
        "urn:epc:pat:",
        "urn:epc:raw:",
        "urn:epc:",
        "urn:nfc:",
    };

    public static NdefMessage fromUri(Uri uri) {
        return fromUri(uri.toString());
    }

    public static NdefMessage fromUri(URI uri) {
        return fromUri(uri.toString());
    }

    public static NdefMessage fromUri(String uri) {
        try {
            int prefix = 0;
            for (int i = 1; i < URI_PREFIXES.length; i++) {
                if (uri.startsWith(URI_PREFIXES[i])) {
                    prefix = i;
                    break;
                }
            }
            if (prefix > 0) uri = uri.substring(URI_PREFIXES[prefix].length());
            int len = uri.length();
            byte[] payload = new byte[len + 1];
            payload[0] = (byte) prefix;
            System.arraycopy(uri.getBytes("UTF-8"), 0, payload, 1, len);
            NdefRecord record = new NdefRecord(NdefRecord.TNF_WELL_KNOWN, NdefRecord.RTD_URI,
                    new byte[0], payload);
            NdefRecord[] records = new NdefRecord[] { record };
            return new NdefMessage(records);
        } catch (NoClassDefFoundError e) {
            return null;
        } catch (UnsupportedEncodingException e) {
            // no UTF-8? really?
            return null;
        }
    }

    public static NdefMessage fromUrl(URL url) {
        return fromUri(url.toString());
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
     * {@hide}
     */
    public static final NdefMessage fromNdefUri(Uri uri) {
        if (!"ndef".equals(uri.getScheme())) {
            throw new IllegalArgumentException("Not an ndef:// uri. did you mean NdefFactory.fromUri()?");
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

    public static final Uri parseUri(NdefRecord record) throws FormatException {
        int tnf = record.getTnf();
        if (tnf == NdefRecord.TNF_ABSOLUTE_URI) {
            return Uri.parse(new String(record.getType()));
        }
        if (tnf == NdefRecord.TNF_WELL_KNOWN &&
                Arrays.equals(NdefRecord.RTD_URI, record.getType())) {
            byte[] payload = record.getPayload();
            int pre = (int)payload[0];
            if (!(pre >= 0 && pre < URI_PREFIXES.length)) {
                throw new FormatException("Unknown uri prefix: " + pre);
            }
            String prefix = URI_PREFIXES[pre];
            String uriStr = new StringBuilder()
                .append(prefix).append(new String(payload, 1, payload.length - 1))
                .toString();
            return Uri.parse(uriStr);
        }
        throw new FormatException("NdefRecord is not a uri.");
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

    /**
     * <p>
     * From Android SDK, version 14. (C) 2010 The Android Open Source Project
     * <p>
     * Creates an Android application NDEF record.
     * <p>
     * This record indicates to other Android devices the package that should be
     * used to handle the rest of the NDEF message. You can embed this record
     * anywhere into your NDEF message to ensure that the intended package
     * receives the message.
     * <p>
     * When an Android device dispatches an {@link NdefMessage} containing one
     * or more Android application records, the applications contained in those
     * records will be the preferred target for the NDEF_DISCOVERED intent, in
     * the order in which they appear in the {@link NdefMessage}. This dispatch
     * behavior was first added to Android in Ice Cream Sandwich.
     * <p>
     * If none of the applications are installed on the device, a Market link
     * will be opened to the first application.
     * <p>
     * Note that Android application records do not overrule applications that
     * have called {@link NfcAdapter#enableForegroundDispatch}.
     * 
     * @param packageName Android package name
     * @return Android application NDEF record
     */
    public static NdefRecord createApplicationRecord(String packageName) {
        try {
            return new NdefRecord(NdefRecord.TNF_EXTERNAL_TYPE, RTD_ANDROID_APP, new byte[] {},
                    packageName.getBytes("US-ASCII"));
        } catch (UnsupportedEncodingException e) {
            return null;
        }
    }
}