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

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.List;

import org.activiti.app.domain.editor.AbstractModel;
import org.activiti.app.domain.editor.Model;
import org.activiti.app.domain.editor.ModelHistory;
import org.activiti.app.model.editor.ModelKeyRepresentation;
import org.activiti.app.model.editor.ReviveModelResultRepresentation;
import org.activiti.app.service.exception.InternalServerErrorException;
import org.activiti.engine.identity.User;
import org.apache.commons.codec.binary.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Configurable;

import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * A Model service that stores all information in the file system starting from
 * a root directory. No history is provided.
 */
@Configurable
public class FileSystemModelServiceImpl extends AbstractHistoryLessModelService {

	/** default extension */
	protected static final String EXTENSION = ".json"; //$NON-NLS-1$
	/** model type to subpath mapping */
	protected static final String[] MODEL_TYPE_DIR = {"bpmn", "template", "form", "app", "decisiontable", "translation"}; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
	private static final Logger LOGGER = LoggerFactory.getLogger(FileSystemModelServiceImpl.class);

	String encoding = "UTF-8"; //$NON-NLS-1$

	File rootDir;

	/**
	 * @param pRootDir
	 */
	public FileSystemModelServiceImpl(File pRootDir) {
		super();
		rootDir = pRootDir;
	}


	@Override
	public void deleteModel(String pModelId, boolean pCascadeHistory, boolean pDeleteRuntimeApp, String pComment, User pDeletedBy) {
		try {
			// if the model is an app definition and the runtime app needs to be
			// deleted, remove it now
			if (pDeleteRuntimeApp && getModelType(pModelId).intValue() == AbstractModel.MODEL_TYPE_APP) {
				deleteAppDefinition(getModelKey(pModelId));
			}
			deleteFile(pDeletedBy, getFile(pModelId), pComment);
		}
		catch (IOException e) {
			throw new InternalServerErrorException("Could not delete model file"); //$NON-NLS-1$
		}
	}

	@Override
	public Model getModel(String pModelId) {
		try {
			return loadModel(getModelType(pModelId), getModelKey(pModelId), getFile(pModelId));
		}
		catch (IOException e) {
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
	public List<Model> getModelsByModelType(Integer pModelType, String pFilter) {
		final String filter = pFilter == null ? null : pFilter.replace("%", ""); //$NON-NLS-1$ //$NON-NLS-2$
		String typeName = MODEL_TYPE_DIR[pModelType == null ? 0 : pModelType.intValue()];
		File typeDir = new File(rootDir, typeName);
		ArrayList<Model> res = new ArrayList<>();
		if (typeDir.isDirectory()) {
			File[] files = typeDir.listFiles(new FilenameFilter() {

				@Override
				public boolean accept(File pDir, String pName) {
					return pName.endsWith(EXTENSION) && (filter == null || pName.toLowerCase().indexOf(filter) >= 0);
				}

			});

			for (File file : files) {
				String id = getId(file);
				try {
					res.add(loadModel(getModelType(id), getModelKey(id), file));
				}
				catch (Throwable e) {
					LOGGER.warn("Unable to load model file " + id, e); //$NON-NLS-1$
				}
			}
		}

		return res;
	}

	@Override
	public ReviveModelResultRepresentation reviveProcessModelHistory(ModelHistory pModelHistory, User pUser, String pNewVersionComment) {
		ReviveModelResultRepresentation result = new ReviveModelResultRepresentation();
		return result;
	}

	@Override
	public Model saveModel(Model modelObject) {
		ObjectNode modelJson = getObjectMapper().createObjectNode();
		putTextValue(modelJson, "name", modelObject.getName()); //$NON-NLS-1$
		putTextValue(modelJson, "description", modelObject.getDescription()); //$NON-NLS-1$
		setVersion(modelObject, modelJson);
		try {
			File file = getFile(getId(modelObject));
			// Parse json to java
			if (modelObject.getModelEditorJson() != null) {
				ObjectNode jsonNode = (ObjectNode) getObjectMapper().readTree(modelObject.getModelEditorJson());
				modelJson.set("modelEditorJson", jsonNode); //$NON-NLS-1$
				int type = modelObject.getModelType() == null ? AbstractModel.MODEL_TYPE_BPMN : modelObject.getModelType().intValue();
				if (type == AbstractModel.MODEL_TYPE_BPMN) {
					// Thumbnail
					generateThumbnailImage(modelObject, jsonNode);
				}

				if (type != AbstractModel.MODEL_TYPE_APP && type != AbstractModel.MODEL_TYPE_TRANSLATION) {
					putTextValue(jsonNode, "name", modelObject.getName()); //$NON-NLS-1$
					putTextValue(jsonNode, "description", modelObject.getDescription()); //$NON-NLS-1$
				}
			}
			byte[] thumbnail = modelObject.getThumbnail();
			if (thumbnail != null) {
				modelJson.put("thumbnail", Base64.encodeBase64String(thumbnail)); //$NON-NLS-1$
			}

			file.getParentFile().mkdirs();
			OutputStreamWriter os = new OutputStreamWriter(new FileOutputStream(file), getEncoding());
			try {
				getObjectMapper().writerWithDefaultPrettyPrinter().writeValue(os, modelJson);
			}
			finally {
				os.close();
			}
		}
		catch (IOException e) {
			throw new InternalServerErrorException("unable to stoe model " + getId(modelObject)); //$NON-NLS-1$
		}
		return getModel(getId(modelObject));
	}

	/**
	 * @param pEncoding the encoding to set
	 */
	public void setEncoding(String pEncoding) {
		encoding = pEncoding;
	}

	@Override
	public ModelKeyRepresentation validateModelKey(Model pModel, Integer pModelType, String pKey) {
		try {
			ModelKeyRepresentation modelKeyResponse = new ModelKeyRepresentation();
			String id = getId(pModelType, pKey);
			modelKeyResponse.setKey(pKey);
			if (pModel == null || pModel.getKey() == null || !pModel.getKey().equals(pKey)) {
				modelKeyResponse.setKeyAlreadyExists(getFile(id).exists());
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
	 * @param pUser User who deletes
	 * @param pFile the file to delete
	 * @param pComment Deletition comment
	 * @throws IOException
	 */
	protected void deleteFile(User pUser, File pFile, String pComment) throws IOException {
		pFile.delete();
	}

	/**
	 * @return may be we want a different encoding
	 */
	protected String getEncoding() {
		return encoding;
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
	 * 
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
	 * 
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
	 * 
	 * @param pModelId model id
	 * @return the model type
	 */
	@Override
	public Integer getModelType(String pModelId) {
		if (pModelId != null) {
			int pos = pModelId.indexOf('-');
			String type = pModelId.substring(1, pos);
			for (int i = 0; i < MODEL_TYPE_DIR.length; i++) {
				if (MODEL_TYPE_DIR[i].equals(type)) {
					return Integer.valueOf(i);
				}
			}
		}
		return Integer.valueOf(AbstractModel.MODEL_TYPE_BPMN);
	}

	/**
	 * @param pType model type
	 * @return directory to store models of the type
	 * @throws IOException
	 */
	protected File getTypeDir(Integer pType) throws IOException {
		return new File(rootDir, MODEL_TYPE_DIR[pType == null ? 0 : pType.intValue()]);
	}

	/**
	 * @param pModelNode File Content
	 * @param pFile the File
	 * @return the current version. number -1, authenticated user and file time.
	 * @throws IOException
	 */
	protected ModelHistory getVersion(ObjectNode pModelNode, File pFile) throws IOException {
		ModelHistory version = new ModelHistory();
		version.setVersion(-1);
		version.setCreatedBy(getTextValue(pModelNode, "createdBy")); //$NON-NLS-1$
		version.setCreated(getDateValue(pModelNode, "created")); //$NON-NLS-1$
		version.setLastUpdatedBy(getTextValue(pModelNode, "lastUpdatedBy")); //$NON-NLS-1$
		version.setLastUpdated(getDateValue(pModelNode, "lastUpdated")); //$NON-NLS-1$
		version.setComment(getTextValue(pModelNode, "comment")); //$NON-NLS-1$
		return version;
	}

	/**
	 * @param pModel The Model
	 * @param pNewVersion this shall create a new Version
	 * @param pComment Commit Comment
	 * @param pUpdatedBy User
	 * @param pModelJson Model to write
	 */
	protected void setVersion(Model pModel, ObjectNode pModelJson) {
		putTextValue(pModelJson, "createdBy", pModel.getCreatedBy()); //$NON-NLS-1$
		putDateValue(pModelJson, "created", pModel.getCreated()); //$NON-NLS-1$
		putTextValue(pModelJson, "lastUpdatedBy", pModel.getLastUpdatedBy()); //$NON-NLS-1$
		putDateValue(pModelJson, "lastUpdated", pModel.getLastUpdated()); //$NON-NLS-1$
		putTextValue(pModelJson, "comment", pModel.getComment()); //$NON-NLS-1$
	}

	/**
	 * @param pType Model Type
	 * @param pKey Model Key
	 * @param pVersion Model Version
	 * @param pLastUpdatedBy update information
	 * @param pLastUpdated update information
	 * @param pModelFile model file
	 * @return the model
	 * @throws IOException
	 * @throws ParseException
	 */
	protected Model loadModel(Integer pType, String pKey, File pModelFile) throws IOException {
		InputStreamReader is = new InputStreamReader(new FileInputStream(pModelFile), getEncoding());
		try {
			ObjectNode modelNode = (ObjectNode) getObjectMapper().readTree(is);
			ModelHistory version = getVersion(modelNode, pModelFile);
			Model newModel = new Model();
			newModel.setId(getId(pType, pKey));
			newModel.setKey(pKey);
			newModel.setModelType(pType);
			newModel.setVersion(version.getVersion());
			newModel.setLastUpdatedBy(version.getLastUpdatedBy());
			newModel.setLastUpdated(version.getLastUpdated());
			newModel.setComment(version.getComment());
			newModel.setName(getTextValue(modelNode, "name")); //$NON-NLS-1$
			newModel.setDescription(getTextValue(modelNode, "description")); //$NON-NLS-1$
			newModel.setModelEditorJson(getObjectMapper().writeValueAsString(modelNode.get("modelEditorJson"))); //$NON-NLS-1$
			String thumbnail = getTextValue(modelNode, "thumbnail"); //$NON-NLS-1$
			if (thumbnail != null) {
				newModel.setThumbnail(Base64.decodeBase64(thumbnail));
			}
			newModel.setCreatedBy(getTextValue(modelNode, "createdBy")); //$NON-NLS-1$
			newModel.setCreated(getDateValue(modelNode, "created")); //$NON-NLS-1$
			return newModel;
		}
		finally {
			is.close();
		}
	}

	String getHistoryId(AbstractModel pModel) {
		if (pModel.getKey() == null) {
			throw new InternalServerErrorException("Model must have a valid key"); //$NON-NLS-1$
		}
		return getHistoryId(pModel.getModelType(), pModel.getKey(), pModel.getVersion());
	}

	String getId(AbstractModel pModel) {
		if (pModel.getId() == null) {
			if (pModel.getKey() == null) {
				throw new InternalServerErrorException("Model must have a valid key"); //$NON-NLS-1$
			}
			pModel.setId(getId(pModel.getModelType(), pModel.getKey()));
		}
		return pModel.getId();
	}

	String getId(File pFile) {
		String name = pFile.getName();
		String type = pFile.getParentFile().getName();
		return new StringBuilder().append('{').append(type).append('-').append(name.substring(0, name.length() - EXTENSION.length())).append('}').toString();
	}

	@Override
	public ObjectNode loadJson(String pModelId) {
		ObjectNode modelNode;
		try {
			InputStreamReader is = new InputStreamReader(new FileInputStream(getFile(pModelId)), getEncoding());
			try {
				modelNode = (ObjectNode) getObjectMapper().readTree(is);
			}
			finally {
				is.close();
			}
		}
		catch (IOException e) {
			LOGGER.error("Could not load models " + pModelId, e); //$NON-NLS-1$
			throw new InternalServerErrorException("Could load model " + pModelId, e); //$NON-NLS-1$
		}
		return (ObjectNode) modelNode.get("modelEditorJson"); //$NON-NLS-1$
	}
}
