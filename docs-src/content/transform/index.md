---
title: Transform
weight: 30
type: blog
---

`*Transform` stages apply a single transformation to one or more incoming datasets.

Transformers should meet this criteria:

- Be logically [pure](https://en.wikipedia.org/wiki/Pure_function).
- Perform only a [single function](https://en.wikipedia.org/wiki/Separation_of_concerns).
- Utilise Spark [internal functionality](https://spark.apache.org/docs/latest/sql-programming-guide.html) where possible.

## DebeziumTransform
##### Since: 3.6.0 - Supports Streaming: True
{{< note title="Plugin" >}}
The `DebeziumTransform` is provided by the https://github.com/tripl-ai/arc-debezium-pipeline-plugin package.
{{</note>}}

{{< note title="Experimental" >}}
The `DebeziumTransform` is currently in experimental state whilst the requirements become clearer.

This means this API is likely to change and feedback is valued.
{{</note>}}

The `DebeziumTransform` stage decodes [Debezium](https://debezium.io/) change-data-capture `JSON` formatted messages for `MySQL`, `PostgreSQL` and `MongoDB` databases and creates a DataFrame which represents an eventually consistent view of a source dataset at a point in time. It supports `Complete` and `Append` modes [Structured Streaming](https://spark.apache.org/docs/latest/structured-streaming-programming-guide.html) modes.

It is intended to be used after a [KafkaExtract](/extract/#kafkaextract) stage.

### Parameters

| Attribute | Type | Required | Description |
|-----------|------|----------|-------------|
|name|String|true|{{< readfile file="/content/partials/fields/stageName.md" markdown="true" >}}|
|environments|Array[String]|true|{{< readfile file="/content/partials/fields/environments.md" markdown="true" >}}|
|inputView|String|true|{{< readfile file="/content/partials/fields/inputView.md" markdown="true" >}}|
|outputView|String|true|{{< readfile file="/content/partials/fields/outputView.md" markdown="true" >}}|
|schema|Array|true*|An inline Arc [schema](/schema). Only one of `schema`, `schemaURI`, `schemaView` can be provided.|
|schemaURI|URI|true*|URI of the input JSON file containing the Arc [schema](/schema). Only one of `schema`, `schemaURI`, `schemaView` can be provided.|
|schemaView|String|true*|Similar to `schemaURI` but allows the Arc [schema](/schema) to be passed in as another `DataFrame`.  Only one of `schema`, `schemaURI`, `schemaView` can be provided.|
|authentication|Map[String, String]|false|{{< readfile file="/content/partials/fields/authentication.md" markdown="true" >}}|
|description|String|false|{{< readfile file="/content/partials/fields/description.md" markdown="true" >}}|
|id|String|false|{{< readfile file="/content/partials/fields/stageId.md" markdown="true" >}}|
|strict|Boolean|false|If `strict` mode is enabled then every change per key is applied in strict sequence and will fail if a prior state is not what is expected. When `strict` mode is disabled then `DebeziumTransform` will employ a `last-writer wins` [conflict resolution](https://en.wikipedia.org/wiki/Eventual_consistency#Conflict_resolution) strategy which requires strict ordering from the source but will be quicker.<br><br>Not all sources support non-strict mode due to how they record change events such as `MongoDB`.<br><br>Default: `true`.|

### Examples

#### Minimal
{{< readfile file="/resources/docs_resources_plugins/DebeziumTransformMin" highlight="json" >}}

#### Complete
{{< readfile file="/resources/docs_resources_plugins/DebeziumTransformComplete" highlight="json" >}}


## DiffTransform
##### Since: 1.0.8 - Supports Streaming: False

The `DiffTransform` stage calculates the difference between two input datasets and produces three datasets:

- A dataset of the `intersection` of the two datasets - or rows that exist and are the same in both datasets.
- A dataset of the `left` dataset - or rows that only exist in the left input dataset (`inputLeftView`).
- A dataset of the `right` dataset - or rows that only exist in the right input dataset (`inputRightView`).

{{< note title="Persistence" >}}
This stage performs this 'diffing' operation in a single pass so if multiple of the output views are going to be used then it is a good idea to set persist = `true` to reduce the cost of recomputing the difference multiple times.
{{</note>}}

### Parameters

| Attribute | Type | Required | Description |
|-----------|------|----------|-------------|
|name|String|true|{{< readfile file="/content/partials/fields/stageName.md" markdown="true" >}}|
|environments|Array[String]|true|{{< readfile file="/content/partials/fields/environments.md" markdown="true" >}}|
|inputLeftView|String|true|Name of first incoming Spark dataset.|
|inputRightView|String|true|Name of second incoming Spark dataset.|
|description|String|false|{{< readfile file="/content/partials/fields/description.md" markdown="true" >}}|
|id|String|false|{{< readfile file="/content/partials/fields/stageId.md" markdown="true" >}}|
|inputLeftKeys|Array[String]|false|A list of columns to create the join key from the first incoming Spark dataset. If provided, the `outputIntersectionView` will contain `left` and `right` structs. If not provided all columns are included.|
|inputRightKeys|Array[String]|false|A list of columns to create the join key from the second incoming Spark dataset. If provided, the `outputIntersectionView` will contain `left` and `right` structs. If not provided all columns are included.|
|outputIntersectionView|String|false|Name of output `intersection` view.|
|outputLeftView|String|false|Name of output `left` view.|
|outputRightView|String|false|Name of output `right` view.|
|persist|Boolean|false|Whether to persist dataset to Spark cache.|

### Examples

#### Minimal
{{< readfile file="/resources/docs_resources/DiffTransformMin" highlight="json" >}}

#### Complete
{{< readfile file="/resources/docs_resources/DiffTransformComplete" highlight="json" >}}


## HTTPTransform
##### Since: 1.0.9 - Supports Streaming: True

The `HTTPTransform` stage transforms the incoming dataset by `POST`ing the value in the incoming dataset with column name `value` (must be of type `string` or `bytes`) and appending the response body from an external API as `body`.

A good use case of the `HTTPTransform` stage is to call an external [RESTful](https://en.wikipedia.org/wiki/Representational_state_transfer) machine learning model service. To see an example of how to host a simple model as a service see:<br>
https://github.com/tripl-ai/arc/tree/master/src/it/resources/flask_serving

### Parameters

| Attribute | Type | Required | Description |
|-----------|------|----------|-------------|
|name|String|true|{{< readfile file="/content/partials/fields/stageName.md" markdown="true" >}}|
|environments|Array[String]|true|{{< readfile file="/content/partials/fields/environments.md" markdown="true" >}}|
|inputView|String|true|{{< readfile file="/content/partials/fields/inputView.md" markdown="true" >}}|
|outputView|String|true|{{< readfile file="/content/partials/fields/outputView.md" markdown="true" >}}|
|uri|URI|true|URI of the HTTP server.|
|batchSize|Integer|false|The number of records to send in each HTTP request to reduce the cost of HTTP overhead.<br><br>Default: `1`.|
|delimiter|String|false|When using a `batchSize` greater than one this option allows the specification of a delimiter so that the receiving HTTP service can split the request body into records and Arc can split the response body back into records.<br><br>Default: `\n` (newline).|
|description|String|false|{{< readfile file="/content/partials/fields/description.md" markdown="true" >}}|
|failMode|String|false|Either `permissive` or `failfast`:<br><br>`permissive` will process all rows in the dataset and collect HTTP response values (`statusCode`, `reasonPhrase`, `contentType`, `responseTime`) into a `response` column. Rules can then be applied in a [SQLValidate](validate/#sqlvalidate) stage if required.<br><br>`failfast` will fail the Arc job on the first reponse with a `statusCode` not in the `validStatusCodes` array.<br><br>Default: `failfast`.|
|headers|Map[String, String]|false|{{< readfile file="/content/partials/fields/headers.md" markdown="true" >}}|
|id|String|false|{{< readfile file="/content/partials/fields/stageId.md" markdown="true" >}}|
|inputField|String|false|The field to pass to the endpoint. JSON encoding can be used to pass multiple values (tuples).<br><br>Default: `value`.|
|numPartitions|Integer|false|{{< readfile file="/content/partials/fields/numPartitions.md" markdown="true" >}}|
|partitionBy|Array[String]|false|{{< readfile file="/content/partials/fields/partitionBy.md" markdown="true" >}}|
|persist|Boolean|false|{{< readfile file="/content/partials/fields/persist.md" markdown="true" >}}|
|validStatusCodes|Array[Integer]|false|{{< readfile file="/content/partials/fields/validStatusCodes.md" markdown="true" >}} Note: all request response codes must be contained in this list for the stage to be successful if `failMode` is set to `failfast`.|

### Examples

#### Minimal
{{< readfile file="/resources/docs_resources/HTTPTransformMin" highlight="json" >}}

#### Complete
{{< readfile file="/resources/docs_resources/HTTPTransformComplete" highlight="json" >}}


## JSONTransform
##### Since: 1.0.0 - Supports Streaming: True

The `JSONTransform` stage transforms the incoming dataset to rows of `json` strings with the column name `value`. It is intended to be used before stages like [HTTPLoad](/load/#httpload) or [HTTPTransform](/transform/#httptransform) to prepare the data for sending externally.

### Parameters

| Attribute | Type | Required | Description |
|-----------|------|----------|-------------|
|name|String|true|{{< readfile file="/content/partials/fields/stageName.md" markdown="true" >}}|
|environments|Array[String]|true|{{< readfile file="/content/partials/fields/environments.md" markdown="true" >}}|
|inputView|String|true|{{< readfile file="/content/partials/fields/inputView.md" markdown="true" >}}|
|outputView|String|true|{{< readfile file="/content/partials/fields/outputView.md" markdown="true" >}}|
|description|String|false|{{< readfile file="/content/partials/fields/description.md" markdown="true" >}}|
|id|String|false|{{< readfile file="/content/partials/fields/stageId.md" markdown="true" >}}|
|numPartitions|Integer|false|{{< readfile file="/content/partials/fields/numPartitions.md" markdown="true" >}}|
|partitionBy|Array[String]|false|{{< readfile file="/content/partials/fields/partitionBy.md" markdown="true" >}}|
|persist|Boolean|false|{{< readfile file="/content/partials/fields/persist.md" markdown="true" >}}|

### Examples

#### Minimal
{{< readfile file="/resources/docs_resources/JSONTransformMin" highlight="json" >}}

#### Complete
{{< readfile file="/resources/docs_resources/JSONTransformComplete" highlight="json" >}}


## MetadataFilterTransform
##### Since: 1.0.9 - Supports Streaming: True

The `MetadataFilterTransform` stage transforms the incoming dataset by filtering columns using the embedded column [metadata](/schema).

Underneath Arc will register a table called `metadata` which contains the metadata of the `inputView`. This allows complex SQL statements to be executed which returns which columns to retain from the `inputView` in the `outputView`. The available columns in the `metadata` table are:

| Field | Description |
|-------|-------------|
|name|The field name.|
|type|The field type.|
|metadata|The field metadata.|

This can be used like:

```sql
-- only select columns which are not personally identifiable information
SELECT
    name
FROM metadata
WHERE metadata.pii = false
```

Will produce an `outputView` which only contains the columns in `inputView` where the `inputView` column metadata contains a key `pii` which has the value equal to `false`.

If the `sqlParams` contains boolean parameter `pii_authorized` if the job is authorised to use Personally identifiable information or not then it could be used like:

```sql
-- only select columns which job is authorised to access based on ${pii_authorized}
SELECT
    name
FROM metadata
WHERE metadata.pii = (
    CASE
        WHEN ${pii_authorized} = true
        THEN metadata.pii   -- this will allow both true and false metadata.pii values if pii_authorized = true
        ELSE false          -- else if pii_authorized = false only allow metadata.pii = false values
    END
)
```

The `inputView` and `outputView` can be set to the same name so that downstream stages have no way of accessing the pre-filtered data accidentially.

### Parameters

| Attribute | Type | Required | Description |
|-----------|------|----------|-------------|
|name|String|true|{{< readfile file="/content/partials/fields/stageName.md" markdown="true" >}}|
|environments|Array[String]|true|{{< readfile file="/content/partials/fields/environments.md" markdown="true" >}}|
|inputURI|URI|*true|{{< readfile file="/content/partials/fields/inputURI.md" markdown="true" >}} Required if `sql` not provided.<br><br>This statement must be written to query against a table called `metadata` and must return at least the `name` column or an error will be raised.|
|sql|String|*true|{{< readfile file="/content/partials/fields/sql.md" markdown="true" >}} Required if `inputURI` not provided.<br><br>This statement must be written to query against a table called `metadata` and must return at least the `name` column or an error will be raised.|
|inputURI|URI|true|{{< readfile file="/content/partials/fields/inputURI.md" markdown="true" >}}|
|inputView|String|true|{{< readfile file="/content/partials/fields/inputView.md" markdown="true" >}}|
|outputView|String|true|{{< readfile file="/content/partials/fields/outputView.md" markdown="true" >}}|
|authentication|Map[String, String]|false|{{< readfile file="/content/partials/fields/authentication.md" markdown="true" >}}|
|persist|Boolean|true|{{< readfile file="/content/partials/fields/persist.md" markdown="true" >}}|
|description|String|false|{{< readfile file="/content/partials/fields/description.md" markdown="true" >}}|
|id|String|false|{{< readfile file="/content/partials/fields/stageId.md" markdown="true" >}}|
|numPartitions|Integer|false|{{< readfile file="/content/partials/fields/numPartitions.md" markdown="true" >}}|
|partitionBy|Array[String]|false|{{< readfile file="/content/partials/fields/partitionBy.md" markdown="true" >}}|
|sqlParams|Map[String, String]|false|{{< readfile file="/content/partials/fields/sqlParams.md" markdown="true" >}}|

### Examples

#### Minimal
{{< readfile file="/resources/docs_resources/MetadataFilterTransformMin" highlight="json" >}}

#### Complete
{{< readfile file="/resources/docs_resources/MetadataFilterTransformComplete" highlight="json" >}}


## MetadataTransform
##### Since: 2.4.0 - Supports Streaming: True

The `MetadataTransform` stage attaches metadata input `Dataframe` and returns a new `DataFrame`.

### Parameters

| Attribute | Type | Required | Description |
|-----------|------|----------|-------------|
|name|String|true|{{< readfile file="/content/partials/fields/stageName.md" markdown="true" >}}|
|environments|Array[String]|true|{{< readfile file="/content/partials/fields/environments.md" markdown="true" >}}|
|inputView|String|true|{{< readfile file="/content/partials/fields/inputView.md" markdown="true" >}}|
|outputView|String|true|{{< readfile file="/content/partials/fields/outputView.md" markdown="true" >}}|
|schema|Array|true*|An inline Arc [schema](/schema). Only one of `schema`, `schemaURI`, `schemaView` can be provided.|
|schemaURI|URI|true*|URI of the input JSON file containing the Arc [schema](/schema). Only one of `schema`, `schemaURI`, `schemaView` can be provided.|
|schemaView|String|true*|Similar to `schemaURI` but allows the Arc [schema](/schema) to be passed in as another `DataFrame`.  Only one of `schema`, `schemaURI`, `schemaView` can be provided.|
|description|String|false|{{< readfile file="/content/partials/fields/description.md" markdown="true" >}}|
|id|String|false|{{< readfile file="/content/partials/fields/stageId.md" markdown="true" >}}|
|failMode|String|false|Either `permissive` or `failfast`:<br><br>`permissive` will attach metadata to any column of the input `DataFrame` which has the same name as in the incoming schema.<br><br>`failfast` will fail the Arc job if any of the columns in the input schema are not found in the input `DataFrame`.<br><br>Default: `permissive`.|
|numPartitions|Integer|false|{{< readfile file="/content/partials/fields/numPartitions.md" markdown="true" >}}|
|partitionBy|Array[String]|false|{{< readfile file="/content/partials/fields/partitionBy.md" markdown="true" >}}|
|persist|Boolean|false|{{< readfile file="/content/partials/fields/persist.md" markdown="true" >}}|

### Examples

#### Minimal
{{< readfile file="/resources/docs_resources/MetadataTransformMin" highlight="json" >}}

#### Complete
{{< readfile file="/resources/docs_resources/MetadataTransformComplete" highlight="json" >}}


## MLTransform
##### Since: 1.0.0 - Supports Streaming: True

The `MLTransform` stage transforms the incoming dataset with a pretrained Spark ML (Machine Learning) model. This will append one or more predicted columns to the incoming dataset. The incoming model must be a `PipelineModel` or `CrossValidatorModel` produced using Spark's Scala, Java, PySpark or SparkR API.

### Parameters

| Attribute | Type | Required | Description |
|-----------|------|----------|-------------|
|name|String|true|{{< readfile file="/content/partials/fields/stageName.md" markdown="true" >}}|
|environments|Array[String]|true|{{< readfile file="/content/partials/fields/environments.md" markdown="true" >}}|
|inputURI|URI|true|URI of the input `PipelineModel` or `CrossValidatorModel`.|
|inputView|String|true|{{< readfile file="/content/partials/fields/inputView.md" markdown="true" >}}|
|outputView|String|true|{{< readfile file="/content/partials/fields/outputView.md" markdown="true" >}}|
|authentication|Map[String, String]|false|{{< readfile file="/content/partials/fields/authentication.md" markdown="true" >}}|
|description|String|false|{{< readfile file="/content/partials/fields/description.md" markdown="true" >}}|
|id|String|false|{{< readfile file="/content/partials/fields/stageId.md" markdown="true" >}}|
|numPartitions|Integer|false|{{< readfile file="/content/partials/fields/numPartitions.md" markdown="true" >}}|
|partitionBy|Array[String]|false|{{< readfile file="/content/partials/fields/partitionBy.md" markdown="true" >}}|
|persist|Boolean|true|{{< readfile file="/content/partials/fields/persist.md" markdown="true" >}} MLTransform will also log percentiles of prediction probabilities for classification models if this option is enabled.|
|description|String|false|{{< readfile file="/content/partials/fields/description.md" markdown="true" >}}|

### Examples

#### Minimal
{{< readfile file="/resources/docs_resources/MLTransformMin" highlight="json" >}}

#### Complete
{{< readfile file="/resources/docs_resources/MLTransformComplete" highlight="json" >}}

## SimilarityJoinTransform
##### Since: 2.1.0 - Supports Streaming: True

The `SimilarityJoinTransform` stage uses [Approximate String Matching](https://en.wikipedia.org/wiki/Approximate_string_matching) (a.k.a. Fuzzy Matching) to find similar records between two datasets. It is possible to pass the same dataset into both side of the comparison to find duplicates (ie. both `leftView` and `rightView`) to find duplicates (in which case the `threshold` value should be high (close to `1.0`) to avoid a potentially very large cross-product resultset).

### Parameters

| Attribute | Type | Required | Description |
|-----------|------|----------|-------------|
|name|String|true|{{< readfile file="/content/partials/fields/stageName.md" markdown="true" >}}|
|environments|Array[String]|true|{{< readfile file="/content/partials/fields/environments.md" markdown="true" >}}|
|leftView|String|true|The view name of the `left` dataset. This should be the bigger of the two input sets.|
|rightView|String|true|The view name of the `right` dataset.|
|leftFields|Array[String]|true|Columns to include in the similarity join from the `left` dataset. These are order dependent.|
|rightFields|Array[String]|true|Columns to include in the similarity join from the `right` dataset. These are order dependent.|
|outputView|String|true|{{< readfile file="/content/partials/fields/outputView.md" markdown="true" >}}|
|caseSensitive|Boolean|false|Whether to use case sensitive comparison.<br><br>Default: `false`.|
|description|String|false|{{< readfile file="/content/partials/fields/description.md" markdown="true" >}}|
|id|String|false|{{< readfile file="/content/partials/fields/stageId.md" markdown="true" >}}|
|numHashTables|Integer|false|The number of hash tables which can be used to trade off execution time vs. false positive rate. Lower values should produce quicker exeuction but higher false positive rate.<br><br>Default: `5`.|
|numPartitions|Integer|false|{{< readfile file="/content/partials/fields/numPartitions.md" markdown="true" >}}|
|params|Map[String, String]|false|{{< readfile file="/content/partials/fields/params.md" markdown="true" >}} Currently unused.|
|partitionBy|Array[String]|false|{{< readfile file="/content/partials/fields/partitionBy.md" markdown="true" >}}|
|persist|Boolean|false|{{< readfile file="/content/partials/fields/persist.md" markdown="true" >}}|
|shingleLength|Integer|false|The length to split the input fields into. E.g. the string `1 Parliament Drive` would be split into [`1 P`, ` Pa`, `Par`, `arl`...] if `shingleLength` is set to `3`. Longer or shorter `shingleLength` may help provide higher similarity depending on your dataset.<br><br>Default: `3`.|
|threshold|Double|false|The similarity threshold for evaluating the records as the same. The default, `0.8`, means that 80% of the character sequences must be the same for the records to be considered equal for joining.<br><br>Default: `0.8`.|

### Examples

#### Minimal
{{< readfile file="/resources/docs_resources/SimilarityJoinTransformMin" highlight="json" >}}

#### Complete
{{< readfile file="/resources/docs_resources/SimilarityJoinTransformComplete" highlight="json" >}}


## SQLTransform
##### Since: 1.0.0 - Supports Streaming: True

The `SQLTransform` stage transforms the incoming dataset with a [Spark SQL](https://spark.apache.org/docs/latest/sql-programming-guide.html) statement. This stage relies on previous stages to load and register the dataset views (`outputView`) and will execute arbitrary SQL statements against those datasets.

All the inbuilt [Spark SQL functions](https://spark.apache.org/docs/latest/api/sql/index.html) are available and have been extended with some [additional functions](/partials/#user-defined-functions).

Please be aware that in streaming mode not all join operations are available. See: [Support matrix for joins in streaming queries](https://spark.apache.org/docs/latest/structured-streaming-programming-guide.html#support-matrix-for-joins-in-streaming-queries).

{{< note title="CAST vs TypingTransform" >}}
It is strongly recommended to use the `TypingTransform` for reproducible, repeatable results.

Whilst SQL is capable of converting data types using the `CAST` function (e.g. `CAST(dateColumn AS DATE)`) be very careful. ANSI SQL specifies that any failure to convert then an exception condition is raised: `data exception-invalid character value for cast` whereas Spark SQL will return a null value and suppress any exceptions: `try s.toString.toInt catch { case _: NumberFormatException => null }`. If you used a `CAST` in a financial scenario, for example bill calculation, the silent `NULL`ing of values could result in errors being suppressed and bills incorrectly calculated.
{{</note>}}

### Parameters

| Attribute | Type | Required | Description |
|-----------|------|----------|-------------|
|name|String|true|{{< readfile file="/content/partials/fields/stageName.md" markdown="true" >}}|
|environments|Array[String]|true|{{< readfile file="/content/partials/fields/environments.md" markdown="true" >}}|
|inputURI|URI|*true|{{< readfile file="/content/partials/fields/inputURI.md" markdown="true" >}} Required if `sql` not provided.|
|sql|String|*true|{{< readfile file="/content/partials/fields/sql.md" markdown="true" >}} Required if `inputURI` not provided.|
|outputView|String|true|{{< readfile file="/content/partials/fields/outputView.md" markdown="true" >}}|
|authentication|Map[String, String]|false|{{< readfile file="/content/partials/fields/authentication.md" markdown="true" >}}|
|description|String|false|{{< readfile file="/content/partials/fields/description.md" markdown="true" >}}|
|id|String|false|{{< readfile file="/content/partials/fields/stageId.md" markdown="true" >}}|
|numPartitions|Integer|false|{{< readfile file="/content/partials/fields/numPartitions.md" markdown="true" >}}|
|partitionBy|Array[String]|false|{{< readfile file="/content/partials/fields/partitionBy.md" markdown="true" >}}|
|persist|Boolean|false|{{< readfile file="/content/partials/fields/persist.md" markdown="true" >}}|
|sqlParams|Map[String, String]|false|{{< readfile file="/content/partials/fields/sqlParams.md" markdown="true" >}}<br><br>For example if the sqlParams contains parameter `current_timestamp` of value `2018-11-24 14:48:56` then this statement would execute in a deterministic way: `SELECT * FROM customer WHERE expiry > FROM_UNIXTIME(UNIX_TIMESTAMP('${current_timestamp}', 'uuuu-MM-dd HH:mm:ss'))` (so would be testable).|

The SQL statement is a plain Spark SQL statement, for example:

```sql
SELECT
    customer.customer_id
    ,customer.first_name
    ,customer.last_name
    ,account.account_id
    ,account.account_name
FROM customer
LEFT JOIN account ON account.customer_id = customer.customer_id
```

### Magic

The `%sql` magic is available via [arc-jupyter](https://github.com/tripl-ai/arc-jupyter) with these available parameters:

```sql
%sql name="sqltransform" description="description" environments=production,test outputView=example persist=true sqlParams=inputView=customer,inputField=id
SELECT
    ${inputField}
FROM ${inputView}
```

### Examples

#### Minimal
{{< readfile file="/resources/docs_resources/SQLTransformMin" highlight="json" >}}

#### Complete
{{< readfile file="/resources/docs_resources/SQLTransformComplete" highlight="json" >}}

The `current_date` and `current_timestamp` can easily be passed in as environment variables using `$(date "+%Y-%m-%d")` and `$(date "+%Y-%m-%d %H:%M:%S")` respectively.

## TensorFlowServingTransform
##### Since: 1.0.0 - Supports Streaming: True

The `TensorFlowServingTransform` stage transforms the incoming dataset by calling a [TensorFlow Serving](https://www.tensorflow.org/serving/) service. Because each call is atomic the TensorFlow Serving instances could be behind a load balancer to increase throughput.

To see how to host a simple model in [TensorFlow Serving](https://www.tensorflow.org/serving/) see:<br>
https://github.com/tripl-ai/arc/tree/master/src/it/resources/tensorflow_serving

### Parameters

| Attribute | Type | Required | Description |
|-----------|------|----------|-------------|
|name|String|true|{{< readfile file="/content/partials/fields/stageName.md" markdown="true" >}}|
|environments|Array[String]|true|{{< readfile file="/content/partials/fields/environments.md" markdown="true" >}}|
|inputView|String|true|{{< readfile file="/content/partials/fields/inputView.md" markdown="true" >}}|
|outputView|String|true|{{< readfile file="/content/partials/fields/outputView.md" markdown="true" >}}|
|uri|String|true|The `URI` of the TensorFlow Serving REST end point.|
|batchSize|Integer|false|The number of records to sent to TensorFlow Serving in each call. A higher number will decrease the number of calls to TensorFlow Serving which may be more efficient.<br><br>Default: `100`.|
|description|String|false|{{< readfile file="/content/partials/fields/description.md" markdown="true" >}}|
|id|String|false|{{< readfile file="/content/partials/fields/stageId.md" markdown="true" >}}|
|inputField|String|false|The field to pass to the model. JSON encoding can be used to pass multiple values (tuples).<br><br>Default: `value`.|
|numPartitions|Integer|false|{{< readfile file="/content/partials/fields/numPartitions.md" markdown="true" >}}|
|params|Map[String, String]|false|{{< readfile file="/content/partials/fields/params.md" markdown="true" >}} Currently unused.|
|partitionBy|Array[String]|false|{{< readfile file="/content/partials/fields/partitionBy.md" markdown="true" >}}|
|persist|Boolean|false|{{< readfile file="/content/partials/fields/persist.md" markdown="true" >}}|
|responseType|String|false|The type returned by the TensorFlow Serving API. Expected to be `integer`, `double` or `object` (which may present as a `string` depending on how the model has been built).<br><br>Default: `object`.|
|signatureName|String|false|{{< readfile file="/content/partials/fields/signatureName.md" markdown="true" >}}|

### Examples

#### Minimal
{{< readfile file="/resources/docs_resources/TensorFlowServingTransformMin" highlight="json" >}}

#### Complete
{{< readfile file="/resources/docs_resources/TensorFlowServingTransformComplete" highlight="json" >}}


## TypingTransform
##### Since: 1.0.0 - Supports Streaming: True

The `TypingTransform` stage transforms the incoming dataset with based on a schema defined in the [schema](../schema/) format.

The logical process that is applied to perform the typing on a field-by-field basis is shown below.

### Parameters

| Attribute | Type | Required | Description |
|-----------|------|----------|-------------|
|name|String|true|{{< readfile file="/content/partials/fields/stageName.md" markdown="true" >}}|
|environments|Array[String]|true|{{< readfile file="/content/partials/fields/environments.md" markdown="true" >}}|
|schema|Array|true*|An inline Arc [schema](/schema). Only one of `schema`, `schemaURI`, `schemaView` can be provided.|
|schemaURI|URI|true*|URI of the input JSON file containing the Arc [schema](/schema). Only one of `schema`, `schemaURI`, `schemaView` can be provided.|
|schemaView|String|true*|Similar to `schemaURI` but allows the Arc [schema](/schema) to be passed in as another `DataFrame`.  Only one of `schema`, `schemaURI`, `schemaView` can be provided.|
|inputView|String|true|{{< readfile file="/content/partials/fields/inputView.md" markdown="true" >}}|
|outputView|String|true|{{< readfile file="/content/partials/fields/outputView.md" markdown="true" >}}|
|authentication|Map[String, String]|false|{{< readfile file="/content/partials/fields/authentication.md" markdown="true" >}}|
|description|String|false|{{< readfile file="/content/partials/fields/description.md" markdown="true" >}}|
|failMode|String|false|Either `permissive` or `failfast`:<br><br>`permissive` will process all rows in the dataset and collect any errors for each row in the `_errors` column. Rules can then be applied in a [SQLValidate](validate/#sqlvalidate) stage if required.<br><br>`failfast` will fail the Arc job on the first row containing at least one error.<br><br>Default: `permissive`.|
|id|String|false|{{< readfile file="/content/partials/fields/stageId.md" markdown="true" >}}|
|numPartitions|Integer|false|{{< readfile file="/content/partials/fields/numPartitions.md" markdown="true" >}}|
|partitionBy|Array[String]|false|{{< readfile file="/content/partials/fields/partitionBy.md" markdown="true" >}}|
|persist|Boolean|false|{{< readfile file="/content/partials/fields/persist.md" markdown="true" >}}|

### Examples

#### Minimal
{{< readfile file="/resources/docs_resources/TypingTransformMin" highlight="json" >}}

#### Complete
{{< readfile file="/resources/docs_resources/TypingTransformComplete" highlight="json" >}}


A demonstration of how the `TypingTransform` behaves. Assuming you have read an input like a [DelimitedExtract](../extract/#DelimitedExtract) which will read a dataset where all the columns are read as strings:

```bash
+-------------------------+---------------------+
|startTime                |endTime              |
+-------------------------+---------------------+
|2018-09-26 07:17:43      |2018-09-27 07:17:43  |
|2018-09-25 08:25:51      |2018-09-26 08:25:51  |
|2018-02-30 01:16:40      |2018-03-01 01:16:40  |
|30 February 2018 01:16:40|2018-03-2018 01:16:40|
+-------------------------+---------------------+
```

In this case the goal is  to safely convert the values from strings like `"2018-09-26 07:17:43"` to a proper `timestamp` object so that we can ensure the timestamp is valid (e.g. not on a date that does not exist e.g. the 30 day of February) and can easily perform date operations such as subtracting 1 week. To do so a [schema](../schema/) file could be constructed to look like:

```json
[
  {
    "id": "8e42c8f0-22a8-40db-9798-6dd533c1de36",
    "name": "startTime",
    "description": "The startTime field.",
    "type": "timestamp",
    "trim": true,
    "nullable": true,
    "nullableValues": [
        "",
        "null"
    ],
    "formatters": [
        "uuuu-MM-dd HH:mm:ss"
    ],
    "timezoneId": "UTC"
  },
  {
    "id": "2e7553cf-2748-49cd-a291-8918823e706a",
    "name": "endTime",
    "description": "The endTime field.",
    "type": "timestamp",
    "trim": true,
    "nullable": true,
    "nullableValues": [
        "",
        "null"
    ],
    "formatters": [
        "uuuu-MM-dd HH:mm:ss"
    ],
    "timezoneId": "UTC"
  }
]
```

Here is the output of the `TypingTransformation` when applied to the input dataset.

```bash
+-------------------+-------------------+--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------+
|startTime          |endTime            |_errors                                                                                                                                                                                                                                                             |
+-------------------+-------------------+--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------+
|2018-09-26 17:17:43|2018-09-27 17:17:43|[]                                                                                                                                                                                                                                                                  |
|2018-09-25 18:25:51|2018-09-26 18:25:51|[]                                                                                                                                                                                                                                                                  |
|null               |2018-03-01 12:16:40|[[startTime, Unable to convert '2018-02-30 01:16:40' to timestamp using formatters ['uuuu-MM-dd HH:mm:ss'] and timezone 'UTC']]                                                                                                                                     |
|null               |null               |[[startTime, Unable to convert '28 February 2018 01:16:40' to timestamp using formatters ['uuuu-MM-dd HH:mm:ss'] and timezone 'UTC'], [endTime, Unable to convert '2018-03-2018 01:16:40' to timestamp using formatters ['uuuu-MM-dd HH:mm:ss'] and timezone 'UTC']]|
+-------------------+-------------------+--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------+
```

- Because the conversion happened successfully for both values on the first two rows there are no errors for those rows.
- On the third row the value `'2018-02-30 01:16:40'` cannot be converted as the 30th day of February is not a valid date and the value is set to `null`. If the `nullable` in the [schema](../schema/) for field `startTime` was set to `false` the job would fail as it would be unable to continue.
- On the forth row both rows are invalid as the `formatter` and `date` values are both wrong.

The [SQLValidate](../validate/#sqlvalidate) stage is a good way to use this data to enforce data quality constraints.

### Logical Flow

The sequence that these fields are converted from `string` fields to `typed` fields is per this flow chart. Each value and its typing schema is passed into this logical process. For each row the `values` are returned as standard table columns and the returned `error` values are groupd into a field called `_errors` on a row-by-row basis. Patterns for consuming the `_errors` array is are demonstrated in the [SQLValidate](validate/#sqlvalidate) stage.

![Logical Flow for Data Typing](/img/typing_flow.png "Logical Flow for Data Typing")
