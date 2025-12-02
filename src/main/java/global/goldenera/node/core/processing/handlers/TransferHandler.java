/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2025-2030 The GoldenEraGlobal Developers
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package global.goldenera.node.core.processing.handlers;

import static com.google.common.base.Preconditions.checkArgument;

import org.apache.tuweni.units.ethereum.Wei;
import org.springframework.stereotype.Component;

import global.goldenera.cryptoj.common.Tx;
import global.goldenera.cryptoj.datatypes.Address;
import global.goldenera.cryptoj.enums.TxType;
import global.goldenera.node.core.processing.StateProcessor.SimpleBlock;
import global.goldenera.node.core.processing.TxExecutionContext;
import global.goldenera.node.core.state.WorldState;
import global.goldenera.node.shared.consensus.state.impl.AccountBalanceStateImpl;
import global.goldenera.node.shared.consensus.state.impl.TokenStateImpl;

@Component
public class TransferHandler implements TxHandler {

	@Override
	public TxType getSupportedType() {
		return TxType.TRANSFER;
	}

	@Override
	public void execute(TxExecutionContext ctx) {
		WorldState state = ctx.getState();
		Tx tx = ctx.getTx();
		SimpleBlock block = ctx.getBlock();

		Address sender = tx.getSender();
		Address recipient = tx.getRecipient();
		if (sender.equals(recipient)) {
			return;
		}

		Address tokenAddr = tx.getTokenAddress();
		Wei amount = tx.getAmount();

		AccountBalanceStateImpl senderBal = (AccountBalanceStateImpl) state.getBalance(sender, tokenAddr);
		AccountBalanceStateImpl newSenderBal = senderBal.debit(amount, block.getHeight(), block.getTimestamp());
		state.setBalance(sender, tokenAddr, newSenderBal);

		if (recipient.equals(Address.ZERO)) {
			TokenStateImpl tokenState = (TokenStateImpl) state.getToken(tokenAddr);
			checkArgument(tokenState.exists(), "Cannot burn token that does not exist in state");
			checkArgument(tokenState.isUserBurnable(), "Token is not burnable");
			TokenStateImpl newTokenState = tokenState.burn(
					amount,
					tx.getHash(),
					block.getHeight(),
					block.getTimestamp());
			state.setToken(tokenAddr, newTokenState);
		} else {
			AccountBalanceStateImpl recipientBal = (AccountBalanceStateImpl) state.getBalance(recipient, tokenAddr);
			AccountBalanceStateImpl newRecipientBal = recipientBal.credit(amount, block.getHeight(),
					block.getTimestamp());
			state.setBalance(recipient, tokenAddr, newRecipientBal);
		}
	}
}