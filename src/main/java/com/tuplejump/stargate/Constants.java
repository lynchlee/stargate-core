package com.tuplejump.stargate;

/**
 * User: satya
 */
public final class Constants {

    public static final String INDEX_OPTIONS_JSON = "sg_options";

    public static final String PK_NAME_INDEXED = "_row_key";
    public static final String PK_NAME_DOC_VAL = "_row_key_val";
    public static final String CF_TS_DOC_VAL = "_cf_ts_val";
    public static final String CF_TS_INDEXED = "_cf_ts";

    //lucene options per field
    public static final String striped = "striped";
    //lucene options

    public enum Analyzers {
        StandardAnalyzer, WhitespaceAnalyzer, StopAnalyzer, SimpleAnalyzer, KeywordAnalyzer, JsonAnalyzer

    }


}
