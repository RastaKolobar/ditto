/*
 * Copyright (c) 2017-2018 Bosch Software Innovations GmbH.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/org/documents/epl-2.0/index.php
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.ditto.services.utils.test;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import javax.annotation.concurrent.Immutable;

import org.atteo.classindex.ClassIndex;
import org.eclipse.ditto.json.JsonObject;
import org.eclipse.ditto.model.base.headers.DittoHeaders;
import org.eclipse.ditto.model.base.json.JsonParsableEvent;
import org.eclipse.ditto.signals.events.base.Event;
import org.junit.Before;
import org.junit.Test;
import org.mutabilitydetector.internal.javassist.Modifier;

@Immutable
public class GlobalEventRegistryTestCases {

    private final List<Class<?>> samples;
    private List<Class<?>> jsonParsableEvents;

    @Before
    public void setup() {
        jsonParsableEvents = StreamSupport.stream(ClassIndex.getAnnotated(JsonParsableEvent.class).spliterator(), true)
                .filter(c -> !c.getSimpleName().equals("TestEvent"))
                .collect(Collectors.toList());
    }

    /**
     * Creates a new instance of test cases.
     *
     * @param samples a List of classes that are annotated with {@link JsonParsableEvent}. This list should contain
     * at least one command of each package containing an Event in the classpath of the respective service.
     */
    protected GlobalEventRegistryTestCases(final List<Class<?>> samples) {
        this.samples = new ArrayList<>(samples);
    }

    /**
     * This test should verify that all modules containing events that need to be deserialized
     * in this service are still in the classpath. Therefore one sample of each module is placed in {@code samples}.
     */
    @Test
    public void sampleCheckForEventFromEachModule() {
        assertThat(jsonParsableEvents).containsAll(samples);
    }

    /**
     * This test should enforce to keep {@code samples} up to date.
     * If this test fails add an event of each missing package to samples.
     */
    @Test
    public void ensureSamplesAreComplete() {
        final Set<Package> packagesOfAnnotated = getPackagesDistinct(jsonParsableEvents);
        final Set<Package> packagesOfSamples = getPackagesDistinct(samples);

        assertThat(packagesOfSamples).containsAll(packagesOfAnnotated);
    }

    private static Set<Package> getPackagesDistinct(final Iterable<Class<?>> classes) {
        return StreamSupport.stream(classes.spliterator(), false)
                .map(Class::getPackage)
                .collect(Collectors.toSet());
    }

    @Test
    public void allEventsRegistered() {
        final List<Class<? extends Event>> eventSubclasses = getEventSubclasses();
        eventSubclasses.forEach(e -> assertThat(e.isAnnotationPresent(JsonParsableEvent.class))
                .as("Check that '%s' is annotated with JsonParsableEvent.", e.getName())
                .isTrue());
    }

    @Test
    public void allRegisteredEventsContainAMethodToParseFromJson() throws NoSuchMethodException {

        for (final Class<?> jsonParsableEvent : jsonParsableEvents) {
            final JsonParsableEvent annotation = jsonParsableEvent.getAnnotation(JsonParsableEvent.class);
            assertAnnotationIsValid(annotation, jsonParsableEvent);
        }
    }

    private static void assertAnnotationIsValid(final JsonParsableEvent annotation, final Class<?> cls)
            throws NoSuchMethodException {
        assertThat(cls.getMethod(annotation.method(), JsonObject.class, DittoHeaders.class))
                .as("Check that JsonParsableEvent of '%s' has correct methodName.", cls.getName())
                .isNotNull();
    }

    private static List<Class<? extends Event>> getEventSubclasses() {
        return StreamSupport.stream(ClassIndex.getSubclasses(Event.class).spliterator(), true)
                .filter(c -> {
                    final int m = c.getModifiers();
                    return !(Modifier.isAbstract(m) || Modifier.isInterface(m));
                }).collect(Collectors.toList());
    }
}
