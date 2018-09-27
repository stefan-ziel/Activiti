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
package org.activiti.app.service.runtime;

import java.util.List;

import org.activiti.app.domain.editor.Model;
import org.activiti.app.service.api.AppDefinitionService;
import org.activiti.app.service.api.DeploymentService;
import org.activiti.app.service.api.ModelService;
import org.activiti.app.service.exception.InternalServerErrorException;
import org.activiti.app.service.util.DeployUtil;
import org.activiti.engine.RepositoryService;
import org.activiti.engine.identity.User;
import org.activiti.engine.repository.Deployment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * @author Tijs Rademakers
 * @author Joram Barrez
 */
@Service
public class DeploymentServiceImpl implements DeploymentService {

  private static final Logger logger = LoggerFactory.getLogger(DeploymentServiceImpl.class);

  @Autowired
  protected AppDefinitionService appDefinitionService;

  @Autowired
  protected ModelService modelService;

  @Autowired
  protected RepositoryService repositoryService;
  
  @Autowired
  protected ObjectMapper objectMapper;
  

  @Override
  @Transactional
  public Deployment updateAppDefinition(Model appDefinitionModel, User user) {
    try {
      return DeployUtil.deploy(appDefinitionModel, modelService, repositoryService, objectMapper);
    } catch (Throwable e) {
      logger.error("Unable to deploy application " + appDefinitionModel.getKey(), e); //$NON-NLS-1$
      throw new InternalServerErrorException("Unable to deploy application " + appDefinitionModel.getKey()); //$NON-NLS-1$
    }
  }

  @Override
  @Transactional
  public void deleteAppDefinition(String appDefinitionId) {
    // First test if deployment is still there, otherwhise the transaction will be rolled back
    List<Deployment> deployments = repositoryService.createDeploymentQuery().deploymentKey(String.valueOf(appDefinitionId)).list();
    if (deployments != null) {
      for (Deployment deployment : deployments) {
        repositoryService.deleteDeployment(deployment.getId(), true);
      }
    }
  }
}
