/*
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at http://www.apache.org/licenses/LICENSE-2.0 Unless required by
 * applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS
 * OF ANY KIND, either express or implied. See the License for the specific
 * language governing permissions and limitations under the License.
 */

package org.activiti.app.service.editor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.activiti.app.domain.editor.AbstractModel;
import org.activiti.app.domain.editor.Model;
import org.activiti.app.domain.editor.ModelHistory;
import org.activiti.app.model.editor.ModelKeyRepresentation;
import org.activiti.app.model.editor.ModelRepresentation;
import org.activiti.app.model.editor.ReviveModelResultRepresentation;
import org.activiti.app.service.api.ModelService;
import org.activiti.bpmn.model.BpmnModel;
import org.activiti.engine.identity.User;
import org.springframework.data.domain.Sort;

import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * A Model service that is based on one or many underlying ModelServices
 */
public class MultiModelService extends AbstractModelService {

	HashMap<String, ModelService> idToServiceMap = new HashMap<>();
	ArrayList<Integer> loadedModelTypes = new ArrayList<>();
	ModelService defaultService;
	ArrayList<ModelService> services = new ArrayList<>();

	/**
	 * Default constructor
	 */
	public MultiModelService() {
		// NOP
	}

	/**
	 * @param pServices the backing services
	 */
	public MultiModelService(ModelService... pServices) {
		for (ModelService s : pServices) {
			if (s != null) {
				if (defaultService == null) {
					defaultService = s;
				}
				services.add(s);
			}
		}
	}

	@Override
	public Model createModel(Model pNewModel, User pCreatedBy) {
		return getServiceForModel(pNewModel).createModel(pNewModel, pCreatedBy);
	}

	@Override
	public Model createModel(ModelRepresentation pModel, String pEditorJson, User pCreatedBy) {
		Model m = new Model();
		m.setId(pModel.getId());
		m.setKey(pModel.getKey());
		m.setModelType(pModel.getModelType());
		m.setName(pModel.getName());
		m.setDescription(pModel.getDescription());
		m.setComment(pModel.getComment());
		return getServiceForModel(m).createModel(pModel, pEditorJson, pCreatedBy);
	}

	@Override
	public Model createNewModelVersion(Model pModel, String pComment, User pUpdatedBy) {
		return getServiceForModel(pModel).createNewModelVersion(pModel, pComment, pUpdatedBy);
	}

	@Override
	public ModelHistory createNewModelVersionAndReturnModelHistory(Model pModel, String pComment, User pUpdatedBy) {
		return getServiceForModel(pModel).createNewModelVersionAndReturnModelHistory(pModel, pComment, pUpdatedBy);
	}

	@Override
	public void deleteModel(String pModelId, boolean pCascadeHistory, boolean pDeleteRuntimeApp, String pComment, User pDeletedBy) {
		getServiceForModelId(pModelId).deleteModel(pModelId, pCascadeHistory, pDeleteRuntimeApp, pComment, pDeletedBy);
	}

	@Override
	public BpmnModel getBpmnModel(AbstractModel pModel) {
		return getServiceForModel(pModel).getBpmnModel(pModel);
	}

	@Override
	public BpmnModel getBpmnModel(AbstractModel pModel, Map<String, Model> pFormMap, Map<String, Model> pDecisionTableMap) {
		return getServiceForModel(pModel).getBpmnModel(pModel, pFormMap, pDecisionTableMap);
	}

	@Override
	public byte[] getBpmnXML(BpmnModel bpmnModel) {
		return getServiceForModelId(null).getBpmnXML(bpmnModel);
	}

	@Override
	public Model getModel(String pModelId) {
		return getServiceForModelId(pModelId).getModel(pModelId);
	}

	@Override
	public Long getModelCountForUser(User pUser, Integer pModelType) {
		long res = 0;
		for (ModelService ms : services()) {
			Long c = ms.getModelCountForUser(pUser, pModelType);
			if (c != null) {
				res += c.longValue();
			}
		}
		return Long.valueOf(res);
	}

	@Override
	public List<ModelHistory> getModelHistory(Model pModel) {
		return getServiceForModel(pModel).getModelHistory(pModel);
	}

	@Override
	public ModelHistory getModelHistory(String pModelHistoryId) {
		return getServiceForModelId(pModelHistoryId).getModelHistory(pModelHistoryId);
	}

	@Override
	public ModelHistory getModelHistory(String pModelId, String pModelHistoryId) {
		return getServiceForModelId(pModelId).getModelHistory(pModelId, pModelHistoryId);
	}

	@Override
	public List<ModelHistory> getModelHistoryForUser(User pUser, Integer pModelType) {
		ArrayList<ModelHistory> res = new ArrayList<>();
		for (ModelService ms : services()) {
			res.addAll(ms.getModelHistoryForUser(pUser, pModelType));
		}
		return res;
	}

	@Override
	public List<Model> getModelsByModelType(Integer pModelType) {
		ArrayList<Model> res = new ArrayList<>();
		for (ModelService ms : services()) {
			addAll(res, ms.getModelsByModelType(pModelType), ms);
		}
		return res;
	}

	@Override
	public List<Model> getModelsByModelType(Integer pModelType, String pFilter) {
		ArrayList<Model> res = new ArrayList<>();
		for (ModelService ms : services()) {
			addAll(res, ms.getModelsByModelType(pModelType, pFilter), ms);
		}
		return res;
	}

	@Override
	public List<Model> getModelsForUser(User pUser, Integer pModelType) {
		ArrayList<Model> res = new ArrayList<>();
		for (ModelService ms : services()) {
			addAll(res, ms.getModelsForUser(pUser, pModelType), ms);
		}
		return res;
	}

	@Override
	public List<Model> getModelsForUser(User pUser, Integer pModelType, String pFilter, Sort pSort) {
		ArrayList<Model> res = new ArrayList<>();
		for (ModelService ms : services()) {
			addAll(res, ms.getModelsForUser(pUser, pModelType, pFilter, pSort), ms);
		}
		return res;
	}

	@Override
	public Integer getModelType(String pModelId) {
		return getServiceForModelId(pModelId).getModelType(pModelId);
	}

	@Override
	public ObjectNode loadJson(String pModelId) {
		return getServiceForModelId(pModelId).loadJson(pModelId);
	}

	@Override
	public ReviveModelResultRepresentation reviveProcessModelHistory(ModelHistory pModelHistory, User pUser, String pNewVersionComment) {
		return getServiceForModel(pModelHistory).reviveProcessModelHistory(pModelHistory, pUser, pNewVersionComment);
	}

	@Override
	public Model saveModel(Model modelObject) {
		return getServiceForModel(modelObject).saveModel(modelObject);
	}

	@Override
	public Model saveModel(Model modelObject, String editorJson, byte[] imageBytes, boolean newVersion, String newVersionComment, User updatedBy) {
		return getServiceForModel(modelObject).saveModel(modelObject, editorJson, imageBytes, newVersion, newVersionComment, updatedBy);
	}

	@Override
	public Model saveModel(String modelId, String name, String key, String description, String editorJson, boolean newVersion, String newVersionComment, User updatedBy) {
		return getServiceForModelId(modelId).saveModel(modelId, name, key, description, editorJson, newVersion, newVersionComment, updatedBy);
	}

	@Override
	public ModelKeyRepresentation validateModelKey(Model pModel, Integer pModelType, String pKey) {
		ModelKeyRepresentation res = null;
		for (ModelService ms : services()) {
			res = ms.validateModelKey(pModel, pModelType, pKey);
			if (res.isKeyAlreadyExists()) {
				return res;
			}
		}
		return res;
	}

	/**
	 * @return the default backing service
	 */
	protected ModelService getDefaultService() {
		if (defaultService == null) {
			defaultService = services().iterator().next();
		}
		return defaultService;
	}

	/**
	 * Gets the appropriate underlying ModelService for a model
	 * 
	 * @param pModel the model must not be null
	 * @return the service for this model
	 */
	protected synchronized ModelService getServiceForModel(AbstractModel pModel) {
		if (pModel.getModelType() == null || pModel.getId() == null) {
			return getServiceForModelId(pModel.getId());
		}

		ModelService service = idToServiceMap.get(pModel.getId());
		if (service == null) {
			if (loadedModelTypes.contains(pModel.getModelType())) {
				// this model type was aleady loaded so fallback needed
				return getDefaultService();
			}
			// load all models of this type and remember the service
			for (ModelService ms : services()) {
				for (Model m : ms.getModelsByModelType(pModel.getModelType())) {
					if (!idToServiceMap.containsKey(m.getId())) {
						idToServiceMap.put(m.getId(), ms);
					}
				}
			}
			loadedModelTypes.add(pModel.getModelType());
			return getServiceForModelId(pModel.getId());
		}
		return service;
	}

	/**
	 * Gets the appropriate underlying ModelService for a model
	 * 
	 * @param pModelId the models id. may be null
	 * @return the service for this model
	 */
	protected synchronized ModelService getServiceForModelId(String pModelId) {
		if (pModelId == null) {
			return getDefaultService();
		}
		ModelService service = idToServiceMap.get(pModelId);
		if (service == null) {
			service = getDefaultService();
			idToServiceMap.put(pModelId, service);
		}
		return service;
	}

	/**
	 * list all underlying model services
	 * 
	 * @return iterable for this purpose
	 */
	protected Iterable<ModelService> services() {
		return services;
	}

	void addAll(List<Model> pAll, List<Model> pSingle, ModelService pService) {
		for (Model m : pSingle) {
			if (!idToServiceMap.containsKey(m.getId())) {
				idToServiceMap.put(m.getId(), pService);
			}
			pAll.add(m);
		}
	}

}
