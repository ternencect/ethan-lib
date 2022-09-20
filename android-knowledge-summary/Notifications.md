# 通知

## 通知剖析

> 通知的设计由系统模板决定，应用只需要定义模板中各个部分的内容即可。通知的部分详情仅在展开后的试图中显示。

- 小图标：必须提供，通过 `setSmallIcon()` 进行设置
- 应用名称：由系统提供
- 时间戳：由系统提供，但可以使用 `setWhen()` 替换或者使用 `setShowWhen(false)` 隐藏
- 大图标：可选内容，通过 `setLargeIcon()` 进行设置
- 标题：可选内容，通过 `setContentTitle()` 进行设置
- 文本：可选内容，通过 `setContentText()` 进行设置

## 创建通知

### 通知渠道

> 在 Android8.0 及更高版本上提供通知，必须先通过向 `createNotificationChannel()` 传递 `NotificationChannel` 的实例在系统中注册应用的通知渠道。

```kotlin
private fun createNotificationChannel(
    context: Context,
    channelName: String,
    description: String,
    importance: Int = NotificationManager.IMPORTANCE_DEFAULT
) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val channel = NotificationChannel(CHANNEL_ID, channelName, importance).apply {
            this.description = description
        }
        val notificationManager: NotificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }
}
```

由于必须先创建通知渠道，因此可以在应用启动时立即执行创建通知渠道的代码，这段代码是安全的，因为创建现有通知渠道不会执行任何操作。

`NotificationChannel` 构造函数需要一个 `importance`，它会使用 `NotificationManager`
类中的一个常量，此参数确定出现任何属于此渠道的通知时如何打断用户，但还必须使用 `stPriority()` 设置优先级，才能支持 Android7.1 和更低版本。

虽然必须按本文所示设置通知重要性/优先级，但系统不能保证您会获得提醒行为。在某些情况下，系统可能会根据其他因素更改重要性级别，并且用户始终可以重新定义指定渠道适用的重要性级别。

### 通知点按操作

每个通知都应该对点按操作做出响应，通常是在应用中打开对应于该通知的Activity。为此，必须指定通过 `PendingIntent` 对象定义的内容Intent，并将其传递给 `setContentIntent()`。

通过 `setAutoCancel()`，设置用户点按通知后是否自动移除通知。

### 显示通知

调用 `NotificationManagerCompat.notify` 显示通知，并将通知的唯一ID和 `NotificationCompat.Builder.build()` 的结果传递给它。

```kotlin
with(NotificationManagerCompat.from(context)) {
    notify(notificationId, builder.build())
}
```

记得保存传递到 `NotificationManagerCompat.notify()` 的通知ID，因为如果之后想要更新或移除通知，将需要使用这个ID。

### 添加操作按钮

一个通知最多可以提供三个操作按钮，让用户能够快速响应。但这些操作按钮不应该重复用户中点按通知时执行的操作。

如需添加操作按钮，可以通过将 `addAction()` 传递给 `PendingIntent` 方法。

### 添加进度条

> 通知可以包含动画形式的进度指示器，向用户显示正在进行的操作的状态。

在可以估算操作在任何时间点的完成进度，应通过调用 `setProgress(max, progress, false)` 使用指示器的"确定性"形式。其中，第一个参数是"完成"
值；第二个参数是当前完成的进度，最后一个参数表明这是一个确定性进度条。

随着操作的继续，持续使用 `progress` 的更新值调用 `setProgress(max, progress, false)` 并重新发出通知。

```kotlin
fun showNotificationWithProgress(
    context: Context,
    notificationId: Int,
    @DrawableRes icon: Int,
    title: String,
    text: String,
    priority: Int
) {
    val builder = NotificationCompat.Builder(context, CHANNEL_ID).apply {
        setContentTitle(title)
        setContentText(text)
        setSmallIcon(icon)
        setPriority(priority)
    }
    val maxProgress = 100
    val currentProgress = 0
    NotificationManagerCompat.from(context).apply {
        builder.setProgress(maxProgress, currentProgress, false)
        notify(notificationId, builder.build())

        // Do the job here that tracks the progress.
        // Usually, this should be in a
        // worker thread
        // To show progress, update PROGRESS_CURRENT and update the notification with:
        // builder.setProgress(PROGRESS_MAX, PROGRESS_CURRENT, false);
        // notificationManager.notify(notificationId, builder.build());

        // When done, update the notification one more time to remove the progress bar
        builder.setContentText("Download complete")
        notify(notificationId, builder.build())
    }
}
```

操作结束时，`progress` 应该等于 `max`。可以在操作完成后仍显示进度条，也可以将其移除。无论哪种情况，都请记得更新通知文本，显示操作已完成。如需移除进度条，请调用 `setProgress(0, 0, false)`。

> 由于进度条要求应用持续更新通知，因此该代码通常应在后台服务中运行。

## 创建一组通知

如果满足以下所有条件，应该使用通知组：

- 子级通知是完整通知，可以单独显示，无需通知组摘要
- 单独显示子级通知有一个好处
    - 可操作，具体操作特定于每条通知
    - 用户看到的每条通知中都包含更多信息

### 创建通知组并为其添加通知

通过为通知组定义一个唯一标识符字符串，创建通知组。对于想要添加到通知组中的每条通知，只需调用 `setGroup()` 并传入通知组名称既可。

```kotlin
val groupNotification = NotificationCompat.Builder(context, CHANNEL_ID)
    .setSmallIcon(icon)
    .setContentTitle(title)
    .setContentText(text)
    .setGroup(GROUP_KEY)
    .build()
```

默认情况下，系统会根据通知的发布时间对其进行排序，但可以通过调用 `setSortKey()` 更改通知顺序。

如果通知组的提醒应由其他通知处理，请调用 `setGroupAlertBehavior()`。例如，如果您只希望通知组摘要发出提醒，那么通知组中的所有子级都应具有通知组提醒行为 GROUP_ALERT_SUMMARY。其他选项包括
`GROUP_ALERT_ALL` 和 `GROUP_ALERT_CHILDREN`。

## 创建和管理通知渠道

> 从Android8.0开始，所有通知都必须分配到相应的渠道。对于每个渠道，可以设置应用于其中的所有通知的视觉和听觉行为。然后，用户可以更改这些设置，并确定应用中的哪些通知渠道应具有干扰性或应该可见。

**在通知设置中将通知渠道称作"类别"**

创建通知渠道后，便无法更改通知行为，此时用户拥有完全控制权。不过，仍然可以更改渠道的名称和说明。

应该为需要发送的每种不同类型的通知各创建一个渠道。还可以创建通知渠道来反映应用的用户做出的选择。例如，可以为用户在短信应用中创建的每个会话组设置不同的通知渠道。

### 创建通知渠道

创建通知渠道，按以下步骤操作：

1. 构建一个具有唯一渠道ID、用户可见名称和重要性级别的 `NotificationChannel` 对象
2. （可选）使用 `setDescription()` 指定用户在系统设置中看到的说明
3. 注册通知渠道，方法是将该渠道传递给 `createNotificationChannel()`

创建采用其原始值的现有通知渠道不会执行任何操作，因此可以放心地在启动应用时调用创建通知渠道。

默认情况下，发布到此渠道的所有通知都使用由 `NotificationManagerCompat` 类中的重要性级别（如 `IMPORTANCE_DEFAULT` 和 `IMPORTANCE_HIGH`）定义的视觉和听觉行为

如果希望进一步自定义渠道的默认通知行为，可以在 `NotificationChannel` 上调用 `enableLights()`、`setLightColor()` 和 `setVibrationPattern()`
等方法。但请注意，创建渠道后，将无法更改这些设置，而且对于是否启用相应行为，用户拥有最终控制权。

还可以通过调用 `createNotificationChannels()` 在一次操作中创建多个通知渠道。

#### 设置重要性级别

渠道重要性会影响在渠道中发布的所有通知的干扰级别，因此必须在 `NotificationChannel` 构造函数中指定渠道重要性。可以使用从 `IMPORTANCE_NONE(0)` 到 `IMPORTANCE_HIGH(4)`
的五个重要性级别之一。为渠道指定的重要性级别会应用到在其中发布的所有通知消息。

重要性 (`NotificationManager.IMPORTANCE_*`) 和优先级常量 (`NotificationCompat.PRIORITY_*`) 会映射到用户可见的重要性选项。

| 用户可见的重要性级别                | 重要性                | 优先级                          |
|:--------------------------|:-------------------|:-----------------------------|
| 紧急<br/>：发出提示音，并以浮动通知的形式显示 | IMPORTANCE_HIGH    | PRIORITY_HIGH 或 PRIORITY_MAX |
| 高<br/>：发出提示音              | IMPORTANCE_DEFAULT | PRIORITY_DEFAULT             |
| 中<br/>：无提示音               | IMPORTANCE_LOW     | PRIORITY_LOW                 |
| 低<br/>：无提示音，且不会在状态栏中显示    | IMPORTANCE_MIN     | PRIORITY_MIN                 |

无论重要性级别如何，所有通知都会在非干扰系统界面位置显示。

将渠道提交到 NotificationManager 后，便无法更改重要性级别。不过，用户可以随时更改他们对应用渠道的偏好设置。

### 读取通知渠道设置

用户可以修改通知渠道的设置，其中包括振动和提醒提示音等行为。如果想要了解用户对通知渠道所应用对设置，可以按以下步骤操作：

1. 通过调用 `getNotificationChannel()` 或 `getNotificationChannels()` 来获取 `NotificationChannel` 对象
2. 查询特定对渠道设置，例如 `getVibrationPattern()`、`getSound()` 和 `getImportance()`

如果检测到某项渠道设置禁止应用对预期行为，可以建议用户更改该设置，并提供一项用于打开渠道设置对操作。

### 打开通知渠道设置

创建通知渠道后，便无法以编程方式更改通知渠道的视觉和听觉行为，只有用户可以通过系统设置更改渠道行为。为了让用户轻松访问这些通知设置，应在应用的设置界面中添加一个用于打开这些系统设置的项

可以通过使用 `ACTION_CHANNEL_NOTIFICATION_SETTINGS` 操作的 `Intent` 打开通知渠道的系统设置。

```kotlin
val intent = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
    putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
    putExtra(Settings.EXTRA_CHANNEL_ID, myNotificationChannel.getId())
}
startActivity(intent)
```

注意，该 intent 需要两个提取项，分别用于指定应用的软件包名称（也称为应用 ID）和要修改的渠道。

### 删除通知渠道

可以通过调用 `deleteNotificationChannel()` 删除通知渠道。

```kotlin
// The id of the channel.
val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
val id: String = "my_channel_01"
notificationManager.deleteNotificationChannel(id)
```

### 创建通知渠道分组

每个通知渠道分组都需要一个在应用内独一无二的ID，以及一个用户可见名称。

创建新分组后，可以调用 `setGroup()` 以将新的 `NotificationChannel` 对象与该分组相关联。将渠道提交至通知管理器后，便无法更改通知渠道和分组之间的关联。

## 创建自定义通知布局

### 为内容区域创建自定义布局

如果需要自定义布局，可以将 `NotificationCompat.DecoratedCustomViewStyle`
应用于通知。借助此API，可以为通常由标题和文本内容占据的内容区域提供自定义布局，同时仍对通知图标、时间戳、子文本和操作按钮使用系统装饰。

该 API 的工作方式与展开式通知模板类似，都是基于基本通知布局，如下所示：

1. 使用 `NotificationCompat.Builder` 构建基本通知。
2. 调用 `setStyle()`，向其传递一个 `NotificationCompat.DecoratedCustomViewStyle` 实例。
3. 将自定义布局扩充为 `RemoteViews` 的实例。
4. 调用 `setCustomContentView()` 以设置收起后通知的布局。还可以选择调用 `setCustomBigContentView()` 为展开后通知设置不同的布局

如果不希望使用标准通知图标和标题装饰通知，按照上述步骤使用 `setCustomBigContentView()`，但不要调用 `setStyle()`。