/* Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.activiti5.engine.impl.cmd;

import java.io.Serializable;

import org.activiti.engine.repository.ProcessDefinition;
import org.activiti5.engine.ActivitiException;
import org.activiti5.engine.impl.interceptor.Command;
import org.activiti5.engine.impl.interceptor.CommandContext;
import org.activiti5.engine.impl.persistence.deploy.DeploymentManager;

/**
 * @author Joram Barrez
 */
public abstract class NeedsActiveProcessDefinitionCmd<T> implements Command<T>, Serializable {

  private static final long serialVersionUID = 1L;
  
  protected String processDefinitionId;
  
  public NeedsActiveProcessDefinitionCmd(String processDefinitionId) {
    this.processDefinitionId = processDefinitionId;
  }
  
  public T execute(CommandContext commandContext) {
    DeploymentManager deploymentManager = commandContext.getProcessEngineConfiguration().getDeploymentManager();
    ProcessDefinition processDefinition = deploymentManager.findDeployedProcessDefinitionById(processDefinitionId);

    if (deploymentManager.isProcessDefinitionSuspended(processDefinitionId)) {
      throw new ActivitiException("Cannot execute operation because process definition '" 
              + processDefinition.getName() + "' (id=" + processDefinition.getId() + ") is supended");
    }
    
    return execute(commandContext, processDefinition);
  }
  
  /**
   * Subclasses should implement this. The provided {@link ProcessDefinition} is 
   * guaranteed to be an active process definition (ie. not suspended).
   */
  protected abstract T execute(CommandContext commandContext, ProcessDefinition processDefinition);

}
