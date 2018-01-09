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
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;
import java.util.UUID;

import org.activiti.app.domain.editor.AbstractModel;
import org.activiti.app.domain.editor.Model;
import org.activiti.app.domain.editor.ModelHistory;
import org.activiti.app.model.editor.ModelKeyRepresentation;
import org.activiti.app.model.editor.ReviveModelResultRepresentation;
import org.activiti.app.service.exception.InternalServerErrorException;
import org.activiti.engine.identity.User;
import org.apache.log4j.Logger;
import org.springframework.beans.factory.annotation.Configurable;

import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * A Model service that stores all information in the file system starting from
 * a root directory. No history is provided.
 */
@Configurable
public class InMemoryModelServiceImpl extends AbstractHistoryLessModelService {

	private static final Logger LOGGER = Logger.getLogger(InMemoryModelServiceImpl.class);

	Hashtable<String, Model> models = new Hashtable<>();

	@Override
	public void deleteModel(String pModelId, boolean pCascadeHistory, boolean pDeleteRuntimeApp, String pComment, User pDeletedBy) {
		models.remove(pModelId);
	}

	@Override
	public Model getModel(String pModelId) {
		return models.get(pModelId);
	}

	@Override
	public Long getModelCountForUser(User pUser, Integer pModelType) {
		long count = 0;
		int requested = pModelType == null ? 0 : pModelType.intValue();
		for (Model model : allModels()) {
			int actual = model.getModelType() == null ? 0 : model.getModelType().intValue();
			if (actual == requested) {
				count++;
			}
		}
		return Long.valueOf(count);
	}

	@Override
	public List<Model> getModelsByModelType(Integer pModelType, String pFilter) {
		final String filter = pFilter == null ? null : pFilter.replace("%", ""); //$NON-NLS-1$ //$NON-NLS-2$
		final List<Model> res = new ArrayList<Model>();
		int requested = pModelType == null ? 0 : pModelType.intValue();
		for (Model model : allModels()) {
			int actual = model.getModelType() == null ? 0 : model.getModelType().intValue();
			if (actual == requested) {
				if (filter == null || (model.getKey() != null && model.getKey().contains(filter)) || (model.getName() != null && model.getName().contains(filter))) {
					res.add(model);
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
		checkId(modelObject);
		models.put(modelObject.getId(), modelObject);
		return modelObject;
	}

	@Override
	public ModelKeyRepresentation validateModelKey(Model pModel, Integer pModelType, String pKey) {
		ModelKeyRepresentation modelKeyResponse = new ModelKeyRepresentation();
		int requested = pModelType == null ? 0 : pModelType.intValue();
		modelKeyResponse.setKey(pKey);
		for (Model model : allModels()) {
			int actual = model.getModelType() == null ? 0 : model.getModelType().intValue();
			if (actual == requested) {
				if ((model.getKey() != null && model.getKey().equals(pKey))) {
					modelKeyResponse.setKeyAlreadyExists(true);
					modelKeyResponse.setId(model.getId());
					modelKeyResponse.setName(model.getName());
				}
			}
		}
		return modelKeyResponse;
	}

	/**
	 * @return all Models
	 */
	protected Iterable<Model> allModels() {
		return models.values();
	}

	@Override
	public Integer getModelType(String pModelId) {
		return getModel(pModelId).getModelType();
	}

	@Override
	public ObjectNode loadJson(String pModelId) {
		try {
			return (ObjectNode) objectMapper.readTree(getModel(pModelId).getModelEditorJson());
		}
		catch (IOException e) {
			LOGGER.error("Could not read model " + pModelId, e); //$NON-NLS-1$
			throw new InternalServerErrorException("Could not read model " + pModelId); //$NON-NLS-1$
		}
	}

	void checkId(AbstractModel pModel) {
		if (pModel.getId() == null) {
			pModel.setId(UUID.randomUUID().toString());
		}
	}
}
