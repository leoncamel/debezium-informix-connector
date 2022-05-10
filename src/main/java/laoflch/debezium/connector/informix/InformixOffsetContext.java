package laoflch.debezium.connector.informix;

import io.debezium.connector.SnapshotRecord;
import io.debezium.pipeline.spi.OffsetContext;
import io.debezium.pipeline.txmetadata.TransactionContext;
import io.debezium.relational.TableId;
import io.debezium.schema.DataCollectionId;
import io.debezium.util.Collect;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.Struct;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;

public class InformixOffsetContext implements OffsetContext {

    private static final String SERVER_PARTITION_KEY = "server";
    private static final String SNAPSHOT_COMPLETED_KEY = "snapshot_completed";
    private static final String EVENT_SERIAL_NO_KEY = "event_serial_no";

    private final SourceInfo sourceInfo;
    private final Schema sourceInfoSchema;
    private final Map<String, String> partition;
    private boolean snapshotCompleted;

    private final TransactionContext transactionContext;

    // TODO: We should move informixTransactionCache into InformixCDCEngine?
    private final InformixTransactionCache transactionCache;

    public InformixOffsetContext(InformixConnectorConfig connectorConfig, TxLogPosition position, boolean snapshot,
                                 boolean snapshotCompleted, TransactionContext transactionContext) {
        partition = Collections.singletonMap(SERVER_PARTITION_KEY, connectorConfig.getLogicalName());
        sourceInfo = new SourceInfo(connectorConfig);

        sourceInfo.setCommitLsn(position.getCommitLsn());
        sourceInfo.setChangeLsn(position.getChangeLsn());
        sourceInfoSchema = sourceInfo.schema();

        this.snapshotCompleted = snapshotCompleted;
        if (this.snapshotCompleted) {
            postSnapshotCompletion();
        }
        else {
            sourceInfo.setSnapshot(snapshot ? SnapshotRecord.TRUE : SnapshotRecord.FALSE);
        }

        this.transactionContext = transactionContext;

        this.transactionCache = new InformixTransactionCache();
    }

    public InformixOffsetContext(InformixConnectorConfig connectorConfig, TxLogPosition position, boolean snapshot, boolean snapshotCompleted) {
        this(connectorConfig, position, snapshot, snapshotCompleted, new TransactionContext());
    }

    @Override
    public Map<String, ?> getPartition() {
        return partition;
    }

    @Override
    public Map<String, ?> getOffset() {
        if (sourceInfo.isSnapshot()) {
            return Collect.hashMapOf(
                    SourceInfo.SNAPSHOT_KEY, true,
                    SNAPSHOT_COMPLETED_KEY, snapshotCompleted,
                    SourceInfo.COMMIT_LSN_KEY, sourceInfo.getCommitLsn().toString());
        }
        else {
            return transactionContext.store(Collect.hashMapOf(
                    SourceInfo.COMMIT_LSN_KEY, sourceInfo.getCommitLsn().toString(),
                    SourceInfo.CHANGE_LSN_KEY,
                    sourceInfo.getChangeLsn() == null ? null : sourceInfo.getChangeLsn().toString()));
        }
    }

    @Override
    public Schema getSourceInfoSchema() {
        return sourceInfoSchema;
    }

    @Override
    public Struct getSourceInfo() {
        return sourceInfo.struct();
    }

    public TxLogPosition getChangePosition() {
        return TxLogPosition.valueOf(sourceInfo.getCommitLsn(), sourceInfo.getChangeLsn(), sourceInfo.getTxId(), sourceInfo.getBeginLsn());
    }

    public void setChangePosition(TxLogPosition position) {
        sourceInfo.setCommitLsn(position.getCommitLsn());
        sourceInfo.setChangeLsn(position.getChangeLsn());

        sourceInfo.setTxId(position.getTxId());
        sourceInfo.setBeginLsn(position.getBeginLsn());
    }

    @Override
    public boolean isSnapshotRunning() {
        return sourceInfo.isSnapshot() && !snapshotCompleted;
    }

    public boolean isSnapshotCompleted() {
        return snapshotCompleted;
    }

    @Override
    public void preSnapshotStart() {
        sourceInfo.setSnapshot(SnapshotRecord.TRUE);
        snapshotCompleted = false;
    }

    @Override
    public void preSnapshotCompletion() {
        // TODO: scala version comment the following assignment, why?
        snapshotCompleted = true;
    }

    @Override
    public void postSnapshotCompletion() {
        sourceInfo.setSnapshot(SnapshotRecord.FALSE);
    }

    public static class Loader implements OffsetContext.Loader {

        private final InformixConnectorConfig connectorConfig;

        public Loader(InformixConnectorConfig connectorConfig) {
            this.connectorConfig = connectorConfig;
        }

        @Override
        public Map<String, ?> getPartition() {
            return Collections.singletonMap(SERVER_PARTITION_KEY, connectorConfig.getLogicalName());
        }

        @Override
        public OffsetContext load(Map<String, ?> offset) {
            // TODO: Is this a special case for Informix?
            Map<String, String> offset_map = (Map<String, String>) offset;
            final Long changeLsn = Long.parseLong(offset_map.getOrDefault(SourceInfo.CHANGE_LSN_KEY, "-1"));
            final Long commitLsn = Long.parseLong(offset_map.getOrDefault(SourceInfo.COMMIT_LSN_KEY, "-1"));

            boolean snapshot = Boolean.TRUE.equals(offset.get(SourceInfo.SNAPSHOT_KEY));
            boolean snapshotCompleted = Boolean.TRUE.equals(offset.get(SNAPSHOT_COMPLETED_KEY));

            return new InformixOffsetContext(connectorConfig, TxLogPosition.valueOf(commitLsn, changeLsn), snapshot, snapshotCompleted,
                    TransactionContext.load(offset));
        }
    }


    @Override
    public String toString() {
        return "InformixOffsetContext [" +
                "sourceInfoSchema=" + sourceInfoSchema +
                ", sourceInfo=" + sourceInfo +
                ", partition=" + partition +
                ", snapshotCompleted=" + snapshotCompleted +
                "]";
    }

    @Override
    public void markLastSnapshotRecord() {
        sourceInfo.setSnapshot(SnapshotRecord.LAST);
    }

    @Override
    public void event(DataCollectionId tableId, Instant timestamp) {
        sourceInfo.setSourceTime(timestamp);
        sourceInfo.setTableId((TableId) tableId);
    }

    @Override
    public TransactionContext getTransactionContext() {
        return transactionContext;
    }

    InformixTransactionCache getInformixTransactionCache() {
        return transactionCache;
    }
}