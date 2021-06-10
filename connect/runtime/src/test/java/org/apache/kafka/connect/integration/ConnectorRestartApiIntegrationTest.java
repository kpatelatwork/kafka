/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.kafka.connect.integration;

import org.apache.kafka.connect.storage.StringConverter;
import org.apache.kafka.connect.util.clusters.EmbeddedConnectCluster;
import org.apache.kafka.test.IntegrationTest;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.rules.TestRule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.core.Response;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.apache.kafka.connect.integration.MonitorableSourceConnector.TOPIC_CONFIG;
import static org.apache.kafka.connect.runtime.ConnectorConfig.CONNECTOR_CLASS_CONFIG;
import static org.apache.kafka.connect.runtime.ConnectorConfig.KEY_CONVERTER_CLASS_CONFIG;
import static org.apache.kafka.connect.runtime.ConnectorConfig.TASKS_MAX_CONFIG;
import static org.apache.kafka.connect.runtime.ConnectorConfig.VALUE_CONVERTER_CLASS_CONFIG;
import static org.apache.kafka.connect.runtime.TopicCreationConfig.DEFAULT_TOPIC_CREATION_PREFIX;
import static org.apache.kafka.connect.runtime.TopicCreationConfig.PARTITIONS_CONFIG;
import static org.apache.kafka.connect.runtime.TopicCreationConfig.REPLICATION_FACTOR_CONFIG;
import static org.apache.kafka.connect.runtime.WorkerConfig.CONNECTOR_CLIENT_POLICY_CLASS_CONFIG;
import static org.apache.kafka.connect.runtime.WorkerConfig.OFFSET_COMMIT_INTERVAL_MS_CONFIG;
import static org.apache.kafka.connect.util.clusters.EmbeddedConnectClusterAssertions.CONNECTOR_SETUP_DURATION_MS;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;


/**
 * Test connectors restart API use cases.
 */
@Category(IntegrationTest.class)
public class ConnectorRestartApiIntegrationTest {
    private static final Logger log = LoggerFactory.getLogger(ConnectorRestartApiIntegrationTest.class);

    private static final long OFFSET_COMMIT_INTERVAL_MS = TimeUnit.SECONDS.toMillis(30);
    private static final int ONE_WORKER = 1;
    private static final int NUM_TASKS = 4;
    private static final int MESSAGES_PER_POLL = 10;
    private static final String CONNECTOR_NAME = "simple-source";
    private static final String TOPIC_NAME = "test-topic";

    private EmbeddedConnectCluster.Builder connectBuilder;
    private EmbeddedConnectCluster connect;
    private Map<String, String> workerProps;
    private Properties brokerProps;
    private ConnectorHandle connectorHandle;

    @Rule
    public TestRule watcher = ConnectIntegrationTestUtils.newTestWatcher(log);

    @Before
    public void setup() {
        // setup Connect worker properties
        workerProps = new HashMap<>();
        workerProps.put(OFFSET_COMMIT_INTERVAL_MS_CONFIG, String.valueOf(OFFSET_COMMIT_INTERVAL_MS));
        workerProps.put(CONNECTOR_CLIENT_POLICY_CLASS_CONFIG, "All");

        // setup Kafka broker properties
        brokerProps = new Properties();
        brokerProps.put("auto.create.topics.enable", String.valueOf(false));

        // build a Connect cluster backed by Kafka and Zk
        connectBuilder = connectBuilderWithNumWorkers(ONE_WORKER); // true is the default, setting here as example
        // get connector handles before starting test.
        connectorHandle = RuntimeHandles.get().connectorHandle(CONNECTOR_NAME);
    }

    private EmbeddedConnectCluster.Builder connectBuilderWithNumWorkers(int numWorkers) {
        return new EmbeddedConnectCluster.Builder()
                .name("connect-cluster")
                .numWorkers(numWorkers)
                .workerProps(workerProps)
                .brokerProps(brokerProps)
                .maskExitProcedures(true);
    }

    @After
    public void close() {
        RuntimeHandles.get().deleteConnector(CONNECTOR_NAME);
        // stop all Connect, Kafka and Zk threads.
        connect.stop();
    }

    @Test
    public void testRestartUnknownConnectorNoParams() throws Exception {
        String connectorName = "Unknown";
        connect = connectBuilder.build();
        // start the clusters
        connect.start();

        // Call the Restart API
        String restartEndpoint = connect.endpointForResource(
                String.format("connectors/%s/restart", connectorName));
        Response response = connect.requestPost(restartEndpoint, "", Collections.emptyMap());
        assertEquals(Response.Status.NOT_FOUND.getStatusCode(), response.getStatus());

    }

    @Test
    public void testRestartUnknownConnector() throws Exception {
        restartUnknownConnector(false, false);
        restartUnknownConnector(false, true);
        restartUnknownConnector(true, false);
        restartUnknownConnector(true, true);
    }

    private void restartUnknownConnector(boolean onlyFailed, boolean includeTasks) throws Exception {
        String connectorName = "Unknown";
        connect = connectBuilder.build();
        // start the clusters
        connect.start();

        // Call the Restart API
        String restartEndpoint = connect.endpointForResource(
                String.format("connectors/%s/restart?onlyFailed=" + onlyFailed + "&includeTasks=" + includeTasks, connectorName));
        Response response = connect.requestPost(restartEndpoint, "", Collections.emptyMap());
        assertEquals(Response.Status.NOT_FOUND.getStatusCode(), response.getStatus());
    }

    @Test
    public void testRestartOnlyConnector() throws Exception {
        runningConnectorAndTasksRestart(false, false, 1, allTasksExpectedRestarts(0), false);
    }

    @Test
    public void testRestartBoth() throws Exception {
        runningConnectorAndTasksRestart(false, true, 1, allTasksExpectedRestarts(1), false);
    }

    @Test
    public void testRestartNoopOnlyConnector() throws Exception {
        runningConnectorAndTasksRestart(true, false, 0, allTasksExpectedRestarts(0), true);
    }

    @Test
    public void testRestartNoopBoth() throws Exception {
        runningConnectorAndTasksRestart(true, true, 0, allTasksExpectedRestarts(0), true);
    }

    @Test
    public void testFailedConnectorRestartOnlyConnector() throws Exception {
        failedConnectorRestart(false, false, 1);
    }

    @Test
    public void testFailedConnectorRestartBoth() throws Exception {
        failedConnectorRestart(false, true, 1);
    }

    @Test
    public void testFailedConnectorRestartBoth2() throws Exception {
        failedConnectorRestart(true, true, 1);
    }

    @Test
    public void testFailedTasksRestartOnlyConnector() throws Exception {
        failedTasksRestart(false, false, 1, allTasksExpectedRestarts(0), buildAllTasksToFail(), false);
    }

    @Test
    public void testFailedTasksRestartOnlyTasks() throws Exception {
        failedTasksRestart(true, true, 0, allTasksExpectedRestarts(1), buildAllTasksToFail(), false);
    }

    @Test
    public void testFailedTasksRestartNoop() throws Exception {
        failedTasksRestart(true, false, 0, allTasksExpectedRestarts(0), buildAllTasksToFail(), true);
    }

    @Test
    public void testFailedTasksRestartBoth() throws Exception {
        failedTasksRestart(false, true, 1, allTasksExpectedRestarts(1), buildAllTasksToFail(), false);
    }

    @Test
    public void testOneFailedTasksRestartOnlyOneTasks() throws Exception {
        Set<String> tasksToFail = Collections.singleton(taskId(1));
        failedTasksRestart(true, true, 0, buildExpectedTasksRestarts(tasksToFail), tasksToFail, false);
    }

    @Test
    public void testMultiWorkerRestartOnlyConnector() throws Exception {
        runningConnectorAndTasksRestart(false, false, 1, allTasksExpectedRestarts(0), false, 4);
    }

    @Test
    public void testMultiWorkerRestartBoth() throws Exception {
        runningConnectorAndTasksRestart(false, true, 1, allTasksExpectedRestarts(1), false, 4);
    }

    private void runningConnectorAndTasksRestart(boolean onlyFailed, boolean includeTasks, int expectedConnectorRestarts, Map<String, Integer> expectedTasksRestarts, boolean noopRequest) throws Exception {
        runningConnectorAndTasksRestart(onlyFailed, includeTasks, expectedConnectorRestarts, expectedTasksRestarts, noopRequest, ONE_WORKER);
    }

    private void runningConnectorAndTasksRestart(boolean onlyFailed, boolean includeTasks, int expectedConnectorRestarts, Map<String, Integer> expectedTasksRestarts, boolean noopRequest, int numWorkers) throws Exception {
        connectBuilder = connectBuilderWithNumWorkers(numWorkers);
        connect = connectBuilder.build();
        // start the clusters
        connect.start();

        // setup up props for the source connector
        Map<String, String> props = defaultSourceConnectorProps(TOPIC_NAME);

        connect.assertions().assertExactlyNumWorkersAreUp(numWorkers,
                "Initial group of workers did not start in time.");

        // Try to start the connector and its single task.
        connect.configureConnector(CONNECTOR_NAME, props);

        connect.assertions().assertConnectorAndAtLeastNumTasksAreRunning(CONNECTOR_NAME, NUM_TASKS,
                "Connector tasks are not all in running state.");

        StartAndStopCounterSnapshot beforeSnapshot = connectorHandle.startAndStopCounter().countsSnapshot();
        Map<String, StartAndStopCounterSnapshot> beforeTasksSnapshot = connectorHandle.tasks().stream().collect(Collectors.toMap(TaskHandle::taskId, task -> task.startAndStopCounter().countsSnapshot()));

        StartAndStopLatch stopLatch = connectorHandle.expectedStops(expectedConnectorRestarts, expectedTasksRestarts, includeTasks);
        StartAndStopLatch startLatch = connectorHandle.expectedStarts(expectedConnectorRestarts, expectedTasksRestarts, includeTasks);

        // Call the Restart API
        String restartEndpoint = connect.endpointForResource(
                String.format("connectors/%s/restart?onlyFailed=" + onlyFailed + "&includeTasks=" + includeTasks, CONNECTOR_NAME));
        connect.requestPost(restartEndpoint, "", Collections.emptyMap());

        if (noopRequest) {
            //for noop requests as everything is in RUNNING state, we need to wait for CONNECTOR_SETUP_DURATION_MS to ensure no unexpected restart events were fired
            Thread.sleep(CONNECTOR_SETUP_DURATION_MS);
        }

        // Wait for the connector to be stopped
        assertTrue("Failed to stop connector and tasks within "
                        + CONNECTOR_SETUP_DURATION_MS + "ms",
                stopLatch.await(CONNECTOR_SETUP_DURATION_MS, TimeUnit.MILLISECONDS));

        connect.assertions().assertConnectorAndAtLeastNumTasksAreRunning(CONNECTOR_NAME, NUM_TASKS,
                "Connector tasks are not all in running state.");
        // Expect that the connector has started again
        assertTrue("Failed to start connector and tasks within "
                        + CONNECTOR_SETUP_DURATION_MS + "ms",
                startLatch.await(CONNECTOR_SETUP_DURATION_MS, TimeUnit.MILLISECONDS));
        StartAndStopCounterSnapshot afterSnapshot = connectorHandle.startAndStopCounter().countsSnapshot();

        assertEquals(beforeSnapshot.starts() + expectedConnectorRestarts, afterSnapshot.starts());
        assertEquals(beforeSnapshot.stops() + expectedConnectorRestarts, afterSnapshot.stops());
        if (numWorkers == 1) {
            //validate tasks stop/start counts only in single worker test because the multi worker rebalance triggers stop/start on task and this make the exact counts unpredictable
            connectorHandle.tasks().forEach(t -> {
                StartAndStopCounterSnapshot afterTaskSnapshot = t.startAndStopCounter().countsSnapshot();
                assertEquals(beforeTasksSnapshot.get(t.taskId()).starts() + expectedTasksRestarts.get(t.taskId()), afterTaskSnapshot.starts());
                assertEquals(beforeTasksSnapshot.get(t.taskId()).stops() + expectedTasksRestarts.get(t.taskId()), afterTaskSnapshot.stops());
            });
        }
    }

    private void failedConnectorRestart(boolean onlyFailed, boolean includeTasks, int expectedConnectorRestarts) throws Exception {
        //as connector is failed we expect 0 task to be started
        Map<String, Integer> expectedTasksStarts = allTasksExpectedRestarts(0);

        connect = connectBuilder.build();
        // start the clusters
        connect.start();

        // setup up props for the source connector
        Map<String, String> props = defaultSourceConnectorProps(TOPIC_NAME);
        props.put("connector.start.inject.error", "true");

        connect.assertions().assertExactlyNumWorkersAreUp(ONE_WORKER,
                "Initial group of workers did not start in time.");

        // Try to start the connector and its single task.
        connect.configureConnector(CONNECTOR_NAME, props);

        connect.assertions().assertConnectorIsFailedAndTasksHaveFailed(CONNECTOR_NAME, 0,
                "Connector or tasks are in running state.");

        StartAndStopCounterSnapshot beforeSnapshot = connectorHandle.startAndStopCounter().countsSnapshot();

        StartAndStopLatch startLatch = connectorHandle.expectedStarts(expectedConnectorRestarts, expectedTasksStarts, includeTasks);

        // Call the Restart API
        String restartEndpoint = connect.endpointForResource(
                String.format("connectors/%s/restart?onlyFailed=" + onlyFailed + "&includeTasks=" + includeTasks, CONNECTOR_NAME));
        connect.requestPost(restartEndpoint, "", Collections.emptyMap());

        connect.assertions().assertConnectorIsFailedAndTasksHaveFailed(CONNECTOR_NAME, 0,
                "Connector tasks are not all in running state.");
        // Expect that the connector has started again
        assertTrue("Failed to start connector and tasks after coordinator failure within "
                        + CONNECTOR_SETUP_DURATION_MS + "ms",
                startLatch.await(CONNECTOR_SETUP_DURATION_MS, TimeUnit.MILLISECONDS));
        StartAndStopCounterSnapshot afterSnapshot = connectorHandle.startAndStopCounter().countsSnapshot();

        assertEquals(beforeSnapshot.starts() + expectedConnectorRestarts, afterSnapshot.starts());
    }

    private void failedTasksRestart(boolean onlyFailed, boolean includeTasks, int expectedConnectorRestarts, Map<String, Integer> expectedTasksRestarts, Set<String> tasksToFail, boolean noopRequest) throws Exception {
        connect = connectBuilder.build();
        // start the clusters
        connect.start();

        // setup up props for the source connector
        Map<String, String> props = defaultSourceConnectorProps(TOPIC_NAME);
        tasksToFail.forEach(taskId -> props.put("task-" + taskId + ".start.inject.error", "true"));

        connect.assertions().assertExactlyNumWorkersAreUp(ONE_WORKER,
                "Initial group of workers did not start in time.");

        // Try to start the connector and its single task.
        connect.configureConnector(CONNECTOR_NAME, props);

        connect.assertions().assertConnectorIsRunningAndNumTasksHaveFailed(CONNECTOR_NAME, NUM_TASKS, tasksToFail.size(),
                "Connector tasks are in running state.");

        StartAndStopCounterSnapshot beforeSnapshot = connectorHandle.startAndStopCounter().countsSnapshot();
        Map<String, StartAndStopCounterSnapshot> beforeTasksSnapshot = connectorHandle.tasks().stream().collect(Collectors.toMap(TaskHandle::taskId, task -> task.startAndStopCounter().countsSnapshot()));

        StartAndStopLatch stopLatch = connectorHandle.expectedStops(expectedConnectorRestarts, expectedTasksRestarts, includeTasks);
        StartAndStopLatch startLatch = connectorHandle.expectedStarts(expectedConnectorRestarts, expectedTasksRestarts, includeTasks);

        // Call the Restart API
        String restartEndpoint = connect.endpointForResource(
                String.format("connectors/%s/restart?onlyFailed=" + onlyFailed + "&includeTasks=" + includeTasks, CONNECTOR_NAME));
        connect.requestPost(restartEndpoint, "", Collections.emptyMap());

        if (noopRequest) {
            //for noop requests as everything is in RUNNING state, we need to wait for CONNECTOR_SETUP_DURATION_MS to ensure no unexpected restart events were fired
            Thread.sleep(CONNECTOR_SETUP_DURATION_MS);
        }

        // Wait for the connector to be stopped
        assertTrue("Failed to stop connector and tasks within "
                        + CONNECTOR_SETUP_DURATION_MS + "ms",
                stopLatch.await(CONNECTOR_SETUP_DURATION_MS, TimeUnit.MILLISECONDS));

        connect.assertions().assertConnectorIsRunningAndNumTasksHaveFailed(CONNECTOR_NAME, NUM_TASKS, tasksToFail.size(),
                "Connector tasks are not all in running state.");
        // Expect that the connector has started again
        assertTrue("Failed to start connector and tasks within "
                        + CONNECTOR_SETUP_DURATION_MS + "ms",
                startLatch.await(CONNECTOR_SETUP_DURATION_MS, TimeUnit.MILLISECONDS));

        StartAndStopCounterSnapshot afterSnapshot = connectorHandle.startAndStopCounter().countsSnapshot();

        assertEquals(beforeSnapshot.starts() + expectedConnectorRestarts, afterSnapshot.starts());
        assertEquals(beforeSnapshot.stops() + expectedConnectorRestarts, afterSnapshot.stops());
        connectorHandle.tasks().forEach(t -> {
            StartAndStopCounterSnapshot afterTaskSnapshot = t.startAndStopCounter().countsSnapshot();
            assertEquals(beforeTasksSnapshot.get(t.taskId()).starts() + expectedTasksRestarts.get(t.taskId()), afterTaskSnapshot.starts());
            assertEquals(beforeTasksSnapshot.get(t.taskId()).stops() + expectedTasksRestarts.get(t.taskId()), afterTaskSnapshot.stops());
        });
    }

    private Set<String> buildAllTasksToFail() {
        Set<String> tasksToFail = new HashSet<>();
        for (int i = 0; i < NUM_TASKS; i++) {
            String taskId = taskId(i);
            tasksToFail.add(taskId);
        }
        return tasksToFail;
    }

    private Map<String, Integer> allTasksExpectedRestarts(int expectedRestarts) {
        Map<String, Integer> expectedTasksRestarts = new HashMap<>();
        for (int i = 0; i < NUM_TASKS; i++) {
            String taskId = taskId(i);
            expectedTasksRestarts.put(taskId, expectedRestarts);
        }
        return expectedTasksRestarts;
    }

    private Map<String, Integer> buildExpectedTasksRestarts(Set<String> tasksToFail) {
        Map<String, Integer> expectedTasksRestarts = new HashMap<>();
        for (int i = 0; i < NUM_TASKS; i++) {
            String taskId = taskId(i);
            expectedTasksRestarts.put(taskId, tasksToFail.contains(taskId) ? 1 : 0);
        }
        return expectedTasksRestarts;
    }

    private String taskId(int i) {
        return CONNECTOR_NAME + "-" + i;
    }

    private Map<String, String> defaultSourceConnectorProps(String topic) {
        // setup up props for the source connector
        Map<String, String> props = new HashMap<>();
        props.put(CONNECTOR_CLASS_CONFIG, MonitorableSourceConnector.class.getSimpleName());
        props.put(TASKS_MAX_CONFIG, String.valueOf(NUM_TASKS));
        props.put(TOPIC_CONFIG, topic);
        props.put("throughput", "10");
        props.put("messages.per.poll", String.valueOf(MESSAGES_PER_POLL));
        props.put(KEY_CONVERTER_CLASS_CONFIG, StringConverter.class.getName());
        props.put(VALUE_CONVERTER_CLASS_CONFIG, StringConverter.class.getName());
        props.put(DEFAULT_TOPIC_CREATION_PREFIX + REPLICATION_FACTOR_CONFIG, String.valueOf(1));
        props.put(DEFAULT_TOPIC_CREATION_PREFIX + PARTITIONS_CONFIG, String.valueOf(1));
        return props;
    }
}