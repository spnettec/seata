/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.seata.rm.datasource.undo.parser;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import org.apache.seata.common.Constants;
import org.apache.seata.common.executor.Initialize;
import org.apache.seata.common.loader.EnhancedServiceLoader;
import org.apache.seata.common.loader.EnhancedServiceNotFoundException;
import org.apache.seata.common.loader.LoadLevel;
import org.apache.seata.common.util.CollectionUtils;
import org.apache.seata.rm.datasource.sql.serial.SerialArray;
import org.apache.seata.rm.datasource.undo.BranchUndoLog;
import org.apache.seata.rm.datasource.undo.UndoLogParser;
import org.apache.seata.rm.datasource.undo.parser.spi.JacksonSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.exc.JacksonIOException;
import tools.jackson.core.type.WritableTypeId;
import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import tools.jackson.databind.jsontype.TypeSerializer;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.ser.std.ArraySerializerBase;

import javax.sql.rowset.serial.SerialBlob;
import javax.sql.rowset.serial.SerialClob;
import javax.sql.rowset.serial.SerialException;
import java.io.IOException;
import java.io.Reader;
import java.lang.reflect.Method;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * The type Json based undo log parser.
 *
 */
@LoadLevel(name = JacksonUndoLogParser.NAME)
public class JacksonUndoLogParser implements UndoLogParser, Initialize {

    public static final String NAME = "jackson";

    private static final Logger LOGGER = LoggerFactory.getLogger(JacksonUndoLogParser.class);

    private static final String DM_JDBC_DRIVER_DMDB_TIMESTAMP = "dm.jdbc.driver.DmdbTimestamp";

    private static final String VALUE_OF = "valueOf";

    /**
     * the zoneId for LocalDateTime
     */
    private static ZoneId zoneId = ZoneId.systemDefault();

    private ObjectMapper mapper;

    private final SimpleModule module = new SimpleModule();

    /**
     * customize serializer for java.sql.Timestamp
     */
    private final ValueSerializer timestampSerializer = new TimestampSerializer();

    /**
     * customize deserializer for java.sql.Timestamp
     */
    private final ValueDeserializer timestampDeserializer = new TimestampDeserializer();

    /**
     * customize serializer of java.sql.Blob
     */
    private final ValueSerializer blobSerializer = new BlobSerializer();

    /**
     * customize deserializer of java.sql.Blob
     */
    private final ValueDeserializer blobDeserializer = new BlobDeserializer();

    /**
     * customize serializer of java.sql.Clob
     */
    private final ValueSerializer clobSerializer = new ClobSerializer();

    /**
     * customize deserializer of java.sql.Clob
     */
    private final ValueDeserializer clobDeserializer = new ClobDeserializer();

    /**
     * customize serializer of java.time.LocalDateTime
     */
    private final ValueSerializer localDateTimeSerializer = new LocalDateTimeSerializer();

    /**
     * customize deserializer of java.time.LocalDateTime
     */
    private final ValueDeserializer localDateTimeDeserializer = new LocalDateTimeDeserializer();

    /**
     * customize serializer for dm.jdbc.driver.DmdbTimestamp
     */
    private final ValueSerializer dmdbTimestampSerializer = new DmdbTimestampSerializer();

    /**
     * customize deserializer for dm.jdbc.driver.DmdbTimestamp
     */
    private final ValueDeserializer dmdbTimestampDeserializer = new DmdbTimestampDeserializer();

    /**
     * customize serializer for org.apache.seata.rm.datasource.sql.serial.SerialArray
     */
    private final ValueSerializer serialArraySerializer = new SerialArraySerializer();

    /**
     * customize deserializer for org.apache.seata.rm.datasource.sql.serial.SerialArray
     */
    private final ValueDeserializer serialArrayDeserializer = new SerialArrayDeserializer();

    @Override
    public void init() {
        try {
            List<JacksonSerializer> jacksonSerializers = EnhancedServiceLoader.loadAll(JacksonSerializer.class);
            if (CollectionUtils.isNotEmpty(jacksonSerializers)) {
                for (JacksonSerializer jacksonSerializer : jacksonSerializers) {
                    Class type = jacksonSerializer.type();
                    ValueSerializer ser = jacksonSerializer.ser();
                    ValueDeserializer deser = jacksonSerializer.deser();
                    if (type != null) {
                        if (ser != null) {
                            module.addSerializer(type, ser);
                        }
                        if (deser != null) {
                            module.addDeserializer(type, deser);
                        }
                        LOGGER.info(
                                "jackson undo log parser load [{}].",
                                jacksonSerializer.getClass().getName());
                    }
                }
            }
        } catch (EnhancedServiceNotFoundException e) {
            LOGGER.warn("JacksonSerializer not found children class.", e);
        }

        module.addSerializer(Timestamp.class, timestampSerializer);
        module.addDeserializer(Timestamp.class, timestampDeserializer);
        module.addSerializer(SerialBlob.class, blobSerializer);
        module.addDeserializer(SerialBlob.class, blobDeserializer);
        module.addSerializer(SerialClob.class, clobSerializer);
        module.addDeserializer(SerialClob.class, clobDeserializer);
        module.addSerializer(LocalDateTime.class, localDateTimeSerializer);
        module.addDeserializer(LocalDateTime.class, localDateTimeDeserializer);
        module.addSerializer(SerialArray.class, serialArraySerializer);
        module.addDeserializer(SerialArray.class, serialArrayDeserializer);
        registerDmdbTimestampModuleIfPresent();
        mapper = JsonMapper.builder()
                .addModule(module)
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                .activateDefaultTyping(
                        BasicPolymorphicTypeValidator.builder()
                                .allowIfBaseType(Object.class)
                                .build(),
                        DefaultTyping.NON_FINAL,
                        JsonTypeInfo.As.PROPERTY)
                .enable(MapperFeature.PROPAGATE_TRANSIENT_MARKER)
                .build();
    }

    private void registerDmdbTimestampModuleIfPresent() {
        try {
            Class<?> dmdbTimestampClass = Class.forName(DM_JDBC_DRIVER_DMDB_TIMESTAMP);
            module.addSerializer(dmdbTimestampClass, dmdbTimestampSerializer);
            module.addDeserializer(dmdbTimestampClass, dmdbTimestampDeserializer);
        } catch (ClassNotFoundException e) {
            // If the DmdbTimestamp class is not found, the serializers and deserializers will not be registered.
            // This is expected behavior since not all environments will have the dm.jdbc.driver.DmdbTimestamp class.
            // Therefore, no error log is recorded to avoid confusion for users without the dm driver.
        }
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public byte[] getDefaultContent() {
        return "{}".getBytes(Constants.DEFAULT_CHARSET);
    }

    @Override
    public byte[] encode(BranchUndoLog branchUndoLog) {
        try {
            return mapper.writeValueAsBytes(branchUndoLog);
        } catch (JacksonException e) {
            LOGGER.error("json encode exception, {}", e.getMessage(), e);
            throw new RuntimeException(e);
        }
    }

    @Override
    public BranchUndoLog decode(byte[] bytes) {
        try {
            BranchUndoLog branchUndoLog;
            if (Arrays.equals(bytes, getDefaultContent())) {
                branchUndoLog = new BranchUndoLog();
            } else {
                branchUndoLog = mapper.readValue(bytes, BranchUndoLog.class);
            }
            return branchUndoLog;
        } catch (JacksonIOException e) {
            LOGGER.error("json decode exception, {}", e.getMessage(), e);
            throw new RuntimeException(e);
        }
    }

    /**
     * if necessary
     * extend {@link ArraySerializerBase}
     */
    private static class TimestampSerializer extends ValueSerializer<Timestamp> {

        @Override
        public void serializeWithType(
                Timestamp timestamp, JsonGenerator gen, SerializationContext ctxt, TypeSerializer typeSerializer) {
            JsonToken valueShape = JsonToken.VALUE_NUMBER_INT;
            // if has microseconds, serialized as an array
            if (timestamp.getNanos() % 1000000 > 0) {
                valueShape = JsonToken.START_ARRAY;
            }

            WritableTypeId typeId =
                    typeSerializer.writeTypePrefix(gen, ctxt, typeSerializer.typeId(timestamp, valueShape));
            serialize(timestamp, gen, ctxt);
            gen.writeTypeSuffix(typeId);
        }

        @Override
        public void serialize(Timestamp timestamp, JsonGenerator gen, SerializationContext ctxt) {
            try {
                gen.writeNumber(timestamp.getTime());
                // if has microseconds, serialized as an array, write the nanos to the array
                if (timestamp.getNanos() % 1000000 > 0) {
                    gen.writeNumber(timestamp.getNanos());
                }
            } catch (JacksonIOException e) {
                LOGGER.error("serialize java.sql.Timestamp error : {}", e.getMessage(), e);
            }
        }
    }

    /**
     * if necessary
     * extend {@link ValueDeserializer}
     */
    private static class TimestampDeserializer extends ValueDeserializer<Timestamp> {

        @Override
        public Timestamp deserialize(JsonParser p, DeserializationContext ctxt) {
            try {
                if (p.isExpectedStartArrayToken()) {
                    ArrayNode arrayNode = p.objectReadContext().readTree(p);
                    Timestamp timestamp = new Timestamp(arrayNode.get(0).asLong());
                    timestamp.setNanos(arrayNode.get(1).asInt());
                    return timestamp;
                } else {
                    long timestamp = p.getLongValue();
                    return new Timestamp(timestamp);
                }
            } catch (JacksonIOException e) {
                LOGGER.error("deserialize java.sql.Timestamp error : {}", e.getMessage(), e);
            }
            return null;
        }
    }

    /**
     * the class of serialize blob type
     */
    private static class BlobSerializer extends ValueSerializer<SerialBlob> {

        @Override
        public void serializeWithType(
                SerialBlob blob, JsonGenerator gen, SerializationContext ctxt, TypeSerializer typeSer) {
            WritableTypeId typeIdDef =
                    typeSer.writeTypePrefix(gen, ctxt, typeSer.typeId(blob, JsonToken.VALUE_EMBEDDED_OBJECT));
            serialize(blob, gen, ctxt);
            typeSer.writeTypeSuffix(gen, ctxt, typeIdDef);
        }

        @Override
        public void serialize(SerialBlob blob, JsonGenerator gen, SerializationContext ctxt) {
            try {
                gen.writeBinary(blob.getBytes(1, (int) blob.length()));
            } catch (SerialException e) {
                LOGGER.error("serialize java.sql.Blob error : {}", e.getMessage(), e);
            }
        }
    }

    /**
     * the class of deserialize blob type
     */
    private static class BlobDeserializer extends ValueDeserializer<SerialBlob> {

        @Override
        public SerialBlob deserialize(JsonParser p, DeserializationContext ctxt) {
            try {
                return new SerialBlob(p.getBinaryValue());
            } catch (SQLException e) {
                LOGGER.error("deserialize java.sql.Blob error : {}", e.getMessage(), e);
            }
            return null;
        }
    }

    /**
     * the class of serialize clob type
     */
    private static class ClobSerializer extends ValueSerializer<SerialClob> {

        @Override
        public void serializeWithType(
                SerialClob clob, JsonGenerator gen, SerializationContext ctxt, TypeSerializer typeSer) {
            WritableTypeId typeIdDef =
                    typeSer.writeTypePrefix(gen, ctxt, typeSer.typeId(clob, JsonToken.VALUE_EMBEDDED_OBJECT));
            serialize(clob, gen, ctxt);
            typeSer.writeTypeSuffix(gen, ctxt, typeIdDef);
        }

        @Override
        public void serialize(SerialClob clob, JsonGenerator gen, SerializationContext ctxt) {
            try (Reader r = clob.getCharacterStream()) {
                gen.writeString(r, (int) clob.length());
            } catch (SerialException e) {
                LOGGER.error("serialize java.sql.Blob error : {}", e.getMessage(), e);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private static class ClobDeserializer extends ValueDeserializer<SerialClob> {

        @Override
        public SerialClob deserialize(JsonParser p, DeserializationContext ctxt) {
            try {
                return new SerialClob(p.getValueAsString().toCharArray());
            } catch (SQLException e) {
                LOGGER.error("deserialize java.sql.Clob error : {}", e.getMessage(), e);
            }
            return null;
        }
    }

    /**
     * the class of serialize LocalDateTime type
     */
    private static class LocalDateTimeSerializer extends ValueSerializer<LocalDateTime> {

        @Override
        public void serializeWithType(
                LocalDateTime localDateTime, JsonGenerator gen, SerializationContext ctxt, TypeSerializer typeSer) {
            JsonToken valueShape = JsonToken.VALUE_NUMBER_INT;
            // if has microseconds, serialized as an array
            if (localDateTime.getNano() % 1000000 > 0) {
                valueShape = JsonToken.START_ARRAY;
            }

            WritableTypeId typeIdDef = typeSer.writeTypePrefix(gen, ctxt, typeSer.typeId(localDateTime, valueShape));
            serialize(localDateTime, gen, ctxt);
            typeSer.writeTypeSuffix(gen, ctxt, typeIdDef);
        }

        @Override
        public void serialize(LocalDateTime localDateTime, JsonGenerator gen, SerializationContext ctxt) {
            try {
                Instant instant = localDateTime.atZone(zoneId).toInstant();
                gen.writeNumber(instant.toEpochMilli());
                // if has microseconds, serialized as an array, write the nano to the array
                if (instant.getNano() % 1000000 > 0) {
                    gen.writeNumber(instant.getNano());
                }
            } catch (JacksonIOException e) {
                LOGGER.error("serialize java.time.LocalDateTime error : {}", e.getMessage(), e);
            }
        }
    }

    /**
     * the class of deserialize LocalDateTime type
     */
    private static class LocalDateTimeDeserializer extends ValueDeserializer<LocalDateTime> {

        @Override
        public LocalDateTime deserialize(JsonParser p, DeserializationContext ctxt) {
            try {
                Instant instant;
                if (p.isExpectedStartArrayToken()) {
                    ArrayNode arrayNode = p.objectReadContext().readTree(p);
                    long timestamp = arrayNode.get(0).asLong();
                    instant = Instant.ofEpochMilli(timestamp);
                    if (arrayNode.size() > 1) {
                        int nano = arrayNode.get(1).asInt();
                        instant = instant.plusNanos(nano % 1000000);
                    }
                } else {
                    long timestamp = p.getLongValue();
                    instant = Instant.ofEpochMilli(timestamp);
                }
                return LocalDateTime.ofInstant(instant, zoneId);
            } catch (Exception e) {
                LOGGER.error("deserialize java.time.LocalDateTime error : {}", e.getMessage(), e);
            }
            return null;
        }
    }

    private static class DmdbTimestampSerializer extends ValueSerializer<Object> {

        private static final String TO_INSTANT = "toInstant";
        private static final String GET_NANOS = "getNanos";

        @Override
        public void serializeWithType(
                Object dmdbTimestamp, JsonGenerator gen, SerializationContext ctxt, TypeSerializer typeSer) {
            JsonToken valueShape = JsonToken.VALUE_NUMBER_INT;
            int nanos = 0;
            try {
                nanos = getNanos(dmdbTimestamp);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            if (nanos % 1000000 > 0) {
                valueShape = JsonToken.START_ARRAY;
            }

            WritableTypeId typeIdDef = typeSer.writeTypePrefix(gen, ctxt, typeSer.typeId(dmdbTimestamp, valueShape));
            serialize(dmdbTimestamp, gen, ctxt);
            typeSer.writeTypeSuffix(gen, ctxt, typeIdDef);
        }

        @Override
        public void serialize(Object dmdbTimestamp, JsonGenerator gen, SerializationContext ctxt) {
            try {
                Instant instant = getInstant(dmdbTimestamp);
                gen.writeNumber(instant.toEpochMilli());
                // if has microseconds, serialized as an array, write the nano to the array
                int nanos = instant.getNano();
                if (nanos % 1000000 > 0) {
                    gen.writeNumber(nanos);
                }
            } catch (Exception e) {
                LOGGER.error("serialize dm.jdbc.driver.DmdbTimestamp error : {}", e.getMessage(), e);
            }
        }

        private int getNanos(Object dmdbTimestamp) throws IOException {
            try {
                Method getNanosMethod = dmdbTimestamp.getClass().getMethod(GET_NANOS);
                return (int) getNanosMethod.invoke(dmdbTimestamp);
            } catch (Exception e) {
                throw new IOException("Error getting nanos value from DmdbTimestamp", e);
            }
        }

        private Instant getInstant(Object dmdbTimestamp) throws IOException {
            try {
                Method toInstantMethod = dmdbTimestamp.getClass().getMethod(TO_INSTANT);
                return (Instant) toInstantMethod.invoke(dmdbTimestamp);
            } catch (Exception e) {
                throw new IOException("Error getting instant from DmdbTimestamp", e);
            }
        }
    }

    private class DmdbTimestampDeserializer extends ValueDeserializer<Object> {

        @Override
        public Object deserialize(JsonParser p, DeserializationContext ctxt) {
            try {
                Instant instant = parseInstant(p);
                return createDmdbTimestamp(instant);
            } catch (Exception e) {
                LOGGER.error("deserialize dm.jdbc.driver.DmdbTimestamp error : {}", e.getMessage(), e);
            }
            return null;
        }

        private Instant parseInstant(JsonParser p) throws IOException {
            try {
                if (p.isExpectedStartArrayToken()) {
                    ArrayNode arrayNode = p.objectReadContext().readTree(p);
                    long timestamp = arrayNode.get(0).asLong();
                    Instant instant = Instant.ofEpochMilli(timestamp);
                    if (arrayNode.size() > 1) {
                        int nano = arrayNode.get(1).asInt();
                        instant = instant.plusNanos(nano % 1000000);
                    }
                    return instant;
                } else {
                    long timestamp = p.getLongValue();
                    return Instant.ofEpochMilli(timestamp);
                }
            } catch (JacksonIOException e) {
                throw new IOException("Error parsing Instant from JSON", e);
            }
        }

        private Object createDmdbTimestamp(Instant instant) throws Exception {
            Class<?> dmdbTimestampClass = Class.forName(DM_JDBC_DRIVER_DMDB_TIMESTAMP);
            Method valueOfMethod = dmdbTimestampClass.getMethod(VALUE_OF, ZonedDateTime.class);
            return valueOfMethod.invoke(null, instant.atZone(zoneId));
        }
    }

    /**
     * set zone id
     *
     * @param zoneId the zoneId
     */
    public static void setZoneOffset(ZoneId zoneId) {
        Objects.requireNonNull(zoneId, "zoneId must be not null");
        JacksonUndoLogParser.zoneId = zoneId;
    }

    /**
     * the class of serialize SerialArray type
     */
    private static class SerialArraySerializer extends ValueSerializer<SerialArray> {

        @Override
        public void serializeWithType(
                SerialArray serialArray, JsonGenerator gen, SerializationContext ctxt, TypeSerializer typeSerializer) {
            WritableTypeId typeIdDef = typeSerializer.writeTypePrefix(
                    gen, ctxt, typeSerializer.typeId(serialArray, JsonToken.START_OBJECT));
            serializeValue(serialArray, gen, ctxt);
            typeSerializer.writeTypeSuffix(gen, ctxt, typeIdDef);
        }

        @Override
        public void serialize(SerialArray serialArray, JsonGenerator gen, SerializationContext ctxt) {
            gen.writeStartObject();
            serializeValue(serialArray, gen, ctxt);
            gen.writeEndObject();
        }

        private void serializeValue(SerialArray serialArray, JsonGenerator gen, SerializationContext ctxt) {
            gen.writeName("baseType");
            try {
                gen.writeNumber(serialArray.getBaseType());
            } catch (SQLException e) {
                gen.writeNull();
            }
            gen.writeName("baseTypeName");
            try {
                gen.writeString(serialArray.getBaseTypeName());
            } catch (SQLException e) {
                gen.writeNull();
            }
            gen.writeName("elements");
            try {
                Object[] elements = serialArray.getElements();
                gen.writeStartArray();
                if (elements != null) {
                    for (Object element : elements) {
                        gen.writePOJO(element);
                    }
                }
                gen.writeEndArray();
            } catch (Exception e) {
                gen.writeNull();
            }
        }
    }

    /**
     * the class of deserialize SerialArray type
     */
    private static class SerialArrayDeserializer extends ValueDeserializer<SerialArray> {
        @Override
        public SerialArray deserialize(JsonParser p, DeserializationContext ctxt) {
            try {
                JsonNode node = p.objectReadContext().readTree(p);
                SerialArray serialArray = new SerialArray();

                if (node.has("baseType") && !node.get("baseType").isNull()) {
                    serialArray.setBaseType(node.get("baseType").asInt());
                }

                if (node.has("baseTypeName") && !node.get("baseTypeName").isNull()) {
                    serialArray.setBaseTypeName(node.get("baseTypeName").asText());
                }

                if (node.has("elements") && node.get("elements").isArray()) {
                    JsonNode elementsNode = node.get("elements");
                    Object[] elements = new Object[elementsNode.size()];
                    for (int i = 0; i < elementsNode.size(); i++) {
                        JsonNode elementNode = elementsNode.get(i);
                        if (elementNode.isNull()) {
                            elements[i] = null;
                        } else if (elementNode.isNumber()) {
                            elements[i] = elementNode.asLong();
                        } else if (elementNode.isTextual()) {
                            elements[i] = elementNode.asText();
                        } else {
                            elements[i] = elementNode;
                        }
                    }
                    serialArray.setElements(elements);
                }

                return serialArray;
            } catch (Exception e) {
                LOGGER.error("deserialize SerialArray error: {}", e.getMessage(), e);
                return null;
            }
        }
    }
}
