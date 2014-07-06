package com.tuplejump.stargate;

import com.tuplejump.stargate.cassandra.*;
import com.tuplejump.stargate.lucene.Indexer;
import com.tuplejump.stargate.lucene.NearRealTimeIndexer;
import com.tuplejump.stargate.lucene.Options;
import org.apache.cassandra.config.ColumnDefinition;
import org.apache.cassandra.cql3.CFDefinition;
import org.apache.cassandra.db.ColumnFamily;
import org.apache.cassandra.db.ColumnFamilyStore;
import org.apache.cassandra.db.DecoratedKey;
import org.apache.cassandra.db.filter.ExtendedFilter;
import org.apache.cassandra.db.index.PerRowSecondaryIndex;
import org.apache.cassandra.db.index.SecondaryIndexSearcher;
import org.apache.cassandra.db.marshal.AbstractType;
import org.apache.cassandra.exceptions.ConfigurationException;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.TopDocs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.Set;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * User: satya
 * A per row lucene index.
 * This index requires Options to be passed as a json using sg_options as key in  the CQL Index options
 */
public class RowIndex extends PerRowSecondaryIndex {
    protected static final Logger logger = LoggerFactory.getLogger(RowIndex.class);
    Indexer indexer;
    protected ColumnDefinition columnDefinition;
    protected String keyspace;
    protected String indexName;
    protected String primaryColumnName;
    protected String tableName;
    protected Options options;
    protected RowIndexSupport rowIndexSupport;
    protected CFDefinition tableDefinition;
    private Lock indexLock = new ReentrantLock();

    public RowIndexSupport getRowIndexSupport() {
        return rowIndexSupport;
    }

    public String getPrimaryColumnName() {
        return primaryColumnName;
    }

    @Override
    public void index(ByteBuffer rowKey, ColumnFamily cf) {
        rowIndexSupport.indexRow(rowKey, cf);
    }

    @Override
    public void delete(DecoratedKey key) {
        AbstractType<?> rkValValidator = baseCfs.metadata.getKeyValidator();
        Term term = Fields.rkTerm(rkValValidator.getString(key.key));
        indexer.delete(term);
    }

    public void delete(String pkString, Long ts) {
        indexer.delete(Fields.idTerm(pkString), Fields.tsTerm(ts));
    }


    @Override
    public SecondaryIndexSearcher createSecondaryIndexSearcher(Set<ByteBuffer> columns) {
        while (true) {
            //spin busy
            //don't give the searcher out till this happens
            if (isIndexBuilt(columnDefinition.name)) break;
        }
        return new PerRowSearchSupport(baseCfs.indexManager, this, indexer, columns, columnDefinition.name, this.options);
    }

    public ColumnFamilyStore.AbstractScanIterator getScanIterator(SearchSupport searchSupport, ColumnFamilyStore baseCfs, IndexSearcher searcher, ExtendedFilter filter, TopDocs topDocs, boolean addlFilter) {
        try {
            if (tableDefinition.isComposite) {
                return new WideRowScanner(searchSupport, baseCfs, searcher, filter, topDocs, addlFilter);
            } else {
                return new SimpleRowScanner(searchSupport, baseCfs, searcher, filter, topDocs, addlFilter);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

    }


    @Override
    public void init() {
        assert baseCfs != null;
        assert columnDefs != null;
        assert columnDefs.size() > 0;
        columnDefinition = columnDefs.iterator().next();
        //null comparator since this is a custom index.
        keyspace = baseCfs.metadata.ksName;
        indexName = columnDefinition.getIndexName();
        tableName = baseCfs.name;
        tableDefinition = baseCfs.metadata.getCfDef();
        primaryColumnName = CFDefinition.definitionType.getString(columnDefinition.name).toLowerCase();
        String optionsJson = columnDefinition.getIndexOptions().get(Constants.INDEX_OPTIONS_JSON);
        this.options = Options.getOptions(primaryColumnName, baseCfs, optionsJson);
        lockSwapIndexer(true);
        if (tableDefinition.isComposite) {
            rowIndexSupport = new WideRowIndexSupport(options, indexer, baseCfs);
        } else {
            rowIndexSupport = new SimpleRowIndexSupport(options, indexer, baseCfs);
        }
    }


    @Override
    public void validateOptions() throws ConfigurationException {
        assert columnDefs != null && columnDefs.size() == 1;
    }

    @Override
    public String getIndexName() {
        assert indexName != null;
        return indexName;
    }


    @Override
    public boolean indexes(ByteBuffer name) {
        String toCheck = rowIndexSupport.getActualColumnName(name);
        for (String columnName : this.options.getFields().keySet()) {
            boolean areEqual = toCheck.trim().equalsIgnoreCase(columnName.trim());
            if (logger.isDebugEnabled())
                logger.debug(String.format("Comparing name for index - This column name [%s] - Passed column name [%s] - Equal [%s]", columnName, toCheck, areEqual));
            if (areEqual)
                return true;
        }
        return false;
    }

    @Override
    public void forceBlockingFlush() {
        if (indexer != null)
            indexer.commit();
    }

    @Override
    public long getLiveSize() {
        return (indexer == null) ? 0 : indexer.getLiveSize();
    }

    /**
     * This is not backed by a CF. Instead it is backed by a lucene index.
     *
     * @return null
     */
    @Override
    public ColumnFamilyStore getIndexCfs() {
        return null;
    }


    @Override
    public void removeIndex(ByteBuffer byteBuffer) {
        logger.warn(indexName + " Got call to REMOVE index.");
        invalidate();
    }

    @Override
    public void reload() {
        logger.warn(indexName + " Got call to RELOAD index.");
        if (indexer == null && columnDefinition.getIndexOptions() != null && !columnDefinition.getIndexOptions().isEmpty()) {
            init();
        }
        if (indexer != null && isIndexBuilt(columnDefinition.name)) {
            indexer.commit();
        }
    }


    private void lockSwapIndexer(boolean createAnother) {
        try {
            boolean locked = indexLock.tryLock();
            if (locked) {
                if (indexer != null) {
                    indexer.removeIndex();
                    indexer = null;
                }
                if (createAnother)
                    indexer = new NearRealTimeIndexer(this.options.analyzer, keyspace, baseCfs.name, indexName);

            } else {
                throw new RuntimeException(String.format("Unable to acquire reload lock for Index %s of %s on %s of %s. Another thread is already reloading it", indexName, primaryColumnName, baseCfs.name, keyspace));
            }
        } finally {
            indexLock.unlock();
        }
    }

    @Override
    public void invalidate() {
        lockSwapIndexer(false);
        setIndexRemoved();
    }

    @Override
    public void truncateBlocking(long l) {
        try {
            boolean locked = indexLock.tryLock();
            if (locked) {
                if (indexer != null) {
                    indexer.truncate(l);
                    logger.warn(indexName + " Truncated index {}.", indexName);
                }

            } else {
                throw new RuntimeException(String.format("Unable to acquire reload lock for Index %s of %s on %s of %s. Another thread is already modifying it", indexName, primaryColumnName, tableName, keyspace));
            }
        } finally {
            indexLock.unlock();
        }
    }

    @Override
    public String toString() {
        return "RowIndex [index=" + indexName + ", keyspace=" + keyspace + ", table=" + tableName + ", column=" + primaryColumnName + "]";
    }
}