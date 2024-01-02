--
-- Tests for external toast datums
--

-- directory paths and dlsuffix are passed to us in environment variables
\getenv libdir PG_LIBDIR
\getenv dlsuffix PG_DLSUFFIX

\set regresslib :libdir '/regress' :dlsuffix

CREATE FUNCTION make_tuple_indirect (record)
        RETURNS record
        AS :'regresslib'
        LANGUAGE C STRICT;