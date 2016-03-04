/*
 * Copyright 2016 Palantir Technologies, Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.palantir.docker.compose;

import com.google.common.collect.ImmutableMap;
import com.jayway.awaitility.Awaitility;
import com.jayway.awaitility.core.ConditionTimeoutException;
import com.palantir.docker.compose.configuration.DockerComposeFiles;
import com.palantir.docker.compose.connection.Container;
import com.palantir.docker.compose.connection.ContainerCache;
import com.palantir.docker.compose.connection.DockerMachine;
import com.palantir.docker.compose.connection.DockerPort;
import com.palantir.docker.compose.execution.DockerCompose;
import com.palantir.docker.compose.logging.DoNothingLogCollector;
import com.palantir.docker.compose.logging.FileLogCollector;
import com.palantir.docker.compose.logging.LogCollector;
import org.apache.commons.lang3.Validate;
import org.joda.time.Duration;
import org.junit.rules.ExternalResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static java.util.stream.Collectors.toMap;
import static org.joda.time.Duration.standardMinutes;

public class DockerComposition extends ExternalResource {

    private static final Logger log = LoggerFactory.getLogger(DockerComposition.class);

    private final DockerCompose dockerComposeProcess;
    private final ContainerCache containers;
    private final Map<Container, Function<Container, Boolean>> servicesToWaitFor;
    private final Duration serviceTimeout;
    private final LogCollector logCollector;

    public static DockerCompositionBuilder of(String dockerComposeFile) {
        return of(DockerComposeFiles.from(dockerComposeFile));
    }

    public static DockerCompositionBuilder of(DockerComposeFiles dockerComposeFiles) {
        return of(dockerComposeFiles,
                  DockerMachine.localMachine()
                               .build());
    }

    public static DockerCompositionBuilder of(String dockerComposeFile, DockerMachine dockerMachine) {
        return of(DockerComposeFiles.from(dockerComposeFile), dockerMachine);
    }

    public static DockerCompositionBuilder of(DockerComposeFiles dockerComposeFiles, DockerMachine dockerMachine) {
        return of(new DockerCompose(dockerComposeFiles, dockerMachine));
    }

    public static DockerCompositionBuilder of(DockerCompose executable) {
        return new DockerCompositionBuilder(executable);
    }

    private DockerComposition(DockerCompose dockerComposeProcess,
                              Map<Container, Function<Container, Boolean>> servicesToWaitFor,
                              Duration serviceTimeout,
                              LogCollector logCollector,
                              ContainerCache containers) {
        this.dockerComposeProcess = dockerComposeProcess;
        this.servicesToWaitFor = ImmutableMap.copyOf(servicesToWaitFor);
        this.serviceTimeout = serviceTimeout;
        this.logCollector = logCollector;
        this.containers = containers;
    }

    @Override
    public void before() throws IOException, InterruptedException {
        log.debug("Starting docker-compose cluster");
        dockerComposeProcess.build();
        dockerComposeProcess.up();

        log.debug("Starting log collection");

        logCollector.startCollecting(dockerComposeProcess);
        log.debug("Waiting for services");
        servicesToWaitFor.entrySet().forEach(serviceCheck -> waitForService(serviceCheck.getKey(), serviceCheck.getValue()));
        log.debug("docker-compose cluster started");
    }

    private void waitForService(Container service, Function<Container, Boolean> serviceCheck) {
        log.debug("Waiting for service '{}'", service);
        try {
            Awaitility.await()
                .pollInterval(50, TimeUnit.MILLISECONDS)
                .atMost(serviceTimeout.getMillis(), TimeUnit.MILLISECONDS)
                .until(() -> serviceCheck.apply(service));
        } catch (ConditionTimeoutException e) {
            throw new IllegalStateException("Container '" + service.getContainerName() + "' failed to pass startup check");
        }
    }

    @Override
    public void after() {
        try {
            log.debug("Killing docker-compose cluster");
            dockerComposeProcess.down();
            dockerComposeProcess.kill();
            dockerComposeProcess.rm();
            logCollector.stopCollecting();
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("Error cleaning up docker compose cluster", e);
        }
    }

    public DockerPort portOnContainerWithExternalMapping(String container, int portNumber) throws IOException, InterruptedException {
        return containers.get(container)
                         .portMappedExternallyTo(portNumber);
    }

    public DockerPort portOnContainerWithInternalMapping(String container, int portNumber) throws IOException, InterruptedException {
        return containers.get(container)
                         .portMappedInternallyTo(portNumber);
    }

    public static class DockerCompositionBuilder {

        private static final Duration DEFAULT_TIMEOUT = standardMinutes(2);
        private final Map<String, Function<Container, Boolean>> containersToWaitFor = new HashMap<>();
        private final DockerCompose dockerComposeProcess;
        private final ContainerCache containers;
        private Duration serviceTimeout = standardMinutes(2);
        private LogCollector logCollector = new DoNothingLogCollector();

        public DockerCompositionBuilder(DockerCompose dockerComposeProcess) {
            this.dockerComposeProcess = dockerComposeProcess;
            this.containers = new ContainerCache(dockerComposeProcess);
        }

        public static Function<Container, Boolean> toHaveAllPortsOpen() {
            return container -> container.waitForPorts(DEFAULT_TIMEOUT);
        }

        public static Function<Container, Boolean> toRespondOverHttp(int internalPort, Function<DockerPort, String> urlFunction) {
            return (container) -> container.waitForHttpPort(internalPort, urlFunction, DEFAULT_TIMEOUT);
        }

        public DockerCompositionBuilder waitingForService(String serviceName, Function<Container, Boolean> check) {
            containersToWaitFor.put(serviceName, check);
            return this;
        }

        public DockerCompositionBuilder serviceTimeout(Duration timeout) {
            this.serviceTimeout = timeout;
            return this;
        }

        public DockerCompositionBuilder saveLogsTo(String path) {
            File logDirectory = new File(path);
            Validate.isTrue(!logDirectory.isFile(), "Log directory cannot be a file");
            if (!logDirectory.exists()) {
                Validate.isTrue(logDirectory.mkdirs(), "Error making log directory");
            }
            this.logCollector = new FileLogCollector(logDirectory);
            return this;
        }

        public DockerComposition build() {
            Map<Container, Function<Container, Boolean>> servicesToWaitFor = containersToWaitFor.entrySet()
                                                                                                .stream()
                                                                                                .collect(toMap(e -> containers.get(e.getKey()), Map.Entry::getValue));
            return new DockerComposition(dockerComposeProcess, servicesToWaitFor, serviceTimeout, logCollector, containers);
        }

    }

}
