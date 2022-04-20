/*
 * Copyright 2015 Otto (GmbH & Co KG)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.flinkspector.datastream;

import com.google.common.base.Preconditions;
import io.flinkspector.core.input.Input;
import io.flinkspector.core.runtime.OutputVerifier;
import io.flinkspector.core.runtime.Runner;
import io.flinkspector.core.trigger.DefaultTestTrigger;
import io.flinkspector.core.trigger.VerifyFinishedTrigger;
import io.flinkspector.datastream.functions.ParallelFromStreamRecordsFunction;
import io.flinkspector.datastream.functions.TestSink;
import io.flinkspector.datastream.input.EventTimeInput;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.java.typeutils.TypeExtractor;
import org.apache.flink.configuration.ConfigOption;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.configuration.RestOptions;
import org.apache.flink.configuration.TaskManagerOptions;
import org.apache.flink.runtime.minicluster.MiniCluster;
import org.apache.flink.runtime.minicluster.MiniClusterConfiguration;
import org.apache.flink.streaming.api.datastream.DataStreamSource;
import org.apache.flink.streaming.api.functions.source.FromElementsFunction;
import org.apache.flink.streaming.api.functions.source.SourceFunction;
import org.apache.flink.streaming.runtime.streamrecord.StreamRecord;
import org.apache.flink.streaming.util.TestStreamEnvironment;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;

import static org.apache.flink.configuration.ConfigOptions.key;

public class DataStreamTestEnvironment extends TestStreamEnvironment {

	private final Runner runner;

	public DataStreamTestEnvironment(MiniCluster cluster, int parallelism) {
		super(cluster, parallelism);
		runner = new Runner(cluster) {
			@Override
			protected void executeEnvironment() throws Throwable {
				TestStreamEnvironment.setAsContext(cluster, parallelism);
				try {
					execute();
				}
				finally {
					TestStreamEnvironment.unsetAsContext();
				}
			}
		};
	}

	/**
	 * Factory method to startWith a new instance, providing a new instance of {@link MiniCluster}
	 *
	 * @param parallelism global setting for parallel execution.
	 * @return new instance of {@link DataStreamTestEnvironment}
	 * @throws Exception
	 */
	public static DataStreamTestEnvironment createTestEnvironment(int parallelism) {
		int taskSlots = Runtime.getRuntime().availableProcessors();

		Configuration configuration = new Configuration();
		ConfigOption configOption = TaskManagerOptions.MANAGED_MEMORY_SIZE;
		configuration.set(configOption, "0");

		if (!configuration.contains(RestOptions.BIND_PORT)) {
			configuration.setString(RestOptions.BIND_PORT, "0");
		}

		int numSlotsPerTaskManager = configuration.getInteger(TaskManagerOptions.NUM_TASK_SLOTS, taskSlots);

		MiniClusterConfiguration cfg = new MiniClusterConfiguration.Builder()
				.setConfiguration(configuration)
				.setNumSlotsPerTaskManager(numSlotsPerTaskManager)
				.build();

		MiniCluster miniCluster = new MiniCluster(cfg);

		return new DataStreamTestEnvironment(
				miniCluster,
				parallelism);
	}

	public void executeTest() throws Throwable {
		runner.executeTest();
	}

	/**
	 * Creates a TestSink to verify your the output of your stream. Using a {@link OutputVerifier}
	 *
	 * @param verifier {@link OutputVerifier} which will be used to verify the received records.
	 * @param <IN>     type of the input
	 * @return the created sink.
	 */
	public <IN> TestSink<IN> createTestSink(OutputVerifier<IN> verifier) {
		VerifyFinishedTrigger trigger = new DefaultTestTrigger();
		int instance = runner.registerListener(verifier, trigger);
		TestSink<IN> sink = new TestSink<IN>(instance, runner.getRingBuffer());
		return sink;
	}

	/**
	 * Creates a TestSink to verify the output of your stream. The environment will register a port
	 *
	 * @param verifier which will be used to verify the received records
	 * @param <IN>     type of the input
	 * @return the created sink.
	 */
	public <IN> TestSink<IN> createTestSink(OutputVerifier<IN> verifier,
			VerifyFinishedTrigger trigger) {
		int instance = runner.registerListener(verifier, trigger);
		TestSink<IN> sink = new TestSink<IN>(instance, runner.getRingBuffer());
		return sink;
	}

	/**
	 * Creates a new data stream that contains the given elements. The elements must all be of the same type, for example, all of the {@link
	 * String} or {@link Integer}.
	 * <p>
	 * The framework will try and determine the exact type from the elements. In case of generic elements, it may be necessary to manually
	 * supply the type information via {@link #fromCollection(java.util.Collection, org.apache.flink.api.common.typeinfo.TypeInformation)}.
	 * <p>
	 * Note that this operation will result in a non-parallel data stream source, i.e. a data stream source with a degree of parallelism
	 * one.
	 *
	 * @param data  The array of elements to startWith the data stream from.
	 * @param <OUT> The type of the returned data stream
	 * @return The data stream representing the given array of elements
	 */
	@SafeVarargs
	public final <OUT> DataStreamSource<OUT> fromElementsWithTimeStamp(StreamRecord<OUT>... data) {
		return fromCollectionWithTimestamp(Arrays.asList(data), false);
	}

	/**
	 * Creates a data stream form the given non-empty {@link EventTimeInput} object. The type of the data stream is that of the {@link
	 * EventTimeInput}.
	 *
	 * @param input The {@link EventTimeInput} to startWith the data stream from.
	 * @param <OUT> The generic type of the returned data stream.
	 * @return The data stream representing the given input.
	 */
	public <OUT> DataStreamSource<OUT> fromInput(EventTimeInput<OUT> input) {
		return fromCollectionWithTimestamp(input.getInput(), input.getFlushWindowsSetting());
	}

	/**
	 * Creates a data stream form the given non-empty {@link Input} object. The type of the data stream is that of the {@link Input}.
	 *
	 * @param input The {@link Input} to startWith the data stream from.
	 * @param <OUT> The generic type of the returned data stream.
	 * @return The data stream representing the given input.
	 */
	public <OUT> DataStreamSource<OUT> fromInput(Input<OUT> input) {
		return fromCollection(input.getInput());
	}

	/**
	 * Creates a data stream from the given non-empty collection. The type of the data stream is that of the elements in the collection.
	 * <p>
	 * <p>The framework will try and determine the exact type from the collection elements. In case of generic
	 * elements, it may be necessary to manually supply the type information via {@link #fromCollection(java.util.Collection,
	 * org.apache.flink.api.common.typeinfo.TypeInformation)}.</p>
	 * <p>
	 * <p>Note that this operation will result in a non-parallel data stream source, i.e. a data stream source with a
	 * parallelism one.</p>
	 *
	 * @param <OUT>        The generic type of the returned data stream.
	 * @param data         The collection of elements to startWith the data stream from.
	 * @param flushWindows Specifies whether open windows should be flushed on termination.
	 * @return The data stream representing the given collection
	 */
	public <OUT> DataStreamSource<OUT> fromCollectionWithTimestamp(Collection<StreamRecord<OUT>> data, Boolean flushWindows) {
		Preconditions.checkNotNull(data, "Collection must not be null");
		if(data.isEmpty()) {
			throw new IllegalArgumentException("Collection must not be empty");
		}

		StreamRecord<OUT> first = data.iterator().next();
		if(first == null) {
			throw new IllegalArgumentException("Collection must not contain null elements");
		}

		TypeInformation<OUT> typeInfo;
		try {
			typeInfo = TypeExtractor.getForObject(first.getValue());
		}
		catch(Exception e) {
			throw new RuntimeException("Could not startWith TypeInformation for type " + first.getClass()
					+ "; please specify the TypeInformation manually via "
					+ "StreamExecutionEnvironment#fromElements(Collection, TypeInformation)");
		}
		return fromCollectionWithTimestamp(data, typeInfo, flushWindows);
	}

	/**
	 * Creates a data stream from the given non-empty collection.
	 * <p>
	 * <p>Note that this operation will result in a non-parallel data stream source,
	 * i.e., a data stream source with a parallelism one.</p>
	 *
	 * @param <OUT>        The type of the returned data stream
	 * @param data         The collection of elements to startWith the data stream from
	 * @param outType      The TypeInformation for the produced data stream
	 * @param flushWindows
	 * @return The data stream representing the given collection
	 */
	public <OUT> DataStreamSource<OUT> fromCollectionWithTimestamp(Collection<StreamRecord<OUT>> data,
			TypeInformation<OUT> outType, Boolean flushWindows) {
		Preconditions.checkNotNull(data, "Collection must not be null");

		TypeInformation<StreamRecord<OUT>> typeInfo;
		StreamRecord<OUT> first = data.iterator().next();
		try {
			typeInfo = TypeExtractor.getForObject(first);
		}
		catch(Exception e) {
			throw new RuntimeException("Could not startWith TypeInformation for type " + first.getClass()
					+ "; please specify the TypeInformation manually via "
					+ "StreamExecutionEnvironment#fromElements(Collection, TypeInformation)");
		}

		// must not have null elements and mixed elements
		FromElementsFunction.checkCollection(data, typeInfo.getTypeClass());

		SourceFunction<OUT> function;
		try {
			function = new ParallelFromStreamRecordsFunction<OUT>(typeInfo.createSerializer(getConfig()),
					data,
					flushWindows);
		}
		catch(IOException e) {
			throw new RuntimeException(e.getMessage(), e);
		}
		return addSource(function, "Collection Source", outType);
	}

	public <OUT> DataStreamSource<OUT> fromInput(Collection<OUT> input) {
		return super.fromCollection(input);
	}

	/**
	 * This method can be used to check if the environment has been stopped prematurely by e.g. a timeout.
	 *
	 * @return true if has been stopped forcefully.
	 */
	public Boolean hasBeenStopped() {
		return runner.hasBeenStopped();
	}

	/**
	 * Getter for the timeout interval after the test execution gets stopped.
	 *
	 * @return timeout in milliseconds
	 */
	public Long getTimeoutInterval() {
		return runner.getTimeoutInterval();
	}

	/**
	 * Setter for the timeout interval after the test execution gets stopped.
	 *
	 * @param interval in milliseconds.
	 */
	public void setTimeoutInterval(long interval) {
		runner.setTimeoutInterval(interval);
	}

}
