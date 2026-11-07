::error title=TEST::com.wearsic.app.ExampleRobolectricTest#testLibraryAndSearchNavigation::java.lang.AssertionError: Failed: assertExists.&#10;Reason: Expected exactly '1' node but found '2' nodes that satisfy: (Text + InputText + EditableText contains 'Library' (ignoreCase: false))&#10;Nodes found:&#10;1) Node #619 at (l=223.03804, t=63.214756, r=231.03804, b=99.21475)px&#10;Text = '[Library]'&#10;Actions = [ClearTextSubstitution, GetTextLayoutResult, SetTextSubstitution, ShowTextSubstitution]&#10;Has 2 siblings&#10;2) Node #548 at (l=120.0, t=108.0, r=128.0, b=130.0)px&#10;Text = '[Library]'&#10;Actions = [ClearTextSubstitution, GetTextLayoutResult, SetTextSubstitution, ShowTextSu — trace in the log/summary below

### com.wearsic.app.ExampleRobolectricTest#testLibraryAndSearchNavigation
cause: java.lang.AssertionError: Failed: assertExists.&#10;Reason: Expected exactly '1' node but found '2' nodes that satisfy: (Text + InputText + EditableText contains 'Library' (ignoreCase: false))&#10;Nodes found:&#10;1) Node #619 at (l=223.03804, t=63.214756, r=231.03804, b=99.21475)px&#10;Text = '[Library]'&#10;Actions = [ClearTextSubstitution, GetTextLayoutResult, SetTextSubstitution, ShowTextSubstitution]&#10;Has 2 siblings&#10;2) Node #548 at (l=120.0, t=108.0, r=128.0, b=130.0)px&#10;Text = '[Library]'&#10;Actions = [ClearTextSubstitution, GetTextLayoutResult, SetTextSubstitution, ShowTextSu

```
java.lang.AssertionError: Failed: assertExists.
Reason: Expected exactly '1' node but found '2' nodes that satisfy: (Text + InputText + EditableText contains 'Library' (ignoreCase: false))
Nodes found:
1) Node #619 at (l=223.03804, t=63.214756, r=231.03804, b=99.21475)px
Text = '[Library]'
Actions = [ClearTextSubstitution, GetTextLayoutResult, SetTextSubstitution, ShowTextSubstitution]
Has 2 siblings
2) Node #548 at (l=120.0, t=108.0, r=128.0, b=130.0)px
Text = '[Library]'
Actions = [ClearTextSubstitution, GetTextLayoutResult, SetTextSubstitution, ShowTextSubstitution]
Has 4 siblings
	at androidx.compose.ui.test.SemanticsNodeInteraction.fetchOneOrThrow(SemanticsNodeInteraction.kt:178)
	at androidx.compose.ui.test.SemanticsNodeInteraction.fetchOneOrThrow$default(SemanticsNodeInteraction.kt:150)
	at androidx.compose.ui.test.SemanticsNodeInteraction.assertExists(SemanticsNodeInteraction.kt:125)
	at androidx.compose.ui.test.SemanticsNodeInteraction.assertExists$default(SemanticsNodeInteraction.kt:124)
	at com.wearsic.app.ExampleRobolectricTest.testLibraryAndSearchNavigation(ExampleRobolectricTest.kt:184)
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
	at androidx.compose.ui.test.IdlingStra
```

::error title=TEST::com.wearsic.app.RecentPlaybackIdentityTest#exact ids survive restart and still resolve their own recordings::org.junit.ComparisonFailure: expected:<[AAA]> but was:<[BBB]> — trace in the log/summary below

### com.wearsic.app.RecentPlaybackIdentityTest#exact ids survive restart and still resolve their own recordings
cause: org.junit.ComparisonFailure: expected:<[AAA]> but was:<[BBB]>

```
but was:<[BBB]>" type="org.junit.ComparisonFailure">org.junit.ComparisonFailure: expected:<[AAA]> but was:<[BBB]>
	at org.junit.Assert.assertEquals(Assert.java:117)
	at org.junit.Assert.assertEquals(Assert.java:146)
	at com.wearsic.app.RecentPlaybackIdentityTest$exact ids survive restart and still resolve their own recordings$1.invokeSuspend(RecentPlaybackIdentityTest.kt:192)
	at kotlin.coroutines.jvm.internal.BaseContinuationImpl.resumeWith(ContinuationImpl.kt:33)
	at kotlinx.coroutines.DispatchedTask.run(DispatchedTask.kt:98)
	at kotlinx.coroutines.EventLoopImplBase.processNextEvent(EventLoop.common.kt:263)
	at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:94)
	at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:70)
	at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
	at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:48)
	at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
	at com.wearsic.app.RecentPlaybackIdentityTest.exact ids survive restart and still resolve their own recordings(RecentPlaybackIdentityTest.kt:179)
	at java.base/jdk.internal.reflect.DirectMethodHandleAccessor.invoke(DirectMethodHandleAccessor.java:103)
	at java.base/java.lang.reflect.Method.invoke(Method.java:580)
	at org.junit.runners.model.FrameworkMethod$1.runReflectiveCall(FrameworkMethod.java:59)
	at org.junit.internal.runners.model.ReflectiveCallable.run(ReflectiveCallable.java:12)
	at org.junit.runners.model.FrameworkMethod.invokeExplosively(FrameworkMethod.java:56)
	at org.junit.internal.runners.statements.InvokeMethod.evaluate(InvokeMethod.java:17)
	at org.junit.internal.runners.statements.RunBefores.evaluate(RunBefores.java:26)
	at org.junit.internal.runners.statements.RunAfters.evaluate(RunAfters.java:27)
	at org.junit.runners.ParentRunner$3.evaluate(ParentRunner.java:306)
	at org.robolectric.RobolectricTestRunner$HelperTestRunner$1.evaluate(RobolectricTestRunner.java:524)
	at org.robolectric.internal.SandboxTestRunner.executeInSandbox(SandboxTestRunner.java:494)
	at org.robolectric.internal.SandboxTestRunner.access$900(SandboxTestRunner.java:67)
	at org.robolectric.internal.SandboxTestRunner$7.evaluate(SandboxTestRunner.java:442)
	at org.junit.runners.BlockJUnit4ClassRunner$1.evaluate(BlockJUnit4ClassRunner.java:100)
	at org.junit.runners.ParentRunner.runLeaf(ParentRunner.java:366)
	at org.junit.runners.BlockJUnit4ClassRunner.runChild(BlockJUnit4ClassRunner.java:103)
	at org.robolectric.internal.SandboxTestRunner.access$600(SandboxTestRunner.java:67)
	at org.robolectric.internal.Sandbox
```

::error title=TEST::com.wearsic.app.RelaunchWithDataTest#relaunch_withPersistedState_rendersAndStaysResponsive::java.lang.AssertionError: Failed to perform checkIsDisplayed check: Expected at most 1 node but found 2 nodes that satisfy (Text + InputText + EditableText contains 'Library' (ignoreCase: false)) — trace in the log/summary below

### com.wearsic.app.RelaunchWithDataTest#relaunch_withPersistedState_rendersAndStaysResponsive
cause: java.lang.AssertionError: Failed to perform checkIsDisplayed check: Expected at most 1 node but found 2 nodes that satisfy (Text + InputText + EditableText contains 'Library' (ignoreCase: false))

```
java.lang.AssertionError: Failed to perform checkIsDisplayed check: Expected at most 1 node but found 2 nodes that satisfy (Text + InputText + EditableText contains 'Library' (ignoreCase: false))
	at androidx.compose.ui.test.AndroidAssertions_androidKt.checkIsDisplayed(AndroidAssertions.android.kt:35)
	at androidx.compose.ui.test.AssertionsKt.isDisplayed(Assertions.kt:351)
	at androidx.compose.ui.test.AssertionsKt.assertIsDisplayed(Assertions.kt:33)
	at com.wearsic.app.RelaunchWithDataTest.relaunch_withPersistedState_rendersAndStaysResponsive(RelaunchWithDataTest.kt:158)
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
	at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$
```

