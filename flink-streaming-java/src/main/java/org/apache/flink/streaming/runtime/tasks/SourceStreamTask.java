/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.streaming.runtime.tasks;

import org.apache.flink.annotation.Internal;
import org.apache.flink.runtime.execution.CancelTaskException;
import org.apache.flink.runtime.execution.Environment;
import org.apache.flink.runtime.util.FatalExitExceptionHandler;
import org.apache.flink.streaming.api.functions.source.SourceFunction;
import org.apache.flink.streaming.api.operators.StreamSource;
import org.apache.flink.streaming.runtime.tasks.mailbox.MailboxDefaultAction;
import org.apache.flink.util.ExceptionUtils;
import org.apache.flink.util.Preconditions;

import java.util.concurrent.CompletableFuture;

/**
 * {@link StreamTask} for executing a {@link StreamSource}.
 *
 * <p>One important aspect of this is that the checkpointing and the emission of elements must never
 * occur at the same time. The execution must be serial. This is achieved by having the contract
 * with the {@link SourceFunction} that it must only modify its state or emit elements in
 * a synchronized block that locks on the lock Object. Also, the modification of the state
 * and the emission of elements must happen in the same block of code that is protected by the
 * synchronized block.
 *
 * @param <OUT> Type of the output elements of this source.
 * @param <SRC> Type of the source function for the stream source operator
 * @param <OP> Type of the stream source operator
 */
@Internal
public class SourceStreamTask<OUT, SRC extends SourceFunction<OUT>, OP extends StreamSource<OUT, SRC>>
	extends StreamTask<OUT, OP> {

	private final LegacySourceFunctionThread sourceThread;
	private final Object lock;

	private volatile boolean externallyInducedCheckpoints;

	/**
	 * Indicates whether this Task was purposefully finished (by finishTask()), in this case we
	 * want to ignore exceptions thrown after finishing, to ensure shutdown works smoothly.
	 */
	private volatile boolean isFinished = false;

	public SourceStreamTask(Environment env) throws Exception {
		this(env, new Object());
	}

	private SourceStreamTask(Environment env, Object lock) throws Exception {
		super(env, null, FatalExitExceptionHandler.INSTANCE, StreamTaskActionExecutor.synchronizedExecutor(lock));
		this.lock = Preconditions.checkNotNull(lock);
		this.sourceThread = new LegacySourceFunctionThread();
	}

	@Override
	protected void init() {
		// we check if the source is actually inducing the checkpoints, rather
		// than the trigger
		SourceFunction<?> source = headOperator.getUserFunction();
	}

	@Override
	protected void cleanup() {
		// does not hold any resources, so no cleanup needed
	}

	@Override
	protected void processInput(MailboxDefaultAction.Controller controller) throws Exception {

		controller.suspendDefaultAction();

		// Against the usual contract of this method, this implementation is not step-wise but blocking instead for
		// compatibility reasons with the current source interface (source functions run as a loop, not in steps).
		sourceThread.setTaskDescription(getName());
		sourceThread.start();
		sourceThread.getCompletionFuture().whenComplete((Void ignore, Throwable sourceThreadThrowable) -> {
			if (isCanceled() && ExceptionUtils.findThrowable(sourceThreadThrowable, InterruptedException.class).isPresent()) {
				mailboxProcessor.reportThrowable(new CancelTaskException(sourceThreadThrowable));
			} else if (!isFinished && sourceThreadThrowable != null) {
				mailboxProcessor.reportThrowable(sourceThreadThrowable);
			} else {
				mailboxProcessor.allActionsCompleted();
			}
		});
	}

	@Override
	protected void cancelTask() {
		try {
			if (headOperator != null) {
				headOperator.cancel();
			}
		}
		finally {
			sourceThread.interrupt();
		}
	}

	// ------------------------------------------------------------------------
	//  Checkpointing
	// ------------------------------------------------------------------------

	/**
	 * Runnable that executes the the source function in the head operator.
	 */
	private class LegacySourceFunctionThread extends Thread {

		private final CompletableFuture<Void> completionFuture;

		LegacySourceFunctionThread() {
			this.completionFuture = new CompletableFuture<>();
		}

		@Override
		public void run() {
			try {
				headOperator.run(lock, getStreamStatusMaintainer(), operatorChain);
				completionFuture.complete(null);
			} catch (Throwable t) {
				// Note, t can be also an InterruptedException
				completionFuture.completeExceptionally(t);
			}
		}

		public void setTaskDescription(final String taskDescription) {
			setName("Legacy Source Thread - " + taskDescription);
		}

		CompletableFuture<Void> getCompletionFuture() {
			return completionFuture;
		}
	}
}
