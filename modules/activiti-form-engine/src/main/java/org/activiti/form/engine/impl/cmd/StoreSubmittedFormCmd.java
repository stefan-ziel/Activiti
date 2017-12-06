/*
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at http://www.apache.org/licenses/LICENSE-2.0 Unless required by
 * applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS
 * OF ANY KIND, either express or implied. See the License for the specific
 * language governing permissions and limitations under the License.
 */

package org.activiti.form.engine.impl.cmd;

import java.io.Serializable;
import java.util.Date;
import java.util.List;
import java.util.Map;

import org.activiti.form.api.SubmittedForm;
import org.activiti.form.engine.ActivitiFormException;
import org.activiti.form.engine.impl.interceptor.Command;
import org.activiti.form.engine.impl.interceptor.CommandContext;
import org.activiti.form.engine.impl.persistence.entity.SubmittedFormEntity;
import org.activiti.form.engine.impl.persistence.entity.SubmittedFormEntityManager;
import org.activiti.form.model.FormDefinition;
import org.activiti.form.model.FormField;
import org.activiti.form.model.FormFieldTypes;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * @author Tijs Rademakers
 */
public class StoreSubmittedFormCmd implements Command<SubmittedForm>, Serializable {

	private static final long serialVersionUID = 1L;

	protected FormDefinition formDefinition;
	protected Map<String, Object> variables;
	protected String taskId;
	protected String processInstanceId;

	public StoreSubmittedFormCmd(FormDefinition formDefinition, Map<String, Object> variables, String taskId, String processInstanceId) {
		this.formDefinition = formDefinition;
		this.variables = variables;
		this.taskId = taskId;
		this.processInstanceId = processInstanceId;
	}

	@Override
	public SubmittedForm execute(CommandContext commandContext) {

		if (formDefinition == null || formDefinition.getId() == null) {
			throw new ActivitiFormException("Invalid form definition provided");
		}

		ObjectMapper objectMapper = commandContext.getFormEngineConfiguration().getObjectMapper();
		ObjectNode submittedFormValuesJson = objectMapper.createObjectNode();

		ObjectNode valuesNode = submittedFormValuesJson.putObject("values");

		// Loop over all form fields and see if a value was provided
		Map<String, FormField> fieldMap = formDefinition.allFieldsAsMap();
		for (String fieldId : fieldMap.keySet()) {
			FormField formField = fieldMap.get(fieldId);

			if (FormFieldTypes.EXPRESSION.equals(formField.getType()) || FormFieldTypes.CONTAINER.equals(formField.getType())) {
				continue;
			}

			if (variables.containsKey(fieldId)) {
				Object variableValue = variables.get(fieldId);
				put(objectMapper, valuesNode, fieldId, variableValue);
			}
		}

		// Handle outcome
		String outcomeVariable = null;
		if (formDefinition.getOutcomeVariableName() != null) {
			outcomeVariable = formDefinition.getOutcomeVariableName();
		} else {
			outcomeVariable = "form_" + formDefinition.getKey() + "_outcome";
		}

		if (variables.containsKey(outcomeVariable) && variables.get(outcomeVariable) != null) {
			submittedFormValuesJson.put("activiti_form_outcome", variables.get(outcomeVariable).toString());
		}

		SubmittedFormEntityManager submittedFormEntityManager = commandContext.getSubmittedFormEntityManager();
		SubmittedFormEntity submittedFormEntity = submittedFormEntityManager.create();
		submittedFormEntity.setFormId(formDefinition.getId());
		submittedFormEntity.setTaskId(taskId);
		submittedFormEntity.setProcessInstanceId(processInstanceId);
		submittedFormEntity.setSubmittedDate(new Date());
		try {
			submittedFormEntity.setFormValueBytes(objectMapper.writeValueAsBytes(submittedFormValuesJson));
		}
		catch (Exception e) {
			throw new ActivitiFormException("Error setting form values JSON", e);
		}

		submittedFormEntityManager.insert(submittedFormEntity);

		return submittedFormEntity;
	}

	private void add(ObjectMapper pObjectMapper, ArrayNode pNode, Object pValue) {
		if (pValue == null) {
			pNode.addNull();
		} else if (pValue instanceof Boolean) {
			pNode.add((Boolean) pValue);
		} else if (pValue instanceof Long) {
			pNode.add((Long) pValue);
		} else if (pValue instanceof Double) {
			pNode.add((Double) pValue);
		} else if (pValue instanceof List) {
			ArrayNode anode = pObjectMapper.createArrayNode();
			for (Object v : ((List<?>) pValue)) {
				add(pObjectMapper, anode, v);
			}
			pNode.add(anode);
		} else if (pValue instanceof Map) {
			ObjectNode onode = pObjectMapper.createObjectNode();
			for (Object name : ((Map<?, ?>) pValue).keySet()) {
				put(pObjectMapper, onode, name.toString(), ((Map<?, ?>) pValue).get(name));
			}
			pNode.add(onode);
		} else {
			pNode.add(pValue.toString());
		}
	}

	private void put(ObjectMapper pObjectMapper, ObjectNode pNode, String pName, Object pValue) {
		if (pValue == null) {
			pNode.putNull(pName);
		} else if (pValue instanceof Boolean) {
			pNode.put(pName, (Boolean) pValue);
		} else if (pValue instanceof Long) {
			pNode.put(pName, (Long) pValue);
		} else if (pValue instanceof Double) {
			pNode.put(pName, (Double) pValue);
		} else if (pValue instanceof List) {
			ArrayNode anode = pObjectMapper.createArrayNode();
			for (Object v : ((List<?>) pValue)) {
				add(pObjectMapper, anode, v);
			}
			pNode.set(pName, anode);
		} else if (pValue instanceof Map) {
			ObjectNode onode = pObjectMapper.createObjectNode();
			for (Object name : ((Map<?, ?>) pValue).keySet()) {
				put(pObjectMapper, onode, name.toString(), ((Map<?, ?>) pValue).get(name));
			}
			pNode.set(pName, onode);
		} else {
			pNode.put(pName, pValue.toString());
		}

	}

}
