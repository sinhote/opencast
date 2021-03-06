/**
 * Licensed to The Apereo Foundation under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
 *
 *
 * The Apereo Foundation licenses this file to you under the Educational
 * Community License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License
 * at:
 *
 *   http://opensource.org/licenses/ecl2.txt
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations under
 * the License.
 *
 */

package org.opencastproject.workflow.impl;

import static com.entwinemedia.fn.data.ListBuilders.LIA;
import static java.lang.String.format;

import org.opencastproject.job.api.Incident.Severity;
import org.opencastproject.security.api.UnauthorizedException;
import org.opencastproject.util.JobCanceledException;
import org.opencastproject.workflow.api.ResumableWorkflowOperationHandler;
import org.opencastproject.workflow.api.WorkflowException;
import org.opencastproject.workflow.api.WorkflowInstance;
import org.opencastproject.workflow.api.WorkflowOperationException;
import org.opencastproject.workflow.api.WorkflowOperationHandler;
import org.opencastproject.workflow.api.WorkflowOperationInstance;
import org.opencastproject.workflow.api.WorkflowOperationInstance.OperationState;
import org.opencastproject.workflow.api.WorkflowOperationResult;
import org.opencastproject.workflow.api.WorkflowOperationResult.Action;

import com.entwinemedia.fn.bool.Bool;
import com.entwinemedia.fn.parser.Parsers;
import com.entwinemedia.fn.parser.Result;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * Handles execution of a workflow operation.
 */
final class WorkflowOperationWorker {
  private static final Logger logger = LoggerFactory.getLogger(WorkflowOperationWorker.class);

  /**
   * Boolean expression parser that interprets non replaced variable strings of pattern <code>${VAR}</code> as false.
   */
  private static final Bool booleanExpressionEvaluator = new Bool(LIA.mk(Parsers.token(Parsers.regex(Pattern.compile("\\$\\{.*?\\}")))
          .bind(Parsers.ignorePrevious(Parsers.yield(false)))));

  private WorkflowOperationHandler handler = null;
  private WorkflowInstance workflow = null;
  private WorkflowServiceImpl service = null;
  private Map<String, String> properties = null;

  /**
   * Creates a worker that will execute the given handler and thereby the current operation of the workflow instance.
   * When the worker is finished, a callback will be made to the workflow service reporting either success or failure of
   * the current workflow operation.
   *
   * @param handler
   *          the workflow operation handler
   * @param workflow
   *          the workflow instance
   * @param service
   *          the workflow service.
   */
  WorkflowOperationWorker(WorkflowOperationHandler handler, WorkflowInstance workflow,
          WorkflowServiceImpl service) {
    this.handler = handler;
    this.workflow = workflow;
    this.service = service;
  }

  /**
   * Creates a worker that will execute the given handler and thereby the current operation of the workflow instance.
   * When the worker is finished, a callback will be made to the workflow service reporting either success or failure of
   * the current workflow operation.
   *
   * @param handler
   *          the workflow operation handler
   * @param workflow
   *          the workflow instance
   * @param properties
   *          the properties used to execute the operation
   * @param service
   *          the workflow service.
   */
  WorkflowOperationWorker(WorkflowOperationHandler handler, WorkflowInstance workflow,
          Map<String, String> properties, WorkflowServiceImpl service) {
    this(handler, workflow, service);
    this.properties = properties;
  }

  /**
   * Creates a worker that still needs an operation handler to be set. When the worker is finished, a callback will be
   * made to the workflow service reporting either success or failure of the current workflow operation.
   *
   * @param workflow
   *          the workflow instance
   * @param properties
   *          the properties used to execute the operation
   * @param service
   *          the workflow service.
   */
  WorkflowOperationWorker(WorkflowInstance workflow, Map<String, String> properties, WorkflowServiceImpl service) {
    this(null, workflow, service);
    this.properties = properties;
  }

  /**
   * Creates a worker that still needs an operation handler to be set. When the worker is finished, a callback will be
   * made to the workflow service reporting either success or failure of the current workflow operation.
   *
   * @param workflow
   *          the workflow instance
   * @param service
   *          the workflow service.
   */
  WorkflowOperationWorker(WorkflowInstance workflow, WorkflowServiceImpl service) {
    this(null, workflow, service);
  }

  /**
   * Sets the workflow operation handler to use.
   *
   * @param operationHandler
   *          the handler
   */
  public void setHandler(WorkflowOperationHandler operationHandler) {
    this.handler = operationHandler;
  }

  /**
   * Executes the workflow operation logic.
   */
  public WorkflowInstance execute() {
    WorkflowOperationInstance operation = workflow.getCurrentOperation();
    try {
      WorkflowOperationResult result = null;
      switch (operation.getState()) {
        case INSTANTIATED:
        case RETRY:
          result = start();
          break;
        case PAUSED:
          result = resume();
          break;
        default:
          throw new IllegalStateException("Workflow operation '" + operation + "' is in unexpected state '"
                  + operation.getState() + "'");
      }
      if (result == null || Action.CONTINUE.equals(result.getAction()) || Action.SKIP.equals(result.getAction())) {
        if (handler != null) {
          handler.destroy(workflow, null);
        }
      }
      workflow = service.handleOperationResult(workflow, result);
    } catch (JobCanceledException e) {
      logger.info(e.getMessage());
    } catch (Exception e) {
      Throwable t = e.getCause();
      if (t != null) {
        logger.error("Workflow operation '" + operation + "' failed", t);
      } else {
        logger.error("Workflow operation '" + operation + "' failed", e);
      }
      // the associated job shares operation's id
      service.getServiceRegistry().incident().unhandledException(operation.getId(), Severity.FAILURE, e);
      try {
        workflow = service.handleOperationException(workflow, operation);
      } catch (Exception e2) {
        logger.error("Error handling workflow operation '{}' failure: {}", new Object[] { operation, e2.getMessage(),
                e2 });
      }
    }
    return workflow;
  }

  /**
   * Starts executing the workflow operation.
   *
   * @return the workflow operation result
   * @throws WorkflowOperationException
   *           if executing the workflow operation handler fails
   * @throws WorkflowException
   *           if there is a problem processing the workflow
   */
  public WorkflowOperationResult start() throws WorkflowOperationException, WorkflowException, UnauthorizedException {
    final WorkflowOperationInstance operation = workflow.getCurrentOperation();
    // Do we need to execute the operation?
    final String executionCondition = operation.getExecutionCondition(); // if
    final boolean execute;
    if (executionCondition == null) {
      execute = true;
    } else {
      final Result<Boolean> parsed = booleanExpressionEvaluator.eval(executionCondition);
      if (parsed.isDefined() && parsed.getRest().isEmpty()) {
        execute = parsed.getResult();
      } else {
        operation.setState(OperationState.FAILED);
        throw new WorkflowOperationException(format("Unable to parse execution condition '%s'. Result is '%s'",
                executionCondition, parsed.toString()));
      }
    }

    operation.setState(OperationState.RUNNING);
    service.update(workflow);

    try {
      WorkflowOperationResult result = null;
      if (execute) {
        if (handler == null) {
          // If there is no handler for the operation, yet we are supposed to run it, we must fail
          logger.warn("No handler available to execute operation '{}'", operation.getTemplate());
          throw new IllegalStateException("Unable to find a workflow handler for '" + operation.getTemplate() + "'");
        }
        result = handler.start(workflow, null);
      } else {
        // Allow for null handlers when we are skipping an operation
        if (handler != null) {
          result = handler.skip(workflow, null);
          result.setAction(Action.SKIP);
        }
      }
      return result;
    } catch (Exception e) {
      operation.setState(OperationState.FAILED);
      if (e instanceof WorkflowOperationException)
        throw (WorkflowOperationException) e;
      throw new WorkflowOperationException(e);
    }
  }

  /**
   * Resumes a previously suspended workflow operation. Note that only workflow operation handlers that implement
   * {@link ResumableWorkflowOperationHandler} can be resumed.
   *
   * @return the workflow operation result
   * @throws WorkflowOperationException
   *           if executing the workflow operation handler fails
   * @throws WorkflowException
   *           if there is a problem processing the workflow
   * @throws IllegalStateException
   *           if the workflow operation cannot be resumed
   */
  public WorkflowOperationResult resume() throws WorkflowOperationException, WorkflowException, IllegalStateException,
          UnauthorizedException {
    WorkflowOperationInstance operation = workflow.getCurrentOperation();

    // Make sure we have a (suitable) handler
    if (handler == null) {
      // If there is no handler for the operation, yet we are supposed to run it, we must fail
      logger.warn("No handler available to resume operation '{}'", operation.getTemplate());
      throw new IllegalStateException("Unable to find a workflow handler for '" + operation.getTemplate() + "'");
    } else if (!(handler instanceof ResumableWorkflowOperationHandler)) {
      throw new IllegalStateException("An attempt was made to resume a non-resumable operation");
    }

    ResumableWorkflowOperationHandler resumableHandler = (ResumableWorkflowOperationHandler) handler;
    operation.setState(OperationState.RUNNING);
    service.update(workflow);

    try {
      WorkflowOperationResult result = resumableHandler.resume(workflow, null, properties);
      return result;
    } catch (Exception e) {
      operation.setState(OperationState.FAILED);
      if (e instanceof WorkflowOperationException)
        throw (WorkflowOperationException) e;
      throw new WorkflowOperationException(e);
    }
  }
}
