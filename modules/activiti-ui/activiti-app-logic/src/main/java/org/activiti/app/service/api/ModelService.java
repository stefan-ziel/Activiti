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
package org.activiti.app.service.api;

import java.util.List;
import java.util.Map;

import org.activiti.app.domain.editor.AbstractModel;
import org.activiti.app.domain.editor.Model;
import org.activiti.app.domain.editor.ModelHistory;
import org.activiti.app.model.editor.ModelKeyRepresentation;
import org.activiti.app.model.editor.ModelRepresentation;
import org.activiti.app.model.editor.ReviveModelResultRepresentation;
import org.activiti.bpmn.model.BpmnModel;
import org.activiti.engine.identity.User;
import org.springframework.data.domain.Sort;

public interface ModelService {

  Model getModel(String modelId);

  List<Model> getModelsByModelType(Integer modelType);

  List<Model> getModelsByModelType(Integer modelType, String filter);
  
  ModelKeyRepresentation validateModelKey(Model model, Integer modelType, String key);
  
  ModelHistory getModelHistory(String modelHistoryId);

  ModelHistory getModelHistory(String modelId, String modelHistoryId);
  
  List<ModelHistory> getModelHistory(Model pModel);

  Long getModelCountForUser(User user, Integer modelType);
  
  BpmnModel getBpmnModel(AbstractModel model);

  byte[] getBpmnXML(BpmnModel bpmnMode);
  
  BpmnModel getBpmnModel(AbstractModel model, Map<String, Model> formMap, Map<String, Model> decisionTableMap);

  Model createModel(ModelRepresentation model, String editorJson, User createdBy);
  
  Model createModel(Model newModel, User createdBy);

  Model saveModel(Model modelObject);
  
  Model saveModel(Model modelObject, String editorJson, byte[] imageBytes, boolean newVersion, String newVersionComment, User updatedBy);

  Model saveModel(String modelId, String name, String key, String description, String editorJson, 
      boolean newVersion, String newVersionComment, User updatedBy);

  Model createNewModelVersion(Model modelObject, String comment, User updatedBy);
  
  ModelHistory createNewModelVersionAndReturnModelHistory(Model modelObject, String comment, User updatedBy);

  void deleteModel(String modelId, boolean cascadeHistory, boolean deleteRuntimeApp, String comment, User deletedBy);

  ReviveModelResultRepresentation reviveProcessModelHistory(ModelHistory modelHistory, User user, String newVersionComment);
  
  List<Model> getReferencedModels(String modelId);

  List<Model> getModelsForUser(User user, Integer modelType);

  List<Model> getModelsForUser(User user, Integer modelType, String filter, Sort sort);
  
  List<ModelHistory> getModelHistoryForUser(User user, Integer modelType );

}
