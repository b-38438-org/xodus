/**
 * Copyright 2010 - 2019 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package jetbrains.exodus.env;

import jetbrains.exodus.ExodusException;
import jetbrains.exodus.core.dataStructures.decorators.HashMapDecorator;
import jetbrains.exodus.core.dataStructures.hash.IntHashMap;
import jetbrains.exodus.tree.ITree;
import jetbrains.exodus.tree.TreeMetaInfo;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;

/**
 * Base class for transactions.
 */
public abstract class TransactionBase implements Transaction {

    @NotNull
    private final EnvironmentImpl env;
    @NotNull
    private final Thread creatingThread;
    private MetaTreeImpl metaTree;
    @NotNull
    private final IntHashMap<ITree> immutableTrees;
    @NotNull
    private final Map<Object, Object> userObjects;
    @Nullable
    private final Throwable trace;
    private final long created; // created is the ticks when the txn was actually created (constructed)
    private long started;       // started is the ticks when the txn held its current snapshot
    private boolean isExclusive;
    private final boolean wasCreatedExclusive;
    private boolean isFinished;
    private int acquiredPermits;

    public TransactionBase(@NotNull final EnvironmentImpl env, final boolean isExclusive) {
        this.env = env;
        this.creatingThread = Thread.currentThread();
        this.isExclusive = isExclusive;
        wasCreatedExclusive = isExclusive;
        immutableTrees = new IntHashMap<>();
        userObjects = new HashMapDecorator<>();
        trace = env.transactionTimeout() > 0 ? new Throwable() : null;
        created = System.currentTimeMillis();
        started = created;
        isFinished = false;
    }

    @Override
    public Transaction getSnapshot() {
        return getSnapshot(null);
    }

    @Override
    public Transaction getSnapshot(@Nullable final Runnable beginHook) {
        checkIsFinished();
        return new ReadWriteTransaction(this, beginHook);
    }

    @Override
    public Transaction getReadonlySnapshot() {
        checkIsFinished();
        return new ReadonlyTransaction(this);
    }

    @Override
    @NotNull
    public EnvironmentImpl getEnvironment() {
        return env;
    }

    @Override
    public long getStartTime() {
        return started;
    }

    @Override
    public long getHighAddress() {
        return metaTree.logTip.highAddress;
    }

    @Override
    public boolean isExclusive() {
        return isExclusive;
    }

    @Override
    public boolean isFinished() {
        return isFinished;
    }

    @Override
    @Nullable
    public Object getUserObject(@NotNull final Object key) {
        return userObjects.get(key);
    }

    @Override
    public void setUserObject(@NotNull final Object key, @NotNull final Object value) {
        userObjects.put(key, value);
    }

    @NotNull
    public ITree getTree(@NotNull final StoreImpl store) {
        checkIsFinished();
        final int structureId = store.getStructureId();
        ITree result = immutableTrees.get(structureId);
        if (result == null) {
            result = store.openImmutableTree(getMetaTree());
            synchronized (immutableTrees) {
                immutableTrees.put(structureId, result);
            }
        }
        return result;
    }

    public void checkIsFinished() {
        if (isFinished) {
            throw new ExodusException("Transaction is already finished");
        }
    }

    @NotNull
    Thread getCreatingThread() {
        return creatingThread;
    }

    @NotNull
    MetaTreeImpl getMetaTree() {
        return metaTree;
    }

    void setMetaTree(@NotNull final MetaTreeImpl metaTree) {
        checkIsFinished();
        this.metaTree = metaTree;
    }

    long getRoot() {
        return getMetaTree().root;
    }

    boolean checkVersion(final long root) {
        return metaTree.root == root;
    }

    @Nullable
    Throwable getTrace() {
        return trace;
    }

    long getCreated() {
        return created;
    }

    void setStarted(final long started) {
        this.started = started;
    }

    boolean wasCreatedExclusive() {
        return wasCreatedExclusive;
    }

    boolean isGCTransaction() {
        return false;
    }

    @Nullable
    TreeMetaInfo getTreeMetaInfo(@NotNull final String name) {
        checkIsFinished();
        return metaTree.getMetaInfo(name, env);
    }

    void storeRemoved(@NotNull final StoreImpl store) {
        checkIsFinished();
        synchronized (immutableTrees) {
            immutableTrees.remove(store.getStructureId());
        }
    }

    @NotNull
    List<String> getAllStoreNames() {
        checkIsFinished();
        return getMetaTree().getAllStoreNames();
    }

    int getAcquiredPermits() {
        return acquiredPermits;
    }

    void setAcquiredPermits(final int acquiredPermits) {
        this.acquiredPermits = acquiredPermits;
    }

    @Nullable
    abstract Runnable getBeginHook();

    protected void clearImmutableTrees() {
        synchronized (immutableTrees) {
            immutableTrees.clear();
        }
    }

    protected void setExclusive(final boolean isExclusive) {
        this.isExclusive = isExclusive;
    }

    protected void setIsFinished() {
        isFinished = true;
    }

    protected Runnable getWrappedBeginHook(@Nullable final Runnable beginHook) {
        return new Runnable() {
            @Override
            public void run() {
                final EnvironmentImpl env = getEnvironment();
                setMetaTree(env.getMetaTreeInternal());
                env.registerTransaction(TransactionBase.this);
                if (beginHook != null) {
                    beginHook.run();
                }

            }
        };
    }
}
