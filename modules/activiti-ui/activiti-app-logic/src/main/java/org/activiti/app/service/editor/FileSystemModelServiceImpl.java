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
package org.activiti.app.service.editor;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
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
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.lang3.StringUtils;
import org.apache.log4j.Logger;
import org.springframework.data.domain.Sort;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;


/**
 * A Model service that stores all information in the file system starting from a root directory. 
 * No history is provided. 
 */
public class FileSystemModelServiceImpl implements ModelService {

	/** JSON-format for dates */
	protected static final SimpleDateFormat JSON_DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd'T'hh:mm:ss.SSS"); //$NON-NLS-1$
	/** default extension */
	protected static final String EXTENSION = ".json"; //$NON-NLS-1$
	/** model type to subpath mapping */
	protected static final String[] MODEL_TYPE_DIR = {"bpmn", "unknown", "form", "app", "decisiontable"}; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
	private static final String ID = "id"; //$NON-NLS-1$
	private static final Logger LOGGER = Logger.getLogger(FileSystemModelServiceImpl.class);

	File rootDir;

	@Inject
	DeploymentService deploymentService;

	@Inject
	ModelImageService modelImageService;

	@Inject
	ObjectMapper objectMapper;

	BpmnJsonConverter bpmnJsonConverter = new BpmnJsonConverter();

	BpmnXMLConverter bpmnXMLConverter = new BpmnXMLConverter();

	/**
	 * @param pRootDir
	 */
	public FileSystemModelServiceImpl(File pRootDir) {
		super();
		rootDir = pRootDir;
	}

	@Override
	public Model createModel(Model pNewModel, User pCreatedBy) {
		persistModel(pNewModel, false, null, pCreatedBy);
		return pNewModel;
	}

	@Override
	public Model createModel(ModelRepresentation pModel, String pEditorJson, User pCreatedBy) {
		Model newModel = new Model();
		newModel.setName(pModel.getName());
		newModel.setKey(pModel.getKey());
		newModel.setModelType(pModel.getModelType());
		newModel.setDescription(pModel.getDescription());
		newModel.setModelEditorJson(pEditorJson);
		newModel.setCreated(new Date());
		newModel.setCreatedBy(pCreatedBy.getId());
		persistModel(newModel, false, null, pCreatedBy);
		return newModel;
	}

	@Override
	public Model createNewModelVersion(Model pModel, String pComment, User pUpdatedBy) {
		persistModel(pModel, true, pComment, pUpdatedBy);
		return pModel;
	}

	@Override
	public ModelHistory createNewModelVersionAndReturnModelHistory(Model pModel, String pComment, User pUpdatedBy) {
		persistModel(pModel, true, pComment, pUpdatedBy);
		return createNewModelhistory(pModel);
	}

	@Override
	public void deleteModel(String pModelId, boolean pCascadeHistory, boolean pDeleteRuntimeApp) {
		try {
			// if the model is an app definition and the runtime app needs to be
			// deleted, remove it now
			if (pDeleteRuntimeApp && getModelType(pModelId) == AbstractModel.MODEL_TYPE_APP) {
				deploymentService.deleteAppDefinition(getModelKey(pModelId));
			}
			deleteFile(getFile(pModelId));
		}
		catch (IOException e) {
			throw new InternalServerErrorException("Could not delete model file"); //$NON-NLS-1$
		}
	}

	@Override
	public BpmnModel getBpmnModel(AbstractModel pModel) {
		BpmnModel bpmnModel = null;
		try {
			ObjectNode editorJsonNode;
			String json = pModel.getModelEditorJson();
			if (json == null) {
				editorJsonNode = loadJson(pModel.getId());
			} else {
				editorJsonNode = (ObjectNode) objectMapper.readTree(json);
			}

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
		try {
			return loadModel(pModelId);
		}
		catch (IOException | ParseException e) {
			LOGGER.error("Could not open model for " + pModelId, e); //$NON-NLS-1$
			throw new InternalServerErrorException("Could not open model for " + pModelId); //$NON-NLS-1$
		}
	}

	@Override
	public Long getModelCountForUser(User pUser, Integer pModelType) {
		File typeDir = new File(rootDir, MODEL_TYPE_DIR[pModelType == null ? 0 : pModelType.intValue()]);
		if (typeDir.isDirectory()) {
			return Long.valueOf(typeDir.list(new FilenameFilter() {

				@Override
				public boolean accept(File pDir, String pName) {
					return pName.endsWith(EXTENSION);
				}

			}).length);
		}
		return null;
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
		try {
			final String filter = pFilter == null ? null : pFilter.replace("%", ""); //$NON-NLS-1$ //$NON-NLS-2$
			String typeName = MODEL_TYPE_DIR[pModelType == null ? 0 : pModelType.intValue()];
			File typeDir = new File(rootDir, typeName);
			ArrayList<Model> res = new ArrayList<>();
			if (typeDir.isDirectory()) {
				File[] files = typeDir.listFiles(new FilenameFilter() {

					@Override
					public boolean accept(File pDir, String pName) {
						return pName.endsWith(EXTENSION) && (filter == null || pName.toLowerCase().indexOf(filter) > 0);
					}

				});

				for (File file : files) {
					res.add(loadModel(file));
				}
			}

			return res;
		}
		catch (IOException | ParseException e) {
			LOGGER.error("Could not list models for " + pModelType, e); //$NON-NLS-1$
			throw new InternalServerErrorException("Could not list models for " + pModelType, e); //$NON-NLS-1$
		}
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
		try {
			int modelType = getModelType(pModelId);
			Set<String> referencedModelIds = null;
			if (modelType == AbstractModel.MODEL_TYPE_APP) {
				ObjectNode jsonObject = loadJson(pModelId);
				referencedModelIds = JsonConverterUtil.getAppModelReferencedModelIds(jsonObject);
			} else if (modelType == AbstractModel.MODEL_TYPE_BPMN) {
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
		catch (IOException e) {
			LOGGER.error("Could not list referenced models for " + pModelId, e); //$NON-NLS-1$
			throw new InternalServerErrorException("Could not list referenced models for " + pModelId, e); //$NON-NLS-1$
		}
	}

	@Override
	public ReviveModelResultRepresentation reviveProcessModelHistory(ModelHistory pModelHistory, User pUser, String pNewVersionComment) {
		ReviveModelResultRepresentation result = new ReviveModelResultRepresentation();
		return result;
	}

	@Override
	public Model saveModel(Model modelObject) {
		return persistModel(modelObject, false, null, null);
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
		try {
			ModelKeyRepresentation modelKeyResponse = new ModelKeyRepresentation();
			String id = getId(pModelType, pKey);
			modelKeyResponse.setKey(pKey);
			if (pModel == null || pModel.getKey() == null || !pModel.getKey().equals(pKey)) {
				modelKeyResponse.setKeyAlreadyExists(getFile(getId(pModelType, pKey)).exists());
				modelKeyResponse.setId(id);
				modelKeyResponse.setName(pKey);
			}
			return modelKeyResponse;
		}
		catch (IOException e) {
			LOGGER.error("Could not validate key " + pModelType + ' ' + pKey, e); //$NON-NLS-1$
			throw new InternalServerErrorException("Could not validate key " + pModelType + ' ' + pKey, e); //$NON-NLS-1$
		}
	}

	/**
	 * @param pFile the file to delete
	 * @throws IOException
	 */
	protected void deleteFile(File pFile) throws IOException {
		pFile.delete();
	}

	/**
	 * @param pModelId models id  
	 * @return the models file
	 * @throws IOException
	 */
	protected File getFile(String pModelId) throws IOException {
		int pos = pModelId.indexOf('-');
		return new File(new File(rootDir, pModelId.substring(1, pos)), pModelId.substring(pos + 1, pModelId.length() - 1) + EXTENSION);
	}

	/**
	 * build a history id
	 * @param pType model type
	 * @param pKey model key
	 * @param pVersion model version
	 * @return the history id
	 */
	protected String getHistoryId(Integer pType, String pKey, int pVersion) {
		return new StringBuilder().append('{').append(MODEL_TYPE_DIR[pType == null ? 0 : pType.intValue()]).append('-').append(pKey).append('-').append(pVersion).append('}').toString();
	}

	/**
	 * build an id
	 * 
	 * @param pType model type
	 * @param pKey model key
	 * @return the model id
	 */
	protected String getId(Integer pType, String pKey) {
		return new StringBuilder().append('{').append(MODEL_TYPE_DIR[pType == null ? 0 : pType.intValue()]).append('-').append(pKey).append('}').toString();
	}

	/**
	 * extract the model key from its id
	 * @param pModelId model id
	 * @return the key
	 */
	protected String getModelKey(String pModelId) {
		if (pModelId == null) {
			return null;
		}
		int pos = pModelId.indexOf('-');
		return pModelId.substring(pos + 1, pModelId.length() - 1);
	}

	/**
	 * extract the model type from its id
	 * @param pModelId model id 
	 * @return the model type
	 */
	protected int getModelType(String pModelId) {
		if (pModelId != null) {
			int pos = pModelId.indexOf('-');
			String type = pModelId.substring(1, pos);
			for (int i = 0; i < MODEL_TYPE_DIR.length; i++) {
				if (MODEL_TYPE_DIR[i].equals(type)) {
					return i;
				}
			}
		}
		return AbstractModel.MODEL_TYPE_BPMN;
	}

	/**
	 * @param pType model type
	 * @return directory to store models of thei type
	 * @throws IOException
	 */
	protected File getTypeDir(Integer pType) throws IOException {
		return new File(rootDir, MODEL_TYPE_DIR[pType == null ? 0 : pType.intValue()]);
	}

	/**
	 * @param pModelId model id
	 * @param pModelFile model file
	 * @return the model
	 * @throws IOException
	 * @throws ParseException
	 */
	protected Model loadModel(String pModelId, File pModelFile) throws IOException, ParseException {
		ObjectNode modelNode = (ObjectNode) objectMapper.readTree(pModelFile);
		Model newModel = new Model();
		newModel.setId(pModelId);
		newModel.setName(getTextValue(modelNode, "name")); //$NON-NLS-1$
		newModel.setKey(getModelKey(pModelId));
		newModel.setModelType(Integer.valueOf(getModelType(pModelId)));
		newModel.setDescription(getTextValue(modelNode, "description")); //$NON-NLS-1$
		newModel.setModelEditorJson(objectMapper.writeValueAsString(modelNode.get("modelEditorJson"))); //$NON-NLS-1$
		String thumbnail = getTextValue(modelNode, "thumbnail"); //$NON-NLS-1$
		if (thumbnail != null) {
			newModel.setThumbnail(Base64.decodeBase64(thumbnail));
		}
		newModel.setVersion(getIntValue(modelNode, "version")); //$NON-NLS-1$
		newModel.setCreatedBy(getTextValue(modelNode, "createdBy")); //$NON-NLS-1$
		newModel.setCreated(getDateValue(modelNode, "created")); //$NON-NLS-1$
		newModel.setLastUpdatedBy(getTextValue(modelNode, "lastUpdatedBy")); //$NON-NLS-1$
		newModel.setLastUpdated(getDateValue(modelNode, "lastUpdated")); //$NON-NLS-1$
		return newModel;
	}

	/**
	 * @param pModel The Model
	 * @param pNewVersion Create new Repository version
	 * @param pComment Commit Comment
	 * @param pUpdatedBy User updating
	 * @return updated Model
	 */
	protected Model persistModel(Model pModel, boolean pNewVersion, String pComment, User pUpdatedBy) {
		try {
			ObjectNode modelJson = objectMapper.createObjectNode();
			modelJson.put("name", pModel.getName()); //$NON-NLS-1$
			modelJson.put("description", pModel.getDescription()); //$NON-NLS-1$
			modelJson.put("version", pModel.getVersion()); //$NON-NLS-1$
			modelJson.put("createdBy", pModel.getCreatedBy() == null ? pUpdatedBy.getId() : pModel.getCreatedBy()); //$NON-NLS-1$
			modelJson.put("created", JSON_DATE_FORMAT.format(pModel.getCreated() == null ? new Date() : pModel.getCreated())); //$NON-NLS-1$
			modelJson.put("lastUpdatedBy", pUpdatedBy.getId()); //$NON-NLS-1$
			modelJson.put("lastUpdated", JSON_DATE_FORMAT.format(new Date())); //$NON-NLS-1$

			File file = getFile(pModel.getId());
			// Parse json to java
			if (pModel.getModelEditorJson() != null) {
				ObjectNode jsonNode = (ObjectNode) objectMapper.readTree(pModel.getModelEditorJson());
				modelJson.put("modelEditorJson", jsonNode); //$NON-NLS-1$
				int type = pModel.getModelType() == null ? AbstractModel.MODEL_TYPE_BPMN : pModel.getModelType().intValue();
				if (type == AbstractModel.MODEL_TYPE_BPMN) {
					// Thumbnail
					modelImageService.generateThumbnailImage(pModel, jsonNode);
				}

				if (type != AbstractModel.MODEL_TYPE_APP) {
					jsonNode.put("name", pModel.getName()); //$NON-NLS-1$
					jsonNode.put("description", pModel.getDescription()); //$NON-NLS-1$
				}
			}
			byte[] thumbnail = pModel.getThumbnail();
			if (thumbnail != null) {
				modelJson.put("thumbnail", Base64.encodeBase64String(thumbnail)); //$NON-NLS-1$
			}

			file.getParentFile().mkdirs();
			FileOutputStream os = new FileOutputStream(file);
			try {
				objectMapper.writerWithDefaultPrettyPrinter().writeValue(os, modelJson);
			}
			finally {
				os.close();
			}
		}
		catch (Exception e) {
			LOGGER.error("Could not deserialize json model", e); //$NON-NLS-1$
			throw new InternalServerErrorException("Could not deserialize json model"); //$NON-NLS-1$
		}
		return pModel;
	}

	ModelHistory createNewModelhistory(Model model) {
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
		historyModel.setId(getHistoryId(model));
		return historyModel;
	}

	Date getDateValue(ObjectNode pJsonObject, String pName) throws ParseException {
		JsonNode jn = pJsonObject.get(pName);
		return jn == null ? null : JSON_DATE_FORMAT.parse(jn.textValue());
	}

	String getHistoryId(AbstractModel pModel) {
		if (pModel.getKey() == null) {
			throw new InternalServerErrorException("Model must have a valid key"); //$NON-NLS-1$
		}
		return getHistoryId(pModel.getModelType(), pModel.getKey(), pModel.getVersion());
	}

	String getId(AbstractModel pModel) {
		if (pModel.getKey() == null) {
			throw new InternalServerErrorException("Model must have a valid key"); //$NON-NLS-1$
		}
		return getId(pModel.getModelType(), pModel.getKey());
	}

	String getId(File pFile) {
		String name = pFile.getName();
		String type = pFile.getParentFile().getName();
		return new StringBuilder().append('{').append(type).append('-').append(name.substring(0, name.length() - EXTENSION.length())).append('}').toString();
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
		modelObject.setLastUpdated(new Date());
		modelObject.setLastUpdatedBy(updatedBy.getId());
		modelObject.setName(name);
		modelObject.setKey(key);
		modelObject.setDescription(description);
		modelObject.setModelEditorJson(editorJson);

		if (imageBytes != null) {
			modelObject.setThumbnail(imageBytes);
		}
		return persistModel(modelObject, newVersion, newVersionComment, updatedBy);
	}

	ObjectNode loadJson(String pModelId) throws IOException {
		ObjectNode modelNode = (ObjectNode) objectMapper.readTree(getFile(pModelId));
		return (ObjectNode) modelNode.get("modelEditorJson"); //$NON-NLS-1$
	}

	Model loadModel(File pModelFile) throws IOException, ParseException {
		return loadModel(getId(pModelFile), pModelFile);
	}

	Model loadModel(String pModelId) throws IOException, ParseException {
		return loadModel(pModelId, getFile(pModelId));
	}

	void putTextValue(ObjectNode pJsonObject, String pName, String pValue) {
		if (StringUtils.isNotEmpty(pValue)) {
			pJsonObject.put(pName, pValue);
		}
	}

}
