# WorkManager

> `WorkManager` 是适合用于持久性工作的推荐解决方案。如果工作始终要通过应用重启和系统重新启动来调度，便是永久性的工作。

## WorkManager 初始化

### 默认初始化

默认情况下，`WorkManager` 使用内置的 `ContentProvider` 自动初始化自身。`ContentProvider` 在 `Application#onCreate` 前创建和运行，因此大多数情况下，这允许在代码运行前设置 `WorkManager` 单例。

使用默认配置初始化是通过 `androidx.startup` 实现的，通过实现 `androidx.startup.Initializer` 接口，在 `create` 方法中对 `WorkManager` 单例进行一次性初始化。

### 按需初始化

如需自定义配置对 `WorkManager` 单例进行一次性初始化。需要按照以下步骤操作：

- 在 `AndroidManifest` 中禁用 `androidx.work.WorkManagerInitializer` 
- 在 `Application#onCreate` 或 `ContentProvider` 中调用此方法。必须在这两个位置之一调用此方法。

### 初始化

这里我们针对默认初始化的场景进行分析。

```java
public WorkManager create(@NonNull Context context) {
		// Initialize WorkManager with the default configuration.
  	WorkManager.initialize(context, new Configuration.Builder().build());
  	return WorkManager.getInstance(context);
}
```

在 `WorkManagerInitializer#create` 中，调用 `WorkManager#initialize` 方法初始化，其中第二个参数 `Configuration ` 配置了用于设置 `WorkManager` 的各种参数。比如在自定义 `Workers` 使用的 `Executor`。

`WorkManager` 的具体实现在 `WorkManagerImpl` 中。

```java
private static WorkManagerImpl sDelegatedInstance = null;
private static WorkManagerImpl sDefaultInstance = null;
private static final Object sLock = new Object();

public static void initialize(@NonNull Context context, @NonNull Configuration configuration) {
        synchronized (sLock) {
            if (sDelegatedInstance != null && sDefaultInstance != null) {
                throw new IllegalStateException("WorkManager is already initialized.  Did you "
                        + "try to initialize it manually without disabling "
                        + "WorkManagerInitializer? See "
                        + "WorkManager#initialize(Context, Configuration) or the class level "
                        + "Javadoc for more information.");
            }

            if (sDelegatedInstance == null) {
                context = context.getApplicationContext();
                if (sDefaultInstance == null) {
                    sDefaultInstance = new WorkManagerImpl(
                            context,
                            configuration,
                            new WorkManagerTaskExecutor(configuration.getTaskExecutor()));
                }
                sDelegatedInstance = sDefaultInstance;
            }
        }
    }
```

在 `WorkManagerImpl#initialize` 方法中，会先判断 `sDelefatedInstance` 是否为 `null` ，仅当 `sDelegatedInstance` 为 `null` 且 `sDefaultInstace` 为 `null` 时，会初始化 `WorkManagerImpl`，此处的 `sDelefatedInstance` 是通过`setDelegate` 设置用于测试的 `WorkManagerImpl` 的委托。

在 `initialize` 方法内，调用 `Context.getApplicationContext()`，因此可以安全的传入任何 `Context` 而不会存在内存泄漏的风险。

创建 `WorkManagerImpl` 的实例，第三个参数传入 `WorkManagerTaskExecutor` ，用于运行“执行中”的任务，例如入队、调度、取消等。在 `WorkManagerTaskExecutor` 的构造方法中，传入 `Configuration#getTaskExecutor` 获取到的 `TaskExecutor` ，在默认初始化的场景中，此处返回的是固定大小的线程池（与 `AsyncTask#THREAD_POOL_EXECUTOR` 的核心池大小相同）。

该线程池复用在共享无界队列上运行的固定数量的线程，并在需要时使用提供的 TreadFactory 创建新线程。在任何时候，最多 nThreads 个线程将是活动的处理任务。如果在所有线程都处于活动状态时提交了其他任务，它们将在队列中等待，直到有线程可用。如果任何线程在关闭之前的执行过程中由于失败而终止，如果需要执行后续任务，新的线程将取代它。池中的线程将一直存在，直到显式关闭。通过控制最大线程来使使用率最大化，同时保证在任务增大时不会占用太多资源。

```java
private @NonNull Executor createDefaultExecutor(boolean isTaskExecutor) {
		return Executors.newFixedThreadPool(
				// This value is the same as the core pool size for AsyncTask#THREAD_POOL_EXECUTOR.
				Math.max(2, Math.min(Runtime.getRuntime().availableProcessors() - 1, 4)),
				createDefaultThreadFactory(isTaskExecutor));
}

```

在 `WorkManagerTaskExecutor` 中使用 `SerialExecutor` 将传入的 `Executor` 包装起来，确保任务是串行执行的，就像一个单线程，以便对正在执行的命令有顺序保证。

`WorkManagerTaskExecutor` 构造方法中的 `TaskExecutor` ，在 `WorkManagerImpl` 的构造方法创建 `WorkDatabase`（跟踪工作状态的 Room 数据库）时，用于执行所有异步 Room 查询。

```
public WorkManagerImpl(
				@NonNull Context context,
				@NonNull Configuration configuration,
				@NonNull TaskExecutor workTaskExecutor,
				boolean useTestDatabase) {
		this(context,
						configuration,
						workTaskExecutor,
						WorkDatabase.create(
										context.getApplicationContext(),
										workTaskExecutor.getBackgroundExecutor(),
										useTestDatabase)
		);
}
```

后续传入创建的`WorkDatabase`，继续执行。

```java
public WorkManagerImpl(
  			@NonNull Context context,
  			@NonNull Configuration configuration,
  			@NonNull TaskExecutor workTaskExecutor,
  			@NonNull WorkDatabase database) {
  	Context applicationContext = context.getApplicationContext();
  	Logger.setLogger(new Logger.LogcatLogger(configuration.getMinimumLoggingLevel()));
  	List<Scheduler> schedulers =
    				createSchedulers(applicationContext, configuration, workTaskExecutor);
  	Processor processor = new Processor(
    				context,
    				configuration,
    				workTaskExecutor,
    				database,
    				schedulers);
  	internalInit(context, configuration, workTaskExecutor, database, schedulers, processor);
}
```

在这个构造方法中，创建调度器，处理器和初始化。

## 工作请求

工作请求分为两种：`OneTimeWorkRequest` 和 `PeriodicWorkRequest`，分别适用于调度非重复性工作，和适用于调度以一定间隔重复执行的工作。

WorkManager 2.7.0 引入了加急工作的概念。使得 `WorkManager` 能够执行重要工作，同时使系统更好的控制对资源的访问权限。

## 管理工作

在 定义 `Worker` 和 `WorkRequest` 后，调用 `WorkManager#enqueue()` 方法，将工作加入到队列中。

```java
WorkManager.getInstance(context).enqueue(Worker)
```

最终调用到 `WorkManagerImpl#enqueue()` ，在这个方法里会创建 `WorkContinuationImpl`，然后调用它的 `enqueue` 方法。

```java
public Operation enqueue(
  	@NonNull List<? extends WorkRequest> requests) {
  	... ...
  	return new WorkContinuationImpl(this, requests).enqueue();
}
```

还可以通过 `beginWith` 以一个或多个 `OneTimeWorkRequest` 链开始，最终调用 `WorkContinuation#enqueue` 将它们一起加入到队列中。

```java
public @NonNull WorkContinuation beginWith(@NonNull List<OneTimeWorkRequest> work) {
    ... ...
    return new WorkContinuationImpl(this, work);
}
```

```java
public @NonNull Operation enqueue() {
  	// Only enqueue if not already enqueued.
  	if (!mEnqueued) {
    		// The runnable walks the hierarchy of the continuations
    		// and marks them enqueued using the markEnqueued() method, parent first.
    		EnqueueRunnable runnable = new EnqueueRunnable(this);
    		mWorkManagerImpl.getWorkTaskExecutor().executeOnBackgroundThread(runnable);
    		mOperation = runnable.getOperation();
  	} else {
    		Logger.get().warning(TAG,
                String.format("Already enqueued work ids (%s)", TextUtils.join(", ", mIds)));
  	}
 		return mOperation;
}
```

`WorkContinuationImpl#enqueue` 方法中，会创建一个 `EnqueueRunnable` 对象，通过前面我们提到的 `WorkManagerImpl.getWorkTaskExecutor()` 在后台线程池中执行这个`Runnable`。最终返回工作执行的状态。

## 工作链

`WorkContinuationImpl` 是 `WorkContinuation` 的具体实现，`WorkContinuation` 是一个允许将 `OneTimeWorkRequest` 链接在一起的类，可以创建任意的工作依赖的非循环图。

如果要创建工作链，可以使用 `WorkManager#beginWith(OneTimeWorkRequest)` 或  `WorkManager#beginWith(List<OneTimeWorkRequest>)`，这会返回 `WorkContinuation` 实例。

然后通过 `then(OneTimeWorkRequest)` 或 `then(List<OneTiimeWorkRequest>)` ，每次调用 `WorkContinuation.then(...)` 都会返回一个新的 `WorkContinuation` 实例。如果添加了 `OneTimeWorkRequest` 实例的 `List`，这些请求可能会并行运行。

```java
WorkContinuation left = workManager.beginWith(A).then(B);
WorkContinuation right = workManager.beginWith(C).then(D);
WorkContinuation final = WorkContinuation.combine(Arrays.asList(left, right)).then(E);
final.enqueue();
```

将 `WorkContinuation` 加入队列并不意味着会将所有先前未加入队列的先决条件加入队列。必须调用 `WorkContinuation.enqueue()` 来通知 `WorkManager` 实际将 `WorkContinuation` 工作链排入队列中。

在调用 `WorkContinuation#then` 会添加新的 `WorkContinuation` 。

```java
    public @NonNull WorkContinuation then(@NonNull List<OneTimeWorkRequest> work) {
        if (work.isEmpty()) {
            return this;
        } else {
            return new WorkContinuationImpl(mWorkManagerImpl,
                    mName,
                    ExistingWorkPolicy.KEEP,
                    work,
                    Collections.singletonList(this));
        }
    }
```

在 `WorkContinuationImpl` 的构造方法中，`parents` 参数在通过 `beginWith` 创建时为 `null`，在通过 `then` 方法创建时，传入当前 `WorkContinuationImpl` 的不可变列表。通过这种方式，在后续对队列进行操作时，可以有父节点出发执行按照顺序对队列操作。

```java
    public WorkContinuationImpl(@NonNull WorkManagerImpl workManagerImpl,
            @Nullable String name,
            @NonNull ExistingWorkPolicy existingWorkPolicy,
            @NonNull List<? extends WorkRequest> work,
            @Nullable List<WorkContinuationImpl> parents)
```

要使用更复杂的图，可以使用 `combine(List)` 返回一个 `WorkContinuation`。

```java
WorkContinuation left = workManager.beginWith(A).then(B);
WorkContinuation right = workManager.beginWith(C).then(D);
WorkContinuation final = WorkContinuation.combine(Arrays.asList(left, right)).then(E);
final.enqueue();
```

A --- B --- + |

​					| --- E

C --- D --- + |

调用 `combine` 方法，传入 `List<WorkContinuation>` ，然后调用 combineInternal 将 `List<WorkContinuation>` 结合起来。

```java
public static @NonNull WorkContinuation combine(@NonNull List<WorkContinuation> continuations) {
		return continuations.get(0).combineInternal(continuations);
}
```



```java
protected @NonNull WorkContinuation combineInternal(
        @NonNull List<WorkContinuation> continuations) {
    OneTimeWorkRequest combinedWork =
            new OneTimeWorkRequest.Builder(CombineContinuationsWorker.class)
                    .setInputMerger(ArrayCreatingInputMerger.class)
                    .build();

    List<WorkContinuationImpl> parents = new ArrayList<>(continuations.size());
    for (WorkContinuation continuation : continuations) {
        parents.add((WorkContinuationImpl) continuation);
    }

    return new WorkContinuationImpl(mWorkManagerImpl,
            null,
            ExistingWorkPolicy.KEEP,
            Collections.singletonList(combinedWork),
            parents);
}
```

在 `combineInternal` 方法中，首先创建一个联合工作 `combinedWork` ，帮助合并工作。然后遍历传入的 `continuations` ，将其设置为 `combinedWork` 的 `parent`。

## 执行工作

调用 WorkContinuation#enqueue 在后台线程上将 WorkContinuation 的实例排入队列。会从父级开始遍历 WorkContinuations 的层次结构，并使用 mardEnqueued() 方法将它们标记为入队。

EnqueueRunnable 是用于管理 WorkContinuationImpl 的队列。

```java
    public void run() {
        try {
            if (mWorkContinuation.hasCycles()) {
                throw new IllegalStateException(
                        String.format("WorkContinuation has cycles (%s)", mWorkContinuation));
            }
            boolean needsScheduling = addToDatabase();
            if (needsScheduling) {
                // Enable RescheduleReceiver, only when there are Worker's that need scheduling.
                final Context context =
                        mWorkContinuation.getWorkManagerImpl().getApplicationContext();
                PackageManagerHelper.setComponentEnabled(context, RescheduleReceiver.class, true);
                scheduleWorkInBackground();
            }
            mOperation.setState(Operation.SUCCESS);
        } catch (Throwable exception) {
            mOperation.setState(new Operation.State.FAILURE(exception));
        }
    }
```

### 添加到数据库

在 EnqueueRunnable#run 方法中，首先将存储工作信息的逻辑单元 WorkSpec 添加到数据库中，从父级开始。如果添加成功，在后台调度工作。

```java
    public boolean addToDatabase() {
        WorkManagerImpl workManagerImpl = mWorkContinuation.getWorkManagerImpl();
        WorkDatabase workDatabase = workManagerImpl.getWorkDatabase();
        workDatabase.beginTransaction();
        try {
            boolean needsScheduling = processContinuation(mWorkContinuation);
            workDatabase.setTransactionSuccessful();
            return needsScheduling;
        } finally {
            workDatabase.endTransaction();
        }
    }
```

在 processContinuation 中，保证了从父级开始依次添加，processContinutation 方法内部执行先判断 WorkContinuation 的 parent是否为 null，当不为 null 时，遍历 parent 列表。如果 parent 列表中的节点没有被标记已入队的状态，递归入队 parent 节点直到父节点。当节点的

```java
    private static boolean processContinuation(@NonNull WorkContinuationImpl workContinuation) {
        boolean needsScheduling = false;
        List<WorkContinuationImpl> parents = workContinuation.getParents();
        if (parents != null) {
            for (WorkContinuationImpl parent : parents) {
                // When chaining off a completed continuation we need to pay
                // attention to parents that may have been marked as enqueued before.
                if (!parent.isEnqueued()) {
                    needsScheduling |= processContinuation(parent);
                } else {
                    Logger.get().warning(TAG, String.format("Already enqueued work ids (%s).",
                            TextUtils.join(", ", parent.getIds())));
                }
            }
        }
        needsScheduling |= enqueueContinuation(workContinuation);
        return needsScheduling;
    }
```

调用 enqueueContinuation 方法将工作排入队列。先获取给定 WorkContinuationImpl 的先决条件集，也就是给定 WorkCOntinuationImpl 的 parents，跟踪先决条件的同时将工作排入队列，在排入队列后，将其标记为已入队。

```java
    private static boolean enqueueContinuation(@NonNull WorkContinuationImpl workContinuation) {
        Set<String> prerequisiteIds = WorkContinuationImpl.prerequisitesFor(workContinuation);

        boolean needsScheduling = enqueueWorkWithPrerequisites(
                workContinuation.getWorkManagerImpl(),
                workContinuation.getWork(),
                prerequisiteIds.toArray(new String[0]),
                workContinuation.getName(),
                workContinuation.getExistingWorkPolicy());

        workContinuation.markEnqueued();
        return needsScheduling;
    }
```

enqueueWorkWithPrerequisites() 中有几个标志，hasPrerequisite标记是否存在先决条件集，hasCompletedAllPrerequisites 标记是否已完成所有的先决条件集，hasFailedPrerequisites 标记是否有失败的先决条件，hasCancelledPrerequisites 标记是否有已取消的先决条件集。流程上分为几个部分，首先是根据先决条件集的存在，如果有先决条件，要保证它们在排队之前存在，通过WorkDatabase 查询先决条件集中的 WorkSpec，如果查询到的先决条件工作为 null，则表示先决条件集不存在，不对该次排队操作继续执行，返回 false。如果查询到的先决条件状态为 SUCCEEDED，则hasCompletedAllPrerequisites 位与 true；如果状态为 FAILED，则hasFailedPrerequisites 为 true；如果状态为 CANCELLED，则 hasCancelledPrerequisites 为 true。

```java
    private static boolean enqueueWorkWithPrerequisites(
            WorkManagerImpl workManagerImpl,
            @NonNull List<? extends WorkRequest> workList,
            String[] prerequisiteIds,
            String name,
            ExistingWorkPolicy existingWorkPolicy) {
      	... ...
        boolean hasPrerequisite = (prerequisiteIds != null && prerequisiteIds.length > 0);
        boolean hasCompletedAllPrerequisites = true;
        boolean hasFailedPrerequisites = false;
        boolean hasCancelledPrerequisites = false;
      	... ...
        if (hasPrerequistie) {
          	for (String id : prerequisiteIds) {
                WorkSpec prerequisiteWorkSpec = workDatabase.workSpecDao().getWorkSpec(id);
                if (prerequisiteWorkSpec == null) {
                    Logger.get().error(TAG,
                            String.format("Prerequisite %s doesn't exist; not enqueuing", id));
                    return false;
                }

                WorkInfo.State prerequisiteState = prerequisiteWorkSpec.state;
                hasCompletedAllPrerequisites &= (prerequisiteState == SUCCEEDED);
                if (prerequisiteState == FAILED) {
                    hasFailedPrerequisites = true;
                } else if (prerequisiteState == CANCELLED) {
                    hasCancelledPrerequisites = true;
                }
            }
        }
      	... ...
    }
```

对于将工作请求排入唯一命名的 WorkContinuation 队列，只对作为链开始的唯一标签序列应用现有的工作策略。首先使用 unique tag 获取已存在的 WorkSpec ID和 State。

如果冲突解决策略是 APPEND 或者 APPEND_OR_REPLACE，确定 WorkSpec 是否有任何依赖项，当不存在依赖的 WorkSpec 时，将该 WorkSpec.IdAndState 的 id 添加到创建的 newPrerequisitedIds，几个状态标志根据其 state 设置。如果冲突解决政策是 APPEND_OR_REPLACE，当存在取消或失败的 WorkSpec.IdAndState，删除所有这个 name 的WorkSpec，并将其视为一条新链。

如果冲突解决策略是 REPLACE 或者 KEEP时，当为 KEEP时，仅在其处于入队或运行时才去保留它。否则，取消所有的工作，并且不在 C ancelWorkRunnable 中重新安排，并且在数据库中删除其记录。因为取消了一些工作，但不允许在 CancelWorkRunnable中重新调度，所以需要保证在 EnqueueRunnable 结束时安排这些工作。

```java
        boolean shouldApplyExistingWorkPolicy = isNamed && !hasPrerequisite;
        if (shouldApplyExistingWorkPolicy) {
            // Get everything with the unique tag.
            List<WorkSpec.IdAndState> existingWorkSpecIdAndStates =
                    workDatabase.workSpecDao().getWorkSpecIdAndStatesForName(name);

            if (!existingWorkSpecIdAndStates.isEmpty()) {
                // If appending, these are the new prerequisites.
                if (existingWorkPolicy == APPEND || existingWorkPolicy == APPEND_OR_REPLACE) {
                    DependencyDao dependencyDao = workDatabase.dependencyDao();
                    List<String> newPrerequisiteIds = new ArrayList<>();
                    for (WorkSpec.IdAndState idAndState : existingWorkSpecIdAndStates) {
                        if (!dependencyDao.hasDependents(idAndState.id)) {
                            hasCompletedAllPrerequisites &= (idAndState.state == SUCCEEDED);
                            if (idAndState.state == FAILED) {
                                hasFailedPrerequisites = true;
                            } else if (idAndState.state == CANCELLED) {
                                hasCancelledPrerequisites = true;
                            }
                            newPrerequisiteIds.add(idAndState.id);
                        }
                    }
                    if (existingWorkPolicy == APPEND_OR_REPLACE) {
                        if (hasCancelledPrerequisites || hasFailedPrerequisites) {
                            // Delete all WorkSpecs with this name
                            WorkSpecDao workSpecDao = workDatabase.workSpecDao();
                            List<WorkSpec.IdAndState> idAndStates =
                                    workSpecDao.getWorkSpecIdAndStatesForName(name);
                            for (WorkSpec.IdAndState idAndState : idAndStates) {
                                workSpecDao.delete(idAndState.id);
                            }
                            // Treat this as a new chain of work.
                            newPrerequisiteIds = Collections.emptyList();
                            hasCancelledPrerequisites = false;
                            hasFailedPrerequisites = false;
                        }
                    }
                    prerequisiteIds = newPrerequisiteIds.toArray(prerequisiteIds);
                    hasPrerequisite = (prerequisiteIds.length > 0);
                } else {
                    // If we're keeping existing work, make sure to do so only if something is
                    // enqueued or running.
                    if (existingWorkPolicy == KEEP) {
                        for (WorkSpec.IdAndState idAndState : existingWorkSpecIdAndStates) {
                            if (idAndState.state == ENQUEUED || idAndState.state == RUNNING) {
                                return false;
                            }
                        }
                    }

                    // Cancel all of these workers.
                    // Don't allow rescheduling in CancelWorkRunnable because it will happen inside
                    // the current transaction.  We want it to happen separately to avoid race
                    // conditions (see ag/4502245, which tries to avoid work trying to run before
                    // it's actually been committed to the database).
                    CancelWorkRunnable.forName(name, workManagerImpl, false).run();
                    // Because we cancelled some work but didn't allow rescheduling inside
                    // CancelWorkRunnable, we need to make sure we do schedule work at the end of
                    // this runnable.
                    needsScheduling = true;

                    // And delete all the database records.
                    WorkSpecDao workSpecDao = workDatabase.workSpecDao();
                    for (WorkSpec.IdAndState idAndState : existingWorkSpecIdAndStates) {
                        workSpecDao.delete(idAndState.id);
                    }
                }
            }
        }
```

接下来，就是将工作请求插入到数据库中，首先获取到当前工作相关联的 WorkSpec，当有先决条件集，并且没有完成所有的先决条件时，如果失败的标记为ture，则WorkSpec的State为FAILED，如果取消的标记为true，则 WorkSpec 的State 为 CANCELLED，否则为 BLOCKED。

如果没有先决条件集或先决条件都已完成，只设置了 OneTimeWorkRequest 的 periodStartTime。对于 PeriodicWorkRequests，第一个间隔持续时间立即生效，WorkerWrapper 特殊情况是 PeriodicWorkRequest 的第一次运行。这是必不可少的，因为我们在给定的时间间隔内对同一 PeriodicWorkRequest 的多次运行进行了重复数据删除。 JobScheduler 存在导致 PeriodicWorkRequests 运行过于频繁的错误。

如果 API大于22 或者API小于 22允许WorkManager使用 GCM 调度任务，委托受约束的 WorkSpec。如果有处于排队状态的 WorkSpec，那么需要调度，设置 needsScheduling 为 true。

将 WorkSpec 插入到数据库中，如果有先决条件集，将 WorkRequest 的ID和 先决条件 ID构建 Dependency 插入到Dependency表中。如果WorkRequest 存在 Tag，插入到 WorkTag表中。如果WorkRequest 存在 Name，插入到 WorkName表中。

```java
        for (WorkRequest work : workList) {
            WorkSpec workSpec = work.getWorkSpec();

            if (hasPrerequisite && !hasCompletedAllPrerequisites) {
                if (hasFailedPrerequisites) {
                    workSpec.state = FAILED;
                } else if (hasCancelledPrerequisites) {
                    workSpec.state = CANCELLED;
                } else {
                    workSpec.state = BLOCKED;
                }
            } else {
                if (!workSpec.isPeriodic()) {
                    workSpec.periodStartTime = currentTimeMillis;
                } else {
                    workSpec.periodStartTime = 0L;
                }
            }

            if (Build.VERSION.SDK_INT >= WorkManagerImpl.MIN_JOB_SCHEDULER_API_LEVEL
                    && Build.VERSION.SDK_INT <= 25) {
                tryDelegateConstrainedWorkSpec(workSpec);
            } else if (Build.VERSION.SDK_INT <= WorkManagerImpl.MAX_PRE_JOB_SCHEDULER_API_LEVEL
                    && usesScheduler(workManagerImpl, Schedulers.GCM_SCHEDULER)) {
                tryDelegateConstrainedWorkSpec(workSpec);
            }

            // If we have one WorkSpec with an enqueued state, then we need to schedule.
            if (workSpec.state == ENQUEUED) {
                needsScheduling = true;
            }

            workDatabase.workSpecDao().insertWorkSpec(workSpec);

            if (hasPrerequisite) {
                for (String prerequisiteId : prerequisiteIds) {
                    Dependency dep = new Dependency(work.getStringId(), prerequisiteId);
                    workDatabase.dependencyDao().insertDependency(dep);
                }
            }

            for (String tag : work.getTags()) {
                workDatabase.workTagDao().insert(new WorkTag(tag, work.getStringId()));
            }

            if (isNamed) {
                workDatabase.workNameDao().insert(new WorkName(name, work.getStringId()));
            }
        }
```

### 工作调度

根据添加到数据库中的返回值，判断是否需要进行调度。如果不需要调度，则跳过调度部分，操作的 State 设置为 SUCCESS。

当工作需要调度时，启用 RescheduleReceiver，在后台线程调度该工作。

```java
public void scheduleWorkInBackground() {
        WorkManagerImpl workManager = mWorkContinuation.getWorkManagerImpl();
        Schedulers.schedule(
                workManager.getConfiguration(),
                workManager.getWorkDatabase(),
                workManager.getSchedulers());
    }
```

通过使用 Schedulers#schedule 调度，必须要遵守 Scheduler.MAX_SCHEDULER_LIMIT的同时安排 WorkSpecs。

Scheduler.MAX_SCHEDULER_LIMIT 值为50，表示在给定时间点可以安排的最大 WorkSpec 数。

在 schedule 中，首先获取有调度资格的 WorkSpecs 列表和没有调度资格限制的 WorkSpecs列表，其中有调度资格的列表，默认的限制数为 Configuration#MIN_SCHEDULER_LIMIT，值为20，表示在使用 JobScheduler 或 AlarmManager时，WorkManager 可以排队的最小系统请求数。另外一个列表的数量限制为MAX_GREEDY_SCHEDULER_LIMIT，值为 200，表示贪婪调度线程考虑执行的最大 WorkSpec 数量。

对于有限制的 WorkSpecs 列表，遍历标记所有的 start_time 为当前时间，对 Scheduler#schedule 的调用可能会导致单独线程上的更多调度，所以这一步需要最先完成。

```java
    public static void schedule(
            @NonNull Configuration configuration,
            @NonNull WorkDatabase workDatabase,
            List<Scheduler> schedulers) {
        if (schedulers == null || schedulers.size() == 0) {
            return;
        }

        WorkSpecDao workSpecDao = workDatabase.workSpecDao();
        List<WorkSpec> eligibleWorkSpecsForLimitedSlots;
        List<WorkSpec> allEligibleWorkSpecs;

        workDatabase.beginTransaction();
        try {
            // Enqueued workSpecs when scheduling limits are applicable.
            eligibleWorkSpecsForLimitedSlots = workSpecDao.getEligibleWorkForScheduling(
                    configuration.getMaxSchedulerLimit());

            // Enqueued workSpecs when scheduling limits are NOT applicable.
            allEligibleWorkSpecs = workSpecDao.getAllEligibleWorkSpecsForScheduling(
                    MAX_GREEDY_SCHEDULER_LIMIT);

            if (eligibleWorkSpecsForLimitedSlots != null
                    && eligibleWorkSpecsForLimitedSlots.size() > 0) {
                long now = System.currentTimeMillis();

                // Mark all the WorkSpecs as scheduled.
                // Calls to Scheduler#schedule() could potentially result in more schedules
                // on a separate thread. Therefore, this needs to be done first.
                for (WorkSpec workSpec : eligibleWorkSpecsForLimitedSlots) {
                    workSpecDao.markWorkSpecScheduled(workSpec.id, now);
                }
            }
            workDatabase.setTransactionSuccessful();
        } finally {
            workDatabase.endTransaction();
        }

        if (eligibleWorkSpecsForLimitedSlots != null
                && eligibleWorkSpecsForLimitedSlots.size() > 0) {

            WorkSpec[] eligibleWorkSpecsArray =
                    new WorkSpec[eligibleWorkSpecsForLimitedSlots.size()];
            eligibleWorkSpecsArray =
                    eligibleWorkSpecsForLimitedSlots.toArray(eligibleWorkSpecsArray);

            // Delegate to the underlying schedulers.
            for (Scheduler scheduler : schedulers) {
                if (scheduler.hasLimitedSchedulingSlots()) {
                    scheduler.schedule(eligibleWorkSpecsArray);
                }
            }
        }

        if (allEligibleWorkSpecs != null && allEligibleWorkSpecs.size() > 0) {
            WorkSpec[] enqueuedWorkSpecsArray = new WorkSpec[allEligibleWorkSpecs.size()];
            enqueuedWorkSpecsArray = allEligibleWorkSpecs.toArray(enqueuedWorkSpecsArray);
            // Delegate to the underlying schedulers.
            for (Scheduler scheduler : schedulers) {
                if (!scheduler.hasLimitedSchedulingSlots()) {
                    scheduler.schedule(enqueuedWorkSpecsArray);
                }
            }
        }
    }
```

然后对 eligibleWorkSpecsForLimitedSlots 和 allEligibleWorkSpecs，如果不为 null 或者 不为空，使用在 WorkManager 初始化时创建的调度器列表执行调度。

```java
List<Scheduler> schedulers =
    				createSchedulers(applicationContext, configuration, workTaskExecutor);
```

```java
    public List<Scheduler> createSchedulers(
            @NonNull Context context,
            @NonNull Configuration configuration,
            @NonNull TaskExecutor taskExecutor) {

        return Arrays.asList(
                Schedulers.createBestAvailableBackgroundScheduler(context, this),
                // Specify the task executor directly here as this happens before internalInit.
                // GreedyScheduler creates ConstraintTrackers and controllers eagerly.
                new GreedyScheduler(context, configuration, taskExecutor, this));
    }
```

```java
    static Scheduler createBestAvailableBackgroundScheduler(
            @NonNull Context context,
            @NonNull WorkManagerImpl workManager) {

        Scheduler scheduler;

        if (Build.VERSION.SDK_INT >= WorkManagerImpl.MIN_JOB_SCHEDULER_API_LEVEL) {
            scheduler = new SystemJobScheduler(context, workManager);
            setComponentEnabled(context, SystemJobService.class, true);
            Logger.get().debug(TAG, "Created SystemJobScheduler and enabled SystemJobService");
        } else {
            scheduler = tryCreateGcmBasedScheduler(context);
            if (scheduler == null) {
                scheduler = new SystemAlarmScheduler(context);
                setComponentEnabled(context, SystemAlarmService.class, true);
                Logger.get().debug(TAG, "Created SystemAlarmScheduler");
            }
        }
        return scheduler;
    }
```

可以看到这里创建的调度列表中，第一个调度是根据API版本创建的更适合的调度器，在大于23时，使用SystemJobScheduler，否则先判断是否可以使用GCM调度器，不能时，使用SystemAlarmScheduler；第二个调度器是 WorkManager 中创建的贪婪调度器，它调度不受约束、非定时的工作。

SystemJobScheduler 是使用 JobScheduler 调度工作的，SystemAlarmScheduler 是使用 AlarmManager 调度工作的，包括 GreedyScheduler，这三个调度器都是实现 Scheduler的。

首先，我们先分析，GreedyScheduler 是怎么调度工作的。

#### GreedyScheduler

> 一个贪婪的调度器，它调度不受约束的、非定时的工作。它故意不获取任何 WakeLock，而是在进程被杀死之前尝试暴力破解它们。

在 GreedyScheduler 的构造方法中，会创建 WorkConstraintsTracker 和 DelayedWorkTracker，其中 WorkConstraintsTracker 顾名思义是用于跟踪 WorkSpec 及其约束的，会在满足或未满足所有约束时通知可选的 WorkConstraintsCallback，DelayedWorkTracker 是跟踪在 GreedyScheduler 中设置初始延迟的 WorkRequest。

现在我们分析在 GreedyScheduler#schedule。

首先会获取到WorkManager创建的 Processor，向其添加一个 ExecutionListener 以跟踪工作何时完成。

```java
    private void registerExecutionListenerIfNeeded() {
        // This method needs to be called *after* Processor is created, since Processor needs
        // Schedulers and is created after this class.
        if (!mRegisteredExecutionListener) {
            mWorkManagerImpl.getProcessor().addExecutionListener(this);
            mRegisteredExecutionListener = true;
        }
    }
```

跟踪需要跟踪其约束的新 WorkSpecs 列表，将它们添加到已知的受限 WorkSpec 列表中，并在 WorkConstriantsTracker 上调用 replace()。这样，就只需要在更新 mConstrainedWorkSpecs 的部分进行同步了。

```java
    public void schedule(@NonNull WorkSpec... workSpecs) {
      	... ...
        Set<WorkSpec> constrainedWorkSpecs = new HashSet<>();
        Set<String> constrainedWorkSpecIds = new HashSet<>();

        for (WorkSpec workSpec : workSpecs) {
            long nextRunTime = workSpec.calculateNextRunTime();
            long now = System.currentTimeMillis();
            if (workSpec.state == WorkInfo.State.ENQUEUED) {
                if (now < nextRunTime) {
                    // Future work
                    if (mDelayedWorkTracker != null) {
                        mDelayedWorkTracker.schedule(workSpec);
                    }
                } else if (workSpec.hasConstraints()) {
                    if (SDK_INT >= 23 && workSpec.constraints.requiresDeviceIdle()) {
                        // Ignore requests that have an idle mode constraint.
                        Logger.get().debug(TAG,
                                String.format("Ignoring WorkSpec %s, Requires device idle.",
                                        workSpec));
                    } else if (SDK_INT >= 24 && workSpec.constraints.hasContentUriTriggers()) {
                        // Ignore requests that have content uri triggers.
                        Logger.get().debug(TAG,
                                String.format("Ignoring WorkSpec %s, Requires ContentUri triggers.",
                                        workSpec));
                    } else {
                        constrainedWorkSpecs.add(workSpec);
                        constrainedWorkSpecIds.add(workSpec.id);
                    }
                } else {
                    Logger.get().debug(TAG, String.format("Starting work for %s", workSpec.id));
                    mWorkManagerImpl.startWork(workSpec.id);
                }
            }
        }

        // onExecuted() which is called on the main thread also modifies the list of mConstrained
        // WorkSpecs. Therefore we need to lock here.
        synchronized (mLock) {
            if (!constrainedWorkSpecs.isEmpty()) {
                Logger.get().debug(TAG, String.format("Starting tracking for [%s]",
                        TextUtils.join(",", constrainedWorkSpecIds)));
                mConstrainedWorkSpecs.addAll(constrainedWorkSpecs);
                mWorkConstraintsTracker.replace(mConstrainedWorkSpecs);
            }
        }
    }
```

遍历WorkSpecs，先获取到下一次执行的时间。如果回退政策设置为BackoffPolicy.EXPONENTIAL，则延迟相对于运行尝试计数以指数速率增加，并以 WorkRequest.MAX_BACKOFF_MILLIS 为上限。如果为 BackoffPolicy.LINEAR，则延迟相对于运行尝试计数以线性速率增加，并以 WorkRequest.MAX_BACKOFF_MILLIS 为上限。MAX_BACKOFF_MILLIS 为 5小时。如果是周期性的，我们还需要考虑柔性时间，所以 PeriodicWorkRequest 的初始延迟为 initialDelay + (interval - flex)。

```java
    public long calculateNextRunTime() {
        if (isBackedOff()) {
            boolean isLinearBackoff = (backoffPolicy == BackoffPolicy.LINEAR);
            long delay = isLinearBackoff ? (backoffDelayDuration * runAttemptCount)
                    : (long) Math.scalb(backoffDelayDuration, runAttemptCount - 1);
            return periodStartTime + Math.min(WorkRequest.MAX_BACKOFF_MILLIS, delay);
        } else if (isPeriodic()) {
            long now = System.currentTimeMillis();
            long start = periodStartTime == 0 ? (now + initialDelay) : periodStartTime;
            boolean isFlexApplicable = flexDuration != intervalDuration;
            if (isFlexApplicable) {
                long offset = periodStartTime == 0 ? (-1 * flexDuration) : 0;
                return start + intervalDuration + offset;
            } else {
                long offset = periodStartTime == 0 ? 0 : intervalDuration;
                return start + offset;
            }
        } else {
            long start = (periodStartTime == 0) ? System.currentTimeMillis() : periodStartTime;
            return start + initialDelay;
        }
    }
```

当 WorkSpec 的 state 是 ENQUEUED 的状态时，如果当前时间小于 下次运行时间，则表示需要延迟调度，则使用 DelayedWorkTracker#schedule 调度WorkSpec；如果 WorkSpec 有约束时，判断约束条件，当条件不忽略时，将WorkSpec 添加到 constraintedWorkSpecs 中；其他情况直接执行工作，通过 WorkManagerImpl#startWork。

```java
    public void startWork(
            @NonNull String workSpecId,
            @Nullable WorkerParameters.RuntimeExtras runtimeExtras) {
        mWorkTaskExecutor
                .executeOnBackgroundThread(
                        new StartWorkRunnable(this, workSpecId, runtimeExtras));
    }

```

有约束条件的WorkSpec，调用 WorkConstraintsTracker#replace，在 WorkConstraintsTracker 中会构建一个约束控制器。

```java
        mConstraintControllers = new ConstraintController[] {
                new BatteryChargingController(appContext, taskExecutor),
                new BatteryNotLowController(appContext, taskExecutor),
                new StorageNotLowController(appContext, taskExecutor),
                new NetworkConnectedController(appContext, taskExecutor),
                new NetworkUnmeteredController(appContext, taskExecutor),
                new NetworkNotRoamingController(appContext, taskExecutor),
                new NetworkMeteredController(appContext, taskExecutor)
        };
```

在约束条件变化时，会调用updateCallback，直到约束条件满足时，会调用 WorkConstraintsCallback#onAllConstraintMet，然后调用WorkManagerImpl#startWork。

##### 工作执行

创建 StartWorkRunnable，在后台线程执行。在 StartWorkRunnable 中，会获取 WorkManager#Processor去执行工作，Processor在 WorkManager初始化时创建，可以根据需要智能地安排和执行工作。

Processor#startWork，在后台启动给定的工作，其内部会创建 WorkerWapper，用于从数据库中查找给定 id 的WorkSpec，实例化其 Worker，然后调用它的 runnable。

```java
public boolean startWork(
            @NonNull String id,
            @Nullable WorkerParameters.RuntimeExtras runtimeExtras) {
  
        WorkerWrapper workWrapper;
        synchronized (mLock) {
            // Work may get triggered multiple times if they have passing constraints
            // and new work with those constraints are added.
            if (isEnqueued(id)) {
                Logger.get().debug(
                        TAG,
                        String.format("Work %s is already enqueued for processing", id));
                return false;
            }

            workWrapper =
                    new WorkerWrapper.Builder(
                            mAppContext,
                            mConfiguration,
                            mWorkTaskExecutor,
                            this,
                            mWorkDatabase,
                            id)
                            .withSchedulers(mSchedulers)
                            .withRuntimeExtras(runtimeExtras)
                            .build();
            ListenableFuture<Boolean> future = workWrapper.getFuture();
            future.addListener(
                    new FutureListener(this, id, future),
                    mWorkTaskExecutor.getMainThreadExecutor());
            mEnqueuedWorkMap.put(id, workWrapper);
        }
        mWorkTaskExecutor.getBackgroundExecutor().execute(workWrapper);
        Logger.get().debug(TAG, String.format("%s: processing %s", getClass().getSimpleName(), id));
        return true;
    }
```

工作的执行逻辑就是在 WorkerWapper的 run 方法中。首先检查中断，然后判断如果 WorkSpec 为null 或其状态不是 ENQUEUED，则结束执行过程。

确保退出的 Worker 仅在应该执行的时候执行。 GreedyScheduler 可以调度已经退出的 WorkSpecs，因为它正在保留 WorkSpecs 的快照。所以 WorkerWrapper 需要在这个时间点判断 ListenableWorker 是否真的有资格执行。

在 API 23 上，我们将 scheduler Workers 加倍，因为 JobScheduler 更喜欢批处理。工作也是周期性的，我们只需要每个间隔执行一次。此外，平台中的潜在错误可能会导致作业多次运行。

如果 WorkSpec 是周期性的或者是重试的，允许 PeriodicWorkRequest 的第一次运行，因为当 periodStartTime = 0，计算下次运行时间总是大于当前时刻的。对于AlarmManager 的实现，我们需要重新安排工作，这对 JobScheduler 来说不是问题，因为只会在 JobScheduler 不知道 job Id 的情况下重新安排工作。

然后获取 input data，将获取到的 input data 在构建 WorkerParameters 时传入。在构建 WorkerParameters 时，创建 WorkProresssUpdater 和 WorkForegroundUpdater，分别在 WorkDatabase 中保持进度和将Worker转换为在前台的 Service中运行。

通过 Configuration#getWorkFactory()#createWorkerWithDefaultFallback() 创建 Worker，当其已被标注使用时，标记失败并返回。如果其没有被标注使用，标注为使用。

然后尝试将工作设置为运行状态。请注意，这可能会失败，因为自从我们上次在此函数顶部检查以来，另一个线程可能已经修改了数据库。

创建 WorkForegroundRunnable，并在主线程运行它。在主线程调用 mWorker.startWork()，最终调用到Worker#doWork方法。

```java
    private void runWorker() {
        if (tryCheckForInterruptionAndResolve()) {
            return;
        }

        mWorkDatabase.beginTransaction();
        try {
            mWorkSpec = mWorkSpecDao.getWorkSpec(mWorkSpecId);
            if (mWorkSpec == null) {
                resolve(false);
                mWorkDatabase.setTransactionSuccessful();
                return;
            }
          
            if (mWorkSpec.state != ENQUEUED) {
                resolveIncorrectStatus();
                mWorkDatabase.setTransactionSuccessful();
                return;
            }

            if (mWorkSpec.isPeriodic() || mWorkSpec.isBackedOff()) {
                long now = System.currentTimeMillis();
                boolean isFirstRun = mWorkSpec.periodStartTime == 0;
                if (!isFirstRun && now < mWorkSpec.calculateNextRunTime()) {
                    resolve(true);
                    mWorkDatabase.setTransactionSuccessful();
                    return;
                }
            }
            mWorkDatabase.setTransactionSuccessful();
        } finally {
            mWorkDatabase.endTransaction();
        }

        Data input;
        if (mWorkSpec.isPeriodic()) {
            input = mWorkSpec.input;
        } else {
            InputMergerFactory inputMergerFactory = mConfiguration.getInputMergerFactory();
            String inputMergerClassName = mWorkSpec.inputMergerClassName;
            InputMerger inputMerger =
                    inputMergerFactory.createInputMergerWithDefaultFallback(inputMergerClassName);
            if (inputMerger == null) {
                setFailedAndResolve();
                return;
            }
            List<Data> inputs = new ArrayList<>();
            inputs.add(mWorkSpec.input);
            inputs.addAll(mWorkSpecDao.getInputsFromPrerequisites(mWorkSpecId));
            input = inputMerger.merge(inputs);
        }

        final WorkerParameters params = new WorkerParameters(
                UUID.fromString(mWorkSpecId),
                input,
                mTags,
                mRuntimeExtras,
                mWorkSpec.runAttemptCount,
                mConfiguration.getExecutor(),
                mWorkTaskExecutor,
                mConfiguration.getWorkerFactory(),
                new WorkProgressUpdater(mWorkDatabase, mWorkTaskExecutor),
                new WorkForegroundUpdater(mWorkDatabase, mForegroundProcessor, mWorkTaskExecutor));

        // Not always creating a worker here, as the WorkerWrapper.Builder can set a worker override
        // in test mode.
        if (mWorker == null) {
            mWorker = mConfiguration.getWorkerFactory().createWorkerWithDefaultFallback(
                    mAppContext,
                    mWorkSpec.workerClassName,
                    params);
        }

        if (mWorker == null) {
          	... ...
            return;
        }

        if (mWorker.isUsed()) {
          	... ...
            setFailedAndResolve();
            return;
        }
        mWorker.setUsed();

        // Try to set the work to the running state.  Note that this may fail because another thread
        // may have modified the DB since we checked last at the top of this function.
        if (trySetRunning()) {
            if (tryCheckForInterruptionAndResolve()) {
                return;
            }

            final SettableFuture<ListenableWorker.Result> future = SettableFuture.create();
            final WorkForegroundRunnable foregroundRunnable =
                    new WorkForegroundRunnable(
                            mAppContext,
                            mWorkSpec,
                            mWorker,
                            params.getForegroundUpdater(),
                            mWorkTaskExecutor
                    );
            mWorkTaskExecutor.getMainThreadExecutor().execute(foregroundRunnable);

            final ListenableFuture<Void> runExpedited = foregroundRunnable.getFuture();
            runExpedited.addListener(new Runnable() {
                @Override
                public void run() {
                    try {
                        runExpedited.get();
                        // Call mWorker.startWork() on the main thread.
                        mInnerFuture = mWorker.startWork();
                        future.setFuture(mInnerFuture);
                    } catch (Throwable e) {
                        future.setException(e);
                    }
                }
            }, mWorkTaskExecutor.getMainThreadExecutor());

            // Avoid synthetic accessors.
            final String workDescription = mWorkDescription;
            future.addListener(new Runnable() {
                @Override
                @SuppressLint("SyntheticAccessor")
                public void run() {
                    try {
                        // If the ListenableWorker returns a null result treat it as a failure.
                        ListenableWorker.Result result = future.get();
                        if (result == null) {
                          	... ... 
                        } else {
                            mResult = result;
                        }
                    } catch (CancellationException exception) {
                      	... ...
                    } finally {
                        onWorkFinished();
                    }
                }
            }, mWorkTaskExecutor.getBackgroundExecutor());
        } else {
            resolveIncorrectStatus();
        }
    }
```

在 Work执行结束后，会调用 onWorkFinished，去处理接触BLOCKED的Worker，以及需要重新安排的Worker（例如使用 AlarmManager 的定期工作）。逻辑在 runWorker 之后运行，因为它应该发生在自己的事务中。在其他调度器中取消这项工作。例如，如果这项工作是由 GreedyScheduler 处理的，应该确保 JobScheduler 被告知它应该删除这个工作并且 AlarmManager 应该删除所有相关的 alarms。

##### 工作约束

电量 / 存储约束是通过带有 BroadcastReceiver 的 ConstraintTracker 来监视约束更改。

网络状态的监控，在API 24 及更好版本是通过注册 ConnectivityManager.NetworkCallback 和 ConnectivityManager.registerDefaultNetworkCallback 跟踪网络状态，对于 API 23 及以下版本，是用过使用 BroadcastReceiver 跟踪网络状态的。

Trackers 保存了每一个 ConstraintTracker 的实例，ConstraintTracker 是用于跟踪约束和通知监听更改的基础，分别有 BatteryChargingTracker、BatteryNotLowTracker、NetworkStateTracker、StorageNotLowTracker，每个都实现了 ConstraintTracker 的 getInitialState 做初始化，startTracking 开始跟踪，stopTracking 结束跟踪。

在监听到变化时，通过 ConstraintTracker#setState 方法更新约束的状态。如果状态没有改变，则什么也不会发生。如果设置了新的状态，调用 onConstraintChanged 接口更新状态。因为 onConstraingChanged 可能会导致调用 addListener 或 removeListener，这可能导致在迭代时对集合进行修改，因此通过创建副本并将其用于迭代来处理此问题。

onConstraintChanged 接口会将状态更新到 ConstraintController#updateCallback 方法，根据其是否满足所有约束去调用 onConstraintNotMet 或 onConstraintMet 方法，然后在 onConstraintMet中调用 onAllConstraintsMet，最终调用 startWork 方法执行工作。

在 ConstraintController#onConstraintChanged 中将引用（回调、currentValue）的副本传递给 updateCallback，因为 ConstraintController 上的公共 API 可以从任何线程调用，并且 onConstraintChanged() 是从主线程调用的。

```java
    public void setState(T newState) {
        synchronized (mLock) {
            if (mCurrentState == newState
                    || (mCurrentState != null && mCurrentState.equals(newState))) {
                return;
            }
            mCurrentState = newState;

            // onConstraintChanged may lead to calls to addListener or removeListener.
            // This can potentially result in a modification to the set while it is being
            // iterated over, so we handle this by creating a copy and using that for
            // iteration.
            final List<ConstraintListener<T>> listenersList = new ArrayList<>(mListeners);
            mTaskExecutor.getMainThreadExecutor().execute(new Runnable() {
                @Override
                public void run() {
                    for (ConstraintListener<T> listener : listenersList) {
                        listener.onConstraintChanged(mCurrentState);
                    }
                }
            });
        }
    }
```

#### SystemJobScheduler

> 使用 JobScheduler 调度工作，在 API 大于 23时选用的调度器，完成周期性的工作。

在 SystemJobScheduler 的构造方法中，创建了 SystemJobInfoConverter 用于将 WorkSpec 转化为 JobInfo。

SystemJobScheduler#schedule 中主要是使用 JobScheduler 调度工作，如果 API 为23时，JobScheduler 仅在队列中至少有两个作业时才开始作业，即使满足作业约束也是如此。所以为了匹配这一行为，在 API 23上双重安排工作，并根据需要在 SystemJobService 中对它们进行重复数据删除。

主要的调度逻辑是在 scheduleInternal 方法中。首先将 WorkSpec 转化为 JobInfo，所有 JobInfo 都设置为在重新启动时保持不变。然后使用 JobScheduler#schedule 调度JobInfo。

```java
    public void scheduleInternal(WorkSpec workSpec, int jobId) {
        JobInfo jobInfo = mSystemJobInfoConverter.convert(workSpec, jobId);
        Logger.get().debug(
                TAG,
                String.format("Scheduling work ID %s Job ID %s", workSpec.id, jobId));
        try {
            int result = mJobScheduler.schedule(jobInfo);
            if (result == JobScheduler.RESULT_FAILURE) {
                Logger.get()
                        .warning(TAG, String.format("Unable to schedule work ID %s", workSpec.id));
                if (workSpec.expedited
                        && workSpec.outOfQuotaPolicy == RUN_AS_NON_EXPEDITED_WORK_REQUEST) {
                    // Falling back to a non-expedited job.
                    workSpec.expedited = false;
                    String message = String.format(
                            "Scheduling a non-expedited job (work ID %s)", workSpec.id);
                    Logger.get().debug(TAG, message);
                    scheduleInternal(workSpec, jobId);
                }
            }
        } catch (IllegalStateException e) {
            // This only gets thrown if we exceed 100 jobs.  Let's figure out if WorkManager is
            // responsible for all these jobs.
            List<JobInfo> jobs = getPendingJobs(mContext, mJobScheduler);
            int numWorkManagerJobs = jobs != null ? jobs.size() : 0;

            String message = String.format(Locale.getDefault(),
                    "JobScheduler 100 job limit exceeded.  We count %d WorkManager "
                            + "jobs in JobScheduler; we have %d tracked jobs in our DB; "
                            + "our Configuration limit is %d.",
                    numWorkManagerJobs,
                    mWorkManager.getWorkDatabase().workSpecDao().getScheduledWork().size(),
                    mWorkManager.getConfiguration().getMaxSchedulerLimit());

            Logger.get().error(TAG, message);

            // Rethrow a more verbose exception.
            throw new IllegalStateException(message, e);
        } catch (Throwable throwable) {
            // OEM implementation bugs in JobScheduler cause the app to crash. Avoid crashing.
            Logger.get().error(TAG, String.format("Unable to schedule %s", workSpec), throwable);
        }
    }
```

在 SystemJobService#onStartJob 方法中对 startWork调用。onStartJob 返回 true，并挂起这个 onStartJob 请求。对 startWork 的调用可能无操作，因为 WorkRequest 可能已被 GreedySchduler 拾取并已经被执行。GreedyScheduler 不处理重试，并且处理器通知所有调度器有关重新调度的意图，在这种情况下，我们依靠 SystemJobService 通过调用 onExecuted() 中的 jobFinished 来请求重新安排。

#### SystemAlarmScheduler

> 使用 AlarmManager 调度工作的调度器

周期性工作通过每次运行后使用一次性 Alarm 重新安排实现。这允许时间漂移，以保证 Alarm 之间的间隔持续时间总是过去。

启动由 AlarmManager 调用以运行工作任务的服务 SystemAlarmService，在 onCreate 时初始化调度器 SystemAlarmDispatcher，基于 AlarmManager 的后台处理器实现。

## 工作状态

> 工作在其整个生命周期内经历了一系列 State 更改
#### 一次性工作的状态
对于 one-time(OneTimeWorkRequest) 工作请求，工作的初始状态为`ENQUEUED`

在 `ENQUEUED` 状态下，工作会在满足其 `Constraints` 和初始延迟计时要求后立即运行。
接下来，工作会转为 `RUNNING` 状态，然后会根据工作的结果转为 `SUCCEEDED`、`FAILED` 状态；
或者，如果结果为 `retry`，可能会回到 `ENQUEUED` 状态。
在此过程中，随时都可以取消工作，取消后工作将进入 `CANCELLED` 状态。

<img src="screenshots/one-time-work-flow.png"/>

`SUCCEEDED`、`FAILED`、`CANCELLED` 均表示此工作的终止状态。如果工作处于任一状态，`WorkInfo.State.isFinished()`都将返回`true`

#### 定期工作的状态

成功和失败状态仅适用于一次性工作和链式工作。定期工作只有一个终止状态`CANCELLED`。这是因为定期工作永远不会结束。每次运行后，无论结果如何，系统都会重新对其进行调度。

<img src="screenshots/periodic-work-states.png"/>

#### 链接工作的状态

> 可以使用 `WorkManager` 创建工作链并将其加入队列。工作链用于指定多个依存任务并定义这些任务的执行顺序。当需要以特定顺序运行多个任务时，此功能尤其有用。

只要工作成功完成（即，返回 `Result.success()`），`OneTimeWorkRequest` 链便会顺序执行。运行时，工作请求可能会失败或被取消，这会对依存工作请求产生下游影响。

当第一个 `OneTimeWorkRequest` 被加入工作请求链队列时，所有后续工作请求会被屏蔽，直到第一个工作请求的工作完成为止。

<img src="screenshots/chaining-enqueued-all-blocked.png"/>

在加入队列且满足所有工作约束后，第一个工作请求开始运行。如果工作在根 `OneTimeWorkRequest` 或 `List<OneTimeWorkRequest>` 中成功完成（即返回 `Result.success()`），系统会将下一组依存工作请求加入队列。

<img src="screenshots/chaining-enqueued-in-progress.png"/>

只要每个工作请求都成功完成，工作请求链中的剩余工作请求就会遵循相同的运行模式，直到链中的所有工作都完成为止。这是最简单的用例，通常也是首选用例，但处理错误状态同样重要。

如果在工作器处理工作请求时出现错误，可以根据定义的退避政策来重试请求。重试请求链中的某个请求意味着，系统将使用提供给该请求的输入数据仅对该请求进行重试。并行运行的所有其他作业均不会受到影响。

<img src="screenshots/chaining-enqueued-retry.png"/>

如果该重试政策未定义或已用尽，或者您以其他方式已达到 `OneTimeWorkRequest` 返回 `Result.failure()` 的某种状态，该工作请求和所有依存工作请求都会被标记为 `FAILED.`

<img src="screenshots/chaining-enqueued-failed.png"/>

`OneTimeWorkRequest` 被取消时遵循相同的逻辑。任何依存工作请求也会被标记为 `CANCELLED`，并且无法执行其工作。

<img src="screenshots/chaining-enqueued-cancelled.png"/>

请注意，如果要向已失败或已取消工作请求的链附加更多工作请求，新附加的工作请求也会分别标记为 `FAILED` 或 `CANCELLED`。

创建工作请求链时，依存工作请求应定义重试政策，以确保始终及时完成工作。失败的工作请求可能会导致链不完整和/或出现意外状态。



