package global.goldenera.node.core.mempool;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigInteger;

import org.apache.tuweni.units.ethereum.Wei;

import global.goldenera.cryptoj.common.Tx;
import global.goldenera.cryptoj.common.payloads.TxPayload;
import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.cryptoj.enums.TxType;
import global.goldenera.node.core.mempool.domain.MempoolEntry;
import global.goldenera.node.core.properties.MempoolProperties;

public final class MempoolTestFixtures {

	public static final Address ALICE = address(1);
	public static final Address BOB = address(2);
	public static final Address CAROL = address(3);

	private MempoolTestFixtures() {
	}

	public static MempoolProperties properties(long maxSize) {
		MempoolProperties properties = new MempoolProperties();
		properties.setMaxSize(maxSize);
		properties.setMaxNonceGap(100L);
		properties.setMinAcceptableFeeWei(BigInteger.ZERO);
		properties.setTxExpireTimeInMinutes(60);
		return properties;
	}

	public static MempoolEntry transfer(int id, Address sender, long nonce, long fee) {
		return entry(id, sender, nonce, fee, TxType.TRANSFER, null, null);
	}

	public static MempoolEntry governance(int id, Address sender, long nonce, long fee, TxPayload payload) {
		return entry(id, sender, nonce, fee, TxType.BIP_CREATE, payload, null);
	}

	public static MempoolEntry vote(int id, Address sender, long nonce, long fee, Hash bipHash) {
		return entry(id, sender, nonce, fee, TxType.BIP_VOTE, null, bipHash);
	}

	public static MempoolEntry entry(int id, Address sender, long nonce, long fee, TxType type,
			TxPayload payload, Hash referenceHash) {
		Tx tx = mock(Tx.class);
		when(tx.getHash()).thenReturn(hash(id));
		when(tx.getSender()).thenReturn(sender);
		when(tx.getNonce()).thenReturn(nonce);
		when(tx.getFee()).thenReturn(Wei.valueOf(fee));
		when(tx.getSize()).thenReturn(100);
		when(tx.getType()).thenReturn(type);
		when(tx.getPayload()).thenReturn(payload);
		when(tx.getReferenceHash()).thenReturn(referenceHash);
		return new MempoolEntry(tx);
	}

	public static Address address(int id) {
		return Address.fromHexString(String.format("0x%040x", id));
	}

	public static Hash hash(int id) {
		return Hash.fromHexString(String.format("0x%064x", id));
	}
}
