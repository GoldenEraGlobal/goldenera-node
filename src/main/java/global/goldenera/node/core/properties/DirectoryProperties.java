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
package global.goldenera.node.core.properties;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "ge.core.directory", ignoreUnknownFields = false)
public class DirectoryProperties {

	/**
	 * Interval between directory pings in milliseconds.
	 * Default: 30000 (30 seconds)
	 */
	Integer pingIntervalInMs = 30000;

	/**
	 * Disable directory server communication.
	 * When true, uses manual peers from configuration instead.
	 * Default: false
	 */
	boolean disable = false;

	/**
	 * Manual peer list for development when directory is disabled.
	 * Each peer must have identity, host, and port.
	 * Used only when disable=true.
	 * 
	 * Example in application-dev.properties:
	 * ge.core.directory.peers[0].identity=0x1234...
	 * ge.core.directory.peers[0].host=127.0.0.1
	 * ge.core.directory.peers[0].port=12345
	 */
	List<ManualPeer> peers = new ArrayList<>();

	@Getter
	@Setter
	public static class ManualPeer {
		/**
		 * Node identity address (e.g., 0x1234...)
		 */
		String identity;

		/**
		 * Network (MAINNET, TESTNET)
		 */
		String network;

		/**
		 * P2P host/IP address (p2pListenHost)
		 */
		String host;

		/**
		 * P2P port (p2pListenPort)
		 */
		Integer port;
	}
}
