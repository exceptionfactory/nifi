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

# QueryRecord

### SQL Over Streams

QueryRecord provides users a tremendous amount of power by leveraging an extremely well-known syntax (SQL) to route,
filter, transform, and query data as it traverses the system. In order to provide the Processor with the maximum amount
of flexibility, it is configured with a Controller Service that is responsible for reading and parsing the incoming
FlowFiles and a Controller Service that is responsible for writing the results out. By using this paradigm, users are
not forced to convert their data from one format to another just to query it, and then transform the data back into the
form that they want. Rather, the appropriate Controller Service can easily be configured and put to use for the
appropriate data format.

Rather than providing a single "SQL SELECT Statement" type of Property, this Processor makes use of user-defined
properties. Each user-defined property that is added to the Processor has a name that becomes a new Relationship for the
Processor and a corresponding SQL query that will be evaluated against each FlowFile. This allows multiple SQL queries
to be run against each FlowFile.

The SQL syntax that is supported by this Processor is ANSI SQL and is powered by Apache Calcite. Please note that
identifiers are quoted using double-quotes, and column names/labels are case-insensitive.

As an example, let's consider that we have a FlowFile with the following CSV data:

```
name, age, title
John Doe, 34, Software Engineer
Jane Doe, 30, Program Manager
Jacob Doe, 45, Vice President
Janice Doe, 46, Vice President
```

Now consider that we add the following properties to the Processor:

| Property Name        | Property Value                                                       |
|----------------------|----------------------------------------------------------------------|
| Engineers            | `SELECT * FROM FLOWFILE WHERE title LIKE '%Engineer%'`               |
| VP                   | `SELECT name FROM FLOWFILE WHERE title = 'Vice President'`           |
| Younger Than Average | `SELECT * FROM FLOWFILE WHERE age < (SELECT AVG(age) FROM FLOWFILE)` |

This Processor will now have five relationships: `original`, `failure`, `Engineers`, `VP`, and `Younger Than Average`.
If there is a failure processing the FlowFile, then the original FlowFile will be routed to `failure`. Otherwise, the
original FlowFile will be routed to `original` and one FlowFile will be routed to each of the other relationships, with
the following values:

| Relationship Name    | FlowFile Value                                                                             |
|----------------------|--------------------------------------------------------------------------------------------|
| Engineers            | `name, age, title`<br>`John Doe, 34, Software Engineer`                                    |
| VP                   | `name`<br>`Jacob Doe`<br>`Janice Doe`                                                      |
| Younger Than Average | `name, age, title`<br>`John Doe, 34, Software Engineer`<br>`Jane Doe, 30, Program Manager` |

Note that this example is intended to illustrate the data that is input and output from the Processor. The actual format
of the data may vary, depending on the configuration of the Record Reader and Record Writer that is used. For example,
here we assume that we are using a CSV Reader and a CSV Writer and that both are configured to have a header line.
Should we have used a JSON Writer instead, the output would have contained the same information but been presented in
JSON Output. The user is able to choose which input and output format make the most since for his or her use case. The
input and output formats need not be the same.

It is also worth noting that the outbound FlowFiles have two different schemas. The `Engineers` and
`Younger Than Average` FlowFiles contain 3 fields: `name`, `age`, and `title` while the `VP` FlowFile contains only the
`name` field. In most cases, the Record Writer is configured to use whatever Schema is provided to it by the Record (
this generally means that it is configured with a `Schema Access Strategy` of `Inherit Record Schema`). In such a case,
this works well. However, if a Schema is supplied to the Record Writer explicitly, it is important to ensure that the
Schema accounts for all fields. If not, then the fields that are missing from the Record Writer's schema will simply not
be present in the output.

### SQL Over Hierarchical Data

One important detail that we must take into account when evaluating SQL over streams of arbitrary data is how we can
handle hierarchical data, such as JSON, XML, and Avro. Because SQL was developed originally for relational databases,
which represent "flat" data, it is easy to understand how this would map to other "flat" data like a CSV file. Or even
a "flat" JSON representation where all fields are primitive types. However, in many cases, users encounter cases where
they would like to evaluate SQL over JSON or Avro data that is made up of many nested values. For example, consider the
following JSON as input:

```json
            {
  "name": "John Doe",
  "title": "Software Engineer",
  "age": 40,
  "addresses": [
    {
      "streetNumber": 4820,
      "street": "My Street",
      "apartment": null,
      "city": "New York",
      "state": "NY",
      "country": "USA",
      "label": "work"
    },
    {
      "streetNumber": 327,
      "street": "Small Street",
      "apartment": 309,
      "city": "Los Angeles",
      "state": "CA",
      "country": "USA",
      "label": "home"
    }
  ],
  "project": {
    "name": "Apache NiFi",
    "maintainer": {
      "id": 28302873,
      "name": "Apache Software Foundation"
    },
    "debutYear": 2014
  }
}

```

Consider a query that will select the title and name of any person who has a home address in a different state than
their work address. Here, we can only select the fields `name`, `title`, `age`, and `addresses`. In this scenario,
`addresses` represents an Array of complex objects - records. In order to accommodate for this, QueryRecord provides
User-Defined Functions to enable Record Path to be used. Record Path is a
simple NiFi Domain Specific Language (DSL) that allows users to reference a nested structure.

The primary User-Defined Function that will be used is named `RPATH` (short for Record Path). This function expects
exactly two arguments: the Record to evaluate the RecordPath against, and the RecordPath to evaluate (in that order).
So, to select the title and name of any person who has a home address in a different state than their work address, we
can use the following SQL statement:

```sql
SELECT title, name
FROM FLOWFILE
WHERE RPATH(addresses, '/state[/label = ''home'']') <> RPATH(addresses, '/state[/label = ''work'']')
```

To explain this query in English, we can say that it selects the "title" and "name" fields from any Record in the
FlowFile for which there is an address whose "label" is "home" and another address whose "label" is "work" and for which
the two addreses have different states.

Similarly, we could select the entire Record (all fields) of any person who has a "project" whose maintainer is the
Apache Software Foundation using the query:

```sql
SELECT *
FROM FLOWFILE
WHERE RPATH(project, '/maintainer/name') = 'Apache Software Foundation'
```

There does exist a caveat, though, when using RecordPath. That is that the `RPATH` function returns an `Object`, which
in JDBC is represented as an `OTHER` type. This is fine and does not affect anything when it is used like above.
However, what if we wanted to use another SQL function on the result? For example, what if we wanted to use the SQL
query `SELECT * FROM FLOWFILE WHERE RPATH(project, '/maintainer/name') LIKE 'Apache%'`? This would fail with a very long
error such as:

```
3860 [pool-2-thread-1] ERROR org.apache.nifi.processors.standard.QueryRecord - QueryRecord[id=135e9bc8-0372-4c1e-9c82-9d9a5bfe1261] Unable to query FlowFile[0,174730597574853.mockFlowFile,0B] due to java.lang.RuntimeException: Error while compiling generated Java code: org.apache.calcite.DataContext root;  public org.apache.calcite.linq4j.Enumerable bind(final org.apache.calcite.DataContext root0) {   root = root0;   final org.apache.calcite.linq4j.Enumerable _inputEnumerable = ((org.apache.nifi.queryrecord.FlowFileTable) root.getRootSchema().getTable("FLOWFILE")).project(new int[] {     0,     1,     2,     3});   return new org.apache.calcite.linq4j.AbstractEnumerable(){       public org.apache.calcite.linq4j.Enumerator enumerator() {         return new org.apache.calcite.linq4j.Enumerator(){             public final org.apache.calcite.linq4j.Enumerator inputEnumerator = _inputEnumerable.enumerator();             public void reset() {               inputEnumerator.reset();             }              public boolean moveNext() {               while (inputEnumerator.moveNext()) {                 final Object[] inp3_ = (Object[]) ((Object[]) inputEnumerator.current())[3];                 if (new org.apache.nifi.processors.standard.QueryRecord.ObjectRecordPath().eval(inp3_, "/state[. = 'NY']") != null && org.apache.calcite.runtime.SqlFunctions.like(new org.apache.nifi.processors.standard.QueryRecord.ObjectRecordPath().eval(inp3_, "/state[. = 'NY']"), "N%")) {                   return true;                 }               }               return false;             }              public void close() {               inputEnumerator.close();             }              public Object current() {               final Object[] current = (Object[]) inputEnumerator.current();               return new Object[] {                   current[2],                   current[0]};             }            };       }      }; }   public Class getElementType() {   return java.lang.Object[].class; }   : java.lang.RuntimeException: Error while compiling generated Java code: org.apache.calcite.DataContext root;  public org.apache.calcite.linq4j.Enumerable bind(final org.apache.calcite.DataContext root0) {   root = root0;   final org.apache.calcite.linq4j.Enumerable _inputEnumerable = ((org.apache.nifi.queryrecord.FlowFileTable) root.getRootSchema().getTable("FLOWFILE")).project(new int[] {     0,     1,     2,     3});   return new org.apache.calcite.linq4j.AbstractEnumerable(){       public org.apache.calcite.linq4j.Enumerator enumerator() {         return new org.apache.calcite.linq4j.Enumerator(){             public final org.apache.calcite.linq4j.Enumerator inputEnumerator = _inputEnumerable.enumerator();             public void reset() {               inputEnumerator.reset();             }              public boolean moveNext() {               while (inputEnumerator.moveNext()) {                 final Object[] inp3_ = (Object[]) ((Object[]) inputEnumerator.current())[3];                 if (new org.apache.nifi.processors.standard.QueryRecord.ObjectRecordPath().eval(inp3_, "/state[. = 'NY']") != null && org.apache.calcite.runtime.SqlFunctions.like(new org.apache.nifi.processors.standard.QueryRecord.ObjectRecordPath().eval(inp3_, "/state[. = 'NY']"), "N%")) {                   return true;                 }               }               return false;             }              public void close() {               inputEnumerator.close();             }              public Object current() {               final Object[] current = (Object[]) inputEnumerator.current();               return new Object[] {                   current[2],                   current[0]};             }            };       }      }; }   public Class getElementType() {   return java.lang.Object[].class; }    3864 [pool-2-thread-1] ERROR org.apache.nifi.processors.standard.QueryRecord - java.lang.RuntimeException: Error while compiling generated Java code: org.apache.calcite.DataContext root;  public org.apache.calcite.linq4j.Enumerable bind(final org.apache.calcite.DataContext root0) {   root = root0;   final org.apache.calcite.linq4j.Enumerable _inputEnumerable = ((org.apache.nifi.queryrecord.FlowFileTable) root.getRootSchema().getTable("FLOWFILE")).project(new int[] {     0,     1,     2,     3});   return new org.apache.calcite.linq4j.AbstractEnumerable(){       public org.apache.calcite.linq4j.Enumerator enumerator() {         return new org.apache.calcite.linq4j.Enumerator(){             public final org.apache.calcite.linq4j.Enumerator inputEnumerator = _inputEnumerable.enumerator();             public void reset() {               inputEnumerator.reset();             }              public boolean moveNext() {               while (inputEnumerator.moveNext()) {                 final Object[] inp3_ = (Object[]) ((Object[]) inputEnumerator.current())[3];                 if (new org.apache.nifi.processors.standard.QueryRecord.ObjectRecordPath().eval(inp3_, "/state[. = 'NY']") != null && org.apache.calcite.runtime.SqlFunctions.like(new org.apache.nifi.processors.standard.QueryRecord.ObjectRecordPath().eval(inp3_, "/state[. = 'NY']"), "N%")) {                   return true;                 }               }               return false;             }              public void close() {               inputEnumerator.close();             }              public Object current() {               final Object[] current = (Object[]) inputEnumerator.current();               return new Object[] {                   current[2],                   current[0]};             }            };       }      }; }   public Class getElementType() {   return java.lang.Object[].class; }    	at org.apache.calcite.avatica.Helper.wrap(Helper.java:37) 	at org.apache.calcite.adapter.enumerable.EnumerableInterpretable.toBindable(EnumerableInterpretable.java:108) 	at org.apache.calcite.prepare.CalcitePrepareImpl$CalcitePreparingStmt.implement(CalcitePrepareImpl.java:1237) 	at org.apache.calcite.prepare.Prepare.prepareSql(Prepare.java:331) 	at org.apache.calcite.prepare.Prepare.prepareSql(Prepare.java:230) 	at org.apache.calcite.prepare.CalcitePrepareImpl.prepare2_(CalcitePrepareImpl.java:772) 	at org.apache.calcite.prepare.CalcitePrepareImpl.prepare_(CalcitePrepareImpl.java:636) 	at org.apache.calcite.prepare.CalcitePrepareImpl.prepareSql(CalcitePrepareImpl.java:606) 	at org.apache.calcite.jdbc.CalciteConnectionImpl.parseQuery(CalciteConnectionImpl.java:229) 	at org.apache.calcite.jdbc.CalciteConnectionImpl.prepareStatement_(CalciteConnectionImpl.java:211) 	at org.apache.calcite.jdbc.CalciteConnectionImpl.prepareStatement(CalciteConnectionImpl.java:200) 	at org.apache.calcite.jdbc.CalciteConnectionImpl.prepareStatement(CalciteConnectionImpl.java:90) 	at org.apache.calcite.avatica.AvaticaConnection.prepareStatement(AvaticaConnection.java:175) 	at org.apache.nifi.processors.standard.QueryRecord.buildCachedStatement(QueryRecord.java:428) 	at org.apache.nifi.processors.standard.QueryRecord.getStatement(QueryRecord.java:415) 	at org.apache.nifi.processors.standard.QueryRecord.queryWithCache(QueryRecord.java:475) 	at org.apache.nifi.processors.standard.QueryRecord.onTrigger(QueryRecord.java:311) 	at org.apache.nifi.processor.AbstractProcessor.onTrigger(AbstractProcessor.java:27) 	at org.apache.nifi.util.StandardProcessorTestRunner$RunProcessor.call(StandardProcessorTestRunner.java:255) 	at org.apache.nifi.util.StandardProcessorTestRunner$RunProcessor.call(StandardProcessorTestRunner.java:249) 	at java.util.concurrent.FutureTask.run(FutureTask.java:266) 	at java.util.concurrent.ScheduledThreadPoolExecutor$ScheduledFutureTask.access$201(ScheduledThreadPoolExecutor.java:180) 	at java.util.concurrent.ScheduledThreadPoolExecutor$ScheduledFutureTask.run(ScheduledThreadPoolExecutor.java:293) 	at java.util.concurrent.ThreadPoolExecutor.runWorker(ThreadPoolExecutor.java:1142) 	at java.util.concurrent.ThreadPoolExecutor$Worker.run(ThreadPoolExecutor.java:617) 	at java.lang.Thread.run(Thread.java:745) Caused by: org.codehaus.commons.compiler.CompileException: Line 21, Column 180: No applicable constructor/method found for actual parameters "java.lang.Object, java.lang.String"; candidates are: "public static boolean org.apache.calcite.runtime.SqlFunctions.like(java.lang.String, java.lang.String)", "public static boolean org.apache.calcite.runtime.SqlFunctions.like(java.lang.String, java.lang.String, java.lang.String)" 	at org.codehaus.janino.UnitCompiler.compileError(UnitCompiler.java:10092) 	at org.codehaus.janino.UnitCompiler.findMostSpecificIInvocable(UnitCompiler.java:7506) 	at org.codehaus.janino.UnitCompiler.findIMethod(UnitCompiler.java:7376) 	at org.codehaus.janino.UnitCompiler.findIMethod(UnitCompiler.java:7280) 	at org.codehaus.janino.UnitCompiler.compileGet2(UnitCompiler.java:3850) 	at org.codehaus.janino.UnitCompiler.access$6900(UnitCompiler.java:183) 	at org.codehaus.janino.UnitCompiler$10.visitMethodInvocation(UnitCompiler.java:3251) 	at org.codehaus.janino.Java$MethodInvocation.accept(Java.java:3974) 	at org.codehaus.janino.UnitCompiler.compileGet(UnitCompiler.java:3278) 	at org.codehaus.janino.UnitCompiler.compileGetValue(UnitCompiler.java:4345) 	at org.codehaus.janino.UnitCompiler.compileBoolean2(UnitCompiler.java:2842) 	at org.codehaus.janino.UnitCompiler.access$4800(UnitCompiler.java:183) 	at org.codehaus.janino.UnitCompiler$8.visitMethodInvocation(UnitCompiler.java:2803) 	at org.codehaus.janino.Java$MethodInvocation.accept(Java.java:3974) 	at org.codehaus.janino.UnitCompiler.compileBoolean(UnitCompiler.java:2830) 	at org.codehaus.janino.UnitCompiler.compileBoolean2(UnitCompiler.java:2924) 	at org.codehaus.janino.UnitCompiler.access$5000(UnitCompiler.java:183) 	at org.codehaus.janino.UnitCompiler$8.visitBinaryOperation(UnitCompiler.java:2797) 	at org.codehaus.janino.Java$BinaryOperation.accept(Java.java:3768) 	at org.codehaus.janino.UnitCompiler.compileBoolean(UnitCompiler.java:2830) 	at org.codehaus.janino.UnitCompiler.compile2(UnitCompiler.java:1742) 	at org.codehaus.janino.UnitCompiler.access$1200(UnitCompiler.java:183) 	at org.codehaus.janino.UnitCompiler$4.visitIfStatement(UnitCompiler.java:935) 	at org.codehaus.janino.Java$IfStatement.accept(Java.java:2157) 	at org.codehaus.janino.UnitCompiler.compile(UnitCompiler.java:956) 	at org.codehaus.janino.UnitCompiler.compileStatements(UnitCompiler.java:997) 	at org.codehaus.janino.UnitCompiler.compile2(UnitCompiler.java:983) 	at org.codehaus.janino.UnitCompiler.access$1000(UnitCompiler.java:183) 	at org.codehaus.janino.UnitCompiler$4.visitBlock(UnitCompiler.java:933) 	at org.codehaus.janino.Java$Block.accept(Java.java:2012) 	at org.codehaus.janino.UnitCompiler.compile(UnitCompiler.java:956) 	at org.codehaus.janino.UnitCompiler.compile2(UnitCompiler.java:1263) 	at org.codehaus.janino.UnitCompiler.access$1500(UnitCompiler.java:183) 	at org.codehaus.janino.UnitCompiler$4.visitWhileStatement(UnitCompiler.java:938) 	at org.codehaus.janino.Java$WhileStatement.accept(Java.java:2244) 	at org.codehaus.janino.UnitCompiler.compile(UnitCompiler.java:956) 	at org.codehaus.janino.UnitCompiler.compileStatements(UnitCompiler.java:997) 	at org.codehaus.janino.UnitCompiler.compile(UnitCompiler.java:2283) 	at org.codehaus.janino.UnitCompiler.compileDeclaredMethods(UnitCompiler.java:820) 	at org.codehaus.janino.UnitCompiler.compileDeclaredMethods(UnitCompiler.java:792) 	at org.codehaus.janino.UnitCompiler.compile2(UnitCompiler.java:505) 	at org.codehaus.janino.UnitCompiler.compile2(UnitCompiler.java:656) 	at org.codehaus.janino.UnitCompiler.compile2(UnitCompiler.java:620) 	at org.codehaus.janino.UnitCompiler.access$200(UnitCompiler.java:183) 	at org.codehaus.janino.UnitCompiler$2.visitAnonymousClassDeclaration(UnitCompiler.java:343) 	at org.codehaus.janino.Java$AnonymousClassDeclaration.accept(Java.java:894) 	at org.codehaus.janino.UnitCompiler.compile(UnitCompiler.java:352) 	at org.codehaus.janino.UnitCompiler.compileGet2(UnitCompiler.java:4194) 	at org.codehaus.janino.UnitCompiler.access$7300(UnitCompiler.java:183) 	at org.codehaus.janino.UnitCompiler$10.visitNewAnonymousClassInstance(UnitCompiler.java:3260) 	at org.codehaus.janino.Java$NewAnonymousClassInstance.accept(Java.java:4131) 	at org.codehaus.janino.UnitCompiler.compileGet(UnitCompiler.java:3278) 	at org.codehaus.janino.UnitCompiler.compileGetValue(UnitCompiler.java:4345) 	at org.codehaus.janino.UnitCompiler.compile2(UnitCompiler.java:1901) 	at org.codehaus.janino.UnitCompiler.access$2100(UnitCompiler.java:183) 	at org.codehaus.janino.UnitCompiler$4.visitReturnStatement(UnitCompiler.java:944) 	at org.codehaus.janino.Java$ReturnStatement.accept(Java.java:2544) 	at org.codehaus.janino.UnitCompiler.compile(UnitCompiler.java:956) 	at org.codehaus.janino.UnitCompiler.compileStatements(UnitCompiler.java:997) 	at org.codehaus.janino.UnitCompiler.compile(UnitCompiler.java:2283) 	at org.codehaus.janino.UnitCompiler.compileDeclaredMethods(UnitCompiler.java:820) 	at org.codehaus.janino.UnitCompiler.compileDeclaredMethods(UnitCompiler.java:792) 	at org.codehaus.janino.UnitCompiler.compile2(UnitCompiler.java:505) 	at org.codehaus.janino.UnitCompiler.compile2(UnitCompiler.java:656) 	at org.codehaus.janino.UnitCompiler.compile2(UnitCompiler.java:620) 	at org.codehaus.janino.UnitCompiler.access$200(UnitCompiler.java:183) 	at org.codehaus.janino.UnitCompiler$2.visitAnonymousClassDeclaration(UnitCompiler.java:343) 	at org.codehaus.janino.Java$AnonymousClassDeclaration.accept(Java.java:894) 	at org.codehaus.janino.UnitCompiler.compile(UnitCompiler.java:352) 	at org.codehaus.janino.UnitCompiler.compileGet2(UnitCompiler.java:4194) 	at org.codehaus.janino.UnitCompiler.access$7300(UnitCompiler.java:183) 	at org.codehaus.janino.UnitCompiler$10.visitNewAnonymousClassInstance(UnitCompiler.java:3260) 	at org.codehaus.janino.Java$NewAnonymousClassInstance.accept(Java.java:4131) 	at org.codehaus.janino.UnitCompiler.compileGet(UnitCompiler.java:3278) 	at org.codehaus.janino.UnitCompiler.compileGetValue(UnitCompiler.java:4345) 	at org.codehaus.janino.UnitCompiler.compile2(UnitCompiler.java:1901) 	at org.codehaus.janino.UnitCompiler.access$2100(UnitCompiler.java:183) 	at org.codehaus.janino.UnitCompiler$4.visitReturnStatement(UnitCompiler.java:944) 	at org.codehaus.janino.Java$ReturnStatement.accept(Java.java:2544) 	at org.codehaus.janino.UnitCompiler.compile(UnitCompiler.java:956) 	at org.codehaus.janino.UnitCompiler.compileStatements(UnitCompiler.java:997) 	at org.codehaus.janino.UnitCompiler.compile(UnitCompiler.java:2283) 	at org.codehaus.janino.UnitCompiler.compileDeclaredMethods(UnitCompiler.java:820) 	at org.codehaus.janino.UnitCompiler.compileDeclaredMethods(UnitCompiler.java:792) 	at org.codehaus.janino.UnitCompiler.compile2(UnitCompiler.java:505) 	at org.codehaus.janino.UnitCompiler.compile2(UnitCompiler.java:391) 	at org.codehaus.janino.UnitCompiler.access$400(UnitCompiler.java:183) 	at org.codehaus.janino.UnitCompiler$2.visitPackageMemberClassDeclaration(UnitCompiler.java:345) 	at org.codehaus.janino.Java$PackageMemberClassDeclaration.accept(Java.java:1139) 	at org.codehaus.janino.UnitCompiler.compile(UnitCompiler.java:352) 	at org.codehaus.janino.UnitCompiler.compileUnit(UnitCompiler.java:320) 	at org.codehaus.janino.SimpleCompiler.compileToClassLoader(SimpleCompiler.java:383) 	at org.codehaus.janino.ClassBodyEvaluator.compileToClass(ClassBodyEvaluator.java:315) 	at org.codehaus.janino.ClassBodyEvaluator.cook(ClassBodyEvaluator.java:233) 	at org.codehaus.janino.SimpleCompiler.cook(SimpleCompiler.java:192) 	at org.codehaus.commons.compiler.Cookable.cook(Cookable.java:47) 	at org.codehaus.janino.ClassBodyEvaluator.createInstance(ClassBodyEvaluator.java:340) 	at org.apache.calcite.adapter.enumerable.EnumerableInterpretable.getBindable(EnumerableInterpretable.java:140) 	at org.apache.calcite.adapter.enumerable.EnumerableInterpretable.toBindable(EnumerableInterpretable.java:105) 	... 24 common frames omitted
```

This happens because the `LIKE` function expects that you use it to compare `String` objects. I.e., it expects a format
of `String LIKE String` and we have instead passed to it `Other LIKE String`. To account for this, there exact a few
other RecordPath functions: `RPATH_STRING`, `RPATH_INT`, `RPATH_LONG`, `RPATH_FLOAT`, and `RPATH_DOUBLE` that can be
used when you want to cause the return type to be of type `String`, `Integer`, `Long` (64-bit Integer), `Float`, or
`Double`, respectively. So the above query would need to instead be written as
`SELECT * FROM FLOWFILE WHERE RPATH_STRING(project, '/maintainer/name') LIKE 'Apache%'`, which will produce the desired
output.

### Aggregate Functions

In order to evaluate SQL against a stream of data, the Processor treats each individual FlowFile as its own Table.
Therefore, aggregate functions such as SUM and AVG will be evaluated against all Records in each FlowFile but will not
span FlowFile boundaries. As an example, consider an input FlowFile in CSV format with the following data:

```
name, age, gender
John Doe, 40, Male
Jane Doe, 39, Female
Jimmy Doe, 4, Male
June Doe, 1, Female
```

Given this data, we may wish to perform a query that performs an aggregate function, such as MAX:

```sql
SELECT name
FROM FLOWFILE
WHERE age = (SELECT MAX(age))
```

The above query will select the name of the oldest person, namely John Doe. If a second FlowFile were to then arrive,
its contents would be evaluated as an entirely new Table.