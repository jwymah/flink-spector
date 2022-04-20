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

package io.flinkspector.dataset;

import io.flinkspector.core.input.Input;
import io.flinkspector.core.runtime.OutputVerifier;
import io.flinkspector.core.runtime.Runner;
import io.flinkspector.core.trigger.DefaultTestTrigger;
import io.flinkspector.core.trigger.VerifyFinishedTrigger;
import org.apache.flink.api.java.DataSet;
import org.apache.flink.configuration.ConfigOption;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.configuration.RestOptions;
import org.apache.flink.configuration.TaskManagerOptions;
import org.apache.flink.runtime.minicluster.MiniCluster;
import org.apache.flink.runtime.minicluster.MiniClusterConfiguration;
import org.apache.flink.runtime.minicluster.RpcServiceSharing;
import org.apache.flink.streaming.util.TestStreamEnvironment;
import org.apache.flink.test.util.TestEnvironment;
import static org.apache.flink.configuration.ConfigOptions.key;

public class DataSetTestEnvironment extends TestEnvironment {


    private final Runner runner;

    public DataSetTestEnvironment(MiniCluster executor, int parallelism) {
        super(executor, parallelism, false);
        runner = new Runner(executor) {
            @Override
            protected void executeEnvironment() throws Throwable {
                TestStreamEnvironment.setAsContext(executor, parallelism);
                try {
                    execute();
                } finally {
                    TestStreamEnvironment.unsetAsContext();
                }
            }
        };
    }

    /**
     * Factory method to startWith a new instance, providing a
     * new instance of {@link MiniCluster}
     *
     * @param parallelism global setting for parallel execution.
     * @return new instance of {@link DataSetTestEnvironment}
     * @throws Exception
     */
    public static DataSetTestEnvironment createTestEnvironment(int parallelism) {

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

        return new DataSetTestEnvironment(miniCluster, parallelism);
    }

    public <T> DataSet<T> createTestSet(Input<T> input) {
        return super.fromCollection(input.getInput());
    }

    /**
     * Creates a TestOutputFormat to verify the output.
     * Using a {@link OutputVerifier}
     *
     * @param verifier {@link OutputVerifier} which will be
     *                 used to verify the received records.
     * @param <IN>     type of the input
     * @return the created {@link TestOutputFormat}.
     */
    public <IN> TestOutputFormat<IN> createTestOutputFormat(OutputVerifier<IN> verifier) {
        VerifyFinishedTrigger trigger = new DefaultTestTrigger();
        int instance = runner.registerListener(verifier, trigger);
        TestOutputFormat<IN> format = new TestOutputFormat<IN>(instance, runner.getRingBuffer());
        return format;
    }

    /**
     * Creates a TestOutputFormat to verify the output.
     * The environment will register a port
     *
     * @param verifier which will be used to verify the received records
     * @param <IN>     type of the input
     * @return the created sink.
     */
    public <IN> TestOutputFormat<IN> createTestOutputFormat(OutputVerifier<IN> verifier,
                                                            VerifyFinishedTrigger trigger) {
        int instance = runner.registerListener(verifier, trigger);
        TestOutputFormat<IN> format = new TestOutputFormat<IN>(instance, runner.getRingBuffer());
        return format;
    }

    /**
     * This method can be used to check if the environment has been
     * stopped prematurely by e.g. a timeout.
     *
     * @return true if has been stopped forcefully.
     */
    public Boolean hasBeenStopped() {
        return runner.hasBeenStopped();
    }

    /**
     * Getter for the timeout interval
     * after the test execution gets stopped.
     *
     * @return timeout in milliseconds
     */
    public Long getTimeoutInterval() {
        return runner.getTimeoutInterval();
    }

    /**
     * Setter for the timeout interval
     * after the test execution gets stopped.
     *
     * @param interval in milliseconds.
     */
    public void setTimeoutInterval(long interval) {
        runner.setTimeoutInterval(interval);
    }

    public void executeTest() throws Throwable {
        runner.executeTest();
    }

}
