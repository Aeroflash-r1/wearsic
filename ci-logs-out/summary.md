::error title=TEST::com.wearsic.app.QueueDragTest#longPressingUpperHalfOfSongCard_liftsThatExactCard_notPreviousCard::java.lang.AssertionError: Assert failed: The component with TestTag = 'queue_track_t3' is not displayed! — trace in the log/summary below

### com.wearsic.app.QueueDragTest#longPressingUpperHalfOfSongCard_liftsThatExactCard_notPreviousCard
cause: java.lang.AssertionError: Assert failed: The component with TestTag = 'queue_track_t3' is not displayed!

```
java.lang.AssertionError: Assert failed: The component with TestTag = 'queue_track_t3' is not displayed!
	at androidx.compose.ui.test.AssertionsKt.assertIsDisplayed(Assertions.kt:34)
	at com.wearsic.app.QueueDragTest.longPressingUpperHalfOfSongCard_liftsThatExactCard_notPreviousCard(QueueDragTest.kt:110)
	at java.base/jdk.internal.reflect.DirectMethodHandleAccessor.invoke(DirectMethodHandleAccessor.java:103)
	at java.base/java.lang.reflect.Method.invoke(Method.java:580)
	at org.junit.runners.model.FrameworkMethod$1.runReflectiveCall(FrameworkMethod.java:59)
	at org.junit.internal.runners.model.ReflectiveCallable.run(ReflectiveCallable.java:12)
	at org.junit.runners.model.FrameworkMethod.invokeExplosively(FrameworkMethod.java:56)
	at org.junit.internal.runners.statements.InvokeMethod.evaluate(InvokeMethod.java:17)
	at androidx.compose.ui.test.junit4.AndroidComposeTestRule$apply$testWithDisposal$1.evaluate(AndroidComposeTestRule.android.kt:418)
	at org.junit.rules.ExternalResource$1.evaluate(ExternalResource.java:54)
	at androidx.compose.ui.test.junit4.AndroidComposeTestRule$apply$1$evaluate$1.invokeSuspend(AndroidComposeTestRule.android.kt:438)
	at androidx.compose.ui.test.junit4.AndroidComposeTestRule$apply$1$evaluate$1.invoke(AndroidComposeTestRule.android.kt)
	at androidx.compose.ui.test.junit4.AndroidComposeTestRule$apply$1$evaluate$1.invoke(AndroidComposeTestRule.android.kt)
	at androidx.compose.ui.test.AndroidComposeUiTestEnvironment$runTest$1$1$1$1$1.invokeSuspend(ComposeUiTest.android.kt:682)
	at androidx.compose.ui.test.AndroidComposeUiTestEnvironment$runTest$1$1$1$1$1.invoke(ComposeUiTest.android.kt)
	at androidx.compose.ui.test.AndroidComposeUiTestEnvironment$runTest$1$1$1$1$1.invoke(ComposeUiTest.android.kt)
	at androidx.compose.ui.test.IdlingStrategy.withStrategy$suspendImpl(IdlingStrategy.android.kt:55)
	at androidx.compose.ui.test.IdlingStrategy.withStrategy(IdlingStrategy.android.kt)
	at androidx.compose.ui.test.AndroidComposeUiTestEnvironment$runTest$1$1.invokeSuspend(ComposeUiTest.android.kt:679)
	at androidx.compose.ui.test.AndroidComposeUiTestEnvironment$runTest$1$1.invoke(ComposeUiTest.android.kt)
	at androidx.compose.ui.test.AndroidComposeUiTestEnvironment$runTest$1$1.invoke(ComposeUiTest.android.kt)
	at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$1.invokeSuspend(TestBuilders.kt:317)
	at kotlin.coroutines.jvm.internal.BaseContinuationImpl.resumeWith(ContinuationImpl.kt:33)
	at kotlinx.coroutines.DispatchedTask.run(DispatchedTask.kt:100)
	at kotlinx.coroutines.test.TestDispatcher.processEvent$kotlinx_coroutines_
```

::error title=TEST::com.wearsic.app.QueueDragTest#draggingSongCardUp_reordersUpward::java.lang.AssertionError: expected:<[(3, 1)]> but was:<[]> — trace in the log/summary below

### com.wearsic.app.QueueDragTest#draggingSongCardUp_reordersUpward
cause: java.lang.AssertionError: expected:<[(3, 1)]> but was:<[]>

```
but was:<[]>" type="java.lang.AssertionError">java.lang.AssertionError: expected:<[(3, 1)]> but was:<[]>
	at org.junit.Assert.fail(Assert.java:89)
	at org.junit.Assert.failNotEquals(Assert.java:835)
	at org.junit.Assert.assertEquals(Assert.java:120)
	at org.junit.Assert.assertEquals(Assert.java:146)
	at com.wearsic.app.QueueDragTest.draggingSongCardUp_reordersUpward(QueueDragTest.kt:202)
	at java.base/jdk.internal.reflect.DirectMethodHandleAccessor.invoke(DirectMethodHandleAccessor.java:103)
	at java.base/java.lang.reflect.Method.invoke(Method.java:580)
	at org.junit.runners.model.FrameworkMethod$1.runReflectiveCall(FrameworkMethod.java:59)
	at org.junit.internal.runners.model.ReflectiveCallable.run(ReflectiveCallable.java:12)
	at org.junit.runners.model.FrameworkMethod.invokeExplosively(FrameworkMethod.java:56)
	at org.junit.internal.runners.statements.InvokeMethod.evaluate(InvokeMethod.java:17)
	at androidx.compose.ui.test.junit4.AndroidComposeTestRule$apply$testWithDisposal$1.evaluate(AndroidComposeTestRule.android.kt:418)
	at org.junit.rules.ExternalResource$1.evaluate(ExternalResource.java:54)
	at androidx.compose.ui.test.junit4.AndroidComposeTestRule$apply$1$evaluate$1.invokeSuspend(AndroidComposeTestRule.android.kt:438)
	at androidx.compose.ui.test.junit4.AndroidComposeTestRule$apply$1$evaluate$1.invoke(AndroidComposeTestRule.android.kt)
	at androidx.compose.ui.test.junit4.AndroidComposeTestRule$apply$1$evaluate$1.invoke(AndroidComposeTestRule.android.kt)
	at androidx.compose.ui.test.AndroidComposeUiTestEnvironment$runTest$1$1$1$1$1.invokeSuspend(ComposeUiTest.android.kt:682)
	at androidx.compose.ui.test.AndroidComposeUiTestEnvironment$runTest$1$1$1$1$1.invoke(ComposeUiTest.android.kt)
	at androidx.compose.ui.test.AndroidComposeUiTestEnvironment$runTest$1$1$1$1$1.invoke(ComposeUiTest.android.kt)
	at androidx.compose.ui.test.IdlingStrategy.withStrategy$suspendImpl(IdlingStrategy.android.kt:55)
	at androidx.compose.ui.test.IdlingStrategy.withStrategy(IdlingStrategy.android.kt)
	at androidx.compose.ui.test.AndroidComposeUiTestEnvironment$runTest$1$1.invokeSuspend(ComposeUiTest.android.kt:679)
	at androidx.compose.ui.test.AndroidComposeUiTestEnvironment$runTest$1$1.invoke(ComposeUiTest.android.kt)
	at androidx.compose.ui.test.AndroidComposeUiTestEnvironment$runTest$1$1.invoke(ComposeUiTest.android.kt)
	at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$1.invokeSuspend(TestBuilders.kt:317)
	at kotlin.coroutines.jvm.internal.BaseContinuationImpl.resumeWith(ContinuationImpl.kt:33)
	at kotlinx.coroutines.DispatchedTask.run(DispatchedTas
```

