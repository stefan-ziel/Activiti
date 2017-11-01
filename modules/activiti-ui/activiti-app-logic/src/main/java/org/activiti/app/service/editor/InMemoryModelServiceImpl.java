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

import java.io.IOException;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.inject.Inject;

import org.activiti.app.domain.editor.AbstractModel;
import org.activiti.app.domain.editor.Model;
import org.activiti.app.domain.editor.ModelHistory;
import org.activiti.app.model.editor.ModelKeyRepresentation;
import org.activiti.app.model.editor.ModelRepresentation;
import org.activiti.app.model.editor.ReviveModelResultRepresentation;
import org.activiti.app.service.api.DeploymentService;
import org.activiti.app.service.api.ModelService;
import org.activiti.app.service.editor.ModelImageService;
import org.activiti.app.service.exception.InternalServerErrorException;
import org.activiti.bpmn.converter.BpmnXMLConverter;
import org.activiti.bpmn.model.BpmnModel;
import org.activiti.bpmn.model.Process;
import org.activiti.editor.language.json.converter.BpmnJsonConverter;
import org.activiti.editor.language.json.converter.util.JsonConverterUtil;
import org.activiti.engine.identity.User;
import org.apache.commons.lang3.StringUtils;
import org.apache.log4j.Logger;
import org.springframework.beans.factory.annotation.Configurable;
import org.springframework.data.domain.Sort;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * A Model service that stores all information in the file system starting from
 * a root directory. No history is provided.
 */
@Configurable
public class InMemoryModelServiceImpl implements ModelService {
	private static final Logger LOGGER = Logger.getLogger(InMemoryModelServiceImpl.class);
	private static final String ID = "id"; //$NON-NLS-1$

	Hashtable<String,Model> models = new Hashtable<>(); 

	@Inject
	DeploymentService deploymentService;

	@Inject
	ModelImageService modelImageService;
	
	@Inject
	ObjectMapper objectMapper;

	BpmnJsonConverter bpmnJsonConverter = new BpmnJsonConverter();

	BpmnXMLConverter bpmnXMLConverter = new BpmnXMLConverter();


	@Override
	public Model createModel(Model pNewModel, User pCreatedBy) {
		pNewModel.setCreated(new Date());
		pNewModel.setCreatedBy(pCreatedBy.getId());
		return saveModel(pNewModel);
	}

	@Override
	public Model createModel(ModelRepresentation pModel, String pEditorJson, User pCreatedBy) {
		Model newModel = new Model();
		newModel.setId(pModel.getId());
		newModel.setKey(pModel.getKey());
		newModel.setModelType(pModel.getModelType());
		newModel.setName(pModel.getName());
		newModel.setDescription(pModel.getDescription());
		newModel.setModelEditorJson(pEditorJson);
		return createModel(newModel, pCreatedBy);
	}

	@Override
	public Model createNewModelVersion(Model pModel, String pComment, User pUpdatedBy) {
		pModel.setLastUpdated(new Date());
		pModel.setLastUpdatedBy(pUpdatedBy.getId());
		return pModel;
	}

	@Override
	public ModelHistory createNewModelVersionAndReturnModelHistory(Model pModel, String pComment, User pUpdatedBy) {
		pModel.setLastUpdated(new Date());
		pModel.setLastUpdatedBy(pUpdatedBy.getId());
		return createNewModelhistory(pModel);
	}

	@Override
	public void deleteModel(String pModelId, boolean pCascadeHistory, boolean pDeleteRuntimeApp, String pComment, User pDeletedBy) {
		models.remove(pModelId);
	}

	@Override
	public BpmnModel getBpmnModel(AbstractModel pModel) {
		BpmnModel bpmnModel = null;
		try {
			ObjectNode editorJsonNode;
			String json = pModel.getModelEditorJson();
			if (json == null) {
				json = getModel(pModel.getId()).getModelEditorJson();
			} 
			
			editorJsonNode = (ObjectNode) objectMapper.readTree(json);
			

			List<JsonNode> formReferenceNodes = JsonConverterUtil.filterOutJsonNodes(JsonConverterUtil.getBpmnProcessModelFormReferences(editorJsonNode));
			Map<String, Model> formMap = new HashMap<String, Model>();
			for (String id : JsonConverterUtil.gatherStringPropertyFromJsonNodes(formReferenceNodes, ID)) {
				formMap.put(id, getModel(id));
			}

			List<JsonNode> decisionTableNodes = JsonConverterUtil.filterOutJsonNodes(JsonConverterUtil.getBpmnProcessModelDecisionTableReferences(editorJsonNode));
			Map<String, Model> decisionTableMap = new HashMap<String, Model>();
			for (String id : JsonConverterUtil.gatherStringPropertyFromJsonNodes(decisionTableNodes, ID)) {
				decisionTableMap.put(id, getModel(id));
			}

			bpmnModel = getBpmnModel(pModel, formMap, decisionTableMap);
		}
		catch (IOException e) {
			LOGGER.error("Could not generate BPMN 2.0 model for " + pModel.getId(), e); //$NON-NLS-1$
			throw new InternalServerErrorException("Could not generate BPMN 2.0 model"); //$NON-NLS-1$
		}

		return bpmnModel;
	}

	@Override
	public BpmnModel getBpmnModel(AbstractModel pModel, Map<String, Model> pFormMap, Map<String, Model> pDecisionTableMap) {
		try {
			ObjectNode editorJsonNode = (ObjectNode) objectMapper.readTree(pModel.getModelEditorJson());
			Map<String, String> formKeyMap = new HashMap<String, String>();
			for (Model formModel : pFormMap.values()) {
				formKeyMap.put(formModel.getId(), formModel.getKey());
			}

			Map<String, String> decisionTableKeyMap = new HashMap<String, String>();
			for (Model decisionTableModel : pDecisionTableMap.values()) {
				decisionTableKeyMap.put(decisionTableModel.getId(), decisionTableModel.getKey());
			}

			return bpmnJsonConverter.convertToBpmnModel(editorJsonNode, formKeyMap, decisionTableKeyMap);

		}
		catch (IOException e) {
			LOGGER.error("Could not generate BPMN 2.0 model for " + pModel.getId(), e); //$NON-NLS-1$
			throw new InternalServerErrorException("Could not generate BPMN 2.0 model"); //$NON-NLS-1$
		}
	}

	@Override
	public byte[] getBpmnXML(BpmnModel bpmnModel) {
		for (Process process : bpmnModel.getProcesses()) {
			if (StringUtils.isNotEmpty(process.getId())) {
				char firstCharacter = process.getId().charAt(0);
				// no digit is allowed as first character
				if (Character.isDigit(firstCharacter)) {
					process.setId("a" + process.getId()); //$NON-NLS-1$
				}
			}
		}
		byte[] xmlBytes = bpmnXMLConverter.convertToXML(bpmnModel);
		return xmlBytes;
	}

	@Override
	public Model getModel(String pModelId) {
		return models.get(pModelId);
	}

	@Override
	public Long getModelCountForUser(User pUser, Integer pModelType) {
		long count = 0;
		int requested =  pModelType == null ? 0 : pModelType.intValue();
		for(Model model: allModels()){
			int actual =  model.getModelType() == null ? 0 : model.getModelType().intValue();
			if(actual == requested){
				count++;
			}
		}
		return Long.valueOf(count);
	}

	@Override
	public List<ModelHistory> getModelHistory(Model pModel) {
		return new ArrayList<ModelHistory>();
	}

	@Override
	public ModelHistory getModelHistory(String pModelHistoryId) {
		return null;
	}

	@Override
	public ModelHistory getModelHistory(String pModelId, String pModelHistoryId) {
		return null;
	}

	@Override
	public List<ModelHistory> getModelHistoryForUser(User pUser, Integer pModelType) {
		return new ArrayList<ModelHistory>();
	}

	@Override
	public List<Model> getModelsByModelType(Integer pModelType) {
		return getModelsByModelType(pModelType, null);
	}

	@Override
	public List<Model> getModelsByModelType(Integer pModelType, String pFilter) {
		final String filter = pFilter == null ? null : pFilter.replace("%", ""); //$NON-NLS-1$ //$NON-NLS-2$
		final List<Model> res = new ArrayList<Model>();
		int requested =  pModelType == null ? 0 : pModelType.intValue();
		for(Model model: allModels()){
			int actual =  model.getModelType() == null ? 0 : model.getModelType().intValue();
			if(actual == requested){
				if(filter == null || (model.getKey() != null && model.getKey().contains(filter)) || (model.getName() != null && model.getName().contains(filter))){
					res.add(model);
				}
			}
		}
		return res;
	}

	@Override
	public List<Model> getModelsForUser(User pUser, Integer pModelType) {
		return getModelsByModelType(pModelType);
	}

	@Override
	public List<Model> getModelsForUser(User pUser, Integer pModelType, String pFilter, Sort pSort) {
		return getModelsByModelType(pModelType, pFilter);
	}

	@Override
	public List<Model> getReferencedModels(String pModelId) {
		Set<String> referencedModelIds = null;
		try {
			Model model = getModel(pModelId);
			Integer modelType = model.getModelType();
			if (modelType.intValue() == AbstractModel.MODEL_TYPE_APP) {
				ObjectNode jsonObject = (ObjectNode) objectMapper.readTree(model.getModelEditorJson());
				referencedModelIds = JsonConverterUtil.getAppModelReferencedModelIds(jsonObject);
			} else if (modelType.intValue() == AbstractModel.MODEL_TYPE_BPMN) {
				ObjectNode jsonObject = (ObjectNode) objectMapper.readTree(model.getModelEditorJson());
				referencedModelIds = JsonConverterUtil.gatherStringPropertyFromJsonNodes(JsonConverterUtil.filterOutJsonNodes(JsonConverterUtil.getBpmnProcessModelFormReferences(jsonObject)), ID);
				referencedModelIds.addAll(JsonConverterUtil.gatherStringPropertyFromJsonNodes(JsonConverterUtil.filterOutJsonNodes(JsonConverterUtil.getBpmnProcessModelDecisionTableReferences(jsonObject)), ID));
			}
		}
		catch (IOException e) {
			LOGGER.error("Could not read referenced models for " + pModelId, e); //$NON-NLS-1$
			throw new InternalServerErrorException("Could not read referenced models"); //$NON-NLS-1$
		}

			ArrayList<Model> referencedModels = new ArrayList<>();
			if (referencedModelIds != null) {
				for (String id : referencedModelIds) {
					referencedModels.add(getModel(id));
				}
			}
			return referencedModels;
	}

	@Override
	public ReviveModelResultRepresentation reviveProcessModelHistory(ModelHistory pModelHistory, User pUser, String pNewVersionComment) {
		ReviveModelResultRepresentation result = new ReviveModelResultRepresentation();
		return result;
	}

	@Override
	public Model saveModel(Model modelObject) {
		checkId(modelObject);
		models.put(modelObject.getId(), modelObject);
		return modelObject;
	}

	@Override
	public Model saveModel(Model modelObject, String editorJson, byte[] imageBytes, boolean newVersion, String newVersionComment, User updatedBy) {
		return internalSave(modelObject.getName(), modelObject.getKey(), modelObject.getDescription(), editorJson, newVersion, newVersionComment, imageBytes, updatedBy, modelObject);
	}

	@Override
	public Model saveModel(String modelId, String name, String key, String description, String editorJson, boolean newVersion, String newVersionComment, User updatedBy) {
		Model modelObject = getModel(modelId);
		return internalSave(name, key, description, editorJson, newVersion, newVersionComment, null, updatedBy, modelObject);
	}

	@Override
	public ModelKeyRepresentation validateModelKey(Model pModel, Integer pModelType, String pKey) {
			ModelKeyRepresentation modelKeyResponse = new ModelKeyRepresentation();
			int requested =  pModelType == null ? 0 : pModelType.intValue();
			modelKeyResponse.setKey(pKey);
			for(Model model:allModels()){
				int actual =  model.getModelType() == null ? 0 : model.getModelType().intValue();
				if(actual == requested){
					if((model.getKey() != null && model.getKey().equals(pKey))){
						modelKeyResponse.setKeyAlreadyExists(true);
						modelKeyResponse.setId(model.getId());
						modelKeyResponse.setName(model.getName());
					}
				}
			}
			return modelKeyResponse;
	}
	
	protected Iterable<Model> allModels(){
		return models.values();
	}

	/**
	 * a History entry for the current Model Version
	 * 
	 * @param model current Model
	 * @return an equivalent History entry
	 */
	protected ModelHistory createNewModelhistory(Model model) {
		ModelHistory historyModel = new ModelHistory();
		historyModel.setName(model.getName());
		historyModel.setKey(model.getKey());
		historyModel.setDescription(model.getDescription());
		historyModel.setCreated(model.getCreated());
		historyModel.setLastUpdated(model.getLastUpdated());
		historyModel.setCreatedBy(model.getCreatedBy());
		historyModel.setLastUpdatedBy(model.getLastUpdatedBy());
		historyModel.setModelEditorJson(model.getModelEditorJson());
		historyModel.setModelType(model.getModelType());
		historyModel.setVersion(model.getVersion());
		historyModel.setComment(model.getComment());
		historyModel.setModelId(model.getId());
		historyModel.setId(model.getId());
		return historyModel;
	}


	Date getDateValue(ObjectNode pJsonObject, String pName) throws IOException {
		JsonNode jn = pJsonObject.get(pName);
		try {
			return jn == null ? null : objectMapper.getDeserializationConfig().getDateFormat().parse(jn.textValue());
		}
		catch (ParseException e) {
			throw new IOException("Cound not parse conten of " + pName, e); //$NON-NLS-1$
		}
	}

	void checkId(AbstractModel pModel) {
		if (pModel.getId() == null) {
			pModel.setId(UUID.randomUUID().toString());
		}
	}


	int getIntValue(ObjectNode pJsonObject, String pName) {
		JsonNode jn = pJsonObject.get(pName);
		return jn == null ? -1 : jn.intValue();
	}

	String getTextValue(ObjectNode pJsonObject, String pName) {
		JsonNode jn = pJsonObject.get(pName);
		return jn == null ? null : jn.textValue();
	}

	Model internalSave(String name, String key, String description, String editorJson, boolean newVersion, String newVersionComment, byte[] imageBytes, User updatedBy, Model modelObject) {
		modelObject.setName(name);
		modelObject.setKey(key);
		modelObject.setDescription(description);
		modelObject.setModelEditorJson(editorJson);

		if (imageBytes != null) {
			modelObject.setThumbnail(imageBytes);
		}
		return saveModel(modelObject);
	}


	void putTextValue(ObjectNode pJsonObject, String pName, String pValue) {
		if (StringUtils.isNotEmpty(pValue)) {
			pJsonObject.put(pName, pValue);
		}
	}
}
