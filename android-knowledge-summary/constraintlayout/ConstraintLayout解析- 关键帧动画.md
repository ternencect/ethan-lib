## ConstraintLayout解析- 关键帧动画

在 `ConstraintLayout` 中，可以使用 ConstraintSet 和 TransitionManager 为尺寸和位置元素的变化增加动画效果。

在 `ConstraintLayout` 中，`ConstraintSet` 是一个轻量型对象，表示 `ConstraintLayout` 内所有子元素的约束条件、尺寸、边距等约束属性。当将 `ConstraintSet` 应用于显示的 `ConstraintLayout` 时，布局会更新其所有子级的约束条件。

### 创建 `ConstraintSet`

ConstraintSet 可以通过多种方式创建：

- 手动创建

  ```kotlin
  val constraintSet = ConstraintSet()
  constraintSet.connect()
  ```

- 通过 R.layout

  ```kotlin
  val constraintSet = ConstraintSet()
  constraintSet.clone(context, R.layout.layout)
  ```

- 从 ConstraintLayout 中获取

  ```kotlin
  constraintSet.clone(constraintLayout)
  ```

### 设置 `ConstraintSet`

`Constraint` 位于 `ConstraintSet` 内部，用于描述当前的约束行为，可以把它当作是一个简化的 ConstraintLayout，可以在其内部创建约束，但更好的做法是通过 `Layout`、`Motion`、`Transform` 等标签来对约束进行分类，从而更好的理清约束和动画的关系。

- Layout：布局相关的状态，如布局约束和尺寸
- Transform：Transform相关的状态，如rotation、translationX等
- PropertySet：View的属性，如visibility
- Motion：和MotionLayout相关的属性，如pathMotionArc、TransitionEasing等
- CustomAttribute：自定义的CustomAttribute

### 应用 `ConstraintSet`

`ConstraintSet` 的属性可分为传统属性（Custom Attributes） 和约束属性，约束属性是只有在约束布局中使用的属性，其他的非约束属性就是传统属性。

将 `ConstraintSet` 应用到 `ConstraintLayout` 中，可以根据应用属性分为以下几种方式：

- 应用 `ConstraintSet` 所有属性：`applyTo(ConstraintLayout constraintLayout)`，将所有的属性都应用到 `ConstraintLayout` 中；
- 应用传统属性：`applyCustomAttributes` 只将传统属性应用到约束布局中；
- 应用约束布局属性：`applyToLayoutParams` 为某个组建应用约束布局属性；
- 应用约束属性：`applyToWithoutCustom` 将约束属性（非传统属性）应用到约束布局中。

### `ConstraintSet` 关键帧动画

使用 `TransitionManager#beginDelayedTransition()` 方法生成并执行动画，使用设置的转换方式，创建基于一个场景 `ViewGroup` 进行生成的动画，初始场景是初始帧，转换后的新场景是目的帧。`TransitionManager` 会自动生成中间的多个过渡帧，其中的初始帧和目的帧是关键帧，过渡帧是根据两个关键帧之间的改变自动生成的。

**`ConstraintSet` 关键帧动画流程：**

1. 设置起始帧：准备 `ViewGroup` 组件，作为关键帧动画的起始帧，只要获取到该组件即可；
2. 设置关键帧动画：调用 `TransitionManager.beginDelayedTransition()` 方法，生成过渡帧，执行时会自动进行关键帧动画执行；
3. 设置目的帧：设置 `ViewGroup` 组件的变化结果，任何组件的尺寸位置的变化，都会以动画形式过渡转换过去。

### TransitionManager 解析

 `TransitionManager.beginDelayedTransition()` 方法是在调用此方法和下一个渲染帧之间给定场景根中的所有更改定义的新场景动画的便捷方法。调用此方法会导致 TransitionManager 捕获场景根中的当前值，然后发布请求以在下一帧运行过渡动画，届时，场景根目录中的新值将被捕获，更改将被动画化。整个过程无需创建场景。在下一帧之前多次调用此方法（例如，如果不相关的代码也想进行动态更改并在同一场景根上运行过渡），则只有第一次调用会触发捕获值并退出当前场景。在同一帧期间对具有相同场景根的方法的后续调用将被忽略。

```java
    public static void beginDelayedTransition(@NonNull final ViewGroup sceneRoot,
            @Nullable Transition transition) {
        if (!sPendingTransitions.contains(sceneRoot) && ViewCompat.isLaidOut(sceneRoot)) {
            if (Transition.DBG) {
                Log.d(LOG_TAG, "beginDelayedTransition: root, transition = "
                        + sceneRoot + ", " + transition);
            }
            sPendingTransitions.add(sceneRoot);
            if (transition == null) {
                transition = sDefaultTransition;
            }
            final Transition transitionClone = transition.clone();
            sceneChangeSetup(sceneRoot, transitionClone);
            Scene.setCurrentScene(sceneRoot, null);
            sceneChangeRunTransition(sceneRoot, transitionClone);
        }
    }
```

整个过程主要分为两步：

#### 保存视图信息

TransitionManager#sceneChangeSetup() 

```java
    private static void sceneChangeSetup(ViewGroup sceneRoot, Transition transition) {
        // Capture current values
        ArrayList<Transition> runningTransitions = getRunningTransitions().get(sceneRoot);

        if (runningTransitions != null && runningTransitions.size() > 0) {
            for (Transition runningTransition : runningTransitions) {
                runningTransition.pause(sceneRoot);
            }
        }

        if (transition != null) {
            transition.captureValues(sceneRoot, true);
        }

        // Notify previous scene that it is being exited
        Scene previousScene = Scene.getCurrentScene(sceneRoot);
        if (previousScene != null) {
            previousScene.exit();
        }
    }
```

获取当前正在运行的 Transition，如果有正在运行的 Transition 先暂停，当传入的 Transition 不为空时，捕获 sceneRoot 下 children 的相关值，参数 true表示这个捕获动作是发生在场景转换前的；

Transition#captureValues()

```java
    void captureValues(ViewGroup sceneRoot, boolean start) {
        // start 为 true/false，清空 mStartValues/mEndValues
        clearValues(start);
        // mTargetIds 是调用 Transition.addTargetIds()/removeTargetIds() 后才有值的
        if ((mTargetIds.size() > 0 || mTargets.size() > 0)
                && (mTargetNames == null || mTargetNames.isEmpty())
                && (mTargetTypes == null || mTargetTypes.isEmpty())) {
            for (int i = 0; i < mTargetIds.size(); ++i) {
                int id = mTargetIds.get(i);
                View view = sceneRoot.findViewById(id);
                if (view != null) {
                    TransitionValues values = new TransitionValues();
                    values.view = view;
                    if (start) {
                        captureStartValues(values);
                    } else {
                        captureEndValues(values);
                    }
                    values.targetedTransitions.add(this);
                    capturePropagationValues(values);
                    if (start) {
                        addViewValues(mStartValues, view, values);
                    } else {
                        addViewValues(mEndValues, view, values);
                    }
                }
            }
            for (int i = 0; i < mTargets.size(); ++i) {
                View view = mTargets.get(i);
                TransitionValues values = new TransitionValues();
                values.view = view;
                if (start) {
                    captureStartValues(values);
                } else {
                    captureEndValues(values);
                }
                values.targetedTransitions.add(this);
                capturePropagationValues(values);
                if (start) {
                    addViewValues(mStartValues, view, values);
                } else {
                    addViewValues(mEndValues, view, values);
                }
            }
        } else {
            // 捕获sceneRoot，以及children的信息并保存到 mStartValues/mEndValues
            captureHierarchy(sceneRoot, start);
        }

        // mNameOverrides 是针对 Fragment shared elements transitions
        if (!start && mNameOverrides != null) {
            int numOverrides = mNameOverrides.size();
            ArrayList<View> overriddenViews = new ArrayList<View>(numOverrides);
            for (int i = 0; i < numOverrides; i++) {
                String fromName = mNameOverrides.keyAt(i);
                overriddenViews.add(mStartValues.nameValues.remove(fromName));
            }
            for (int i = 0; i < numOverrides; i++) {
                View view = overriddenViews.get(i);
                if (view != null) {
                    String toName = mNameOverrides.valueAt(i);
                    mStartValues.nameValues.put(toName, view);
                }
            }
        }
    }  
```

Transition#captureHierarchy() 用来保存 sceneRoot 的信息，以及遍历 sceneRoot 的 children，并且递归调用 captureHierarchy() 来保存所有 children 的信息。

```java
    private void captureHierarchy(View view, boolean start) {
        if (view == null) {
            return;
        }
        int id = view.getId();

        // mTargetIdExcludes 是在调用 Transition.excludeTarget(int targetId, boolean exclude) 后才有值
        if (mTargetIdExcludes != null && mTargetIdExcludes.contains(id)) {
            return;
        }
        // mTargetExcludes 是在调用 Transition.excludeTarget(View target, boolean exclude) 后才有值
        if (mTargetExcludes != null && mTargetExcludes.contains(view)) {
            return;
        }

        // mTargetTypeExcludes 是在调用 Transition.excludeTarget(Class type, boolean exclude) 后才有值
        if (mTargetTypeExcludes != null && view != null) {
            int numTypes = mTargetTypeExcludes.size();
            for (int i = 0; i < numTypes; ++i) {
                if (mTargetTypeExcludes.get(i).isInstance(view)) {
                    return;
                }
            }
        }

        if (view.getParent() instanceof ViewGroup) {
            TransitionValues values = new TransitionValues();
            values.view = view;
            if (start) {
                // 为 TransitionValues.values存值，保存起始场景View的属性值，自定义Transition类，这个方法要复写
                captureStartValues(values);
            } else {
                // 为 TransitionValues.values存值，保存结束场景View的属性值，自定义Transition类，这个方法要复写
                captureEndValues(values);
            }
            // 把当前 Transition 保存到 TransitionValues.targetedTransitions
            values.targetedTransitions.add(this);
            if (start) {
                // 为 mStartValues 的 4 个 map 存值
                addViewValues(mStartValues, view, values);
            } else {
                // 为 mEndValues 的 4 个 map 存值
                addViewValues(mEndValues, view, values);
            }
        }
        if (view instanceof ViewGroup) {
            // Don't traverse child hierarchy if there are any child-excludes on this view
            if (mTargetIdChildExcludes != null && mTargetIdChildExcludes.contains(id)) {
                return;
            }
            if (mTargetChildExcludes != null && mTargetChildExcludes.contains(view)) {
                return;
            }
            if (mTargetTypeChildExcludes != null) {
                int numTypes = mTargetTypeChildExcludes.size();
                for (int i = 0; i < numTypes; ++i) {
                    if (mTargetTypeChildExcludes.get(i).isInstance(view)) {
                        return;
                    }
                }
            }
            // 遍历，递归调用，直到根部局下所有的 child 都保存了值
            ViewGroup parent = (ViewGroup) view;
            for (int i = 0; i < parent.getChildCount(); ++i) {
                captureHierarchy(parent.getChildAt(i), start);
            }
        }
    } 
```

保存数据，然后遍历 sceneRoot 的children，递归调用 captureHierarchy() 方法来保存数据。 所以整个 captureHierarchy() 方法就中用来保存 View（包括 sceneRoot）的数据。

captureStartValues()/captureEndValues() ，分别用来保存转换 前/后 布局的信息，这2个方法，是在 Transtion 类中是抽象方法。我们以Transition实现类ChangeBounds 类的 captureStartValues() 方法来分析。

ChangeBounds.java 的 captureStartValues()

```java
@Override
public void captureStartValues(TransitionValues transitionValues) {
    captureValues(transitionValues);
}

@Override
public void captureEndValues(TransitionValues transitionValues) {
    captureValues(transitionValues);
}

private static final String PROPNAME_BOUNDS = "android:changeBounds:bounds";
private static final String PROPNAME_CLIP = "android:changeBounds:clip";
private static final String PROPNAME_PARENT = "android:changeBounds:parent";
private static final String PROPNAME_WINDOW_X = "android:changeBounds:windowX";
private static final String PROPNAME_WINDOW_Y = "android:changeBounds:windowY";

private void captureValues(TransitionValues values) {
    View view = values.view;

    if (view.isLaidOut() || view.getWidth() != 0 || view.getHeight() != 0) {
        values.values.put(PROPNAME_BOUNDS, new Rect(view.getLeft(), view.getTop(),
                view.getRight(), view.getBottom()));
        values.values.put(PROPNAME_PARENT, values.view.getParent());

        if (mReparent) {
            values.view.getLocationInWindow(tempLocation);
            values.values.put(PROPNAME_WINDOW_X, tempLocation[0]);
            values.values.put(PROPNAME_WINDOW_Y, tempLocation[1]);
        }

        if (mResizeClip) {
            values.values.put(PROPNAME_CLIP, view.getClipBounds());
        }
    }
}
```

在 `captureValues` 方法中，可以看到 TransitionValues.values保存了什么数据。再回到 captureHierarchy() 方法中，把当前的 transition 保存到 values.targetedTransitions 中。在 `captureHierarchy()` 方法中，`addViewValues()` 方法把刚刚创建的局部变量 values的值保存到 `mStartValues/ mEndValues` 中。

Transition#addViewValues()

```java
private static void addViewValues(TransitionValuesMaps transitionValuesMaps,
        View view, TransitionValues transitionValues) {
    // step1
    transitionValuesMaps.mViewValues.put(view, transitionValues);
    int id = view.getId();
    if (id >= 0) {
        if (transitionValuesMaps.mIdValues.indexOfKey(id) >= 0) {
            // Duplicate IDs cannot match by ID.
            // 如果有重复的 id，那么就不去匹配了，直接把 id 和 null 对应
            transitionValuesMaps.mIdValues.put(id, null);
        } else {
            // step2
            transitionValuesMaps.mIdValues.put(id, view);
        }
    }
    String name = ViewCompat.getTransitionName(view);
    if (name != null) {
        if (transitionValuesMaps.mNameValues.containsKey(name)) {
            // Duplicate transitionNames: cannot match by transitionName.
            // 如果有重复的 transitionName，那么就不去匹配了，直接把 transitionName和 null 对应
            transitionValuesMaps.mNameValues.put(name, null);
        } else {
            // step3
            transitionValuesMaps.mNameValues.put(name, view);
        }
    }
    // 关于 ListView 的
    if (view.getParent() instanceof ListView) {
        ListView listview = (ListView) view.getParent();
        if (listview.getAdapter().hasStableIds()) {
            int position = listview.getPositionForView(view);
            long itemId = listview.getItemIdAtPosition(position);
            if (transitionValuesMaps.mItemIdValues.indexOfKey(itemId) >= 0) {
                // Duplicate item IDs: cannot match by item ID.
                View alreadyMatched = transitionValuesMaps.mItemIdValues.get(itemId);
                if (alreadyMatched != null) {
                    ViewCompat.setHasTransientState(alreadyMatched, false);
                    transitionValuesMaps.mItemIdValues.put(itemId, null);
                }
            } else {
                ViewCompat.setHasTransientState(view, true);
                // step4
                transitionValuesMaps.mItemIdValues.put(itemId, view);
            }
        }
    }
}
```

分为 4 步保存数据，其中我们需要注意就是，同一个布局中不要出现重复的 id 或 重复的 transitionname，不然是不能实现动画的。

到此为止，完成了 TransitionManager#beginDelayedTransition() 的第一步，将转换前的布局中的每个view的信息，保存到了 mStartValues 中。

#### 生成并执行动画

与 TransitionManager.go 不同，beginDelayedTransition 没有布局切换的步骤，直接执行 sceneChangeRunTransition，这一步是创建 Anmator，并运行。

```java
private static void sceneChangeRunTransition(final ViewGroup sceneRoot,
        final Transition transition) {
    if (transition != null && sceneRoot != null) {
        MultiListener listener = new MultiListener(transition, sceneRoot);
        sceneRoot.addOnAttachStateChangeListener(listener);
        sceneRoot.getViewTreeObserver().addOnPreDrawListener(listener);
    }
}

private static class MultiListener implements ViewTreeObserver.OnPreDrawListener,
        View.OnAttachStateChangeListener {

    Transition mTransition;

    ViewGroup mSceneRoot;

    MultiListener(Transition transition, ViewGroup sceneRoot) {
        mTransition = transition;
        mSceneRoot = sceneRoot;
    }

    private void removeListeners() {
        mSceneRoot.getViewTreeObserver().removeOnPreDrawListener(this);
        mSceneRoot.removeOnAttachStateChangeListener(this);
    }

    @Override
    public void onViewAttachedToWindow(View v) {
    }

    @Override
    public void onViewDetachedFromWindow(View v) {
        removeListeners();

        sPendingTransitions.remove(mSceneRoot);
        ArrayList<Transition> runningTransitions = getRunningTransitions().get(mSceneRoot);
        if (runningTransitions != null && runningTransitions.size() > 0) {
            for (Transition runningTransition : runningTransitions) {
                runningTransition.resume(mSceneRoot);
            }
        }
        mTransition.clearValues(true);
    }

    @Override
    public boolean onPreDraw() {
        removeListeners();
        // TransitionManager.go() 的时候 add 过一次
        sPendingTransitions.remove(mSceneRoot);
        // Add to running list, handle end to remove it
        final ArrayMap<ViewGroup, ArrayList<Transition>> runningTransitions =
                getRunningTransitions();
        ArrayList<Transition> currentTransitions = runningTransitions.get(mSceneRoot);
        ArrayList<Transition> previousRunningTransitions = null;
        if (currentTransitions == null) {
            // 如果不存在正在运行的 transition，就初始化
            currentTransitions = new ArrayList<>();
            runningTransitions.put(mSceneRoot, currentTransitions);
        } else if (currentTransitions.size() > 0) {
            // 如果有正在运行的 transtion, 就用 previousRunningTransitions 保存
            previousRunningTransitions = new ArrayList<>(currentTransitions);
        }
        currentTransitions.add(mTransition);
        mTransition.addListener(new Transition.TransitionListenerAdapter() {
            @Override
            public void onTransitionEnd(@NonNull Transition transition) {
                ArrayList<Transition> currentTransitions = runningTransitions.get(mSceneRoot);
                currentTransitions.remove(transition);
            }
        });
        // step1 保存 end scene 的信息
        mTransition.captureValues(mSceneRoot, false);
        if (previousRunningTransitions != null) {
            for (Transition runningTransition : previousRunningTransitions) {
                // 继续运行之前暂停的 transition
                runningTransition.resume(mSceneRoot);
            }
        }
        // step2 创建并运行动画
        mTransition.playTransition(mSceneRoot);

        return true;
    }
}
```

需要注意的是，在re-draw 之前，调用 onPreDraw()。在 sceneChangeSetup方法中，我们获取过一次运行的动画，如果有就暂停，这里会恢复之前暂停的动画。Transition.captureValues 方法会保存数据信息到mEndValues中。

最后创建动画，并执行动画。

```java
void playTransition(ViewGroup sceneRoot) {
    // mStartValuesList/mEndValuesList 这里才开始初始化
    mStartValuesList = new ArrayList<TransitionValues>();
    mEndValuesList = new ArrayList<TransitionValues>();

    // 过滤掉不匹配做动画的的View
    matchStartAndEnd(mStartValues, mEndValues);

    // 如果有动画正在运行或者刚开始，就取消动画。
    // 如果还没有运行，就移除动画。
    ArrayMap<Animator, AnimationInfo> runningAnimators = getRunningAnimators();
    int numOldAnims = runningAnimators.size();
    WindowId windowId = sceneRoot.getWindowId();
    for (int i = numOldAnims - 1; i >= 0; i--) {
        Animator anim = runningAnimators.keyAt(i);
        if (anim != null) {
            AnimationInfo oldInfo = runningAnimators.get(anim);
            if (oldInfo != null && oldInfo.view != null && oldInfo.windowId == windowId) {
                TransitionValues oldValues = oldInfo.values;
                View oldView = oldInfo.view;
                TransitionValues startValues = getTransitionValues(oldView, true);
                TransitionValues endValues = getMatchedTransitionValues(oldView, true);
                if (startValues == null && endValues == null) {
                    endValues = mEndValues.viewValues.get(oldView);
                }
                boolean cancel = (startValues != null || endValues != null) &&
                        oldInfo.transition.isTransitionRequired(oldValues, endValues);
                if (cancel) {
                    if (anim.isRunning() || anim.isStarted()) {
                        if (DBG) {
                            Log.d(LOG_TAG, "Canceling anim " + anim);
                        }
                        anim.cancel();
                    } else {
                        if (DBG) {
                            Log.d(LOG_TAG, "removing anim from info list: " + anim);
                        }
                        runningAnimators.remove(anim);
                    }
                }
            }
        }
    }

    // 创建动画
    createAnimators(sceneRoot, mStartValues, mEndValues, mStartValuesList, mEndValuesList);
    // 运行动画
    runAnimators();
}
```

在这个方法中，才初始化了两个全局变量 mStartValuesList 和 mEndValuesList，类型为 ArrayList< TransitionValues >。

过滤掉不能匹配做动画的 View，看下matchStartAndEnd() 方法。

```java
int[] mMatchOrder = DEFAULT_MATCH_ORDER;
private static final int[] DEFAULT_MATCH_ORDER = {
    MATCH_NAME,
    MATCH_INSTANCE,
    MATCH_ID,
    MATCH_ITEM_ID,
};
private void matchStartAndEnd(TransitionValuesMaps startValues,
        TransitionValuesMaps endValues) {
    // 获取 mStartValues 和 mEndValues 的 viewValues 
    ArrayMap<View, TransitionValues> unmatchedStart =
            new ArrayMap<View, TransitionValues>(startValues.viewValues);
    ArrayMap<View, TransitionValues> unmatchedEnd =
            new ArrayMap<View, TransitionValues>(endValues.viewValues);

    // 按照 mMathOrder 的顺序找到匹配的项
    for (int i = 0; i < mMatchOrder.length; i++) {
        switch (mMatchOrder[i]) {
            case MATCH_INSTANCE:
                // step2
                matchInstances(unmatchedStart, unmatchedEnd);
                break;
            case MATCH_NAME:
                // step1
                matchNames(unmatchedStart, unmatchedEnd,
                        startValues.nameValues, endValues.nameValues);
                break;
            case MATCH_ID:
                // step3
                matchIds(unmatchedStart, unmatchedEnd,
                        startValues.idValues, endValues.idValues);
                break;
            case MATCH_ITEM_ID:
                // step4
                matchItemIds(unmatchedStart, unmatchedEnd,
                        startValues.itemIdValues, endValues.itemIdValues);
                break;
        }
    }
    // 
    addUnmatched(unmatchedStart, unmatchedEnd);
}
```

按照 mMatchOrder 数组的顺序，开始过滤不匹配的 View，并保存匹配的 View，根据 match order，分别按照 transitionName、view instance、unique Ids、item id去过滤。

根据 transitionName，id，View Instance，listView 的 item id 成功匹配到的 View ， 把它们的 TransitionValues 加入到了 mStartValuesList 和 mEndValuesList 中了，而没有匹配成功的就用 null 来代替。

然后就是创建动画，执行 createAnimators()。

```java
protected void createAnimators(ViewGroup sceneRoot, TransitionValuesMaps startValues,
        TransitionValuesMaps endValues, ArrayList<TransitionValues> startValuesList,
        ArrayList<TransitionValues> endValuesList) {
    if (DBG) {
        Log.d(LOG_TAG, "createAnimators() for " + this);
    }
    ArrayMap<Animator, AnimationInfo> runningAnimators = getRunningAnimators();
    long minStartDelay = Long.MAX_VALUE;
    int minAnimator = mAnimators.size();
    SparseLongArray startDelays = new SparseLongArray();
    int startValuesListCount = startValuesList.size();
    for (int i = 0; i < startValuesListCount; ++i) {
        TransitionValues start = startValuesList.get(i);
        TransitionValues end = endValuesList.get(i);
        // 过滤异常情况
        if (start != null && !start.targetedTransitions.contains(this)) {
            start = null;
        }
        // 过滤异常情况
        if (end != null && !end.targetedTransitions.contains(this)) {
            end = null;
        }
        // 过滤同时为 null 的情况 
        if (start == null && end == null) {
            continue;
        }
        // Only bother trying to animate with values that differ between start/end
        // 如果 start 和 end 有差异，就代表需要改变
        boolean isChanged = start == null || end == null || isTransitionRequired(start, end);
        if (isChanged) {
          	... ...
            // TODO: what to do about targetIds and itemIds?
            // 为匹配到的 View 创建动画
            Animator animator = createAnimator(sceneRoot, start, end);
            if (animator != null) {
                // Save animation info for future cancellation purposes
                View view = null;
                TransitionValues infoValues = null;
                if (end != null) {
                    view = end.view;
                    String[] properties = getTransitionProperties();
                    if (view != null && properties != null && properties.length > 0) {
                        infoValues = new TransitionValues();
                        infoValues.view = view;
                        TransitionValues newValues = endValues.viewValues.get(view);
                        if (newValues != null) {
                            for (int j = 0; j < properties.length; ++j) {
                                infoValues.values.put(properties[j],
                                        newValues.values.get(properties[j]));
                            }
                        }
                        int numExistingAnims = runningAnimators.size();
                        for (int j = 0; j < numExistingAnims; ++j) {
                            Animator anim = runningAnimators.keyAt(j);
                            AnimationInfo info = runningAnimators.get(anim);
                            if (info.values != null && info.view == view &&
                                    ((info.name == null && getName() == null) ||
                                            info.name.equals(getName()))) {
                                if (info.values.equals(infoValues)) {
                                    // Favor the old animator
                                    animator = null;
                                    break;
                                }
                            }
                        }
                    }
                } else {
                    view = (start != null) ? start.view : null;
                }
                if (animator != null) {
                    // 如果调用过 setPropagation()，就获取每个 Transition 的动画延迟时间 
                    if (mPropagation != null) {
                        long delay = mPropagation
                                .getStartDelay(sceneRoot, this, start, end);
                        startDelays.put(mAnimators.size(), delay);
                        minStartDelay = Math.min(delay, minStartDelay);
                    }
                    AnimationInfo info = new AnimationInfo(view, getName(), this,
                            sceneRoot.getWindowId(), infoValues);
                    runningAnimators.put(animator, info);
                    mAnimators.add(animator);
                }
            }
        }
    }
    // 为相应的动画设置延迟时间 
    if (startDelays.size() != 0) {
        for (int i = 0; i < startDelays.size(); i++) {
            int index = startDelays.keyAt(i);
            Animator animator = mAnimators.get(index);
            long delay = startDelays.valueAt(i) - minStartDelay + animator.getStartDelay();
            animator.setStartDelay(delay);
        }
    }
}
```

createAnimator() 在 Transition 类中是一个抽象方法，所以需要子类去实现，参照 ChangeBounds 类来说明 ，只显示 ChangeBounds 用默认的方式 new ChangeBounds() 创建动画的代码。

```java
@Override
public Animator createAnimator(final ViewGroup sceneRoot, TransitionValues startValues,
        TransitionValues endValues) {
    // 只有 startValues 和 endValues 都不为空才创建动画
    if (startValues == null || endValues == null) {
        return null;
    }

    // ...

    // 注意，这是获取的是 end scene 的 View
    final View view = endValues.view;
    // 如果不设置 mReparent 为 true，parentMatches() 默认返回为 true
    if (parentMatches(startParent, endParent)) {
        Rect startBounds = (Rect) startValues.values.get(PROPNAME_BOUNDS);
        Rect endBounds = (Rect) endValues.values.get(PROPNAME_BOUNDS);
        final int startLeft = startBounds.left;
        final int endLeft = endBounds.left;
        final int startTop = startBounds.top;
        final int endTop = endBounds.top;
        final int startRight = startBounds.right;
        final int endRight = endBounds.right;
        final int startBottom = startBounds.bottom;
        final int endBottom = endBounds.bottom;
        final int startWidth = startRight - startLeft;
        final int startHeight = startBottom - startTop;
        final int endWidth = endRight - endLeft;
        final int endHeight = endBottom - endTop;
        // 如果设置 mResizeClip = true ，并且为 View 调用过 setClipBounds()，这里才有值
        Rect startClip = (Rect) startValues.values.get(PROPNAME_CLIP);
        Rect endClip = (Rect) endValues.values.get(PROPNAME_CLIP);
        int numChanges = 0;
        if ((startWidth != 0 && startHeight != 0) || (endWidth != 0 && endHeight != 0)) {
            if (startLeft != endLeft || startTop != endTop) ++numChanges;
            if (startRight != endRight || startBottom != endBottom) ++numChanges;
        }
        if ((startClip != null && !startClip.equals(endClip)) ||
                (startClip == null && endClip != null)) {
            ++numChanges;
        }
        if (numChanges > 0) {
            Animator anim;
            if (!mResizeClip) {
                // 运行动画之前，先设置 view 的坐标为 start scene 的坐标
                view.setLeftTopRightBottom(startLeft, startTop, startRight, startBottom);
                // 如果不用 clip bounds 实现动画
                if (numChanges == 2) {
                    // 如果宽高相等，只创建一个动画就可以达到整体平移的效果
                    if (startWidth == endWidth && startHeight == endHeight) {
                        Path topLeftPath = getPathMotion().getPath(startLeft, startTop, endLeft,
                                endTop);
                        anim = ObjectAnimator.ofObject(view, POSITION_PROPERTY, null,
                                topLeftPath);
                    } 
                    // 如果宽高有一个不相等，就需要创建两个动画来达到效果
                    else {
                        final ViewBounds viewBounds = new ViewBounds(view);
                        Path topLeftPath = getPathMotion().getPath(startLeft, startTop,
                                endLeft, endTop);
                        ObjectAnimator topLeftAnimator = ObjectAnimator
                                .ofObject(viewBounds, TOP_LEFT_PROPERTY, null, topLeftPath);

                        Path bottomRightPath = getPathMotion().getPath(startRight, startBottom,
                                endRight, endBottom);
                        ObjectAnimator bottomRightAnimator = ObjectAnimator.ofObject(viewBounds,
                                BOTTOM_RIGHT_PROPERTY, null, bottomRightPath);
                        AnimatorSet set = new AnimatorSet();
                        // 两个动画是同时进行的
                        set.playTogether(topLeftAnimator, bottomRightAnimator);
                        anim = set;
                        set.addListener(new AnimatorListenerAdapter() {
                            // We need a strong reference to viewBounds until the
                            // animator ends.
                            private ViewBounds mViewBounds = viewBounds;
                        });
                    }
                } else if (startLeft != endLeft || startTop != endTop) {
                    // ...
                } else {
                    // ...
                }
            } else {
                // ...
            }
            // 如果 Transition 运行期间，设置状态，不让 view 的 parent 刷新布局，Transition 结束后再恢复状态
            if (view.getParent() instanceof ViewGroup) {
                final ViewGroup parent = (ViewGroup) view.getParent();
                parent.suppressLayout(true);
                TransitionListener transitionListener = new TransitionListenerAdapter() {
                    boolean mCanceled = false;

                    @Override
                    public void onTransitionCancel(Transition transition) {
                        parent.suppressLayout(false);
                        mCanceled = true;
                    }

                    @Override
                    public void onTransitionEnd(Transition transition) {
                        if (!mCanceled) {
                            parent.suppressLayout(false);
                        }
                    }

                    @Override
                    public void onTransitionPause(Transition transition) {
                        parent.suppressLayout(false);
                    }

                    @Override
                    public void onTransitionResume(Transition transition) {
                        parent.suppressLayout(true);
                    }
                };
                addListener(transitionListener);
            }
            return anim;
        }
    } else {
       // ...
    }
    return null;
}
```

先是获取 end view，然后把这个 view 设置到转换前的的位置，然后再设置相应的动画来达到位移的效果。

接下来就是运行动画，runAnimators()。

```java
protected void runAnimators() {
    if (DBG) {
        Log.d(LOG_TAG, "runAnimators() on " + this);
    }
    // 调用 Transition 的 TransitionListener 的 onTransitionStart()
    start();
    ArrayMap<Animator, AnimationInfo> runningAnimators = getRunningAnimators();
    // Now start every Animator that was previously created for this transition
    for (Animator anim : mAnimators) {
        if (DBG) {
            Log.d(LOG_TAG, "  anim: " + anim);
        }
        if (runningAnimators.containsKey(anim)) {
            start();
            // 运行动画
            runAnimator(anim, runningAnimators);
        }
    }
    // 运行完了就清除保存的动画
    mAnimators.clear();
    // 调用 Transition 的 TransitionListener 的 onTransitionEnd()
    end();
}

protected void start() {
    if (mNumInstances == 0) {
        if (mListeners != null && mListeners.size() > 0) {
            ArrayList<TransitionListener> tmpListeners =
                    (ArrayList<TransitionListener>) mListeners.clone();
            int numListeners = tmpListeners.size();
            for (int i = 0; i < numListeners; ++i) {
                tmpListeners.get(i).onTransitionStart(this);
            }
        }
        mEnded = false;
    }
    mNumInstances++;
}

protected void end() {
    --mNumInstances;
    if (mNumInstances == 0) {
        if (mListeners != null && mListeners.size() > 0) {
            ArrayList<TransitionListener> tmpListeners =
                    (ArrayList<TransitionListener>) mListeners.clone();
            int numListeners = tmpListeners.size();
            for (int i = 0; i < numListeners; ++i) {
                tmpListeners.get(i).onTransitionEnd(this);
            }
        }
        for (int i = 0; i < mStartValues.itemIdValues.size(); ++i) {
            View view = mStartValues.itemIdValues.valueAt(i);
            if (view != null) {
                view.setHasTransientState(false);
            }
        }
        for (int i = 0; i < mEndValues.itemIdValues.size(); ++i) {
            View view = mEndValues.itemIdValues.valueAt(i);
            if (view != null) {
                view.setHasTransientState(false);
            }
        }
        mEnded = true;
    }
}
```

在runningAnimators包含动画时，执行动画。

```java
private void runAnimator(Animator animator,
        final ArrayMap<Animator, AnimationInfo> runningAnimators) {
    if (animator != null) {
        // TODO: could be a single listener instance for all of them since it uses the param
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationStart(Animator animation) {
                mCurrentAnimators.add(animation);
            }
            @Override
            public void onAnimationEnd(Animator animation) {
                runningAnimators.remove(animation);
                mCurrentAnimators.remove(animation);
            }
        });
        animate(animator);
    }
}

protected void animate(Animator animator) {
    // TODO: maybe pass auto-end as a boolean parameter?
    if (animator == null) {
        end();
    } else {
        if (getDuration() >= 0) {
            animator.setDuration(getDuration());
        }
        if (getStartDelay() >= 0) {
            animator.setStartDelay(getStartDelay() + animator.getStartDelay());
        }
        if (getInterpolator() != null) {
            animator.setInterpolator(getInterpolator());
        }
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                end();
                animation.removeListener(this);
            }
        });
        animator.start();
    }
}
```