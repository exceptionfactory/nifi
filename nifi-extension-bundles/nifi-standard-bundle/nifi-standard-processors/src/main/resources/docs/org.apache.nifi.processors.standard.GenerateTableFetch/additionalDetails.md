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

# GenerateTableFetch

GenerateTableFetch uses its properties and the specified database connection to generate FlowFiles containing SQL
statements that can be used to fetch "pages" (aka "partitions") of data from a table. GenerateTableFetch executes a
query to the database to determine the current row count and maximum value, and if Maximum Value Columns are specified,
will collect the count of rows whose values for the Maximum Value Columns are larger than those last observed by
GenerateTableFetch. This allows for incremental fetching of "new" rows, rather than generating SQL to fetch the entire
table each time. If no Maximum Value Columns are set, then the processor will generate SQL to fetch the entire table
each time.

In order to generate SQL that will fetch pages/partitions of data, by default GenerateTableFetch will generate SQL that
orders the data based on the Maximum Value Columns (if present) and utilize the row numbers of the result set to
determine each page. For example if the Maximum Value Column is an integer "id" and the partition size is 10, then the
SQL for the first page might be "SELECT \* FROM myTable LIMIT 10" and the second page might be "SELECT \* FROM myTable
OFFSET 10 LIMIT 10", and so on.

Ordering the data can be an expensive operation depending on the database, the number of rows, etc. Alternatively, it is
possible to specify a column whose values will be used to determine the pages, using the Column for Value Partitioning
property. If set, GenerateTableFetch will determine the minimum and maximum values for the column, and uses the minimum
value as the initial offset. The SQL to fetch a page is then based on this initial offset and the total difference in
values (i.e. maximum - minimum) divided by the page size. For example, if the column "id" is used for value
partitioning, and the column contains values 100 to 200, then with a page size of 10 the SQL to fetch the first page
might be "SELECT \* FROM myTable WHERE id >= 100 AND id < 110" and the second page might be "SELECT \* FROM myTable
WHERE id >= 110 AND id < 120", and so on.

It is important that the Column for Value Partitioning be set to a column whose type can be coerced to a long integer (
i.e. not date or timestamp), and that the column values are evenly distributed and not sparse, for best performance. As
a counterexample to the above, consider a column "id" whose values are 100, 2000, and 30000. If the Partition Size is
100, then the column values are relatively sparse, so the SQL for the "second page" (see above example) will return zero
rows, and so will every page until the value in the query becomes "id >= 2000". Another counterexample is when the
values are not uniformly distributed. Consider a column "id" with values 100, 200, 201, 202, ... 299. Then the SQL for
the first page (see above example) will return one row with value id = 100, and the second page will return 100 rows
with values 200 ... 299. This can cause inconsistent processing times downstream, as the pages may contain a very
different number of rows. For these reasons it is recommended to use a Column for Value Partitioning that is
sufficiently dense (not sparse) and fairly evenly distributed.