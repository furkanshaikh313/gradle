/*
 * Copyright 2024 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.api.tasks.testing;

import org.gradle.api.Incubating;

import java.time.Instant;

/**
 * Generates test events.
 *
 * @since 8.12
 */
@Incubating
public interface TestEventGenerator extends AutoCloseable {
    /**
     * Emit a start event for the test.
     *
     * @param startTime the time the test started
     * @since 8.12
     */
    void started(Instant startTime);

    /**
     * Emit a output event for the test. May be called multiple times. May not be called before {@link #started(Instant)}.
     *
     * @param logTime the time the output was logged, must be between the start and end times of the test
     * @param destination the destination of the output
     * @param output some output from the test
     * @since 8.12
     */
    void output(Instant logTime, TestOutputEvent.Destination destination, String output);

    /**
     * Emit a successful completion event for the test. May not be called before {@link #started(Instant)}.
     *
     * @param endTime the time the test completed
     * @since 8.12
     */
    void succeeded(Instant endTime);

    /**
     * Emit a skipped event for the test. May not be called before {@link #started(Instant)}.
     *
     * @param endTime the time the test completed
     * @since 8.12
     */
    void skipped(Instant endTime);

    /**
     * Emit a failure event for the test. May not be called before {@link #started(Instant)}.
     *
     * @param endTime the time the test completed
     * @since 8.12
     */
    void failed(Instant endTime);

    // TODO add more details to the failure
    /**
     * Emit a failure event for the test. May not be called before {@link #started(Instant)}.
     *
     * @param endTime the time the test completed
     * @param message the failure message
     * @since 8.12
     */
    void failed(Instant endTime, String message);

    /**
     * Close the generator. No further events can be emitted after this.
     *
     * @since 8.12
     */
    @Override
    void close();
}
