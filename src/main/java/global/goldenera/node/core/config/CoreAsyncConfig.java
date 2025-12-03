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
package global.goldenera.node.core.config;

import static lombok.AccessLevel.PRIVATE;

import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import lombok.AllArgsConstructor;
import lombok.experimental.FieldDefaults;

@Configuration
@EnableAsync
@AllArgsConstructor
@FieldDefaults(level = PRIVATE, makeFinal = true)
public class CoreAsyncConfig {

	public static final String P2P_RECEIVE_EXECUTOR = "p2pReceiveExecutor";
	public static final String P2P_SEND_EXECUTOR = "p2pSendExecutor";
	public static final String CORE_TASK_EXECUTOR = "coreTaskExecutor";
	public static final String CORE_SCHEDULER = "coreTaskScheduler";
	public static final String BLOCK_MINING_EXECUTOR = "blockMiningExecutor";
	public static final String MINER_WORKER_POOL = "minerWorkerPool";
	public static final String MINER_THREAD_FACTORY = "minerThreadFactory";

	@Bean(name = P2P_RECEIVE_EXECUTOR)
	public Executor p2pReceiveExecutor() {
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		int cores = Runtime.getRuntime().availableProcessors();
		executor.setCorePoolSize(Math.max(2, cores / 2));
		executor.setMaxPoolSize(cores);
		executor.setQueueCapacity(3000);
		executor.setThreadNamePrefix("P2P-In-");
		executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
		executor.initialize();
		return executor;
	}

	@Bean(name = P2P_SEND_EXECUTOR)
	public Executor p2pSendExecutor() {
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setCorePoolSize(2);
		executor.setMaxPoolSize(6);
		executor.setQueueCapacity(500);
		executor.setThreadNamePrefix("P2P-Out-");
		executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
		executor.initialize();
		return executor;
	}

	@Bean(name = CORE_TASK_EXECUTOR)
	public ThreadPoolTaskExecutor coreTaskExecutor() {
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setCorePoolSize(4);
		executor.setMaxPoolSize(12);
		executor.setQueueCapacity(10000);
		executor.setThreadNamePrefix("Core-Worker-");
		executor.setRejectedExecutionHandler(new ThreadPoolExecutor.DiscardPolicy());
		executor.initialize();
		return executor;
	}

	@Bean(name = CORE_SCHEDULER)
	public ThreadPoolTaskScheduler coreTaskScheduler() {
		ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
		scheduler.setPoolSize(4);
		scheduler.setThreadNamePrefix("Core-Sched-");
		scheduler.initialize();
		return scheduler;
	}

	@Bean(name = BLOCK_MINING_EXECUTOR)
	public ExecutorService blockMiningExecutor() {
		return Executors.newSingleThreadExecutor(r -> new Thread(r, "Miner-Main-Loop"));
	}

	@Bean(MINER_THREAD_FACTORY)
	public ThreadFactory minerThreadFactory() {
		return r -> {
			Thread t = new Thread(r, "Miner-Hash-Worker");
			t.setDaemon(true);
			t.setPriority(Thread.MIN_PRIORITY); // So it doesn't kill UI/API
			return t;
		};
	}
}