/*
 * Copyright (c) 2019 Contributors to the Eclipse Foundation
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.ditto.services.utils.akka.logging;

import javax.annotation.concurrent.NotThreadSafe;

import org.slf4j.Logger;

/**
 * This interface represents a SLF4J {@link Logger} which additionally is {@link AutoCloseable}.
 * The semantic of the {@link #close()} method is defined by its implementation.
 */
@NotThreadSafe
public interface AutoCloseableSlf4jLogger extends Logger, AutoCloseable {

    @Override
    void close();

}
