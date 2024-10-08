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

# PutSplunkHTTP

## PutSplunkHTTP

This processor serves as a counterpart for PutSplunk processor. While the latter solves communication using TCP and UDP
protocols, PutSplunkHTTP aims to send events into Splunk via HTTP or HTTPS. In this fashion, this processor shows
similarities with GetSplunk processor and the properties relevant to the connection with Splunk server are identical.
There are however some aspects unique for this processor:

### Content details

PutSplunkHTTP allows the user to specify some metadata about the event being sent to the Splunk. These include: the "
Character Set" and the "Content Type" of the flow file content, using the matching properties. If the incoming flow file
has "mime.type" attribute, the processor will use it, unless the "Content Type" property is set, in which case the
property will override the flow file attribute.

### Event parameters

The "Source", "Source Type", "Host" and "Index" properties are optional and will be set by Splunk if unspecified. If
set, the default values will be overwritten by user specified ones. For more details about the Splunk API, please
visit [this documentation](https://docs.splunk.com/Documentation/Splunk/LATEST/RESTREF/RESTinput#services.2Fcollector.2Fraw).

### Acknowledgements

HTTP Event Collector (HEC) in Splunk provides the possibility of index acknowledgement, which can be used to monitor the
indexing status of the individual events. PutSplunkHTTP supports this feature by enriching the outgoing flow file with
the necessary information, making it possible for a later processor to poll the status based on. The necessary
information for this is stored within flow file attributes "splunk.acknowledgement.id" and "splunk.responded.at".

For further steps of acknowledgement handling in NiFi side, please refer to QuerySplunkIndexingStatus processor. For
more details about the index acknowledgement, please
visit [this documentation](https://docs.splunk.com/Documentation/Splunk/LATEST/Data/AboutHECIDXAck).

### Error information

For more refined processing, flow files are enriched with additional information if possible. The information is stored
in the flow file attribute "splunk.status.code" or "splunk.response.code", depending on the success of the processing.
The attribute "splunk.status.code" is always filled when the Splunk API call is executed and contains the HTTP status
code of the response. In case the flow file transferred into "failure" relationship, the "splunk.response.code" might be
also filled, based on the Splunk response code.