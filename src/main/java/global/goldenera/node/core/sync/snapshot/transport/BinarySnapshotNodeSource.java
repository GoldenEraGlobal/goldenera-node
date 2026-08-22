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
package global.goldenera.node.core.sync.snapshot.transport;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.tuweni.bytes.Bytes;

import global.goldenera.cryptoj.datatypes.Hash;
import global.goldenera.node.core.sync.snapshot.CheckpointSnapshotLimits;
import global.goldenera.node.core.sync.snapshot.SnapshotChunkDescriptor;
import global.goldenera.node.core.sync.snapshot.SnapshotNode;
import global.goldenera.node.core.sync.snapshot.SnapshotNodeSource;

/**
 * Streaming chunk encoding: big-endian chunk index followed by repeated
 * {@code key[32], contentLength[4], content[contentLength]} records.
 * This byte stream is also the verifier's canonical chunk-hash preimage.
 */
public final class BinarySnapshotNodeSource implements SnapshotNodeSource {

	private final DataInputStream input;
	private final SnapshotChunkDescriptor descriptor;
	private int nodesRead;
	private boolean endChecked;

	public BinarySnapshotNodeSource(Path path, SnapshotChunkDescriptor descriptor) throws IOException {
		this(Files.newInputStream(path), descriptor);
	}

	public BinarySnapshotNodeSource(InputStream source, SnapshotChunkDescriptor descriptor) throws IOException {
		this.input = new DataInputStream(new BufferedInputStream(source));
		this.descriptor = descriptor;
		int declaredIndex = input.readInt();
		if (declaredIndex != descriptor.index()) {
			input.close();
			throw new IOException("Staged snapshot chunk index mismatch");
		}
	}

	@Override
	public boolean hasNext() throws IOException {
		if (nodesRead < descriptor.nodeCount()) {
			return true;
		}
		if (!endChecked) {
			endChecked = true;
			if (input.read() != -1) {
				throw new IOException("Staged snapshot chunk contains trailing bytes");
			}
		}
		return false;
	}

	@Override
	public SnapshotNode next() throws IOException {
		if (!hasNext()) {
			throw new EOFException("No more nodes in staged snapshot chunk");
		}
		byte[] key = input.readNBytes(Hash.SIZE);
		if (key.length != Hash.SIZE) {
			throw new EOFException("Truncated snapshot node key");
		}
		int size = input.readInt();
		if (size <= 0 || size > CheckpointSnapshotLimits.MAX_NODE_BYTES) {
			throw new IOException("Invalid staged snapshot node length");
		}
		byte[] content = input.readNBytes(size);
		if (content.length != size) {
			throw new EOFException("Truncated snapshot node content");
		}
		nodesRead++;
		return new SnapshotNode(Hash.wrap(key), Bytes.wrap(content));
	}

	@Override
	public void close() throws IOException {
		input.close();
	}
}
