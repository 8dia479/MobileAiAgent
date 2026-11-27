package com.mobileaiagent

import com.mobileaiagent.agent.NewTaskParams
import com.mobileaiagent.agent.RunOutcome
import com.mobileaiagent.agent.TaskManager
import com.mobileaiagent.agent.plan.PlanManager
import com.mobileaiagent.core.storage.AgentStores
import com.mobileaiagent.domain.model.*
import com.mobileaiagent.plugins.BuiltInRegistrar
import com.mobileaiagent.tools.spec.ToolRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert
import org.junit.Test

/**
 * TaskManager lifecycle: creation, queueing, terminal transitions, cancel,
 * pause/resume and event journaling. Uses a scripted runner so the assertions
 * are about orchestration, not model behaviour.
 */
class TaskManagerTest {

    private fun params(mode: ExecutionMode = ExecutionMode.BUILD) = NewTaskParams(
        request = "do work", projectId = "p1", projectName = "Test", mode = mode,
        mainModel = "m", mainProvider = "p", mainReasoningEffort = ReasoningEffort.HIGH,
    )

    private suspend fun waitFor(timeoutMs: Long = 5_000, condition: suspend () -> Boolean): Boolean {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            if (condition()) return true
            Thread.sleep(20)
        }
        return condition()
    }

    @Test fun taskIsPersistedBeforeExecutionAndReachesCompleted() = runBlocking {
        val stores = AgentStores.inMemory()
        val runner = ScriptedRunner { RunOutcome(RunOutcome.Kind.COMPLETED) }
        val manager = TaskManager(stores, runner)
        val task = manager.createTask(params())
        // The record is persisted *before* any work starts; the run may already have
        // been claimed by the dispatcher, so accept the two legal early states.
        val early = stores.tasks.get(task.id)!!.status
        Assert.assertTrue("unexpected early status $early", early == TaskStatus.QUEUED || early == TaskStatus.RUNNING)
        Assert.assertTrue("task persisted before execution", stores.tasks.get(task.id) != null)
        Assert.assertTrue("message persisted", stores.messages.list(task.id).isNotEmpty())
        Assert.assertTrue(waitFor { stores.tasks.get(task.id)?.status == TaskStatus.COMPLETED })
        val events = stores.events.list(task.id, 100)
        Assert.assertTrue(events.any { it.type == EventType.TASK_CREATED })
        Assert.assertTrue(events.any { it.type == EventType.RUN_STARTED })
        Assert.assertTrue(events.any { it.type == EventType.TASK_COMPLETED })
    }

    @Test fun failedRunIsRecordedWithErrorKind() = runBlocking {
        val stores = AgentStores.inMemory()
        val runner = ScriptedRunner { RunOutcome(RunOutcome.Kind.FAILED, "boom", ErrorKind.PROVIDER) }
        val manager = TaskManager(stores, runner)
        val task = manager.createTask(params())
        Assert.assertTrue(waitFor { stores.tasks.get(task.id)?.status == TaskStatus.FAILED })
        val stored = stores.tasks.get(task.id)!!
        Assert.assertEquals(ErrorKind.PROVIDER, stored.errorKind)
        Assert.assertEquals("boom", stored.lastError)
        Assert.assertNotNull(stored.finishedAt)
    }

    @Test fun waitingForNetworkKeepsTaskResumable() = runBlocking {
        val stores = AgentStores.inMemory()
        val runner = ScriptedRunner { RunOutcome(RunOutcome.Kind.WAITING_NETWORK, "offline", ErrorKind.NETWORK) }
        val manager = TaskManager(stores, runner)
        val task = manager.createTask(params())
        Assert.assertTrue(waitFor { stores.tasks.get(task.id)?.status == TaskStatus.WAITING_FOR_NETWORK })
        Assert.assertTrue(com.mobileaiagent.service.TaskTransitions.canResume(TaskStatus.WAITING_FOR_NETWORK))
    }

    @Test fun cancelRequestsCancellationOnTheLiveRunner() = runBlocking {
        val stores = AgentStores.inMemory()
        val runner = ScriptedRunner { RunOutcome(RunOutcome.Kind.COMPLETED) }
        val manager = TaskManager(stores, runner)
        val task = manager.createTask(params())
        // Wait until running, then cancel.
        Assert.assertTrue(waitFor { stores.tasks.get(task.id)?.status == TaskStatus.RUNNING })
        manager.cancel(task.id)
        Assert.assertTrue(runner.cancelled.contains(task.id))
        Assert.assertTrue(waitFor { stores.tasks.get(task.id)?.status == TaskStatus.CANCELLED })
    }

    @Test fun queuedTasksAreOrderedAndRunOneAtATime() = runBlocking {
        val stores = AgentStores.inMemory()
        val concurrent = java.util.concurrent.atomic.AtomicInteger(0)
        val maxConcurrent = java.util.concurrent.atomic.AtomicInteger(0)
        val ran = java.util.concurrent.CopyOnWriteArrayList<String>()
        val runner = object : com.mobileaiagent.agent.TaskRunner {
            override suspend fun run(task: TaskRecord, cancellation: com.mobileaiagent.core.util.CancellationToken, live: com.mobileaiagent.service.LiveRunRegistry): RunOutcome {
                val now = concurrent.incrementAndGet()
                maxConcurrent.updateAndGet { maxOf(it, now) }
                ran.add(task.id)
                var i = 0
                while (i < 10 && !cancellation.isCancelled) {
                    Thread.sleep(10)
                    i++
                }
                concurrent.decrementAndGet()
                return RunOutcome(RunOutcome.Kind.COMPLETED)
            }
            override fun requestPause(taskId: String) {}
            override fun requestCancel(taskId: String) {}
        }
        val manager = TaskManager(stores, runner)
        val a = manager.createTask(params())
        val b = manager.createTask(params())
        val c = manager.createTask(params())
        Assert.assertTrue(waitFor(8_000) { ran.size == 3 })
        Assert.assertEquals("only one task may run at a time", 1, maxConcurrent.get())
        Assert.assertEquals(listOf(a.id, b.id, c.id), ran.toList())
    }

    @Test fun pauseBeforeStartLeavesTaskPausedAndResumable() = runBlocking {
        val stores = AgentStores.inMemory()
        val runner = ScriptedRunner { RunOutcome(RunOutcome.Kind.COMPLETED) }
        val manager = TaskManager(stores, runner)
        // Occupy the single slot so the second task stays queued.
        val blocker = manager.createTask(params())
        Assert.assertTrue(waitFor { stores.tasks.get(blocker.id)?.status == TaskStatus.RUNNING })
        val queued = manager.createTask(params())
        Assert.assertEquals(TaskStatus.QUEUED, stores.tasks.get(queued.id)!!.status)
        manager.pause(queued.id)
        Assert.assertEquals(TaskStatus.PAUSED, stores.tasks.get(queued.id)!!.status)
        manager.cancel(blocker.id)
        manager.resume(queued.id)
        Assert.assertTrue(waitFor { stores.tasks.get(queued.id)?.status == TaskStatus.COMPLETED })
    }

    @Test fun dispatchDoesNotDuplicateRunForSameTask() = runBlocking {
        val stores = AgentStores.inMemory()
        val runs = java.util.concurrent.atomic.AtomicInteger(0)
        val runner = object : com.mobileaiagent.agent.TaskRunner {
            override suspend fun run(task: TaskRecord, cancellation: com.mobileaiagent.core.util.CancellationToken, live: com.mobileaiagent.service.LiveRunRegistry): RunOutcome {
                runs.incrementAndGet()
                Thread.sleep(50)
                return RunOutcome(RunOutcome.Kind.COMPLETED)
            }
            override fun requestPause(taskId: String) {}
            override fun requestCancel(taskId: String) {}
        }
        val manager = TaskManager(stores, runner)
        val task = manager.createTask(params())
        repeat(5) { manager.dispatch() }
        Assert.assertTrue(waitFor { stores.tasks.get(task.id)?.status == TaskStatus.COMPLETED })
        Assert.assertEquals(1, runs.get())
    }

    @Test fun createTaskRejectsNothingAndPersistsReasoningEffort() = runBlocking {
        val stores = AgentStores.inMemory()
        val manager = TaskManager(stores, ScriptedRunner { RunOutcome(RunOutcome.Kind.COMPLETED) })
        val task = manager.createTask(params(ExecutionMode.PLAN_BUILD))
        val stored = stores.tasks.get(task.id)!!
        Assert.assertEquals(ReasoningEffort.HIGH, stored.mainReasoningEffort)
        Assert.assertEquals(ExecutionMode.PLAN_BUILD, stored.mode)
        Assert.assertTrue(stored.autoResumeOnBoot)
    }
}
