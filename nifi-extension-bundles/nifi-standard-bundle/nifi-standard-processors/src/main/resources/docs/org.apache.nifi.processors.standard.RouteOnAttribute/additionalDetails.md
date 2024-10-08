<!--
  Licensed to the Apache Software Foundation (ASF) under one or more
  contributor license agreements.  See the NOTICE file distributed with
  this work for additional information regarding copyright ownership.
  The ASF licenses this file to You under the Apache License, Version 2.0
  (the "License"); you may not use this file except in compliance with
  the License.  You may obtain a copy of the License at
      http://www.apache.org/licenses/LICENSE-2.0
  Unless required by applicable law or agreed to in writing, software
  distributed under the License is distributed on an "AS IS" BASIS,
  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  See the License for the specific language governing permissions and
  limitations under the License.
-->

# RouteOnAttribute

## Usage Example

This processor routes FlowFiles based on their attributes using the NiFi Expression Language. Users add properties with
valid NiFi Expression Language Expressions as the values. Each Expression must return a value of type Boolean (true or
false).

Example: The goal is to route all files with filenames that start with ABC down a certain path. Add a property with the
following name and value:

* **property name**: ABC
* **property value**: ${filename:startsWith('ABC')}

In this example, all files with filenames that start with ABC will follow the ABC relationship.