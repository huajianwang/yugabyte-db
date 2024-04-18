CREATE TABLE test_table (k INT PRIMARY KEY, v INT) SPLIT INTO 2 TABLETS;

SELECT
    table_name,
    namespace_name,
    table_type,
    ysql_schema_name,
    partition_key_start,
    partition_key_end
FROM yb_local_tablets
WHERE table_name = 'test_table'
ORDER BY partition_key_start NULLS FIRST;
