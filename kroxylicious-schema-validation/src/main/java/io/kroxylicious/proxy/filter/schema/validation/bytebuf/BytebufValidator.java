/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.proxy.filter.schema.validation.bytebuf;

import java.nio.ByteBuffer;

import org.apache.kafka.common.record.Record;

import io.kroxylicious.proxy.filter.schema.validation.Result;

/**
 * Used to validate a ByteBuffer against some criteria
 */
public interface BytebufValidator {

    /**
     * Validate a ByteBuffer.
     * <p>
     * You can expect this ByteBuffer instance to not be re-used outside
     * this validator so you don't have to mark/reset it after use. Though it will
     * likely be backed by a shared buffer so do not write to it.
     * </p>
     *
     * @param buffer the buffer containing data
     * @param length the length of the value in the buffer (buffer may contain more data after the value)
     * @param record the record the buffer was extracted from
     * @param isKey true if the buffer is the key of the record, false if it is the value of the record
     * @return a valid result if the buffer is valid
     */
    Result validate(ByteBuffer buffer, int length, Record record, boolean isKey);
}
