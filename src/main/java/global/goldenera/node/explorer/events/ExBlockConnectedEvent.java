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
package global.goldenera.node.explorer.events;

import static lombok.AccessLevel.PRIVATE;

import java.math.BigInteger;
import java.util.List;

import org.apache.tuweni.units.ethereum.Wei;
import org.springframework.context.ApplicationEvent;

import global.goldenera.cryptoj.common.Block;
import global.goldenera.node.core.storage.blockchain.domain.BlockEvent;
import lombok.Getter;
import lombok.NonNull;
import lombok.experimental.FieldDefaults;

@Getter
@FieldDefaults(level = PRIVATE, makeFinal = true)
public class ExBlockConnectedEvent extends ApplicationEvent {

	@NonNull
	Block block;
	@NonNull
	BigInteger cumulativeDifficulty;
	@NonNull
	Wei totalFees;
	@NonNull
	Wei blockReward;
	@NonNull
	List<BlockEvent> events;

	public ExBlockConnectedEvent(
			Object source,
			Block block,
			BigInteger cumulativeDifficulty,
			Wei totalFees,
			Wei blockReward,
			List<BlockEvent> events) {
		super(source);
		this.block = block;
		this.cumulativeDifficulty = cumulativeDifficulty;
		this.totalFees = totalFees;
		this.blockReward = blockReward;
		this.events = events;
	}
}