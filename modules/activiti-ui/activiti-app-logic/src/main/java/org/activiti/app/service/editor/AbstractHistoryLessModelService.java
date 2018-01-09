/* $Id$ */

package org.activiti.app.service.editor;

import java.util.ArrayList;
import java.util.List;

import org.activiti.app.domain.editor.Model;
import org.activiti.app.domain.editor.ModelHistory;
import org.activiti.engine.identity.User;

/**
 * 
 */
public abstract class AbstractHistoryLessModelService extends AbstractModelService {

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

}
