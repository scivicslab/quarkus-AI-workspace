package com.scivicslab.lxdpups.kafka;

import io.quarkus.kafka.client.serialization.ObjectMapperDeserializer;

/**
 * Jackson-based Kafka deserializer for {@link ContainerCommand}.
 */
public class ContainerCommandDeserializer extends ObjectMapperDeserializer<ContainerCommand> {
    public ContainerCommandDeserializer() {
        super(ContainerCommand.class);
    }
}
