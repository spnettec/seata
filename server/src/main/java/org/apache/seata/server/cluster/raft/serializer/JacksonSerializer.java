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
package org.apache.seata.server.cluster.raft.serializer;

import org.apache.seata.common.loader.LoadLevel;
import org.apache.seata.core.serializer.Serializer;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;

/**
 */
@LoadLevel(name = "JACKSON")
public class JacksonSerializer implements Serializer {

    private static final ObjectMapper OBJECT_MAPPER;

    static {
        SimpleModule module = new SimpleModule();
        module.addDeserializer(Class.class, new CustomDeserializer());
        OBJECT_MAPPER = JsonMapper.builder()
                .addModule(module)
                .configure(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES, false)
                .build();
    }

    @Override
    public <T> byte[] serialize(T t) {
        try {
            JsonInfo jsonInfo = new JsonInfo(OBJECT_MAPPER.writeValueAsBytes(t), t.getClass());
            return OBJECT_MAPPER.writeValueAsBytes(jsonInfo);
        } catch (JacksonException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public <T> T deserialize(byte[] bytes) {
        JsonInfo jsonInfo = OBJECT_MAPPER.readValue(bytes, JsonInfo.class);
        return (T) OBJECT_MAPPER.readValue(jsonInfo.getObj(), jsonInfo.getClz());
    }

    static class JsonInfo {

        byte[] obj;

        Class<?> clz;

        public JsonInfo() {}

        public JsonInfo(byte[] obj, Class<?> clz) {
            this.obj = obj;
            this.clz = clz;
        }

        public byte[] getObj() {
            return obj;
        }

        public void setObj(byte[] obj) {
            this.obj = obj;
        }

        public Class<?> getClz() {
            return clz;
        }

        public void setClz(Class<?> clz) {
            this.clz = clz;
        }
    }
}
