package org.babyfish.jimmer.sql.ast.impl.mutation.save;

import org.babyfish.jimmer.meta.ImmutableProp;
import org.babyfish.jimmer.meta.ImmutableType;
import org.babyfish.jimmer.meta.LogicalDeletedInfo;
import org.babyfish.jimmer.sql.association.meta.AssociationType;
import org.babyfish.jimmer.sql.ast.impl.AstContext;
import org.babyfish.jimmer.sql.ast.impl.render.AbstractSqlBuilder;
import org.babyfish.jimmer.sql.ast.impl.render.BatchSqlBuilder;
import org.babyfish.jimmer.sql.ast.impl.render.ComparisonPredicates;
import org.babyfish.jimmer.sql.ast.impl.value.ValueGetter;
import org.babyfish.jimmer.sql.ast.tuple.Tuple2;
import org.babyfish.jimmer.sql.ast.tuple.Tuple3;
import org.babyfish.jimmer.sql.dialect.Dialect;
import org.babyfish.jimmer.sql.meta.*;
import org.babyfish.jimmer.sql.runtime.*;

import java.sql.Connection;
import java.sql.ResultSet;
import java.util.*;

class MiddleTableOperator extends AbstractOperator {

    private final MutationPath path;
    
    private final MutationTrigger trigger;

    private final MiddleTable middleTable;

    private final List<ValueGetter> sourceGetters;

    private final List<ValueGetter> targetGetters;

    private final List<ValueGetter> getters;

    @SuppressWarnings("unchecked")
    MiddleTableOperator(SaveContext ctx) {
        this(
                ctx.options.getSqlClient(), 
                ctx.con,
                ctx.path,
                ctx.trigger
        );
    }

    MiddleTableOperator(DeleteContext ctx) {
        this(
                ctx.options.getSqlClient(),
                ctx.con,
                ctx.path,
                ctx.trigger
        );
    }
    
    private MiddleTableOperator(
            JSqlClientImplementor sqlClient,
            Connection con,
            MutationPath path,
            MutationTrigger trigger
    ) {
        super(sqlClient, con);
        ImmutableProp associationProp = path.getProp();
        boolean inverse = false;
        if (associationProp != null) {
            if (associationProp.getMappedBy() != null) {
                associationProp = associationProp.getMappedBy();
                inverse = true;
            }
        } else {
            associationProp = path.getBackProp();
            if (associationProp.getMappedBy() != null) {
                associationProp = associationProp.getMappedBy();
            } else {
                inverse = true;
            }
        }
        MetadataStrategy strategy = sqlClient.getMetadataStrategy();
        this.path = path;
        this.trigger = trigger;
        if (inverse) {
            this.middleTable = associationProp
                    .<MiddleTable>getStorage(strategy)
                    .getInverse();
            AssociationType associationType = AssociationType.of(associationProp);
            this.sourceGetters = ValueGetter.valueGetters(sqlClient, associationType.getTargetProp());
            this.targetGetters = ValueGetter.valueGetters(sqlClient, associationType.getSourceProp());
        } else {
            this.middleTable = associationProp.getStorage(strategy);
            AssociationType associationType = AssociationType.of(associationProp);
            this.sourceGetters = ValueGetter.valueGetters(sqlClient, associationType.getSourceProp());
            this.targetGetters = ValueGetter.valueGetters(sqlClient, associationType.getTargetProp());
        }
        this.getters = ValueGetter.tupleGetters(sourceGetters, targetGetters);
    }

    public int append(IdPairs idPairs) {
        int rowCount = connect(idPairs);
        MutationTrigger trigger = this.trigger;
        if (trigger != null) {
            for (Tuple2<Object, Object> idTuple : idPairs.tuples()) {
                fireInsert(idTuple.get_1(), idTuple.get_2());
            }
        }
        return rowCount;
    }

    public int merge(IdPairs idPairs) {
        if (isUpsertUsed()) {
            int[] rowCounts = connectIfNecessary(idPairs);
            int sumRowCount = 0;
            int index = 0;
            MutationTrigger trigger = this.trigger;
            for (Tuple2<Object, Object> idTuple : idPairs.tuples()) {
                if (rowCounts[index++] != 0) {
                    sumRowCount++;
                    if (trigger != null) {
                        fireInsert(idTuple.get_1(), idTuple.get_2());
                    }
                }
            }
            return sumRowCount;
        }
        Set<Object> sourceIds = new LinkedHashSet<>();
        for (Tuple2<Object, Object> idTuple : idPairs.tuples()) {
            sourceIds.add(idTuple.get_1());
        }
        Set<Tuple2<Object, Object>> existingIdTuples = find(sourceIds);
        List<Tuple2<Object, Object>> insertingIdTuples =
                new ArrayList<>(idPairs.tuples().size() - existingIdTuples.size());
        for (Tuple2<Object, Object> idTuple : idPairs.tuples()) {
            if (!existingIdTuples.contains(idTuple)) {
                insertingIdTuples.add(idTuple);
            }
        }
        return append(IdPairs.of(insertingIdTuples));
    }

    public int replace(IdPairs idPairs) {
        MutationTrigger trigger = this.trigger;
        if (trigger == null && isUpsertUsed()) {
            int sumRowCount = disconnectExcept(idPairs);
            int[] rowCounts = connectIfNecessary(idPairs);
            for (int rowCount : rowCounts) {
                if (rowCount != 0) {
                    sumRowCount += rowCount;
                }
            }
            return sumRowCount;
        }
        Collection<Tuple2<Object, Object>> idTuples = idPairs.tuples();
        if (!(idTuples instanceof Set<?>)) {
            idTuples = new LinkedHashSet<>(idTuples);
        }
        Set<Object> sourceIds = new LinkedHashSet<>();
        for (Tuple2<Object, Object> idTuple : idTuples) {
            sourceIds.add(idTuple.get_1());
        }
        Set<Tuple2<Object, Object>> existingIdTuples = find(sourceIds);
        List<Tuple2<Object, Object>> insertingIdTuples =
                new ArrayList<>(idTuples.size() - existingIdTuples.size());
        List<Tuple2<Object, Object>> deletingIdTuples =
                new ArrayList<>();
        for (Tuple2<Object, Object> idTuple : idTuples) {
            if (!existingIdTuples.contains(idTuple)) {
                insertingIdTuples.add(idTuple);
            }
        }
        for (Tuple2<Object, Object> existingIdTuple : existingIdTuples) {
            if (!idTuples.contains(existingIdTuple)) {
                deletingIdTuples.add(existingIdTuple);
            }
        }
        int rowCount = disconnect(IdPairs.of(deletingIdTuples)) +
                connect(IdPairs.of(insertingIdTuples));
        if (trigger != null) {
            for (Tuple2<Object, Object> idTuple : insertingIdTuples) {
                fireInsert(idTuple.get_1(), idTuple.get_2());
            }
            for (Tuple2<Object, Object> idTuple : deletingIdTuples) {
                fireDelete(idTuple.get_1(), idTuple.get_2());
            }
        }
        return rowCount;
    }

    @SuppressWarnings("unchecked")
    Set<Tuple2<Object, Object>> find(Collection<Object> ids) {
        SqlBuilder builder = new SqlBuilder(new AstContext(sqlClient));
        builder.enter(AbstractSqlBuilder.ScopeType.SELECT);
        for (ValueGetter getter : getters) {
            builder.separator().sql(getter);
        }
        builder.leave();
        builder
                .sql(" from ").sql(middleTable.getTableName())
                .enter(SqlBuilder.ScopeType.WHERE);
        ComparisonPredicates.renderIn(
                false,
                sourceGetters,
                ids,
                builder
        );
        addLogicalDeletedPredicate(builder);
        addFilterPredicate(builder);
        builder.leave();
        Tuple3<String, List<Object>, List<Integer>> tuple = builder.build();
        Reader<Object> sourceIdReader;
        Reader<Object> targetIdReader;
        if (path.getProp() != null) {
            sourceIdReader = (Reader<Object>) sqlClient.getReader(path.getProp().getDeclaringType().getIdProp());
            targetIdReader = (Reader<Object>) sqlClient.getReader(path.getProp().getTargetType().getIdProp());
        } else {
            sourceIdReader = (Reader<Object>) sqlClient.getReader(path.getBackProp().getTargetType().getIdProp());
            targetIdReader = (Reader<Object>) sqlClient.getReader(path.getBackProp().getDeclaringType().getIdProp());
        }
        return sqlClient.getExecutor().execute(
                new Executor.Args<>(
                        sqlClient,
                        con,
                        tuple.get_1(),
                        tuple.get_2(),
                        tuple.get_3(),
                        ExecutionPurpose.QUERY,
                        null,
                        stmt -> {
                            Reader.Context ctx = new Reader.Context(null, sqlClient);
                            Set<Tuple2<Object, Object>> idTuples = new LinkedHashSet<>();
                            try (ResultSet rs = stmt.executeQuery()) {
                                while (rs.next()) {
                                    ctx.resetCol();
                                    Object sourceId = sourceIdReader.read(rs, ctx);
                                    Object targetId = targetIdReader.read(rs, ctx);
                                    idTuples.add(new Tuple2<>(sourceId, targetId));
                                }
                            }
                            return idTuples;
                        }
                )
        );
    }

    int connect(IdPairs idPairs) {
        BatchSqlBuilder builder = new BatchSqlBuilder(sqlClient);
        builder.sql("insert into ").sql(middleTable.getTableName()).enter(BatchSqlBuilder.ScopeType.TUPLE);
        appendColumns(builder);
        builder.leave();
        builder.sql(" values").enter(BatchSqlBuilder.ScopeType.TUPLE);
        appendValues(builder);
        builder.leave();
        return execute(builder, idPairs.tuples());
    }

    int[] connectIfNecessary(IdPairs idPairs) {
        BatchSqlBuilder builder = new BatchSqlBuilder(sqlClient);
        sqlClient.getDialect().upsert(new UpsertContextImpl(builder));
        return executeImpl(builder, idPairs.tuples());
    }

    int disconnect(IdPairs idPairs) {
        BatchSqlBuilder builder = new BatchSqlBuilder(sqlClient);
        builder.sql("delete from ").sql(middleTable.getTableName()).enter(BatchSqlBuilder.ScopeType.WHERE);
        for (ValueGetter getter : getters) {
            builder.separator()
                    .sql(getter)
                    .sql(" = ")
                    .variable(getter);
        }
        builder.leave();
        return execute(builder, idPairs.tuples());
    }

    int disconnectExcept(IdPairs idPairs) {
        if (idPairs.entries().size() < 2) {
            Tuple2<Object, Collection<Object>> idTuple = idPairs.entries().iterator().next();
            return disconnectExceptBySimpleInPredicate(idTuple.get_1(), idTuple.get_2());
        }
        if (targetGetters.size() == 1 && sqlClient.getDialect().isAnyEqualityOfArraySupported()) {
            return disconnectExceptByBatch(idPairs);
        }
        return disconnectExceptByComplexInPredicate(idPairs);
    }

    @SuppressWarnings("unchecked")
    private int disconnectExceptByBatch(IdPairs idPairs) {
        BatchSqlBuilder builder = new BatchSqlBuilder(sqlClient);
        builder.sql("delete from ").sql(middleTable.getTableName());
        builder.enter(AbstractSqlBuilder.ScopeType.WHERE);
        ExclusiveIdPairPredicates.addPredicates(
                builder,
                sourceGetters,
                targetGetters
        );
        addLogicalDeletedPredicate(builder);
        addFilterPredicate(builder);
        builder.leave();
        return execute(builder, idPairs.entries());
    }

    private int disconnectExceptBySimpleInPredicate(Object sourceId, Collection<Object> targetIds) {
        AstContext astContext = new AstContext(sqlClient);
        SqlBuilder builder = new SqlBuilder(astContext);
        builder.sql("delete from ").sql(middleTable.getTableName());
        builder.enter(SqlBuilder.ScopeType.WHERE);
        ExclusiveIdPairPredicates.addPredicates(
                builder,
                sourceGetters,
                targetGetters,
                sourceId,
                targetIds
        );
        addLogicalDeletedPredicate(builder);
        addFilterPredicate(builder);
        builder.leave();
        return execute(builder);
    }

    private int disconnectExceptByComplexInPredicate(IdPairs idPairs) {
        SqlBuilder builder = new SqlBuilder(new AstContext(sqlClient));
        builder.sql("delete from ").sql(middleTable.getTableName());
        builder.enter(SqlBuilder.ScopeType.WHERE);
        ExclusiveIdPairPredicates.addPredicates(
                builder,
                sourceGetters,
                targetGetters,
                idPairs
        );
        addLogicalDeletedPredicate(builder);
        addFilterPredicate(builder);
        builder.leave();
        return execute(builder);
    }

    private void addLogicalDeletedPredicate(AbstractSqlBuilder<?> builder) {
        LogicalDeletedInfo logicalDeletedInfo = middleTable.getLogicalDeletedInfo();
        if (logicalDeletedInfo == null) {
            return;
        }
        builder.separator();
        LogicalDeletedInfo.Action action = logicalDeletedInfo.getAction();
        if (action instanceof LogicalDeletedInfo.Action.Eq) {
            LogicalDeletedInfo.Action.Eq eq = (LogicalDeletedInfo.Action.Eq) action;
            builder.sql(logicalDeletedInfo.getColumnName()).sql(" = ").rawVariable(eq.getValue());
        } else if (action instanceof LogicalDeletedInfo.Action.Ne) {
            LogicalDeletedInfo.Action.Ne ne = (LogicalDeletedInfo.Action.Ne) action;
            builder.sql(logicalDeletedInfo.getColumnName()).sql(" <> ").rawVariable(ne.getValue());
        } else if (action instanceof LogicalDeletedInfo.Action.IsNull) {
            builder.sql(logicalDeletedInfo.getColumnName()).sql(" is null");
        } else if (action instanceof LogicalDeletedInfo.Action.IsNotNull) {
            builder.sql(logicalDeletedInfo.getColumnName()).sql(" is not null");
        }
    }

    private void addFilterPredicate(AbstractSqlBuilder<?> builder) {
        JoinTableFilterInfo filterInfo = middleTable.getFilterInfo();
        if (filterInfo == null) {
            return;
        }
        builder.separator().sql(filterInfo.getColumnName());
        if (filterInfo.getValues().size() == 1) {
            builder.sql(" = ").rawVariable(filterInfo.getValues().get(0));
        } else {
            builder.sql(" in ").enter(SqlBuilder.ScopeType.LIST);
            for (Object value : filterInfo.getValues()) {
                builder.separator().rawVariable(value);
            }
            builder.leave();
        }
    }

    private void appendColumns(BatchSqlBuilder builder) {
        for (ValueGetter getter : getters) {
            builder.separator().sql(getter);
        }
        if (middleTable.getLogicalDeletedInfo() != null) {
            builder.separator().sql(middleTable.getLogicalDeletedInfo().getColumnName());
        }
        if (middleTable.getFilterInfo() != null) {
            builder.separator().sql(middleTable.getFilterInfo().getColumnName());
        }
    }

    @SuppressWarnings("unchecked")
    private void appendValues(BatchSqlBuilder builder) {
        for (ValueGetter getter : getters) {
            builder.separator().variable(getter);
        }
        if (middleTable.getLogicalDeletedInfo() != null) {
            builder.separator().rawVariable(middleTable.getLogicalDeletedInfo().allocateInitializedValue());
        }
        if (middleTable.getFilterInfo() != null) {
            builder.separator().rawVariable(middleTable.getFilterInfo().getValues().get(0));
        }
    }

    private boolean isUpsertUsed() {
        Dialect dialect = sqlClient.getDialect();
        return dialect.isUpsertSupported() && !dialect.isAffectCountOfInsertIgnoreWrong();
    }

    private class UpsertContextImpl implements Dialect.UpsertContext {

        private final BatchSqlBuilder builder;

        UpsertContextImpl(BatchSqlBuilder builder) {
            this.builder = builder;
        }

        @Override
        public boolean hasUpdatedColumns() {
            return false;
        }

        @Override
        public boolean hasOptimisticLock() {
            return false;
        }

        @Override
        public Dialect.UpsertContext sql(String sql) {
            builder.sql(sql);
            return this;
        }

        @Override
        public Dialect.UpsertContext appendTableName() {
            builder.sql(middleTable.getTableName());
            return this;
        }

        @Override
        public Dialect.UpsertContext appendInsertedColumns() {
            builder.enter(BatchSqlBuilder.ScopeType.COMMA);
            appendColumns(builder);
            builder.leave();
            return this;
        }

        @Override
        public Dialect.UpsertContext appendConflictColumns() {
            builder.enter(BatchSqlBuilder.ScopeType.COMMA);
            appendColumns(builder);
            builder.leave();
            return this;
        }

        @Override
        public Dialect.UpsertContext appendInsertingValues() {
            builder.enter(BatchSqlBuilder.ScopeType.COMMA);
            appendValues(builder);
            builder.leave();
            return this;
        }

        @Override
        public Dialect.UpsertContext appendUpdatingAssignments(String prefix, String suffix) {
            return this;
        }

        @Override
        public Dialect.UpsertContext appendOptimisticLockCondition() {
            return this;
        }
    }
    
    private void fireInsert(Object sourceId, Object targetId) {
        ImmutableProp prop = path.getProp();
        if (prop != null) {
            trigger.insertMiddleTable(prop, sourceId, targetId);
        } else {
            trigger.insertMiddleTable(path.getBackProp(), targetId, sourceId);
        }
    }

    private void fireDelete(Object sourceId, Object targetId) {
        ImmutableProp prop = path.getProp();
        if (prop != null) {
            trigger.deleteMiddleTable(prop, sourceId, targetId);
        } else {
            trigger.deleteMiddleTable(path.getBackProp(), targetId, sourceId);
        }
    }
}
