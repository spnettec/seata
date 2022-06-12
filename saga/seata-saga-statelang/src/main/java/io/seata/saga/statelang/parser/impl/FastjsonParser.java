/*
 *  Copyright 1999-2019 Seata.io Group.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package io.seata.saga.statelang.parser.impl;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONReader;
import com.alibaba.fastjson2.JSONWriter;
import io.seata.common.loader.LoadLevel;
import io.seata.saga.statelang.parser.JsonParser;

/**
 * JsonParser implement by Fastjson
 *
 * @author lorne.cl
 */
@LoadLevel(name = FastjsonParser.NAME)
public class FastjsonParser implements JsonParser {

    private static final JSONWriter.Feature[] SERIALIZER_FEATURES = new JSONWriter.Feature[] {
            JSONWriter.Feature.WriteClassName};

    private static final JSONWriter.Feature[] SERIALIZER_FEATURES_PRETTY = new JSONWriter.Feature[] {
            JSONWriter.Feature.WriteClassName,
            JSONWriter.Feature.PrettyFormat };

    private static final JSONWriter.Feature[] FEATURES_PRETTY = new JSONWriter.Feature[] {
            JSONWriter.Feature.PrettyFormat };

    public static final String NAME = "fastjson";

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public String toJsonString(Object o, boolean prettyPrint) {
        return toJsonString(o, false, prettyPrint);
    }

    @Override
    public String toJsonString(Object o, boolean ignoreAutoType, boolean prettyPrint) {
        if (prettyPrint) {
            if (ignoreAutoType) {
                return JSON.toJSONString(o, FEATURES_PRETTY);
            }
            else {
                return JSON.toJSONString(o, SERIALIZER_FEATURES_PRETTY);
            }
        }
        else {
            if (ignoreAutoType) {
                return JSON.toJSONString(o);
            }
            else {
                return JSON.toJSONString(o, SERIALIZER_FEATURES);
            }
        }
    }

    @Override
    public <T> T parse(String json, Class<T> type, boolean ignoreAutoType) {
        if (ignoreAutoType) {
            return JSON.parseObject(json, type);
        }
        else {
            return JSON.parseObject(json, type, JSONReader.Feature.SupportAutoType);
        }
    }
}