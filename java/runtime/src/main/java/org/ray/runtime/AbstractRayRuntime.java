package org.ray.runtime;

import com.google.common.collect.ImmutableList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.commons.lang3.tuple.Pair;
import org.ray.api.RayActor;
import org.ray.api.RayObject;
import org.ray.api.WaitResult;
import org.ray.api.function.RayFunc;
import org.ray.api.id.UniqueId;
import org.ray.api.runtime.RayRuntime;
import org.ray.runtime.config.RayConfig;
import org.ray.runtime.functionmanager.FunctionManager;
import org.ray.runtime.functionmanager.RayFunction;
import org.ray.runtime.objectstore.ObjectStoreProxy;
import org.ray.runtime.objectstore.ObjectStoreProxy.GetStatus;
import org.ray.runtime.raylet.RayletClient;
import org.ray.runtime.task.ArgumentsBuilder;
import org.ray.runtime.task.TaskSpec;
import org.ray.runtime.util.ResourceUtil;
import org.ray.runtime.util.UniqueIdHelper;
import org.ray.runtime.util.exception.TaskExecutionException;
import org.ray.runtime.util.logger.RayLog;

/**
 * Core functionality to implement Ray APIs.
 */
public abstract class AbstractRayRuntime implements RayRuntime {

  private static final int GET_TIMEOUT_MS = 1000;
  private static final int FETCH_BATCH_SIZE = 1000;

  protected RayConfig rayConfig;
  protected WorkerContext workerContext;
  protected Worker worker;
  protected RayletClient rayletClient;
  protected ObjectStoreProxy objectStoreProxy;
  protected FunctionManager functionManager;

  /**
   * Actor ID -> local actor instance.
   */
  Map<UniqueId, Object> localActors = new HashMap<>();

  public AbstractRayRuntime(RayConfig rayConfig) {
    this.rayConfig = rayConfig;
    functionManager = new FunctionManager();
    worker = new Worker(this);
    workerContext = new WorkerContext(rayConfig.workerMode, rayConfig.driverId);
  }

  /**
   * Start runtime.
   */
  public abstract void start() throws Exception;

  @Override
  public abstract void shutdown();

  @Override
  public <T> RayObject<T> put(T obj) {
    UniqueId objectId = UniqueIdHelper.computePutId(
        workerContext.getCurrentTask().taskId, workerContext.nextPutIndex());

    put(objectId, obj);
    return new RayObjectImpl<>(objectId);
  }

  public <T> void put(UniqueId objectId, T obj) {
    UniqueId taskId = workerContext.getCurrentTask().taskId;
    RayLog.core.info("Putting object {}, for task {} ", objectId, taskId);
    objectStoreProxy.put(objectId, obj, null);
  }

  @Override
  public <T> T get(UniqueId objectId) throws TaskExecutionException {
    List<T> ret = get(ImmutableList.of(objectId));
    return ret.get(0);
  }

  @Override
  public <T> List<T> get(List<UniqueId> objectIds) {
    boolean wasBlocked = false;
    UniqueId taskId = workerContext.getCurrentTask().taskId;

    try {
      int numObjectIds = objectIds.size();

      // Do an initial fetch for remote objects.
      List<List<UniqueId>> fetchBatches =
          splitIntoBatches(objectIds, FETCH_BATCH_SIZE);
      for (List<UniqueId> batch : fetchBatches) {
        rayletClient.reconstructObjects(batch, true);
      }

      // Get the objects. We initially try to get the objects immediately.
      List<Pair<T, GetStatus>> ret = objectStoreProxy
          .get(objectIds, GET_TIMEOUT_MS, false);
      assert ret.size() == numObjectIds;

      // Mapping the object IDs that we haven't gotten yet to their original index in objectIds.
      Map<UniqueId, Integer> unreadys = new HashMap<>();
      for (int i = 0; i < numObjectIds; i++) {
        if (ret.get(i).getRight() != GetStatus.SUCCESS) {
          unreadys.put(objectIds.get(i), i);
        }
      }
      wasBlocked = (unreadys.size() > 0);

      // Try reconstructing any objects we haven't gotten yet. Try to get them
      // until at least PlasmaLink.GET_TIMEOUT_MS milliseconds passes, then repeat.
      while (unreadys.size() > 0) {
        List<UniqueId> unreadyList = new ArrayList<>(unreadys.keySet());
        List<List<UniqueId>> reconstructBatches =
            splitIntoBatches(unreadyList, FETCH_BATCH_SIZE);

        for (List<UniqueId> batch : reconstructBatches) {
          rayletClient.reconstructObjects(batch, false);
        }

        List<Pair<T, GetStatus>> results = objectStoreProxy
            .get(unreadyList, GET_TIMEOUT_MS, false);

        // Remove any entries for objects we received during this iteration so we
        // don't retrieve the same object twice.
        for (int i = 0; i < results.size(); i++) {
          Pair<T, GetStatus> value = results.get(i);
          if (value.getRight() == GetStatus.SUCCESS) {
            UniqueId id = unreadyList.get(i);
            ret.set(unreadys.get(id), value);
            unreadys.remove(id);
          }
        }
      }

      RayLog.core
          .debug("Task " + taskId + " Objects " + Arrays.toString(objectIds.toArray()) + " get");
      List<T> finalRet = new ArrayList<>();

      for (Pair<T, GetStatus> value : ret) {
        finalRet.add(value.getLeft());
      }

      return finalRet;
    } catch (TaskExecutionException e) {
      RayLog.core.error("Task " + taskId + " Objects " + Arrays.toString(objectIds.toArray())
          + " get with Exception", e);
      throw e;
    } finally {
      // If there were objects that we weren't able to get locally, let the local
      // scheduler know that we're now unblocked.
      if (wasBlocked) {
        rayletClient.notifyUnblocked();
      }
    }
  }

  @Override
  public void free(List<UniqueId> objectIds, boolean localOnly) {
    rayletClient.freePlasmaObjects(objectIds, localOnly);
  }

  private List<List<UniqueId>> splitIntoBatches(List<UniqueId> objectIds, int batchSize) {
    List<List<UniqueId>> batches = new ArrayList<>();
    int objectsSize = objectIds.size();

    for (int i = 0; i < objectsSize; i += batchSize) {
      int endIndex = i + batchSize;
      List<UniqueId> batchIds = (endIndex < objectsSize)
          ? objectIds.subList(i, endIndex)
          : objectIds.subList(i, objectsSize);

      batches.add(batchIds);
    }

    return batches;
  }

  @Override
  public <T> WaitResult<T> wait(List<RayObject<T>> waitList, int numReturns, int timeoutMs) {
    return rayletClient.wait(waitList, numReturns, timeoutMs);
  }

  @Override
  public RayObject call(RayFunc func, Object[] args) {
    TaskSpec spec = createTaskSpec(func, RayActorImpl.NIL, args, false);
    rayletClient.submitTask(spec);
    return new RayObjectImpl(spec.returnIds[0]);
  }

  @Override
  public RayObject call(RayFunc func, RayActor actor, Object[] args) {
    if (!(actor instanceof RayActorImpl)) {
      throw new IllegalArgumentException("Unsupported actor type: " + actor.getClass().getName());
    }
    RayActorImpl actorImpl = (RayActorImpl)actor;
    TaskSpec spec = createTaskSpec(func, actorImpl, args, false);
    spec.getExecutionDependencies().add(((RayActorImpl) actor).getTaskCursor());
    actorImpl.setTaskCursor(spec.returnIds[1]);
    rayletClient.submitTask(spec);
    return new RayObjectImpl(spec.returnIds[0]);
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T> RayActor<T> createActor(RayFunc actorFactoryFunc, Object[] args) {
    TaskSpec spec = createTaskSpec(actorFactoryFunc, RayActorImpl.NIL, args, true);
    RayActorImpl<?> actor = new RayActorImpl(spec.returnIds[0]);
    actor.increaseTaskCounter();
    actor.setTaskCursor(spec.returnIds[0]);
    rayletClient.submitTask(spec);
    return (RayActor<T>) actor;
  }

  /**
   * Generate the return ids of a task.
   */
  private UniqueId[] genReturnIds(UniqueId taskId, int numReturns) {
    UniqueId[] ret = new UniqueId[numReturns];
    for (int i = 0; i < numReturns; i++) {
      ret[i] = UniqueIdHelper.computeReturnId(taskId, i + 1);
    }
    return ret;
  }

  /**
   * Create the task specification.
   * @param func The target remote function.
   * @param actor The actor handle. If the task is not an actor task, actor id must be NIL.
   * @param args The arguments for the remote function.
   * @param isActorCreationTask Whether this task is an actor creation task.
   * @return A TaskSpec object.
   */
  private TaskSpec createTaskSpec(RayFunc func, RayActorImpl actor, Object[] args,
      boolean isActorCreationTask) {
    final TaskSpec current = workerContext.getCurrentTask();
    UniqueId taskId = rayletClient.generateTaskId(current.driverId,
        current.taskId,
        workerContext.nextCallIndex());
    int numReturns = actor.getId().isNil() ? 1 : 2;
    UniqueId[] returnIds = genReturnIds(taskId, numReturns);

    UniqueId actorCreationId = UniqueId.NIL;
    if (isActorCreationTask) {
      actorCreationId = returnIds[0];
    }

    RayFunction rayFunction = functionManager.getFunction(current.driverId, func);
    return new TaskSpec(
        current.driverId,
        taskId,
        current.taskId,
        -1,
        actorCreationId,
        actor.getId(),
        actor.getHandleId(),
        actor.increaseTaskCounter(),
        ArgumentsBuilder.wrap(args),
        returnIds,
        ResourceUtil.getResourcesMapFromArray(rayFunction.getRayRemoteAnnotation()),
        rayFunction.getFunctionDescriptor()
    );
  }

  public void loop() {
    worker.loop();
  }

  public Worker getWorker() {
    return worker;
  }

  public WorkerContext getWorkerContext() {
    return workerContext;
  }

  public RayletClient getRayletClient() {
    return rayletClient;
  }

  public FunctionManager getFunctionManager() {
    return functionManager;
  }
}

