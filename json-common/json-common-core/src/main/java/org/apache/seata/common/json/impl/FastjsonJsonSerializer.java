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
package org.apache.seata.common.json.impl;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONReader;
import com.alibaba.fastjson2.JSONWriter;
import com.alibaba.fastjson2.filter.AutoTypeBeforeHandler;
import org.apache.seata.common.exception.JsonParseException;
import org.apache.seata.common.json.JsonAllowlistManager;
import org.apache.seata.common.json.JsonSerializer;
import org.apache.seata.common.loader.LoadLevel;

import java.lang.reflect.Type;
import java.util.regex.Pattern;

/**
 * FastJSON2 implementation of JsonSerializer
 */
@LoadLevel(name = FastjsonJsonSerializer.NAME)
public class FastjsonJsonSerializer implements JsonSerializer {

    private static final Pattern AUTOTYPE_PATTERN = Pattern.compile("\"@type\"\\s*:");

    // 默认序列化特性：禁用循环引用、日期格式化、写入类名
    private static final JSONWriter.Feature[] SERIALIZER_FEATURES = {
            JSONWriter.Feature.ReferenceDetection, // Fastjson2 默认开启，此处若要禁用需配合 ! 符号或逻辑处理，但通常建议保留
            JSONWriter.Feature.WriteCallbacks,
            JSONWriter.Feature.WriteClassName
    };

    private static final JSONWriter.Feature[] SERIALIZER_FEATURES_PRETTY = {
            JSONWriter.Feature.WriteClassName,
            JSONWriter.Feature.PrettyFormat
    };

    private static final JSONWriter.Feature[] FEATURES_PRETTY = {
            JSONWriter.Feature.PrettyFormat
    };

    // 读取特性
    private static final JSONReader.Feature[] READER_FEATURES_SUPPORT_AUTO_TYPE = {
            JSONReader.Feature.SupportAutoType,
            JSONReader.Feature.FieldBased
    };

    private static final JSONReader.Feature[] READER_FEATURES_IGNORE_AUTO_TYPE = {
            JSONReader.Feature.FieldBased
    };

    // Fastjson2 使用 AutoTypeBeforeHandler 替代 AutoTypeCheckHandler
    private static final AutoTypeBeforeHandler ALLOWLIST_HANDLER = (typeName, expectClass, features) -> {
        JsonAllowlistManager.getInstance().checkClass(typeName);
        return null;
    };

    public static final String NAME = "fastjson";

    @Override
    public String toJSONString(Object object) {
        try {
            return JSON.toJSONString(object);
        } catch (Exception e) {
            throw new JsonParseException("FastJSON2 serialize error", e);
        }
    }

    @Override
    public <T> T parseObject(String text, Class<T> clazz) {
        if (text == null || clazz == null) {
            return null;
        }
        try {
            return JSON.parseObject(text, clazz);
        } catch (Exception e) {
            throw new JsonParseException("FastJSON2 deserialize error", e);
        }
    }

    @Override
    public <T> T parseObjectWithType(String text, Type type) {
        if (text == null || type == null) {
            return null;
        }
        try {
            // 使用 Context 来注入白名单处理器
            return JSON.parseObject(text, type, JSONReader.Context.of(ALLOWLIST_HANDLER, READER_FEATURES_SUPPORT_AUTO_TYPE));
        } catch (SecurityException e) {
            throw e;
        } catch (Exception e) {
            rethrowIfSecurityException(e);
            throw new JsonParseException("FastJSON2 deserialize error", e);
        }
    }

    @Override
    public boolean useAutoType(String json) {
        return json != null && AUTOTYPE_PATTERN.matcher(json).find();
    }

    @Override
    public String toJSONString(Object object, boolean prettyPrint) {
        return toJSONString(object, false, prettyPrint);
    }

    @Override
    public String toJSONString(Object object, boolean ignoreAutoType, boolean prettyPrint) {
        try {
            if (prettyPrint) {
                return ignoreAutoType
                        ? JSON.toJSONString(object, FEATURES_PRETTY)
                        : JSON.toJSONString(object, SERIALIZER_FEATURES_PRETTY);
            } else {
                return ignoreAutoType
                        ? JSON.toJSONString(object)
                        : JSON.toJSONString(object, SERIALIZER_FEATURES);
            }
        } catch (Exception e) {
            throw new JsonParseException("FastJSON2 serialize error", e);
        }
    }

    @Override
    public <T> T parseObject(String text, Class<T> type, boolean ignoreAutoType) {
        if (text == null || type == null) {
            return null;
        }
        try {
            if ("[]".equals(text) && (java.util.Collection.class.isAssignableFrom(type) || type == Object.class)) {
                return (T) new java.util.ArrayList<>();
            }

            if (ignoreAutoType) {
                return JSON.parseObject(text, type, READER_FEATURES_IGNORE_AUTO_TYPE);
            } else {
                return JSON.parseObject(text, type, JSONReader.Context.of(ALLOWLIST_HANDLER, READER_FEATURES_SUPPORT_AUTO_TYPE));
            }
        } catch (SecurityException e) {
            throw e;
        } catch (Exception e) {
            rethrowIfSecurityException(e);
            throw new JsonParseException("FastJSON2 deserialize error", e);
        }
    }

    private static void rethrowIfSecurityException(Throwable e) {
        Throwable cause = e.getCause();
        while (cause != null) {
            if (cause instanceof SecurityException) {
                throw (SecurityException) cause;
            }
            cause = cause.getCause();
        }
    }
}
