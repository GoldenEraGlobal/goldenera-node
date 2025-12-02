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
package global.goldenera.node.core.p2p.messages.serialization;

import java.util.List;

import org.apache.tuweni.bytes.Bytes;

import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.node.core.p2p.messages.dtos.common.P2PBlockHeaderDto;
import global.goldenera.node.core.p2p.messages.dtos.common.P2PTxDto;
import global.goldenera.node.core.p2p.messages.dtos.sync.P2PBlockBodiesDto;
import global.goldenera.node.core.p2p.messages.dtos.sync.P2PBlockBodiesReqDto;
import global.goldenera.node.core.p2p.messages.dtos.sync.P2PBlockHeadersDto;
import global.goldenera.node.core.p2p.messages.dtos.sync.P2PBlockHeadersReqDto;
import global.goldenera.node.core.p2p.messages.dtos.sync.P2PMempoolHashesDto;
import global.goldenera.node.core.p2p.messages.dtos.sync.P2PMempoolHashesReqDto;
import global.goldenera.node.core.p2p.messages.dtos.sync.P2PMempoolTxsDto;
import global.goldenera.node.core.p2p.messages.dtos.sync.P2PMempoolTxsReqDto;
import global.goldenera.rlp.RLP;
import global.goldenera.rlp.RLPInput;
import lombok.experimental.UtilityClass;

@UtilityClass
public class P2PSyncSerializer {

	public static Bytes encodeGetHeaders(P2PBlockHeadersReqDto dto) {
		return RLP.encode(out -> {
			out.startList();
			out.writeList(dto.getLocators(), (h, o) -> o.writeBytes(h));
			out.writeBytes32(dto.getStopHash());
			out.writeIntScalar(dto.getBatchSize());
			out.endList();
		});
	}

	public static P2PBlockHeadersReqDto decodeGetHeaders(Bytes bytes) {
		RLPInput input = RLP.input(bytes);
		input.enterList();
		List<Hash> locators = input.readList(in -> Hash.wrap(in.readBytes32()));
		Hash stopHash = Hash.wrap(input.readBytes32());
		int batchSize = input.readIntScalar();
		input.leaveList();
		return P2PBlockHeadersReqDto.builder()
				.locators(locators)
				.stopHash(stopHash)
				.batchSize(batchSize)
				.build();
	}

	public static Bytes encodeGetBodies(P2PBlockBodiesReqDto dto) {
		return RLP.encode(out -> {
			out.startList();
			out.writeList(dto.getHashes(), (h, o) -> o.writeBytes(h));
			out.endList();
		});
	}

	public static P2PBlockBodiesReqDto decodeGetBodies(Bytes bytes) {
		RLPInput input = RLP.input(bytes);
		input.enterList();
		List<Hash> hashes = input.readList(in -> Hash.wrap(in.readBytes32()));
		input.leaveList();
		return P2PBlockBodiesReqDto.builder()
				.hashes(hashes)
				.build();
	}

	public static Bytes encodeBlockHeaders(List<P2PBlockHeaderDto> headers) {
		return RLP.encode(out -> {
			out.startList();
			out.writeList(headers, (h, o) -> o.writeRaw(P2PBlockHeaderSerializer.encodeBlockHeader(h)));
			out.endList();
		});
	}

	public static P2PBlockHeadersDto decodeBlockHeaders(Bytes bytes) {
		RLPInput input = RLP.input(bytes);
		input.enterList();
		List<P2PBlockHeaderDto> headers = input
				.readList(in -> P2PBlockHeaderSerializer.decodeBlockHeader(in.readRaw()));
		input.leaveList();
		return P2PBlockHeadersDto.builder().headers(headers).build();
	}

	public static Bytes encodeBlockBodies(P2PBlockBodiesDto dto) {
		return RLP.encode(out -> {
			out.startList();
			out.writeList(dto.getBodies(), (txList, o) -> {
				o.startList();
				for (P2PTxDto tx : txList) {
					o.writeRaw(P2PTxSerializer.encodeTx(tx));
				}
				o.endList();
			});
			out.endList();
		});
	}

	public static P2PBlockBodiesDto decodeBlockBodies(Bytes bytes) {
		RLPInput input = RLP.input(bytes);
		input.enterList();
		List<List<P2PTxDto>> bodies = input.readList(bodyInput -> {
			return bodyInput.readList(txInput -> P2PTxSerializer.decodeTx(txInput.readAsRlp().raw()));
		});
		input.leaveList();
		return P2PBlockBodiesDto.builder().bodies(bodies).build();
	}

	public static Bytes encodeMempoolHashes(P2PMempoolHashesDto dto) {
		return RLP.encode(out -> {
			out.startList();
			out.writeList(dto.getHashes(), (h, o) -> o.writeBytes(h));
			out.endList();
		});
	}

	public static P2PMempoolHashesDto decodeMempoolHashes(Bytes bytes) {
		RLPInput input = RLP.input(bytes);
		input.enterList();
		P2PMempoolHashesDto dto = P2PMempoolHashesDto.builder()
				.hashes(input.readList(in -> Hash.wrap(in.readBytes32()))).build();
		input.leaveList();
		return dto;
	}

	public static Bytes encodeGetMempoolHashes(P2PMempoolHashesReqDto dto) {
		return RLP.encode(out -> {
			out.startList();
			out.writeList(dto.getHashes(), (h, o) -> o.writeBytes(h));
			out.endList();
		});
	}

	public static Bytes encodeGetMempoolTransactions(P2PMempoolTxsReqDto dto) {
		return RLP.encode(out -> {
			out.startList();
			out.writeList(dto.getHashes(), (h, o) -> o.writeBytes(h));
			out.endList();
		});
	}

	public static P2PMempoolTxsReqDto decodeGetMempoolTransactions(Bytes bytes) {
		RLPInput input = RLP.input(bytes);
		input.enterList();
		P2PMempoolTxsReqDto dto = P2PMempoolTxsReqDto.builder()
				.hashes(input.readList(in -> Hash.wrap(in.readBytes32()))).build();
		input.leaveList();
		return dto;
	}

	public static Bytes encodeMempoolTransactions(P2PMempoolTxsDto dto) {
		return RLP.encode(out -> {
			out.startList();
			out.writeList(dto.getTxs(), (tx, o) -> o.writeRaw(P2PTxSerializer.encodeTx(tx)));
			out.endList();
		});
	}

	public static P2PMempoolTxsDto decodeMempoolTransactions(Bytes bytes) {
		RLPInput input = RLP.input(bytes);
		input.enterList();
		P2PMempoolTxsDto dto = P2PMempoolTxsDto.builder()
				.txs(input.readList(in -> P2PTxSerializer.decodeTx(in.readAsRlp().raw()))).build();
		input.leaveList();
		return dto;
	}
}
