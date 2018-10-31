/* $Id$ */

package org.activiti.app.service.editor;

import java.io.IOException;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.inject.Inject;

import org.activiti.app.domain.editor.AbstractModel;
import org.activiti.app.domain.editor.Model;
import org.activiti.app.domain.editor.ModelHistory;
import org.activiti.app.model.editor.ModelRepresentation;
import org.activiti.app.service.api.DeploymentService;
import org.activiti.app.service.api.ModelService;
import org.activiti.app.service.exception.InternalServerErrorException;
import org.activiti.bpmn.converter.BpmnXMLConverter;
import org.activiti.bpmn.model.BpmnModel;
import org.activiti.bpmn.model.Process;
import org.activiti.editor.language.json.converter.BpmnJsonConverter;
import org.activiti.editor.language.json.converter.util.JsonConverterUtil;
import org.activiti.engine.identity.User;
import org.apache.commons.lang3.StringUtils;
import org.springframework.data.domain.Sort;
import org.springframework.data.domain.Sort.Direction;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 
 */
public abstract class AbstractModelService implements ModelService {

	/** id attribute name */
	protected static final String ID = "id"; //$NON-NLS-1$

	@Inject
	DeploymentService deploymentService;

	@Inject
	ModelImageService modelImageService;

	@Inject
	ObjectMapper objectMapper;

	BpmnJsonConverter bpmnJsonConverter = new BpmnJsonConverter();

	BpmnXMLConverter bpmnXMLConverter = new BpmnXMLConverter();

	@Override
	@Transactional
	public Model createModel(Model pNewModel, User pCreatedBy) {
		pNewModel.setCreated(new Date());
		pNewModel.setCreatedBy(pCreatedBy.getId());
		pNewModel.setLastUpdated(new Date());
		pNewModel.setLastUpdatedBy(pCreatedBy.getId());
		return saveModel(pNewModel);
	}

	@Override
	@Transactional
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
		pModel.setComment(pComment);
		return saveModel(pModel);
	}

	@Override
	public ModelHistory createNewModelVersionAndReturnModelHistory(Model pModel, String pComment, User pUpdatedBy) {
		return createNewModelhistory(createNewModelVersion(pModel, pComment, pUpdatedBy));
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

			editorJsonNode = (ObjectNode) getObjectMapper().readTree(json);

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
			throw new InternalServerErrorException("Could not generate BPMN 2.0 model", e); //$NON-NLS-1$
		}

		return bpmnModel;
	}

	@Override
	public BpmnModel getBpmnModel(AbstractModel pModel, Map<String, Model> pFormMap, Map<String, Model> pDecisionTableMap) {
		try {
			ObjectNode editorJsonNode = (ObjectNode) getObjectMapper().readTree(pModel.getModelEditorJson());
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
			throw new InternalServerErrorException("Could not generate BPMN 2.0 model", e); //$NON-NLS-1$
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
	public List<Model> getModelsByModelType(Integer pModelType) {
		return getModelsByModelType(pModelType, null);
	}

	@Override
	public List<Model> getModelsForUser(User user, Integer modelType) {
		return getModelsForUser(user, modelType, null, new Sort(Direction.ASC, "name")); //$NON-NLS-1$
	}

	@Override
	public List<Model> getModelsForUser(User pUser, Integer pModelType, String pFilter, Sort pSort) {
		return getModelsByModelType(pModelType, pFilter);
	}

	/**
	 * @return the object mapper
	 */
	public ObjectMapper getObjectMapper() {
		if (objectMapper == null) {
			objectMapper = new ObjectMapper();
		}
		return objectMapper;
	}

	@Override
	public List<Model> getReferencedModels(String pModelId) {
		Integer modelType = getModelType(pModelId);
		Set<String> referencedModelIds = null;
		if (modelType.intValue() == AbstractModel.MODEL_TYPE_APP) {
			ObjectNode jsonObject = loadJson(pModelId);
			referencedModelIds = JsonConverterUtil.getAppModelReferencedModelIds(jsonObject);
		} else if (modelType.intValue() == AbstractModel.MODEL_TYPE_BPMN) {
			ObjectNode jsonObject = loadJson(pModelId);
			referencedModelIds = JsonConverterUtil.gatherStringPropertyFromJsonNodes(JsonConverterUtil.filterOutJsonNodes(JsonConverterUtil.getBpmnProcessModelFormReferences(jsonObject)), ID);
			referencedModelIds.addAll(JsonConverterUtil.gatherStringPropertyFromJsonNodes(JsonConverterUtil.filterOutJsonNodes(JsonConverterUtil.getBpmnProcessModelDecisionTableReferences(jsonObject)), ID));
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
	public Model saveModel(Model modelObject, String editorJson, byte[] imageBytes, boolean newVersion, String newVersionComment, User updatedBy) {
		return internalSave(modelObject.getName(), modelObject.getKey(), modelObject.getDescription(), editorJson, newVersion, newVersionComment, imageBytes, updatedBy, modelObject);
	}

	@Override
	public Model saveModel(String modelId, String name, String key, String description, String editorJson, boolean newVersion, String newVersionComment, User updatedBy) {
		Model modelObject = getModel(modelId);
		return internalSave(name, key, description, editorJson, newVersion, newVersionComment, null, updatedBy, modelObject);
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

	/**
	 * @param appDefinitionId app to delete
	 */
	protected void deleteAppDefinition(String appDefinitionId) {
		if (deploymentService != null) {
			deploymentService.deleteAppDefinition(appDefinitionId);
		}
	}

	/**
	 * @param model the model
	 * @param editorJsonNode the json representation
	 */
	protected void generateThumbnailImage(Model model, ObjectNode editorJsonNode) {
		if (modelImageService != null) {
			modelImageService.generateThumbnailImage(model, editorJsonNode);
		}
	}

	/**
	 * get a date from a JSON object
	 * 
	 * @param pJsonObject the object
	 * @param pName the member name
	 * @return the date if contained else null
	 * @throws IOException unparseable value
	 */
	protected Date getDateValue(ObjectNode pJsonObject, String pName) throws IOException {
		JsonNode jn = pJsonObject.get(pName);
		try {
			return jn == null ? null : getObjectMapper().getDeserializationConfig().getDateFormat().parse(jn.textValue());
		}
		catch (ParseException e) {
			throw new IOException("Cound not parse conten of " + pName, e); //$NON-NLS-1$
		}
	}

	/**
	 * get an integer from a JSON object
	 * 
	 * @param pJsonObject the object
	 * @param pName the member name
	 * @return the value or -1 if not contained
	 */
	protected int getIntValue(ObjectNode pJsonObject, String pName) {
		JsonNode jn = pJsonObject.get(pName);
		return jn == null ? -1 : jn.intValue();
	}

	/**
	 * get a text from a JSON object
	 * 
	 * @param pJsonObject the object
	 * @param pName the member name
	 * @return the value or null if not contained
	 */
	protected String getTextValue(ObjectNode pJsonObject, String pName) {
		JsonNode jn = pJsonObject.get(pName);
		return jn == null ? null : jn.textValue();
	}

	/**
	 * put a date to a JSON object if it is not null 
	 * 
	 * @param pJsonObject the object 
	 * @param pName the member name 
	 * @param pValue the value
	 */
	protected void putDateValue(ObjectNode pJsonObject, String pName, Date pValue) {
		if (pValue != null) {
			pJsonObject.put(pName, getObjectMapper().getDeserializationConfig().getDateFormat().format(pValue)); 
		}
	}

	/**
	 * put a text to a JSON object if it is not null or empty 
	 * 
	 * @param pJsonObject the object 
	 * @param pName the member name 
	 * @param pValue the value
	 */
	protected void putTextValue(ObjectNode pJsonObject, String pName, String pValue) {
		if (StringUtils.isNotEmpty(pValue)) {
			pJsonObject.put(pName, pValue);
		}
	}

	Model internalSave(String name, String key, String description, String editorJson, boolean newVersion, String newVersionComment, byte[] imageBytes, User updatedBy, Model modelObject) {
		modelObject.setName(name);
		modelObject.setKey(key);
		modelObject.setDescription(description);
		modelObject.setModelEditorJson(editorJson);
		if(updatedBy != null) {
			modelObject.setLastUpdatedBy(updatedBy.getId());
			modelObject.setLastUpdated(new Date());
		}
		if (imageBytes != null) {
			modelObject.setThumbnail(imageBytes);
		}
		if(newVersion) {
			return createNewModelVersion(modelObject, newVersionComment, updatedBy);
		}
		return saveModel(modelObject);
	}
}
