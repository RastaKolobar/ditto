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
package org.eclipse.ditto.services.concierge.enforcement.config;

import java.time.Duration;

import javax.annotation.concurrent.Immutable;

import org.eclipse.ditto.services.utils.config.KnownConfigValue;

/**
 * Provides configuration settings for Concierge enforcement behaviour.
 * <p>
 * Java serialization is supported for {@code EnforcementConfig}.
 * </p>
 */
@Immutable
public interface EnforcementConfig {

    /**
     * Returns the ask timeout duration: the duration to wait for entity shard regions.
     *
     * @return the ask timeout duration.
     */
    Duration getAskTimeout();

    /**
     * An enumeration of the known config path expressions and their associated default values for
     * {@code EnforcementConfig}.
     */
    enum EnforcementConfigValue implements KnownConfigValue {

        /**
         * The ask timeout duration: the duration to wait for entity shard regions.
         */
        ASK_TIMEOUT("ask-timeout", Duration.ofSeconds(10));

        private final String path;
        private final Object defaultValue;

        private EnforcementConfigValue(final String thePath, final Object theDefaultValue) {
            path = thePath;
            defaultValue = theDefaultValue;
        }

        @Override
        public String getConfigPath() {
            return path;
        }

        @Override
        public Object getDefaultValue() {
            return defaultValue;
        }

    }

}
