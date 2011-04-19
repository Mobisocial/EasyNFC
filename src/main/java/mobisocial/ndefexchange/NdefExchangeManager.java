package mobisocial.ndefexchange;

import mobisocial.nfc.ConnectionHandoverManager;

public class NdefExchangeManager extends ConnectionHandoverManager {
	public NdefExchangeManager(NdefExchangeContract ndefExchange) {
		addConnectionHandover(new NdefBluetoothPushHandover(ndefExchange));
		addConnectionHandover(new NdefTcpPushHandover(ndefExchange));
	}
}
