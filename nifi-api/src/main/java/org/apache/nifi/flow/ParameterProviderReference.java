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
package org.apache.nifi.flow;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema
public class ParameterProviderReference {

    private String identifier;
    private String name;
    private String type;
    private Bundle bundle;

    @Schema(description = "The fully qualified name of the parameter provider class.")
    public String getType() {
        return type;
    }

    public void setType(final String type) {
        this.type = type;
    }

    @Schema(description = "The details of the artifact that bundled this parameter provider.")
    public Bundle getBundle() {
        return bundle;
    }

    public void setBundle(final Bundle bundle) {
        this.bundle = bundle;
    }

    @Schema(description = "The identifier of the parameter provider")
    public String getIdentifier() {
        return identifier;
    }

    public void setIdentifier(final String identifier) {
        this.identifier = identifier;
    }

    @Schema(description = "The name of the parameter provider")
    public String getName() {
        return name;
    }

    public void setName(final String name) {
        this.name = name;
    }
}
